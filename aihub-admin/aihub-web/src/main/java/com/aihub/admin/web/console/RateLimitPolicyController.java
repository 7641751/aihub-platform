package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.service.audit.AuditService;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.ratelimit.RateLimitPolicyAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 限流策略控制面接口（{@code /api/rate-limits}，Task 10）：由 {@link ConsoleAuthFilter} 守门
 * （ADMIN 可读写、VIEWER 只读），本类只做「HTTP DTO ↔ 服务层入参」的转换与 {@link ApiResponse} 包装。
 *
 * <p><b>响应契约是 admin 信封</b>（{@code {"code","message","data"}}）。
 *
 * <p><b>{@code rate_limit_policy} 是租户维度资源（R2/R3.2）</b>：写是平台级（{@code tenantId} 来自请求体），
 * 但列表缺省只回**令牌里的 {@code tenantId}**（最小权限）—— 本类因此从 claims 取租户，而不是接受查询参数。
 *
 * <p><b>{@code POST} = upsert</b>（先停用同维度旧 ACTIVE 行、再插新 ACTIVE 行）；
 * <b>{@code DELETE} = 软停用</b>（置 {@code INACTIVE}、不删行）。
 *
 * <p><b>错误一律走 {@link BizException} → {@code GlobalExceptionHandler}**
 * （{@code INVALID_PARAM} 400 / {@code NOT_FOUND} 404），本类不写任何自定义错误体。
 */
@RestController
@RequestMapping("/api/rate-limits")
public class RateLimitPolicyController {

    private final RateLimitPolicyAdminService rateLimitPolicyAdminService;

    public RateLimitPolicyController(RateLimitPolicyAdminService rateLimitPolicyAdminService) {
        this.rateLimitPolicyAdminService = rateLimitPolicyAdminService;
    }

    /** upsert 请求体。{@code apiKeyId} 缺省 = 租户级维度。 */
    public record PolicyRequest(Long tenantId, Long apiKeyId, Integer qps, Integer burst) {
    }

    /** PUT 请求体：只改 {@code qps}/{@code burst}。 */
    public record PolicyUpdateRequest(Integer qps, Integer burst) {
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RateLimitPolicyAdminService.PolicySummary>> upsert(
            @RequestBody(required = false) PolicyRequest request, HttpServletRequest http) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "请求体不能为空");
        }
        RateLimitPolicyAdminService.PolicyWrite write = new RateLimitPolicyAdminService.PolicyWrite(
                request.tenantId(), request.apiKeyId(), request.qps(), request.burst());
        return ResponseEntity.ok(ApiResponse.ok(rateLimitPolicyAdminService.upsert(write, actor(http))));
    }

    /** R3.2：缺省 = 令牌里的 {@code tenantId}。 */
    @GetMapping
    public ResponseEntity<ApiResponse<List<RateLimitPolicyAdminService.PolicySummary>>> list(HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(rateLimitPolicyAdminService.list(claims(http).tenantId())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RateLimitPolicyAdminService.PolicySummary>> update(
            @PathVariable long id, @RequestBody(required = false) PolicyUpdateRequest request,
            HttpServletRequest http) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "请求体不能为空");
        }
        RateLimitPolicyAdminService.PolicyUpdate update =
                new RateLimitPolicyAdminService.PolicyUpdate(request.qps(), request.burst());
        return ResponseEntity.ok(ApiResponse.ok(rateLimitPolicyAdminService.update(id, update, actor(http))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id, HttpServletRequest http) {
        rateLimitPolicyAdminService.deactivate(id, actor(http));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * 从请求属性取过滤器验过的 claims（列表需要它的租户；写需要操作者）。
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
