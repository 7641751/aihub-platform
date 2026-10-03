package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.service.audit.AuditService;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.kb.KbDocumentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 文档入库的**写入侧**接口（M5 Task 2，{@code /api/kb/documents}）：上传（同步半段）+ 列表/状态轮询。
 *
 * <p>由 {@link ConsoleAuthFilter} 守门（ADMIN 可读写、VIEWER 只读），本类只做「HTTP DTO ↔ 服务层入参」
 * 的转换与 {@link ApiResponse} 包装；错误一律走 {@link BizException} → {@code GlobalExceptionHandler}
 * （{@code INVALID_PARAM} 400 / {@code UNAUTHORIZED} 401），**不写自定义错误体**。
 *
 * <p><b>响应契约是 admin 信封</b>（{@code {"code","message","data"}}），**不是**数据面的 OpenAI 兼容体。
 *
 * <p><b>租户语义（CONVENTIONS §10）</b>：{@code POST} 的租户取**请求体**（R2，写操作是平台级，
 * 运营必须能给任一租户入库）；{@code GET} 是**资源列表** ⇒ 缺省租户取**令牌里的租户**（R3.2）。
 * 两者都**不**用操作者的租户去覆盖目标资源的租户。
 *
 * <p><b>multipart 而不是 JSON</b>：原件是二进制（Task 7 起包括 PDF），JSON 装不下。
 * 上传上限由 {@code spring.servlet.multipart.max-file-size}（20MB）钉在 {@code application.yml} ——
 * 默认的 1MB 会**静默拒绝**稍大的文件，那是本项目最容易"看起来能跑、其实全挂"的地方。
 */
@RestController
@RequestMapping("/api/kb/documents")
public class KbDocumentController {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final KbDocumentService kbDocumentService;

    public KbDocumentController(KbDocumentService kbDocumentService) {
        this.kbDocumentService = kbDocumentService;
    }

    /** 上传响应：只暴露状态与进度，**绝不**回显原件内容。 */
    public record UploadView(long id, String status, int chunkCount, boolean duplicate) {
    }

    /** 列表里的一行（{@code errorMsg} 是失败原因的非敏感摘要）。 */
    public record ItemView(long id, String filename, long sizeBytes, String status, int chunkCount,
                           String errorMsg, String createdAt) {
    }

    /** 列表页：{@code total} 是**满足条件的总行数**（不是本页行数）。 */
    public record PageView(List<ItemView> items, long total, int page, int size) {
    }

    /**
     * 上传一份原件并触发（本任务只建行的）入库流程。
     *
     * @param tenantId **目标资源的租户**，来自 multipart 文本字段；缺省 ⇒ 400（R2：写操作必须显式指定目标租户）
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UploadView>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "tenantId", required = false) Long tenantId,
            HttpServletRequest http) {
        if (tenantId == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "tenantId 不能为空");
        }
        if (file == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "file 不能为空");
        }
        KbDocumentService.UploadResult result;
        try (InputStream in = file.getInputStream()) {
            // 刻意**不**拒绝零字节文件：空文档是合法输入，它会在流水线的切分那一步
            // 得到 FAILED("无可提取文本")（M5 D14）—— 在这里拦掉会让那条出口永远测不到。
            result = kbDocumentService.upload(tenantId, file.getOriginalFilename(), in, actor(http));
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传流失败", e);
        }
        KbDocumentEntity row = result.row();
        return ResponseEntity.ok(ApiResponse.ok(
                new UploadView(row.getId(), row.getStatus(), row.getChunkCount(), result.duplicate())));
    }

    /**
     * 列表 + 状态轮询（页面靠它拿 {@code status}/{@code chunkCount}）。
     *
     * <p>边界（Task 2 的接口契约）：{@code page} 缺省 0、负数 400；{@code size} 缺省 20、{@code >200} **钳到 200**、
     * {@code <1} 400。校验顺序有意写在钳位之前：{@code size=0} 是**入参错误**（400），不是"被钳成 1"。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageView>> list(
            @RequestParam(name = "tenantId", required = false) Long tenantId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size,
            HttpServletRequest http) {
        if (page < 0) {
            throw new BizException(ErrorCode.INVALID_PARAM, "page 不能为负数");
        }
        if (size < 1) {
            throw new BizException(ErrorCode.INVALID_PARAM, "size 必须 ≥ 1");
        }
        int clampedSize = Math.min(size, KbDocumentService.MAX_PAGE_SIZE);
        long effectiveTenant = tenantId != null ? tenantId : claims(http).tenantId();
        KbDocumentService.Page result = kbDocumentService.list(effectiveTenant, status, page, clampedSize);
        List<ItemView> items = result.rows().stream().map(KbDocumentController::itemView).toList();
        return ResponseEntity.ok(ApiResponse.ok(new PageView(items, result.total(), result.page(), result.size())));
    }

    private static ItemView itemView(KbDocumentEntity row) {
        return new ItemView(row.getId(), row.getFilename(), row.getSizeBytes(), row.getStatus(),
                row.getChunkCount(), row.getErrorMsg(),
                row.getCreatedAt() == null ? null : ISO_MILLIS.format(row.getCreatedAt()));
    }

    /**
     * 从请求属性取过滤器验过的 claims。
     *
     * <p>正常路径下 {@code ConsoleAuthFilter} 已经拦掉了无令牌请求，所以 {@code null} 只可能发生在
     * 「控制器被注册、但过滤器没跑」这种装配错误里 —— 宁可回 401，也不返回一份看起来通过鉴权的响应。
     */
    private static ConsoleClaims claims(HttpServletRequest request) {
        Object attribute = request.getAttribute(ConsoleAuthFilter.ATTRIBUTE_CLAIMS);
        if (!(attribute instanceof ConsoleClaims claims)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少控制台令牌");
        }
        return claims;
    }

    /** 审计主体：上传是**用户**发起的动作（{@code actor_type=USER}）。 */
    private static AuditService.Actor actor(HttpServletRequest request) {
        return new AuditService.Actor("USER", String.valueOf(claims(request).userId()));
    }
}
