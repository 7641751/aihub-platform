package com.aihub.common.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一次 {@code GET /internal/config/snapshot} 的完整结果：渠道（含密文）+ 路由 + 限流策略 + 默认模型
 * + **租户周期额度** + 单调递增的 {@code version}。
 *
 * <p>{@code version} 是 §6.3「本地版本落后则丢弃并回源」的判据，也是缓存失效的唯一信号
 * （M3 没有 Pub/Sub 发布方，见计划决策 16）。
 *
 * <p>{@link #channelsSupporting(String)} / {@link #routesFor(String)} 是路由的**唯一**入口：
 * 它们把「路由行」与「渠道行」的联表逻辑收在一处，避免「admin 与 gateway 各拼一份候选」这种必然漂移的写法。
 *
 * <p><b>额度（{@code quotas}，Task 13 新增）</b>：控制面把 {@code quota} 表按 {@code (tenant_id, period)}
 * 的额度行镜像进快照，数据面（网关）从快照里读它 —— **网关不连数据库**，这是额度到达数据面的唯一通路。
 * 没有行 或 {@code tokenLimit == 0} 表示**不限**（决策 D15）。见 {@link #quota(long, String)}。
 */
public record ConfigSnapshot(long version, long generatedAtEpochMilli,
                             List<ChannelDescriptor> channels, List<ModelRouteDescriptor> routes,
                             List<RatePolicy> ratePolicies, String defaultModel,
                             List<QuotaDescriptor> quotas) {

    public ConfigSnapshot {
        channels = List.copyOf(channels);
        routes = List.copyOf(routes);
        ratePolicies = List.copyOf(ratePolicies);
        quotas = List.copyOf(quotas);
    }

    /**
     * 不携带额度的六参便捷构造器：保留给**不关心额度**的既有调用点（遗留单渠道兜底、测试夹具等）。
     * 它把 {@code quotas} 默认成空表（= 所有租户都不限，决策 D15）—— 与「没有配额行」在行为上等价。
     *
     * <p>它存在的理由是**限定改动面**：给 record 加一个分量会打断全部 6 参构造点，而它们几乎都与额度无关。
     * 真正要携带额度的调用点（{@code ConfigSnapshotService} / {@code ConfigSnapshotCodec}）显式走七参构造器。
     */
    public ConfigSnapshot(long version, long generatedAtEpochMilli,
                          List<ChannelDescriptor> channels, List<ModelRouteDescriptor> routes,
                          List<RatePolicy> ratePolicies, String defaultModel) {
        this(version, generatedAtEpochMilli, channels, routes, ratePolicies, defaultModel, List.of());
    }

    /** 冷启动 + admin 不可达时的空快照（调用方再决定是否回落「遗留单渠道」）。 */
    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(0L, 0L, List.of(), List.of(), List.of(), null);
    }

    public Optional<ChannelDescriptor> channel(long id) {
        return channels.stream().filter(channel -> channel.id() == id).findFirst();
    }

    /**
     * 该 {@code (tenantId, period)} 的额度行（Task 13：数据面从快照里读额度）。
     *
     * <p><b>按「租户 + 周期」精确匹配</b>：{@code period} 是 UTC 的 {@code YYYYMM}
     * （{@link com.aihub.common.quota.QuotaPeriod#of}），**不是**「只看租户」—— 同一租户在不同周期各有
     * 一行，跨周期取错会让上个月的额度（或某个月的空配额）在下个月生效。
     *
     * <p>返回空表示**不限**（决策 D15：没有行 = 与 M3 一致）；返回的行里 {@code tokenLimit == 0} 同样
     * 表示**不限**。判定「是否受限」由数据面（{@code QuotaResolver}）负责，本方法只做查找。
     */
    public Optional<QuotaDescriptor> quota(long tenantId, String period) {
        if (period == null) {
            return Optional.empty();
        }
        return quotas.stream()
                .filter(quota -> quota.tenantId() == tenantId && period.equals(quota.period()))
                .findFirst();
    }

    /** 某个模型对应的路由行（**route 级 weight/priority 的来源**）。 */
    public List<ModelRouteDescriptor> routesFor(String model) {
        if (model == null || model.isBlank()) {
            return List.of();
        }
        List<ModelRouteDescriptor> matched = new ArrayList<>();
        for (ModelRouteDescriptor route : routes) {
            if (route.usable() && model.equals(route.modelName())) {
                matched.add(route);
            }
        }
        return matched;
    }

    /**
     * 某个模型可以走的渠道（**顺序保持 {@code routes} 的顺序**；同一渠道不重复）。
     * 选择算法在 gateway 的 {@code RouteResolver} 里，本方法只负责联表与过滤。
     */
    public List<ChannelDescriptor> channelsSupporting(String model) {
        List<ChannelDescriptor> candidates = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (ModelRouteDescriptor route : routesFor(model)) {
            if (!seen.add(route.channelId())) {
                continue;
            }
            channel(route.channelId()).filter(ChannelDescriptor::usable).ifPresent(candidates::add);
        }
        return candidates;
    }

    /**
     * 该租户的**租户级**策略（{@code apiKeyId == null}，决策 7 的第二级）；
     * 顺序 = 组装顺序（admin 已按 id 升序），因此「取最后一条」= 「取 id 最大的那条」。
     */
    public List<RatePolicy> tenantPolicies(long tenantId) {
        List<RatePolicy> matched = new ArrayList<>();
        for (RatePolicy policy : ratePolicies) {
            if (policy.tenantLevel() && policy.tenantId() != null && policy.tenantId() == tenantId) {
                matched.add(policy);
            }
        }
        return matched;
    }

    /**
     * 该租户、该 key 的 **key 级**策略（{@code apiKeyId} 非空且相等 —— 决策 7 的第一级）。
     * 顺序同上，因此「取最后一条」同样等于「取 id 最大的那条」。
     *
     * <p>它是 {@code RateLimitResolver} 的**第一优先**来源：这一维**只要有一行**就由它收口
     * （值非法则回落到内置默认，**不**再去看租户级那一维 —— 一条写坏的 key 级行不该变成
     * 「额度比不写还大」）。**这里不做值校验**（非正的 qps/burst 由 resolver 判定），
     * 本方法只负责「哪些行属于这个 (租户, key)」。
     */
    public List<RatePolicy> keyPolicies(long tenantId, long apiKeyId) {
        List<RatePolicy> matched = new ArrayList<>();
        for (RatePolicy policy : ratePolicies) {
            if (!policy.tenantLevel() && policy.tenantId() != null && policy.tenantId() == tenantId
                    && policy.apiKeyId() == apiKeyId) {
                matched.add(policy);
            }
        }
        return matched;
    }

    /** {@code GET /v1/models} 的数据源（排序去重，便于测试与客户端缓存）。 */
    public Set<String> modelNames() {
        Set<String> names = new TreeSet<>();
        for (ModelRouteDescriptor route : routes) {
            if (route.usable()) {
                names.add(route.modelName());
            }
        }
        return names;
    }
}
