package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.dao.entity.BillingDailyEntity;
import com.aihub.dao.mapper.BillingDailyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 每日账单的运营查询（{@code GET /api/billing/daily}，Task 11）：由 {@link ConsoleAuthFilter} 守门（只读）。
 *
 * <p><b>必须带 {@code tenantId}</b>（控制器裁定 2）：{@code billing_daily} 有 {@code tenant_id NOT NULL}，
 * 且 {@code /api/billing/daily} 在 {@code docs/CONVENTIONS.md} §10 **R3.1** 里明文归为**运营查询** ⇒
 * 必须显式 {@code tenantId}（缺省 400），与 {@code /api/logs}、{@code /api/audit} 语义统一。
 *
 * <p><b>时间边界用 {@code stat_date}（DATE）而不是 {@code datetime(3)}</b>：账单是**自然日**粒度，
 * 因此把带 {@code Z} 的绝对时刻折成 **UTC 自然日**（{@code LocalDate.ofInstant(instant, ZoneOffset.UTC)}）
 * 去比 —— 这里不涉及 CONVENTIONS §7 的 {@code datetime(3)} 时区陷阱（{@code stat_date} 不是瞬时列）。
 *
 * <p><b>为什么查询逻辑直接在本控制器里、而不是一个 {@code BillingQueryService}</b>：Task 11 的文件集是
 * **封闭**的 —— 计划的 Files 与 Step 5 的 {@code git add} 清单里**没有**账单服务（只有
 * {@code BillingDailyEntity}/{@code BillingDailyMapper}/{@code BillingController}）。新建一个未被声明的
 * 文件会让「只 stage 显式路径」失去可核对性，因此这里把这条**极薄**的只读查询放在控制器里，并把
 * 各控制器共用的参数校验助手一并内联（理由详见 {@link LogQueryController} 的类注释）。这是对
 * 「控制器只做 DTO ↔ 服务转换」的一处**已登记偏差**（交付报告「偏差与发现」）。
 *
 * <p>错误一律走 {@link BizException} → {@code GlobalExceptionHandler}。
 */
@RestController
@RequestMapping("/api/billing/daily")
public class BillingController {

    private final BillingDailyMapper billingDailyMapper;

    public BillingController(BillingDailyMapper billingDailyMapper) {
        this.billingDailyMapper = billingDailyMapper;
    }

    /** 账单视图：只回计量列，**不含**任何密钥材料（该表本来也没有）。 */
    public record BillingDailyView(Long id, Long tenantId, LocalDate statDate, Long requests, Long tokens,
                                   BigDecimal cost) {
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BillingDailyView>>> query(
            @RequestParam(name = "tenantId", required = false) Long tenantId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        long tenant = requireTenantId(tenantId);
        Instant start = requireInstant(from, "from");
        Instant end = requireInstant(to, "to");
        requireOrderedRange(start, end);

        LocalDate startDate = LocalDate.ofInstant(start, ZoneOffset.UTC);
        LocalDate endDate = LocalDate.ofInstant(end, ZoneOffset.UTC);
        List<BillingDailyView> rows = billingDailyMapper.selectList(new LambdaQueryWrapper<BillingDailyEntity>()
                        .eq(BillingDailyEntity::getTenantId, tenant)
                        .ge(BillingDailyEntity::getStatDate, startDate)
                        .le(BillingDailyEntity::getStatDate, endDate)
                        .orderByAsc(BillingDailyEntity::getStatDate)).stream()
                .map(entity -> new BillingDailyView(entity.getId(), entity.getTenantId(), entity.getStatDate(),
                        entity.getRequests(), entity.getTokens(), entity.getCost()))
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(rows));
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
}
