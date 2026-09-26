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

    public static CircuitState closed() {
        return new CircuitState(false, SOURCE_REDIS);
    }

    public static CircuitState open(String source) {
        return new CircuitState(true, source);
    }

    public boolean degraded() {
        return SOURCE_LOCAL.equals(source);
    }
}
