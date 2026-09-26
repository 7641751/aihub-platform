package com.aihub.gateway.relay;

import com.aihub.common.meter.MeteringEvent;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 计量默认关闭（见 src/test/resources/application.properties）：只有读取计量事件的
        // forgedUpstreamRequestIdNeverOverwritesTheGatewayMintedOne 需要它，故此处显式打开，
        // 并用 MeteringTestConfig 的内存投递器顶替 MQ（无 broker、无 Docker）。
        properties = {"aihub.auth.enabled=false", "aihub.metering.enabled=true"})
@Import(MeteringTestConfig.class)
class ChatRelayControllerTest {

    /** 网关自产值必然匹配的形状（{@code RequestIdFilter} 用的是 {@code UUID.randomUUID()}）。 */
    private static final String UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** 上游伪造的 {@code x-request-id}：刻意不是 UUID，一眼就能在失败输出里认出来。 */
    private static final String FORGED_REQUEST_ID = "forged-upstream-request-id";

    private static FakeUpstream upstream;

    @LocalServerPort
    private int gatewayPort;

    /** 内存投递器（不依赖 broker/Docker）：让「响应头 id == 计量事件 request_id」可断言。 */
    @Autowired
    private RecordingMeteringTransport recorder;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @BeforeEach
    void resetRecorder() {
        recorder.reset();
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
        // 顺序也是契约的一部分：只断言 contains("你")/contains("好") 的话，两帧**颠倒**同样是绿的，
        // 而客户端会拿到乱序增量。indexOf 的相对位置把「先你后好」钉死（两者必然都存在，上一行已断言）。
        assertThat(response.body().indexOf("你")).isLessThan(response.body().indexOf("好"));
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
        // 未配置 aihub.upstream.api-key 时不发 Authorization —— 空 Bearer 会被部分上游直接 401。
        // 「配了 apiKey 就发 Bearer」由 UpstreamAuthorizationTest 覆盖。
        assertThat(upstream.lastRequest().headers()).doesNotContainKey("authorization");
    }

    @Test
    void malformedAcceptHeaderDoesNotBreakTheRelay() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                null, "this-is-not/a-media-type;;;q=x");

        assertThat(response.statusCode()).isEqualTo(200);
        // 不只断言 200：空 body 的兜底响应也会是 200，必须确认 body 真的透传了。
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).contains("application/json"));
        assertThat(response.body()).contains("\"object\":\"chat.completion\"").contains("你好");
    }

    @Test
    void malformedUpstreamContentTypeRelaysStatusAndBodyInsteadOf500() throws Exception {
        upstream.enqueueRaw(429, "not a media type",
                "{\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit_error\"}}");

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                "application/json", "application/json");

        // 旧的 setContentType(...) 会在这里抛 InvalidMediaTypeException，被吞成 500 并丢掉上游状态码。
        assertThat(response.statusCode()).isEqualTo(429);
        // 头本身也原样透传（不做解析、不做归一化）。
        assertThat(response.headers().firstValue("Content-Type")).hasValue("not a media type");
        assertThat(response.body()).contains("rate limited");
    }

    @Test
    void absentUpstreamContentTypeIsNotFabricated() throws Exception {
        upstream.enqueueRaw(200, null, "no content type here");

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
        assertThat(response.body()).isEqualTo("no content type here");
    }

    /**
     * 上游 429 的 {@code Retry-After} 是客户端唯一的退避依据；网关把它吃掉等于让所有 SDK
     * 只能瞎猜重试间隔。M3 的限流治理同样依赖这条透传（台账 t1-6 的遗留项）。
     */
    @Test
    void relaysRetryAfterOnUpstream429() throws Exception {
        upstream.enqueueWithHeaders(429, "application/json; charset=utf-8",
                "{\"error\":{\"message\":\"rate limited\"}}", Map.of("Retry-After", "7"));

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).hasValue("7");
    }

    @Test
    void relaysUpstreamRateLimitHeaders() throws Exception {
        Map<String, String> headers = Map.of(
                "x-ratelimit-limit-requests", "100",
                "x-ratelimit-limit-tokens", "100000",
                "x-ratelimit-remaining-requests", "0",
                "x-ratelimit-remaining-tokens", "0",
                "x-ratelimit-reset-requests", "7s",
                "x-ratelimit-reset-tokens", "7s");
        upstream.enqueueWithHeaders(429, "application/json; charset=utf-8",
                "{\"error\":{\"message\":\"rate limited\"}}", headers);

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(429);
        headers.forEach((name, value) ->
                assertThat(response.headers().firstValue(name))
                        .as("header %s 必须原样透传", name)
                        .hasValue(value));
    }

    /**
     * 决策 1 / Task 2 的契约：{@code x-request-id} 是**网关自产**的计量幂等键，上游的同名头绝不透传。
     *
     * <p>这里的失败模式是静默的、且正好与本任务编辑过的那张白名单有关：{@code RequestIdFilter}
     * 在 {@code relay()} **之前**用 {@code headers().set(...)} 写入自产 id，而 {@code relay()} 用
     * {@code put(...)} 拷贝白名单头 —— 一旦有人把 {@code "x-request-id"} 加回
     * {@link ChatRelayController} 的 {@code RELAYED_HEADERS}，上游的值就会**覆盖**网关的值：
     * 客户端看到的 id 与计量事件的 {@code request_id} 从此分叉（幂等键/对账全部对不上），
     * 而状态码、body、既有用例**全绿**，没有任何测试会红。
     *
     * <p>因此本用例同时钉两件事：① 客户端看到的是 UUID 且不是上游伪造值；② 该 UUID 就是本次请求
     * 计量事件里的 {@code request_id}。用 {@code enqueueWithHeaders} 构造上游伪造头，
     * 用 {@code MeteringTestConfig} 的内存投递器读事件（无 broker、无 Docker）。
     */
    @Test
    void forgedUpstreamRequestIdNeverOverwritesTheGatewayMintedOne() throws Exception {
        upstream.enqueueWithHeaders(200, "application/json; charset=utf-8",
                FakeUpstream.completionJson(), Map.of(RequestIdFilter.HEADER, FORGED_REQUEST_ID));

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        assertThat(requestId)
                .as("上游伪造的 x-request-id 必须被丢弃，客户端只能看到网关自产的值")
                .isNotEqualTo(FORGED_REQUEST_ID)
                .matches(UUID_REGEX);

        // 「响应头 == 计量事件 request_id」正是被覆盖会破坏的不变式：查不到事件即说明两者分叉。
        MeteringEvent event = recorder.awaitEvent(requestId, Duration.ofSeconds(5));
        assertThat(event)
                .as("响应头 x-request-id 必须能在计量记录里找到（实际记录: %s）",
                        recorder.recordedRequestIds())
                .isNotNull();
        assertThat(event.requestId()).isEqualTo(requestId);
    }

    /**
     * M2 的透传白名单是**精确名**（前缀匹配会把白名单变成开放集合）。M3 补进了
     * {@code retry-after-ms} 与 IETF 的 {@code RateLimit-*} 三兄弟，但**不得**顺手放宽成前缀匹配
     * —— 这条用例用一个「差一点」的头名（{@code retry-after-ms-x}）证明白名单仍然是封闭集合。
     *
     * <p>{@code x-ratelimit-limit-requests} 同时也在构造里：它是 M2 就有的六个 OpenAI 名字之一，
     * 一条用例同时钉住「新增的进来了」与「已有的没被挤掉」。
     */
    @Test
    void relaysRetryAfterMsAndTheIetfRateLimitFamilyButNothingElse() throws Exception {
        upstream.enqueueWithHeaders(429, "application/json; charset=utf-8",
                "{\"error\":{\"message\":\"slow down\"}}",
                Map.of("Retry-After", "3",
                        "Retry-After-Ms", "250",
                        "RateLimit-Limit", "10, 20",
                        "RateLimit-Remaining", "0",
                        "RateLimit-Reset", "1",
                        "Retry-After-Ms-X", "should-not-pass",
                        "X-RateLimit-Limit-Requests", "100"));

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("retry-after")).contains("3");
        assertThat(response.headers().firstValue("retry-after-ms")).contains("250");
        assertThat(response.headers().firstValue("ratelimit-limit")).contains("10, 20");
        assertThat(response.headers().firstValue("ratelimit-remaining")).contains("0");
        assertThat(response.headers().firstValue("ratelimit-reset")).contains("1");
        assertThat(response.headers().firstValue("x-ratelimit-limit-requests")).contains("100");
        assertThat(response.headers().firstValue("retry-after-ms-x"))
                .as("白名单是精确名：差一点的名字不得穿过")
                .isEmpty();
    }

    /**
     * **字面量钉子**：白名单的成员资格是一个契约，既不许静默放宽（前缀匹配 / 上游随手新增的头
     * 自动穿过），也不许静默收窄（某人删掉一个名字，只会在某个下游 SDK 那里表现为「莫名其妙地
     * 拿不到退避建议」，没有任何测试会红）。
     *
     * <p>断言整个集合而不是「包含某几个」：新增一个头必须**有意**改这里，改的时候要回答
     * 「为什么这个头可以穿过网关」。同时它也钉住 {@code x-request-id} 不在其中（参见
     * {@link #forgedUpstreamRequestIdNeverOverwritesTheGatewayMintedOne}）。
     */
    @Test
    void theRelayedHeaderAllowListIsExactlyThePinnedSetOfNames() {
        assertThat(ChatRelayController.RELAYED_HEADERS).containsExactlyInAnyOrder(
                "content-type",
                "retry-after",
                "retry-after-ms",
                "x-ratelimit-limit-requests",
                "x-ratelimit-limit-tokens",
                "x-ratelimit-remaining-requests",
                "x-ratelimit-remaining-tokens",
                "x-ratelimit-reset-requests",
                "x-ratelimit-reset-tokens",
                "ratelimit-limit",
                "ratelimit-remaining",
                "ratelimit-reset");
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
