package com.aihub.gateway.relay;

import com.aihub.gateway.upstream.UpstreamProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.NestedTestConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    /**
     * {@code defaultModel} 为 null（属性整个缺失）或空白（环境变量被设成空串）都不该 500：
     * OpenAI 协议下 {@code data} 是数组，空数组是合法且诚实的回答；编造一个 id 会让 SDK
     * 拿着一个不存在的模型名去打下游。修复前这里是 {@code Map.of("id", null)} → NPE → 500。
     */
    @Test
    void blankOrMissingDefaultModelYieldsAnEmptyList() {
        for (String model : Arrays.asList(null, "", "   ")) {
            ModelsController controller =
                    new ModelsController(new UpstreamProperties("http://127.0.0.1:1", null, model));

            Map<String, Object> body = controller.listModels().block();

            assertThat(body).containsEntry("object", "list");
            assertThat((List<?>) body.get("data")).isEmpty();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + gatewayPort + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 空白 {@code aihub.upstream.default-model} 的**真实 HTTP 状态行**：上面那条用例直接把控制器方法
     * {@code block()} 掉，只能钉住「不抛异常」，钉不住「客户端拿到的是 200 + 空 {@code data}」。
     *
     * <p><b>为什么是空串而不是「属性缺失」</b>：主配置是
     * {@code aihub.upstream.default-model: ${AIHUB_UPSTREAM_DEFAULT_MODEL:default}}，键**永远存在**，
     * 因此配置绑定不可能产出 {@code null}（实测：把本类属性去掉后上下文拿到的是 {@code default}，
     * {@code data} 有 1 个元素）。也就是说 {@code defaultModel == null} 这条 NPE → 500 路径在 HTTP 层
     * **不可达**（只能由直接构造触发，见上面那条用例），HTTP 层唯一可达的「空白」是空串
     * （环境变量/属性被设成空）。本用例钉的正是它：旧代码在这里是 200 + 编造出来的空 id，
     * 修复后必须是 200 + 空 {@code data}。
     *
     * <p><b>为什么用 {@code @Nested}</b>：属性在上下文启动时就绑定进 {@code UpstreamProperties}，
     * 同一个上下文里改不了，而外层上下文的 {@code default-model=m1-test-model} 正是
     * {@link #listsTheConfiguredModelInOpenAiListShape} 的断言依据。因此不动那个上下文，改用
     * {@link NestedTestConfiguration} 的 {@code OVERRIDE} 另起一个**只差这一个属性**的上下文
     * （本类其余测试与断言一行未改）。
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "aihub.auth.enabled=false",
                    "aihub.upstream.default-model=",
                    "aihub.upstream.base-url=http://127.0.0.1:1"})
    class BlankDefaultModelOverHttp {

        @LocalServerPort
        private int gatewayPort;

        @Test
        void blankDefaultModelYields200WithAnEmptyListInsteadOfAFabricatedId() throws Exception {
            HttpResponse<String> response = get("/v1/models");

            assertThat(response.statusCode()).isEqualTo(200);
            // 「这个上下文真的把 default-model 绑成了空白」本身也要可证伪：
            // 一旦继承错上下文（拿到 m1-test-model 或主配置的 default），下面两条会先红。
            assertThat(response.body()).doesNotContain("m1-test-model");

            JsonNode root = MAPPER.readTree(response.body());
            assertThat(root.path("object").asText()).isEqualTo("list");
            assertThat(root.path("data").isArray()).as("data 必须是数组").isTrue();
            // 旧代码在这里是 1（编造出 {"id":""}）；修复后必须是 0。
            assertThat(root.path("data").size()).isZero();
        }

        private HttpResponse<String> get(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + gatewayPort + path)).GET().build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
