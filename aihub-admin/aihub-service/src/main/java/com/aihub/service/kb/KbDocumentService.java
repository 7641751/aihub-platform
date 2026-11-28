package com.aihub.service.kb;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.common.kb.KbStatus;
import com.aihub.dao.entity.KbChunkEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbChunkMapper;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.dao.mapper.KbOutboxMapper;
import com.aihub.mq.kb.KbEmbedBatch;
import com.aihub.mq.kb.KbMessageCodec;
import com.aihub.mq.kb.KbMessageRecoverer;
import com.aihub.mq.kb.KbTopology;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 上传事务提交文档、审计和解析任务；派发器从发件箱投递已提交的任务。
 * 同一租户的相同内容共用原件和文档行。重传失败文档时，在行锁内启动新一代入库。
 */
@Service
public class KbDocumentService implements KbMessageRecoverer.TerminalFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(KbDocumentService.class);

    /** 审计的 {@code target_type}；测试按它 + {@code target_id} 定向查（{@code audit_log} 是共享表）。 */
    public static final String AUDIT_TARGET_TYPE = "KB_DOCUMENT";

    /** 终态失败是<b>系统</b>产生的（D13）：{@code actor_type=SYSTEM}，actor id 标明是 kb 流水线。 */
    private static final AuditService.Actor SYSTEM_ACTOR = new AuditService.Actor("SYSTEM", "kb-pipeline");

    /** 单条失败原因的 UTF-16 长度上限，包含清理说明和省略号。 */
    private static final int MAX_ERROR_MSG_CHARS = 512;

    /** 资源列表的缺省/上界（Task 2 的接口契约）。 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    /** 白名单：`md` / `txt` / `pdf`（pdf 由 M5 Task 7 显式加入；三处必须同时扩：本白名单 + `KbTextExtractor` + 用例）。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("md", "txt", "pdf");

    private final KbDocumentMapper kbDocumentMapper;
    private final KbFileStore fileStore;
    private final AuditService auditService;
    private final KbOutboxMapper outboxMapper;
    private final KbChunkMapper kbChunkMapper;
    private final KbDocumentCleanup cleanup;

    public KbDocumentService(KbDocumentMapper kbDocumentMapper, KbFileStore fileStore,
                             AuditService auditService, KbOutboxMapper outboxMapper,
                             KbChunkMapper kbChunkMapper, KbDocumentCleanup cleanup) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.fileStore = fileStore;
        this.auditService = auditService;
        this.outboxMapper = outboxMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.cleanup = cleanup;
    }

    /**
     * 上传结果。
     *
     * @param row       {@code kb_document} 那一行（<b>新建或已存在</b>，两种情况都非 null）
     * @param duplicate 本次是否命中了已有的同内容文档
     */
    public record UploadResult(KbDocumentEntity row, boolean duplicate) {
    }

    /**
     * 列表页。
     *
     * @param rows  当前页
     * @param total 满足条件的<b>总行数</b>（不是本页行数）
     */
    public record Page(List<KbDocumentEntity> rows, long total, int page, int size) {
    }

    /**
     * 上传一份原件。
     *
     * @param tenantId <b>目标资源的租户</b>（来自请求体，不是操作者的租户 —— CONVENTIONS §10 R2）
     * @param filename 原始文件名（白名单校验 + 落进 {@code kb_document.filename}，<b>不进路径</b>）
     * @param in       原件字节流（<b>只读一遍</b>：摘要与落盘共用同一次 IO）
     */
    @Transactional
    public UploadResult upload(long tenantId, String filename, InputStream in, AuditService.Actor actor) {
        String safeName = requireAllowedFilename(filename);
        KbFileStore.StagedFile staged = fileStore.stage(tenantId, in);
        try {
            boolean duplicate = find(tenantId, staged.sha256()) != null;
            if (!duplicate) {
                kbDocumentMapper.insertIfAbsent(tenantId, safeName, staged.sizeBytes(), staged.sha256(), KbStatus.PENDING);
            }
            KbDocumentEntity row = kbDocumentMapper.lockByContent(tenantId, staged.sha256());
            if (row == null) {
                throw new IllegalStateException(
                        "无法读回上传文档：tenantId=" + tenantId + " sha256=" + staged.sha256());
            }
            if (Files.exists(fileStore.targetFor(tenantId, staged.sha256()))) {
                fileStore.discard(staged);
            } else {
                fileStore.promote(staged);
            }
            if (KbStatus.FAILED.equals(row.getStatus())) {
                restartIngestion(row);
            }
            audit(tenantId, actor, row, duplicate);
            if (KbStatus.PENDING.equals(row.getStatus()) || KbStatus.PARSING.equals(row.getStatus())) {
                outboxMapper.enqueue(row.getId(), row.getIngestGeneration(), KbTopology.PARSE_ROUTING_KEY,
                        KbMessageCodec.parse(row.getId()));
            }
            return new UploadResult(row, duplicate);
        } catch (RuntimeException e) {
            // promote 成功后临时文件已不存在，deleteIfExists 是幂等的 —— 这里只处理"还没改名就失败"的情形。
            fileStore.discard(staged);
            throw e;
        }
    }

    private void restartIngestion(KbDocumentEntity row) {
        long generation = Math.incrementExact(row.getIngestGeneration());
        kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, row.getId())
                .set(KbDocumentEntity::getIngestGeneration, generation)
                .set(KbDocumentEntity::getStatus, KbStatus.PENDING)
                .set(KbDocumentEntity::getChunkCount, 0)
                .set(KbDocumentEntity::getErrorMsg, null));
        row.setIngestGeneration(generation);
        row.setStatus(KbStatus.PENDING);
        row.setChunkCount(0);
        row.setErrorMsg(null);
    }

    /**
     * 资源列表（CONVENTIONS §10 R3.2：缺省租户由<b>调用方</b>决定 —— 控制器用令牌里的租户填进来）。
     *
     * <p><b>不用 {@code PaginationInnerInterceptor}</b>：本项目的类路径上没有
     * {@code mybatis-plus-jsqlparser}，所以在 SQL 末尾拼 {@code LIMIT/OFFSET}（{@code page}/{@code size}
     * 已在控制器里校验过是<b>有界整数</b>，不存在注入面）。
     */
    public Page list(long tenantId, String status, int page, int size) {
        long total = kbDocumentMapper.selectCount(baseQuery(tenantId, status));
        List<KbDocumentEntity> rows = kbDocumentMapper.selectList(baseQuery(tenantId, status)
                .orderByDesc(KbDocumentEntity::getCreatedAt)
                .orderByDesc(KbDocumentEntity::getId)
                .last("LIMIT " + size + " OFFSET " + ((long) page * size)));
        return new Page(rows, total, page, size);
    }

    /** 构造一个<b>全新的</b>查询条件（{@code selectCount} 与 {@code selectList} 各用一份，避免复用同一个 wrapper）。 */
    private static LambdaQueryWrapper<KbDocumentEntity> baseQuery(long tenantId, String status) {
        LambdaQueryWrapper<KbDocumentEntity> query = new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getTenantId, tenantId);
        if (status != null && !status.isBlank()) {
            query.eq(KbDocumentEntity::getStatus, status);
        }
        return query;
    }

    private KbDocumentEntity find(long tenantId, String sha256) {
        return kbDocumentMapper.selectOne(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getTenantId, tenantId)
                .eq(KbDocumentEntity::getSha256, sha256));
    }

    private void audit(long tenantId, AuditService.Actor actor, KbDocumentEntity row, boolean duplicate) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("filename", row.getFilename());
        detail.put("sizeBytes", row.getSizeBytes());
        detail.put("sha256", row.getSha256());
        detail.put("duplicate", duplicate);
        auditService.record(tenantId, actor, AuditAction.KB_DOCUMENT_UPLOAD, AUDIT_TARGET_TYPE,
                String.valueOf(row.getId()), detail);
    }

    // ---------------------------------------------------------------- 失败终态（M5 Task 6，D7/D8/D13）

    /** 只有当前代次仍待处理的工作可以进入终态清理。恢复器随后独立拒绝消息进入 DLQ。 */
    @Override
    @Transactional
    public void onTerminalFailure(long docId, long generation, String stage, KbEmbedBatch batch, Throwable cause) {
        KbDocumentEntity doc = kbDocumentMapper.lockDocument(docId);
        if (doc == null || doc.getIngestGeneration() != generation || !hasOutstandingWork(doc, stage, batch)) {
            log.info("跳过终态清理 docId={} generation={} stage={}", docId, generation, stage);
            return;
        }

        KbDocumentCleanup.Result result = cleanup.cleanup(docId);
        String reason = buildFailureReason(stage, cause, result);
        boolean marked = markFailed(docId, reason);
        if (marked) {
            log.warn("文档 {} 进入终态 FAILED（stage={}，清理{}）：{}",
                    docId, stage, result.clean() ? "干净" : "未完成", reason);
        } else {
            log.info("文档 {} 无需再置 FAILED，清理{}", docId, result.clean() ? "干净" : "未完成");
        }
    }

    private boolean hasOutstandingWork(KbDocumentEntity doc, String stage, KbEmbedBatch batch) {
        String status = doc.getStatus();
        if ("parse".equals(stage)) {
            return KbStatus.PENDING.equals(status) || KbStatus.PARSING.equals(status);
        }
        if (!"embed".equals(stage) || batch == null || batch.docId() != doc.getId()
                || !(KbStatus.EMBEDDING.equals(status) || KbStatus.FAILED.equals(status))) {
            return false;
        }
        return kbChunkMapper.exists(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, doc.getId())
                .between(KbChunkEntity::getSeq, batch.seqFrom(), batch.seqTo())
                .isNull(KbChunkEntity::getEmbeddedAt));
    }

    /**
     * 把文档条件迁移到 {@code FAILED} 并写 {@code error_msg}；<b>命中一行才写</b> {@code KB_DOCUMENT_FAILED} 审计。
     *
     * <p>条件 {@code status IN (PENDING, PARSING, EMBEDDING)}：只有<b>非终态</b>才能转 FAILED ——
     * 已经是 {@code READY}/{@code FAILED} 的行既不改也不重复记账（幂等、可重入；也避免一条迟到的
     * 死信消息把 {@code READY} 的文档"救死"）。与 D14 的 0 段出口共用本方法（裁定 #6②）。
     *
     * @return 是否<b>真的</b>迁移了（{@code true} = 命中一行并写了审计）
     */
    @Transactional
    public boolean markFailed(long docId, String reason) {
        String safeReason = reason == null ? "" : reason;
        KbDocumentEntity doc = kbDocumentMapper.selectById(docId);
        if (doc == null) {
            // 行不存在（可能已被清理）：无处可标记，如实返回 false 而不是抛 —— 恢复器仍会拒绝进 DLQ。
            return false;
        }
        int updated = kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, docId)
                .in(KbDocumentEntity::getStatus, KbStatus.PENDING, KbStatus.PARSING, KbStatus.EMBEDDING)
                .set(KbDocumentEntity::getStatus, KbStatus.FAILED)
                .set(KbDocumentEntity::getErrorMsg, safeReason));
        if (updated == 0) {
            return false;
        }
        // 审计与状态迁移在同一事务（AuditService 的纪律）⇒ "改了但没审计"不可能。
        // detail 只放非敏感摘要（失败原因）；审计**绝不记**原件内容 / chunk 文本 / 向量。
        auditService.record(doc.getTenantId(), SYSTEM_ACTOR, AuditAction.KB_DOCUMENT_FAILED,
                AUDIT_TARGET_TYPE, String.valueOf(docId), Map.of("reason", safeReason));
        return true;
    }

    /** 有界地派生失败原因：能看出<b>哪一段</b>失败（stage）+ 异常类名 + 摘要；清理未完成时如实追加。 */
    private static String buildFailureReason(String stage, Throwable cause, KbDocumentCleanup.Result cleanup) {
        String suffix = cleanup != null && !cleanup.clean()
                ? truncate("；清理未完成：" + cleanup.detail(), MAX_ERROR_MSG_CHARS / 2) : "";
        String primary = stage + " 阶段失败：" + summarize(cause);
        return truncate(primary, MAX_ERROR_MSG_CHARS - suffix.length()) + suffix;
    }

    /**
     * 异常摘要：沿 {@code cause} 链（有界深度）拼接"类名 + 消息"。
     *
     * <p><b>为什么要走整条链而不是只看最外层</b>：监听容器抛出的可能是包装异常
     * （{@code ListenerExecutionFailedException}），真正的根因（如
     * {@code embeddings 上游返回 500}）在更深的一层；只看最外层会让 {@code error_msg} 失去诊断价值。
     */
    private static String summarize(Throwable cause) {
        if (cause == null) {
            return "<无异常>";
        }
        StringBuilder text = new StringBuilder();
        Throwable current = cause;
        int depth = 0;
        while (current != null && depth < 6) {
            if (text.length() > 0) {
                text.append(" ← ");
            }
            text.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                text.append(": ").append(message);
            }
            Throwable next = current.getCause();
            current = (next == current) ? null : next;
            depth++;
        }
        return text.toString();
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int end = maxChars - 1;
        if (end > 0 && Character.isSurrogatePair(text.charAt(end - 1), text.charAt(end))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    /**
     * 文件名白名单 + 取"基底名"。
     *
     * <p>文件名<b>不进路径</b>（路径用内容摘要），但审计与展示要靠它，所以顺手把路径成分剥掉：
     * 任何 {@code ../} 或目录分隔符都只保留最后一段，避免"文件名"里带着别处的路径。
     */
    private static String requireAllowedFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "文件名不能为空");
        }
        String trimmed = filename.trim();
        int lastSlash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String base = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
        if (base.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "文件名不能为空");
        }
        int dot = base.lastIndexOf('.');
        String extension = dot < 0 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "不支持的文件类型：" + (extension.isEmpty() ? "（无扩展名）" : "." + extension)
                            + "，当前只接受 " + ALLOWED_EXTENSIONS);
        }
        return base;
    }
}
