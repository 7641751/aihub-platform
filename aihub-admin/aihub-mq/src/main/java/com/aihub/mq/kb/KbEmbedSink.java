package com.aihub.mq.kb;

/**
 * 嵌入阶段的接缝（M5 Task 5），与 {@link KbParseSink} **同形**。
 *
 * <p><b>为什么需要它</b>：依赖方向是 {@code web → service → mq}，而 {@code mq} 的依赖面只有
 * {@code aihub-common} + {@code spring-boot-starter-amqp}（**看不到 {@code aihub-dao} / {@code aihub-service}**，实测 pom）。
 * 嵌入要读 {@code kb_chunk}、调 embeddings 上游、写 Chroma —— 全在 service 侧，因此消费者只依赖这个接口。
 * 形状逐字照既有计量链路的 {@link com.aihub.mq.meter.MeteringSink}（"照抄既有形状、不发明新形状"）。
 */
public interface KbEmbedSink {

    /**
     * 把一批段嵌入并写进向量库：读 {@code [seqFrom, seqTo]} 的段（带出文档的 {@code tenant_id}）→
     * 调 embeddings → **按 {@code vector_id} upsert 进 Chroma** → 给这些段打 {@code embedded_at} →
     * 若该 doc 的段**全部**已打满，则条件迁移 {@code EMBEDDING → READY}。
     *
     * <p><b>幂等 / 抢跑语义</b>：段不存在（已被清理）或文档不在 {@code EMBEDDING}（已是 {@code READY}/{@code FAILED}）
     * ⇒ 什么都不做直接返回（消息照常 ACK）。同一批重放 ⇒ Chroma 按 id 覆盖、{@code embedded_at} 不重复写 ⇒ 收敛。
     *
     * <p><b>异常一律往外抛</b>（不是 log + return）：静默 ACK 等于永久丢数据。抛出后交给容器的重试策略
     * （3 次指数退避）→ {@code RejectAndDontRequeueRecoverer} → DLQ；**终态 {@code FAILED} + 清理**由
     * Task 6 的 {@code MessageRecoverer} 写（D7：进 DLQ 与置 FAILED 必须是同一处）。
     *
     * @param batch 一批段的坐标（{@code embed:{docId}:{seqFrom}:{seqTo}}）
     */
    void embed(KbEmbedBatch batch);
}
