package com.aihub.gateway.meter;

import com.aihub.gateway.testsupport.RecordingMeteringTransport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级链的完整覆盖：投递成功 → 一次失败后退避 → 落盘 → 重投成功删除 / 仍失败保留。
 * 两种「丢弃」（内存队列满 / 磁盘 spool 满）都必须计数 + 打 ERROR，绝不能静默。
 *
 * <p>单元测试用 {@link RecordingMeteringTransport}（假实现）+ {@link SimpleMeterRegistry}，
 * 不碰 Docker、不启线程：{@code drainOnce()} / {@code replayOnce()} 是可直接调用的确定性入口。
 */
class MeteringDispatcherTest {

    @TempDir
    Path dir;

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private MeteringDispatcher dispatcher(MeteringTransport transport, MeteringSpool spool, int queueCapacity) {
        MeteringProperties properties = new MeteringProperties(
                true, 4096, queueCapacity, 3, 30_000L, 100L, 60_000L, dir.toString());
        return new MeteringDispatcher(properties, transport, spool, registry);
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void deliversToTheTransport() throws IOException {
        RecordingMeteringTransport transport = new RecordingMeteringTransport();
        MeteringSpool spool = new MeteringSpool(dir, 10);
        MeteringDispatcher dispatcher = dispatcher(transport, spool, 10);

        assertThat(dispatcher.enqueue("payload-1")).isTrue();
        assertThat(dispatcher.drainOnce()).isTrue();

        assertThat(transport.payloads()).containsExactly("payload-1");
        assertThat(counter("aihub.metering.published")).isEqualTo(1);
        assertThat(spool.list()).isEmpty();
    }

    @Test
    void spoolsWhenTheTransportFails() throws IOException {
        RecordingMeteringTransport transport = new RecordingMeteringTransport();
        transport.failAll(true);
        MeteringSpool spool = new MeteringSpool(dir, 10);
        MeteringDispatcher dispatcher = dispatcher(transport, spool, 10);

        dispatcher.enqueue("payload-1");
        dispatcher.drainOnce();

        assertThat(spool.list()).hasSize(1);
        assertThat(spool.read(spool.list().get(0))).isEqualTo("payload-1");
        assertThat(counter("aihub.metering.spooled")).isEqualTo(1);
        assertThat(counter("aihub.metering.dropped")).isZero();
    }

    /**
     * 一次失败之后必须冷却：冷却期内的新事件**直接落盘**，不再每次花一个连接超时去试 broker。
     * 只断言「落盘了」的用例抓不到这个错误 —— 它表现为事件延迟而不是丢失。
     */
    @Test
    void backsOffAfterAFailure() throws IOException {
        RecordingMeteringTransport transport = new RecordingMeteringTransport();
        transport.failAll(true);
        MeteringSpool spool = new MeteringSpool(dir, 10);
        MeteringDispatcher dispatcher = dispatcher(transport, spool, 10);

        dispatcher.enqueue("payload-1");
        dispatcher.drainOnce();
        dispatcher.enqueue("payload-2");
        dispatcher.drainOnce();

        assertThat(transport.attempts()).isEqualTo(1);
        assertThat(spool.list()).hasSize(2);
    }

    @Test
    void dropsAndCountsWhenTheQueueIsFull() {
        RecordingMeteringTransport transport = new RecordingMeteringTransport();
        MeteringDispatcher dispatcher = dispatcher(transport, new MeteringSpool(dir, 10), 1);

        assertThat(dispatcher.enqueue("payload-1")).isTrue();
        assertThat(dispatcher.enqueue("payload-2")).isFalse();

        assertThat(counter("aihub.metering.dropped")).isEqualTo(1);
    }

    @Test
    void dropsAndCountsWhenTheSpoolIsFull() {
        RecordingMeteringTransport transport = new RecordingMeteringTransport();
        transport.failAll(true);
        MeteringDispatcher dispatcher = dispatcher(transport, new MeteringSpool(dir, 1), 10);

        dispatcher.enqueue("payload-1");
        dispatcher.drainOnce();
        dispatcher.enqueue("payload-2");
        dispatcher.drainOnce();

        assertThat(counter("aihub.metering.dropped")).isEqualTo(1);
        assertThat(counter("aihub.metering.spooled")).isEqualTo(1);
    }

    @Test
    void replayResendsSpooledEventsAndDeletesThem() throws IOException {
        RecordingMeteringTransport transport = new RecordingMeteringTransport();
        MeteringSpool spool = new MeteringSpool(dir, 10);
        spool.append("payload-1");
        spool.append("payload-2");
        MeteringDispatcher dispatcher = dispatcher(transport, spool, 10);

        assertThat(dispatcher.replayOnce()).isEqualTo(2);

        assertThat(transport.payloads()).containsExactly("payload-1", "payload-2");
        assertThat(spool.list()).isEmpty();
        assertThat(counter("aihub.metering.replayed")).isEqualTo(2);
    }

    @Test
    void replayKeepsTheFileWhenTheTransportStillFails() throws IOException {
        RecordingMeteringTransport transport = new RecordingMeteringTransport();
        transport.failAll(true);
        MeteringSpool spool = new MeteringSpool(dir, 10);
        spool.append("payload-1");
        MeteringDispatcher dispatcher = dispatcher(transport, spool, 10);

        assertThat(dispatcher.replayOnce()).isZero();

        assertThat(spool.list()).hasSize(1);
    }

    /**
     * 守护线程是唯一的消费者：某一条事件触发未预期异常时，循环必须继续，否则线程静默死掉、
     * 后面所有事件只会在队列里堆到「队列满」的 ERROR（那条日志把原因指向容量，误导排查）。
     * 这是唯一一条覆盖 {@code loop()} 自身的用例（其余用 {@code drainOnce()} 的确定性入口）。
     */
    @Test
    void aThrowingDispatchDoesNotKillTheConsumerLoop() throws InterruptedException {
        MeteringSpool spool = new MeteringSpool(dir, 10);
        BlockingQueue<String> delivered = new LinkedBlockingQueue<>();
        AtomicInteger calls = new AtomicInteger();
        MeteringTransport explodingFirstSend = payload -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom from the transport");
            }
            delivered.add(payload);
            return true;
        };
        MeteringDispatcher dispatcher = dispatcher(explodingFirstSend, spool, 10);

        try {
            dispatcher.enqueue("payload-1");
            dispatcher.enqueue("payload-2");
            dispatcher.start();

            // 第一投抛异常之后，队列里的第二条仍必须被投出去。
            assertThat(delivered.poll(5, TimeUnit.SECONDS)).isEqualTo("payload-2");
        } finally {
            dispatcher.stop();
        }
    }
}
