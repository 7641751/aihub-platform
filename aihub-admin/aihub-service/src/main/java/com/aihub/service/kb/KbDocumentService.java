package com.aihub.service.kb;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.common.kb.KbStatus;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.mq.kb.KbMessageRecoverer;
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
 * 文档入库的**同步半段**（M5 Task 2）：校验 → 边读边算 sha256 + 原子落盘 → 建 {@code kb_document(PENDING)} 行 → 审计。
 *
 * <p><b>异步半段不在本类</b>：本类只在**建行之后**注册一个 after-commit 钩子，由
 * {@link KbDocumentPublisher} 在**事务提交之后**把 {@code parse:{docId}} 投到 {@code kb.parse}
 * （Task 3）。发布失败不升级成业务失败（见 {@code KbDocumentPublisher} 的登记）。
 *
 * <p><b>幂等是唯一键给的</b>：{@code uk_kb_document_tenant_sha(tenant_id, sha256)} ⇒ 同租户同内容永远只有一行。
 * 重复上传是**用户的正常动作**（点了两次、换个文件名再传一次），所以按 M4 Task 12 的定稿处理：
 * {@code INSERT ... ON DUPLICATE KEY UPDATE id = id} + 读回，**不抛异常、不 catch {@code DuplicateKeyException}}**。
 *
 * <p><b>「中断上传不留脏数据」在这里的实现</b>：先落**临时**文件、建行、再原子改名。任何一步抛异常 ⇒
 * 事务回滚（行没了）+ 临时文件被丢掉（文件没了）。⇒ 绝不出现"有行没文件"或"有文件没行"的中间态。
 *
 * <p><b>审计</b>：成功路径写一条 {@code KB_DOCUMENT_UPLOAD}（{@code actor_type=USER}），
 * detail 只放**非敏感**字段（文件名、字节数、摘要、是否重复）—— 绝不记原件内容（D13）。
 * 重复上传**也写审计**（"有人又传了一次同一个文件"是有运维价值的事实）。
 */
@Service
public class KbDocumentService implements KbMessageRecoverer.TerminalFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(KbDocumentService.class);

    /** 审计的 {@code target_type}；测试按它 + {@code target_id} 定向查（{@code audit_log} 是共享表）。 */
    public static final String AUDIT_TARGET_TYPE = "KB_DOCUMENT";

    /** 终态失败是**系统**产生的（D13）：{@code actor_type=SYSTEM}，actor id 标明是 kb 流水线。 */
    private static final AuditService.Actor SYSTEM_ACTOR = new AuditService.Actor("SYSTEM", "kb-pipeline");

    /**
     * {@code error_msg} 列是 {@code VARCHAR(1024)}；本类派生的原因**有界**（远小于列宽，
     * 留足余量给"清理未完成"等后缀），免得一条超长异常把列写爆（严格模式下是硬失败）。
     */
    private static final int MAX_ERROR_MSG_CHARS = 512;

    /** 资源列表的缺省/上界（Task 2 的接口契约）。 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    /** 白名单：`md` / `txt` / `pdf`（pdf 由 M5 Task 7 显式加入；三处必须同时扩：本白名单 + `KbTextExtractor` + 用例）。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("md", "txt", "pdf");

    private final KbDocumentMapper kbDocumentMapper;
    private final KbFileStore fileStore;
    private final AuditService auditService;
    private final KbDocumentPublisher publisher;
    private final KbDocumentCleanup cleanup;

    public KbDocumentService(KbDocumentMapper kbDocumentMapper, KbFileStore fileStore,
                             AuditService auditService, KbDocumentPublisher publisher,
                             KbDocumentCleanup cleanup) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.fileStore = fileStore;
        this.auditService = auditService;
        this.publisher = publisher;
        this.cleanup = cleanup;
    }

    /**
     * 上传结果。
     *
     * @param row       {@code kb_document} 那一行（**新建或已存在**，两种情况都非 null）
     * @param duplicate 本次是否命中了已有的同内容文档
     */
    public record UploadResult(KbDocumentEntity row, boolean duplicate) {
    }

    /**
     * 列表页。
     *
     * @param rows  当前页
     * @param total 满足条件的**总行数**（不是本页行数）
     */
    public record Page(List<KbDocumentEntity> rows, long total, int page, int size) {
    }

    /**
     * 上传一份原件。
     *
     * @param tenantId **目标资源的租户**（来自请求体，不是操作者的租户 —— CONVENTIONS §10 R2）
     * @param filename 原始文件名（白名单校验 + 落进 {@code kb_document.filename}，**不进路径**）
     * @param in       原件字节流（**只读一遍**：摘要与落盘共用同一次 IO）
     */
    @Transactional
    public UploadResult upload(long tenantId, String filename, InputStream in, AuditService.Actor actor) {
        String safeName = requireAllowedFilename(filename);
        KbFileStore.StagedFile staged = fileStore.stage(tenantId, in);
        try {
            KbDocumentEntity existing = find(tenantId, staged.sha256());
            if (existing != null) {
                // 已存在同内容的行 ⇒ 幂等返回它。临时文件正常丢掉；**唯一例外**是原件在盘上不见了
                //（运维误删），这时把它补上 —— 落一份正确的内容比留一个"有行没文件"的破洞便宜得多。
                if (Files.exists(fileStore.targetFor(tenantId, staged.sha256()))) {
                    fileStore.discard(staged);
                } else {
                    fileStore.promote(staged);
                }
                audit(tenantId, actor, existing, true);
                // 控制器裁决（2026-10-03）：**幂等路径也要发**。原先这里不发，理由是"那一行可能已过了 PENDING，
                // 重新驱动解析没有意义（运维重发由 DLQ/计数器的出口负责）" —— 那句话**是错的**：
                // DLQ 只管**消费端**失败，计数器只**观测**发布失败，两者都不会把消息重新推下去。
                // 于是"发布丢了 ⇒ 该行永远 PENDING"成了**没有任何补救手段**的死状态。
                // 让重复上传也驱动一次解析，等于给运维一个现成的重试手势。
                // 代价（如实登记）：对一份**已经 READY** 的文档再传一次会多做一次解析 —— 有界（用户动作驱动），
                // 且 Task 4/5 的写入按设计是幂等的（重放收敛到同一状态）。
                // D5 的口径随之更正为「对**数据**无副作用（不新增行/文件），但会**重新触发解析**」。
                publisher.publishParseAfterCommit(existing.getId());
                return new UploadResult(existing, true);
            }

            kbDocumentMapper.insertIfAbsent(tenantId, safeName, staged.sizeBytes(), staged.sha256(), KbStatus.PENDING);
            fileStore.promote(staged);
            KbDocumentEntity row = find(tenantId, staged.sha256());
            if (row == null) {
                // 插入成功却读不回来 ⇒ 装配/映射错误，宁可响亮失败也不返回半真结果。
                throw new IllegalStateException(
                        "kb_document 插入后读不回：tenantId=" + tenantId + " sha256=" + staged.sha256());
            }
            audit(tenantId, actor, row, false);
            // Task 3：**建行之后**注册 after-commit 发布 —— 事务回滚时钩子不执行，因此"消息发了但行没提交"
            // 不可能发生。（重复上传那条路径同样会发：两条路径的语义都是"请解析这份文档"。）
            publisher.publishParseAfterCommit(row.getId());
            return new UploadResult(row, false);
        } catch (RuntimeException e) {
            // promote 成功后临时文件已不存在，deleteIfExists 是幂等的 —— 这里只处理"还没改名就失败"的情形。
            fileStore.discard(staged);
            throw e;
        }
    }

    /**
     * 资源列表（CONVENTIONS §10 R3.2：缺省租户由**调用方**决定 —— 控制器用令牌里的租户填进来）。
     *
     * <p><b>不用 {@code PaginationInnerInterceptor}</b>：本项目的类路径上没有
     * {@code mybatis-plus-jsqlparser}，所以在 SQL 末尾拼 {@code LIMIT/OFFSET}（{@code page}/{@code size}
     * 已在控制器里校验过是**有界整数**，不存在注入面）。
     */
    public Page list(long tenantId, String status, int page, int size) {
        long total = kbDocumentMapper.selectCount(baseQuery(tenantId, status));
        List<KbDocumentEntity> rows = kbDocumentMapper.selectList(baseQuery(tenantId, status)
                .orderByDesc(KbDocumentEntity::getCreatedAt)
                .orderByDesc(KbDocumentEntity::getId)
                .last("LIMIT " + size + " OFFSET " + ((long) page * size)));
        return new Page(rows, total, page, size);
    }

    /** 构造一个**全新的**查询条件（{@code selectCount} 与 {@code selectList} 各用一份，避免复用同一个 wrapper）。 */
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

    /**
     * 消息重试耗尽时的终态处置（D7）：**清理 → 置 FAILED → 审计**，且这一切发生在
     * {@code KbMessageRecoverer} 把消息拒绝进 DLQ **之前**（恢复器调完本方法才抛
     * {@code AmqpRejectAndDontRequeueException}）⇒ "死信"与"失败终态"永远同时发生。
     *
     * <p>{@code @Transactional}：让"删 {@code kb_chunk}（清理的第二步）+ 置 FAILED + 审计"落在**一个**事务里。
     * 清理的第一、二步之间隔一次 Chroma 网络调用，把整个清理都放进事务会占住连接 —— 但**失败路径**不以
     * 吞吐为目标，这里选"原子"而不是"连接占用"（与 {@code KbEmbedService} 的取舍相反，理由不同：
     * 那边是**热路径**、每条消息都会走）。
     *
     * @param docId {@code kb_document.id}
     * @param stage 失败阶段（{@code "parse"} / {@code "embed"}），进 {@code error_msg} 便于定位是哪一段
     * @param cause 重试耗尽时的最后一个异常（据此派生失败原因）
     */
    @Override
    @Transactional
    public void onTerminalFailure(long docId, String stage, Throwable cause) {
        KbDocumentCleanup.Result result = cleanup.cleanup(docId);
        String reason = buildFailureReason(stage, cause, result);
        boolean marked = markFailed(docId, reason);
        if (marked) {
            log.warn("文档 {} 进入终态 FAILED（stage={}，清理{}）：{}",
                    docId, stage, result.clean() ? "干净" : "未完成", reason);
        } else {
            log.info("文档 {} 无需再置 FAILED（已是终态 / 行不存在），ack 丢弃", docId);
        }
    }

    /**
     * 把文档条件迁移到 {@code FAILED} 并写 {@code error_msg}；**命中一行才写** {@code KB_DOCUMENT_FAILED} 审计。
     *
     * <p>条件 {@code status IN (PENDING, PARSING, EMBEDDING)}：只有**非终态**才能转 FAILED ——
     * 已经是 {@code READY}/{@code FAILED} 的行既不改也不重复记账（幂等、可重入；也避免一条迟到的
     * 死信消息把 {@code READY} 的文档"救死"）。与 D14 的 0 段出口共用本方法（裁定 #6②）。
     *
     * @return 是否**真的**迁移了（{@code true} = 命中一行并写了审计）
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

    /** 有界地派生失败原因：能看出**哪一段**失败（stage）+ 异常类名 + 摘要；清理未完成时如实追加。 */
    private static String buildFailureReason(String stage, Throwable cause, KbDocumentCleanup.Result cleanup) {
        StringBuilder reason = new StringBuilder();
        reason.append(stage).append(" 阶段失败：").append(summarize(cause));
        if (cleanup != null && !cleanup.clean()) {
            reason.append("；清理未完成：").append(cleanup.detail());
        }
        return truncate(reason.toString(), MAX_ERROR_MSG_CHARS);
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
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }

    /**
     * 文件名白名单 + 取"基底名"。
     *
     * <p>文件名**不进路径**（路径用内容摘要），但审计与展示要靠它，所以顺手把路径成分剥掉：
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
