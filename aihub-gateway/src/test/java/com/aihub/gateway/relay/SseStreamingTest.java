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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 的两件事，之前的测试都没钉住：
 * <ol>
 *   <li><b>逐帧 flush</b>：M1 只断言了帧的顺序与内容，一个「攒完整段再回写」的实现照样全绿
 *       （台账 t1-4 / t5-4）。本类用「上游写完第一帧就卡住、等客户端读到第一帧才放行」的握手，
 *       把缓冲实现逼成死锁 → 客户端读超时 → 用例红。</li>
 *   <li><b>客户端断连的计量</b>：设计文档 §8.1 ⑤ 要求 cancel 上游 + 按已收内容估算 token。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=false", "aihub.metering.enabled=true"})
@Import(MeteringTestConfig.class)
class SseStreamingTest {

    private static final String FIRST_FRAME = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n";
    private static final String SECOND_FRAME = "data: [DONE]\n\n";

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
    void reset() {
        recorder.reset();
    }

    @DynamicPropertySource
    static void upstreamBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> upstream.baseUrl());
    }

    @Test
    void firstFrameReachesTheClientBeforeTheUpstreamSendsTheSecond() throws Exception {
        upstream.enqueueHandshakeSse(FIRST_FRAME, SECOND_FRAME);

        HttpResponse<InputStream> response =
                HttpClient.newHttpClient().send(request(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        ExecutorService readerThread = Executors.newSingleThreadExecutor();
        try {
            Future<String> firstLine = readerThread.submit(reader::readLine);
            // 攒批实现会在这里抛 TimeoutException：上游正等「客户端读到第一帧」才放行。
            String line = firstLine.get(5, TimeUnit.SECONDS);
            assertThat(line).isEqualTo("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}");
            assertThat(upstream.secondFrameWritten())
                    .as("第一帧到达客户端时，上游不该已经写出第二帧 —— 否则说明网关在攒批")
                    .isFalse();

            upstream.releaseSecondFrame();

            assertThat(reader.lines().toList()).contains("data: [DONE]");
        } finally {
            upstream.releaseSecondFrame();
            readerThread.shutdownNow();
            response.body().close();
        }
    }

    /** 字节等同：流式响应体必须与上游发出的帧逐字节一致（含换行）。 */
    @Test
    void streamedBodyIsByteIdenticalToTheUpstreamFrames() throws Exception {
        String frames = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: [DONE]\n\n\n";
        upstream.enqueueSse(frames);

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).isEqualTo(frames);
    }

    @Test
    void clientDisconnectIsMeteredAsCancelled() throws Exception {
        upstream.enqueueHandshakeSse(FIRST_FRAME, SECOND_FRAME);

        HttpResponse<InputStream> response =
                HttpClient.newHttpClient().send(request(), HttpResponse.BodyHandlers.ofInputStream());
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        assertThat(reader.readLine()).contains("你");

        // 客户端断连：关掉响应流（JDK HttpClient 会取消这次交换），并放行上游的收尾帧。
        response.body().close();
        upstream.releaseSecondFrame();

        MeteringEvent event = recorder.awaitEvent(requestId, Duration.ofSeconds(10));
        assertThat(event).as("客户端断连后仍必须有计量事件（设计文档 §8.1 ⑤）").isNotNull();
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_CANCELLED);
        assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_CLIENT_DISCONNECTED);
        assertThat(event.completionTokens()).as("按已收内容估算").isGreaterThan(0);
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true}", StandardCharsets.UTF_8))
                .build();
    }
}
