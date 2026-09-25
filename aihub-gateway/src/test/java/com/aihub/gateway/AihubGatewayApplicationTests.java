package com.aihub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AihubGatewayApplicationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void healthzReturnsUp() {
        webTestClient.get().uri("/healthz")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                // 同时钉住 src/test/resources/application.yml 的 Redis 健康开关真的生效了：
                // 本机没有 Redis，若该指示器还在，这里会是 DOWN 且响应体里出现 "redis"。
                .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                        .contains("\"status\":\"UP\"")
                        .doesNotContain("\"redis\""));
    }
}
