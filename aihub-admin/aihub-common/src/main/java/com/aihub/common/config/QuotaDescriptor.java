package com.aihub.common.config;

/**
 * 快照里的一份**租户周期额度**：{@code quota} 表按 {@code (tenant_id, period)} 的一行镜像。
 *
 * <p><b>维度是「租户 + 周期」，不是「租户 + key」</b>：{@code quota} 表只有
 * {@code uk_quota_tenant_period (tenant_id, period)}，**没有 {@code api_key_id}** —— 配额是租户级的
 * 周期预算，不要照抄限流的「两维」结构（决策 D13）。
 *
 * <p><b>{@code period} 是 UTC 的 {@code YYYYMM}</b>（{@link com.aihub.common.quota.QuotaPeriod#of}，
 * 决策 D13）。
 *
 * <p><b>没有行 或 {@code tokenLimit == 0} 表示不限</b>（决策 D15）：M4 上线前所有租户都没有配额行，
 * 把「0」当成「额度为零」会让升级瞬间全员 429；{@code requestLimit} 同理。
 *
 * <p><b>这是「控制面写、数据面读」的镜像</b>：{@code tokenLimit} / {@code requestLimit} 由控制面
 * （{@code QuotaAdminService}）写；数据面（网关）**只读**它们。已用量（{@code token_used} /
 * {@code request_used}）是 Redis 预扣 + 每日对账的账，**不进快照**（配额判定不许依赖控制面的已用量，
 * 那是数据面的内部状态）。
 *
 * @param tenantId     租户 id
 * @param period       UTC 的 {@code YYYYMM}
 * @param tokenLimit   周期 token 上限；{@code 0} = 不限
 * @param requestLimit 周期请求数上限；{@code 0} = 不限
 */
public record QuotaDescriptor(long tenantId, String period, long tokenLimit, long requestLimit) {
}
