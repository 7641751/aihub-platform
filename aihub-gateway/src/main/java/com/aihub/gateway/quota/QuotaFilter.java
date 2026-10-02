package com.aihub.gateway.quota;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.QuotaDescriptor;
import com.aihub.common.quota.QuotaDecision;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.gateway.auth.ApiKeyAuthFilter;
import com.aihub.gateway.error.GatewayErrors;
import com.aihub.gateway.trace.RequestIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据面配额预扣（设计文档 §6.2）。守 {@code /v1/**} 中**唯一计费**的端点
 * {@code /v1/chat/completions}（A 决策 13：{@code /v1/models} 不落 {@code request_log}），
 * 排在鉴权（{@code +100}）与限流（{@code +150}）之后（{@code +175}），因此租户已可从
 * {@link ApiKeyAuthFilter#ATTRIBUTE_KEY_VIEW} 里取到。
 *
 * <p><b>两种「配额没生效」的原因分开计数</b>（F6 / E.4-(a)）：
 * <ul>
 *   <li>{@link #DEGRADED_METRIC}（{@code aihub.quota.degraded}）：Redis **不可用** → 按 D7 放行；</li>
 *   <li>{@link #SCRIPT_ERROR_METRIC}（{@code aihub.quota.script_error}）：预扣脚本返回**非预期形状**
 *       （{@code QuotaScript.parse} 抛 {@code IllegalStateException}）→ 同样放行，但**这是缺陷不是降级**。
 *       合成一个计数器，等于让「Lua 写错了」永远藏在「Redis 挂了」后面 —— 两者的处置完全不同。</li>
 * </ul>
 * 另有 {@link #DENIED_METRIC}（超限拒绝）与 {@link #FAIL_OPEN_METRIC}（本过滤器自身故障放行）。
 *
 * <p><b>Redis 不可用时可选择回源 admin 预扣</b>（{@link QuotaFallback}，由 {@code aihub.quota.fallback-enabled}
 * 决定开与关）。兜底**只在「Redis 不可用」这一条分支**上尝试 —— 「本周期不限」「脚本形状异常」「自身故障」
 * 都在到达这里之前就返回了，绝不试兜底。而它只可能把「放行」升级成「拒绝」：兜底自己失败 / 拿不到判定 /
 * 说还有额度，一律回到 D7 的「放行 + {@code degraded} 计数 + 跳过校正」。这样兜底既不会成为新的可用性单点，
 * 也不会成为绕过配额的手段。
 *
 * <p><b>请求体是一次性流，必须缓存且不得改变转发字节（D17 + M1 铁律）</b>：本过滤器为了估算要读
 * 请求体，而下游（路由与转发）也必须能读到**同一份字节**。因此：join 出整段 body → 复制进
 * {@code byte[]} → **释放** join 出来的 buffer（此时数据已在数组里，释放是安全的）→ 用
 * {@link ServerHttpRequestDecorator} 把**同一份** {@code byte[]} 反复供给下游 →
 * {@code exchange.mutate().request(decorated).build()}。**顺序是承重的**：先复制、再释放、
 * 然后传**装饰过的** exchange —— 反过来（释放了还传原 exchange）会让下游读到空 body。
 * 这一点由 {@code cachedBodyStillReachesTheUpstreamByteForByte} / 单元级字节比较证明，
 * 不靠「应该没事」。
 *
 * <p><b>阻塞的 Redis 调用离开 event loop</b>：判定整体在 {@link #LIMITER_SCHEDULER} 上执行
 * （与 {@code RateLimitFilter} 同一纪律；网关侧 {@code spring.data.redis.timeout} 仍是 2 秒，
 * 占住 event loop 就是灾难）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 175)
public class QuotaFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(QuotaFilter.class);

    /** 本次请求的预扣关联（{@link QuotaReservationRegistry.QuotaReservation}）在 exchange 属性里的键。 */
    public static final String ATTRIBUTE_RESERVATION = "aihub.quotaReservation";

    /** Redis 不可用导致放行的次数（D7）。 */
    public static final String DEGRADED_METRIC = "aihub.quota.degraded";

    /** 预扣脚本返回非预期形状导致放行的次数（缺陷，不是降级；与 {@link #DEGRADED_METRIC} 分开）。 */
    public static final String SCRIPT_ERROR_METRIC = "aihub.quota.script_error";

    /** 超限拒绝的次数。 */
    public static final String DENIED_METRIC = "aihub.quota.denied";

    /** 本过滤器自身故障导致放行的次数。 */
    public static final String FAIL_OPEN_METRIC = "aihub.quota.fail_open";

    /** 唯一计费的端点（决策 A13）。用**精确相等**，不是 {@code /v1/**} 前缀。 */
    private static final String GUARDED_PATH = "/v1/chat/completions";

    private static final Scheduler LIMITER_SCHEDULER = Schedulers.boundedElastic();

    private static final byte[] EMPTY_BODY = new byte[0];

    /** 降级日志的节流窗口（Redis 挂掉时不要每个请求打一行）。 */
    private static final long DEGRADE_LOG_INTERVAL_MILLIS = 60_000L;

    private final QuotaResolver resolver;
    private final QuotaLimiter limiter;
    private final QuotaReservationRegistry reservations;
    private final QuotaConfigProperties properties;
    private final MeterRegistry registry;
    private final QuotaFallback fallback;
    private final AtomicLong lastDegradeLogMillis = new AtomicLong(Long.MIN_VALUE / 2);

    /**
     * Spring 用的构造器：{@code fallback} 由 {@link QuotaConfig} 注入（{@code aihub.quota.fallback-enabled}
     * 决定它是「回源 admin」还是「什么都不做」）。
     *
     * <p>{@code @Autowired} **是承重的**：本类有两个公开构造器（另一个是给测试直接用的「无兜底」版本），
     * 少了它就等于没有「唯一的构造器」，Spring 只会报
     * {@code Failed to instantiate [...QuotaFilter]: No default constructor found} 而整条链起不来。
     */
    @Autowired
    public QuotaFilter(QuotaResolver resolver, QuotaLimiter limiter, QuotaReservationRegistry reservations,
                       QuotaConfigProperties properties, MeterRegistry registry, QuotaFallback fallback) {
        this.resolver = resolver;
        this.limiter = limiter;
        this.reservations = reservations;
        this.properties = properties;
        this.registry = registry;
        this.fallback = fallback;
    }

    /**
     * 不带兜底的构造器：等价于「兜底关闭」。保留它是为了让既有的直接构造用法（{@code QuotaFilterTest}）一行不改
     * —— 那批用例钉的是**正常路径与降级计数**，不是兜底；兜底另由 {@code QuotaReserveFallbackTest} 端到端证明。
     */
    public QuotaFilter(QuotaResolver resolver, QuotaLimiter limiter, QuotaReservationRegistry reservations,
                       QuotaConfigProperties properties, MeterRegistry registry) {
        this(resolver, limiter, reservations, properties, registry, QuotaFallback.disabled());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.enabled()
                || !GUARDED_PATH.equals(exchange.getRequest().getPath().pathWithinApplication().value())) {
            return chain.filter(exchange);
        }
        // 读体（非阻塞）→ 复制进 byte[]（见类注释的顺序约束）→ 判定（阻塞，切到弹性线程池）→ 续链。
        return DataBufferUtils.join(exchange.getRequest().getBody(), properties.maxInMemoryBytes())
                .map(QuotaFilter::copyAndRelease)
                .defaultIfEmpty(EMPTY_BODY)
                .flatMap(body -> {
                    Decision decision = new Decision();
                    return Mono.fromRunnable(() -> decide(exchange, body, decision))
                            .subscribeOn(LIMITER_SCHEDULER)
                            .then(Mono.defer(() -> finish(exchange, body, decision, chain)));
                });
    }

    /** 复制 join 出来的 buffer 并**立刻释放**它（数据已在 byte[] 里，释放是安全的）。 */
    private static byte[] copyAndRelease(DataBuffer buffer) {
        byte[] body = new byte[buffer.readableByteCount()];
        buffer.read(body);
        DataBufferUtils.release(buffer);
        return body;
    }

    /**
     * 判定（**只会在 {@link #LIMITER_SCHEDULER} 上执行**）。结果写进 {@code decision}；
     * 自身故障折算成 {@code fault}，形状异常折算成 {@code scriptError}，交给 {@link #finish} 放行。
     * 这里**绝不把异常继续向上抛**（除了 {@code Error}）——配额是记账，它自己坏了不能变成全量 5xx。
     */
    private void decide(ServerWebExchange exchange, byte[] body, Decision decision) {
        decision.requestId = RequestIdFilter.ensure(exchange);
        decision.tenantId = tenantId(exchange);
        decision.period = QuotaPeriod.of(System.currentTimeMillis());

        Optional<QuotaDescriptor> limits;
        try {
            limits = resolver.resolve(decision.tenantId, decision.period);
        } catch (RuntimeException e) {
            decision.fault = e;
            return;
        }
        if (limits.isEmpty()) {
            decision.verdict = Verdict.UNLIMITED;
            return;
        }
        decision.limits = limits.get();
        String text = new String(body, StandardCharsets.UTF_8);
        decision.estimatedTokens = QuotaEstimator.estimate(text, QuotaEstimator.maxTokens(text));
        try {
            decision.decision = limiter.reserve(decision.limits, decision.estimatedTokens);
        } catch (IllegalStateException e) {
            // 脚本形状异常 = 缺陷（不是降级）：单独落 script_error。
            // **不试兜底**：admin 跑的是同一份 Lua（QuotaScript.SCRIPT 是共享常量），必然也失败；
            // 试它只会把「脚本写错了」这个缺陷藏进「Redis 挂了」这个降级里。
            decision.scriptError = e;
            return;
        } catch (RuntimeException e) {
            decision.fault = e;
            return;
        }
        if (decision.decision == null) {
            // **只有这里**（Redis 这一级不可用）才试兜底：
            //   * 「本周期不限」在 limits.isEmpty() 处已提前返回（没有预扣可言，没有可兜底的东西）；
            //   * 「脚本形状异常」「自身故障」在上面两个 catch 处已提前返回。
            // 兜底只能把「放行」升级成「拒绝」：它说还有额度、或拿不到判定，都与 fail-open 同效，
            // 且都必须按「本次没有发生可校正的预扣」处理 ⇒ DEGRADED（计数 + 跳过校正）。
            QuotaDecision fallbackDecision =
                    fallback.reserveFallback(decision.tenantId, decision.estimatedTokens).orElse(null);
            if (fallbackDecision != null && !fallbackDecision.allowed()) {
                // 降级是为了「不因为组件坏了而拒绝」，**不是**为了「绕过配额」：
                // 兜底权威地说余额不足 ⇒ 仍然是拒绝（网关据此回 429 insufficient_quota）。
                decision.decision = fallbackDecision;
                decision.verdict = Verdict.DENIED;
            } else {
                decision.verdict = Verdict.DEGRADED;
            }
        } else if (decision.decision.allowed()) {
            decision.verdict = Verdict.ALLOWED;
        } else {
            decision.verdict = Verdict.DENIED;
        }
    }

    /** 判定的结局：自身故障/形状异常/降级 → 放行；超限 → 429；放行 → 附带预扣关联续链。 */
    private Mono<Void> finish(ServerWebExchange exchange, byte[] body, Decision decision, WebFilterChain chain) {
        if (decision.fault != null) {
            count(FAIL_OPEN_METRIC);
            log.warn("配额判定自身故障，本次放行（fail-open，交由每日对账兜底）: {}", decision.fault.toString());
            return continueWithReservation(exchange, body, decision, true, chain);
        }
        if (decision.scriptError != null) {
            count(SCRIPT_ERROR_METRIC);
            log.error("配额预扣脚本返回了非预期形状，本次放行（这是缺陷不是降级，请立刻修脚本）: {}",
                    decision.scriptError.toString());
            // 形状异常时无法判断脚本到底扣没扣 ⇒ 记 degraded=true（**跳过校正**），由对账兜底。
            return continueWithReservation(exchange, body, decision, true, chain);
        }
        switch (decision.verdict) {
            case UNLIMITED:
                // 本周期不限：没有预扣。仍写一条 degraded=true 的关联条目，让校正器把它与「本该有
                // 预扣却没有」（那才是异常）区分开 —— 否则「不限」会把 correction_missing 顶到失真。
                return continueWithReservation(exchange, body, decision, true, chain);
            case DEGRADED:
                count(DEGRADED_METRIC);
                logDegradedAtMostOncePerWindow();
                return continueWithReservation(exchange, body, decision, true, chain);
            case ALLOWED:
                return continueWithReservation(exchange, body, decision, false, chain);
            case DENIED:
            default:
                count(DENIED_METRIC);
                log.debug("配额拒绝: tenant={} period={} estimated={} ", decision.tenantId, decision.period,
                        decision.estimatedTokens);
                return GatewayErrors.write(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS,
                        "insufficient_quota", "insufficient_quota",
                        "租户 " + decision.tenantId + " 在周期 " + decision.period
                                + " 的配额已用尽（本请求预估 " + decision.estimatedTokens + " tokens）");
        }
    }

    /**
     * 放行分支：写入预扣关联 → 把**修饰过的** exchange（同一份 byte[]）交给下游链。
     *
     * @param degraded {@code true} = 本次**没有发生预扣**（不限 / Redis 降级 / 形状异常 / 自身故障），
     *                 校正器据此跳过 adjust（否则会把从未压掉的额度补进桶里）
     */
    private Mono<Void> continueWithReservation(ServerWebExchange exchange, byte[] body, Decision decision,
                                               boolean degraded, WebFilterChain chain) {
        QuotaReservationRegistry.QuotaReservation reservation = new QuotaReservationRegistry.QuotaReservation(
                decision.tenantId, decision.period, decision.estimatedTokens, degraded);
        exchange.getAttributes().put(ATTRIBUTE_RESERVATION, reservation);
        reservations.write(decision.requestId, reservation);
        return chain.filter(withCachedBody(exchange, body));
    }

    /**
     * 把同一份 {@code byte[]} 反复供给下游的装饰器：只包 request，其它一概不动。
     * 每次订阅都重新 wrap 一个缓存块（{@code Flux.defer}），因此下游读几次都拿到完整、独立的字节。
     */
    private static ServerWebExchange withCachedBody(ServerWebExchange exchange, byte[] body) {
        ServerHttpRequest decorated = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
        };
        return exchange.mutate().request(decorated).build();
    }

    /** 租户取自鉴权视图；鉴权关闭 / 没有视图时用 0（与计量、限流的匿名维度一致）。 */
    private static long tenantId(ServerWebExchange exchange) {
        ApiKeyView view = (ApiKeyView) exchange.getAttributes().get(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW);
        return view == null ? 0L : view.tenantId();
    }

    /** 指标记录**绝不改变判定结论**（写失败只记一条 WARN）。 */
    private void count(String metric) {
        try {
            registry.counter(metric).increment();
        } catch (RuntimeException e) {
            log.warn("配额指标记录失败（不影响判定结果）: {}", e.toString());
        }
    }

    /** 降级日志节流：每个窗口最多一行，但**每个窗口都有一行**（长故障必须持续可见）。 */
    private void logDegradedAtMostOncePerWindow() {
        long now = System.currentTimeMillis();
        long last = lastDegradeLogMillis.get();
        if (now - last >= DEGRADE_LOG_INTERVAL_MILLIS && lastDegradeLogMillis.compareAndSet(last, now)) {
            log.warn("Redis 配额不可用，本次请求放行（fail-open：宁可少记，也不因为记账组件坏了而拒绝付费客户）");
        }
    }

    /** 一次判定的结局（逐个字段由 {@link #decide} 写、{@link #finish} 读）。 */
    private enum Verdict {UNLIMITED, ALLOWED, DENIED, DEGRADED}

    private static final class Decision {
        private String requestId;
        private long tenantId;
        private String period;
        private QuotaDescriptor limits;
        private long estimatedTokens;
        private QuotaDecision decision;
        private Verdict verdict;
        private IllegalStateException scriptError;
        private RuntimeException fault;
    }
}
