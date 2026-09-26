package com.aihub.common.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一次 {@code GET /internal/config/snapshot} 的完整结果：渠道（含密文）+ 路由 + 限流策略 + 默认模型
 * + 单调递增的 {@code version}。
 *
 * <p>{@code version} 是 §6.3「本地版本落后则丢弃并回源」的判据，也是缓存失效的唯一信号
 * （M3 没有 Pub/Sub 发布方，见计划决策 16）。
 *
 * <p>{@link #channelsSupporting(String)} / {@link #routesFor(String)} 是路由的**唯一**入口：
 * 它们把「路由行」与「渠道行」的联表逻辑收在一处，避免「admin 与 gateway 各拼一份候选」这种必然漂移的写法。
 */
public record ConfigSnapshot(long version, long generatedAtEpochMilli,
                             List<ChannelDescriptor> channels, List<ModelRouteDescriptor> routes,
                             List<RatePolicy> ratePolicies, String defaultModel) {

    public ConfigSnapshot {
        channels = List.copyOf(channels);
        routes = List.copyOf(routes);
        ratePolicies = List.copyOf(ratePolicies);
    }

    /** 冷启动 + admin 不可达时的空快照（调用方再决定是否回落「遗留单渠道」）。 */
    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(0L, 0L, List.of(), List.of(), List.of(), null);
    }

    public Optional<ChannelDescriptor> channel(long id) {
        return channels.stream().filter(channel -> channel.id() == id).findFirst();
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
