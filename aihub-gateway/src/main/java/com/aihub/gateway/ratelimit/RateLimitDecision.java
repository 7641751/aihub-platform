package com.aihub.gateway.ratelimit;

/**
 * 一次限流判定的结果。{@code remaining} / {@code limit} / {@code burst} 直接映射到响应头
 * （IETF {@code RateLimit-*}；网关自己回给客户端的是 IETF 那一族，见 Task 9）。
 *
 * @param allowed      是否放行
 * @param remaining    本窗口剩余请求数（放行后）
 * @param retryAfterMs 被拒时的建议退避毫秒数（放行时为 0）
 * @param limit        本策略的 qps
 * @param burst        本策略的突发上限
 * @param source       {@code REDIS}（正常）或 {@code LOCAL}（Redis 降级）
 */
public record RateLimitDecision(boolean allowed, int remaining, long retryAfterMs, int limit, int burst, Source source) {

    /** 判定发生在哪一级 —— 也是「Redis 是否降级」的唯一可观测信号（指标只按它打标签）。 */
    public enum Source {
        REDIS,
        LOCAL
    }

    public static RateLimitDecision allowed(int remaining, int limit, int burst, Source source) {
        return new RateLimitDecision(true, remaining, 0L, limit, burst, source);
    }

    public static RateLimitDecision denied(long retryAfterMs, int limit, int burst, Source source) {
        return new RateLimitDecision(false, 0, retryAfterMs, limit, burst, source);
    }

    /** 本次判定是否走了降级路径（Redis 不可用）。 */
    public boolean degraded() {
        return source == Source.LOCAL;
    }
}
