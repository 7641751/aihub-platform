package com.aihub.common.config;

/**
 * 一条上游渠道的**纯数据视图**。admin 从 {@code channel} 表组装（含 AES-GCM 密文），
 * gateway 解密后使用；字段与 V1 的列一一对应，因此两侧不需要各自定义 DTO。
 *
 * <p>{@code apiKeyCipher} 是**密文**：明文渠道密钥永不跨越网络（设计文档 §6.1）。
 *
 * @param id           {@code channel.id}（网关放进计量事件的 {@code channel_id}）
 * @param name         渠道名，仅用于日志与运维定位（**明文密钥绝不出现在日志里**）
 * @param baseUrl      上游 base-url
 * @param apiKeyCipher AES-GCM 自描述密文（{@code v{n}:{base64}}）
 * @param keyVersion   admin 侧记录的版本号（展示/巡检用；解密以密文里的标签为准）
 * @param timeoutMs    单次上游请求的超时（**只对非流式生效**；流式不设响应超时，见 Task 8）
 * @param status       {@code ACTIVE} / {@code DISABLED}
 * @param weight       渠道级默认权重（**路由权重以 {@code model_route.weight} 为准**）
 * @param priority     渠道级默认优先级（**路由优先级以 {@code model_route.priority} 为准**）
 */
public record ChannelDescriptor(long id, String name, String baseUrl, String apiKeyCipher, int keyVersion,
                                int timeoutMs, String status, int weight, int priority) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 「这条渠道现在能用吗」的**唯一**判据（与 {@code ApiKeyView.usable()} 同一套纪律：只有一处定义）。 */
    public boolean usable() {
        return STATUS_ACTIVE.equals(status)
                && baseUrl != null && !baseUrl.isBlank()
                && timeoutMs > 0;
    }
}
