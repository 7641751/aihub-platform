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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * <p>本类**只**覆盖过滤器自己的行为（判定怎么变成响应、降级怎么计数、故障怎么放行）：
 * 它自己 {@code new} 过滤器、自己塞 exchange 属性，因此对它而言「过滤器有没有被 Spring 装进链上」
 * 是无意义的 —— 那两条必须由**别的**类证明，别在这里假装：
 * <ul>
 *   <li>「真实 HTTP 穿过真实过滤器链、限流组件真的被装配」→ {@code RateLimitWiringTest}
 *       （{@code @SpringBootTest(RANDOM_PORT)}，不构造任何限流组件）；</li>
 *   <li>「桶 key 的两级布局 / 本机前缀恰好一层」（G4）→ {@code BucketKeyLayoutTest}
 *       （本类的 {@code RecordingRateLimiter} 把 {@code acquire} 整个换掉了，看不到任何桶 key）。</li>
 * </ul>
 *
 * <p>本类里由控制器评审带进来的钉子都在「过滤器自己这一层」能自证的范围内：
 * <ul>
 *   <li>放行与拒绝的分岔：拒绝时**不**调用 {@code chain.filter}，放行时调用 —— test slice 的假链
 *       是这一点的唯一证据；</li>
 *   <li>没有哈希时用 {@code ANONYMOUS_KEY_HASH} 哨兵，**绝不**把 {@code null} 拼进桶 key ——
 *       否则所有无哈希请求共享一个桶（还会被字面量 {@code "null"} 拼出 {@code aihub:ratelimit:7:null}）；</li>
 *   <li>降级/拒绝/自身故障三条路径的计数形态（{@code source} 只加在会取两个值的计数器上）；</li>
 *   <li>收窄后的 catch 的边界：{@code RuntimeException} 与 {@code null} 判定 → fail-open，
 *       而 {@code Error} → **向上抛**（{@link #anErrorFromTheLimiterPropagatesInsteadOfFailingOpen()}）。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：判定被切到 {@code Schedulers.boundedElastic()} 上执行，本类的用例因此都
 * 在订阅之后 {@code block(...)} 等结果（与真实请求路径一样是异步的）。「判定不占 event loop」
 * 这条不变式由 {@code RateLimitEventLoopTest} 单独证明。
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
     * G12（复审 Fix 4 之后的形态）：{@code source} 标签只加在**会取两个值**的计数器上 —— 拒绝计数。
     * 降级的判定（{@code decision.degraded()}）可能来自本机桶，也可能来自 Redis 桶，因此这个标签
     * 在这里才有信息量；而 {@code aihub.ratelimit.degraded} 只在判定来自本机桶时才 +1，
     * {@code source=local} 对它是一个常量标签（复审指出：常量标签不区分任何两个时间序列）。
     *
     * <p>两条断言合起来把「标签加在会变的地方、且取值正确」钉死：同一个 Redis 侧判定必须进
     * {@code source=redis} 那条时间序列，而带 {@code source=local} 的那条必须**一个都没有**。
     */
    @Test
    void theRejectedCounterCarriesTheVaryingSourceTagAndTheDegradedOneDoesNot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RateLimitFilter(new RecordingRateLimiter(deny(1L, RateLimitDecision.Source.REDIS)), true, registry)
                .filter(exchange("/v1/chat/completions", true), chain(new AtomicReference<>()))
                .block(Duration.ofSeconds(5));

        assertThat(registry.get(RateLimitFilter.REJECTED_METRIC)
                .tag(RateLimitFilter.SOURCE_TAG, RateLimitFilter.SOURCE_REDIS)
                .counter().count())
                .as("Redis 侧的拒绝必须进 source=redis 这一条时间序列")
                .isEqualTo(1.0);
        assertThat(registry.find(RateLimitFilter.REJECTED_METRIC)
                .tag(RateLimitFilter.SOURCE_TAG, RateLimitFilter.SOURCE_LOCAL)
                .counter())
                .as("Redis 侧拒绝不得被记成本机（降级）拒绝")
                .isNull();
        assertThat(registry.find(RateLimitFilter.DEGRADED_METRIC)
                .tag(RateLimitFilter.SOURCE_TAG, RateLimitFilter.SOURCE_LOCAL)
                .counter())
                .as("degraded 计数器不再带 source 这个常量标签（它只有一个取值）")
                .isNull();
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

    /**
     * <b>收窄后的 catch 只兜 {@code RuntimeException}：{@code Error} 必须向上抛</b>（复审 Fix 3）。
     *
     * <p>收窄这个 catch 时原先**没有任何用例**覆盖 {@code Error} 这一侧（替身字段是
     * {@code RuntimeException}，只覆盖了它和 null），于是「收窄」这件事本身就是无证据的声明 ——
     * 把它改回 {@code catch (RuntimeException | Error e)} 全绿。这条用例补上判别力：
     * JVM 级故障不能被当成「限流降级」，否则一个已经失去资源的 JVM 会继续放行请求、把故障扩散。
     *
     * <p>用 {@code AssertionError} 而不是 {@code OutOfMemoryError}：Reactor 的
     * {@code Exceptions.throwIfFatal} 会把 {@code VirtualMachineError} 直接在调度线程上重抛
     * （那时订阅者永远收不到终态信号，用例会以超时而不是断言失败收场）。{@code AssertionError}
     * 是非致命的 {@code Error}，会正常经 {@code onError} 传到订阅者，因此它精确地测「catch 的宽度」。
     */
    @Test
    void anErrorFromTheLimiterPropagatesInsteadOfFailingOpen() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(null);
        limiter.failWithError = new AssertionError("限流器遇到 JVM 级故障");
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        assertThatThrownBy(() -> filter(limiter, true).filter(exchange, chain(passed))
                .block(Duration.ofSeconds(5)))
                .as("Error 必须向上抛：它表达的是 JVM 级故障，不是「限流机制降级」")
                .hasRootCauseInstanceOf(AssertionError.class);
        assertThat(passed.get()).as("Error 绝不能被吞成 fail-open（收窄 catch 之前的形态就是这样）")
                .isNull();
    }

    /**
     * <b>{@code fail_open} 计数器不带任何常量标签</b>：这是本轮**已披露**的一处指标形态变更
     * （复审 Fix 4）。
     *
     * <p>上一轮把 {@code source=local} 从这条计数器上一起拿掉了，而复审只点名了 {@code degraded}
     * 那条 —— 因此这里把它变成可执行的契约而不是注释：fail-open 发生时**没有任何一级给出过判定**
     * （Redis 与本机桶都没结论），{@code source=local} 会是一条**假的**来源标注；它与
     * {@code degraded} 是同一个「常量标签不区分任何两个时间序列」的理由。
     * 「去掉标签」会改变既有时序的名字，所以它必须在报告里被明确写出来（本轮已写）。
     */
    @Test
    void theFailOpenCounterCarriesNoConstantSourceTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingRateLimiter limiter = new RecordingRateLimiter(null);
        limiter.failWith = new IllegalStateException("限流器内部错误");

        new RateLimitFilter(limiter, true, registry)
                .filter(exchange("/v1/chat/completions", true), chain(new AtomicReference<>()))
                .block(Duration.ofSeconds(5));

        assertThat(registry.get(RateLimitFilter.FAIL_OPEN_METRIC).counter().count())
                .as("fail-open 必须被计数（它是「限流保护失效」的对外信号）")
                .isEqualTo(1.0);
        assertThat(registry.find(RateLimitFilter.FAIL_OPEN_METRIC)
                .tag(RateLimitFilter.SOURCE_TAG, RateLimitFilter.SOURCE_LOCAL)
                .counter())
                .as("fail-open 时没有一级给出过判定，source=local 是假标签")
                .isNull();
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
        /** 非致命的 {@code Error}（JVM 级故障）：用来测「catch 只兜 RuntimeException」这条边界。 */
        Error failWithError;

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
            if (failWithError != null) {
                throw failWithError;
            }
            return decision;
        }
    }
}
