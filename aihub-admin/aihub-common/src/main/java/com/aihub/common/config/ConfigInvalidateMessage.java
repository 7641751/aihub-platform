package com.aihub.common.config;

/**
 * 一条配置失效消息（D4）：{@code version} 是 admin 抬升后的 {@code config_version} 水位（epoch 毫秒），
 * {@code reason} 是失效原因。
 *
 * <p><b>{@code reason} 是有限枚举，不是自由文本</b>（如 {@code "channel.update"} / {@code "api-key.disable"}）：
 * 它只用于日志与排障，订阅方的判定**只看 {@code version}**。
 *
 * <p>线上格式（{@code {version}|{escaped reason}}）由 {@link ConfigInvalidateCodec} 负责，
 * 两侧共用同一份实现；本 record 本身不含任何编解码逻辑。
 */
public record ConfigInvalidateMessage(long version, String reason) {
}
