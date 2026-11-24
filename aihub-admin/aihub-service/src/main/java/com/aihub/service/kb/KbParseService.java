package com.aihub.service.kb;

import com.aihub.common.kb.KbStatus;
import com.aihub.dao.entity.KbChunkEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbChunkMapper;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.mq.kb.KbEmbedBatch;
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
 * 解析阶段的实现（M5 Task 4）：实现 {@link KbParseSink}，被 {@code KbParseConsumer} 调用。
 *
 * <p>流水线（<b>一个事务</b>）：条件迁移 {@code PENDING|PARSING|FAILED → PARSING} → 读 {@code kb_document} →
 * 定位原件 → 抽取文本 → 切分 → 逐段 upsert {@code kb_chunk}（{@code vector_id = "{docId}:{seq}"}，D15）
 * → {@code chunk_count=N} + {@code EMBEDDING} → 注册 after-commit 钩子发 N 条 {@code kb.embed}
 * （{@code ceil(N/batchSize)} 条）。
 *
 * <p><b>为什么整段放一个事务、而消息在 after-commit 才发</b>：embed 消费者（Task 5）会按
 * {@code (docId, seq)} 去读 {@code kb_chunk}；若在事务里就发，它可能读到"还没提交的段"，
 * 于是要么空跑要么死信。after-commit 钩子保证"段已落库"先于"消息可见"。
 *
 * <p>条件迁移仅接受待解析、解析中和失败的文档。重试 {@code FAILED} 文档时，先清理上次遗留的分段和向量，
 * 再按当前窗口重新切分。
 *
 * <p><b>D14</b>：切出来 0 段（空 / 全空白文档、未来的无文本层 PDF）⇒ 置 {@code FAILED("无可提取文本")}，
 * 不发 embed。<b>绝不静默 {@code READY}</b> —— 那会让检索侧永远查不到却显示成功。
 *
 * <p><b>异常一律往外抛</b>（原件缺失、抽取失败、落库失败）：静默 ACK 等于永久丢数据，必须走
 * "重试 3 次 → DLQ"。原件不存在<b>先按"抛"处理</b>（Task 6 再决定要不要转成 {@code FAILED}）。
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
    private final KbDocumentPublisher publisher;
    private final KbDocumentService kbDocumentService;
    private final KbDocumentCleanup cleanup;
    private final int sizeChars;
    private final int overlapChars;
    private final int batchSize;

    public KbParseService(KbDocumentMapper kbDocumentMapper,
                          KbChunkMapper kbChunkMapper,
                          KbFileStore fileStore,
                          KbTextExtractor textExtractor,
                          KbDocumentPublisher publisher,
                          KbDocumentService kbDocumentService,
                          KbDocumentCleanup cleanup,
                          @Value("${aihub.kb.chunk.size-chars:800}") int sizeChars,
                          @Value("${aihub.kb.chunk.overlap-chars:100}") int overlapChars,
                          @Value("${aihub.kb.embed.batch-size:10}") int batchSize) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.fileStore = fileStore;
        this.textExtractor = textExtractor;
        this.publisher = publisher;
        this.kbDocumentService = kbDocumentService;
        this.cleanup = cleanup;
        this.sizeChars = sizeChars;
        this.overlapChars = overlapChars;
        this.batchSize = batchSize;
    }

    @Override
    @Transactional
    public void parse(long docId) {
        if (!moveToParsing(docId, KbStatus.PENDING, KbStatus.PARSING)) {
            if (!moveToParsing(docId, KbStatus.FAILED)) {
                log.info("解析跳过：doc {} 不存在或已进入嵌入阶段，ack 丢弃", docId);
                return;
            }
            cleanFailedRetry(docId);
        }

        KbDocumentEntity doc = kbDocumentMapper.selectById(docId);
        if (doc == null) {
            // 刚 update 命中过它，读不回来说明映射/装配坏了 —— 响亮失败，别静默。
            throw new IllegalStateException("迁移到 PARSING 后却读不回 kb_document：docId=" + docId);
        }

        // 消息里只有 docId ⇒ 从行里取 tenant_id + sha256 定位原件（不扫目录、不从消息带路径）。
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

        // 段已写好（提交后会可见）；消息在 after-commit 才真正发出 ⇒ embed 消费者读得到段。
        for (KbEmbedBatch batch : KbTopology.embedBatches(docId, chunkCount, batchSize)) {
            publisher.publishEmbedAfterCommit(batch);
        }
        log.info("解析完成：doc {} 切出 {} 段，将发 {} 条 kb.embed（batchSize={}）",
                docId, chunkCount, (chunkCount + batchSize - 1) / batchSize, batchSize);
    }

    private boolean moveToParsing(long docId, String... currentStatuses) {
        return kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, docId)
                .in(KbDocumentEntity::getStatus, List.of(currentStatuses))
                .set(KbDocumentEntity::getStatus, KbStatus.PARSING)
                .set(KbDocumentEntity::getErrorMsg, null)) > 0;
    }

    private void cleanFailedRetry(long docId) {
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
