package com.aihub.admin.kb;

import com.aihub.admin.kb.support.FakeEmbeddingUpstream;
import com.aihub.admin.kb.support.KbIntegrationTestBase;
import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.AuditLogMapper;
import com.aihub.mq.kb.KbTopology;
import com.aihub.service.audit.AuditService;
import com.aihub.service.kb.KbDocumentCleanup;
import com.aihub.service.kb.KbDocumentPublisher;
import com.aihub.service.kb.KbDocumentService;
import com.aihub.service.kb.KbFileStore;
import com.aihub.service.kb.KbVectorStoreClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 Task 6 的**线上契约**：失败路径与死信 —— M5 的官方验收（**全成或全清 / 中断上传不留脏数据**）。
 *
 * <p><b>真容器（MySQL/Redis/RabbitMQ/Chroma）+ 真 HTTP + 真令牌 + 真 broker + 真消费者 + 真向量库</b>，
 * 唯一的假东西是 embeddings 上游（宿主进程内的 {@link FakeEmbeddingUpstream}，D6）。
 * 复用**已有**的 Spring 上下文（{@code @TestPropertySource} 那把字面量与其它 kb 用例**逐字相同**、
 * 且**不加** {@code @Import}）⇒ 判据是全量套件 {@code Tomcat started on port} 仍是 **7**。
 *
 * <p><b>三条核心判据</b>（计划 Task 6 Step 1）：
 * <ol>
 *   <li>{@link #aBatchThatKeepsFailingLeavesTheVectorStoreCleanAndTheMessageInTheDlq()} —— 四处**同时**断言：
 *       {@code FAILED} + Chroma 干净 + {@code kb_chunk} 干净 + 消息在 DLQ + 一条 {@code KB_DOCUMENT_FAILED} 审计；</li>
 *   <li>{@link #aDocumentWithNoExtractableTextFailsWithAnExplicitReason()} —— D14：0 段 ⇒
 *       {@code FAILED("无可提取文本")}（**绝不静默 READY**）+ 审计；</li>
 *   <li>{@link #cleanupIsIdempotentAndReportsIncompleteCleanupHonestly()} —— 清理幂等可重入；
 *       清理失败**如实**登记（{@code error_msg} 含"清理未完成"且**保留坐标**）。</li>
 * </ol>
 *
 * <p><b>DLQ 是共享的</b>（单例 broker 上所有上下文共用一条 {@code aihub.kb.dlq}）：因此
 * {@code @BeforeEach} **先排空**，并用**非破坏性**读数 {@code AmqpAdmin.getQueueInfo(...).getMessageCount()}
 * 判深度（既有先例 {@code MeteringConsumerIntegrationTest} 用的是 {@code receive} 的排空式读法，
 * 那会与"断言消息还在里面"冲突 —— 这里改用只读的深度）。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=console-it-secret-0123456789abcdefghijklmn"
})
class KbFailureIntegrationTest extends KbIntegrationTestBase {

    /** 本任务专用租户（与 Task 2/3/4/5 的 920001..920004 区分）。 */
    private static final long TENANT = 920_005L;

    private static final String KB_TARGET_TYPE = "KB_DOCUMENT";

    /** 17000 字符 ⇒ 在 size=800 / overlap=100（步长 700）下恰好 25 段（与 Task 4/5 的算法同一份）。 */
    private static final int CHUNKS = 25;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private AmqpAdmin amqpAdmin;

    /** 生产服务（含 Task 6 新增的终态处置）：第三条用例用它做手工装配的对照。 */
    @Autowired
    private KbDocumentService kbDocumentService;

    @Autowired
    private KbFileStore fileStore;

    @Autowired
    private AuditService auditService;

    @Autowired
    private KbDocumentPublisher publisher;

    /** 与生产同一个键；测试**只读**它（用来手工构造一个"会失败"的向量库客户端）。 */
    @Value("${aihub.kb.chroma.base-url:}")
    private String chromaBaseUrl;

    @Value("${aihub.kb.chroma.collection:kb_chunks}")
    private String chromaCollection;

    @BeforeEach
    void resetUpstreamAndDrainDlq() {
        FakeEmbeddingUpstream.reset();
        drainDlq();
    }

    @AfterEach
    void cleanFixtures() {
        FakeEmbeddingUpstream.reset();
        cleanKbFixture(TENANT);
        drainDlq();
    }

    // ---------------------------------------------------------------- 1) 重试耗尽 ⇒ 全清 + DLQ

    @Test
    void aBatchThatKeepsFailingLeavesTheVectorStoreCleanAndTheMessageInTheDlq() throws Exception {
        FakeEmbeddingUpstream.respondWithDeterministicVectors(8);
        FakeEmbeddingUpstream.failFromBatch(3);   // 前两批成功、第三批起**一直**失败 ⇒ 重试 3 次全失败
        long id = uploadId(TENANT, "doc.md", markdownOf(17_000, "段落"));

        // 失败终态由 KbMessageRecoverer 写（D7）——它在重试**耗尽**之后才被调用。
        awaitUntil(Duration.ofSeconds(40), () -> "FAILED".equals(statusOf(id)), id);

        assertThat(vectorStore.countByDocId(id)).as("★ 全清：Chroma 里一个 chunk 都不许留").isZero();
        assertThat(chunkRowsFor(id)).as("★ 全清：坐标也要清掉").isZero();
        assertThat(errorMsgOf(id)).as("必须能看出是什么错（embeddings 上游返回 500）").contains("embed");
        assertThat(auditRowsFor(id, "KB_DOCUMENT_FAILED")).as("失败必须留审计").isEqualTo(1);

        // 死信与失败终态**同时**发生（D7）：恢复器先置 FAILED、立刻抛出 ⇒ 消息经 DLX 落到 DLQ。
        // 置 FAILED 与"拒绝入队"之间有微秒级间隙 ⇒ 用**有界轮询**消除竞争，而不是靠"睡一觉"。
        awaitUntil(Duration.ofSeconds(10), () -> dlqDepth() > 0, id);
        assertThat(dlqDepth()).as("★ 死信与失败终态必须同时发生").isGreaterThan(0);
    }

    // ---------------------------------------------------------------- 2) D14：0 段 ⇒ FAILED

    @Test
    void aDocumentWithNoExtractableTextFailsWithAnExplicitReason() throws Exception {
        FakeEmbeddingUpstream.respondWithDeterministicVectors(8);

        long id = uploadId(TENANT, "empty.md", "   \n\n".getBytes(UTF_8));

        awaitUntil(Duration.ofSeconds(20), () -> "FAILED".equals(statusOf(id)), id);
        assertThat(errorMsgOf(id)).as("扫描版 PDF 的 YAGNI 出口：如实说没说文本").contains("无可提取文本");
        assertThat(chunkRowsFor(id)).as("0 段就该 0 行坐标").isZero();
        assertThat(statusOf(id)).as("绝不许静默 READY").isNotEqualTo("READY");
        // 这条路**不是异常**（走 markNoText，不经恢复器）⇒ 它必须**自己**写 KB_DOCUMENT_FAILED（裁定 #6②）。
        assertThat(auditRowsFor(id, "KB_DOCUMENT_FAILED")).as("0 段也必须留失败审计").isEqualTo(1);
        assertThat(dlqDepth()).as("0 段不是消费失败，不该进 DLQ").isZero();
    }

    // ---------------------------------------------------------------- 3) 清理幂等 + 如实登记

    @Test
    void cleanupIsIdempotentAndReportsIncompleteCleanupHonestly() throws Exception {
        FakeEmbeddingUpstream.respondWithDeterministicVectors(8);

        // ① 幂等可重入：先造一份 READY 的真实文档（Chroma 里 25 条 + kb_chunk 25 行），清两次。
        long id = uploadId(TENANT, "cleanup.md", markdownOf(17_000, "清理"));
        awaitUntil(Duration.ofSeconds(90), () -> "READY".equals(statusOf(id)), id);
        assertThat(vectorStore.countByDocId(id)).as("前置：Chroma 里确实有 25 条").isEqualTo(CHUNKS);

        KbDocumentCleanup cleanup = new KbDocumentCleanup(vectorStore, kbChunkMapper);
        assertThat(cleanup.cleanup(id).clean()).as("第一次清理必须干净").isTrue();
        assertThat(cleanup.cleanup(id).clean()).as("对已干净的 doc 重入也必须报干净（不许抛）").isTrue();
        assertThat(chunkRowsFor(id)).as("坐标已清").isZero();
        assertThat(vectorStore.countByDocId(id)).as("向量已清").isZero();

        // ② 清理失败必须**如实登记**：让 Chroma delete 抛 ⇒ 保留 kb_chunk（可重入）+ error_msg 含"清理未完成"。
        long id2 = uploadId(TENANT, "cleanup-fail.md", markdownOf(17_000, "清理失败"));
        awaitUntil(Duration.ofSeconds(90), () -> "READY".equals(statusOf(id2)), id2);

        // 2026-10-05 控制器订正（**测试的前置条件写错了，实现是对的**）：`markFailed` **刻意**只允许
        // **非终态**（PENDING/PARSING/EMBEDDING）转 FAILED —— 免得一条**迟到**的死信消息把已经 READY 的
        // 文档"救死"（见 KbDocumentService#markFailed 的 javadoc）。所以这里必须先把行推回非终态，
        // 否则 onTerminalFailure 会被守卫**正确地**拒绝，用例就红在 "expected FAILED but was READY"。
        KbDocumentEntity backToEmbedding = new KbDocumentEntity();
        backToEmbedding.setId(id2);
        backToEmbedding.setStatus("EMBEDDING");
        kbDocumentMapper.updateById(backToEmbedding);   // 局部实体 ⇒ 只更新 status（NOT_NULL 策略）
        assertThat(statusOf(id2)).as("前置：必须回到非终态，否则守卫会（正确地）拒绝置 FAILED")
                .isEqualTo("EMBEDDING");

        KbVectorStoreClient faultyVectorStore = new KbVectorStoreClient(chromaBaseUrl, chromaCollection, 30) {
            @Override
            public void deleteByDocId(long docId) {
                throw new IllegalStateException("注入的 Chroma 删除失败");
            }
        };
        KbDocumentCleanup faultyCleanup = new KbDocumentCleanup(faultyVectorStore, kbChunkMapper);
        // 手工装配：用**会失败**的清理替身驱动终态处置，其余依赖用真 bean（真 DB / 真审计）。
        KbDocumentService serviceWithFaultyCleanup =
                new KbDocumentService(kbDocumentMapper, fileStore, auditService, publisher, faultyCleanup);
        serviceWithFaultyCleanup.onTerminalFailure(id2, "embed", new IllegalStateException("embeddings 上游返回 500"));

        assertThat(statusOf(id2)).as("失败终态照常置位").isEqualTo("FAILED");
        assertThat(chunkRowsFor(id2)).as("清理未完成时**坐标必须保留**（可重入）").isEqualTo(CHUNKS);
        assertThat(errorMsgOf(id2)).as("清理失败必须如实登记，绝不假装干净").contains("清理未完成");
        assertThat(auditRowsFor(id2, "KB_DOCUMENT_FAILED")).as("失败必须留审计").isEqualTo(1);
    }

    // ---------------------------------------------------------------- 助手

    private String errorMsgOf(long docId) {
        KbDocumentEntity row = kbDocumentMapper.selectById(docId);
        return row == null ? null : row.getErrorMsg();
    }

    /** 按本次文档 id + action 定向查审计（{@code audit_log} 是共享表，绝不做全表计数）。 */
    private long auditRowsFor(long docId, String action) {
        return auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, action)
                .eq(AuditLogEntity::getTargetType, KB_TARGET_TYPE)
                .eq(AuditLogEntity::getTargetId, String.valueOf(docId)));
    }

    /** 非破坏性读 DLQ 深度（{@code getQueueInfo} 不改动队列；排空式读法会与"断言消息还在"冲突）。 */
    private int dlqDepth() {
        QueueInformation info = amqpAdmin.getQueueInfo(KbTopology.DEAD_LETTER_QUEUE);
        return info == null ? 0 : info.getMessageCount();
    }

    /** 排空共享 DLQ：别的用例 / 其它上下文可能留下消息，不排空会让"深度 > 0"变成假绿。 */
    private void drainDlq() {
        amqpAdmin.purgeQueue(KbTopology.DEAD_LETTER_QUEUE, false);
    }
}
