package com.aihub.gateway.auth;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.error.GatewayErrors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String GUARDED_PREFIX = "/v1/";

    private final AuthProperties properties;
    private final ApiKeyResolver resolver;

    public ApiKeyAuthFilter(AuthProperties properties, ApiKeyResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!properties.enabled() || !path.startsWith(GUARDED_PREFIX)) {
            return chain.filter(exchange);
        }

        String secret = extractSecret(exchange.getRequest().getHeaders().getFirst("Authorization"));
        if (secret == null) {
            return unauthorized(exchange, "缺少 API Key：请在 Authorization 头里带 Bearer <key_id>.<secret>");
        }

        return resolver.resolve(sha256Hex(secret))
                .flatMap(view -> {
                    // 不需要判 null：ApiKeyResolver 的契约是「永不返回空 Mono」，未知 key 会拿到
                    // 一个 usable()==false 的哨兵值。
                    if (!view.usable()) {
                        return unauthorized(exchange, "API Key 无效、已过期或已停用");
                    }
                    exchange.getAttributes().put(ATTRIBUTE_KEY_VIEW, view);
                    return chain.filter(exchange);
                });
    }

    /** 客户端携带 {@code <key_id>.<secret>}；只有 secret 部分参与哈希（与铸造端一致）。 */
    private String extractSecret(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
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

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("JVM 未提供 SHA-256", e);
        }
    }
}
