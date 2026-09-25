package com.aihub.gateway.testsupport;

import com.aihub.common.meter.MeteringEvent;
import com.aihub.common.meter.MeteringEventCodec;
import com.aihub.gateway.meter.MeteringTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 记录型假投递器：网关测试不允许依赖 Docker，MQ 这一层用它在内存里顶替。
 * <p>既给单元测试用（{@link #payloads()} / {@link #attempts()}），也给端到端测试用
 * （{@link #awaitEvent(String, Duration)} 按 {@code request_id} 取解码后的事件）。
 *
 * <p><b>记录过的事件永远不会被「取走」</b>：{@link #awaitEvent} 只做扫描，投递成功的 payload
 * 一律留在 {@link #payloads()} 里。此前它用队列 poll，会把 {@code requestId} 不匹配的 payload
 * 当场丢掉 —— 同时有两个请求在飞时，「等 B」的调用会把 A 的事件吃掉，测试随机变红
 * （Task 5/6 的端到端计量测试都复用本夹具）。
 */
public final class RecordingMeteringTransport implements MeteringTransport {

    private final List<String> payloads = new ArrayList<>();
    private final List<String> attempts = new ArrayList<>();
    private volatile boolean failAll;

    @Override
    public synchronized boolean send(String payload) {
        attempts.add(payload);
        if (failAll) {
            return false;
        }
        payloads.add(payload);
        notifyAll();
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

    /** 记录过的全部 {@code request_id}（按投递顺序）：超时断言失败时用它说明「实际收到了什么」。 */
    public synchronized List<String> recordedRequestIds() {
        List<String> ids = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            MeteringEvent event = MeteringEventCodec.decode(payload);
            ids.add(event == null ? "<无法解码>" : event.requestId());
        }
        return List.copyOf(ids);
    }

    public synchronized void reset() {
        payloads.clear();
        attempts.clear();
    }

    /**
     * 等到某个 {@code request_id} 的事件；超时返回 {@code null}（断言时给出清晰失败信息）。
     * <p>只扫描已记录的 payload，**不消费**任何一条；超时后用 {@link #payloads()} /
     * {@link #recordedRequestIds()} 能看出实际收到了哪些事件，失败可诊断。
     */
    public MeteringEvent awaitEvent(String requestId, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        synchronized (this) {
            while (true) {
                for (String payload : payloads) {
                    MeteringEvent event = MeteringEventCodec.decode(payload);
                    if (event != null && event.requestId().equals(requestId)) {
                        return event;
                    }
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return null;
                }
                // 等待新记录（或超时）后再扫一遍，而不是忙等。
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            }
        }
    }
}
