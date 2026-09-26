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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 限流**端到端**用例的公共装配：真实 HTTP + 真实过滤器链 + {@code @Primary} 假 admin +
 * 真 Lettuce 指向死端口（Redis 不可用 = 降级路径）。
 *
 * <p>抽出来只为一件事：限流的端到端用例各自需要**不同的上下文级配置**，而 Spring 的测试上下文
 * 是按类缓存的 —— 把不同配置的用例塞进同一个类只能靠方法顺序去赌，那是一条将来会随机红的假断言。
 * 当前三个子类（差别只落在**上下文级配置 + 要不要预热缓存**上，装配部分共用这里）：
 *
 * <ul>
 *   <li>{@link RateLimitWiringTest} —— 预热配置缓存，证「缓存有货时限流链正确」，并覆盖
 *       <b>流式（SSE）</b>路径；</li>
 *   <li>{@link RateLimitColdStartTest} —— 不预热 + {@code @DirtiesContext}(BEFORE_CLASS)，
 *       证「请求路径上从来没人预热过」时也用上快照策略；</li>
 *   <li>{@link RateLimitColdCacheAuthDisabledTest} —— 冷配置缓存 + {@code aihub.auth.enabled=false}
 *       + 异步控制面（{@link #FAKE_ADMIN_LATENCY_PROPERTY}），让判定**必然留在 event loop 上**；
 *       这是「冷缓存 + event loop 阻塞导致策略被静默忽略」那个症状唯一的判别性 RED
 *       （见任务报告；同步假 admin 会把那个症状掩盖掉）。</li>
 * </ul>
 *
 * <p><b>{@code aihub.auth.enabled} 刻意不在这里写死</b>：动态属性源的优先级高于
 * {@code @SpringBootTest(properties = ...)} 的内联属性，写死 {@code true} 会让上面第三个子类
 * 永远拿不到 {@code false}。主配置的默认值本来就是 {@code true}（见
 * {@code application.yml} 的 {@code AIHUB_AUTH_ENABLED:true}），因此前两个子类不需要这个键。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({MeteringTestConfig.class, RateLimitWiringSupport.FakeAdmin.class})
abstract class RateLimitWiringSupport {

    /** 假 admin 给任何哈希都回这同一个租户。 */
    static final long TENANT_ID = 7L;

    /**
     * 匿名桶（鉴权关闭 / 没有 {@code ApiKeyView}）用的租户维度。快照里给它一条**独立的**策略行
     * （同样是 {@code QPS/BURST}），这样「鉴权关闭时配置的策略也必须生效」可以被断言成
     * {@code 1, 30} 而不是内置默认 {@code 10, 20} —— 没有这一行，那条用例即使实现正确也只能看到
     * 内置默认，判别力为零。
     */
    static final long ANONYMOUS_TENANT_ID = 0L;

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
        registry.add("aihub.internal.secret", () -> "test-internal-secret");
        // 死端口 = Redis 不可用。降级路径正是这些用例要证的。
        registry.add("spring.data.redis.port", () -> "1");
        // 连接被拒本来就是毫秒级；显式收短超时，避免任何情况下退化成「每请求 2 秒」。
        registry.add("spring.data.redis.timeout", () -> "500ms");
        // 一个成功的快照必须能覆盖本类的全部用例（否则「冷启动拿到策略」会在跑到一半时过期）。
        registry.add("aihub.config.local-ttl", () -> "60s");
        // 其余网关测试默认关掉限流（见 src/test/resources/application.properties）：本类显式打开。
        registry.add("aihub.ratelimit.enabled", () -> "true");
        // aihub.auth.enabled **不在这里**：动态属性源优先于子类的 @SpringBootTest(properties=...)
        // 内联属性，写死会让「冷缓存 + 鉴权关闭」那个子类拿不到 false。主配置默认就是 true。
    }

    /**
     * 一份**合法**的快照：租户级策略 {@code QPS/BURST}（租户 {@link #TENANT_ID} 与匿名租户
     * {@link #ANONYMOUS_TENANT_ID} 各一条）+ 一条渠道 + 一条路由。
     *
     * <p>两条策略行不是冗余：鉴权打开时租户是 {@code 7}、鉴权关闭时是 {@code 0}，两个子类要证的是
     * 同一件事（配置的策略必须生效），因此两行都必须给出 {@code QPS/BURST}（都与内置默认
     * {@code 10/20} 不同，判别力才存在）。
     *
     * <p>渠道与路由不是点缀：{@code ConfigClient.usable()} 把「既没有渠道也没有路由」的快照当成
     * **空快照**（那是 admin 冷启动的中间状态），于是「只有策略行」的快照会被整体丢弃、策略悄悄
     * 变回内置默认 —— 实测就是这么红的。真实的控制面不会下发这种快照，测试也不该伪造。
     */
    static ConfigSnapshot wiringSnapshot() {
        ChannelDescriptor channel = new ChannelDescriptor(11L, "wiring-channel",
                upstream.baseUrl(), null, 0, 30_000, ChannelDescriptor.STATUS_ACTIVE, 100, 0);
        ModelRouteDescriptor route = new ModelRouteDescriptor("wiring-model", 11L, 100, 0,
                ModelRouteDescriptor.STATUS_ACTIVE);
        return new ConfigSnapshot(1L, 2L, List.of(channel), List.of(route),
                List.of(new RatePolicy(TENANT_ID, null, QPS, BURST),
                        new RatePolicy(ANONYMOUS_TENANT_ID, null, QPS, BURST)),
                "wiring-model");
    }

    /**
     * **假 admin 控制面往返的模拟时延（毫秒）**，{@code 0} = 同步、立即返回。
     *
     * <p>为什么夹具需要这个开关：真实的 {@code AdminClient.Http.configSnapshot()} 是 WebClient，
     * **异步**（结果不可能在订阅的同一拍到达）；而同步的 {@code Mono.just} 会在
     * {@code ConfigClient.refreshBlocking()} 的 {@code block()} 抛出「非阻塞线程不能 block」**之前**
     * 就把回源跑完并写进本地缓存，于是「冷缓存 + event loop 上同步判定」那个症状被夹具本身掩盖
     * （实测：诊断这类问题的用例在修复前也是绿的）。需要那个真实形状的子类把这个值调大
     * （见 {@code RateLimitColdCacheAuthDisabledTest}）；其余子类保持 {@code 0}，
     * 让它们与「控制面时延」无关、跑得更快。
     */
    static final String FAKE_ADMIN_LATENCY_PROPERTY = "aihub.test.fake-admin-latency";

    /**
     * **同步**的假 admin：任何哈希都回同一个 {@code apiKeyId=42}（所以「两个密钥各有独立的桶」同时
     * 证明了桶的第二维是 {@code sha256(secret)} 而**不是** {@code api_key_id}）。
     * {@link #FAKE_ADMIN_LATENCY_PROPERTY} 大于 0 时改为异步返回（模拟真实 WebClient 控制面）。
     */
    @TestConfiguration
    static class FakeAdmin {
        @Bean
        @Primary
        AdminClient adminClient(@Value("${" + FAKE_ADMIN_LATENCY_PROPERTY + ":0}") long latencyMillis) {
            return new AdminClient() {
                @Override
                public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
                    return Mono.just(Optional.of(new ApiKeyView("ak_wiring", TENANT_ID, "wiring",
                            ApiKeyView.STATUS_ACTIVE, null, 42L)));
                }

                @Override
                public Mono<Optional<ConfigSnapshot>> configSnapshot() {
                    if (latencyMillis <= 0) {
                        return Mono.just(Optional.of(wiringSnapshot()));
                    }
                    // Mono.delay 落在 parallel 调度器上：订阅当时不可能有结果 —— 与真实控制面一致。
                    return Mono.delay(Duration.ofMillis(latencyMillis))
                            .map(ignored -> Optional.of(wiringSnapshot()));
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

    /**
     * **不带任何凭据**的请求。只有 {@code aihub.auth.enabled=false} 的子类用它：没有
     * {@code ApiKeyView} ⇒ 租户 {@link #ANONYMOUS_TENANT_ID} + 匿名桶（那正是那条用例要的维度）。
     */
    final HttpResponse<String> postAnonymous(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"wiring-model\",\"stream\":false}",
                        StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * **流式**（{@code stream:true} + {@code Accept: text/event-stream}）请求，与 {@link #post}
     * 同一套装配与鉴权。返回**原始字节流**（不是字符串）：调用方必须能逐帧读，才能断言
     * 「限流开启时中继仍然逐帧 flush」，而不是只看到「最后字节都对」。
     */
    final HttpResponse<InputStream> postSse(String path, String secret) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer ak_wiring." + secret)
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"wiring-model\",\"stream\":true}",
                        StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    final HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
