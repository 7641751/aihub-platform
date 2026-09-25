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
 * <p><b>解不开时把载荷打进 ERROR 日志</b>：{@link MeteringEventCodec#decode} 用
 * {@code catch (RuntimeException)} 兜底并返回 {@code null}，因此「垃圾输入」与
 * 「codec 内部的真实缺陷」在返回值上**完全无法区分**。不留下载荷，事后就只能看到一句
 * 「无法解码」，既没法还原坏消息、也没法判断是不是自己的解析器坏了。
 * 但载荷来自 broker，**长度与内容都不受本进程控制**：只记录「长度 + 截断后的前缀」
 * （上限见 {@code MAX_LOGGED_PAYLOAD_CHARS}），且 CR/LF 一律转义 —— 否则一条超大或带换行的
 * 消息会在 3 次重试里各打一遍，既能伪造日志行，也能把日志冲爆。
 *
 * <p><b>落库失败同理</b>：{@code RequestLogService} 只吞「重复键」，其它异常会冒到这里 → 重试 → 死信。
 *
 * <p><b>分区与事件时间（排查 DLQ 时先看这条）</b>：落库的 {@code created_at} 取事件里的原值
 * （它同时是分区列与幂等键，**绝不用 {@code now()}**）。{@code request_log} 按 {@code created_at}
 * {@code RANGE} 分区（{@code p202609} / {@code p202610} / {@code p202611} / {@code pmax}），
 * 而 {@code RANGE} 分区只声明**上界**：早于 {@code 2026-10-01} 的事件不会被拒绝，它会落进
 * **第一个**分区 {@code p202609}（实测：{@code created_at='2020-01-01'} 与 {@code '0999-12-31'}
 * 均插入成功且落在 p202609），不会被拒绝、因而也不会进 DLQ —— 但按分区做归档/清理时要意识到
 * 「老事件堆在 p202609 里」。真正会硬失败并因此死信的是**落在 MySQL {@code DATETIME} 定义域之外的
 * {@code created_at}**（坏掉或伪造的 epoch 毫秒换算出的时间戳就可能越界；具体的年份边界本任务未逐点
 * 验证，故不在此处断言数字）：
 * 严格模式下报 {@code ERROR 1292 Incorrect datetime value} → {@code DataAccessException} →
 * 重试 3 次 → DLQ。这是固定 schema 的固有行为（本里程碑不得新增迁移），不是消费端的 bug；
 * DLQ 里出现这类消息时应当去查**产生事件的网关**，而不是改消费端。
 */
@Component
public class MeteringConsumer {

    private static final Logger log = LoggerFactory.getLogger(MeteringConsumer.class);

    /**
     * 写进 ERROR 日志的载荷前缀上限（字符数）。载荷是 broker 给的字节，长度不受本进程控制，
     * 而一条消息会被重试 3 次、每次都打一遍，所以必须截断。
     */
    private static final int MAX_LOGGED_PAYLOAD_CHARS = 256;

    private final MeteringSink sink;

    public MeteringConsumer(MeteringSink sink) {
        this.sink = sink;
    }

    @RabbitListener(queues = MeteringTopology.QUEUE)
    public void onMessage(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        MeteringEvent event = MeteringEventCodec.decode(payload);
        if (event == null) {
            log.error("计量事件载荷无法解码，转死信队列（{}）：载荷长度 {} 字符，"
                            + "以下为最多前 {} 字符（CR/LF 已转义，载荷无法伪造日志行）: {}",
                    MeteringTopology.DEAD_LETTER_QUEUE, payload.length(), MAX_LOGGED_PAYLOAD_CHARS,
                    loggedPayloadPrefix(payload));
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

    /**
     * 截断并转义后的载荷前缀。转义 CR/LF 是**安全要求**而不是美观要求：载荷里漏出一个换行，
     * 一条日志记录就会被读成两条（伪造日志行）。转义规则与 {@link MeteringEventCodec} 一致
     * （先转义 {@code \}，再转义 CR/LF），这样「载荷里字面的 {@code \n}」与「真换行」也分得清。
     */
    private static String loggedPayloadPrefix(String payload) {
        int limit = Math.min(payload.length(), MAX_LOGGED_PAYLOAD_CHARS);
        StringBuilder out = new StringBuilder(limit + 8);
        for (int i = 0; i < limit; i++) {
            char c = payload.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
