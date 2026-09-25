package com.aihub.admin.web.internal;

import com.aihub.common.internal.InternalHmac;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Instant;

/**
 * 守卫 /internal/**：缺签名或不合法一律 401。该前缀永远不应该暴露到公网。
 *
 * <p><b>路径判定与签名都用 {@code getPathWithinApplication} 的结果，而不是原始的 {@code getRequestURI()}。</b>
 * 原因：{@code getRequestURI()} 带 {@code server.servlet.context-path}（且未解码），而 Spring MVC 的
 * handler mapping 用的是「去掉 context path 并解码后的应用内路径」。例如部署时加上
 * {@code server.servlet.context-path=/admin}，URI 变成 {@code /admin/internal/api-keys/resolve}，
 * 用原始 URI 做「以 /internal/ 开头」判断就会落空 → 过滤器被跳过 → 控制器照样映射并对外服务，
 * 签名校验被静默移除（fail-open）。这里改用 {@link UrlPathHelper#defaultInstance}
 * —— MVC 匹配用的同一个工具 —— 于是守卫与 context path、与 URI 编码都无关。
 *
 * <p>这同时定义了签名契约：被签名的 path 是**应用内路径** {@code /internal/api-keys/resolve}，
 * 不含 context path。gateway 侧对「base-url 之后追加的那段路径」签名即可。
 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);

    /** 内部接口的应用内前缀。 */
    private static final String INTERNAL_PREFIX = "/internal/";

    private static final long MAX_SKEW_SECONDS = 300;

    private final String secret;

    public InternalAuthFilter(@Value("${aihub.internal.secret:}") String secret) {
        this.secret = secret;
    }

    /**
     * 密钥为空时每个内部调用都会 401，而失败原因只出现在网关侧的响应里 —— 在启动阶段大声报出来。
     * 选择「大声告警」而不是「启动失败」：{@code application.yml} 里该属性的默认值就是空，
     * fail-fast 会让没配密钥的本地/单机启动直接起不来；告警保留可启动性，但不留静默。
     */
    @PostConstruct
    void warnIfSecretIsBlank() {
        if (secret == null || secret.isBlank()) {
            log.error("""

                    ==================== 内部接口签名密钥未配置 ====================
                    属性 aihub.internal.secret（环境变量 AIHUB_INTERNAL_SECRET）为空。
                    所有 /internal/** 调用都会返回 401 UNAUTHORIZED，直到配置该密钥。
                    ==============================================================
                    """);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !applicationPath(request).startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String timestamp = request.getHeader("X-Internal-Timestamp");
        String signature = request.getHeader("X-Internal-Signature");
        if (secret == null || secret.isBlank() || !fresh(timestamp)
                || !InternalHmac.verify(secret, timestamp, request.getMethod(), applicationPath(request), signature)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"invalid internal signature\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 应用内路径（context path 已去掉、URI 已解码、{@code ;jsessionid} 已清理），
     * 与 MVC 用来匹配 handler 的路径是同一个来源 —— 这正是本类不再被 context path 绕过的前提。
     */
    private static String applicationPath(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return path == null ? "" : path;
    }

    private boolean fresh(String timestamp) {
        try {
            long skew = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
            return skew <= MAX_SKEW_SECONDS;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
