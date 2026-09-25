package com.aihub.gateway.meter;

import com.aihub.common.meter.MeteringEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code MeteringPublisher} 是控制器唯一依赖的发布门面，而它被调用的位置是**响应收尾**
 * （{@code doFinally}，Netty event loop）。Task 4 的测试清单里没有它，于是两条最关键的纪律无人守备：
 * <ol>
 *   <li>{@code aihub.metering.enabled=false} 必须**完全短路**（连编码都不做、绝不碰投递器）——
 *       本地排查时关掉计量，却仍在往 MQ 投事件，是最容易骗过自己的一种坏法；</li>
 *   <li>{@code publish} 绝不把异常抛回请求路径（设计决策 C：计量是派生数据，任何异常只记日志）。
 *       这条不成立时，一个计量故障会变成 500 —— 用户请求被派生数据拖挂。</li>
 * </ol>
 */
class MeteringPublisherTest {

    @TempDir
    Path dir;

    private MeteringProperties properties(boolean enabled) {
        return new MeteringProperties(enabled, 4096, 100, 10, 30_000L, 100L, 60_000L, dir.toString());
    }

    private static MeteringEvent event() {
        return new MeteringEvent("req-1", 7L, null, null, "m", 1, 2, 3, 4, null,
                MeteringEvent.STATUS_SUCCESS, null, 1_700_000_000_000L);
    }

    /** 关闭计量时**什么都不做**：不给投递器任何东西，也不落盘。 */
    @Test
    void disabledPublisherHandsNothingToTheTransport() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        MeteringSpool spool = new MeteringSpool(dir, 10);
        MeteringDispatcher dispatcher = new MeteringDispatcher(properties(false), transport, spool,
                new SimpleMeterRegistry());
        MeteringPublisher publisher = new MeteringPublisher(properties(false), dispatcher);

        publisher.publish(event());

        assertThat(dispatcher.drainOnce()).as("队列里不该有任何东西").isFalse();
        assertThat(transport.sends).isZero();
        assertThat(spool.list()).isEmpty();
    }

    /** 开关打开时才真的入队（并且是异步投递：publish 返回时还没到投递器）。 */
    @Test
    void enabledPublisherEnqueuesTheEncodedEvent() {
        RecordingTransport transport = new RecordingTransport();
        MeteringSpool spool = new MeteringSpool(dir, 10);
        MeteringDispatcher dispatcher = new MeteringDispatcher(properties(true), transport, spool,
                new SimpleMeterRegistry());
        MeteringPublisher publisher = new MeteringPublisher(properties(true), dispatcher);

        publisher.publish(event());

        assertThat(transport.sends).as("publish 不该在 event loop 上直接投递").isZero();
        assertThat(dispatcher.drainOnce()).isTrue();
        assertThat(transport.lastPayload).contains("req-1");
    }

    /**
     * 投递器抛异常时 {@code publish} 必须吞掉：它是 {@code doFinally} 里被调用的，异常一旦逃出去
     * 就会把一次**已经成功转发**的请求变成错误响应（或污染 {@code doFinally} 的信号）。
     */
    @Test
    void publishSwallowsDispatcherFailuresInsteadOfThrowing() {
        MeteringDispatcher exploding = new MeteringDispatcher(properties(true),
                new RecordingTransport(), new MeteringSpool(dir, 10), new SimpleMeterRegistry()) {
            @Override
            public boolean enqueue(String payload) {
                throw new IllegalStateException("队列炸了");
            }
        };
        MeteringPublisher publisher = new MeteringPublisher(properties(true), exploding);

        assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();
    }

    /** 只记录调用次数的极简假投递器（真实投递语义由 {@code RecordingMeteringTransport} 覆盖）。 */
    private static final class RecordingTransport implements MeteringTransport {

        private int sends;
        private String lastPayload;

        @Override
        public boolean send(String payload) {
            sends++;
            lastPayload = payload;
            return true;
        }
    }
}
