package com.aihub.service.kb;

import com.aihub.common.kb.KbStatus;
import com.aihub.dao.entity.KbChunkEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbChunkMapper;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.mq.kb.KbEmbedBatch;
import com.aihub.mq.kb.KbEmbedSink;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌入阶段的实现（M5 Task 5）：实现 {@link KbEmbedSink}，被 {@code KbEmbedConsumer} 调用。
 *
 * <p>流水线：读 {@code [seqFrom, seqTo]} 的段（连同文档行的 {@code tenant_id}）→ 调 embeddings 上游
 * → **按 {@code vector_id} upsert 进 Chroma**（metadata `doc_id`/`tenant_id`/`seq`，附录 A 的跨仓库契约）
 * → 给这些段打 {@code embedded_at} → 若该 doc 的段**全部**已打满 ⇒ 条件迁移 {@code EMBEDDING → READY}。
 *
 * <p><b>为什么这里刻意**没有** {@code @Transactional}</b>（与解析阶段的"整段一个事务"形成对照）：
 * 解析阶段的那个事务保护一个**真不变量**（段 + {@code chunk_count} + 状态必须一起成立）；
 * 而嵌入阶段的两个外部调用（embeddings、Chroma）各有最长 30 秒的超时，把它们包进一个数据库事务
 * 会让连接被**网络等待**占住；而这里的"单元"其实都是**单条条件语句**（打 {@code embedded_at}、`EMBEDDING→READY`），
 * 并且 {@code READY} 的判定是**从行数算出来的**（`embedded == total`）⇒ 中途失败后**重试会自愈**，
 * 不需要（也不该有）跨网络调用的事务。
 *
 * <p><b>D14 的出口不在这里</b>：{@code 0 段 ⇒ FAILED("无可提取文本")} 由解析阶段判（Task 4）。
 * <b>终态 {@code FAILED} + 清理 + 审计</b>也不在这里 —— 那是 Task 6 的 {@code MessageRecoverer}（D7）：
 * 本类的职责是"要么把这一段推进到 READY，要么把异常原样抛出去让重试/DLQ 接管"。
 */
@Service
public class KbEmbedService implements KbEmbedSink {

    private static final Logger log = LoggerFactory.getLogger(KbEmbedService.class);

    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;
    private final KbEmbeddingClient embeddingClient;
    private final KbVectorStoreClient vectorStore;

    public KbEmbedService(KbDocumentMapper kbDocumentMapper,
                          KbChunkMapper kbChunkMapper,
                          KbEmbeddingClient embeddingClient,
                          KbVectorStoreClient vectorStore) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    @Override
    public void embed(KbEmbedBatch batch) {
        long docId = batch.docId();
        KbDocumentEntity doc = kbDocumentMapper.selectById(docId);
        if (doc == null) {
            log.warn("嵌入跳过：kb_document {} 不存在（可能已被清理），ack 丢弃", docId);
            return;
        }
        if (!KbStatus.EMBEDDING.equals(doc.getStatus())) {
            // READY/FAILED/PENDING/PARSING 都不是"可以嵌入"的状态：前者已完成、中者已终态、
            // 后两者说明解析还没走到发消息（或这一批是抢跑）⇒ ack 丢弃，绝不把状态往回拨。
            log.info("嵌入跳过：doc {} 状态是 {}（不是 EMBEDDING），ack 丢弃", docId, doc.getStatus());
            return;
        }

        List<KbChunkEntity> chunks = chunksOfBatch(docId, batch);
        if (chunks.isEmpty()) {
            log.warn("嵌入跳过：doc {} 的段 {}..{} 一行都没有（可能已被清理），ack 丢弃",
                    docId, batch.seqFrom(), batch.seqTo());
            return;
        }

        // 一次调用把这一批的文本全嵌了（要么全成、要么整体抛 —— 不存在"半批"落库的形态）。
        List<String> texts = chunks.stream().map(KbChunkEntity::getText).toList();
        List<float[]> vectors = embeddingClient.embed(texts);

        List<KbVectorStoreClient.VectorRecord> records = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            KbChunkEntity chunk = chunks.get(i);
            records.add(new KbVectorStoreClient.VectorRecord(vectorIdOf(chunk), vectors.get(i),
                    metadataOf(chunk, doc.getTenantId())));
        }
        vectorStore.upsert(records);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);   // datetime(3) 一律显式 UTC（CONVENTIONS §7）
        kbChunkMapper.update(null, new LambdaUpdateWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId)
                .between(KbChunkEntity::getSeq, batch.seqFrom(), batch.seqTo())
                .isNull(KbChunkEntity::getEmbeddedAt)            // 重放不覆盖首次嵌入时间
                .set(KbChunkEntity::getEmbeddedAt, now));

        promoteToReadyWhenEveryChunkIsEmbedded(docId);
    }

    private List<KbChunkEntity> chunksOfBatch(long docId, KbEmbedBatch batch) {
        return kbChunkMapper.selectList(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId)
                .between(KbChunkEntity::getSeq, batch.seqFrom(), batch.seqTo())
                .orderByAsc(KbChunkEntity::getSeq));
    }

    /**
     * 全部段都嵌入之后才把文档推到 {@code READY}（**从行数算出来的**，因此可重入、可自愈）。
     *
     * <p>迁移**带条件**（`status = EMBEDDING`）：若这期间有人把它置成 {@code FAILED}，本方法不许把它"救活"
     * —— 那会掩盖一次真实的失败（受影响行数 0 就什么都不做）。
     */
    private void promoteToReadyWhenEveryChunkIsEmbedded(long docId) {
        long total = kbChunkMapper.selectCount(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId));
        long embedded = kbChunkMapper.selectCount(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId)
                .isNotNull(KbChunkEntity::getEmbeddedAt));
        if (total == 0 || embedded < total) {
            return;
        }
        int updated = kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, docId)
                .eq(KbDocumentEntity::getStatus, KbStatus.EMBEDDING)
                .set(KbDocumentEntity::getStatus, KbStatus.READY));
        if (updated > 0) {
            log.info("文档 {} 的全部 {} 段已嵌入 ⇒ READY", docId, total);
        }
    }

    /** {@code vector_id} 优先用解析阶段写的（{@code "{docId}:{seq}"}，D15）；缺失时才按同一规则补算。 */
    private static String vectorIdOf(KbChunkEntity chunk) {
        String existing = chunk.getVectorId();
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return chunk.getDocId() + ":" + chunk.getSeq();
    }

    /** Chroma 的 metadata：**逐字**照计划附录 A 的必填字段（改字段名对检索侧是破坏性变更）。 */
    private static Map<String, Object> metadataOf(KbChunkEntity chunk, long tenantId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("doc_id", chunk.getDocId());
        metadata.put("tenant_id", tenantId);
        metadata.put("seq", chunk.getSeq());
        return metadata;
    }
}
