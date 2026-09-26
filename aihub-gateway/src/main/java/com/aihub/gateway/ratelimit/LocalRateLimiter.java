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
 * <p><b>没见过的 key 以满桶起步，与 Lua 侧是同一条规则</b>：{@code state == null}（本进程从未见过这个
 * key；Lua 侧是 {@code HGET} 取不到字段）直接按**满桶**判定，而**不是**折成 {@code (0, 0)} 再靠
 * {@code elapsed * qps} 补出来 —— 后者在 {@code qps = 0} 时永远补不出令牌，会把「允许 burst 次突发」
 * 退化成「一次都不放行」。因此 {@code null} 与 {@code (0, 0)} 是**两个不同的状态**：前者是「没见过的
 * key」（满桶），后者是一个真实存在的空桶（0 个令牌）。
 *
 * <p><b>TTL 到期或被 Caffeine 逐出之后，桶会再次变成「没见过的 key」，因而又是满桶起步</b>：这是
 * **有意为之**的宽松方向误差（宁可多放行，也不误伤刚过期的正常客户端），也正是空闲 TTL 取得宽裕的
 * 原因 —— {@code RateLimitScript.idleTtlMillis} 的下限是 60 秒，正常取「放空一个满桶所需时间」的 20 倍。
 */
public final class LocalRateLimiter {

    /**
     * 本机桶的键前缀。**刻意与 Redis 布局（{@code aihub:ratelimit:}）不同**：
     * 两种存储混用同一个字符串时，日志与抓包里无法判断一个 key 到底在哪一级，
     * 而且将来万一有人把本机桶写进 Redis，前缀会让这件事立刻可见。
     *
     * <p>因此 {@link #tryConsume} 收的是**身份**（{@code {tenantId}:{keyHash}}），而不是
     * Redis 布局那一份键 —— 完整键是 {@code local:ratelimit:{tenantId}:{keyHash}}，前缀只有**一层**
     * （见 {@link RateLimiter#acquire}）。把 Redis 布局的键原样传进来的话，这一层再补一次就得到
     * {@code local:ratelimit:aihub:ratelimit:…}，两个前缀叠在同一串里。
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
            // limit 与正常路径口径一致：也是钳位后的 rate，不把原始 qps 透出去。
            return RateLimitDecision.allowed(Math.max(burst, 1), Math.max(qps, 0), Math.max(burst, 1),
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

    /**
     * 已跟踪的桶的**完整键**（含 {@link #KEY_PREFIX}），只给测试看：桶布局（尤其是「前缀只有一层」）
     * 是控制器评审的验收点（G4），而它对任何外部行为都不可见。返回的是快照，不暴露内部 map。
     */
    java.util.Set<String> trackedKeys() {
        buckets.cleanUp();
        return java.util.Set.copyOf(buckets.asMap().keySet());
    }

    /** 清空所有桶（测试用；生产没有任何调用点）。 */
    public void clear() {
        buckets.invalidateAll();
    }
}
