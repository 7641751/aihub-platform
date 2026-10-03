package com.aihub.mq.kb;

/**
 * 解析阶段的接缝（M5 Task 4）。
 *
 * <p><b>刻意定义在 {@code aihub-mq}（消费侧）而由 {@code aihub-service} 实现</b>：依赖方向是
 * {@code web → service → mq}，且 {@code mq} 的依赖面只有 {@code aihub-common} + {@code spring-boot-starter-amqp}
 * （**看不到 {@code aihub-dao} / {@code aihub-service}**，实测 pom）。解析要读 {@code kb_document}、
 * 读原件、切分、写 {@code kb_chunk}、发 {@code kb.embed} —— 这些都在 service/dao 侧，因此消费者只依赖这个接口。
 * 形状**逐字照既有计量链路**的 {@link com.aihub.mq.meter.MeteringSink}（"照抄既有形状、不发明新形状"）。
 */
public interface KbParseSink {

    /**
     * 尝试把文档推进到 {@code EMBEDDING}：条件迁移 {@code PENDING|PARSING → PARSING} → 读原件 → 抽取 → 切分
     * → upsert {@code kb_chunk} → {@code chunk_count=N} + {@code EMBEDDING} → （提交后）发 N 条 {@code kb.embed}。
     *
     * <p><b>幂等 / 抢跑语义</b>：条件迁移的受影响行数为 0（别人已推进，或它已是 {@code READY}/{@code FAILED}）
     * ⇒ 什么都不做直接返回（消息照常 ACK）。{@code chunkCount == 0}（{@code D14}）⇒ 置
     * {@code FAILED("无可提取文本")}，不发 embed。
     *
     * <p><b>解码 / 读文件 / 落库异常一律往外抛</b>（不是 log + return）：静默 ACK 等于永久丢数据。
     * 抛出后交给容器的重试策略（3 次指数退避）→ {@code RejectAndDontRequeueRecoverer} → DLQ。
     *
     * @param docId {@code kb_document.id}（消息载荷里唯一的东西）
     */
    void parse(long docId);
}
