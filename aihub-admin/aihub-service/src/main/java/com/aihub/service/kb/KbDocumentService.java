package com.aihub.service.kb;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.common.kb.KbStatus;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * <p><b>异步半段不在这里</b>：任务 3 起才"提交后发消息"，本类**不发布任何消息**（也**不留占位/TODO**）。
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
public class KbDocumentService {

    /** 审计的 {@code target_type}；测试按它 + {@code target_id} 定向查（{@code audit_log} 是共享表）。 */
    public static final String AUDIT_TARGET_TYPE = "KB_DOCUMENT";

    /** 资源列表的缺省/上界（Task 2 的接口契约）。 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    /** 本任务的白名单：**只有** md/txt（pdf 属 Task 7，会在那一任务里显式加入）。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("md", "txt");

    private final KbDocumentMapper kbDocumentMapper;
    private final KbFileStore fileStore;
    private final AuditService auditService;

    public KbDocumentService(KbDocumentMapper kbDocumentMapper, KbFileStore fileStore, AuditService auditService) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.fileStore = fileStore;
        this.auditService = auditService;
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
