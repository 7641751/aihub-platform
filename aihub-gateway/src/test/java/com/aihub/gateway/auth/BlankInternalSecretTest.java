package com.aihub.gateway.auth;

import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出厂默认配置（{@code aihub.internal.secret} 为空串）下的 fail-closed 行为。
 *
 * <p>空 secret 会让 {@code InternalHmac.sign} 抛 {@code IllegalStateException}（{@code SecretKeySpec}
 * 不接受空 key）。那一刻的异常**必须**被折算成 401 + OpenAI 错误体，绝不能变成 500 ——
 * 这正是「任何一级不可用都降级、绝不因基础设施问题给客户端 500」的约定。
 *
 * <p>这里刻意**不**提供 {@code @Primary} 假 {@code AdminClient}：要验证的就是真实回源实现，
 * 假 bean 会把唯一的真实现挡住（这也正是当初漏掉该缺陷的原因）。
 * 同步指向死端口的 Redis 与死端口的 upstream：缓存必然 miss，且一旦鉴权被绕过就会立刻表现为
 * 「触到上游」，而不是安静地拿到 200。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BlankInternalSecretTest {

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
        // 仓库默认值：空内部密钥 → 回源签名必然失败
        registry.add("aihub.internal.secret", () -> "");
        registry.add("aihub.auth.admin-base-url", () -> "http://127.0.0.1:1");
        // 指向不存在的 Redis 端口：缓存必然 miss，强制走进真实 AdminClient
        registry.add("spring.data.redis.port", () -> "1");
    }

    @Test
    void unknownKeyWithBlankInternalSecretIsRejectedAsInvalidApiKey() throws Exception {
        upstream.clearLastRequest();

        HttpResponse<String> response = post("Bearer ak_unknown.some-secret");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"invalid_api_key\"");
        assertThat(response.body()).contains("\"type\":\"invalid_request_error\"");
        assertThat(upstream.lastRequest()).isNull();
    }

    private HttpResponse<String> post(String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":false}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
