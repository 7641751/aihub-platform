package com.aihub.gateway.upstream;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 上游模型服务配置。**M3 起它只服务「遗留单渠道」**：真正的上游由配置快照里的
 * {@code ChannelDescriptor} 决定（多渠道、每渠道 base-url / 超时 / 密文密钥）。
 * 这组配置仍然是**冷启动 + admin 不可达时的兜底**（{@code LegacyChannel}），因此不能删。
 *
 * @param apiKey       上游自己的密钥；空串表示上游无需鉴权（例如本地 Ollama）
 * @param defaultModel 遗留单渠道场景下 {@code GET /v1/models} 回报的模型名
 */
@Validated
@ConfigurationProperties(prefix = "aihub.upstream")
public record UpstreamProperties(@NotBlank String baseUrl, String apiKey, String defaultModel) {
}
