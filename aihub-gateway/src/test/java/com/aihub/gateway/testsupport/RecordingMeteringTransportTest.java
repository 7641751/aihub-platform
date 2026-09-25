package com.aihub.gateway.testsupport;

import com.aihub.common.meter.MeteringEvent;
import com.aihub.common.meter.MeteringEventCodec;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code awaitEvent} 是 Task 5/6 端到端计量测试的观察窗口，因此「它不看的事件是否还活着」
 * 本身就是被测行为：本夹具以前用队列 poll，会把 {@code requestId} 不匹配的 payload 丢掉 ——
 * 两个请求同时在飞时，等 B 的调用会吃掉 A 的事件，测试随机变红。
 */
class RecordingMeteringTransportTest {

    @Test
    void awaitingOneRequestKeepsTheOtherRecordedEvents() throws InterruptedException {
        RecordingMeteringTransport transport = new RecordingMeteringTransport();
        transport.send(payload("r-a"));
        transport.send(payload("r-b"));

        assertThat(transport.awaitEvent("r-b", Duration.ofSeconds(1)).requestId()).isEqualTo("r-b");

        // 等 B 不能把先到的 A 吃掉：两条都要还在（且顺序不变）。
        assertThat(transport.payloads()).containsExactly(payload("r-a"), payload("r-b"));
        assertThat(transport.awaitEvent("r-a", Duration.ofSeconds(1)).requestId()).isEqualTo("r-a");
    }

    @Test
    void timesOutWithNullWithoutDestroyingTheRecords() throws InterruptedException {
        RecordingMeteringTransport transport = new RecordingMeteringTransport();
        transport.send(payload("r-a"));

        assertThat(transport.awaitEvent("r-never", Duration.ofMillis(100))).isNull();

        // 超时后事件仍在，且能看到「实际收到了什么」（断言失败时可诊断）。
        assertThat(transport.payloads()).containsExactly(payload("r-a"));
        assertThat(transport.recordedRequestIds()).containsExactly("r-a");
    }

    private static String payload(String requestId) {
        return MeteringEventCodec.encode(new MeteringEvent(
                requestId, 7L, 42L, 99L, "deepseek-chat", 12, 34, 46, 1200, 250,
                MeteringEvent.STATUS_SUCCESS, null, 1_800_000_000_123L));
    }
}
