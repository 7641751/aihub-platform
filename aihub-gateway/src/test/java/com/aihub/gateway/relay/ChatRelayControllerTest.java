package com.aihub.gateway.relay;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aihub.auth.enabled=false")
class ChatRelayControllerTest {

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
    void relaysSseFramesWhenClientAcceptsJson() throws Exception {
        upstream.enqueueSse(FakeUpstream.sseFrames());

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":true}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).contains("text/event-stream"));
        assertThat(response.body()).contains("你").contains("好").contains("[DONE]");
    }

    @Test
    void relaysNonStreamingJsonBody() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).contains("application/json"));
        assertThat(response.body()).contains("\"object\":\"chat.completion\"").contains("你好");
    }

    @Test
    void propagatesUpstreamErrorStatusAndBody() throws Exception {
        upstream.enqueueJson(429, "{\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit_error\"}}");

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("rate limited");
    }

    @Test
    void forwardsRequestBodyVerbatim() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        post("/v1/chat/completions", "{\"stream\":false,\"messages\":[]}", "application/json", null);

        assertThat(upstream.lastRequest().body()).isEqualTo("{\"stream\":false,\"messages\":[]}");
        assertThat(upstream.lastRequest().path()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void malformedAcceptHeaderDoesNotBreakTheRelay() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                null, "this-is-not/a-media-type;;;q=x");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> post(String path, String body, String contentType, String accept)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + path))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (accept != null) {
            builder.header("Accept", accept);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
