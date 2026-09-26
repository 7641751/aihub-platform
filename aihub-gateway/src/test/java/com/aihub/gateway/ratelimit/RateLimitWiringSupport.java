package com.aihub.gateway.ratelimit;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.RatePolicy;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 限流**端到端**用例的公共装配：真实 HTTP + 真实过滤器链 + {@code @Primary} 假 admin +
 * 真 Lettuce 指向死端口（Redis 不可用 = 降级路径）。
 *
 * <p>抽出来只为一件事：{@code RateLimitWiringTest}（预热缓存）与 {@code RateLimitColdStartTest}
 * （**不**预热）必须是两个测试类 —— Spring 测试上下文是按类缓存的，而「冷缓存」只有在
 * <b>上下文从没被别的类调用过 {@code ConfigClient.current()}</b> 时才成立。把两者放进同一个类
 * 只能靠方法顺序去赌，那是一条会在将来随机红的假断言。
 *
 * <p>假 admin 与死端口 Redis 的形态在 {@link #FakeAdmin} 与 {@link #properties} 上，
 * 两个子类的差别**只有**「要不要预热缓存」这一件事。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({MeteringTestConfig.class, RateLimitWiringSupport.FakeAdmin.class})
abstract class RateLimitWiringSupport {

    /** 假 admin 给任何哈希都回这同一个租户。 */
    static final long TENANT_ID = 7L;

    /** 两个**不同**的密钥（因此是两个不同的桶）—— 「桶的第二维是 sha256 而不是 api_key_id」靠它证明。 */
    static final String OVER_LIMIT_SECRET = "wiring-over-limit-secret";
    static final String IN_LIMIT_SECRET = "wiring-in-limit-secret";

    /** 由**配置快照**下发的租户级策略（{@code qps=1 / burst=30}），刻意不同于内置默认 {@code 10/20}。 */
    static final int QPS = 1;
    static final int BURST = 30;

    /**
     * 打空桶的尝试次数上界。补充速率是 {@code QPS=1}/秒，把 {@code BURST=30} 打空理论上需要约 30 秒；
     * 本类每个请求是毫秒级，因此几百次内必然出现拒绝 —— 而一个「降级就放行所有人」的实现永远打不到。
     */
    static final int MAX_ATTEMPTS = 400;

    private static FakeUpstream upstream;

    /** 子类需要预置上游响应时用（默认队列空时上游回 200 + {@code {}}）。 */
    static FakeUpstream upstream() {
        return upstream;
    }

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
        registry.add("aihub.upstream.default-model", () -> "wiring-model");
        registry.add("aihub.auth.enabled", () -> "true");
        registry.add("aihub.internal.secret", () -> "test-internal-secret");
        // 死端口 = Redis 不可用。降级路径正是这些用例要证的。
        registry.add("spring.data.redis.port", () -> "1");
        // 连接被拒本来就是毫秒级；显式收短超时，避免任何情况下退化成「每请求 2 秒」。
        registry.add("spring.data.redis.timeout", () -> "500ms");
        // 一个成功的快照必须能覆盖本类的全部用例（否则「冷启动拿到策略」会在跑到一半时过期）。
        registry.add("aihub.config.local-ttl", () -> "60s");
        // 其余网关测试默认关掉限流（见 src/test/resources/application.properties）：本类显式打开。
        registry.add("aihub.ratelimit.enabled", () -> "true");
    }

    /**
     * 一份**合法**的快照：租户级策略 {@code QPS/BURST} + 一条渠道 + 一条路由。
     *
     * <p>渠道与路由不是点缀：{@code ConfigClient.usable()} 把「既没有渠道也没有路由」的快照当成
     * **空快照**（那是 admin 冷启动的中间状态），于是「只有策略行」的快照会被整体丢弃、策略悄悄
     * 变回内置默认 —— 实测就是这么红的。真实的控制面不会下发这种快照，测试也不该伪造。
     *
     * <p>假 admin 对任何哈希都回同一个 {@code apiKeyId=42}，所以「两个密钥各有独立的桶」同时
     * 证明了桶的第二维是 {@code sha256(secret)} 而**不是** {@code api_key_id}。
     */
    @TestConfiguration
    static class FakeAdmin {
        @Bean
        @Primary
        AdminClient adminClient() {
            return new AdminClient() {
                @Override
                public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
                    return Mono.just(Optional.of(new ApiKeyView("ak_wiring", TENANT_ID, "wiring",
                            ApiKeyView.STATUS_ACTIVE, null, 42L)));
                }

                @Override
                public Mono<Optional<ConfigSnapshot>> configSnapshot() {
                    ChannelDescriptor channel = new ChannelDescriptor(11L, "wiring-channel",
                            upstream.baseUrl(), null, 0, 30_000, ChannelDescriptor.STATUS_ACTIVE, 100, 0);
                    ModelRouteDescriptor route = new ModelRouteDescriptor("wiring-model", 11L, 100, 0,
                            ModelRouteDescriptor.STATUS_ACTIVE);
                    return Mono.just(Optional.of(new ConfigSnapshot(1L, 2L, List.of(channel), List.of(route),
                            List.of(new RatePolicy(TENANT_ID, null, QPS, BURST)), "wiring-model")));
                }
            };
        }
    }

    final HttpResponse<String> post(String path, String secret) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ak_wiring." + secret)
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"wiring-model\",\"stream\":false}",
                        StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    final HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
