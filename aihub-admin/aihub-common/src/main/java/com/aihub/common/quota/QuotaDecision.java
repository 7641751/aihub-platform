package com.aihub.common.quota;

/**
 * 一次预扣判定的纯数据（{@link QuotaScript#parse} 的产物）。
 *
 * <ul>
 *   <li>{@code allowed}：是否放行这次请求；</li>
 *   <li>{@code remainingTokens} / {@code remainingRequests}：判定后该维度的剩余额度，{@code -1} 表示
 *       **该维度不限**（限额为 {@code 0}，D15）。用 {@code -1} 而不是某个大数，是为了让「不限」与
 *       「额度恰好剩余很多」在日志/指标里一眼可辨。</li>
 * </ul>
 *
 * <p>只由 JDK 类型组成，放在 {@code aihub-common} 不破坏零依赖；把它包成 Spring 的
 * {@code RedisScript} 是**调用方自己的事**（与 {@code RateLimitScript} 同一纪律）。
 */
public record QuotaDecision(boolean allowed, long remainingTokens, long remainingRequests) {
}
