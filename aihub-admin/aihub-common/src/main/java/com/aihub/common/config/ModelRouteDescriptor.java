package com.aihub.common.config;

/**
 * 一条「模型 → 渠道」候选。权重与优先级取 {@code model_route} 的值
 * （**不是** {@code channel} 的默认值）：同一个渠道可以给不同模型不同的权重，这是灰度与容量的常用手段。
 */
public record ModelRouteDescriptor(String modelName, long channelId, int weight, int priority, String status) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public boolean usable() {
        return STATUS_ACTIVE.equals(status) && modelName != null && !modelName.isBlank();
    }
}
