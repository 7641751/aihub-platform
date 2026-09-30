package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.service.audit.AuditService;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.route.ModelRouteAdminService;
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
 * 模型路由控制面接口（{@code /api/routes}，Task 10）：由 {@link ConsoleAuthFilter} 守门
 * （ADMIN 可读写、VIEWER 只读），本类只做「HTTP DTO ↔ 服务层入参」的转换与 {@link ApiResponse} 包装。
 *
 * <p><b>响应契约是 admin 信封</b>（{@code {"code","message","data"}}），**不是** gateway {@code /v1}
 * 的 OpenAI 兼容体 —— 两套契约不许混用。
 *
 * <p><b>{@code model_route} 是全局资源（R1）</b>：列表返回全部行，不按令牌租户过滤
 * （表里也没有 {@code tenantId} 维度可过滤）。
 *
 * <p><b>错误一律走 {@link BizException} → {@code GlobalExceptionHandler}**
 * （{@code INVALID_PARAM} 400 / {@code NOT_FOUND} 404），本类不写任何自定义错误体。
 */
@RestController
@RequestMapping("/api/routes")
public class ModelRouteController {

    private final ModelRouteAdminService modelRouteAdminService;

    public ModelRouteController(ModelRouteAdminService modelRouteAdminService) {
        this.modelRouteAdminService = modelRouteAdminService;
    }

    /** 路由请求体。更新语义下缺省字段表示「不修改」。 */
    public record RouteRequest(String modelName, Long channelId, Integer weight, Integer priority, String status) {
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ModelRouteAdminService.RouteSummary>> create(
            @RequestBody(required = false) RouteRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(modelRouteAdminService.create(write(request), actor(http))));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ModelRouteAdminService.RouteSummary>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(modelRouteAdminService.list()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ModelRouteAdminService.RouteSummary>> update(
            @PathVariable long id, @RequestBody(required = false) RouteRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(modelRouteAdminService.update(id, write(request), actor(http))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id, HttpServletRequest http) {
        modelRouteAdminService.delete(id, actor(http));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private static ModelRouteAdminService.RouteWrite write(RouteRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "请求体不能为空");
        }
        return new ModelRouteAdminService.RouteWrite(request.modelName(), request.channelId(),
                request.weight(), request.priority(), request.status());
    }

    /**
     * 从请求属性取过滤器验过的 claims 并折成审计主体。
     *
     * <p>正常路径下 {@code ConsoleAuthFilter} 已经拦掉了无令牌请求，所以 {@code null} 只可能发生在
     * 「控制器被注册、但过滤器没跑」这种装配错误里 —— 宁可回 401，也不返回一份看起来通过鉴权的响应。
     */
    private static AuditService.Actor actor(HttpServletRequest request) {
        Object attribute = request.getAttribute(ConsoleAuthFilter.ATTRIBUTE_CLAIMS);
        if (!(attribute instanceof ConsoleClaims claims)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少控制台令牌");
        }
        return new AuditService.Actor("USER", String.valueOf(claims.userId()));
    }
}
