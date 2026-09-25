package com.aihub.gateway.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /v1/models} 的契约：单渠道场景只回报一个模型，形状遵循 OpenAI 的 list 协议
 * （{@code {"object":"list","data":[{"id":<defaultModel>,"object":"model","owned_by":"aihub"}]}}）。
 *
 * <p>这里按 JSON 结构断言而不是子串匹配：模型 id 来自 {@code aihub.upstream.default-model} 配置项，
 * 用配置值而非硬编码常量来断言，才真的证明它是「配置驱动」的。用 Jackson 解析也避免了键序问题
 * （{@code Map.of} 的迭代顺序不保证）。
 *
 * <p>{@code base-url} 故意指向必然连接被拒的端口：{@code /v1/models} 必须是网关**本地**应答，
 * 一旦实现改成回源，本用例会立刻变成 502 而不是 200。
 *
 * <p>鉴权开关关闭时本端点应放行；「开着鉴权时必须 401」由
 * {@code ApiKeyAuthFilterTest.modelsEndpointIsGuardedLikeEveryOtherV1Path} 覆盖（那边是
 * auth-enabled 上下文）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "aihub.auth.enabled=false",
                "aihub.upstream.default-model=m1-test-model",
                "aihub.upstream.base-url=http://127.0.0.1:1"})
class ModelsControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int gatewayPort;

    @Test
    void listsTheConfiguredModelInOpenAiListShape() throws Exception {
        HttpResponse<String> response = get("/v1/models");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).contains("application/json"));

        JsonNode root = MAPPER.readTree(response.body());
        assertThat(root.path("object").asText()).isEqualTo("list");

        JsonNode data = root.path("data");
        assertThat(data.isArray()).as("data 必须是数组").isTrue();
        assertThat(data.size()).isEqualTo(1);

        JsonNode model = data.get(0);
        assertThat(model.path("id").asText()).isEqualTo("m1-test-model");
        assertThat(model.path("object").asText()).isEqualTo("model");
        assertThat(model.path("owned_by").asText()).isEqualTo("aihub");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + gatewayPort + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
