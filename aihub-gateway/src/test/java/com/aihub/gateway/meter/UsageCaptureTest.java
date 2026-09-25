package com.aihub.gateway.meter;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 捕获器是「计量」与「字节透传」的交汇点：它读上游响应体，但**绝不能消费/改动**那些字节。
 * 本类里 {@code doesNotConsumeTheDataBuffer} 就是这条铁律的单元级防线 ——
 * 一旦实现改用会推进读写位置的读法，客户端的响应体就会缺字节，而它必然变红。
 */
class UsageCaptureTest {

    private static final DefaultDataBufferFactory FACTORY = new DefaultDataBufferFactory();

    private final AtomicLong clock = new AtomicLong(0);

    private DataBuffer buffer(String value) {
        return FACTORY.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ttftIsMeasuredOnTheFirstChunk() {
        UsageCapture capture = new UsageCapture(clock::get, true, 4096);

        clock.set(5_000_000L);   // 5ms
        capture.onChunk(buffer("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"));
        clock.set(50_000_000L);  // 50ms：TTFT 不允许被这个时刻改写
        capture.onChunk(buffer("data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n"));

        assertThat(capture.ttftMs()).isEqualTo(5);
    }

    /**
     * **区分性用例**：{@code onChunk} 之后 DataBuffer 的可读字节必须一字不差。
     * 实现若用 {@code read(...)} / {@code DataBufferUtils.join} 这类会推进读写位置的读法，
     * 透传出去的就是残缺 body —— 本用例变红，而其它用例（只看计量结果）可能全绿。
     */
    @Test
    void doesNotConsumeTheDataBuffer() {
        byte[] payload = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer dataBuffer = FACTORY.wrap(payload);
        UsageCapture capture = new UsageCapture(clock::get, true, 4096);

        capture.onChunk(dataBuffer);

        assertThat(dataBuffer.readableByteCount()).isEqualTo(payload.length);
        byte[] after = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(after);
        assertThat(after).isEqualTo(payload);
    }

    @Test
    void capturesExactUsageFromSse() {
        UsageCapture capture = new UsageCapture(clock::get, true, 4096);

        capture.onChunk(buffer(UsageExtractorTest.STREAMING_SSE));

        UsageCapture.Captured captured = capture.capture();
        assertThat(captured.source()).isEqualTo(UsageCapture.Source.EXACT);
        assertThat(captured.promptTokens()).isEqualTo(9);
        assertThat(captured.completionTokens()).isEqualTo(2);
        assertThat(captured.totalTokens()).isEqualTo(11);
    }

    @Test
    void capturesExactUsageFromANonStreamingBody() {
        UsageCapture capture = new UsageCapture(clock::get, false, 4096);

        capture.onChunk(buffer(UsageExtractorTest.COMPLETION_JSON));

        UsageCapture.Captured captured = capture.capture();
        assertThat(captured.source()).isEqualTo(UsageCapture.Source.EXACT);
        assertThat(captured.totalTokens()).isEqualTo(11);
        // 非流式不产出 TTFT（决策 6）：即使收到了 chunk 也必须是 null。
        assertThat(captured.ttftMs()).isNull();
    }

    @Test
    void estimatesCompletionTokensWhenUsageIsMissing() {
        UsageCapture capture = new UsageCapture(clock::get, true, 4096);

        // 没有 usage 帧，只有两个汉字增量 → ceil(2 * 0.6) = 2，prompt 未知记 0。
        capture.onChunk(buffer("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"));

        UsageCapture.Captured captured = capture.capture();
        assertThat(captured.source()).isEqualTo(UsageCapture.Source.ESTIMATED);
        assertThat(captured.promptTokens()).isZero();
        assertThat(captured.completionTokens()).isEqualTo(2);
        assertThat(captured.totalTokens()).isEqualTo(2);
    }

    @Test
    void returnsMissingWhenThereIsNoContent() {
        UsageCapture capture = new UsageCapture(clock::get, true, 4096);

        capture.onChunk(buffer("data: [DONE]\n\n"));

        UsageCapture.Captured captured = capture.capture();
        assertThat(captured.source()).isEqualTo(UsageCapture.Source.MISSING);
        assertThat(captured.totalTokens()).isZero();
    }

    @Test
    void ttftIsNullWhenNoChunkArrived() {
        UsageCapture capture = new UsageCapture(clock::get, true, 4096);

        assertThat(capture.ttftMs()).isNull();
        assertThat(capture.capture().ttftMs()).isNull();
        assertThat(capture.capture().source()).isEqualTo(UsageCapture.Source.MISSING);
    }

    /**
     * {@code latencyMs()} 的口径是「从收到请求（构造）到响应写完成（收尾时取数）」，与「什么时候收到
     * 第一个字节」无关 —— 因此用**非流式**请求来钉：它没有 TTFT，但耗时照样必须测出来。
     * <p>Task 3 的测试此前从未调用过它，于是「耗时恒为 0」这种实现也能全绿。
     */
    @Test
    void latencySpansFromRequestStartToResponseCompletion() {
        UsageCapture capture = new UsageCapture(clock::get, false, 4096);

        assertThat(capture.latencyMs()).isZero();

        clock.set(500_000_000L);   // 500ms：响应还没"写完"
        assertThat(capture.latencyMs()).isEqualTo(500);

        clock.set(1_234_000_000L);
        capture.onChunk(buffer(UsageExtractorTest.COMPLETION_JSON));   // 收尾前到达的字节不改变口径

        assertThat(capture.latencyMs()).isEqualTo(1234);
        assertThat(capture.capture().source()).isEqualTo(UsageCapture.Source.EXACT);
    }

    /**
     * 决策 6 的**单元级**钉子，也是本类里最容易踩的脚坑：非流式请求里
     * {@link UsageCapture#ttftMs()} 仍然测「第一个响应体字节」（这是它的定义，供测试直接验证语义），
     * 但真正落库的 {@link UsageCapture.Captured#ttftMs()} 必须是 {@code null}。
     * <p>把两者混为一谈（例如控制器/计量改用 {@code capture.ttftMs()} 组装事件）就会给非流式请求
     * 编造出一个「首字延迟」，污染 M6 的 TTFT 数据 —— 本用例保证那种改法在这里就变红。
     */
    @Test
    void capturedTtftStaysNullForNonStreamingEvenThoughTheRawMeasurementIsNot() {
        UsageCapture capture = new UsageCapture(clock::get, false, 4096);

        clock.set(7_000_000L);
        capture.onChunk(buffer(UsageExtractorTest.COMPLETION_JSON));

        assertThat(capture.ttftMs()).isEqualTo(7);
        assertThat(capture.capture().ttftMs()).isNull();
    }

    /**
     * 起始时刻只在**构造时**采样一次：用假时钟把这一条钉死（{@code start(...)} 走真实 {@code nanoTime}，
     * 混用两种时钟只会测出负数，所以这里用构造器；{@code start(...)} 本身由下面那条用例覆盖）。
     * <p>若实现改成「每次 {@code latencyMs()} 都重新采样起始时刻」，第二次取数会退回 0 —— 这条挡住它。
     */
    @Test
    void startSamplesTheStartingInstantOnce() {
        clock.set(3_000_000L);
        UsageCapture capture = new UsageCapture(clock::get, false, 4096);

        clock.set(50_000_000L);
        int first = capture.latencyMs();
        clock.set(90_000_000L);
        int second = capture.latencyMs();

        assertThat(first).isEqualTo(47);
        assertThat(second).isEqualTo(87);
    }

    /** 生产工厂 {@code start(...)} 也要能产出非负耗时（Task 3 此前从未调用过它）。 */
    @Test
    void startMeasuresLatencyWithTheRealClock() {
        UsageCapture capture = UsageCapture.start(false, 4096);

        assertThat(capture.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(capture.capture().ttftMs()).isNull();
    }

    /**
     * 非流式的捕获上限底线：**尾部**滑窗对「整体 JSON」是危险的 —— 一旦 body 超过窗口，头部被丢掉、
     * JSON 不再可解析，精确 usage 会被静默降级成 {@code usage_missing} + {@code prompt_tokens = 0}。
     * 本用例用一个**远大于**现实非流式 body（300 KB 正文 + usage）的响应，在**默认配置**的上限下
     * 断言 usage 仍然精确可解析。
     * <p>所以：把 {@code aihub.metering.max-capture-bytes} 的默认值调到 300 KB 以下，本用例必然变红 ——
     * 这就是「默认值够不够大」这条判断的可执行版本（口径写在 {@code MeteringProperties} 上）。
     */
    @Test
    void parsesALargeNonStreamingBodyThatFitsTheDefaultCaptureWindow() {
        String content = "x".repeat(300 * 1024);
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":456,\"total_tokens\":579}}";
        int defaultMaxCaptureBytes = new MeteringProperties(true, 1048576, 10_000, 50_000,
                30_000L, 5_000L, 5_000L, "data/metering-spool").maxCaptureBytes();
        UsageCapture capture = new UsageCapture(clock::get, false, defaultMaxCaptureBytes);

        capture.onChunk(buffer(body));

        UsageCapture.Captured captured = capture.capture();
        assertThat(captured.source())
                .as("非流式 body 必须完整落在捕获窗口内，否则精确 usage 会被静默降级")
                .isEqualTo(UsageCapture.Source.EXACT);
        assertThat(captured.promptTokens()).isEqualTo(123);
        assertThat(captured.totalTokens()).isEqualTo(579);
    }

    /**
     * 反过来把「降级而不是崩」钉住：捕获窗口小于非流式 body 时，结果是 {@code MISSING} + 全 0，
     * 而**不是**异常、也不是「用残缺 JSON 抢救出来的一堆假 token」。
     */
    @Test
    void aTooSmallCaptureWindowDegradesInsteadOfThrowing() {
        String body = "{\"" + "x".repeat(2000)
                + "\":1,\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":9,\"total_tokens\":18}}";
        UsageCapture capture = new UsageCapture(clock::get, false, 512);

        capture.onChunk(buffer(body));

        assertThat(capture.truncated()).isTrue();
        assertThat(capture.capture().source()).isEqualTo(UsageCapture.Source.MISSING);
        assertThat(capture.capture().totalTokens()).isZero();
    }
}
