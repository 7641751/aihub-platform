package com.aihub.gateway.ratelimit;

import com.aihub.common.ratelimit.RateLimitScript;

/**
 * 令牌桶的**纯算术**。Redis Lua 脚本（{@link RateLimitScript} 里的 {@code SCRIPT}）与 Redis 不可用
 * 时的本机实现（{@link LocalRateLimiter}）都遵守它，因此「补充速率 / 封顶 / 退避时间」在降级前后
 * **逐字一致** —— 这是设计文档 §9「限流降级为本地令牌桶（单机近似）」能被称为「近似」而不是
 * 「另一种算法」的前提。
 *
 * <p><b>为什么用整数毫令牌</b>：Lua 的数字只有 double，Java 侧如果用 {@code double} 累加，
 * 长时间运行后两侧的取整会有偏差，导致「Redis 通的时候拒绝、断了之后放行」这类只在切换瞬间
 * 出现的怪现象。用千分之一的整数（{@code tokensMilli}）后两侧都是整数运算，结果完全可复现。
 *
 * <p><b>时间由调用方传入</b>（不用 Redis 的 {@code TIME}，计划决策 9）：一次拒绝必须能对应到网关
 * 日志里的时间戳，否则排查限流问题时无从下手。代价是时钟回拨会重置桶（放宽而不是收紧）。
 *
 * <h2>「新桶是满的」是一条独立规则，不能靠「从 0 号毫秒补充」凑出来</h2>
 * 新桶（{@code state == null}；Lua 侧是 Hash 不存在，即 {@code HGET} 返回 {@code false}）必须
 * **直接以满桶起步**：{@code tokensMilli = capacity * 1000}。不能只把它当成 {@code (0, 0)} 再靠
 * {@code elapsed * qps} 补出来，原因有两条：
 * <ul>
 *   <li>{@code qps = 0} 时永远补不出令牌，于是「只能突发 burst 次」会退化成「一次都不放行」——
 *       这直接违反决策 7 对 {@code burst} 的定义（qps=0 时仍允许 burst 次突发）；</li>
 *   <li>更普遍地，{@code now * qps} 够不够满取决于时钟的**绝对值**。生产时钟是 epoch 毫秒
 *       （约 1.7e12），任意正 qps 都够；但注入的假时钟（例如从 1000 开始）配小 qps 就不够了 ——
 *       于是「新桶是满的」会变成一条只在生产成立的隐式巧合，测试里换个时钟就变红。</li>
 * </ul>
 *
 * <p>因此 {@code null} 与 {@code (0, 0)} 是**两个不同的语义**：前者是「这个 key 从没见过」（满桶），
 * 后者是一个真实存在的空状态（0 个令牌、基准时刻为 0），在 {@code qps = 0} 下它就是 0 个令牌。
 */
public final class TokenBucket {

    /** 一个令牌的千分之一表示。 */
    public static final long MILLI = 1000L;

    private TokenBucket() {
    }

    /**
     * 桶的状态：{@code tokensMilli} 是**当前令牌数 × 1000**，{@code lastRefillMillis} 是上次补充时刻。
     */
    public record State(long tokensMilli, long lastRefillMillis) {
    }

    /**
     * 尝试消费一个令牌。**纯函数**：不修改传入的 state，也不读任何全局状态 —— 同样的输入必得同样的
     * 结果。因此「连续消费」必须由调用方把 {@link #nextState} 的结果喂回来（Redis 侧则由一个原子
     * 脚本在一次往返里完成读改写）。
     *
     * <p>时钟回拨（{@code now < lastRefill}）时**不补充**也不报错，只把时间戳前移 —— 也就是说回拨
     * 期间桶只减不增。这比「按负的 elapsed 扣令牌」安全：后者会让桶瞬间变成负数，随后需要一个
     * 很长的窗口才能恢复到能放行。
     *
     * @param state     当前状态；{@code null} 表示新桶（满桶起步）
     * @param nowMillis 调用方的当前毫秒时间戳
     * @param qps       每秒补充的令牌数（非正数视为「永不补充」→ 只允许 burst 次突发）
     * @param burst     桶容量（非正数视为 1）
     */
    public static RateLimitDecision tryConsume(State state, long nowMillis, int qps, int burst) {
        int capacity = Math.max(burst, 1);
        int rate = Math.max(qps, 0);
        long tokens = effectiveTokens(state, nowMillis, capacity, rate);

        if (tokens >= MILLI) {
            long remainingTokens = tokens - MILLI;
            return RateLimitDecision.allowed((int) Math.min(capacity, remainingTokens / MILLI), rate, capacity,
                    RateLimitDecision.Source.REDIS);
        }

        long missingTokensMilli = MILLI - tokens;
        // qps=0：永远补不出下一个令牌。给一个明确的上界而不是 Long.MAX_VALUE，
        // 免得被写进 Retry-After 头时变成一个荒唐的数字。
        long retryAfterMs = rate == 0 ? 3_600_000L : Math.max(1L, ceilDiv(missingTokensMilli, rate));
        return RateLimitDecision.denied(retryAfterMs, rate, capacity, RateLimitDecision.Source.REDIS);
    }

    /**
     * 判定之后应该写回的状态（放行与拒绝都要写回：拒绝时时间戳同样推进，否则补充会被重复计算）。
     *
     * <p>新桶同样先满桶再扣，因此「第一次请求」的落盘结果与 {@link #tryConsume} 的判定严格一致，
     * 不会出现「判定放行、落盘的却是空桶」。
     */
    public static State nextState(State state, long nowMillis, int qps, int burst, RateLimitDecision decision) {
        int capacity = Math.max(burst, 1);
        int rate = Math.max(qps, 0);
        long tokens = effectiveTokens(state, nowMillis, capacity, rate);
        if (decision != null && decision.allowed()) {
            tokens = Math.max(0L, tokens - MILLI);
        }
        return new State(tokens, nowMillis);
    }

    /**
     * 按「补充 + 封顶」算出消费前的令牌数。新桶（{@code null}）直接是满桶：这条规则与 Lua 脚本里
     * 下面这段一一对应（{@code HGET} 取不到字段时走 {@code else}），两侧因此不会在「第一次请求」上分叉：
     * <pre>{@code
     * if tokens and lastRefill then
     *   tokens = tonumber(tokens)
     *   lastRefill = tonumber(lastRefill)
     * else
     *   tokens = capacity
     *   lastRefill = now
     * end
     * }</pre>
     */
    private static long effectiveTokens(State state, long nowMillis, int capacity, int rate) {
        long capacityMilli = (long) capacity * MILLI;
        if (state == null) {
            return capacityMilli;
        }
        long tokens = state.tokensMilli();
        long elapsed = nowMillis - state.lastRefillMillis();
        if (elapsed > 0 && rate > 0) {
            tokens = Math.min(capacityMilli, tokens + elapsed * rate);
        }
        // elapsed < 0（时钟回拨）：不补充，令牌原样保留，只把基准时刻前移（由 nextState 落盘）。
        return tokens;
    }

    private static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
