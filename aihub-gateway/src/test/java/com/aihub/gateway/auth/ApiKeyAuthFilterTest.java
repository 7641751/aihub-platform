package com.aihub.gateway.auth;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyAuthFilterTest {

    private static FakeUpstream upstream;

    @LocalServerPort
    private int gatewayPort;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> upstream.baseUrl());
        registry.add("aihub.auth.enabled", () -> "true");
        registry.add("aihub.internal.secret", () -> "test-internal-secret");
        // 指向不存在的 Redis 端口：缓存必然 miss，强制走 AdminClient（本测试用假实现）
        registry.add("spring.data.redis.port", () -> "1");
    }

    @TestConfiguration
    static class FakeAdmin {
        @Bean
        @Primary
        AdminClient adminClient() {
            return keyHash -> {
                if (keyHash.equals(TestKeys.VALID_HASH)) {
                    return Mono.just(Optional.of(new ApiKeyView("ak_valid", 7L, "t", "ACTIVE", null)));
                }
                if (keyHash.equals(TestKeys.EXPIRED_HASH)) {
                    return Mono.just(Optional.of(new ApiKeyView("ak_exp", 7L, "t", "ACTIVE",
                            Instant.now().minusSeconds(60))));
                }
                return Mono.just(Optional.empty());
            };
        }
    }

    static final class TestKeys {
        static final String VALID_SECRET = "valid-secret";
        static final String EXPIRED_SECRET = "expired-secret";
        static final String VALID_HASH = sha256Hex(VALID_SECRET);
        static final String EXPIRED_HASH = sha256Hex(EXPIRED_SECRET);
    }

    @Test
    void missingApiKeyIsRejectedWithOpenAiErrorBody() throws Exception {
        HttpResponse<String> response = post(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"invalid_api_key\"");
        assertThat(response.body()).contains("\"type\":\"invalid_request_error\"");
    }

    /**
     * 缺口覆盖：新增的数据面端点 {@code GET /v1/models} 也落在守备范围 {@code /v1/**} 内，
     * 必须和 {@code /v1/chat/completions} 一样先过鉴权。少了这条，「新端点被过滤器漏掉」在测试里
     * 毫无痕迹 —— 它只在线上表现为「任何人都能匿名列出模型」。
     *
     * <p>断言的是 401 + OpenAI 错误体本身，而不是「不是 200」：状态码对但错误体换成框架默认页，
     * OpenAI SDK 同样解析不了。本类与 {@code ModelsControllerTest}（{@code auth.enabled=false}）
     * 一起才构成完整契约：端点存在（那边 200 + list 形状）且确实在过滤器后面（这边 401）。
     */
    @Test
    void modelsEndpointIsGuardedLikeEveryOtherV1Path() throws Exception {
        HttpResponse<String> response = get("/v1/models");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"invalid_api_key\"");
        assertThat(response.body()).contains("\"type\":\"invalid_request_error\"");
    }

    @Test
    void unknownApiKeyIsRejected() throws Exception {
        assertThat(post("Bearer ak_x.unknown").statusCode()).isEqualTo(401);
    }

    @Test
    void expiredApiKeyIsRejected() throws Exception {
        assertThat(post("Bearer ak_exp." + TestKeys.EXPIRED_SECRET).statusCode()).isEqualTo(401);
    }

    @Test
    void validApiKeyReachesUpstream() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("Bearer ak_valid." + TestKeys.VALID_SECRET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chat.completion");
    }

    @Test
    void healthEndpointIsNotGuarded() throws Exception {
        HttpResponse<String> response = get("/healthz");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    /**
     * 回归（**区分性**用例）：守备判定用的路径必须与 handler mapping 一致（解码后、剥掉 path parameter）。
     * {@code /%761/chat/completions} 的**原始**路径是 {@code /%761/...}（不以 {@code /v1/} 开头 →
     * 旧的字符串前缀守卫会放行），但 handler mapping 按解码后路径匹配到 controller
     * → 无密钥直达上游。这条是 Finding 1 的**唯一**回归防护：恢复旧守卫时它必然变红。
     */
    @Test
    void percentEncodedGuardedPathCannotBypassTheGuard() throws Exception {
        assertRejectedWithoutTouchingUpstream("/%761/chat/completions");
    }

    /**
     * **形状覆盖**（不是区分性用例）：{@code /v1;x=/chat/completions} 同样命中 controller，
     * 但它的原始路径以 {@code "/v1;"} 开头、不以 {@code "/v1/"} 开头，**旧守卫也会拒绝**它 ——
     * 实测把守卫改回 {@code value().startsWith("/v1/")} 本用例仍绿。所以它只能证明「这类变体
     * 保持被拒绝」，不能用来论证「旧守卫的绕过已关闭」；那个论证只有上面那条百分号编码用例成立。
     */
    @Test
    void pathParameterVariantCannotBypassTheGuard() throws Exception {
        assertRejectedWithoutTouchingUpstream("/v1;x=/chat/completions");
    }

    /** RFC 7235：scheme 大小写不敏感，{@code bearer} 必须与 {@code Bearer} 等价。 */
    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = postPath("/v1/chat/completions",
                "bearer ak_valid." + TestKeys.VALID_SECRET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chat.completion");
    }

    /**
     * 不带 Authorization 打「过滤器看不见、handler mapping 却看得见」的路径变体：既必须 401，
     * 又必须**没有触到上游**。只断言状态码的话，将来把绕过改成另一种拒绝（例如 400/403）就会假绿。
     */
    private void assertRejectedWithoutTouchingUpstream(String path) throws Exception {
        upstream.clearLastRequest();

        HttpResponse<String> response = postPath(path, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"invalid_api_key\"");
        assertThat(upstream.lastRequest()).isNull();
    }

    private HttpResponse<String> post(String authorization) throws Exception {
        return postPath("/v1/chat/completions", authorization);
    }

    /** 不带 Authorization 的 GET：用于「某些端点必须被守卫拦下」的断言。 */
    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + gatewayPort + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postPath(String path, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":false}"));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
