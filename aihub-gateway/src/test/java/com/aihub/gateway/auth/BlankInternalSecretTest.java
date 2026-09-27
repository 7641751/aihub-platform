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
 * 不接受空 key）。那是**平台配置故障**：我们**无法判定**这把 key 是否有效，所以它必须被折算成
 * {@code 503 service_unavailable} + OpenAI 错误体（{@code api_error}），绝不能变成 {@code 500}。
 *
 * <p><b>503 不是 500，而且仍然是拒绝</b>：它是网关自己产出的、有明确语义的数据面错误码
 * （「我们暂时判不了」），不是「网关自身崩了」；请求同样走不到限流 / 路由 / 上游，客户端同样拿不到
 * {@code 200}。变的只是诊断通道 —— 以前平台故障伪装成 401「你的 key 是错的」，客户端会去改密钥；
 * 现在它诚实地告诉客户端「稍后重试」，OpenAI SDK 对 5xx 有内建重试，能做出正确反应。
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

    /**
     * 空 {@code aihub.internal.secret} = **平台配置故障**（网关判不了这把 key），
     * 对客是 {@code 503 service_unavailable} + {@code api_error}；上游一次都不许被触到。
     */
    @Test
    void unknownKeyWithBlankInternalSecretIsAnsweredAsAServiceFault() throws Exception {
        upstream.clearLastRequest();

        HttpResponse<String> response = post("Bearer ak_unknown.some-secret");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"code\":\"service_unavailable\"");
        assertThat(response.body()).contains("\"type\":\"api_error\"");
        assertThat(upstream.lastRequest()).as("503 仍然是拒绝：上游一次都不许被触到").isNull();
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
