package com.aihub.mq.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * {@code kb.embed} 的消费者（M5 Task 5）：解码 {@code embed:{docId}:{seqFrom}:{seqTo}} 后交给 {@link KbEmbedSink}。
 *
 * <p>参数是原始 {@link Message}（不是已反序列化的对象）：线格式是 {@link KbMessageCodec} 的文本分隔符，
 * 不引入 JSON 转换器 —— 也就不会因为两侧 Jackson 配置不同而"静默解析成空对象"。
 *
 * <p><b>载荷解不开时抛异常</b>（不是 log + return）：**静默 ACK 一条解不开的消息等于永久丢数据**。
 * 这里记一条 ERROR 后**原样抛出**，交给容器的重试策略（3 次指数退避），仍失败则 reject → {@code aihub.kb.dlq}。
 * 形状**逐字照** {@link KbParseConsumer} 与既有 {@code MeteringConsumer} —— **绝不 `catch … { log; return; }`**。
 *
 * <p><b>本类是"薄适配器"</b>：真正的嵌入流水线在 {@code aihub-service} 的 {@link KbEmbedSink} 实现里。
 * 注意：每个 Spring 上下文都会订阅 {@code kb.embed}，因此**测试断言不要抢这个队列**，要读数据库状态
 * （{@code kb_chunk.embedded_at} / {@code kb_document.status}）—— 见 {@code KbParseIntegrationTest} 的同类说明。
 *
 * <p><b>容器工厂是"专用"的</b>（M5 Task 6）：{@code containerFactory = "kbListenerContainerFactory"} ⇒
 * 走 {@link KbTopologyConfig#kbListenerContainerFactory} —— 重试 3 次耗尽后由 {@link KbMessageRecoverer}
 * 置 FAILED + 清理 + 审计，再把消息拒绝进 DLQ。**计量链路仍用默认工厂，不受影响**（裁定 #2）。
 */
@Component
public class KbEmbedConsumer {

    private static final Logger log = LoggerFactory.getLogger(KbEmbedConsumer.class);

    private final KbEmbedSink sink;

    public KbEmbedConsumer(KbEmbedSink sink) {
        this.sink = sink;
    }

    @RabbitListener(queues = KbTopology.EMBED_QUEUE, containerFactory = "kbListenerContainerFactory")
    public void onMessage(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        KbEmbedBatch batch;
        try {
            batch = KbMessageCodec.decodeEmbed(payload);
        } catch (IllegalArgumentException e) {
            log.error("嵌入消息载荷无法解码，转死信队列（{}）：{}",
                    KbTopology.DEAD_LETTER_QUEUE, e.getMessage());
            throw e;   // 不许吞：静默 ACK = 永久丢数据
        }

        sink.embed(batch);
    }
}
