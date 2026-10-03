package com.aihub.mq.kb;

import java.util.ArrayList;
import java.util.List;

/**
 * kb 流水线（M5 Task 3）的拓扑名字与分批纯函数。
 *
 * <p><b>admin 是唯一的声明方</b>（{@link KbTopologyConfig}）；发布端（{@code KbDocumentPublisher}）
 * 与消费端（Task 4/5）都按这些常量工作。常量是**唯一真相源**：单侧改名不会编译失败，
 * 只会变成「消息永远投不到队列」。
 *
 * <p><b>命名形状</b>照既有计量链路（{@code MeteringTopology}）：业务 exchange + N 条业务队列 +
 * 一个 DLX + 一个 DLQ。两个阶段的**失败代价不同**（解析失败 = 文件问题，嵌入失败 = 上游/向量库问题），
 * 因此各一条队列；**共用 DLQ** 让运维只需盯一个地方。
 *
 * <p><b>命名与既有计量链路逐字同构</b>（决策 **D16**）：{@code aihub.kb.exchange}（exchange）/
 * {@code aihub.kb.parse}（routing + queue）/ {@code aihub.kb.embed}（routing + queue）/
 * {@code aihub.kb.dlx} / {@code aihub.kb.dlq}，形状与 {@code MeteringTopology} 一一对应。
 * <b>照抄形状、不发明新形状</b>：运维在两个链路之间切换时不该重新学一套命名。
 * （2026-10-03 记：本类一度把 exchange 写成 {@code aihub.kb}、路由键写成裸的 {@code kb.parse} ——
 * 那是个"看起来更漂亮"但与 D16 冲突的形状，已按决策表改回。）
 */
public final class KbTopology {

    /** 业务交换机。 */
    public static final String EXCHANGE = "aihub.kb.exchange";

    /** 解析阶段队列（`PENDING|PARSING → PARSING`）。 */
    public static final String PARSE_QUEUE = "aihub.kb.parse";
    /** 解析阶段路由键。 */
    public static final String PARSE_ROUTING_KEY = "aihub.kb.parse";

    /** 嵌入阶段队列。 */
    public static final String EMBED_QUEUE = "aihub.kb.embed";
    /** 嵌入阶段路由键。 */
    public static final String EMBED_ROUTING_KEY = "aihub.kb.embed";

    /** 死信交换机。 */
    public static final String DEAD_LETTER_EXCHANGE = "aihub.kb.dlx";
    /** 死信队列。 */
    public static final String DEAD_LETTER_QUEUE = "aihub.kb.dlq";
    /** 死信路由键（与死信队列同名，照 {@code MeteringTopology}）。 */
    public static final String DEAD_LETTER_ROUTING_KEY = "aihub.kb.dlq";

    /** 线格式的内容类型（与 {@code MeteringTopology} 同款）。 */
    public static final String MESSAGE_CONTENT_TYPE = "text/plain;charset=UTF-8";

    private KbTopology() {
    }

    /**
     * 把 {@code chunkCount} 个分段按 {@code batchSize} 切成批次区间（**纯函数**，D10）。
     *
     * <p>产出 {@code ceil(N/batchSize)} 条；**最后一批可以不满**。第 i 批是闭区间
     * {@code [i*batchSize, min((i+1)*batchSize-1, chunkCount-1)]}。用**区间**而不是"每批带文本"，
     * 让消息体积恒定且可重放（文本从 {@code kb_chunk} 读）。
     *
     * @param docId      文档 id（批次归属）
     * @param chunkCount 分段总数；{@code 0} ⇒ **空列表**（0 段不该走到这里：Task 4 会先判
     *                   {@code FAILED("无可提取文本")}，D14）
     * @param batchSize  每批段数，**必须为正**（0/负会让切分除零或死循环 ⇒ 快速失败）
     */
    public static List<KbEmbedBatch> embedBatches(long docId, int chunkCount, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 必须为正：" + batchSize);
        }
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount 不能为负：" + chunkCount);
        }
        List<KbEmbedBatch> batches = new ArrayList<>((chunkCount + batchSize - 1) / batchSize);
        for (int from = 0; from < chunkCount; from += batchSize) {
            int to = Math.min(from + batchSize - 1, chunkCount - 1);
            batches.add(new KbEmbedBatch(docId, from, to));
        }
        return batches;
    }
}
