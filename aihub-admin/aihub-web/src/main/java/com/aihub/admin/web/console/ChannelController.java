package com.aihub.admin.web.console;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.service.audit.AuditService;
import com.aihub.service.channel.ChannelAdminService;
import com.aihub.service.config.ChannelProbeService;
import com.aihub.service.console.ConsoleClaims;
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
 * 渠道控制面接口（{@code /api/channels}，D10/D14）：由 {@link ConsoleAuthFilter} 守门
 * （ADMIN 可读写、VIEWER 只读），本类只做「HTTP DTO ↔ 服务层入参」的转换与
 * {@link ApiResponse} 包装。
 *
 * <p><b>响应契约是 admin 信封</b>（{@code {"code","message","data"}}），**不是** gateway {@code /v1}
 * 的 OpenAI 兼容体 —— 两套契约不许混用。
 *
 * <p><b>明文密钥只在请求体里出现一次</b>：{@code apiKey} 被原样交给服务层，本类不记日志、
 * 不放进任何返回体；返回的 {@link ChannelAdminService.View} 连 {@code apiKeyCipher} 字段都没有。
 *
 * <p><b>错误一律走 {@link BizException} → {@code GlobalExceptionHandler}</b>
 * （{@code INVALID_PARAM} 400 / {@code NOT_FOUND} 404 / {@code CONFIGURATION_ERROR} 500），
 * 本类不写任何自定义错误体。
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelController {

    private final ChannelAdminService channelAdminService;
    private final ChannelProbeService channelProbeService;

    public ChannelController(ChannelAdminService channelAdminService, ChannelProbeService channelProbeService) {
        this.channelAdminService = channelAdminService;
        this.channelProbeService = channelProbeService;
    }

    /** 渠道请求体。更新语义下缺省字段表示「不修改」。 */
    public record ChannelRequest(String name, String provider, String baseUrl, String apiKey, String modelsJson,
                                 Integer weight, Integer priority, Integer timeoutMs, String status) {
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChannelAdminService.View>> create(
            @RequestBody(required = false) ChannelRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(channelAdminService.create(write(request), actor(http))));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ChannelAdminService.View>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(channelAdminService.list()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ChannelAdminService.View>> get(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(channelAdminService.get(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ChannelAdminService.View>> update(
            @PathVariable long id, @RequestBody(required = false) ChannelRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(channelAdminService.update(id, write(request), actor(http))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id, HttpServletRequest http) {
        channelAdminService.delete(id, actor(http));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/rotate-key")
    public ResponseEntity<ApiResponse<ChannelAdminService.View>> rotateKey(
            @PathVariable long id, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(channelAdminService.rotateKey(id, actor(http))));
    }

    /**
     * 渠道探测（Task 11）：用渠道自己的 {@code baseUrl} + 密钥向上游打**一次**请求并回报可达性。
     *
     * <p>它是**诊断动作**而不是数据面调用 ⇒ **不做写审计**、**不检查渠道是否停用**（停用仍可探测）；
     * 渠道不存在时服务层抛 {@code NOT_FOUND}（404）。响应是 {@link ChannelProbeService.ProbeResult}，
     * **绝不回显密钥**（{@code ChannelProbeService} 的类注释）。
     */
    @PostMapping("/{id}/probe")
    public ResponseEntity<ApiResponse<ChannelProbeService.ProbeResult>> probe(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(channelProbeService.probe(id)));
    }

    private static ChannelAdminService.Write write(ChannelRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "请求体不能为空");
        }
        return new ChannelAdminService.Write(request.name(), request.provider(), request.baseUrl(),
                request.apiKey(), request.modelsJson(), request.weight(), request.priority(),
                request.timeoutMs(), request.status());
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
