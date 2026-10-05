package com.aihub.mq.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * {@code kb.parse} 的消费者（M5 Task 4）：解码 {@code parse:{docId}} 后交给 {@link KbParseSink}。
 *
 * <p>参数是原始 {@link Message} 而不是已反序列化的对象：线格式是 {@link KbMessageCodec} 的文本分隔符，
 * 不引入 JSON 转换器（也就不会因为两侧 Jackson 配置不同而"静默解析成空对象"）。
 *
 * <p><b>载荷解不开时抛异常</b>（不是 log + return）：<b>静默 ACK 一条解不开的消息等于永久丢数据</b>。
 * 这里只记一条 ERROR（载荷已由 {@link KbMessageCodec} 截断转义）后**原样抛出**，交给容器的重试策略
 * （3 次指数退避），仍失败则 reject → 进 {@code aihub.kb.dlq} 等人排查。照 {@code MeteringConsumer} 的纪律，
 * **绝不 `catch … { log; return; }`**。
 *
 * <p><b>本类是"薄适配器"</b>：真正的解析流水线在 {@code aihub-service} 的 {@link KbParseSink} 实现里
 * （{@code mq} 看不到 {@code dao}/{@code service}）。这样每个 Spring 上下文都会订阅 {@code kb.parse}
 * —— {@code KbPublishIntegrationTest} 那种"从队列收消息"的断言到 Task 4 起会被本消费者抢走，
 * 因此 Task 4+ 的断言一律走**数据库状态**。
 *
 * <p><b>容器工厂是"专用"的</b>（M5 Task 6）：{@code containerFactory = "kbListenerContainerFactory"} ⇒
 * 走 {@link KbTopologyConfig#kbListenerContainerFactory}（挂了失败终态恢复器 {@link KbMessageRecoverer}），
 * 而不是框架默认工厂 ⇒ **计量链路的死信行为不受影响**（裁定 #2）。
 */
@Component
public class KbParseConsumer {

    private static final Logger log = LoggerFactory.getLogger(KbParseConsumer.class);

    private final KbParseSink sink;

    public KbParseConsumer(KbParseSink sink) {
        this.sink = sink;
    }

    @RabbitListener(queues = KbTopology.PARSE_QUEUE, containerFactory = "kbListenerContainerFactory")
    public void onMessage(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        long docId;
        try {
            docId = KbMessageCodec.decodeParse(payload);
        } catch (IllegalArgumentException e) {
            log.error("解析消息载荷无法解码，转死信队列（{}）：{}",
                    KbTopology.DEAD_LETTER_QUEUE, e.getMessage());
            throw e;   // 不许吞：静默 ACK = 永久丢数据
        }

        sink.parse(docId);
    }
}
