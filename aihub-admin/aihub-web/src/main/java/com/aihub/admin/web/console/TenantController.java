package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.service.audit.AuditService;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.tenant.TenantAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户控制面接口（{@code /api/tenants}，D10）：与 {@link ChannelController} 同一套纪律
 * （admin 信封、只做 DTO 转换、错误走 {@code BizException}）。
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantAdminService tenantAdminService;

    public TenantController(TenantAdminService tenantAdminService) {
        this.tenantAdminService = tenantAdminService;
    }

    /** 租户请求体。更新语义下缺省字段表示「不修改」。 */
    public record TenantRequest(String name, String status) {
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TenantAdminService.View>> create(
            @RequestBody(required = false) TenantRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(tenantAdminService.create(write(request), actor(http))));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TenantAdminService.View>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(tenantAdminService.list()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TenantAdminService.View>> update(
            @PathVariable long id, @RequestBody(required = false) TenantRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(tenantAdminService.update(id, write(request), actor(http))));
    }

    private static TenantAdminService.Write write(TenantRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "请求体不能为空");
        }
        return new TenantAdminService.Write(request.name(), request.status());
    }

    private static AuditService.Actor actor(HttpServletRequest request) {
        Object attribute = request.getAttribute(ConsoleAuthFilter.ATTRIBUTE_CLAIMS);
        if (!(attribute instanceof ConsoleClaims claims)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少控制台令牌");
        }
        return new AuditService.Actor("USER", String.valueOf(claims.userId()));
    }
}
