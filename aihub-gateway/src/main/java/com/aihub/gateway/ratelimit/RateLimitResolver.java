package com.aihub.gateway.ratelimit;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.RatePolicy;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@code (tenantId, apiKeyId) → RatePolicy}。策略来自配置快照（三级缓存），因此**每次调用都惰性
 * 读快照**，快照刷新后新策略立即生效（不需要重启，也不需要清缓存）。
 *
 * <p><b>两个维度都生效</b>（决策 7，2026-09-26 依控制器 pre-flight 评审修订），顺序是确定性的：
 * <ol>
 *   <li>该 {@code (tenantId, apiKeyId)} 的 **key 级**策略存在 → **在这一维收口**，取最后一条；</li>
 *   <li>否则该租户的**租户级**策略存在 → 取最后一条；</li>
 *   <li>都没有 → 内置默认 `qps=10 / burst=20`。</li>
 * </ol>
 * {@code apiKeyId == null}（鉴权关闭 / 匿名桶 / 没有数值主键）时**直接走第二级**：不能因为拿不到
 * key 主键就把租户级策略也丢掉。
 *
 * <p>同维度多条时取**列表里的最后一条** —— 组装快照的 admin 侧已经按 {@code id} 升序排好，
 * 因此「最后一条」就是「最后插入的那条」（决策 17）。
 *
 * <p>非正的 qps/burst 是配置事故，回落到内置默认值（让 qps=0 把租户彻底打死不是想要的运维后果；
 * 要走「停用」应该改 {@code status}）。**这条按行校验规则保留原样，并且不跨维回落**：命中 key 级的
 * 那一条如果值非法，结果是**内置默认**，而不是悄悄放宽成该租户的租户级额度 —— 一条写坏的 key 级
 * 行不该变成「额度比不写还大」。
 */
public class RateLimitResolver {

    private final Supplier<ConfigSnapshot> snapshots;

    public RateLimitResolver(Supplier<ConfigSnapshot> snapshots) {
        this.snapshots = snapshots;
    }

    public RatePolicy resolve(long tenantId, Long apiKeyId) {
        ConfigSnapshot snapshot = snapshots.get();
        if (apiKeyId != null) {
            List<RatePolicy> keyLevel = snapshot.keyPolicies(tenantId, apiKeyId);
            if (!keyLevel.isEmpty()) {
                return usableOrDefault(keyLevel.get(keyLevel.size() - 1), tenantId);
            }
        }
        List<RatePolicy> tenantLevel = snapshot.tenantPolicies(tenantId);
        if (!tenantLevel.isEmpty()) {
            return usableOrDefault(tenantLevel.get(tenantLevel.size() - 1), tenantId);
        }
        return RatePolicy.defaultFor(tenantId);
    }

    /** 非正的行按「配置事故」处理：回落到内置默认（调用方已决定不再看另一维）。 */
    private static RatePolicy usableOrDefault(RatePolicy candidate, long tenantId) {
        return candidate.qps() > 0 && candidate.burst() > 0 ? candidate : RatePolicy.defaultFor(tenantId);
    }
}
