package com.aihub.gateway.auth;

import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.admin.AdminResolution;
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
 * <p>失败体是 OpenAI 形状（{@code GatewayErrors.write}，<b>不是</b> admin 的 {@code {code,message,data}}
 * 信封）：数据面客户端是各种 OpenAI SDK，它们只认 {@code error.message}。密钥格式不对也按 401 处理
 * —— 畸形请求头不是参数校验问题，不该回 400 让客户端以为换个 body 就能通过。
 *
 * <p><b>两类失败，两个状态码（D4 起）</b>：
 * <ul>
 *   <li><b>「我们决定了这把 key 无效」</b> → {@code 401 invalid_api_key}：缺 {@code Authorization}、
 *       格式不对、admin **权威地**说没有这把 key（{@link AdminResolution.Status#NOT_FOUND}）、
 *       或 admin 给了一份 {@code usable() == false} 的视图（已过期 / 已停用）；</li>
 *   <li><b>「我们无法判定这把 key 是否有效」</b> → {@code 503 service_unavailable}：
 *       {@link AdminResolution.Status#UNAVAILABLE}（admin 不可达 / 超时 / 5xx / 响应畸形 /
 *       内部签名失败，含 {@code aihub.internal.secret} 为空这种平台配置故障），
 *       以及 {@link ApiKeyResolver} 打破了「永不返回空 Mono」契约的情形。
 *       见 {@link #serviceUnavailable(ServerWebExchange)}。</li>
 * </ul>
 *
 * <p><b>这不是削弱鉴权：两条路径都是拒绝。</b>无论 401 还是 503，请求都不会继续走到限流 / 路由 /
 * 上游，客户端都不会拿到 {@code 200} —— 401 与 503 的区别只在于**诊断通道**：以前平台故障伪装成
 * 「你的 key 错了」（客户端会去改密钥，是错的反应），现在诚实地告诉客户端「我们暂时判不了，
 * 请稍后重试」，OpenAI SDK 对 5xx 有内建重试，能做出正确反应。因此安全姿态不变。
 *
 * <p>不上 {@code Retry-After}：我们**不知道**这次故障会持续多久（内部跳的 3 秒预算是单次调用的
 * 上界，不是恢复时刻），给一个猜出来的值比不给更糟（{@code docs/CONVENTIONS.md} 第 6.6 节）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ApiKeyAuthFilter implements WebFilter {

    /** 解析成功的 {@link ApiKeyView} 存放位置（M2 计量、M3 配额从这里取）。 */
    public static final String ATTRIBUTE_KEY_VIEW = "aihub.apiKeyView";

    /**
     * 本次请求密钥的 SHA-256 存放位置。M3 的限流用它当桶的第二维（{@code tenant + api_key}）。
     * 存**哈希**而不是 secret：它本来就已经算出来了，而且哈希能安全地进日志/指标（secret 不能）。
     */
    public static final String ATTRIBUTE_KEY_HASH = "aihub.apiKeyHash";

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
     * 「我们**无法判定**这把 key 是否有效」（平台故障）的对客文案。逐字来自本轮登记的契约
     * （{@code docs/CONVENTIONS.md} 第 4 节），不要改写。
     */
    private static final String SERVICE_UNAVAILABLE_MESSAGE =
            "密钥服务暂时不可用：网关无法校验本次 API Key，请稍后重试";
    private static final String SERVICE_UNAVAILABLE_CODE = "service_unavailable";
    private static final String SERVICE_UNAVAILABLE_TYPE = "api_error";

    /**
     * {@link ApiKeyResolver} 的契约是「永不返回空 Mono」。万一契约被打破（返回了空 Mono），
     * 用这个「判不了」哨兵兜底 —— 空 Mono 意味着**无法判定**这把 key 是否有效，与
     * {@link AdminResolution.Status#UNAVAILABLE} 是同一条诊断通道：对客 503。
     * <p><b>只 new 一次</b>：契约被打破与 admin 判定不了共用一个实例，两侧不再各自造同形哨兵。
     */
    private static final AdminResolution UNRESOLVED = AdminResolution.unavailable();

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
        // 哈希同时是 M3 限流桶的第二维，因此在这里（而不是在限流器里）算一次并写进属性：
        // 限流器不该拿到 secret，也不该再算一遍。
        String keyHash = ApiKeyHasher.hash(secret);
        return resolver.resolve(keyHash)
                .switchIfEmpty(Mono.just(UNRESOLVED))
                .flatMap(resolution -> {
                    if (resolution.status() == AdminResolution.Status.UNAVAILABLE) {
                        return serviceUnavailable(exchange);
                    }
                    if (!resolution.isFound() || !resolution.view().usable()) {
                        return unauthorized(exchange, INVALID_KEY_MESSAGE);
                    }
                    ApiKeyView view = resolution.view();
                    exchange.getAttributes().put(ATTRIBUTE_KEY_VIEW, view);
                    exchange.getAttributes().put(ATTRIBUTE_KEY_HASH, keyHash);
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

    /**
     * 「我们**无法判定**这把 key 是否有效」：{@code 503 service_unavailable} + {@code api_error}。
     *
     * <p>与 {@link #unauthorized} 一样是**拒绝**（下游链一次都不碰），只是诊断通道不同：
     * 401 说的是「你的凭据不行」（客户端该去改密钥），503 说的是「我们这边判不了，稍后重试」
     * （客户端该退避重试）。把后者报成前者会让客户端做错事 —— 那正是 D4 要修的。
     *
     * <p><b>刻意不加 {@code Retry-After}</b>：故障会持续多久是**未知**的（内部跳的 3 秒预算只是
     * 单次调用的上界）。因此「网关只写这四个头」（{@code Retry-After} / {@code Retry-After-MS} /
     * {@code RateLimit-Limit} / {@code RateLimit-Remaining}，见 CONVENTIONS 第 6.6 节）依旧成立。
     * {@code x-request-id} 由排在前面的 {@code RequestIdFilter} 写上，所以 503 一样带它。
     */
    private Mono<Void> serviceUnavailable(ServerWebExchange exchange) {
        return GatewayErrors.write(exchange.getResponse(), HttpStatus.SERVICE_UNAVAILABLE,
                SERVICE_UNAVAILABLE_TYPE, SERVICE_UNAVAILABLE_CODE, SERVICE_UNAVAILABLE_MESSAGE);
    }
}
