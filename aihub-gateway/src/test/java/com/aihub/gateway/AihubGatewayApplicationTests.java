package com.aihub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AihubGatewayApplicationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private Environment environment;

    @Test
    void healthzReturnsUp() {
        webTestClient.get().uri("/healthz")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                // 同时钉住 /healthz 的路径映射（来自主配置的 management.endpoints.web.path-mapping.health）
                // 与测试用的 Redis / RabbitMQ 健康开关真的生效了：本机没有 Redis，
                // 若该指示器还在，这里会是 DOWN 且响应体里出现 "redis"；RabbitMQ 即使本机恰好有一个
                // 在跑也必须不出现 —— 否则测试套件会静默依赖一个活 broker。
                .value(body -> assertThat(body)
                        .contains("\"status\":\"UP\"")
                        .doesNotContain("\"redis\"")
                        .doesNotContain("\"rabbit\""));
    }

    /**
     * 主配置（{@code src/main/resources/application.yml}）确实在测试里生效，而不是被测试副本取代。
     * <p>这里断言的都是**只写在主配置里**、且不含占位符（不受环境变量影响）的键；测试资源目录下只有
     * 一份 {@code application.properties}，且只含 {@code management.health.redis.enabled}。
     * 只要主配置被整体复制成测试资源、或者主配置里这些键被删改而测试副本没跟着改，本测试就会红。
     */
    @Test
    void mainApplicationYamlIsMergedIntoTheTestConfiguration() {
        // 主配置独有：api-key 缓存的 TTL。测试副本从来没有（也不该有）这两个键。
        assertThat(environment.getProperty("aihub.auth.local-cache-ttl")).isEqualTo("30s");
        assertThat(environment.getProperty("aihub.auth.key-cache-ttl")).isEqualTo("5m");
        // /healthz 能在测试里工作，靠的就是主配置的这条路径映射。
        assertThat(environment.getProperty("management.endpoints.web.path-mapping.health")).isEqualTo("healthz");
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("always");
        // 同时确认测试自己的那一行也合并在内（两份配置真的叠加，而不是二选一）。
        assertThat(environment.getProperty("management.health.redis.enabled")).isEqualTo("false");
        // rabbit 健康指示器的开关同样只写在主配置里，且是「网关测试不需要活 broker」的前提：
        // 没有它，healthzReturnsUp 会静默依赖一个正在跑的 RabbitMQ（本机恰好有一个时全绿，
        // CI / 别的机器上随机红）。
        assertThat(environment.getProperty("management.health.rabbit.enabled")).isEqualTo("false");
    }

    /**
     * 资源层面的证据：classpath 上解析到的 {@code application.yml} 不能来自测试资源目录。
     * 同名测试资源会排在主资源前面（test-classes 先于 classes），一旦有人再放一份进去，
     * 它就会整体取代主配置 —— 这正是被修掉的那个陷阱。
     */
    @Test
    void noTestResourceShadowsTheMainApplicationYaml() {
        URL yaml = getClass().getClassLoader().getResource("application.yml");

        assertThat(yaml).as("classpath 上必须有主配置 application.yml").isNotNull();
        assertThat(yaml.toString())
                .as("测试 classpath 上不允许再出现同名的 application.yml（它会整体取代主配置）；"
                        + "本地若只是 target/test-classes 里的旧残留，先跑 mvn clean")
                .doesNotContain("test-classes");
    }
}
