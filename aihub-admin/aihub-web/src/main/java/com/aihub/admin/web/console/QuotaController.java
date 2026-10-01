package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.service.audit.AuditService;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.quota.QuotaAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * 配额控制面接口（{@code /api/quotas}，Task 12）：由 {@link ConsoleAuthFilter} 守门
 * （ADMIN 可读写、VIEWER 只读 —— 注意 {@code GET} 在本路径上会**惰性物化**一行零额度行，见
 * {@link QuotaAdminService#getOrCreate} 的类注释）。本类只做「HTTP DTO ↔ 服务层入参」的转换与
 * {@link ApiResponse} 包装。
 *
 * <p>响应契约是 **admin 信封**（{@code {"code","message","data"}}）。
 *
 * <p><b>租户语义（{@code docs/CONVENTIONS.md} §10）</b>：
 * <ul>
 *   <li><b>GET = R3.2（查）</b>：{@code tenantId} **只**取令牌里的（least privilege）—— 今天**不**接受
 *       显式覆盖（跨租户列举登记为待办），因此查询参数里没有 {@code tenantId}；</li>
 *   <li><b>PUT = R2（写）</b>：写是平台级，{@code tenantId} 来自请求体（运营必须能对任一租户设额度）。</li>
 * </ul>
 *
 * <p>错误一律走 {@link BizException} → {@code GlobalExceptionHandler}
 * （{@code INVALID_PARAM} 400），本类不写任何自定义错误体。
 */
@RestController
@RequestMapping("/api/quotas")
public class QuotaController {

    private final QuotaAdminService quotaAdminService;
    private final Clock clock;

    /** Spring 注入用的构造器：时钟默认 {@code Clock.systemUTC()}（与 {@code QuotaAdminService} 同款）。 */
    @Autowired
    public QuotaController(QuotaAdminService quotaAdminService) {
        this(quotaAdminService, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造器（{@code period} 缺省时用它折算当前周期）。
     *
     * <p><b>为什么用 {@link Clock} 而不是 {@code System.currentTimeMillis()}</b>：CONVENTIONS §7
     * 要求时间基准**写在代码里**、可被判据替代 —— 与 {@code QuotaAdminService} / {@code AuditService}
     * 保持同一条纪律（缺省 {@code Clock.systemUTC()} 不依赖 JVM 默认时区，也便于用例钉固定瞬时）。
     */
    public QuotaController(QuotaAdminService quotaAdminService, Clock clock) {
        this.quotaAdminService = quotaAdminService;
        this.clock = clock;
    }

    /** PUT 请求体：{@code tenantId} 必填（平台级写）；{@code period} 缺省 = 当前 UTC 周期。 */
    public record QuotaUpdateRequest(Long tenantId, String period, Long tokenLimit, Long requestLimit) {
    }

    /** R3.2：缺省租户 = 令牌里的 {@code tenantId}；{@code period} 缺省 = 当前 UTC 周期。 */
    @GetMapping
    public ResponseEntity<ApiResponse<QuotaAdminService.QuotaSummary>> get(
            @RequestParam(required = false) String period, HttpServletRequest http) {
        ConsoleClaims claims = claims(http);
        return ResponseEntity.ok(ApiResponse.ok(
                quotaAdminService.getOrCreate(claims.tenantId(), resolvePeriod(period))));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<QuotaAdminService.QuotaSummary>> update(
            @RequestBody(required = false) QuotaUpdateRequest request, HttpServletRequest http) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "请求体不能为空");
        }
        if (request.tenantId() == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "tenantId 不能为空");
        }
        if (request.tokenLimit() == null || request.requestLimit() == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "tokenLimit 与 requestLimit 都不能为空（0 = 不限）");
        }
        return ResponseEntity.ok(ApiResponse.ok(quotaAdminService.update(
                request.tenantId(), resolvePeriod(request.period()),
                request.tokenLimit(), request.requestLimit(), actor(http))));
    }

    /** {@code period} 缺省 = 当前 UTC 周期（按注入的 {@link #clock} 折算）；显式传入时由服务层校验其合法性。 */
    private String resolvePeriod(String period) {
        return (period == null || period.isBlank()) ? QuotaPeriod.of(clock.millis()) : period;
    }

    /**
     * 从请求属性取过滤器验过的 claims（读路径需要它的租户；写路径需要操作者）。
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

    private static AuditService.Actor actor(HttpServletRequest request) {
        return new AuditService.Actor("USER", String.valueOf(claims(request).userId()));
    }
}
