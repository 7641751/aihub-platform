package com.aihub.common.quota;

/**
 * 配额桶的 **Redis 键布局与 TTL 公式**，放在共享模块里（与 {@code RateLimitScript} 同一纪律）：
 * <ol>
 *   <li>它是**跨模块契约**：gateway 跑预扣脚本，而 admin 侧的集成测试（需要 Docker，因此不能放在 gateway
 *       模块）要验它；两侧必须逐字节同一份键与字段名，否则「测试验过的键」与「线上写的键」会漂移。</li>
 *   <li>它只由 JDK 的字符串常量组成，**不破坏 main 作用域零依赖**。</li>
 * </ol>
 *
 * <p>桶键 {@code aihub:quota:{tenantId}:{period}}；两个字段 {@value #FIELD_TOKENS}（已用 token 估算累计）
 * 与 {@value #FIELD_REQUESTS}（已用请求数）也是契约的一部分：运维用 {@code HGETALL} 巡检桶时必须认识它们，
 * 测试也用它们断言桶值。
 *
 * <p><b>配额是「租户级」的</b>：{@code quota} 表只有 {@code (tenant_id, period)}（V1 的
 * {@code uk_quota_tenant_period}），**没有 {@code api_key_id}** —— 桶键因此只按租户 + 周期建，
 * 不要照抄限流的「两维」结构。
 */
public final class QuotaKeys {

    /** 桶键前缀：完整键是 {@code aihub:quota:{tenantId}:{period}}。 */
    public static final String KEY_PREFIX = "aihub:quota:";

    /** 已用 token 估算累计的字段名（{@code Hash} 的字段）。 */
    public static final String FIELD_TOKENS = "tok";

    /** 已用请求数的字段名。 */
    public static final String FIELD_REQUESTS = "req";

    /** 「下个周期开始 + 1 天」里的那 1 天（决策 D13）。 */
    private static final long ONE_DAY_MILLIS = 86_400_000L;

    private QuotaKeys() {
    }

    /**
     * 桶键：{@code aihub:quota:{tenantId}:{period}}。
     *
     * @param tenantId 租户 id（配额是租户维度，没有 key 维度）
     * @param period   UTC 的 {@code YYYYMM}
     */
    public static String bucketKey(long tenantId, String period) {
        return KEY_PREFIX + tenantId + ":" + period;
    }

    /**
     * 桶的存活时间（毫秒）：从 {@code nowEpochMillis} 到「**下个周期开始 + 1 天**」。
     *
     * <p>为什么不是「到下个周期开始」就够（决策 D13）：跨月的边界请求（月末最后一个请求）、以及每日对账
     * 对已用量的补扣，都需要在原周期的桶还存在时被记；+1 天把这两件事都罩住。取「+1 天」而不是固定值，
     * 是为了让桶的生命周期跟着周期走，而不是跟着部署时长走。
     *
     * @param period          UTC 的 {@code YYYYMM}
     * @param nowEpochMillis  调用方的时间基准（沿用 M3 决策 9：不用 Redis 的 {@code TIME}）
     * @return 到期时刻减 {@code now} 的毫秒数（可能为负 —— 调用方若传了已经过期的 period，由 Redis 自行处理）
     */
    public static long ttlMillis(String period, long nowEpochMillis) {
        return QuotaPeriod.nextPeriodStartMillis(period) + ONE_DAY_MILLIS - nowEpochMillis;
    }
}
