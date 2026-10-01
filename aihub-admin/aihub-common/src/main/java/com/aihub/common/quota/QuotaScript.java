package com.aihub.common.quota;

import java.util.List;

/**
 * 配额预扣的 **Lua 脚本 + 键布局 + ARGV 顺序 + RETURN 语义**，放在共享模块里 —— 与
 * {@link com.aihub.common.ratelimit.RateLimitScript} 完全同一纪律：
 * <ol>
 *   <li>它是**跨模块契约**：gateway 跑这段脚本，而 admin 侧的集成测试（需要 Docker，因此不能放在 gateway
 *       模块）要验它；两侧必须逐字节同一份，否则「测试验过的脚本」与「线上跑的脚本」会漂移。</li>
 *   <li>它只由 JDK 的字符串常量组成，**不破坏 main 作用域零依赖**：把它包成 Spring 的
 *       {@code RedisScript} 是**调用方自己的事**（{@code RedisScript.of(QuotaScript.SCRIPT, List.class)}）。</li>
 * </ol>
 *
 * <p><b>为什么必须是 Lua</b>：预扣是「读已用量 → 判定 → 写回」的读改写三步。若由客户端分三次调用
 * Redis，并发下多个请求会读到同一个已用量、各自判定成功，于是预算被击穿（经典超发）。Lua 在 Redis 里
 * **单线程原子执行**，三步之间不可能插入别的请求，因此判定与扣减是同一个原子操作。集成测试用
 * 「50 个任务 × 16 线程、预算 1000、每次估 100、恰好放行 10 次」在真 Redis 上证明这一点。
 *
 * <p><b>配额是周期预算，不是速率</b>：所以脚本里**没有**令牌桶那样的「按时间补充」逻辑 —— 已用量只在
 * 周期内单调累加，整个桶在「下个周期开始 + 1 天」后由 {@code PEXPIRE} 自然回收（决策 D13）。
 *
 * <p><b>{@code 0} 的限额表示不限</b>（决策 D15，见 {@link #SCRIPT} 的注释）：两个维度都非 0 才逐维判定。
 */
public final class QuotaScript {

    /**
     * Lua 的整数精度上界：{@code 2^53}。ARGV 走 Lua 的 double，超过它整数就丢精度，因此限额值必须
     * 先被 {@link #assertWithinRange(long)} 挡住。
     */
    public static final long MAX_EXACT_LIMIT = 9_007_199_254_740_992L;

    /**
     * 一次往返、服务器端原子的配额预扣。**这段字符串是唯一真相**：gateway 直接委托它，admin 侧的集成
     * 测试也读它。任何改动都必须先过 {@link #keys}/{@link #args} 与 {@code QuotaContractTest}。
     *
     * <p>字段名 {@code tok}/{@code req} 与 {@link QuotaKeys#FIELD_TOKENS}/{@link QuotaKeys#FIELD_REQUESTS}
     * 必须一致（与 {@code RateLimitScript} 用字面量 {@code 't'}/{@code 'k'} 而暴露常量的做法相同）；
     * 集成测试直接读那两个常量断言桶值，因此一旦漂移会立刻变红。
     *
     * <p>KEYS[1] = {@code aihub:quota:{tenantId}:{period}}（Hash）；
     * ARGV = {@code {estimatedTokens, tokenLimit, requestLimit, ttlMillis}}；
     * 返回 = {@code {allowed(1/0), remainingTokens(-1 = 不限), remainingRequests(-1 = 不限)}}。
     */
    public static final String SCRIPT = """
            -- 配额预扣：一次往返、服务器端原子（与 RateLimitScript 同一纪律）。
            -- KEYS[1] = aihub:quota:{tenantId}:{period}   (Hash: tok = 已用 token 估算累计, req = 已用请求数)
            -- ARGV    = {estimatedTokens, tokenLimit, requestLimit, ttlMillis}
            -- 返回    = {allowed(1/0), remainingTokens(-1 = 不限), remainingRequests(-1 = 不限)}
            -- 0 的限额表示**不限**（决策 D15）：M4 上线前所有租户都没有配额行，把 0 当「额度为零」
            -- 会让升级瞬间全员 429。
            local est          = tonumber(ARGV[1])
            local tokenLimit   = tonumber(ARGV[2])
            local requestLimit = tonumber(ARGV[3])
            local ttl          = tonumber(ARGV[4])

            local used = redis.call('HMGET', KEYS[1], 'tok', 'req')
            local tokenUsed   = tonumber(used[1]) or 0
            local requestUsed = tonumber(used[2]) or 0

            local remainingTokens   = -1
            local remainingRequests = -1
            if tokenLimit > 0 then remainingTokens = math.max(0, tokenLimit - tokenUsed) end
            if requestLimit > 0 then remainingRequests = math.max(0, requestLimit - requestUsed) end

            if tokenLimit > 0 and (tokenUsed + est) > tokenLimit then
              return {0, remainingTokens, remainingRequests}
            end
            if requestLimit > 0 and (requestUsed + 1) > requestLimit then
              return {0, remainingTokens, remainingRequests}
            end

            tokenUsed = tokenUsed + est
            requestUsed = requestUsed + 1
            redis.call('HSET', KEYS[1], 'tok', tokenUsed, 'req', requestUsed)
            redis.call('PEXPIRE', KEYS[1], ttl)
            if tokenLimit > 0 then remainingTokens = math.max(0, tokenLimit - tokenUsed) end
            if requestLimit > 0 then remainingRequests = math.max(0, requestLimit - requestUsed) end
            return {1, remainingTokens, remainingRequests}
            """;

    private QuotaScript() {
    }

    /**
     * 上界校验（F4）：ARGV 走 Lua 的 double，**超过 {@code 2^53} 会丢整数精度**。
     *
     * <p>边界**只在这里定义一次**（判据是「超过」= {@code >}）；{@link com.aihub.service.quota.QuotaAdminService}
     * 调它并把异常折成 {@code BizException(INVALID_PARAM, …)} —— 别在服务层再写一个 {@code >=}，
     * 两份边界迟早会不一致。
     *
     * @throws IllegalArgumentException {@code limit > 2^53}
     */
    public static void assertWithinRange(long limit) {
        if (limit > MAX_EXACT_LIMIT) {
            throw new IllegalArgumentException("限额超过 2^53，Lua 的 double 会丢整数精度： " + limit);
        }
    }

    /**
     * 预扣脚本的 KEYS 列表。键布局的唯一真相在 {@link QuotaKeys#bucketKey(long, String)}。
     */
    public static List<String> keys(long tenantId, String period) {
        return List.of(QuotaKeys.bucketKey(tenantId, period));
    }

    /**
     * 预扣脚本的 ARGV（顺序固定为 {@code {estimatedTokens, tokenLimit, requestLimit, ttlMillis}}）。
     *
     * <p>统一转成十进制字符串：Lua 侧用 {@code tonumber(ARGV[i])} 解，避免不同序列化器对整数的表示差异。
     */
    public static List<String> args(long estimatedTokens, long tokenLimit, long requestLimit, long ttlMillis) {
        return List.of(String.valueOf(estimatedTokens), String.valueOf(tokenLimit),
                String.valueOf(requestLimit), String.valueOf(ttlMillis));
    }

    /**
     * 把脚本返回值折成 {@link QuotaDecision}。
     *
     * <p><b>必须用 {@code ((Number) raw.get(i)).longValue()}</b>：Spring Data Redis 的
     * {@code DefaultRedisScript<…, List>} 回来的是 {@code List<Long>} / {@code Number}，直接强转
     * {@code Long} 在某些驱动/版本下会 {@code ClassCastException} —— 而那个异常会被网关 catch 成
     * 「Redis 不可用」从而永久降级（静默）。
     *
     * <p><b>返回形状不是 3 个元素时抛 {@link IllegalStateException}（不是返回 null）</b>：返回 null 同样
     * 会被当成「Redis 不可用」，于是「脚本写错了」永远藏在「Redis 挂了」后面（E.4-(a)）。形状不对是
     * **缺陷**，必须响亮失败，让网关把它计到独立的 {@code aihub.quota.script_error} 上。
     *
     * @throws IllegalStateException {@code raw} 为 null 或元素个数不是 3
     */
    public static QuotaDecision parse(List<?> raw) {
        if (raw == null || raw.size() != 3) {
            throw new IllegalStateException(
                    "配额预扣脚本返回形状异常：期望 3 个元素 {allowed, remainingTokens, remainingRequests}，实际 "
                            + (raw == null ? "null" : raw.size() + " 个"));
        }
        long allowed = ((Number) raw.get(0)).longValue();
        long remainingTokens = ((Number) raw.get(1)).longValue();
        long remainingRequests = ((Number) raw.get(2)).longValue();
        return new QuotaDecision(allowed != 0L, remainingTokens, remainingRequests);
    }
}
