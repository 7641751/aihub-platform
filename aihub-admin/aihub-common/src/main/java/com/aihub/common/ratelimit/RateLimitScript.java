package com.aihub.common.ratelimit;

/**
 * Redis + Lua 令牌桶的**脚本与键布局**，放在共享模块里，理由有两条：
 * <ol>
 *   <li>它是**跨模块契约**：gateway 要跑这段脚本，而 admin 侧的集成测试（需要 Docker，
 *       因此不能放在 gateway 模块）要验它；两侧必须逐字节同一份，否则「测试验过的脚本」
 *       与「线上跑的脚本」会漂移。与 {@code MeteringTopology} 放在共享模块的理由相同。</li>
 *   <li>它只由 JDK 的字符串常量组成，**不破坏 main 作用域零依赖**。</li>
 * </ol>
 *
 * <p>字段名 {@code t}（令牌毫数）与 {@code k}（上次补充的毫秒时间戳）也是契约的一部分：
 * 运维用 {@code HGETALL} 巡检桶时必须认识它们，测试也用它们断言 TTL。
 *
 * <p><b>为什么必须是 Lua</b>（设计文档 §11 深挖清单第 2 题）：令牌桶的「补充 → 判定 → 扣减」是
 * 三步读改写。若由客户端分三次调用 Redis，并发下两个请求会读到同一个令牌数、各自判定成功，
 * 于是 burst 被击穿（经典的超发）。Lua 脚本在 Redis 里**单线程原子执行**，三步之间不可能插入
 * 别的请求，因此判定与扣减是同一个原子操作。Task 14 用「20 线程 × 20 次并发、恰好放行 burst 次」
 * 在真 Redis 上证明这一点。
 *
 * <p><b>为什么不用 Redis 的 {@code TIME}</b>（计划决策 9）：时间由 {@code ARGV[1]} 传入。
 * 用 {@code TIME} 会把「网关的处理时刻」与「桶的判定时刻」拆成两个时钟，排查限流问题时无法把一次
 * 拒绝对应到网关日志里的时间戳。代价是时钟回拨会重置桶（放宽而不是收紧），与纯算术一致。
 *
 * <p><b>脚本的算术与 Java 纯算术（{@code com.aihub.gateway.ratelimit.TokenBucket}）必须逐字一致</b>：
 * 两侧都只用整数毫令牌、同一个「补满→封顶→扣减/退避」公式。gateway 侧的
 * {@code TokenBucketScriptConformanceTest} 用同一张字面量向量表交叉钉住这两份实现（见该测试的类注释）。
 */
public final class RateLimitScript {

    /** 桶键前缀：完整键是 {@code aihub:ratelimit:{tenantId}:{sha256(secret)}}（计划决策 8）。 */
    public static final String KEY_PREFIX = "aihub:ratelimit:";

    public static final String FIELD_TOKENS = "t";
    public static final String FIELD_LAST_REFILL = "k";

    private static final long MIN_IDLE_TTL_MILLIS = 60_000L;

    /**
     * 一次往返、服务器端原子的令牌桶。**这段字符串是唯一真相**：gateway 的 {@code LuaTokenBucket}
     * 直接委托它，admin 侧的集成测试也读它。任何改动都必须先过 {@code RateLimitScriptTest}。
     *
     * <p>ARGV = {nowMillis, qps, burst, ttlMillis}；返回值 = {allowed, remaining, retryAfterMillis}。
     * 所有算术都用整数毫令牌（与 {@code TokenBucket} 逐字一致）：Lua 只有 double，Java 这个公式用
     * 整数才能保证「降级前后行为完全相同」。
     *
     * <p><b>新桶直接满桶</b>：当 {@code HGET} 取不到字段（新 key，返回 {@code false}）时把
     * {@code tokens} 置为 {@code capacity}、{@code lastRefill} 置为 {@code now}，**不是**用
     * {@code or '0'} 当作 0 再靠 {@code elapsed * qps} 补。理由与 {@code TokenBucket} 的类注释
     * 完全一样：{@code qps = 0} 时「从 0 补」永远补不出令牌，会把「允许 burst 次突发」退化成
     * 「一次都不放行」；而且「补得满补不满」会取决于时钟的绝对值。两侧必须是同一条规则。
     *
     * <p><b>时钟回拨没有单独的分支</b>：脚本**曾经**写过 {@code if elapsed < 0 then lastRefill = now end}，
     * 但那是死代码 —— 该变量在计算 {@code elapsed} 之后再没被读过，而基准时刻是最后那次
     * {@code HSET … 'k', now} **无条件**写进去的。于是「回拨时不补充、基准前移到 now」这条规则
     * 由两个真实生效的地方共同承载：判定处的守卫 {@code if elapsed > 0 and qps > 0 then}（负的
     * {@code elapsed} 因此不补充、令牌原样保留），加上那次无条件的 {@code HSET}。这与
     * {@code TokenBucket} 的纯算术（{@code nextState} 恒定写回 {@code now}）逐字一致。
     */
    public static final String SCRIPT = """
            local tokens = redis.call('HGET', KEYS[1], 't')
            local lastRefill = redis.call('HGET', KEYS[1], 'k')
            local now = tonumber(ARGV[1])
            local qps = tonumber(ARGV[2])
            local burst = tonumber(ARGV[3])
            if burst < 1 then burst = 1 end
            if qps < 0 then qps = 0 end
            local capacity = burst * 1000
            if tokens and lastRefill then
              tokens = tonumber(tokens)
              lastRefill = tonumber(lastRefill)
            else
              tokens = capacity
              lastRefill = now
            end
            local elapsed = now - lastRefill
            if elapsed > 0 and qps > 0 then
              tokens = math.min(capacity, tokens + elapsed * qps)
            end
            local allowed = 0
            local remaining = 0
            local retryAfter = 0
            if tokens >= 1000 then
              tokens = tokens - 1000
              allowed = 1
              remaining = math.floor(tokens / 1000)
              if remaining > burst then remaining = burst end
            else
              local missing = 1000 - tokens
              if qps > 0 then
                retryAfter = math.ceil(missing / qps)
              else
                retryAfter = 3600000
              end
              if retryAfter < 1 then retryAfter = 1 end
            end
            redis.call('HSET', KEYS[1], 't', tokens, 'k', now)
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return {allowed, remaining, retryAfter}
            """;

    private RateLimitScript() {
    }

    /**
     * 空闲 TTL：至少 1 分钟；正常取「把满桶放空所需时间」的 20 倍。
     * 太小 → 桶频繁被重建，限流会被打穿；太大 → 键不回收。
     */
    public static long idleTtlMillis(int qps, int burst) {
        int capacity = Math.max(burst, 1);
        int rate = Math.max(qps, 1);
        long timeToDrain = (long) capacity * 1000L / rate;
        return Math.max(MIN_IDLE_TTL_MILLIS, timeToDrain * 20L);
    }
}
