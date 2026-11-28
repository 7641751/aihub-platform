package com.aihub.service.kb;

import com.aihub.common.kb.KbStatus;
import com.aihub.dao.entity.KbChunkEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbChunkMapper;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.dao.mapper.KbOutboxMapper;
import com.aihub.mq.kb.KbEmbedBatch;
import com.aihub.mq.kb.KbMessageCodec;
import com.aihub.mq.kb.KbParseSink;
import com.aihub.mq.kb.KbTopology;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 同一事务内锁定当前代次，写入分段、嵌入状态和全部嵌入任务。
 * 重传后的解析先清除遗留分段及向量，再生成新一代的分段。
 */
@Service
public class KbParseService implements KbParseSink {

    /** D14 的失败原因：扫描版 / 图片型 PDF 的 YAGNI 出口，不做 OCR。 */
    public static final String NO_TEXT_REASON = "无可提取文本";

    private static final Logger log = LoggerFactory.getLogger(KbParseService.class);

    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;
    private final KbFileStore fileStore;
    private final KbTextExtractor textExtractor;
    private final KbOutboxMapper outboxMapper;
    private final KbDocumentService kbDocumentService;
    private final KbDocumentCleanup cleanup;
    private final int sizeChars;
    private final int overlapChars;
    private final int batchSize;

    public KbParseService(KbDocumentMapper kbDocumentMapper,
                          KbChunkMapper kbChunkMapper,
                          KbFileStore fileStore,
                          KbTextExtractor textExtractor,
                          KbOutboxMapper outboxMapper,
                          KbDocumentService kbDocumentService,
                          KbDocumentCleanup cleanup,
                          @Value("${aihub.kb.chunk.size-chars:800}") int sizeChars,
                          @Value("${aihub.kb.chunk.overlap-chars:100}") int overlapChars,
                          @Value("${aihub.kb.embed.batch-size:10}") int batchSize) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.fileStore = fileStore;
        this.textExtractor = textExtractor;
        this.outboxMapper = outboxMapper;
        this.kbDocumentService = kbDocumentService;
        this.cleanup = cleanup;
        this.sizeChars = sizeChars;
        this.overlapChars = overlapChars;
        this.batchSize = batchSize;
    }

    @Override
    @Transactional
    public void parse(long docId, long generation) {
        KbDocumentEntity doc = kbDocumentMapper.lockDocument(docId);
        if (doc == null || doc.getIngestGeneration() != generation
                || !(KbStatus.PENDING.equals(doc.getStatus()) || KbStatus.PARSING.equals(doc.getStatus()))) {
            log.info("解析跳过 docId={} generation={}", docId, generation);
            return;
        }
        cleanPreviousChunks(docId);
        kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, docId)
                .set(KbDocumentEntity::getStatus, KbStatus.PARSING)
                .set(KbDocumentEntity::getErrorMsg, null));

        Path file = fileStore.targetFor(doc.getTenantId(), doc.getSha256());
        if (!Files.exists(file)) {
            // 先按"抛"处理（Task 6 再决定要不要转 FAILED）。
            throw new IllegalStateException("原件不存在，无法解析：docId=" + docId + " path=" + file);
        }

        String text = textExtractor.extract(extensionOf(doc.getFilename()), file);
        List<String> chunks = KbChunker.chunk(text, sizeChars, overlapChars);
        if (chunks.isEmpty()) {
            markNoText(docId);
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);   // datetime(3) 一律显式 UTC（CONVENTIONS §7）
        for (int seq = 0; seq < chunks.size(); seq++) {
            KbChunkEntity chunk = new KbChunkEntity();
            chunk.setDocId(docId);
            chunk.setSeq(seq);
            chunk.setText(chunks.get(seq));
            chunk.setVectorId(docId + ":" + seq);   // D15：稳定可推导 ⇒ 重放即覆盖
            chunk.setCreatedAt(now);
            kbChunkMapper.upsert(chunk);            // 命中 uk_kb_chunk_doc_seq 即覆盖（重放不产生重复段）
        }

        int chunkCount = chunks.size();
        kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, docId)
                .set(KbDocumentEntity::getChunkCount, chunkCount)
                .set(KbDocumentEntity::getStatus, KbStatus.EMBEDDING));

        for (KbEmbedBatch batch : KbTopology.embedBatches(docId, chunkCount, batchSize)) {
            outboxMapper.enqueue(docId, generation, KbTopology.EMBED_ROUTING_KEY,
                    KbMessageCodec.embed(docId, batch.seqFrom(), batch.seqTo()));
        }
        log.info("解析完成：doc {} 切出 {} 段，将发 {} 条 kb.embed（batchSize={}）",
                docId, chunkCount, (chunkCount + batchSize - 1) / batchSize, batchSize);
    }

    private void cleanPreviousChunks(long docId) {
        if (!kbChunkMapper.exists(new LambdaQueryWrapper<KbChunkEntity>().eq(KbChunkEntity::getDocId, docId))) {
            return;
        }
        KbDocumentCleanup.Result result = cleanup.cleanup(docId);
        if (!result.clean()) {
            throw new IllegalStateException("文档 " + docId + " 重试前清理未完成：" + result.detail());
        }
    }

    /**
     * D14：0 段 ⇒ 终态失败（{@link #NO_TEXT_REASON}）。
     *
     * <p><b>这条路不是异常</b>（它只是"切出来 0 段"），所以它不会经过 {@code MessageRecoverer} ⇒
     * 它必须<b>自己</b>把 {@code KB_DOCUMENT_FAILED} 审计写出来（裁定 #6②）。为避免两处各写一份审计逻辑，
     * 委托到 {@link KbDocumentService#markFailed}（同一路径也保证"只在条件更新命中了行时写"、
     * 且 {@code error_msg} 的写法与失败终态一致）。本方法在 {@link #parse} 的事务里被调用 ⇒ 更新与审计同事务。
     */
    private void markNoText(long docId) {
        boolean marked = kbDocumentService.markFailed(docId, NO_TEXT_REASON);
        if (marked) {
            log.warn("解析无文本可切：doc {} 置 FAILED(\"{}\")（D14，不做 OCR）", docId, NO_TEXT_REASON);
        } else {
            log.info("解析无文本可切：doc {} 无需再置 FAILED（已是终态 / 行不存在），ack 丢弃", docId);
        }
    }

    /** {@code doc.md -> md}；无扩展名 ⇒ 空串（交给 {@link KbTextExtractor} 快速失败）。 */
    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String base = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1);
    }
}
