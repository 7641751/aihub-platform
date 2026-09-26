package com.aihub.gateway.admin;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.RatePolicy;
import com.aihub.common.internal.InternalHmac;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 调 admin 内部接口。网关不直连 MySQL（设计文档决策 A），密钥的真相源在 admin。
 * <p>签名算法必须与 admin 的 InternalHmac 一致；两处都改了才算改对。
 *
 * <p><b>本类型是函数式接口</b>：它只有一条契约「给定 keyHash，给出解析结果」，测试里一个 lambda
 * 就是完整替身，不必再为「可替身」而额外造一个接口 + 一个实现类。真实实现见 {@link Http}。
 *
 * <p>约定：{@link #resolve} **不抛异常**。admin 不可达/超时/返回非 2xx/响应体畸形一律折算成
 * {@link Optional#empty()}（视作「这个 key 不存在」）。网络故障与垃圾数据都不该让客户端拿到 500。
 */
@FunctionalInterface
public interface AdminClient {

    /**
     * 内部接口路径。**必须是应用内路径**（不含 context path，也不由 base-url 拼出）：admin 的
     * {@code InternalAuthFilter} 用 {@code UrlPathHelper.getPathWithinApplication} 取路径再验签，
     * 用 base-url 拼出来的带前缀路径永远验不过 —— 这正是 Task 3 最后一个 commit 钉下的契约。
     */
    String RESOLVE_PATH = "/internal/api-keys/resolve";

    /**
     * 配置快照路径。与 {@code RESOLVE_PATH} 一样**必须是应用内路径**（不含 context path）：
     * admin 的 {@code InternalAuthFilter} 用 {@code UrlPathHelper.getPathWithinApplication} 验签。
     */
    String CONFIG_SNAPSHOT_PATH = "/internal/config/snapshot";

    Mono<Optional<ApiKeyView>> resolve(String keyHash);

    /**
     * 拉取配置快照（渠道 + 路由 + 限流策略 + 版本号）。
     *
     * <p>**默认实现返回空**：这样所有既有的替换实现（测试里的 {@code keyHash -> Mono.just(...)}）
     * 不必改一行就仍然编译通过；真实实现见 {@link Http}。
     * 空 {@link Optional} 的语义是「控制面拿不到快照」，调用方据此走降级（决策 6）。
     */
    default Mono<Optional<ConfigSnapshot>> configSnapshot() {
        return Mono.just(Optional.empty());
    }

    /** 真实实现：相对路径 + HMAC 签名 + 响应解析。 */
    static AdminClient http(WebClient webClient, String internalSecret) {
        return new Http(webClient, internalSecret);
    }

    final class Http implements AdminClient {

        private static final Logger log = LoggerFactory.getLogger(Http.class);
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final WebClient webClient;
        private final String internalSecret;

        Http(WebClient webClient, String internalSecret) {
            this.webClient = webClient;
            this.internalSecret = internalSecret;
        }

        @Override
        public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
            // 签名与请求构造**必须**惰性：InternalHmac.sign 在 secret 为空（仓库默认 aihub.internal.secret
            // 就是空串）时会抛 IllegalStateException，急切求值会让它在返回 Mono **之前**就逃出去，
            // 那样下面的 onErrorResume 根本看不见它，客户端拿到的是 500 而不是约定的 401。
            // Mono.defer 之后，签名失败与网络失败走同一条 fail-closed 路径。
            return Mono.defer(() -> {
                String timestamp = String.valueOf(Instant.now().getEpochSecond());
                String signature = InternalHmac.sign(internalSecret, timestamp, "POST", RESOLVE_PATH);

                return webClient.post()
                        .uri(RESOLVE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Timestamp", timestamp)
                        .header("X-Internal-Signature", signature)
                        .bodyValue("{\"keyHash\":\"" + keyHash + "\"}")
                        .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                                .map(body -> parse(response.statusCode().value(), body)));
            }).onErrorResume(ex -> {
                // 传输层失败（连不上 / 超时 / 签名抛错）**不是**「key 不存在」，是 admin 侧故障。
                // fail-closed 的对客表现与坏 key 完全一致（401 invalid_api_key），所以这行 ERROR
                // 是运维侧唯一的区分信号：admin 全挂时它会持续出现，而单个坏 key 不会。
                log.error("admin 回源失败（传输层异常，非「key 不存在」），按「key 不存在」处理（fail-closed）: {}",
                        ex.toString());
                return Mono.just(Optional.empty());
            });
        }

        @Override
        public Mono<Optional<ConfigSnapshot>> configSnapshot() {
            // 与 resolve 同一套 fail-closed 纪律：签名/网络/非 2xx/畸形响应一律折算成「没有快照」，
            // 由配置层决定继续用陈旧快照还是回落到遗留单渠道。绝不抛到请求路径上。
            return Mono.defer(() -> {
                String timestamp = String.valueOf(Instant.now().getEpochSecond());
                String signature = InternalHmac.sign(internalSecret, timestamp, "GET", CONFIG_SNAPSHOT_PATH);

                return webClient.get()
                        .uri(CONFIG_SNAPSHOT_PATH)
                        .header("X-Internal-Timestamp", timestamp)
                        .header("X-Internal-Signature", signature)
                        .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                                .map(body -> parseSnapshot(response.statusCode().value(), body)));
            }).onErrorResume(ex -> {
                log.error("admin 配置快照拉取失败（传输层异常），本次用缓存/遗留渠道继续服务: {}", ex.toString());
                return Mono.just(Optional.empty());
            });
        }

        /** 非 2xx / 缺 {@code data} / 字段畸形都折算成「没有快照」。 */
        static Optional<ConfigSnapshot> parseSnapshot(int status, String body) {
            if (status < 200 || status >= 300) {
                log.error("admin 配置快照拉取失败（HTTP {}），本次用缓存/遗留渠道继续服务。响应体: {}",
                        status, body);
                return Optional.empty();
            }
            try {
                JsonNode data = MAPPER.readTree(body).path("data");
                if (data.isMissingNode() || data.isNull()) {
                    log.debug("admin 配置快照：HTTP {} 响应无 data，按「没有快照」处理", status);
                    return Optional.empty();
                }
                long version = data.path("version").asLong(0L);
                long generatedAt = data.path("generatedAtEpochMilli").asLong(0L);
                String defaultModel = data.path("defaultModel").isTextual()
                        ? data.get("defaultModel").asText() : null;

                List<ChannelDescriptor> channels = new ArrayList<>();
                for (JsonNode node : data.path("channels")) {
                    channels.add(new ChannelDescriptor(
                            node.path("id").asLong(), node.path("name").asText(null),
                            node.path("baseUrl").asText(null), node.path("apiKeyCipher").asText(null),
                            node.path("keyVersion").asInt(0), node.path("timeoutMs").asInt(0),
                            node.path("status").asText(null), node.path("weight").asInt(0),
                            node.path("priority").asInt(0)));
                }
                List<ModelRouteDescriptor> routes = new ArrayList<>();
                for (JsonNode node : data.path("routes")) {
                    routes.add(new ModelRouteDescriptor(
                            node.path("modelName").asText(null), node.path("channelId").asLong(),
                            node.path("weight").asInt(0), node.path("priority").asInt(0),
                            node.path("status").asText(null)));
                }
                List<RatePolicy> policies = new ArrayList<>();
                for (JsonNode node : data.path("ratePolicies")) {
                    policies.add(new RatePolicy(
                            node.path("tenantId").isNumber() ? node.get("tenantId").asLong() : null,
                            node.path("apiKeyId").isNumber() ? node.get("apiKeyId").asLong() : null,
                            node.path("qps").asInt(0), node.path("burst").asInt(0)));
                }
                return Optional.of(new ConfigSnapshot(version, generatedAt, channels, routes, policies, defaultModel));
            } catch (Exception e) {
                log.error("admin 配置快照响应畸形，本次用缓存/遗留渠道继续服务: {}", e.toString());
                return Optional.empty();
            }
        }

        /** 非 2xx、缺 {@code data}、字段畸形都折算成「不存在」，绝不向上抛。 */
        static Optional<ApiKeyView> parse(int status, String body) {
            if (status < 200 || status >= 300) {
                if (status == 404 && hasAdminCode(body, "NOT_FOUND")) {
                    // admin 的 404 + NOT_FOUND 信封 = 「这个 key 不存在」：正常业务结果，不打 ERROR。
                    log.debug("admin 回源：key 不存在（HTTP 404）");
                } else {
                    // 5xx / 401 / 403，以及「路径写错」这类没有 NOT_FOUND 信封的 404：都是**平台故障**。
                    // 客户端仍然只看到 401 invalid_api_key（与坏 key 不可区分，见 CONVENTIONS 第 4/5 节），
                    // 因此这条 ERROR 是「平台级故障」与「单个坏 key」在日志里唯一的分界。
                    log.error("admin 回源失败（HTTP {}，服务不可用或配置错误，非「key 不存在」），"
                            + "按「key 不存在」处理（fail-closed）。响应体: {}", status, body);
                }
                return Optional.empty();
            }
            try {
                JsonNode data = MAPPER.readTree(body).path("data");
                if (data.isMissingNode() || data.isNull()) {
                    // 2xx 但没有 data（例如 admin 的 200 空信封）：同样按「不存在」处理，不是故障。
                    log.debug("admin 回源：HTTP {} 响应无 data，按「key 不存在」处理", status);
                    return Optional.empty();
                }
                JsonNode expireAt = data.path("expireAt");
                return Optional.of(new ApiKeyView(
                        data.path("keyId").asText(),
                        data.path("tenantId").asLong(),
                        data.path("tenantName").asText(),
                        data.path("status").asText(),
                        expireAt.isNull() || expireAt.isMissingNode() ? null : Instant.parse(expireAt.asText()),
                        data.path("apiKeyId").isNumber() ? data.get("apiKeyId").asLong() : null));
            } catch (Exception e) {
                log.error("admin 回源响应畸形（非「key 不存在」），按「key 不存在」处理（fail-closed）: {}",
                        e.toString());
                return Optional.empty();
            }
        }

        /** 响应体是不是 admin 的 {@code {code,message,data}} 信封，且 {@code code} 等于给定值。 */
        private static boolean hasAdminCode(String body, String code) {
            try {
                return code.equals(MAPPER.readTree(body).path("code").asText());
            } catch (Exception e) {
                return false;
            }
        }
    }
}
