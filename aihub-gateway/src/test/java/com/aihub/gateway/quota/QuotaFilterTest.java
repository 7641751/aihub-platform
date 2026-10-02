package com.aihub.gateway.quota;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.QuotaDescriptor;
import com.aihub.common.quota.QuotaDecision;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.gateway.auth.ApiKeyAuthFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配额过滤器是「429 {@code insufficient_quota} 长什么样、Redis 挂了怎么办、脚本坏了怎么区分」的
 * 唯一落点。本类**只**覆盖过滤器自己那一层（判定怎么变成响应、降级怎么计数、缓存怎么透传字节）：
 * 它自己 {@code new} 过滤器、自己塞 exchange 属性，因此对它而言「过滤器有没有被 Spring 装进链上」
 * 是无意义的 —— 那条由 {@code QuotaDegradeTest}（真实 HTTP + 真实过滤器链）证明。
 *
 * <p>必须钉住的四条规则：
 * <ol>
 *   <li><b>超限是 429 + 数据面契约</b>（D6）：{@code code = type = "insufficient_quota"}，且
 *       **不含** {@code rate_limit_exceeded}（与限流区分开）；</li>
 *   <li><b>降级 ≠ 放行全部，也 ≠ 拒绝全部</b>：配额是**记账**，Redis 不可用 → 放行 + {@code degraded} 计数
 *       （与限流的「降级仍拒绝」刻意相反，D7）；</li>
 *   <li><b>两种「没生效」分开计数</b>（F6 / E.4-(a)）：脚本形状异常落 {@code script_error}，
 *       {@code degraded} 必须保持 0 —— 合成一个计数器会让「Lua 写错了」永远藏在「Redis 挂了」后面；</li>
 *   <li><b>缓存请求体不得改变转发字节</b>（D17 + M1 铁律）：过滤器的下游链读到的必须是**逐字节**原样
 *       的请求体（本用例直接用含 {@code NUL}/{@code 0xFF}/{@code 0xED} 的原始字节比较）。</li>
 * </ol>
 */
class QuotaFilterTest {

    private static final ApiKeyView VIEW =
            new ApiKeyView("ak_demo", 7L, "demo", ApiKeyView.STATUS_ACTIVE, null, 42L);

    private static final String BODY = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\","
            + "\"content\":\"你好世界\"}]}";

    private static final QuotaConfigProperties ENABLED =
            new QuotaConfigProperties(true, 65536, Duration.ofMinutes(2), 1_000);

    // --- 429 的形状（D6 / 13b 裁定 4） -------------------------------------------------

    @Test
    void anOverBudgetRequestIsRejectedWithInsufficientQuotaNotRateLimitExceeded() {
        StubLimiter limiter = new StubLimiter();
        limiter.decision = new QuotaDecision(false, 0L, -1L);
        MockServerWebExchange exchange = exchange("/v1/chat/completions", BODY, true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(resolver(7L, 100L, 0L), limiter, new SimpleMeterRegistry())
                .filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).as("超限不得进入下游链").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(bodyOf(exchange))
                .contains("\"code\":\"insufficient_quota\"")
                .contains("\"type\":\"insufficient_quota\"")
                .contains("\"param\":null")
                .doesNotContain("rate_limit_exceeded")
                .doesNotContain("rate_limit_error");
    }

    // --- 放行 + 预扣关联 ---------------------------------------------------------------

    @Test
    void aWithinBudgetRequestIsForwardedAndTheReservationIsRecorded() {
        StubLimiter limiter = new StubLimiter();
        limiter.decision = new QuotaDecision(true, 90L, -1L);
        MockServerWebExchange exchange = exchange("/v1/chat/completions", BODY, true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(resolver(7L, 100L, 0L), limiter, new SimpleMeterRegistry())
                .filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).as("未超限必须放行").isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(limiter.calls).isEqualTo(1);
        assertThat(limiter.lastLimits.tokenLimit()).isEqualTo(100L);
        assertThat(limiter.lastEstimated).as("估算 = prompt + max_tokens，必为正").isPositive();

        Object attr = exchange.getAttributes().get(QuotaFilter.ATTRIBUTE_RESERVATION);
        assertThat(attr).isInstanceOf(QuotaReservationRegistry.QuotaReservation.class);
        QuotaReservationRegistry.QuotaReservation reservation =
                (QuotaReservationRegistry.QuotaReservation) attr;
        assertThat(reservation.tenantId()).isEqualTo(7L);
        assertThat(reservation.period()).isEqualTo(QuotaPeriod.of(System.currentTimeMillis()));
        assertThat(reservation.estimatedTokens()).isEqualTo(limiter.lastEstimated);
        assertThat(reservation.degraded()).as("真实预扣：degraded=false ⇒ 收尾要校正").isFalse();
    }

    @Test
    void anUnlimitedTenantDoesNotCallTheLimiter() {
        StubLimiter limiter = new StubLimiter();
        MockServerWebExchange exchange = exchange("/v1/chat/completions", BODY, true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        // 快照里没有 (7, period) 的额度行 ⇒ 不限（D15）：不预扣、不调 limiter。
        filter(new QuotaResolver(ConfigSnapshot::empty), limiter, new SimpleMeterRegistry())
                .filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNotNull();
        assertThat(limiter.calls).isZero();
        QuotaReservationRegistry.QuotaReservation reservation = (QuotaReservationRegistry.QuotaReservation)
                exchange.getAttributes().get(QuotaFilter.ATTRIBUTE_RESERVATION);
        assertThat(reservation).isNotNull();
        assertThat(reservation.degraded()).as("不限 = 没有预扣 ⇒ 校正器跳过").isTrue();
    }

    @Test
    void noApiKeyViewUsesTheAnonymousTenantZero() {
        StubLimiter limiter = new StubLimiter();
        limiter.decision = new QuotaDecision(true, 90L, -1L);
        MockServerWebExchange exchange = exchange("/v1/chat/completions", BODY, false);

        filter(resolver(0L, 100L, 0L), limiter, new SimpleMeterRegistry())
                .filter(exchange, chain(new AtomicReference<>())).block(Duration.ofSeconds(5));

        assertThat(limiter.calls).isEqualTo(1);
        QuotaReservationRegistry.QuotaReservation reservation = (QuotaReservationRegistry.QuotaReservation)
                exchange.getAttributes().get(QuotaFilter.ATTRIBUTE_RESERVATION);
        assertThat(reservation.tenantId()).isZero();
    }

    // --- 早退 ------------------------------------------------------------------------

    @Test
    void skipsWhenDisabled() {
        StubLimiter limiter = new StubLimiter();
        MockServerWebExchange exchange = exchange("/v1/chat/completions", BODY, true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        new QuotaFilter(resolver(7L, 100L, 0L), limiter, registry(),
                new QuotaConfigProperties(false, 65536, Duration.ofMinutes(2), 1_000), new SimpleMeterRegistry())
                .filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNotNull();
        assertThat(limiter.calls).isZero();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void skipsNonChatCompletionsPaths() {
        StubLimiter limiter = new StubLimiter();
        MockServerWebExchange models = exchange("/v1/models", BODY, true);
        MockServerWebExchange health = exchange("/healthz", BODY, true);
        AtomicReference<ServerWebExchange> passedModels = new AtomicReference<>();
        AtomicReference<ServerWebExchange> passedHealth = new AtomicReference<>();

        QuotaFilter filter = filter(resolver(7L, 100L, 0L), limiter, new SimpleMeterRegistry());
        filter.filter(models, chain(passedModels)).block(Duration.ofSeconds(5));
        filter.filter(health, chain(passedHealth)).block(Duration.ofSeconds(5));

        assertThat(passedModels.get()).as("/v1/models 不计费（A13）").isNotNull();
        assertThat(passedHealth.get()).isNotNull();
        assertThat(limiter.calls).isZero();
    }

    @Test
    void filterOrderIsAfterAuthenticationAndRateLimit() {
        Order order = QuotaFilter.class.getAnnotation(Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 150);
        assertThat(ApiKeyAuthFilter.class.getAnnotation(Order.class).value())
                .as("鉴权必须先跑，配额才拿得到 tenant").isLessThan(order.value());
        assertThat(com.aihub.gateway.ratelimit.RateLimitFilter.class.getAnnotation(Order.class).value())
                .as("限流必须先跑（保护上游优先于记账）").isLessThan(order.value());
    }

    // --- 字节透传（D17 + M1 铁律） ------------------------------------------------------

    @Test
    void theCachedBodyStillReachesTheDownstreamChainByteForByte() {
        StubLimiter limiter = new StubLimiter();
        limiter.decision = new QuotaDecision(true, 90L, -1L);
        // 含非 ASCII、CRLF、NUL、0xFF、一个非法 UTF-8 序列（0xED 0xA0 0x80）：任何字节丢失/重排都逃不掉。
        byte[] sent = new byte[] {'{', 0x00, (byte) 0xFF, (byte) 0xED, (byte) 0xA0, (byte) 0x80,
                '\r', '\n', ' ', 'x', '}'};
        MockServerWebExchange exchange = exchangeBytes("/v1/chat/completions", sent, true);

        AtomicReference<byte[]> received = new AtomicReference<>();
        WebFilterChain capturingChain = ex -> DataBufferUtils.join(ex.getRequest().getBody())
                .map(buffer -> {
                    byte[] copy = new byte[buffer.readableByteCount()];
                    buffer.read(copy);
                    DataBufferUtils.release(buffer);
                    return copy;
                })
                .doOnNext(received::set)
                .then();

        filter(resolver(7L, 100L, 0L), limiter, new SimpleMeterRegistry())
                .filter(exchange, capturingChain).block(Duration.ofSeconds(5));

        assertThat(received.get())
                .as("缓存请求体后，下游必须收到**逐字节**原样的 body（释放了还传原 exchange 会让它是空的）")
                .isEqualTo(sent);
    }

    // --- 降级 / 形状异常 / 自身故障（分开计数） -----------------------------------------

    @Test
    void redisUnavailableFailsOpenAndCountsDegrade() {
        StubLimiter limiter = new StubLimiter();
        limiter.decision = null;                     // = Redis 这一级不可用
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockServerWebExchange exchange = exchange("/v1/chat/completions", BODY, true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(resolver(7L, 100L, 0L), limiter, registry)
                .filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).as("配额是记账：Redis 挂了必须放行（D7）").isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(registry.get(QuotaFilter.DEGRADED_METRIC).counter().count()).isEqualTo(1.0);
        assertThat(registry.find(QuotaFilter.SCRIPT_ERROR_METRIC).counter())
                .as("降级不得被计成脚本错误").isNull();
    }

    @Test
    void anUnexpectedScriptShapeCountsScriptErrorNotDegrade() {
        StubLimiter limiter = new StubLimiter();
        limiter.scriptError = new IllegalStateException("配额预扣脚本返回形状异常：期望 3 个元素，实际 2 个");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockServerWebExchange exchange = exchange("/v1/chat/completions", BODY, true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(resolver(7L, 100L, 0L), limiter, registry)
                .filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).as("脚本坏了也不能拒绝付费客户（配额是记账）").isNotNull();
        assertThat(registry.get(QuotaFilter.SCRIPT_ERROR_METRIC).counter().count())
                .as("形状异常必须落 script_error（缺陷）").isEqualTo(1.0);
        assertThat(registry.find(QuotaFilter.DEGRADED_METRIC).counter())
                .as("形状异常**不是**降级：degraded 必须保持 0").isNull();
    }

    @Test
    void anUnexpectedLimiterFailureFailsOpen() {
        StubLimiter limiter = new StubLimiter();
        limiter.failWith = new RuntimeException("配额判定内部错误");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockServerWebExchange exchange = exchange("/v1/chat/completions", BODY, true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(resolver(7L, 100L, 0L), limiter, registry)
                .filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).as("配额自身故障必须放行（fail-open）").isNotNull();
        assertThat(exchange.getResponse().getStatusCode())
                .as("fail-open 是**没有任何状态码**的放行，绝不能变成 5xx").isNull();
        assertThat(registry.get(QuotaFilter.FAIL_OPEN_METRIC).counter().count()).isEqualTo(1.0);
    }

    @Test
    void aDeniedRequestIsCounted() {
        StubLimiter limiter = new StubLimiter();
        limiter.decision = new QuotaDecision(false, 0L, -1L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        filter(resolver(7L, 100L, 0L), limiter, registry)
                .filter(exchange("/v1/chat/completions", BODY, true), chain(new AtomicReference<>()))
                .block(Duration.ofSeconds(5));

        assertThat(registry.get(QuotaFilter.DENIED_METRIC).counter().count()).isEqualTo(1.0);
    }

    // --- 脚手架 ----------------------------------------------------------------------

    private static QuotaFilter filter(QuotaResolver resolver, QuotaLimiter limiter, SimpleMeterRegistry registry) {
        return new QuotaFilter(resolver, limiter, registry(), ENABLED, registry);
    }

    private static QuotaReservationRegistry registry() {
        return new QuotaReservationRegistry(ENABLED);
    }

    /** 一条 (tenantId, period, tokenLimit, requestLimit) 的快照（period 取**现在**，与过滤器一致）。 */
    private static QuotaResolver resolver(long tenantId, long tokenLimit, long requestLimit) {
        QuotaDescriptor quota = new QuotaDescriptor(tenantId, QuotaPeriod.of(System.currentTimeMillis()),
                tokenLimit, requestLimit);
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, 1L, List.of(), List.of(), List.of(), null, List.of(quota));
        return new QuotaResolver(() -> snapshot);
    }

    private static MockServerWebExchange exchange(String path, String body, boolean withView) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .header("Content-Type", "application/json").body(body));
        attachView(exchange, withView);
        return exchange;
    }

    private static MockServerWebExchange exchangeBytes(String path, byte[] body, boolean withView) {
        DataBufferFactory factory = new DefaultDataBufferFactory();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .header("Content-Type", "application/json")
                .body(Flux.just(factory.wrap(body))));
        attachView(exchange, withView);
        return exchange;
    }

    private static void attachView(MockServerWebExchange exchange, boolean withView) {
        if (withView) {
            exchange.getAttributes().put(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW, VIEW);
        }
    }

    private static WebFilterChain chain(AtomicReference<ServerWebExchange> passed) {
        return exchange -> {
            passed.set(exchange);
            return Mono.empty();
        };
    }

    private static String bodyOf(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
    }

    /** 可编排的 {@link QuotaLimiter} 替身：记录调用、可注入结果 / 异常。 */
    private static final class StubLimiter implements QuotaLimiter {
        private QuotaDecision decision;
        private RuntimeException failWith;
        private IllegalStateException scriptError;
        private int calls;
        private QuotaDescriptor lastLimits;
        private long lastEstimated;

        @Override
        public QuotaDecision reserve(QuotaDescriptor limits, long estimatedTokens) {
            calls++;
            lastLimits = limits;
            lastEstimated = estimatedTokens;
            if (scriptError != null) {
                throw scriptError;
            }
            if (failWith != null) {
                throw failWith;
            }
            return decision;
        }

        @Override
        public void adjust(long tenantId, String period, long estimatedTokens, long actualTokens) {
            // 过滤器不调用 adjust。
        }
    }
}
