package com.aihub.gateway.relay;

import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import com.aihub.gateway.testsupport.RecordingMeteringTransport;
import com.aihub.gateway.trace.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上游完全不可达（连接被拒）时必须返回网关自造的 502，而不是框架 500。
 * <p>本类**不能**复用 {@code ChatRelayControllerTest} 里的静态 {@code FakeUpstream}：那个字段在整类
 * 级别固定了 {@code aihub.upstream.base-url}，而这里需要指向一个没有监听的端口。端口 1 在 Windows
 * 上必然连接被拒（且无特权进程监听），所以用属性写死即可，无需 Docker。
 * <p>M1 计划里本就排了这个类（后续任务的鉴权重放等场景会在此扩展），这里先把错误路径钉住。
 * <p>M2 起它同时钉住「502 也要有计量」：计量默认关闭（见测试配置），因此这里显式打开并只替换投递器，
 * 不依赖 broker、不依赖 Docker。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=false", "aihub.upstream.base-url=http://127.0.0.1:1",
                "aihub.metering.enabled=true"})
@Import(MeteringTestConfig.class)
class UnreachableUpstreamTest {

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private RecordingMeteringTransport recorder;

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

    /**
     * 502 也必须有计量：否则「上游整体挂掉」这段时间的请求在 request_log 里完全不存在，
     * 与「没有请求」无法区分。鉴权在本类是关的，因此 tenantId 走哨兵 0（决策 9）。
     */
    @Test
    void unreachableUpstreamIsMeteredAsUnreachable() throws Exception {
        HttpResponse<String> response = post("{\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(502);
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        MeteringEvent event = recorder.awaitEvent(requestId, Duration.ofSeconds(5));
        assertThat(event)
                .as("502 的响应头 x-request-id 必须能在计量记录里找到（实际记录: %s）",
                        recorder.recordedRequestIds())
                .isNotNull();
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_ERROR);
        assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_UPSTREAM_UNREACHABLE);
        assertThat(event.tenantId()).isEqualTo(0L);
        assertThat(event.totalTokens()).isZero();
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
