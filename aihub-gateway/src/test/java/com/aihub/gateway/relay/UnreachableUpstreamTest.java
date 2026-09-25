package com.aihub.gateway.relay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上游完全不可达（连接被拒）时必须返回网关自造的 502，而不是框架 500。
 * <p>本类**不能**复用 {@code ChatRelayControllerTest} 里的静态 {@code FakeUpstream}：那个字段在整类
 * 级别固定了 {@code aihub.upstream.base-url}，而这里需要指向一个没有监听的端口。端口 1 在 Windows
 * 上必然连接被拒（且无特权进程监听），所以用属性写死即可，无需 Docker。
 * <p>M1 计划里本就排了这个类（后续任务的鉴权重放等场景会在此扩展），这里先把错误路径钉住。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=false", "aihub.upstream.base-url=http://127.0.0.1:1"})
class UnreachableUpstreamTest {

    @LocalServerPort
    private int gatewayPort;

    @Test
    void unreachableUpstreamYields502WithStableGatewayErrorBody() throws Exception {
        HttpResponse<String> response = post("{\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).contains("application/json"));
        assertThat(response.body())
                .contains("\"type\":\"api_error\"")
                .contains("\"code\":\"upstream_unreachable\"");
        // 稳定的英文 message，且不泄漏上游内网地址/异常细节（旧实现是 "上游服务不可达: " + ex.getMessage()）。
        assertThat(response.body()).contains("\"message\":\"Upstream service is unreachable\"");
        assertThat(response.body()).doesNotContain("Connection refused");
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
