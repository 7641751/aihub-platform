package com.aihub.mq.kb;

/**
 * 一批待嵌入的分段区间（M5 D10）。
 *
 * <p>载荷是**闭区间** {@code [seqFrom, seqTo]}；分段文本不随消息传（从 {@code kb_chunk} 按
 * {@code (docId, seq)} 读）—— 这样消息体积恒定、可重放，也能让它天然进 DLQ 之后被人工重投。
 *
 * <p>本类由计划漏写（2026-10-03 控制器订正），它是 {@link KbTopology#embedBatches} 的返回元素类型，
 * 也是 {@link KbMessageCodec#decodeEmbed} 的返回类型。
 */
public record KbEmbedBatch(long docId, int seqFrom, int seqTo) {
}
