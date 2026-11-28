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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 嵌入请求在事务外执行，写回时锁定当前文档并复核代次和分段。 */
@Service
public class KbEmbedService implements KbEmbedSink {

    private static final Logger log = LoggerFactory.getLogger(KbEmbedService.class);

    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;
    private final KbEmbeddingClient embeddingClient;
    private final KbVectorStoreClient vectorStore;
    private final TransactionTemplate transactions;

    public KbEmbedService(KbDocumentMapper kbDocumentMapper,
                          KbChunkMapper kbChunkMapper,
                          KbEmbeddingClient embeddingClient,
                          KbVectorStoreClient vectorStore,
                          TransactionTemplate transactions) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.transactions = transactions;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void embed(KbEmbedBatch batch, long generation) {
        long docId = batch.docId();
        if (!isCurrentEmbedding(kbDocumentMapper.selectById(docId), generation)) {
            return;
        }

        List<KbChunkEntity> pending = pendingChunks(batch);
        Map<Long, float[]> computed = new HashMap<>();
        if (!pending.isEmpty()) {
            List<float[]> vectors = embeddingClient.embed(pending.stream().map(KbChunkEntity::getText).toList());
            for (int i = 0; i < pending.size(); i++) {
                computed.put(pending.get(i).getId(), vectors.get(i));
            }
        }

        transactions.executeWithoutResult(status -> writePending(batch, generation, computed));
    }

    private void writePending(KbEmbedBatch batch, long generation, Map<Long, float[]> computed) {
        KbDocumentEntity doc = kbDocumentMapper.lockDocument(batch.docId());
        if (!isCurrentEmbedding(doc, generation)) {
            return;
        }
        List<KbChunkEntity> pending = pendingChunks(batch);
        if (!pending.isEmpty()) {
            List<KbVectorStoreClient.VectorRecord> records = new ArrayList<>(pending.size());
            for (KbChunkEntity chunk : pending) {
                float[] vector = computed.get(chunk.getId());
                if (vector == null) {
                    throw new IllegalStateException("嵌入期间出现未计算的待嵌入分段：" + chunk.getId());
                }
                records.add(new KbVectorStoreClient.VectorRecord(vectorIdOf(chunk), vector,
                        metadataOf(chunk, doc.getTenantId())));
            }
            vectorStore.upsert(records);
            kbChunkMapper.update(null, new LambdaUpdateWrapper<KbChunkEntity>()
                    .in(KbChunkEntity::getId, pending.stream().map(KbChunkEntity::getId).toList())
                    .isNull(KbChunkEntity::getEmbeddedAt)
                    .set(KbChunkEntity::getEmbeddedAt, LocalDateTime.now(ZoneOffset.UTC)));
        }

        // 恢复历史文档时，批次可以只触发 READY 判定。
        promoteToReadyWhenEveryChunkIsEmbedded(doc);
    }

    private static boolean isCurrentEmbedding(KbDocumentEntity doc, long generation) {
        return doc != null && doc.getIngestGeneration() == generation && KbStatus.EMBEDDING.equals(doc.getStatus());
    }

    private List<KbChunkEntity> pendingChunks(KbEmbedBatch batch) {
        return kbChunkMapper.selectList(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, batch.docId())
                .between(KbChunkEntity::getSeq, batch.seqFrom(), batch.seqTo())
                .isNull(KbChunkEntity::getEmbeddedAt)
                .orderByAsc(KbChunkEntity::getSeq));
    }

    private void promoteToReadyWhenEveryChunkIsEmbedded(KbDocumentEntity doc) {
        long docId = doc.getId();
        long embedded = kbChunkMapper.selectCount(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId).isNotNull(KbChunkEntity::getEmbeddedAt));
        long total = kbChunkMapper.selectCount(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId));
        if (total == 0 || total != doc.getChunkCount() || embedded != total) {
            return;
        }
        kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, docId)
                .set(KbDocumentEntity::getStatus, KbStatus.READY));
        log.info("文档 {} 的全部 {} 段已嵌入 ⇒ READY", docId, total);
    }

    /** {@code vector_id} 优先用解析阶段写的（{@code "{docId}:{seq}"}，D15）；缺失时才按同一规则补算。 */
    private static String vectorIdOf(KbChunkEntity chunk) {
        String existing = chunk.getVectorId();
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return chunk.getDocId() + ":" + chunk.getSeq();
    }

    /** Chroma 的 metadata：<b>逐字</b>照计划附录 A 的必填字段（改字段名对检索侧是破坏性变更）。 */
    private static Map<String, Object> metadataOf(KbChunkEntity chunk, long tenantId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("doc_id", chunk.getDocId());
        metadata.put("tenant_id", tenantId);
        metadata.put("seq", chunk.getSeq());
        return metadata;
    }
}
