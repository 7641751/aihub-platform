package com.aihub.mq.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

import java.nio.charset.StandardCharsets;

/**
 * kb 流水线的**失败终态恢复器**（M5 Task 6，决策 D7）。
 *
 * <p><b>它在流水线里的位置</b>：{@code @RabbitListener} 的重试策略是**3 次指数退避**
 * （值取自 {@code spring.rabbitmq.listener.simple.retry.*}）；重试**全部耗尽**之后才轮到
 * {@link MessageRecoverer#recover(Message, Throwable)}。因此"进 DLQ"（容器把消息
 * requeue=false 拒绝 ⇒ 经业务队列的 {@code x-dead-letter-exchange} 进 {@code aihub.kb.dlq}）
 * 与"置 {@code FAILED} + 清理 + 审计"**必然同时发生** —— 这正是 D7 要求的"必须同一处"。
 *
 * <p><b>⚠️ 恢复器必须"抛出"才进得了 DLQ</b>（本任务最关键的一条，被 javap 实测钉死）：
 * Spring AMQP 的语义是 —— {@code recover(...)} **正常返回 ⇒ 容器 ack 这条消息**（消息就此消失）；
 * **只有抛** {@link AmqpRejectAndDontRequeueException} 才会以 requeue=false 拒绝 ⇒ 进 DLQ。
 * ⇒ DB 部分（置 FAILED / 清理 / 审计）可以 try/catch（记日志），但**最后必须抛**。
 * {@code catch → return} 会让"置 FAILED"与"进 DLQ"**只剩一半**，而 {@code dlqDepth() > 0} 那条断言
 * 会红 —— 那正是它该红的样子。
 *
 * <p><b>为什么是"专用恢复器 + 专用容器工厂"而不是改默认工厂</b>：本仓的
 * {@code retry.*} / {@code default-requeue-rejected} 是**全局**的，把它们（以及一个自定义 recoverer）
 * 挂到默认工厂上会**一起改掉 {@code MeteringConsumer} 的死信行为**（它有自己的 DLQ 用例）。
 * ⇒ 见 {@link KbTopologyConfig#kbListenerContainerFactory}：只有 kb 的两个消费者走这个专用工厂。
 *
 * <p><b>本类刻意在 {@code aihub-mq} 里</b>（依赖方向 {@code web → service → mq}，mq 看不到 service）：
 * 真正的处置（cleanup + 置 FAILED + 审计）通过 {@link TerminalFailureHandler} 这个**接缝**回调到
 * service 侧的实现（{@code KbDocumentService}）。形状照 {@link KbParseSink}/{@link KbEmbedSink}
 * 的既有先例（"消费侧定义接口、service 侧实现"）。
 */
public class KbMessageRecoverer implements MessageRecoverer {

    /**
     * 终态失败的处置接缝：把"这份文档彻底失败了"交给 service 侧（cleanup → 置 FAILED → 审计）。
     *
     * <p>定义在 {@code aihub-mq}、由 {@code aihub-service}（{@code KbDocumentService}）实现 —— 与
     * {@link KbParseSink} / {@link KbEmbedSink} 同样的理由：mq 的依赖面看不到 dao/service。
     *
     * @param docId {@code kb_document.id}（从消息载荷解出）
     * @param stage 失败发生在哪个阶段（{@code "parse"} / {@code "embed"}），用于让 {@code error_msg}
     *              能看出是哪一段失败的
     * @param cause 重试耗尽时的**最后一个**异常（恢复器按它派生失败原因）
     */
    public interface TerminalFailureHandler {

        void onTerminalFailure(long docId, String stage, Throwable cause);
    }

    private static final Logger log = LoggerFactory.getLogger(KbMessageRecoverer.class);

    private final TerminalFailureHandler handler;

    public KbMessageRecoverer(TerminalFailureHandler handler) {
        this.handler = handler;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        String queue = message.getMessageProperties() == null ? null : message.getMessageProperties().getConsumerQueue();
        String stage = stageOf(queue);

        Long docId = decodeDocId(queue, payload);
        if (docId != null) {
            try {
                handler.onTerminalFailure(docId, stage, cause);
            } catch (RuntimeException e) {
                // DB 部分允许失败（记日志），但**绝不**因此吞掉下面那条"拒绝"：消息仍必须进 DLQ 等人排查。
                log.error("死信终态处置失败（仍拒绝并进 DLQ）：docId={} stage={} ⇒ {}: {}",
                        docId, stage, e.getClass().getSimpleName(), e.getMessage());
            }
        } else {
            // 载荷连 docId 都解不出（真正的坏消息）：无处可置 FAILED/清理，但消息照样必须进 DLQ。
            log.error("死信载荷解不出 docId，跳过置 FAILED/清理（仍拒绝并进 DLQ）：queue={} payload={}",
                    queue, payload.length() > 128 ? payload.substring(0, 128) + "…" : payload);
        }

        // ⚠️ 必须抛：正常返回 = 容器 ack 这条消息 = 消息消失（既没置 FAILED、也没进 DLQ）。
        throw new AmqpRejectAndDontRequeueException(
                "kb 消息重试耗尽，" + stage + " 阶段已放弃并转死信队列 " + KbTopology.DEAD_LETTER_QUEUE, cause);
    }

    /** 阶段名来自容器投递时写的 {@code consumerQueue}（{@code aihub.kb.parse} / {@code aihub.kb.embed}）。 */
    private static String stageOf(String queue) {
        if (KbTopology.EMBED_QUEUE.equals(queue)) {
            return "embed";
        }
        if (KbTopology.PARSE_QUEUE.equals(queue)) {
            return "parse";
        }
        return queue == null ? "unknown" : queue;
    }

    /**
     * 从载荷里解出 {@code docId}。用 {@code consumerQueue} 决定解码器（两种载荷前缀不同）；
     * 解不出返回 {@code null}（调用方仍会拒绝进 DLQ，只是没有可标记的 doc）。
     */
    private static Long decodeDocId(String queue, String payload) {
        try {
            if (KbTopology.EMBED_QUEUE.equals(queue)) {
                return KbMessageCodec.decodeEmbed(payload).docId();
            }
            return KbMessageCodec.decodeParse(payload);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
