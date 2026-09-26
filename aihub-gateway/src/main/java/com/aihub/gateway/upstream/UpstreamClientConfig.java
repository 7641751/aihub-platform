package com.aihub.gateway.upstream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 上游客户端的装配点。
 *
 * <p>M1/M2 时这里直接构造唯一一个 {@code WebClient}；M3 起渠道是**多条**，构造逻辑搬到
 * {@link UpstreamClientFactory}（按渠道 base-url + 超时缓存）。这里保留的 bean 是
 * **遗留单渠道**的客户端（{@code factory.legacy()}）：它服务于「冷启动 + admin 不可达」的兜底
 * 路径与所有既有的单渠道测试，语义与 M1 完全相同（含 {@code aihub.upstream.api-key} 的默认头）。
 * **不要删掉它**：删掉会让兜底路径丢掉上游密钥，那是一次静默的功能退化。
 */
@Configuration
@EnableConfigurationProperties(UpstreamProperties.class)
public class UpstreamClientConfig {

    @Bean
    public UpstreamClientFactory upstreamClientFactory(UpstreamProperties properties) {
        return new UpstreamClientFactory(properties);
    }

    @Bean
    public WebClient upstreamWebClient(UpstreamClientFactory factory) {
        return factory.legacy();
    }
}
