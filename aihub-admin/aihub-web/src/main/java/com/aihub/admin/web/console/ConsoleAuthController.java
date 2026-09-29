package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.service.console.ConsoleAuthService;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台的登录接口（{@code POST /api/auth/login}）与一个**读数探针**（{@code GET /api/ping}）。
 *
 * <p><b>为什么会有 {@code /api/ping}</b>：本任务要给"带令牌 200 / VIEWER 写操作 403"两条正向用例
 * 一个**真实存在**的靶子，而 {@code /api/**} 在 M4 的 Task 6 里只有登录一个映射（{@code /api/tenants}
 * 要等 Task 8）。它只回读 {@code ATTRIBUTE_CLAIMS} 里的三个字段，没有任何业务语义。
 *
 * <p><b>令牌在这里签发</b>（{@link ConsoleAuthService#login} 只产出载荷，
 * {@link ConsoleTokenService#issue} 才是签名）：两个关注点分开，口令校验的用例不需要碰令牌格式。
 *
 * <p>错误一律走 {@link BizException} → {@code GlobalExceptionHandler} → admin 信封
 * （{@code CONFIGURATION_ERROR} 500 / {@code UNAUTHORIZED} 401 / {@code INVALID_PARAM} 400），
 * 本类不写任何自定义错误体。
 */
@RestController
@RequestMapping("/api")
public class ConsoleAuthController {

    private final ConsoleAuthService authService;
    private final ConsoleTokenService tokenService;

    public ConsoleAuthController(ConsoleAuthService authService, ConsoleTokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    /** 登录请求体。字段缺失时由服务层按"凭证不对"处理（不区分，见 {@link ConsoleAuthService#login}）。 */
    public record LoginRequest(String username, String password) {
    }

    /**
     * 登录成功的响应体：令牌 + 角色 + 到期时刻。
     *
     * <p>{@code role} 与 {@code expiresAtEpochSecond} 是给管理台用的（前端只读它们，不再自己解令牌）：
     * 令牌是**不透明**的凭据，前端不该解析它（D9 的 vanilla JS 只把它放进 {@code sessionStorage}）。
     */
    public record LoginResponse(String token, String role, long expiresAtEpochSecond) {
    }

    /** 探针响应体：全部来自过滤器验过的 claims。 */
    public record PingResponse(long userId, long tenantId, String role) {
    }

    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody(required = false) LoginRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "请求体不能为空");
        }
        ConsoleClaims claims = authService.login(request.username(), request.password());
        return ResponseEntity.ok(ApiResponse.ok(
                new LoginResponse(tokenService.issue(claims), claims.role(), claims.expiresAtEpochSecond())));
    }

    /**
     * 鉴权探针：返回过滤器塞进请求属性的 claims。
     *
     * <p>{@code claims == null} 只可能发生在"本控制器被注册、但过滤器没跑"的装配错误里
     * （正常路径下 {@code ConsoleAuthFilter} 已经拦掉了无令牌请求）：宁可回 401，也不返回一份空 claims
     * —— 那会变成一个"看起来通过鉴权"的响应。
     */
    @GetMapping("/ping")
    public ResponseEntity<ApiResponse<PingResponse>> ping(HttpServletRequest request) {
        Object attribute = request.getAttribute(ConsoleAuthFilter.ATTRIBUTE_CLAIMS);
        if (!(attribute instanceof ConsoleClaims claims)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少控制台令牌");
        }
        return ResponseEntity.ok(ApiResponse.ok(
                new PingResponse(claims.userId(), claims.tenantId(), claims.role())));
    }
}
