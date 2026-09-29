package com.aihub.admin.web.console;

import com.aihub.admin.web.config.ConsoleProperties;
import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * 守卫 {@code /api/**}（D3 的令牌 + D10 的两级角色）。
 *
 * <p><b>与既有 {@code InternalAuthFilter} 同一手法</b>：自己解析、自己写响应、**不引 Spring Security**
 * （{@code spring-boot-starter-security} 会带来第二条全局过滤器链与 auto-config，且会改变
 * {@code /internal/**}、{@code /healthz} 的既有行为 —— 见 D2）。路由由 Spring MVC 决定，
 * 授权只在这里发生一次。
 *
 * <p><b>路径判定用 {@code UrlPathHelper.getPathWithinApplication}</b>（与 {@code InternalAuthFilter}
 * 完全一致）：不能用原始 {@code getRequestURI()}，它带 {@code server.servlet.context-path} 且未解码，
 * 于是在 {@code /admin} 这类部署下"以 /api/ 开头"的判断会落空、过滤器被静默跳过而控制器照样对外服务
 * （fail-open）—— 那个 bug 在本仓库已经真实发生过一次，并有 {@code InternalAuthFilterContextPathTest} 钉着。
 *
 * <p><b>三条不变量</b>（各自的用例在 {@code ConsoleAuthFilterTest}）：
 * <ul>
 *   <li><b>密钥不可用 ⇒ 门关着</b>（D16）：空/短于 32 字符时 {@code /api/**} 一律 401，
 *       **绝不放行**。这里**不**回 500 —— 告诉运维"真正原因是配置"是**登录接口**的职责
 *       （它回 {@code CONFIGURATION_ERROR}），门本身只需要保证关着。登录接口因此从本过滤器的
 *       判定范围里排除（{@link #shouldNotFilter}），否则没人能取到第一张令牌。</li>
 *   <li><b>令牌层失败 ⇒ 401</b>，且必须是 admin 信封（{@code {"code","message","data"}}），
 *       不是 Spring 的默认错误体（{@code /api/**} 的响应契约与 {@code /internal/**} 同源）。</li>
 *   <li><b>角色只认 {@code ADMIN}/{@code VIEWER}</b>（D10）：{@code ADMIN} 可读写；
 *       {@code VIEWER} 只允许 GET/HEAD/OPTIONS；**其余角色一律 403，连读也不放行**
 *       （{@code ConsoleToken.verify} 不校验 role，所以 fail-closed 必须在这里落地）。</li>
 * </ul>
 *
 * <p><b>为什么配置是从 {@code @Value} 来、而不是注入 {@code ConsoleProperties}/{@code ConsoleTokenService}
 * bean</b>：{@code jakarta.servlet.Filter} 在 {@code @WebMvcTest} 的默认包含清单里，切片上下文里没有
 * {@code @Service}/{@code @Component} —— 注入它们的过滤器会让既有的 {@code GlobalExceptionHandlerTest}
 * 整个变红。详见 {@code ConsoleProperties} 的类注释。代价是过滤器自己持有一份无状态的
 * {@code ConsoleTokenService} 实例（它只有两个 final 字段），与容器里那个 bean 读的是同一组属性键。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class ConsoleAuthFilter extends OncePerRequestFilter {

    /** 通过鉴权的控制器从请求属性里取 claims 的键（{@code ConsoleAuthController#ping} 在用）。 */
    public static final String ATTRIBUTE_CLAIMS = "aihub.consoleClaims";

    private static final Logger log = LoggerFactory.getLogger(ConsoleAuthFilter.class);

    /** 信封序列化器：本类写得不多，但一定要和 {@code ApiResponse} 的字段顺序一致。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final PathPattern GUARDED = new PathPatternParser().parse("/api/**");

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * 登录接口自己**必须**无令牌可达：它是唯一的签发入口，守起来就没人是能拿到令牌的鸡生蛋问题。
     * 它自己的 401（口令错）/ 500（配置故障）由控制器与服务层决定，本过滤器一个字都不插嘴。
     */
    private static final String LOGIN_PATH = "/api/auth/login";

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final String NOT_CONFIGURED_MESSAGE =
            "控制台未配置（AIHUB_CONSOLE_SECRET 为空或短于 32 字符）";

    private final ConsoleProperties properties;
    private final ConsoleTokenService tokenService;

    public ConsoleAuthFilter(@Value("${aihub.console.secret:}") String secret,
                             @Value("${aihub.console.token-ttl:2h}") Duration tokenTtl) {
        this.properties = new ConsoleProperties(secret, tokenTtl);
        this.tokenService = new ConsoleTokenService(secret, tokenTtl);
    }

    /**
     * 启动时把"配置没配好"大声说出来（D16）：密钥为空/过短时管理台等于没有鉴权，而失败现象是
     * "登录 500 / 所有 /api/** 401"—— 不打日志的话运维只能靠猜。选择告警而不是启动失败，
     * 与 {@code InternalAuthFilter} 同款：{@code application.yml} 的默认值就是空，
     * fail-fast 会让没配密钥的本地启动直接起不来。
     *
     * <p>日志里**只打属性名与长度类别**，绝不打密钥本身（哪怕它看起来是空的）。
     */
    @PostConstruct
    void warnIfMisconfigured() {
        if (!properties.secretUsable()) {
            log.warn("""
                    控制台签名密钥未配置或过短（aihub.console.secret / AIHUB_CONSOLE_SECRET）：
                    所有 /api/** 请求一律 401（fail-closed），登录接口回 500 CONFIGURATION_ERROR。
                    密钥至少需要 {} 个字符（当前长度为 {}）；生成方法见 .env.example。""",
                    ConsoleTokenService.MIN_SECRET_LENGTH, properties.secret().length());
        }
        if (properties.tokenTtlMisconfigured()) {
            log.warn("aihub.console.token-ttl={} 不合理（非正数或超过 2 小时封顶）：签发端按 {} 处理",
                    properties.tokenTtl(), ConsoleTokenService.MAX_TOKEN_TTL);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = applicationPath(request);
        if (!GUARDED.matches(PathContainer.parsePath(path))) {
            return true;
        }
        return LOGIN_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.secretUsable()) {
            // D16：平台配置故障**不伪装**成凭证错误 —— 但门必须是关着的，所以仍然 401（fail-closed），
            // 只有登录接口会回 500 CONFIGURATION_ERROR 告诉运维真正的原因。
            writeError(response, ErrorCode.UNAUTHORIZED, NOT_CONFIGURED_MESSAGE);
            return;
        }
        String header = request.getHeader(AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            writeError(response, ErrorCode.UNAUTHORIZED, "缺少控制台令牌");
            return;
        }
        ConsoleClaims claims;
        try {
            claims = tokenService.verify(header.substring(BEARER_PREFIX.length()).strip());
        } catch (IllegalArgumentException e) {
            writeError(response, ErrorCode.UNAUTHORIZED, "控制台令牌无效或已过期");
            return;
        } catch (IllegalStateException e) {
            // 令牌层不会给 ISE（密钥问题上面已经判过），这一支是兜底：任何"平台侧"的意外都不许变成放行，
            // 也不许漏成过滤器外的 500（/api/** 的失败形状只有 {401,403} 两种）。
            writeError(response, ErrorCode.UNAUTHORIZED, NOT_CONFIGURED_MESSAGE);
            return;
        }
        String role = claims.role();
        if (!ConsoleClaims.ROLE_ADMIN.equals(role) && !ConsoleClaims.ROLE_VIEWER.equals(role)) {
            // fail-closed（D10）：未知角色连**读**都不放行。verify 不校验角色，所以这里是唯一的一道门。
            writeError(response, ErrorCode.FORBIDDEN, "未知的控制台角色（只认 ADMIN/VIEWER）");
            return;
        }
        if (!READ_METHODS.contains(request.getMethod()) && !ConsoleClaims.ROLE_ADMIN.equals(role)) {
            writeError(response, ErrorCode.FORBIDDEN, "只读角色不能执行写操作");
            return;
        }
        request.setAttribute(ATTRIBUTE_CLAIMS, claims);
        chain.doFilter(request, response);
    }

    /**
     * 应用内路径（context path 已去掉、URI 已解码、{@code ;jsessionid} 已清理），与 MVC 匹配 handler
     * 用的是同一个来源 —— 这正是"守卫不会被 context path 绕过"的前提（与 {@code InternalAuthFilter} 一致）。
     */
    private static String applicationPath(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return path == null ? "" : path;
    }

    /** 失败响应必须是 admin 信封（{@code code/message/data}），字段顺序与 {@code ApiResponse} 一致。 */
    private static void writeError(HttpServletResponse response, ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write(MAPPER.writeValueAsString(ApiResponse.fail(errorCode, message)));
        } catch (JsonProcessingException e) {
            // 信封只有两个 String + 一个 null，这条路径今天不可达；保留它是因为抛出 JsonProcessingException
            // 会变成过滤器外的 500，而这里的契约只有 401/403。
            response.getWriter().write("{\"code\":\"" + errorCode.code() + "\",\"message\":\"error\",\"data\":null}");
        }
    }
}
