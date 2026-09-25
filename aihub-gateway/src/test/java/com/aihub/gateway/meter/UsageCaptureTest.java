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
}
