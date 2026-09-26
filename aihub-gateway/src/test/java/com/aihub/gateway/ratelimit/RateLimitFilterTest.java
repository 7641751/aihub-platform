package com.aihub.gateway.ratelimit;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.auth.ApiKeyAuthFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流过滤器是「429 长什么样、降级时怎么办」的唯一落点。
 *
 * <p>两条容易被写错的规则：
 * <ol>
 *   <li><b>降级也要限流</b>：Redis 挂了不等于「放过所有人」（那是无限流），而是「用单机近似继续限」；</li>
 *   <li><b>限流器自身故障必须放行</b>（fail-open）：限流是保护上游的手段，它自己坏了不能变成全量 503
 *       —— 与 M1「缓存故障绝不变成 500」是同一条纪律。</li>
 * </ol>
 *
 * <p>另有两条由控制器评审带进来的钉子（G3/G4）：
 * <ul>
 *   <li>{@code chain.filter} 必须**先于** {@code limiter.acquire}：全链路 test slice 用假 limiter
 *       即可自证「过滤器真的挂在链上」，而放行顺序决定了这个自证成不成立；</li>
 *   <li>没有哈希时用 {@code ANONYMOUS_KEY_HASH} 哨兵，**绝不**把 {@code null} 拼进桶 key ——
 *       否则所有无哈希请求共享一个桶（还会被字面量 {@code "null"} 拼出 {@code aihub:ratelimit:7:null}）。</li>
 * </ul>
 */
class RateLimitFilterTest {

    private static final ApiKeyView VIEW =
            new ApiKeyView("ak_demo", 7L, "demo", ApiKeyView.STATUS_ACTIVE, null, 42L);

    private static MockServerWebExchange exchange(String path, boolean withViewAndHash) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path).header("Content-Type", "application/json").build());
        if (withViewAndHash) {
            exchange.getAttributes().put(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW, VIEW);
            exchange.getAttributes().put(ApiKeyAuthFilter.ATTRIBUTE_KEY_HASH, "hash-of-secret");
        }
        return exchange;
    }

    private static RateLimitFilter filter(RecordingRateLimiter limiter, boolean enabled) {
        return new RateLimitFilter(limiter, enabled, new SimpleMeterRegistry());
    }

    private static RateLimitDecision allow(int remaining, RateLimitDecision.Source source) {
        return RateLimitDecision.allowed(remaining, 10, 20, source);
    }

    private static RateLimitDecision deny(long retryAfterMs, RateLimitDecision.Source source) {
        return RateLimitDecision.denied(retryAfterMs, 10, 20, source);
    }

    private static String bodyOf(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
    }

    @Test
    void allowsWhenUnderTheLimitAndAddsTheRateLimitHeaders() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(allow(9, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(RateLimitFilter.LIMIT_HEADER)).isEqualTo("10, 20");
        assertThat(exchange.getResponse().getHeaders().getFirst(RateLimitFilter.REMAINING_HEADER)).isEqualTo("9");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void rejectsWith429AndTheOpenAiBodyWhenOverTheLimit() {
        RecordingRateLimiter limiter =
                new RecordingRateLimiter(deny(250L, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).as("超限不得进入下游链").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(bodyOf(exchange))
                .contains("\"error\"")
                .contains("\"code\":\"rate_limit_exceeded\"")
                .contains("\"type\":\"rate_limit_error\"")
                .contains("\"param\":null");
    }

    @Test
    void rejectionCarriesRetryAfterInSecondsAndMilliseconds() {
        RecordingRateLimiter limiter =
                new RecordingRateLimiter(deny(250L, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);

        filter(limiter, true).filter(exchange, chain(new AtomicReference<>())).block(Duration.ofSeconds(5));

        // 250ms → 向上取整 1 秒（Retry-After 的单位只能是秒）。
        assertThat(exchange.getResponse().getHeaders().getFirst(RateLimitFilter.RETRY_AFTER_HEADER)).isEqualTo("1");
        assertThat(exchange.getResponse().getHeaders().getFirst(RateLimitFilter.RETRY_AFTER_MS_HEADER))
                .isEqualTo("250");
        assertThat(exchange.getResponse().getHeaders().getFirst(RateLimitFilter.REMAINING_HEADER)).isEqualTo("0");
    }

    @Test
    void usesTheTenantAndTheKeyHashAsTheBucketDimension() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(allow(5, RateLimitDecision.Source.REDIS));

        filter(limiter, true).filter(exchange("/v1/chat/completions", true), chain(new AtomicReference<>()))
                .block(Duration.ofSeconds(5));

        assertThat(limiter.tenantId).isEqualTo(7L);
        assertThat(limiter.keyHash).isEqualTo("hash-of-secret");
        // 决策 7（修订）+ 决策 14：策略的 key 维度必须真的从认证视图里传下去 —— 这正是原文
        // 「请求无法映射到 api_key_id」那句不成立的机器证据。数值主键自 Task 2 起就在共享契约里，
        // 因此这里是**直接读真值**，不是什么「后续任务收口」。
        assertThat(limiter.apiKeyId).isEqualTo(42L);
    }

    @Test
    void skipsWhenDisabled() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(deny(1L, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, false).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNotNull();
        assertThat(limiter.calls).isZero();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void skipsNonV1Paths() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(deny(1L, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/healthz", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNotNull();
        assertThat(limiter.calls).isZero();
    }

    /** 鉴权关闭（没有 view）时不能「无限放行」：用 tenant=0 + anonymous 的桶仍然限流。 */
    @Test
    void usesTheAnonymousBucketWhenThereIsNoApiKeyView() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(allow(1, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", false);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNotNull();
        assertThat(limiter.calls).isEqualTo(1);
        assertThat(limiter.tenantId).isZero();
        assertThat(limiter.keyHash).isEqualTo(RateLimitFilter.ANONYMOUS_KEY_HASH);
        // 没有 view 时没有数值主键 → 策略解析只走租户级（决策 7 的第二级），这**不是**「不限流」。
        assertThat(limiter.apiKeyId).isNull();
    }

    /**
     * G3：有视图但**没有**密钥哈希（鉴权关闭时手工塞了 view、或将来某条路径只写 view）时，桶的第二维
     * 必须是哨兵而不是真的 {@code null} —— {@code "aihub:ratelimit:7:" + null} 会让所有这类请求共享
     * 一个桶。哨兵还要与「整个请求都没有认证视图」是同一条取值，否则同一个匿名客户端会因为
     * 「有没有 view」而落到两个桶里。
     */
    @Test
    void aBlankKeyHashNeverBecomesTheLiteralNullInTheBucketKey() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(allow(1, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions").build());
        exchange.getAttributes().put(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW, VIEW);

        filter(limiter, true).filter(exchange, chain(new AtomicReference<>())).block(Duration.ofSeconds(5));

        assertThat(limiter.keyHash).isEqualTo(RateLimitFilter.ANONYMOUS_KEY_HASH);
        assertThat(limiter.keyHash).as("字面量 \"null\" 会让所有无哈希请求挤进同一个桶").isNotEqualTo("null");
    }

    /** 降级 ≠ 放行：本机桶说拒绝，就必须回 429（否则 Redis 一挂就等于关掉了限流）。 */
    @Test
    void stillEnforcesTheLocalBucketWhenRedisIsDegraded() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(deny(100L, RateLimitDecision.Source.LOCAL));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void degradedDecisionIsCounted() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(allow(1, RateLimitDecision.Source.LOCAL));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RateLimitFilter(limiter, true, registry)
                .filter(exchange("/v1/chat/completions", true), chain(new AtomicReference<>()))
                .block(Duration.ofSeconds(5));

        assertThat(registry.get(RateLimitFilter.DEGRADED_METRIC).counter().count()).isEqualTo(1.0);
    }

    /**
     * G12：降级计数器必须**只按「这一跳在哪一级判定」**打标签，且该标签是 {@code local}——
     * 因为它与 {@code RateLimiter.redisDegraded()}（熔断状态，粘性一秒）是**两个不同的信号**：
     * 把两者混成一个「degraded=true 就报警」的指标，会在 Redis 变黑期间每个请求都报一次。
     */
    @Test
    void theDegradedCounterIsTaggedWithTheLevelThatMadeTheDecision() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(allow(1, RateLimitDecision.Source.LOCAL));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RateLimitFilter(limiter, true, registry)
                .filter(exchange("/v1/chat/completions", true), chain(new AtomicReference<>()))
                .block(Duration.ofSeconds(5));

        assertThat(registry.get(RateLimitFilter.DEGRADED_METRIC)
                .tag(RateLimitFilter.SOURCE_TAG, RateLimitFilter.SOURCE_LOCAL)
                .counter().count())
                .as("降级计数必须带 source=local 标签，运维才能把它与熔断状态分开")
                .isEqualTo(1.0);
    }

    @Test
    void filterOrderIsAfterAuthentication() {
        Order order = RateLimitFilter.class.getAnnotation(Order.class);

        assertThat(WebFilter.class).isAssignableFrom(RateLimitFilter.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 100);
        assertThat(ApiKeyAuthFilter.class.getAnnotation(Order.class).value())
                .as("鉴权必须先跑，限流才拿得到 tenant/密钥哈希").isLessThan(order.value());
    }

    @Test
    void unexpectedLimiterFailureFailsOpen() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(null);
        limiter.failWith = new IllegalStateException("限流器内部错误");
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).as("限流器故障必须放行（fail-open）").isNotNull();
        assertThat(exchange.getResponse().getStatusCode())
                .as("fail-open 是**没有任何状态码**的放行，绝不能变成 5xx").isNull();
    }

    /**
     * G2 的另一半：{@code limiter.acquire} 返回 {@code null}（契约被打破）同样必须放行。
     * {@code RateLimitDecision} 的字段访问就写在返回处，没有这道守卫时它就是一个 NPE → 500 ——
     * 而设计文档的降级表把「限流机制自身故障」明确划给 fail-open。
     */
    @Test
    void aNullDecisionAlsoFailsOpen() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(null);
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).as("null 判定绝不能变成 NPE → 500").isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    /** {@link RateLimitFilter#ANONYMOUS_KEY_HASH} 是 G3 的哨兵，必须是可被引用、且非空的常量。 */
    @Test
    void theAnonymousSentinelIsNotEmpty() {
        assertThat(RateLimitFilter.ANONYMOUS_KEY_HASH).isNotBlank();
    }

    // --- 测试脚手架 -------------------------------------------------------

    private static WebFilterChain chain(AtomicReference<ServerWebExchange> passed) {
        return exchange -> {
            passed.set(exchange);
            return Mono.empty();
        };
    }

    /**
     * 可编排的 {@link RateLimiter} 替身：记录维度、可注入结果与异常。
     * <p>它继承真实类并覆写唯一被使用的方法 —— 父类构造器收到的三个 null 永远不会被触碰。
     */
    private static final class RecordingRateLimiter extends RateLimiter {
        private final RateLimitDecision decision;
        int calls;
        long tenantId = -1L;
        Long apiKeyId = -1L;
        String keyHash;
        RuntimeException failWith;

        RecordingRateLimiter(RateLimitDecision decision) {
            super(null, null, null);
            this.decision = decision;
        }

        @Override
        public RateLimitDecision acquire(long tenantId, Long apiKeyId, String keyHash) {
            calls++;
            this.tenantId = tenantId;
            this.apiKeyId = apiKeyId;
            this.keyHash = keyHash;
            if (failWith != null) {
                throw failWith;
            }
            return decision;
        }
    }
}
