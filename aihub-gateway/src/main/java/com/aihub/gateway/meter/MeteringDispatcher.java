package com.aihub.gateway.meter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 计量投递的降级链（设计文档 §9 的 MQ 那一行 + 计划「决策登记」第 5 条）：
 *
 * <pre>
 * 事件 → [有界内存队列] → 守护线程 → RabbitMQ（confirm）
 *                                   ├─ 成功：published +1
 *                                   └─ 失败：冷却 backoff，然后落磁盘 spool（spooled +1）
 *                                             spool 满 / 队列满 / 写盘失败：dropped +1 + ERROR 日志
 * 定时任务 → 重投 spool 里的文件（成功即删，失败保留到下一轮）
 * </pre>
 *
 * <p><b>线程模型</b>：{@link #enqueue} 由 event loop 调用，只做一次非阻塞 offer；
 * 所有网络/磁盘 I/O 都发生在守护线程与调度线程上，event loop 永远不会被 MQ 或磁盘拖住。
 * 这两个入口（{@link #drainOnce} / {@link #replayOnce}）被设计成可直接调用的确定性方法，
 * 因此单元测试不需要线程与 sleep。
 */
public class MeteringDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MeteringDispatcher.class);

    private final MeteringTransport transport;
    private final MeteringSpool spool;
    private final BlockingQueue<String> queue;
    private final long transportRetryBackoffMs;
    private final Counter published;
    private final Counter spooled;
    private final Counter dropped;
    private final Counter replayed;
    private final ExecutorService drain =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "aihub-metering-drain");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean running = true;
    private volatile long nextAttemptAtMillis;

    public MeteringDispatcher(MeteringProperties properties, MeteringTransport transport,
                              MeteringSpool spool, MeterRegistry registry) {
        this.transport = transport;
        this.spool = spool;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.queueCapacity()));
        this.transportRetryBackoffMs = properties.transportRetryBackoffMs();
        this.published = registry.counter("aihub.metering.published");
        this.spooled = registry.counter("aihub.metering.spooled");
        this.dropped = registry.counter("aihub.metering.dropped");
        this.replayed = registry.counter("aihub.metering.replayed");
    }

    @PostConstruct
    void start() {
        drain.execute(this::loop);
    }

    @PreDestroy
    void stop() {
        running = false;
        drain.shutdownNow();
    }

    /** event loop 上唯一允许的调用：非阻塞。返回 false 表示**已丢弃**（并已计数 + 打 ERROR）。 */
    public boolean enqueue(String payload) {
        if (queue.offer(payload)) {
            return true;
        }
        dropped.increment();
        log.error("计量事件内存队列已满（容量 {}），丢弃该事件: {}", queue.size(), payload);
        return false;
    }

    /** 取一条并投递；返回是否处理了一条（守护线程循环调用，测试直接调用）。 */
    public boolean drainOnce() {
        String payload;
        try {
            payload = queue.poll(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (payload == null) {
            return false;
        }
        dispatch(payload);
        return true;
    }

    /**
     * 把 spool 里的事件按文件名（≈ 到达时间）顺序重投：成功一个删一个，第一个失败就停下等下一轮
     * （顺序保持不变，幂等键保证重复投递无害）。返回本次重投成功的条数。
     */
    public int replayOnce() {
        int replayedCount = 0;
        try {
            List<Path> files = spool.list();
            for (Path file : files) {
                String payload;
                try {
                    payload = spool.read(file);
                } catch (IOException e) {
                    log.error("读取 spool 文件失败（跳过）: {} - {}", file, e.toString());
                    continue;
                }
                if (!transport.send(payload)) {
                    log.warn("spool 重投失败，保留 {} 个文件等下一轮", files.size() - replayedCount);
                    break;
                }
                spool.delete(file);
                replayed.increment();
                replayedCount++;
            }
        } catch (IOException e) {
            log.error("遍历 spool 目录失败: {}", e.toString());
        }
        return replayedCount;
    }

    private void loop() {
        while (running) {
            drainOnce();
        }
    }

    private void dispatch(String payload) {
        if (System.currentTimeMillis() < nextAttemptAtMillis) {
            // 冷却期内不再试连接：否则每个事件都要等一个连接超时。
            spool(payload);
            return;
        }
        if (transport.send(payload)) {
            published.increment();
            return;
        }
        nextAttemptAtMillis = System.currentTimeMillis() + transportRetryBackoffMs;
        log.error("RabbitMQ 投递失败，计量事件转落磁盘 spool（由定时任务补偿重投）");
        spool(payload);
    }

    private void spool(String payload) {
        try {
            spool.append(payload);
            spooled.increment();
        } catch (MeteringSpool.SpoolFullException e) {
            dropped.increment();
            log.error("磁盘 spool 已满，丢弃计量事件: {}", payload);
        } catch (IOException e) {
            dropped.increment();
            log.error("写 spool 失败，丢弃计量事件: {} - {}", payload, e.toString());
        }
    }
}
