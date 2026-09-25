package com.aihub.gateway.upstream;

import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上游自己需要密钥时（例如 OpenAI），网关默认带上 {@code Authorization: Bearer <apiKey>}。
 * <p>「未配置 apiKey 就完全不发该头」由 {@code ChatRelayControllerTest#forwardsRequestBodyVerbatim} 断言
 * （那个上下文不设 {@code aihub.upstream.api-key}；两个类属性不同，因此各是一个 Spring 上下文）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=false", "aihub.upstream.api-key=upstream-secret"})
class UpstreamAuthorizationTest {

    private static FakeUpstream upstream;

    @LocalServerPort
    private int gatewayPort;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @DynamicPropertySource
    static void upstreamBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> upstream.baseUrl());
    }

    @Test
    void sendsUpstreamApiKeyAsBearerToken() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        post();

        assertThat(upstream.lastRequest().headers()).containsEntry("authorization", "Bearer upstream-secret");
    }

    private void post() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":false}", StandardCharsets.UTF_8))
                .build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
