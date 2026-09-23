package com.aihub.gateway.upstream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上游模型服务配置。M0 只有单渠道的 base-url；
 * M3 引入多渠道后，这里会扩展为「渠道快照」的一部分。
 */
@ConfigurationProperties(prefix = "aihub.upstream")
public record UpstreamProperties(String baseUrl) {
}
