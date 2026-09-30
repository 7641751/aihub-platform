package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.service.log.RequestLogQueryService;
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
 * 请求日志的运营查询（{@code GET /api/logs}，Task 11）：由 {@link ConsoleAuthFilter} 守门
 * （ADMIN/VIEWER 都可读，本端点只读）。本类只做「查询参数校验 + DTO 包装」，业务在
 * {@link RequestLogQueryService}。
 *
 * <p><b>租户模型（{@code docs/CONVENTIONS.md} §10 R3.1）</b>：这是**运营查询**，{@code tenantId}
 * **必须显式给出**（缺省 400）—— 理由是**防无界扫描**（{@code request_log} 的索引以 {@code tenant_id}
 * 打头），**不是**授权。令牌里的 {@code tenantId}（R4）在这里不参与判定。
 *
 * <p><b>边界被钉死在这里</b>（控制器裁定 3）：{@code size} 缺省 20 / {@code >200} 钳到 200 /
 * {@code <1} → 400；{@code page} 缺省 0 / {@code <0} → 400；{@code from}/{@code to} 必须是能解析成
 * {@link Instant} 的绝对时刻（解析失败 400）、且 {@code from <= to}（否则 400）。
 *
 * <p><b>关于这几条 {@code private static} 校验助手在两/三个控制器里重复</b>：Task 11 的文件集是**封闭**的
 * （计划 Files / Step 5 的 {@code git add} 清单里没有共享工具类），所以这里选择各自持有一份小助手，
 * 而不是新建一个未被声明的文件、或让控制器去调另一个控制器的静态方法。
 *
 * <p>错误一律走 {@link BizException} → {@code GlobalExceptionHandler} → admin 信封。
 */
@RestController
@RequestMapping("/api/logs")
public class LogQueryController {

    /** 缺省页大小（控制器裁定 3）。 */
    static final int DEFAULT_SIZE = 20;
    /** 页大小硬上界：{@code size} 超过它一律钳到它（「分页有上界」不能只是文档承诺）。 */
    static final int MAX_SIZE = 200;

    private final RequestLogQueryService requestLogQueryService;

    public LogQueryController(RequestLogQueryService requestLogQueryService) {
        this.requestLogQueryService = requestLogQueryService;
    }

    /** 分页信封：显式给出 {@code total}/{@code page}/{@code size}，让「钳到 200」这类边界可被断言。 */
    public record LogPage(long total, int page, int size, List<RequestLogQueryService.RequestLogView> records) {
    }

    @GetMapping
    public ResponseEntity<ApiResponse<LogPage>> query(
            @RequestParam(name = "tenantId", required = false) Long tenantId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "apiKeyId", required = false) Long apiKeyId,
            @RequestParam(name = "channelId", required = false) Long channelId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        long tenant = requireTenantId(tenantId);
        Instant start = requireInstant(from, "from");
        Instant end = requireInstant(to, "to");
        requireOrderedRange(start, end);
        int effectivePage = requirePage(page);
        int effectiveSize = requireSize(size);

        Page<RequestLogQueryService.RequestLogView> result =
                requestLogQueryService.page(tenant, start, end, apiKeyId, channelId, effectivePage, effectiveSize);
        return ResponseEntity.ok(ApiResponse.ok(
                new LogPage(result.getTotal(), effectivePage, effectiveSize, result.getRecords())));
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
