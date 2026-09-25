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
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/healthz"))
                .GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    /**
     * 回归：守备判定用的路径必须与 handler mapping 一致（解码后、剥掉 path parameter）。
     * {@code /%761/chat/completions} 解码后就是 {@code /v1/chat/completions}，用原始的
     * {@code getPath().value()} 做字符串前缀判断会漏掉它 → 过滤器放行 → 无密钥直达上游。
     */
    @Test
    void percentEncodedGuardedPathCannotBypassTheGuard() throws Exception {
        assertRejectedWithoutTouchingUpstream("/%761/chat/completions");
    }

    /** 回归：{@code /v1;x=/chat/completions} 同样命中 controller，但原始路径不以 {@code /v1/} 开头。 */
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
