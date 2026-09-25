package com.aihub.gateway.admin;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.internal.InternalHmac;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
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

    Mono<Optional<ApiKeyView>> resolve(String keyHash);

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
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String signature = InternalHmac.sign(internalSecret, timestamp, "POST", RESOLVE_PATH);

            return webClient.post()
                    .uri(RESOLVE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Timestamp", timestamp)
                    .header("X-Internal-Signature", signature)
                    .bodyValue("{\"keyHash\":\"" + keyHash + "\"}")
                    .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                            .map(body -> parse(response.statusCode().value(), body)))
                    .onErrorResume(ex -> {
                        log.warn("admin 内部接口调用失败: {}", ex.toString());
                        return Mono.just(Optional.empty());
                    });
        }

        /** 非 2xx、缺 {@code data}、字段畸形都折算成「不存在」，绝不向上抛。 */
        static Optional<ApiKeyView> parse(int status, String body) {
            if (status < 200 || status >= 300) {
                return Optional.empty();
            }
            try {
                JsonNode data = MAPPER.readTree(body).path("data");
                if (data.isMissingNode() || data.isNull()) {
                    return Optional.empty();
                }
                JsonNode expireAt = data.path("expireAt");
                return Optional.of(new ApiKeyView(
                        data.path("keyId").asText(),
                        data.path("tenantId").asLong(),
                        data.path("tenantName").asText(),
                        data.path("status").asText(),
                        expireAt.isNull() || expireAt.isMissingNode() ? null : Instant.parse(expireAt.asText())));
            } catch (Exception e) {
                log.warn("解析 admin 响应失败: {}", e.toString());
                return Optional.empty();
            }
        }
    }
}
