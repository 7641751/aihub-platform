package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.service.apikey.ApiKeyAdminService;
import com.aihub.service.audit.AuditService;
import com.aihub.service.console.ConsoleClaims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API Key 控制面接口（{@code /api/api-keys}，D11）：由 {@link ConsoleAuthFilter} 守门
 * （ADMIN 可读写、VIEWER 只读），本类只做「HTTP DTO ↔ 服务层入参」的转换与 {@link ApiResponse} 包装。
 *
 * <p><b>响应契约是 admin 信封</b>（{@code {"code","message","data"}}），**不是** gateway {@code /v1}
 * 的 OpenAI 兼容体。
 *
 * <p><b>明文只在一个响应里出现</b>：{@code POST /api/api-keys} 的 {@code data.plaintextKey}。
 * 列表返回的 {@link ApiKeyAdminService.ApiKeySummary} 连 {@code keyHash} 字段都没有，本类也不记任何日志。
 *
 * <p><b>列表的租户维度来自令牌 claims</b>（不是查询参数）：控制台主体只可能看到自己租户的 key，
 * 无法用一个查询参数跨租户读。计划只定死了 {@code ApiKeyAdminService.list(long tenantId)}，来源由本类
 * 决定 —— 这是最小惊讶且 fail-closed 的那个选择。
 *
 * <p><b>错误一律走 {@link BizException} → {@code GlobalExceptionHandler}**
 * （{@code INVALID_PARAM} 400 / {@code NOT_FOUND} 404 / {@code UNAUTHORIZED} 401），本类不写自定义错误体。
 */
@RestController
@RequestMapping("/api/api-keys")
public class ApiKeyController {

    private final ApiKeyAdminService apiKeyAdminService;

    public ApiKeyController(ApiKeyAdminService apiKeyAdminService) {
        this.apiKeyAdminService = apiKeyAdminService;
    }

    /**
     * 签发请求体。{@code validDays} 缺省或非正数 = 永不过期
     * （与 {@code ApiKeyMintRunner} 同语义）。
     */
    public record ApiKeyRequest(long tenantId, String name, Integer validDays) {
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ApiKeyAdminService.ApiKeyCreated>> create(
            @RequestBody(required = false) ApiKeyRequest request, HttpServletRequest http) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "请求体不能为空");
        }
        ApiKeyAdminService.ApiKeyCreateRequest write = new ApiKeyAdminService.ApiKeyCreateRequest(
                request.tenantId(), request.name(), request.validDays());
        return ResponseEntity.ok(ApiResponse.ok(apiKeyAdminService.create(write, actor(http))));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ApiKeyAdminService.ApiKeySummary>>> list(HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(apiKeyAdminService.list(claims(http).tenantId())));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<Void>> disable(@PathVariable long id, HttpServletRequest http) {
        apiKeyAdminService.disable(id, actor(http));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<Void>> enable(@PathVariable long id, HttpServletRequest http) {
        apiKeyAdminService.enable(id, actor(http));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id, HttpServletRequest http) {
        apiKeyAdminService.delete(id, actor(http));
        return ResponseEntity.ok(ApiResponse.ok(null));
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

    private static AuditService.Actor actor(HttpServletRequest request) {
        return new AuditService.Actor("USER", String.valueOf(claims(request).userId()));
    }
}
