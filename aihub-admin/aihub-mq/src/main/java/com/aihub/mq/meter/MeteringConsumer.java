package com.aihub.mq.meter;

import com.aihub.common.meter.MeteringEvent;
import com.aihub.common.meter.MeteringEventCodec;
import com.aihub.common.meter.MeteringTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 计量事件的消费者。
 *
 * <p>参数是原始 {@link Message} 而不是已反序列化的对象：线格式是
 * {@link MeteringEventCodec} 的分隔符文本，不引入 JSON 转换器（也就不会因为两侧 Jackson 配置
 * 不同而「静默解析成空对象」）。
 *
 * <p><b>载荷解不开时抛异常</b>（不是 log + return）：<b>静默 ACK 一条解不开的消息等于永久丢数据</b>。
 * 抛出后交给容器的重试策略（3 次指数退避），仍失败则 reject → 进 DLQ 等人排查。
 *
 * <p><b>解不开时把原始载荷打进 ERROR 日志</b>：{@link MeteringEventCodec#decode} 用
 * {@code catch (RuntimeException)} 兜底并返回 {@code null}，因此「垃圾输入」与
 * 「codec 内部的真实缺陷」在返回值上**完全无法区分**。不留下原始载荷，事后就只能看到一句
 * 「无法解码」，既没法还原坏消息、也没法判断是不是自己的解析器坏了。
 *
 * <p><b>落库失败同理</b>：{@code RequestLogService} 只吞「重复键」，其它异常会冒到这里 → 重试 → 死信。
 *
 * <p><b>分区与事件时间（排查 DLQ 时先看这条）</b>：落库的 {@code created_at} 取事件里的原值
 * （它同时是分区列与幂等键，**绝不用 {@code now()}**）。{@code request_log} 按 {@code created_at}
 * {@code RANGE} 分区（{@code p202609} / {@code p202610} / {@code p202611} / {@code pmax}），
 * 而 {@code RANGE} 分区只声明**上界**：早于 {@code 2026-10-01} 的事件不会被拒绝，它会落进
 * **第一个**分区 {@code p202609}（实测：{@code created_at='2020-01-01'} 与 {@code '0999-12-31'}
 * 均插入成功且落在 p202609），不会被拒绝、因而也不会进 DLQ —— 但按分区做归档/清理时要意识到
 * 「老事件堆在 p202609 里」。真正会硬失败并因此死信的是**超出 MySQL {@code DATETIME} 合法范围的
 * {@code created_at}**（例如坏掉或伪造的 epoch 毫秒换算出的年份 < 1000 / > 9999）：
 * 严格模式下报 {@code ERROR 1292 Incorrect datetime value} → {@code DataAccessException} →
 * 重试 3 次 → DLQ。这是固定 schema 的固有行为（本里程碑不得新增迁移），不是消费端的 bug；
 * DLQ 里出现这类消息时应当去查**产生事件的网关**，而不是改消费端。
 */
@Component
public class MeteringConsumer {

    private static final Logger log = LoggerFactory.getLogger(MeteringConsumer.class);

    private final MeteringSink sink;

    public MeteringConsumer(MeteringSink sink) {
        this.sink = sink;
    }

    @RabbitListener(queues = MeteringTopology.QUEUE)
    public void onMessage(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        MeteringEvent event = MeteringEventCodec.decode(payload);
        if (event == null) {
            log.error("计量事件载荷无法解码，转死信队列（{}）: {}", MeteringTopology.DEAD_LETTER_QUEUE, payload);
            throw new IllegalArgumentException("计量事件载荷无法解码");
        }
        boolean inserted = sink.persist(event);
        if (inserted) {
            log.debug("计量事件已落库 request_id={}", event.requestId());
        } else {
            log.info("计量事件重复消费，已幂等丢弃 request_id={} created_at={}",
                    event.requestId(), event.createdAt());
        }
    }
}
