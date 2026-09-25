package com.aihub.gateway.upstream;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 上游模型服务配置。M1 仍是单渠道（渠道管理是 M4 的事）；
 * M3 引入多渠道后，这里会扩展为「渠道快照」的一部分。
 *
 * @param apiKey       上游自己的密钥；空串表示上游无需鉴权（例如本地 Ollama）
 * @param defaultModel 单渠道场景下 {@code GET /v1/models} 回报的模型名
 */
@Validated
@ConfigurationProperties(prefix = "aihub.upstream")
public record UpstreamProperties(@NotBlank String baseUrl, String apiKey, String defaultModel) {
}
