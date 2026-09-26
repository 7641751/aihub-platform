package com.aihub.gateway.upstream;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道级客户端工厂：每个渠道有自己的 base-url 与超时。
 *
 * <p>最关键的一条是**流式客户端不设 {@code responseTimeout}**：SSE 可以合法地跑很久
 * （M1/M2 的全局客户端设了 120 秒响应超时，长回答会在中途被掐断 —— M3 顺手把这个隐患修掉）。
 */
class UpstreamClientFactoryTest {

    private static FakeUpstream upstream;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    private static UpstreamProperties legacy(String apiKey) {
        return new UpstreamProperties(upstream.baseUrl(), apiKey, "legacy-model");
    }

    private static ChannelDescriptor channel(long id, String baseUrl, int timeoutMs) {
        return new ChannelDescriptor(id, "ch-" + id, baseUrl, "v1:QUJD", 1, timeoutMs,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    @Test
    void legacyClientCarriesTheConfiguredBearerToken() {
        WebClient client = new UpstreamClientFactory(legacy("legacy-bearer")).legacy();

        upstream.enqueueJson(200, FakeUpstream.completionJson());
        client.post().uri("/v1/chat/completions").bodyValue("{}").retrieve().bodyToMono(String.class).block();

        assertThat(upstream.lastRequest().headers()).containsEntry("authorization", "Bearer legacy-bearer");
    }

    /** 渠道密钥由控制器**逐请求**注入（不同渠道不同密钥，不能挂在客户端上）。 */
    @Test
    void channelClientCarriesNoDefaultBearerToken() {
        WebClient client = new UpstreamClientFactory(legacy("legacy-bearer"))
                .forChannel(channel(11L, upstream.baseUrl(), 5_000), false);

        upstream.enqueueJson(200, FakeUpstream.completionJson());
        client.post().uri("/v1/chat/completions").bodyValue("{}").retrieve().bodyToMono(String.class).block();

        assertThat(upstream.lastRequest().headers()).doesNotContainKey("authorization");
    }

    @Test
    void streamingClientHasNoResponseTimeout() {
        // 渠道超时只有 50ms；流式客户端若不设响应超时，整段 SSE 仍然读得完。
        WebClient streaming = new UpstreamClientFactory(legacy(null))
                .forChannel(channel(11L, upstream.baseUrl(), 50), true);

        upstream.enqueueSse(FakeUpstream.sseFrames());
        String body = streaming.post().uri("/v1/chat/completions").bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));

        assertThat(body).contains("data: [DONE]");
    }

    @Test
    void clientsAreCachedByBaseUrlTimeoutAndMode() {
        UpstreamClientFactory factory = new UpstreamClientFactory(legacy(null));

        WebClient first = factory.forChannel(channel(11L, upstream.baseUrl(), 5_000), false);
        WebClient second = factory.forChannel(channel(11L, upstream.baseUrl(), 5_000), false);
        WebClient differentTimeout = factory.forChannel(channel(11L, upstream.baseUrl(), 9_000), false);
        WebClient streaming = factory.forChannel(channel(11L, upstream.baseUrl(), 5_000), true);

        assertThat(first).isSameAs(second);
        assertThat(differentTimeout).isNotSameAs(first);
        assertThat(streaming).isNotSameAs(first);
        assertThat(factory.cachedClients()).isEqualTo(3);
    }

    /** 一个立刻超时的通道（连不上）必须抛 WebClientRequestException，而不是挂住。 */
    @Test
    void unreachableChannelFailsFast() {
        WebClient client = new UpstreamClientFactory(legacy(null))
                .forChannel(channel(12L, "http://127.0.0.1:1", 500), false);

        assertThatThrownBy(() -> client.post().uri("/v1/chat/completions").bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)))
                .isInstanceOf(WebClientRequestException.class);
    }
}
