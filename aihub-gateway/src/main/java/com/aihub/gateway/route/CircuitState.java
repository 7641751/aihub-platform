package com.aihub.gateway.route;

/**
 * 一条渠道的熔断状态。
 *
 * <p>{@code source} 只用于日志与指标：它记录「这个判断是从 Redis 读到的，还是本机降级表里的」。
 * 运维含义完全不同 —— 前者是全局共识，后者只代表本实例。
 */
public record CircuitState(boolean open, String source) {

    public static final String SOURCE_REDIS = "redis";
    public static final String SOURCE_LOCAL = "local";

    /** 「未熔断」且判断来自 Redis（跨实例权威）。 */
    public static CircuitState closed() {
        return closed(SOURCE_REDIS);
    }

    /**
     * 「未熔断」但判断来自指定来源。
     *
     * <p>降级路径（Redis 是黑的、从未被成功咨询过）必须用 {@link #SOURCE_LOCAL} 而不是
     * {@link #SOURCE_REDIS}，否则指标侧无法把「Redis 说健康」与「Redis 是黑的」分开。
     */
    public static CircuitState closed(String source) {
        return new CircuitState(false, source);
    }

    public static CircuitState open(String source) {
        return new CircuitState(true, source);
    }

    public boolean degraded() {
        return SOURCE_LOCAL.equals(source);
    }
}
