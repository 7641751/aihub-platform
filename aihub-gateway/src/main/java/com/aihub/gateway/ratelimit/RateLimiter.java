package com.aihub.gateway.ratelimit;

import com.aihub.common.config.RatePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 限流的<b>唯一入口</b>，也是「Redis 挂了怎么办」这条降级规则的唯一落点
 * （设计文档 §9：「Redis 不可用 → 限流降级为本地令牌桶（单机近似）…不阻断服务」）。
 *
 * <p>顺序：解析策略 → 试 Redis → Redis 说「我不可用」就试本机桶。
 * <b>绝不返回「拒绝」来表达自身故障</b>：故障只能转化为「换一种近似」，不能转化为「拒绝服务」。
 *
 * <p>降级是<b>粘性一秒</b>的（避免 Redis 挂掉时每个请求都先等一次超时窗，现 300 ms、事故时 2 s）：
 * Redis 返回「不可用」那一刻就标记降级并记录时刻（<b>在本机桶被用到之前</b>，不是「本机桶命中一次后」），
 * 接下来 1 秒内直接走本机桶、连试都不试 Redis。这是性能取舍，不是正确性取舍 ——
 * 恢复后最多晚 1 秒回到 Redis。
 *
 * <p>时钟是<b>注入</b>的（{@link LongSupplier}，与 {@link LocalRateLimiter} 同一做法）：
 * 粘性窗口的边界是「跨过 1 秒那一刻」，只有可注入的时钟才能在不 sleep 的前提下测到它。
 * 生产取 {@code System::currentTimeMillis}，见 {@link RateLimiterConfig}。
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final long DEFAULT_DEGRADE_STICKY_MILLIS = 1_000L;

    private final RedisRateLimiter redis;
    private final LocalRateLimiter local;
    private final RateLimitResolver resolver;
    private final long degradeStickyMillis;
    private final LongSupplier clockMillis;

    private final AtomicBoolean redisDegraded = new AtomicBoolean(false);
    private volatile long degradedAtMillis;

    public RateLimiter(RedisRateLimiter redis, LocalRateLimiter local, RateLimitResolver resolver) {
        this(redis, local, resolver, DEFAULT_DEGRADE_STICKY_MILLIS, System::currentTimeMillis);
    }

    /** 默认粘性窗口 + 可注入时钟：给「1 秒这个数本身」写边界用例用。 */
    public RateLimiter(RedisRateLimiter redis, LocalRateLimiter local, RateLimitResolver resolver,
                       LongSupplier clockMillis) {
        this(redis, local, resolver, DEFAULT_DEGRADE_STICKY_MILLIS, clockMillis);
    }

    public RateLimiter(RedisRateLimiter redis, LocalRateLimiter local, RateLimitResolver resolver,
                       long degradeStickyMillis) {
        this(redis, local, resolver, degradeStickyMillis, System::currentTimeMillis);
    }

    public RateLimiter(RedisRateLimiter redis, LocalRateLimiter local, RateLimitResolver resolver,
                       long degradeStickyMillis, LongSupplier clockMillis) {
        this.redis = redis;
        this.local = local;
        this.resolver = resolver;
        this.degradeStickyMillis = degradeStickyMillis;
        this.clockMillis = clockMillis;
    }

    /**
     * @param tenantId 桶维度之一，也是策略的租户维度
     * @param apiKeyId {@code api_key} 的<b>数值主键</b>（策略的 key 维度，决策 7/14）；没有则为 {@code null}
     *                 （鉴权关闭 / 匿名桶），此时策略解析自动只走租户级
     * @param keyHash  密钥的 SHA-256（桶维度之二；鉴权过滤器写进 exchange 属性）
     */
    public RateLimitDecision acquire(long tenantId, Long apiKeyId, String keyHash) {
        RatePolicy policy = resolver.resolve(tenantId, apiKeyId);
        // 两级各拼**自己的**键，谁都不复用对方那一个（G4）。这背后是同一条理由：两个存储的键
        // 前缀「刻意不同」（{@code LocalRateLimiter.KEY_PREFIX} 的 Javadoc），为的是一眼看出
        // 一个 key 在哪一级。若把 Redis 布局那一份原样交给本机桶，本机键就会变成
        // local:ratelimit:aihub:ratelimit:…（两个前缀叠在一串里），恰好把这条理由反过来。
        //
        // 两个键的唯一共同点是**身份**（{@code tenantId:keyHash}）—— 它构造一次、再用两次：
        //   · 本机桶：local:ratelimit:{tenantId}:{keyHash}
        //   · Redis： aihub:ratelimit:{tenantId}:{keyHash}
        // 前缀属于各自的存储，因此「加前缀」这件事只发生在各自那一侧。
        String identity = tenantId + ":" + keyHash;

        if (isDegradedNow()) {
            return local.tryConsume(identity, policy.qps(), policy.burst());
        }
        RateLimitDecision decision = redis.tryConsume(LuaTokenBucket.KEY_PREFIX + identity, policy.qps(),
                policy.burst());
        if (decision == null) {
            markDegraded();
            return local.tryConsume(identity, policy.qps(), policy.burst());
        }
        clearDegraded();
        return decision;
    }

    /** 最近一次判定是否走了降级路径（供 Task 9 打指标与限速日志）。 */
    public boolean redisDegraded() {
        return redisDegraded.get();
    }

    private boolean isDegradedNow() {
        return redisDegraded.get() && clockMillis.getAsLong() - degradedAtMillis < degradeStickyMillis;
    }

    private void markDegraded() {
        degradedAtMillis = clockMillis.getAsLong();
        if (redisDegraded.compareAndSet(false, true)) {
            log.error("Redis 限流不可用，已降级为本地令牌桶（单机近似，多实例下实际放行量约为「策略 × 实例数」）");
        }
    }

    private void clearDegraded() {
        if (redisDegraded.compareAndSet(true, false)) {
            log.info("Redis 限流已恢复");
        }
    }
}
