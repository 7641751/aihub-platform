package com.aihub.gateway.testsupport;

import com.aihub.common.meter.MeteringEvent;
import com.aihub.common.meter.MeteringEventCodec;
import com.aihub.gateway.meter.MeteringTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 记录型假投递器：网关测试不允许依赖 Docker，MQ 这一层用它在内存里顶替。
 * <p>既给单元测试用（{@link #payloads()} / {@link #attempts()}），也给端到端测试用
 * （{@link #awaitEvent(String, Duration)} 按 {@code request_id} 取解码后的事件）。
 */
public final class RecordingMeteringTransport implements MeteringTransport {

    private final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();
    private final List<String> attempts = new ArrayList<>();
    private volatile boolean failAll;

    @Override
    public synchronized boolean send(String payload) {
        attempts.add(payload);
        if (failAll) {
            return false;
        }
        payloads.add(payload);
        return true;
    }

    /** 让后续投递全部失败（用于验证降级链）。 */
    public void failAll(boolean fail) {
        this.failAll = fail;
    }

    public synchronized int attempts() {
        return attempts.size();
    }

    public synchronized List<String> payloads() {
        return List.copyOf(payloads);
    }

    public void reset() {
        payloads.clear();
        synchronized (this) {
            attempts.clear();
        }
    }

    /** 等到某个 {@code request_id} 的事件；超时返回 {@code null}（断言时给出清晰失败信息）。 */
    public MeteringEvent awaitEvent(String requestId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String payload = payloads.poll(100, TimeUnit.MILLISECONDS);
            if (payload == null) {
                continue;
            }
            MeteringEvent event = MeteringEventCodec.decode(payload);
            if (event != null && event.requestId().equals(requestId)) {
                return event;
            }
        }
        return null;
    }
}
