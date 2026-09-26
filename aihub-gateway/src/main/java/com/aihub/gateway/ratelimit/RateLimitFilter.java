package com.aihub.gateway.ratelimit;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.auth.ApiKeyAuthFilter;
import com.aihub.gateway.error.GatewayErrors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据面限流（设计文档 §8.1 ②）。守 {@code /v1/**}，**排在鉴权之后**：限流的策略维度是
 * {@code tenant + api_key}（决策 7 修订），桶维度是 {@code tenant + sha256(secret)}，
 * 这些值都来自 {@link ApiKeyAuthFilter} 的解析结果。
 *
 * <p>超限回 **429 + OpenAI 形状错误体**（决策 13：数据面不套 admin 信封），并带上
 * IETF 风格的 {@code RateLimit-*} 与 {@code Retry-After}。
 *
 * <p><b>降级不等于放行</b>：Redis 不可用时 {@link RateLimiter} 会给出本机桶的判定，
 * 过滤器**照常执行**那个判定（拒绝就是拒绝）。放开全部请求不是「降级」，是「关掉限流」，
 * 会让上游被瞬间打挂 —— 那才是真正的不可用；反过来，因为 Redis 挂了就拒绝所有人同样是不可用。
 *
 * <p><b>自身故障必须放行</b>（fail-open）：限流是**保护**手段，它自己坏了不能变成全量 5xx。
 * 与鉴权的 fail-closed 相反，这是刻意的取舍（保护层的故障方向应当朝向「让请求过去」，
 * 而信任层的故障方向应当朝向「拒绝」）。守卫覆盖的是**整个判定动作**：{@code acquire} 抛异常、
 * 以及它返回 {@code null}（契约被打破）都算自身故障 —— 后者若不加判断，就是一个 NPE → 500。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 150)
public class RateLimitFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** IETF RateLimit 字段族。 */
    public static final String LIMIT_HEADER = "ratelimit-limit";
    public static final String REMAINING_HEADER = "ratelimit-remaining";

    /** 退避信号：标准头是秒，毫秒版是非标准但客户端友好。 */
    public static final String RETRY_AFTER_HEADER = "retry-after";
    public static final String RETRY_AFTER_MS_HEADER = "retry-after-ms";

    /** 限流自身的故障导致放行的次数（限流保护失效的时长，与 {@code degraded} 是两回事）。 */
    public static final String FAIL_OPEN_METRIC = "aihub.ratelimit.fail_open";

    /** 判定落在本机桶（Redis 不可用）的次数。 */
    public static final String DEGRADED_METRIC = "aihub.ratelimit.degraded";

    /** 被限流拒绝的次数。 */
    public static final String REJECTED_METRIC = "aihub.ratelimit.rejected";

    /**
     * 判定发生在哪一级的标签（G12）。
     *
     * <p><b>为什么必须带这个标签</b>：{@code degraded} 这个名字在 M3 里有**两个**互不相同的信号 ——
     * {@link RateLimitDecision#degraded()}（这一跳在哪一级判定，逐请求）与
     * {@link RateLimiter#redisDegraded()}（熔断状态，粘性一秒）。一个只按「degraded 为真」报警的
     * 面板会在 Redis 变黑期间每个请求响一次。带上 {@code source} 之后，这条计数器读作
     * 「有多少请求是用单机近似放行的」，而「Redis 现在是不是黑的」由熔断状态表达 —— 两者不再混用。
     */
    public static final String SOURCE_TAG = "source";

    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_REDIS = "redis";

    private static final PathPattern GUARDED_PATH = new PathPatternParser().parse("/v1/**");

    /** 降级日志的节流窗口：Redis 挂掉时不要每个请求打一行。 */
    private static final long DEGRADE_LOG_INTERVAL_MILLIS = 60_000L;

    /** 没有 {@code ApiKeyView}（鉴权关闭）时的桶维度：仍然限流，而不是无限放行。 */
    public static final String ANONYMOUS_KEY_HASH = "anonymous";

    private final RateLimiter limiter;
    private final boolean enabled;
    private final MeterRegistry registry;
    private final AtomicLong lastDegradeLogMillis = new AtomicLong(Long.MIN_VALUE / 2);

    /**
     * 唯一的构造器：Spring 用它装配（{@code enabled} 来自 {@code aihub.ratelimit.enabled}），
     * 测试也直接用它 —— 测试不该为了注一个开关而拉起 Spring。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitFilter(RateLimiter limiter,
                           @Value("${aihub.ratelimit.enabled:true}") boolean enabled,
                           MeterRegistry registry) {
        this.limiter = limiter;
        this.enabled = enabled;
        this.registry = registry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled || !GUARDED_PATH.matches(exchange.getRequest().getPath().pathWithinApplication())) {
            return chain.filter(exchange);
        }
        ApiKeyView view = (ApiKeyView) exchange.getAttributes().get(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW);
        Object keyHash = exchange.getAttributes().get(ApiKeyAuthFilter.ATTRIBUTE_KEY_HASH);
        long tenantId = view == null ? 0L : view.tenantId();
        // G3：**绝不**把 null 拼进桶 key。`"aihub:ratelimit:7:" + null` 会让所有「有视图但没有哈希」
        // 的请求共享一个字面量 "null" 的桶（而且每个请求照样烧一次 Redis 往返）。没有哈希就是匿名桶，
        // 与「整个请求都没有认证视图」取同一个哨兵 —— 同一个客户端不该因为这一维的有无而落到两个桶里。
        String hash = keyHash == null || keyHash.toString().isBlank()
                ? ANONYMOUS_KEY_HASH
                : keyHash.toString();
        // 决策 7（修订）+ 决策 14：key 级策略的映射键是 api_key 的**数值主键**，它随 ApiKeyView 一起
        // 下发（该分量自 Task 2 起就在共享契约里，admin 侧填 api_key.id、网关侧 parse 透传），
        // 因此这里直接读真值：没有 view（鉴权关闭）时为 null → 策略解析只走租户级那一维。
        Long apiKeyId = view == null ? null : view.apiKeyId();

        RateLimitDecision decision;
        try {
            decision = limiter.acquire(tenantId, apiKeyId, hash);
        } catch (RuntimeException | Error e) {
            return failOpen(chain, exchange, e);
        }
        // null 判定同样是「限流机制故障」：它的字段访问就写在下面，没有这一句就是 NPE → 500。
        if (decision == null) {
            return failOpen(chain, exchange, new NullPointerException("RateLimiter.acquire 返回了 null"));
        }

        try {
            if (decision.degraded()) {
                degradedCounter().increment();
                logDegradedAtMostOncePerWindow();
            }
            if (!decision.allowed()) {
                rejectedCounter(decision).increment();
            }
        } catch (RuntimeException e) {
            // 观测手段坏掉不该改变判定结论：头照发、该 429 还是 429。
            log.warn("限流指标记录失败（不影响判定结果）: {}", e.toString());
        }

        exchange.getResponse().getHeaders().set(LIMIT_HEADER, decision.limit() + ", " + decision.burst());
        exchange.getResponse().getHeaders().set(REMAINING_HEADER, String.valueOf(decision.remaining()));

        if (decision.allowed()) {
            return chain.filter(exchange);
        }

        long retryAfterMs = Math.max(1L, decision.retryAfterMs());
        long retryAfterSeconds = Math.max(1L, (retryAfterMs + 999L) / 1000L);
        exchange.getResponse().getHeaders().set(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));
        exchange.getResponse().getHeaders().set(RETRY_AFTER_MS_HEADER, String.valueOf(retryAfterMs));
        log.debug("限流拒绝: tenant={} source={} limit={}qps burst={} retryAfter={}ms", tenantId,
                decision.source(), decision.limit(), decision.burst(), retryAfterMs);

        return GatewayErrors.write(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS,
                "rate_limit_error", "rate_limit_exceeded",
                "请求过于频繁：租户 " + tenantId + " 的限额为 " + decision.limit() + " QPS（突发 "
                        + decision.burst() + "），请在 " + retryAfterMs + " 毫秒后重试");
    }

    /**
     * 限流机制自身故障 → 放行。**只记日志与计数，绝不设置状态码**：一旦在这里写响应，
     * 保护层的故障就变成了数据面的 5xx，而那正是本设计要避免的方向。
     */
    private Mono<Void> failOpen(WebFilterChain chain, ServerWebExchange exchange, Throwable fault) {
        // 只记录异常类型/消息，不记录请求内容（可能含密钥或正文）。
        log.error("限流器自身故障，本次请求放行（fail-open）: {}", fault.toString());
        try {
            registry.counter(FAIL_OPEN_METRIC, SOURCE_TAG, SOURCE_LOCAL).increment();
        } catch (RuntimeException e) {
            log.warn("fail-open 指标记录失败: {}", e.toString());
        }
        return chain.filter(exchange);
    }

    private Counter degradedCounter() {
        return registry.counter(DEGRADED_METRIC, SOURCE_TAG, SOURCE_LOCAL);
    }

    private Counter rejectedCounter(RateLimitDecision decision) {
        return registry.counter(REJECTED_METRIC, SOURCE_TAG,
                decision.degraded() ? SOURCE_LOCAL : SOURCE_REDIS);
    }

    /** 降级日志节流：每个窗口最多一行，但**每个窗口都有一行**（长故障必须持续可见）。 */
    private void logDegradedAtMostOncePerWindow() {
        long now = System.currentTimeMillis();
        long last = lastDegradeLogMillis.get();
        if (now - last >= DEGRADE_LOG_INTERVAL_MILLIS && lastDegradeLogMillis.compareAndSet(last, now)) {
            log.warn("限流正在使用本机令牌桶（Redis 不可用，单机近似；多实例下实际放行量约为「策略 × 实例数」）");
        }
    }
}
