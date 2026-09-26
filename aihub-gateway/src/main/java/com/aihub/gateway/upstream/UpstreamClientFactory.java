package com.aihub.gateway.upstream;

import com.aihub.common.config.ChannelDescriptor;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按渠道构造 {@link WebClient}。渠道之间只有两件事不同：base-url 与超时 —— 密钥由控制器
 * **逐请求**注入（一个客户端实例可能被多个渠道复用，把密钥挂在客户端上必然串号）。
 *
 * <p><b>流式与非流式是两个客户端</b>：
 * <ul>
 *   <li>非流式：{@code responseTimeout = channel.timeout_ms}（这是 {@code timeout_ms} 列第一次真正生效）；</li>
 *   <li>流式：**不设响应超时**。SSE 可以合法地跑几十分钟，响应级超时会在长回答中途把流掐断 ——
 *       M1/M2 的全局客户端设了 120 秒，M3 顺手把这个隐患修掉（连接超时仍然保留 5 秒）。</li>
 * </ul>
 *
 * <p>缓存键是 {@code (baseUrl, timeoutMs, streaming)}：**不是 channelId**。渠道改了 base-url 或超时后
 * 应当立刻用新参数（否则一次配置变更要等重启才生效，与「配置热生效」的目标相反）。
 */
public class UpstreamClientFactory {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final UpstreamProperties legacyProperties;
    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    public UpstreamClientFactory(UpstreamProperties legacyProperties) {
        this.legacyProperties = legacyProperties;
    }

    /** 渠道专用客户端。**不设默认 Authorization**（由控制器按本次渠道注入）。 */
    public WebClient forChannel(ChannelDescriptor channel, boolean streaming) {
        String key = "channel|" + channel.baseUrl() + "|" + channel.timeoutMs() + "|" + streaming;
        return clients.computeIfAbsent(key, ignored -> build(channel.baseUrl(),
                streaming ? null : Duration.ofMillis(Math.max(channel.timeoutMs(), 1)), null));
    }

    /**
     * M1 形状的遗留客户端：带 {@code aihub.upstream.api-key} 的默认 Authorization。
     * **保留它**是为了让单渠道配置（含既有测试）继续按原样工作 —— 否则冷启动兜底路径
     * 会丢掉上游密钥。
     */
    public WebClient legacy() {
        String key = "legacy|" + legacyProperties.baseUrl() + "|" + legacyProperties.apiKey();
        return clients.computeIfAbsent(key, ignored -> build(legacyProperties.baseUrl(),
                Duration.ofSeconds(120), legacyProperties.apiKey()));
    }

    public int cachedClients() {
        return clients.size();
    }

    private static WebClient build(String baseUrl, Duration responseTimeout, String defaultApiKey) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS);
        if (responseTimeout != null) {
            httpClient = httpClient.responseTimeout(responseTimeout);
        }
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (StringUtils.hasText(defaultApiKey)) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + defaultApiKey);
        }
        return builder.build();
    }
}
