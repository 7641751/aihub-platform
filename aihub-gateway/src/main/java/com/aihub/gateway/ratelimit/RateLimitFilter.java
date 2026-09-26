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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

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
 *
 * <p><b>线程模型：判定整体在 {@link #LIMITER_SCHEDULER} 上执行，绝不占 event loop。</b>
 * 这条链上一个判定要做两件**阻塞**的事：策略解析会调 {@code ConfigClient.current()}（两级缓存
 * 未命中时是同步 Redis GET，必要时还有一次同步回源），而 {@code RedisRateLimiter} 是阻塞的
 * Lua 调用。{@code spring.data.redis.timeout} 是 2 秒：Redis **卡住**（不是拒绝）时，在 event loop
 * 上做这些事会把共享同一个 loop 的所有请求一起按住 2 秒 —— {@code /healthz} 也不例外，
 * 而且这不是异常，{@code catch} 救不了一个正在阻塞的线程。这与 M2 给 {@code ApiKeyResolver} 的
 * Redis 读取所做的处置是同一件事（同一套理由：`StringRedisTemplate` 是阻塞驱动，而调用方是
 * {@code WebFilter}）。顺带它也把「{@code ConfigClient} 在冷缓存时会 {@code Mono.block()}」
 * 变成合法动作 —— 阻塞调用本来就该发生在弹性线程池上。
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
     * 判定发生在哪一级的标签（G12）。**只加在会取两个值的计数器上 —— 目前是
     * {@link #REJECTED_METRIC}**（拒绝可能来自 Redis 桶，也可能来自降级的本机桶）。
     *
     * <p><b>为什么需要它</b>：{@code degraded} 这个名字在 M3 里有**两个**互不相同的信号 ——
     * {@link RateLimitDecision#degraded()}（这一跳在哪一级判定，逐请求）与
     * {@link RateLimiter#redisDegraded()}（熔断状态，粘性一秒）。一个只按「degraded 为真」报警的
     * 面板会在 Redis 变黑期间每个请求响一次。
     *
     * <p><b>为什么只加在那里</b>：{@link #DEGRADED_METRIC} 只在判定来自本机桶时才 +1，
     * {@code source=local} 对它是一个**常量**标签 —— 多出来的那个维度永远只有一个取值，
     * 不区分任何两个时间序列（复审 Fix 4）。把会变的维度放在会变的地方，标签才有信息量。
     */
    public static final String SOURCE_TAG = "source";

    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_REDIS = "redis";

    private static final PathPattern GUARDED_PATH = new PathPatternParser().parse("/v1/**");

    /** 降级日志的节流窗口：Redis 挂掉时不要每个请求打一行。 */
    private static final long DEGRADE_LOG_INTERVAL_MILLIS = 60_000L;

    /** 没有 {@code ApiKeyView}（鉴权关闭）时的桶维度：仍然限流，而不是无限放行。 */
    public static final String ANONYMOUS_KEY_HASH = "anonymous";

    /**
     * 判定动作的执行器。与 M2 的 {@code ApiKeyResolver.REDIS_SCHEDULER} 同一取舍：用
     * {@link Schedulers#boundedElastic()} 而不是 {@code single()}，因为阻塞任务天然可能堆积，
     * 单线程会把堆积变成队列延迟。
     *
     * <p>它是静态共享的（不是每实例一个）：线程池按 JVM 共享本来就是 {@code boundedElastic} 的语义，
     * 而本类是单例 bean。用全局调度器也让测试能断言「判定不发生在订阅它的那个线程上」。
     */
    private static final Scheduler LIMITER_SCHEDULER = Schedulers.boundedElastic();

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

        // 判定（含策略解析与令牌桶 I/O）整体切到 LIMITER_SCHEDULER：这些调用是**阻塞**的，
        // 在 event loop 上做会把整个 loop 按住（见类 Javadoc 的「线程模型」）。
        // fromRunnable 的 runnable 就在调度器线程上同步执行（判定 + 指标 + 响应头），
        // 随后的 then(Mono.defer(...)) 也在同一个线程上订阅下游链 / 错误体写入 ——
        // 顺序与同步版逐字相同，只是换了一个线程执行。
        // 判定结果放**本次请求自己的** holder（不是实例字段）：本类是单例，实例字段会让并发请求互相踩。
        Outcome outcome = new Outcome();
        return Mono.fromRunnable(() -> acquire(exchange, tenantId, apiKeyId, hash, outcome))
                .subscribeOn(LIMITER_SCHEDULER)
                .then(Mono.defer(() -> finish(exchange, chain, tenantId, outcome)));
    }

    /**
     * 调用限流器并落地它的直接后果：指标与响应头。**只会在 {@link #LIMITER_SCHEDULER} 上执行**
     * （由 {@link #filter} 的 {@code subscribeOn} 保证），因此这里的阻塞调用不占 event loop。
     *
     * <p>结果写进 {@code outcome}；自身故障折算成 {@code fault} 交给 {@link #finish} fail-open。
     * 这里**刻意不把异常继续向上抛**（除了 {@code Error}）：判定链的故障必须变成放行，
     * 而放行要带着下游链的 {@code Mono} 一起组合，那一步只能在 {@code finish} 里做。
     */
    private void acquire(ServerWebExchange exchange, long tenantId, Long apiKeyId, String hash,
                         Outcome outcome) {
        try {
            RateLimitDecision decided = limiter.acquire(tenantId, apiKeyId, hash);
            // null 判定同样是「限流机制故障」：它的字段访问就写在下面，没有这一句就是 NPE → 500。
            if (decided == null) {
                throw new NullPointerException("RateLimiter.acquire 返回了 null");
            }
            outcome.decision = decided;
            reportDecision(decided);
            writeDecisionHeaders(exchange, decided);
        } catch (RuntimeException e) {
            // **只兜 RuntimeException**：它精确表达「限流机制本身故障了」。Error（OOM、StackOverflow
            // 这类 JVM 级故障）必须继续向上抛 —— 把一个已经失去资源的 JVM 当成「限流降级」继续放行
            // 请求，只会让故障扩散（与 M2 在 Redis 读取处只兜 RuntimeException 是同一条纪律）。
            outcome.fault = e;
        }
    }

    /**
     * 判定的结局：自身故障 → fail-open，超限 → 429，其余 → 交给下游链。
     *
     * <p>放行与拒绝在这里分岔，而**判定本身已经落地**（{@link #acquire}）—— 因此下游链拿到的
     * 响应头一定是最新那一次判定的，不存在「先转发后写头」的竞态。
     */
    private Mono<Void> finish(ServerWebExchange exchange, WebFilterChain chain, long tenantId,
                              Outcome outcome) {
        if (outcome.fault != null) {
            return failOpen(chain, exchange, outcome.fault);
        }
        RateLimitDecision decided = outcome.decision;
        // 判定只有两种来源：要么 acquire 写下了它，要么 acquire 记下了 fault（上面已经返回）。
        if (decided == null) {
            return failOpen(chain, exchange, new IllegalStateException("限流判定缺失"));
        }
        if (decided.allowed()) {
            return chain.filter(exchange);
        }
        long retryAfterMs = Math.max(1L, decided.retryAfterMs());
        log.debug("限流拒绝: tenant={} source={} limit={}qps burst={} retryAfter={}ms", tenantId,
                decided.source(), decided.limit(), decided.burst(), retryAfterMs);

        return GatewayErrors.write(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS,
                "rate_limit_error", "rate_limit_exceeded",
                "请求过于频繁：租户 " + tenantId + " 的限额为 " + decided.limit() + " QPS（突发 "
                        + decided.burst() + "），请在 " + retryAfterMs + " 毫秒后重试");
    }

    /** 一次判定的结果：要么是判定本身，要么是「限流机制故障了」这条事实。 */
    private static final class Outcome {
        private RateLimitDecision decision;
        private RuntimeException fault;
    }

    /** 观测：降级计数 + 节流 WARN、拒绝计数。**绝不改变判定结论**（写失败只记一条 WARN）。 */
    private void reportDecision(RateLimitDecision decision) {
        try {
            if (decision.degraded()) {
                degradedCounter().increment();
                logDegradedAtMostOncePerWindow();
            }
            if (!decision.allowed()) {
                rejectedCounter(decision).increment();
            }
        } catch (RuntimeException e) {
            log.warn("限流指标记录失败（不影响判定结果）: {}", e.toString());
        }
    }

    /** 放行与拒绝**都**写这两条：客户端据此知道自己离限额还有多远。 */
    private static void writeDecisionHeaders(ServerWebExchange exchange, RateLimitDecision decision) {
        exchange.getResponse().getHeaders().set(LIMIT_HEADER, decision.limit() + ", " + decision.burst());
        exchange.getResponse().getHeaders().set(REMAINING_HEADER, String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            long retryAfterMs = Math.max(1L, decision.retryAfterMs());
            // Retry-After 的单位只能是秒，且 0 是非法值：向上取整并至少给 1。
            long retryAfterSeconds = Math.max(1L, (retryAfterMs + 999L) / 1000L);
            exchange.getResponse().getHeaders().set(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));
            exchange.getResponse().getHeaders().set(RETRY_AFTER_MS_HEADER, String.valueOf(retryAfterMs));
        }
    }

    /**
     * 限流机制自身故障 → 放行。**只记日志与计数，绝不设置状态码**：一旦在这里写响应，
     * 保护层的故障就变成了数据面的 5xx，而那正是本设计要避免的方向。
     */
    private Mono<Void> failOpen(WebFilterChain chain, ServerWebExchange exchange, Throwable fault) {
        // 只记录异常类型/消息，不记录请求内容（可能含密钥或正文）。
        log.error("限流器自身故障，本次请求放行（fail-open）: {}", fault.toString());
        try {
            // 与 degraded 同理：这条计数器只在「限流机制自身故障」时 +1，来源维度是常量，
            // 因此不带 source 标签（复审 Fix 4 的同一个理由，顺手一并处理）。
            registry.counter(FAIL_OPEN_METRIC).increment();
        } catch (RuntimeException e) {
            log.warn("fail-open 指标记录失败: {}", e.toString());
        }
        return chain.filter(exchange);
    }

    /**
     * 降级计数（判定落在本机桶，即 Redis 不可用）。**刻意不带 {@code source} 标签**：
     * 这条计数器只在判定来自本机桶时才 +1，因此 {@code source=local} 是一个常量标签 ——
     * 它不区分任何两个时间序列，只是把同一个名字又写了一遍（复审 Fix 4）。
     * 「这一跳在哪一级判定」这个**会变**的信息由 {@link #REJECTED_METRIC} 携带，那里才是它有意义的地方。
     */
    private Counter degradedCounter() {
        return registry.counter(DEGRADED_METRIC);
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
