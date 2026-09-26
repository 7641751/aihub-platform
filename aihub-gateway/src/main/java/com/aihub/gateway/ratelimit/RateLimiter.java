package com.aihub.gateway.ratelimit;

import com.aihub.common.config.RatePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 限流的**唯一入口**，也是「Redis 挂了怎么办」这条降级规则的唯一落点
 * （设计文档 §9：「Redis 不可用 → 限流降级为本地令牌桶（单机近似）…不阻断服务」）。
 *
 * <p>顺序：解析策略 → 试 Redis → Redis 说「我不可用」就试本机桶。
 * **绝不返回「拒绝」来表达自身故障**：故障只能转化为「换一种近似」，不能转化为「拒绝服务」。
 *
 * <p>降级是**粘性一秒**的（避免 Redis 挂掉时每个请求都先等一次 2 秒超时）：本机桶命中一次后，
 * 标记为降级并记录时刻，接下来 1 秒内直接走本机桶。这是性能取舍，不是正确性取舍 ——
 * 恢复后最多晚 1 秒回到 Redis。
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final long DEFAULT_DEGRADE_STICKY_MILLIS = 1_000L;

    private final RedisRateLimiter redis;
    private final LocalRateLimiter local;
    private final RateLimitResolver resolver;
    private final long degradeStickyMillis;

    private final AtomicBoolean redisDegraded = new AtomicBoolean(false);
    private volatile long degradedAtMillis;

    public RateLimiter(RedisRateLimiter redis, LocalRateLimiter local, RateLimitResolver resolver) {
        this(redis, local, resolver, DEFAULT_DEGRADE_STICKY_MILLIS);
    }

    public RateLimiter(RedisRateLimiter redis, LocalRateLimiter local, RateLimitResolver resolver,
                       long degradeStickyMillis) {
        this.redis = redis;
        this.local = local;
        this.resolver = resolver;
        this.degradeStickyMillis = degradeStickyMillis;
    }

    /**
     * @param tenantId 桶维度之一，也是策略的租户维度
     * @param apiKeyId {@code api_key} 的**数值主键**（策略的 key 维度，决策 7/14）；没有则为 {@code null}
     *                 （鉴权关闭 / 匿名桶），此时策略解析自动只走租户级
     * @param keyHash  密钥的 SHA-256（桶维度之二；鉴权过滤器写进 exchange 属性）
     */
    public RateLimitDecision acquire(long tenantId, Long apiKeyId, String keyHash) {
        RatePolicy policy = resolver.resolve(tenantId, apiKeyId);
        // Redis 布局的桶键（决策 8）。本机桶那一级**自己**会补上它自己的前缀
        // （{@code LocalRateLimiter.KEY_PREFIX}），因此这里绝不能预先拼 local 前缀 ——
        // 那会拼出 local:ratelimit:aihub:ratelimit:…，与本机前缀「刻意与 Redis 布局不同」的
        // 理由（一眼看出一个 key 在哪一级）正好相反。
        String bucketKey = LuaTokenBucket.KEY_PREFIX + tenantId + ":" + keyHash;

        if (isDegradedNow()) {
            return local.tryConsume(bucketKey, policy.qps(), policy.burst());
        }
        RateLimitDecision decision = redis.tryConsume(bucketKey, policy.qps(), policy.burst());
        if (decision == null) {
            markDegraded();
            return local.tryConsume(bucketKey, policy.qps(), policy.burst());
        }
        clearDegraded();
        return decision;
    }

    /** 最近一次判定是否走了降级路径（供 Task 9 打指标与限速日志）。 */
    public boolean redisDegraded() {
        return redisDegraded.get();
    }

    private boolean isDegradedNow() {
        return redisDegraded.get() && System.currentTimeMillis() - degradedAtMillis < degradeStickyMillis;
    }

    private void markDegraded() {
        degradedAtMillis = System.currentTimeMillis();
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
