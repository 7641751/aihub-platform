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
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
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
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 的两件事，之前的测试都没钉住：
 * <ol>
 *   <li><b>逐帧 flush</b>：M1 只断言了帧的顺序与内容，一个「攒完整段再回写」的实现照样全绿
 *       （台账 t1-4 / t5-4）。本类用「上游写完第一帧就卡住、等客户端读到第一帧才放行」的握手，
 *       攒批实现的实测失败点是 {@code secondFrameWritten()} 断言：它连响应头都提交不出来，
 *       客户端 {@code send()} 一直阻塞，没人放行上游 → 上游等待预算到期、补写第二帧并收尾，
 *       网关这才把整段一次性回吐 → 读到第一帧时断言已经为 true。客户端的读取预算是另一条
 *       **兜底**路径（先提交响应头、却扣住第一帧的实现才会走到它），见 {@link FakeUpstream} 的注释。</li>
 *   <li><b>客户端断连的计量</b>：设计文档 §8.1 ⑤ 要求 cancel 上游 + 按已收内容估算 token。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=false", "aihub.metering.enabled=true"})
@Import(MeteringTestConfig.class)
class SseStreamingTest {

    private static final String FIRST_FRAME = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n";
    private static final String SECOND_FRAME = "data: [DONE]\n\n";

    /** 客户端读第一帧的预算；**必须远小于** {@link FakeUpstream#SECOND_FRAME_HOLD_BUDGET_SECONDS}。 */
    private static final long CLIENT_READ_TIMEOUT_SECONDS = 5;

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
        // 记录「客户端实际收到了哪些字节」：既用它做增量读取（读到第一帧才会放行上游），
        // 也在收尾后用它把 chunked 握手响应体逐字节钉死。
        RecordingInputStream rawBody = new RecordingInputStream(response.body());
        BufferedReader reader = new BufferedReader(new InputStreamReader(rawBody, StandardCharsets.UTF_8));
        ExecutorService readerThread = Executors.newSingleThreadExecutor();
        try {
            Future<String> firstLine = readerThread.submit(reader::readLine);
            // 客户端的读取预算是**兜底路径**：只有当某个实现先把响应头提交出去、却把第一帧扣住时
            // 才会走到这里。攒批实现的实测失败点在下面的 secondFrameWritten() 断言上（它连响应头都
            // 提交不出来，客户端的 send() 先一直阻塞到上游的等待预算到期）。这里仍把超时包成一条
            // 明确的诊断，免得兜底路径退化成一个裸的 TimeoutException。客户端预算（5 秒）刻意远小于
            // 上游预算（30 秒），正确实现下第一帧是毫秒级到达的。
            String line;
            try {
                line = firstLine.get(CLIENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("网关在 " + CLIENT_READ_TIMEOUT_SECONDS + " 秒内没有把第一帧 flush 给客户端，"
                        + "而上游此刻仍在扣留第二帧（等待预算 "
                        + FakeUpstream.SECOND_FRAME_HOLD_BUDGET_SECONDS + " 秒）"
                        + " —— 说明网关把第一帧攒住了，而不是逐帧 flush（M1 字节级透传铁律）。", e);
            }
            assertThat(line).isEqualTo("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}");
            assertThat(upstream.secondFrameWritten())
                    .as("第一帧到达客户端时，上游不该已经写出第二帧 —— 否则说明网关在攒批")
                    .isFalse();

            upstream.releaseSecondFrame();

            assertThat(reader.lines().toList()).contains("data: [DONE]");
            // chunked（长度未知）握手响应此前从未被逐字节比对过：既有的字节等同用例走的是
            // 定长的 enqueueSse 路径。这里把「上游两帧的拼接」与客户端实际收到的字节全等起来。
            assertThat(rawBody.captured())
                    .as("chunked 握手响应体必须与上游两帧逐字节相同（M1 铁律）")
                    .isEqualTo((FIRST_FRAME + SECOND_FRAME).getBytes(StandardCharsets.UTF_8));
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

        HttpResponse<InputStream> response = null;
        try {
            response = HttpClient.newHttpClient().send(request(), HttpResponse.BodyHandlers.ofInputStream());
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
        } finally {
            // 头查找 / readLine 提前抛异常时也必须放行握手并关掉 body：本夹具的 HttpServer 与
            // 本类其它用例共用一条 dispatch 线程，一个仍在阻塞等放行的 handler 会把后面的用例拖住。
            upstream.releaseSecondFrame();
            if (response != null) {
                response.body().close();
            }
        }
    }

    /**
     * 只读地记录「客户端从响应体读到的每一个字节」，不改动、不重排、不缓存以外的任何事。
     * <p>用它同时满足两个需求：{@code BufferedReader} 照常增量读取（读到第一帧才放行上游），
     * 收尾后又能拿 {@link #captured()} 把 chunked 响应体逐字节比对。
     */
    private static final class RecordingInputStream extends FilterInputStream {

        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        private RecordingInputStream(InputStream delegate) {
            super(delegate);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                captured.write(value);
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            int count = super.read(target, offset, length);
            if (count > 0) {
                captured.write(target, offset, count);
            }
            return count;
        }

        private byte[] captured() {
            return captured.toByteArray();
        }
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
