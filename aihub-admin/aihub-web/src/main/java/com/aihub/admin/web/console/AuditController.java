package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.service.log.AuditQueryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 审计日志的运营查询（{@code GET /api/audit}，Task 11）：由 {@link ConsoleAuthFilter} 守门（只读）。
 * 语义与 {@link LogQueryController} **统一**：必须显式 {@code tenantId}（§10 R3.1 运营查询）+ 时间范围，
 * {@code size} 缺省 20 / 钳到 200 / {@code <1} 即 400，{@code page} 缺省 0 / {@code <0} 即 400，
 * {@code from <= to}，排序 {@code created_at DESC}。
 *
 * <p>校验助手与 {@link LogQueryController} 逐字相同是**有意**的：Task 11 的文件集封闭（计划里没有共享
 * 工具类），所以各自持有一份小助手而不是新建未声明的文件（理由同 {@code LogQueryController} 的类注释）。
 *
 * <p>错误一律走 {@link BizException} → {@code GlobalExceptionHandler}。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 200;

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    public record AuditPage(long total, int page, int size, List<AuditQueryService.AuditLogView> records) {
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AuditPage>> query(
            @RequestParam(name = "tenantId", required = false) Long tenantId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        long tenant = requireTenantId(tenantId);
        Instant start = requireInstant(from, "from");
        Instant end = requireInstant(to, "to");
        requireOrderedRange(start, end);
        int effectivePage = requirePage(page);
        int effectiveSize = requireSize(size);

        Page<AuditQueryService.AuditLogView> result = auditQueryService.page(tenant, start, end, effectivePage,
                effectiveSize);
        return ResponseEntity.ok(ApiResponse.ok(
                new AuditPage(result.getTotal(), effectivePage, effectiveSize, result.getRecords())));
    }

    private static long requireTenantId(Long tenantId) {
        if (tenantId == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "tenantId 不能为空（运营查询必须显式指定租户）");
        }
        return tenantId;
    }

    private static Instant requireInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    field + " 不能为空（必须是带 Z 的 ISO-8601 绝对时刻，例：2026-09-01T00:00:00Z）");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不是合法的 ISO-8601 时刻");
        }
    }

    private static void requireOrderedRange(Instant from, Instant to) {
        if (from.isAfter(to)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "from 不能晚于 to");
        }
    }

    private static int requirePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new BizException(ErrorCode.INVALID_PARAM, "page 必须 >= 0");
        }
        return page;
    }

    private static int requireSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        if (size < 1) {
            throw new BizException(ErrorCode.INVALID_PARAM, "size 必须 >= 1");
        }
        return Math.min(size, MAX_SIZE);
    }
}
