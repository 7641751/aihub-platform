package com.aihub.gateway.quota;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.QuotaDescriptor;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code (tenantId, period) → 本周期额度}。额度**只能**来自配置快照（Task 13a 已把 {@code quota} 表
 * 镜像进快照）：网关**不连数据库**（没有该依赖），也**不每请求回源 admin**（那是 Task 14 的
 * Redis 不可用兜底路径）。因此这里收的是一个 {@link Supplier}（{@code ConfigClient::current}），
 * 每次调用惰性读快照 —— 控制面改了额度，下一个请求就生效。
 *
 * <p><b>维度是「租户 + 周期」，不是「租户 + key」</b>：{@code quota} 表只有
 * {@code uk_quota_tenant_period (tenant_id, period)}，没有 {@code api_key_id}。
 *
 * <p><b>没有行 或 两个限额都是 0 表示不限</b>（决策 D15）：M4 上线前所有租户都没有配额行，
 * 把「0」当成「额度为零」会让升级瞬间全员 429。返回空 {@link Optional} = 不限。
 */
public class QuotaResolver {

    private final Supplier<ConfigSnapshot> snapshots;

    public QuotaResolver(Supplier<ConfigSnapshot> snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * 本 {@code (tenantId, period)} 生效的额度；空 = **不限**（无行，或两个限额都为 0）。
     *
     * @param period UTC 的 {@code YYYYMM}（{@code QuotaPeriod.of}）
     */
    public Optional<QuotaDescriptor> resolve(long tenantId, String period) {
        return snapshots.get().quota(tenantId, period).filter(QuotaResolver::limited);
    }

    /** 「是否受限」只有这一处定义：任一维限额为正即受限（0 = 该维不限，D15）。 */
    private static boolean limited(QuotaDescriptor quota) {
        return quota.tokenLimit() > 0 || quota.requestLimit() > 0;
    }
}
