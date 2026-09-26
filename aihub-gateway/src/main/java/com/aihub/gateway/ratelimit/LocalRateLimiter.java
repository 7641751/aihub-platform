package com.aihub.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.function.LongSupplier;

/**
 * Redis 不可用时的**本机令牌桶降级实现**（设计文档 §9：「限流降级为本地令牌桶（单机近似）」）。
 *
 * <p>它的语义与 Redis 版**完全一致** —— 两边都遵守 {@link TokenBucket} 的纯算术，因此降级
 * 不会让客户端看到另一套限流规则。差别只在**作用域**：本机桶只看得见本进程的流量，
 * 多实例部署时实际放行量会接近「策略 × 实例数」。这是「单机近似」的准确含义，也是接受降级的
 * 代价；不接受的做法是「Redis 挂了就整体拒绝」，那违反「数据面永不因控制面故障而整体不可用」。
 *
 * <p><b>必须有界</b>：{@link Caffeine} 的 {@code maximumSize} 保证内存不会被「无数个 key」撑爆
 * （否则降级本身变成攻击面）。淘汰是**逐出即遗忘**，被逐出的桶下一次请求会以满桶重建 ——
 * 宽松方向的误差，可接受。
 *
 * <p>并发正确性来自 {@link java.util.concurrent.ConcurrentMap#compute} 的**单键原子性**：
 * 「读状态 → 判定 → 写状态」在同一个 compute 里完成，因此同一 key 的并发请求不会超发。
 *
 * <p><b>缺省状态与 Lua 侧一致</b>：新 key 用 {@code (0, 0)} 起步，等价于 Lua 脚本里
 * {@code HGET … or '0'} 把不存在的 Hash 读成 0 —— 两侧「第一次请求会发生什么」因此相同。
 */
public final class LocalRateLimiter {

    /**
     * 本机桶的键前缀。**刻意与 Redis 布局（{@code aihub:ratelimit:}）不同**：
     * 两种存储混用同一个字符串时，日志与抓包里无法判断一个 key 到底在哪一级，
     * 而且将来万一有人把本机桶写进 Redis，前缀会让这件事立刻可见。
     */
    public static final String KEY_PREFIX = "local:ratelimit:";

    private final Cache<String, TokenBucket.State> buckets;
    private final LongSupplier clockMillis;

    /**
     * @param maxBuckets  本机桶的数量上界
     * @param clockMillis 毫秒时钟（测试注入假时钟；生产用 {@code System::currentTimeMillis}）
     */
    public LocalRateLimiter(int maxBuckets, LongSupplier clockMillis) {
        this.buckets = Caffeine.newBuilder()
                .maximumSize(Math.max(maxBuckets, 1))
                .build();
        this.clockMillis = clockMillis;
    }

    /** 判定并消费。**永不抛异常**：它跑在请求路径上。 */
    public RateLimitDecision tryConsume(String key, int qps, int burst) {
        long now = clockMillis.getAsLong();
        TokenBucket.State[] holder = new TokenBucket.State[1];
        RateLimitDecision[] decisionHolder = new RateLimitDecision[1];
        buckets.asMap().compute(KEY_PREFIX + key, (ignored, state) -> {
            // state == null 就是「本进程从没见过这个 key」，直接原样交给 TokenBucket：
            // 它以满桶起步（与 Lua 侧 HGET 取不到字段时的分支一致），不在这里折成 (0, 0)。
            RateLimitDecision decision = TokenBucket.tryConsume(state, now, qps, burst);
            decisionHolder[0] = decision;
            holder[0] = TokenBucket.nextState(state, now, qps, burst, decision);
            return holder[0];
        });
        RateLimitDecision decision = decisionHolder[0];
        if (decision == null) {
            // compute 理论上必然被调用；为「永不为 null」这条契约兜底（宁可放行也不抛）。
            return RateLimitDecision.allowed(Math.max(burst, 1), qps, Math.max(burst, 1),
                    RateLimitDecision.Source.LOCAL);
        }
        return new RateLimitDecision(decision.allowed(), decision.remaining(), decision.retryAfterMs(),
                decision.limit(), decision.burst(), RateLimitDecision.Source.LOCAL);
    }

    /** 已跟踪的桶数量（测试与运维巡检用）。 */
    public int trackedBuckets() {
        buckets.cleanUp();
        return (int) buckets.estimatedSize();
    }

    /** 清空所有桶（测试用；生产没有任何调用点）。 */
    public void clear() {
        buckets.invalidateAll();
    }
}
