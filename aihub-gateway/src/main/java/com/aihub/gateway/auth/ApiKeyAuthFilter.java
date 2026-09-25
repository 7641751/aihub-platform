package com.aihub.gateway.auth;

import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.error.GatewayErrors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * API Key 鉴权。只守 {@code /v1/**}；{@code /healthz} 与未来其它运维端点不设防（健康检查由网关/容器发起，
 * 不会带密钥）。
 * <p>通过后把解析结果写进 exchange 属性，供 M2 的计量与 M3 的配额复用。
 * <p>失败一律 401 + OpenAI 错误体（<b>不是</b> admin 的 {@code {code,message,data}} 信封）：数据面客户端
 * 是各种 OpenAI SDK，它们只认 {@code error.message}。密钥格式不对也按 401 处理 —— 畸形请求头不是
 * 参数校验问题，不该回 400 让客户端以为换个 body 就能通过。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ApiKeyAuthFilter implements WebFilter {

    /** 解析成功的 {@link ApiKeyView} 存放位置（M2 计量、M3 配额从这里取）。 */
    public static final String ATTRIBUTE_KEY_VIEW = "aihub.apiKeyView";

    /**
     * 守备范围。**必须**用 {@link PathPattern}，不能用 {@code path.startsWith("/v1/")} 这种字符串前缀：
     * {@code getPath().value()} 是请求行里的**原始（未解码）**路径，而 handler mapping 按**解码后**的
     * 路径匹配、并且会剥掉 path parameter。两者不一致就产生绕过 ——
     * {@code POST /%761/chat/completions}（解码后即 {@code /v1/chat/completions}）与
     * {@code POST /v1;x=/chat/completions} 都不满足字符串前缀，过滤器直接放行，却照样命中
     * {@code ChatRelayController}，等于无密钥直达上游。这里改成与 handler mapping 同一套匹配
     * （{@code RequestPath#pathWithinApplication()} + {@link PathPattern}）。
     * <p>admin 侧 Task 3 的 {@code InternalAuthFilter} 用 {@code UrlPathHelper.getPathWithinApplication}
     * 解决的是同一类「原始路径 vs 应用内解码路径」问题，WebFlux 的对应物就是这里这两行。
     */
    private static final PathPattern GUARDED_PATH = new PathPatternParser().parse("/v1/**");

    /** RFC 7235 规定 auth-scheme 大小写不敏感：{@code Bearer}/{@code bearer}/{@code BEARER} 等价。 */
    private static final String BEARER_SCHEME = "bearer";

    private static final String MISSING_KEY_MESSAGE =
            "缺少 API Key：请在 Authorization 头里带 Bearer <key_id>.<secret>";
    private static final String INVALID_KEY_MESSAGE = "API Key 无效、已过期或已停用";

    /**
     * {@link ApiKeyResolver} 的契约是「永不返回空 Mono」。万一契约被打破（返回了空 Mono），用这个
     * 不可用哨兵兜底，走与「key 无效」**完全相同**的 401 —— 客户端绝不会碰到「没有状态码就挂住」。
     */
    private static final ApiKeyView UNRESOLVED = new ApiKeyView("", 0L, "", "MISSING", null);

    private final AuthProperties properties;
    private final ApiKeyResolver resolver;

    public ApiKeyAuthFilter(AuthProperties properties, ApiKeyResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.enabled()
                || !GUARDED_PATH.matches(exchange.getRequest().getPath().pathWithinApplication())) {
            return chain.filter(exchange);
        }

        String secret = extractSecret(exchange.getRequest().getHeaders().getFirst("Authorization"));
        if (secret == null) {
            return unauthorized(exchange, MISSING_KEY_MESSAGE);
        }

        // 哈希实现只有一份：com.aihub.common.apikey.ApiKeyHasher（admin 铸造端用的也是它）。
        return resolver.resolve(ApiKeyHasher.hash(secret))
                .switchIfEmpty(Mono.just(UNRESOLVED))
                .flatMap(view -> {
                    if (!view.usable()) {
                        return unauthorized(exchange, INVALID_KEY_MESSAGE);
                    }
                    exchange.getAttributes().put(ATTRIBUTE_KEY_VIEW, view);
                    return chain.filter(exchange);
                });
    }

    /** 客户端携带 {@code <key_id>.<secret>}；只有 secret 部分参与哈希（与铸造端一致）。 */
    private String extractSecret(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String header = authorizationHeader.strip();
        int schemeEnd = header.indexOf(' ');
        if (schemeEnd <= 0 || !BEARER_SCHEME.equalsIgnoreCase(header.substring(0, schemeEnd))) {
            return null;
        }
        String token = header.substring(schemeEnd + 1).strip();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        return token.substring(dot + 1);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        return GatewayErrors.write(exchange.getResponse(), HttpStatus.UNAUTHORIZED,
                "invalid_request_error", "invalid_api_key", message);
    }
}
