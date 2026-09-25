package com.aihub.gateway.meter;

import org.springframework.core.io.buffer.DataBuffer;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 单个请求的用量捕获：TTFT、响应字节副本、以及「解析不出 usage 时按内容估算」的兜底。
 *
 * <p><b>不改动被转发的字节</b>：{@link #onChunk} 只用 {@link DataBuffer#asByteBuffer()} 拿到一个
 * **只读视图**并复制出来 —— 视图不推进原 buffer 的读写位置，因此透传出去的仍是上游原字节。
 * 这条不变式由 {@code UsageCaptureTest.doesNotConsumeTheDataBuffer} 钉住。
 *
 * <p><b>TTFT 口径</b>：从请求开始到**响应体第一个字节**到达（含上游的 keep-alive / role 帧）。
 * 只有流式才产出 TTFT（{@link #capture()} 对非流式返回 {@code null}）：非流式没有「首字延迟」，
 * 编造一个会污染 M6 的 TTFT 数据（计划「决策登记」第 6 条）。
 * <p>{@link #ttftMs()} 本身返回原始测量值，供单元测试直接验证「首个 chunk 定 TTFT」的语义。
 *
 * <p>线程模型：{@code onChunk} 在 event loop，{@link #capture()} / {@link #latencyMs()} 在收尾线程；
 * 跨线程状态用 {@code volatile} + {@link #firstChunkSeen} 的 CAS。
 */
public final class UsageCapture {

    /** usage 的来源：精确 / 估算 / 完全拿不到。 */
    public enum Source {
        EXACT,
        ESTIMATED,
        MISSING
    }

    public record Captured(int promptTokens, int completionTokens, int totalTokens,
                           Integer ttftMs, Source source) {
    }

    private final LongSupplier nanoTime;
    private final long startNanos;
    private final boolean streaming;
    private final TailBuffer tail;
    private final AtomicBoolean firstChunkSeen = new AtomicBoolean(false);
    private volatile long firstChunkNanos = -1L;

    /** 生产用工厂；测试用带假时钟的构造器拿到确定的 TTFT。 */
    public static UsageCapture start(boolean streaming, int maxCaptureBytes) {
        return new UsageCapture(System::nanoTime, streaming, maxCaptureBytes);
    }

    public UsageCapture(LongSupplier nanoTime, boolean streaming, int maxCaptureBytes) {
        this.nanoTime = nanoTime;
        this.startNanos = nanoTime.getAsLong();
        this.streaming = streaming;
        this.tail = new TailBuffer(maxCaptureBytes);
    }

    /** 只读观察：既不消费 buffer，也不影响写回客户端的字节。 */
    public void onChunk(DataBuffer buffer) {
        if (buffer == null) {
            return;
        }
        if (firstChunkSeen.compareAndSet(false, true)) {
            firstChunkNanos = nanoTime.getAsLong();
        }
        ByteBuffer view = buffer.asByteBuffer();
        byte[] copy = new byte[view.remaining()];
        view.get(copy);
        tail.append(copy);
    }

    public Integer ttftMs() {
        return firstChunkNanos < 0 ? null : (int) ((firstChunkNanos - startNanos) / 1_000_000L);
    }

    public int latencyMs() {
        return (int) ((nanoTime.getAsLong() - startNanos) / 1_000_000L);
    }

    /** 捕获是否因为超限丢过字节（丢过说明结果可能不完整，调用方可打计数器）。 */
    public boolean truncated() {
        return tail.truncated();
    }

    public Captured capture() {
        // 非流式不产出 TTFT：列可空，且「首字延迟」只对流式有意义（决策 6）。
        Integer ttft = streaming ? ttftMs() : null;
        byte[] body = tail.toByteArray();
        Optional<UsageExtractor.Usage> usage =
                streaming ? UsageExtractor.fromSse(body) : UsageExtractor.fromJsonBody(body);
        if (usage.isPresent()) {
            UsageExtractor.Usage exact = usage.get();
            return new Captured(exact.promptTokens(), exact.completionTokens(), exact.totalTokens(),
                    ttft, Source.EXACT);
        }
        String content = streaming ? UsageExtractor.sseContent(body) : UsageExtractor.jsonContent(body);
        int estimated = TokenEstimator.estimate(content);
        if (estimated > 0) {
            // prompt 拿不到（网关不切词），只能记 0 —— 见计划「决策登记」第 7 条。
            return new Captured(0, estimated, estimated, ttft, Source.ESTIMATED);
        }
        return new Captured(0, 0, 0, ttft, Source.MISSING);
    }
}
