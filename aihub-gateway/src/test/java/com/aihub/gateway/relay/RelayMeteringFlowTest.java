package com.aihub.gateway.relay;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import com.aihub.gateway.testsupport.RecordingMeteringTransport;
import com.aihub.gateway.trace.RequestIdFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端计量：真实 HTTP 进网关 → 中继到假上游 → 计量事件被投递（这里投给内存记录器）。
 * <p>鉴权**开着**（用 {@code @Primary} 假 AdminClient 提供 ApiKeyView），因此能钉住
 * 「事件里的 tenantId 来自鉴权结果」以及「响应头 {@code x-request-id} == 事件的 request_id」
 * 这两条最容易写错的契约。Redis 指向不存在的端口，必然 miss 后回源假 admin（与 M1 的
 * {@code ApiKeyAuthFilterTest} 同一套手法）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MeteringTestConfig.class)
class RelayMeteringFlowTest {

    private static final String SECRET = "metering-flow-secret";
    private static final String VALID_HASH = sha256Hex(SECRET);
    private static final Path SPOOL_DIR = createTempDir();

    private static FakeUpstream upstream;

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private RecordingMeteringTransport recorder;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void resetRecorder() {
        recorder.reset();
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("aihub-metering-spool");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> upstream.baseUrl());
        registry.add("aihub.upstream.default-model", () -> "flow-model");
        registry.add("aihub.auth.enabled", () -> "true");
        registry.add("aihub.internal.secret", () -> "test-internal-secret");
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("aihub.metering.enabled", () -> "true");
        registry.add("aihub.metering.spool-dir", () -> SPOOL_DIR.toString());
    }

    @TestConfiguration
    static class FakeAdmin {
        @Bean
        @Primary
        AdminClient adminClient() {
            return keyHash -> keyHash.equals(VALID_HASH)
                    ? Mono.just(Optional.of(new ApiKeyView("ak_flow", 7L, "demo", ApiKeyView.STATUS_ACTIVE, null, 42L)))
                    : Mono.just(Optional.empty());
        }
    }

    @Test
    void nonStreamingRequestPublishesMeteringWithExactUsage() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"deepseek-chat\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(200);
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        MeteringEvent event = recorder.awaitEvent(requestId, Duration.ofSeconds(5));
        assertThat(event).as("计量事件必须与响应头 x-request-id 一一对应").isNotNull();
        assertThat(event.tenantId()).isEqualTo(7L);
        assertThat(event.model()).isEqualTo("deepseek-chat");
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_SUCCESS);
        assertThat(event.errorCode()).isNull();
        // FakeUpstream.completionJson() 里的 usage 是 1/2/3。
        assertThat(event.promptTokens()).isEqualTo(1);
        assertThat(event.completionTokens()).isEqualTo(2);
        assertThat(event.totalTokens()).isEqualTo(3);
        assertThat(event.ttftMs()).isNull();
        assertThat(event.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    /**
     * 决策 6 的**端到端**钉子：非流式请求的 {@code ttft_ms} 必须是 NULL。
     * <p>它专门防 {@code UsageCapture.ttftMs()}（无论是否流式都测「首个响应体字节」）被误当作落库值
     * 使用 —— 只有 {@code Captured.ttftMs()} 才是「非流式 ⇒ null」的那个。这里非流式响应体确实
     * 到达过网关（body 断言保证），所以「测到过首字节却仍然记 NULL」这一条是真的被验证了。
     */
    @Test
    void nonStreamingRequestRecordsNoTtftEvenThoughBodyBytesArrived() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"deepseek-chat\",\"stream\":false}");

        assertThat(response.body()).contains("chat.completion");
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        MeteringEvent event = recorder.awaitEvent(requestId, Duration.ofSeconds(5));
        assertThat(event).isNotNull();
        assertThat(event.ttftMs()).isNull();
        // 同一事件的 token 是精确值：证明这一轮真的解析到了响应体，而不是「什么都没收到所以 ttft 为空」。
        assertThat(event.totalTokens()).isEqualTo(3);
    }

    @Test
    void streamingRequestPublishesMeteringFromTheFinalUsageFrame() throws Exception {
        upstream.enqueueSse("""
                data: {"choices":[{"delta":{"content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":4,"completion_tokens":5,"total_tokens":9}}

                data: [DONE]

                """);

        HttpResponse<String> response = post("{\"model\":\"deepseek-chat\",\"stream\":true}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("[DONE]");
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        MeteringEvent event = recorder.awaitEvent(requestId, Duration.ofSeconds(5));
        assertThat(event).isNotNull();
        assertThat(event.totalTokens()).isEqualTo(9);
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_SUCCESS);
        assertThat(event.errorCode()).isNull();
        assertThat(event.ttftMs()).isNotNull();
    }

    /** 上游必须真的收到 {@code include_usage}，否则最后一帧永远不会带 usage。 */
    @Test
    void streamingRequestBodyCarriesIncludeUsageUpstream() throws Exception {
        upstream.enqueueSse(FakeUpstream.sseFrames());

        post("{\"model\":\"deepseek-chat\",\"stream\":true}");

        assertThat(upstream.lastRequest().body()).contains("\"include_usage\":true");
    }

    /**
     * 字节级透传（M1 铁律）在**计量开着**时的钉子：观察者必须只复制、不消费。
     * <p>两条断言缺一不可：
     * <ul>
     *   <li>流式响应以 {@code data: [DONE]\n\n} 收尾 —— 观察者若用 {@code read(...)} /
     *       {@code DataBufferUtils.join} 这类会推进读写位置的读法，尾部就会缺字节，这条立刻变红
     *       （而只断言「body 里含 [DONE]」的写法仍可能绿）；</li>
     *   <li>非流式 body 与上游发出的字节**完全相等** —— 非流式是单块 body，任何字节丢失或重排都逃不掉。</li>
     * </ul>
     */
    @Test
    void meteringObserverDoesNotAlterTheRelayedBytes() throws Exception {
        upstream.enqueueSse(FakeUpstream.sseFrames());
        HttpResponse<String> streamed = post("{\"model\":\"deepseek-chat\",\"stream\":true}");

        assertThat(streamed.body()).endsWith("data: [DONE]\n\n");

        upstream.enqueueJson(200, FakeUpstream.completionJson());
        HttpResponse<String> json = post("{\"model\":\"deepseek-chat\",\"stream\":false}");

        assertThat(json.body()).isEqualTo(FakeUpstream.completionJson());
    }

    @Test
    void upstreamErrorIsRelayedAndMeteredAsError() throws Exception {
        upstream.enqueueJson(429, "{\"error\":{\"message\":\"rate limited\"}}");

        HttpResponse<String> response = post("{\"model\":\"deepseek-chat\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("rate limited");
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        MeteringEvent event = recorder.awaitEvent(requestId, Duration.ofSeconds(5));
        assertThat(event).isNotNull();
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_ERROR);
        assertThat(event.errorCode()).isEqualTo("upstream_http_429");
        assertThat(event.totalTokens()).isZero();
    }

    /**
     * M2 决策 8 留下的缺口（{@code api_key_id} / {@code channel_id} 恒为 NULL）在本里程碑关闭：
     * {@code apiKeyId} 来自共享的 {@code ApiKeyView}（决策 14），{@code channelId} 来自路由结果
     * （多渠道让「哪条渠道服务了这次请求」第一次有含义）。单渠道兜底路径会写
     * {@code LegacyChannel.ID}（哨兵，不是 NULL），因此「走了兜底」在库里可查。
     *
     * <p>判别性：把 {@code RelayMetering.start} 里的 {@code view.apiKeyId()} 改回 {@code null}
     * （M2 的写法），第一条断言立刻红 —— 这条链路（视图 → 事件 → MQ → request_log.api_key_id）
     * 正是本任务要闭合的那一段。
     */
    @Test
    void meteringCarriesTheNumericApiKeyIdAndTheServingChannelId() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"flow-model\",\"stream\":false}");

        MeteringEvent event = recorder.awaitEvent(
                response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow(), Duration.ofSeconds(5));
        assertThat(event).isNotNull();
        assertThat(event.apiKeyId()).as("api_key_id 取自鉴权视图（Task 2 的数值主键）").isEqualTo(42L);
        assertThat(event.channelId()).as("兜底单渠道也必须给出可区分的渠道 id").isNotNull();
        assertThat(event.channelId())
                .as("没有快照时服务的是遗留兜底渠道：库里因此能区分「走了兜底」")
                .isEqualTo(LegacyChannel.ID);
    }

    /**
     * 决策 15：超长 model **只**在计量事件里被截断到 128（{@code request_log.model} 是 VARCHAR(128)），
     * 转发给上游的请求体**逐字节不变**。M2 时这一行会 INSERT 失败 → 重试 3 次 → 进死信队列，
     * 而 DLQ 是任何持合法 API Key 的客户端都能触碰的入口。
     *
     * <p>两条断言必须同时成立：只断言「事件被截断」时，一个把截断错放在转发路径上的实现照样绿 ——
     * 那是客户端可见的行为改变，违反 M1 的字节级透传铁律。因此这里把上游真正收到的 body 与
     * 客户端发出的 body 做**整体相等**断言（不是 contains）。
     */
    @Test
    void oversizedModelIsTruncatedInTheEventButForwardedVerbatim() throws Exception {
        String longModel = "m".repeat(200);
        String clientBody = "{\"model\":\"" + longModel + "\",\"stream\":false}";
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post(clientBody);

        MeteringEvent event = recorder.awaitEvent(
                response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow(), Duration.ofSeconds(5));
        assertThat(event).isNotNull();
        assertThat(event.model()).hasSize(128);
        assertThat(event.model()).isEqualTo(longModel.substring(0, 128));
        assertThat(upstream.lastRequest().body())
                .as("上游必须收到客户端原始的请求体，逐字节相同（截断只发生在计量事件里）")
                .isEqualTo(clientBody);
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ak_flow." + SECRET)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    static String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
