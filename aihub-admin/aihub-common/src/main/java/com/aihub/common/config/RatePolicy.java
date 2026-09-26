package com.aihub.common.config;

/**
 * 限流策略的纯数据视图（对应 {@code rate_limit_policy}）。
 *
 * <p><b>两个维度 ({@code tenantId} / {@code apiKeyId}) 都参与判定</b>（决策 7，2026-09-26 依控制器
 * pre-flight 评审修订）：{@code apiKeyId == null} 是**租户级**策略（该租户所有 key 的兜底），
 * 非空是**key 级**策略（只作用于该 {@code api_key.id}）。{@link ConfigSnapshot} 上两个维度各有
 * 一个取值入口，由 gateway 的 {@code RateLimitResolver} 按「key 级优先 → 租户级回落 → 内置默认」
 * 的顺序取（Task 4）。
 */
public record RatePolicy(Long tenantId, Long apiKeyId, int qps, int burst) {

    public static final int DEFAULT_QPS = 10;
    public static final int DEFAULT_BURST = 20;

    /** 该策略是否作用于整个租户（key 级为 false）。 */
    public boolean tenantLevel() {
        return apiKeyId == null;
    }

    /** 没有任何策略行（或策略值非法）时的兜底：与 V1 两列的默认值一致。 */
    public static RatePolicy defaultFor(long tenantId) {
        return new RatePolicy(tenantId, null, DEFAULT_QPS, DEFAULT_BURST);
    }
}
