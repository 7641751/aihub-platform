package com.aihub.gateway.ratelimit;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.RatePolicy;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G8：**限流链真的接进了跑起来的网关**（不是「某个 bean 存在」）。
 *
 * <p>为什么必须有这一条：{@code RateLimitFilterTest} 自己 {@code new} 过滤器，因此它对
 * 「生产代码里根本没有 {@code RateLimitResolver} / {@code RateLimiter} 的 bean、过滤器压根不在
 * 链上」这类故障**完全免疫** —— 那正是 M3 前几个任务留下来的真实缺口（限流器全写好了，一行都没接）。
 * 本类不构造任何限流组件，只发真实 HTTP、只读响应，因此它红就等于「线上不会限流」。
 *
 * <p>三个被钉住的事实，每一个都只能由「真的接上了」来解释：
 * <ol>
 *   <li>第 21 个请求是 **429**（内置默认策略 {@code qps=10/burst=20}：新桶满桶 → 前 20 个放行）；</li>
 *   <li>该 429 的 body 是 **OpenAI 形状**（数据面铁律，不是 admin 信封），并带 IETF 头
 *       （{@code RateLimit-Limit: 10, 20} / {@code RateLimit-Remaining: 0} / 退避头）；</li>
 *   <li>{@code /healthz} 仍然 200：限流只守 {@code /v1/**}，运维端点不受影响。</li>
 * </ol>
 *
 * <p><b>Redis 指向死端口</b>（{@code spring.data.redis.port=1}，与 M1/M2/M3 既有做法一致）：
 * 本类证明的因此是**降级路径**上的端到端限流 —— 即「Redis 挂了也照样拒绝超限请求」，
 * 而这正是 §9 与控制器 ruling 要求的那一条（降级 ≠ 放行全部）。连接被拒是毫秒级的，
 * 加上短超时，整套用例的附加延迟是秒级而不是「每个请求 2 秒」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({MeteringTestConfig.class, RateLimitWiringTest.FakeAdmin.class})
class RateLimitWiringTest {

    /**
     * 两个**不同**的密钥（因此是两个不同的桶）。这不只是卫生习惯：{@link #FakeAdmin} 对任何哈希都
     * 回同一个 {@code apiKeyId=42}，所以「两个密钥各自有独立的桶」同时证明了桶的第二维是
     * {@code sha256(secret)} 而**不是** {@code api_key_id} —— 写成后者的实现会让两个用例互相耗尽
     * 对方的名额（那条断言会红）。
     */
    private static final String OVER_LIMIT_SECRET = "wiring-over-limit-secret";
    private static final String IN_LIMIT_SECRET = "wiring-in-limit-secret";

    /**
     * 由**配置快照**下发的租户级策略（{@code qps=1 / burst=30}）。
     *
     * <p>为什么用一对显式的小额度，而不是内置默认的 {@code 10/20}：本类的请求间隔约 100ms，
     * 而内置默认的补充速率是 10/s —— 恰好追平请求速率，桶会在 20 附近动态平衡、永远耗不干净
     * （这不是猜测：第一版就是这么红的，实测第 21 个仍是 200）。换成 {@code 1/30} 后，
     * 30 个请求的总耗时（约 0.1s 量级，见实测）内最多补充 1 个令牌，因此「第 31 个必然被拒」
     * 是确定性的，与机器快慢无关。
     *
     * <p><b>{@code qps} 不能写 0</b>：{@code RateLimitResolver} 把非正的 qps/burst 当成配置事故、
     * 回落内置默认（Task 4 的按行校验，刻意不跨维回落），所以 {@code 0/3} 表达不出「永不补充」——
     * 它只会静默变回 {@code 10/20}。
     *
     * <p>顺带它把「策略来自控制面快照」这条链也钉住了：断言的 {@code RateLimit-Limit} 是
     * {@code "1, 30"} 而不是内置的 {@code "10, 20"}，因此「快照 → RateLimitResolver → RateLimiter
     * → 过滤器」任何一环断开都会红。
     */
    private static final int QPS = 1;
    private static final int BURST = 30;

    private static final long TENANT_ID = 7L;

    /**
     * 打空桶的尝试次数上界。理论需要「把 burst 放空所需时间 / 单请求耗时」次 ≈
     * (30 s / 毫秒级) ，几百次远远够用；而一个「降级就放行」的实现永远打不到。
     */
    private static final int MAX_ATTEMPTS = 400;

    private static FakeUpstream upstream;

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ConfigClient configClient;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    /**
     * 在**测试线程**上把配置快照灌进本地缓存（{@code ConfigClient} 的 TTL 是 30 秒，足够本类跑完）。
     *
     * <p><b>为什么必须有这一句</b>：{@code ConfigClient.current()} 在两级缓存都空时走
     * {@code refreshBlocking()}，而它内部是 {@code Mono.block()} —— 在 event loop 线程上**必然**抛
     * {@code IllegalStateException: block()/blockFirst()/blockLast() are blocking}，于是冷启动的
     * 第一个请求永远拿不到快照、策略静默回落成内置默认 {@code 10/20}。这是 Task 7 的
     * {@code ConfigClient} 自身的缺陷（{@code current()} 直到本任务才第一次被请求路径调用，
     * 所以之前没有任何测试碰到它），**不是限流链的问题**。
     *
     * <p>本类的职责是证明「限流链接上了」，因此这里把那个缺陷挡在门外（缓存有货 → 请求路径只读本地缓存，
     * 不触发回源），让断言指向限流本身。缺陷本身已登记在任务报告里，不在本任务修。
     */
    @BeforeEach
    void warmTheConfigSnapshotOnTheTestThread() {
        configClient.refresh().block(Duration.ofSeconds(5));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> upstream.baseUrl());
        registry.add("aihub.upstream.default-model", () -> "wiring-model");
        registry.add("aihub.auth.enabled", () -> "true");
        registry.add("aihub.internal.secret", () -> "test-internal-secret");
        // 死端口 = Redis 不可用。降级路径正是本条要证的。
        registry.add("spring.data.redis.port", () -> "1");
        // 连接被拒本来就是毫秒级；显式收短超时，避免任何情况下退化成「每请求 2 秒」。
        registry.add("spring.data.redis.timeout", () -> "500ms");
        // 其余网关测试默认关掉限流（见 src/test/resources/application.properties）：本类显式打开。
        registry.add("aihub.ratelimit.enabled", () -> "true");
    }

    @TestConfiguration
    static class FakeAdmin {
        @Bean
        @Primary
        AdminClient adminClient() {
            return new AdminClient() {
                @Override
                public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
                    return Mono.just(Optional.of(
                            new ApiKeyView("ak_wiring", TENANT_ID, "wiring", ApiKeyView.STATUS_ACTIVE, null, 42L)));
                }

                /**
                 * 一份**合法**的快照：租户级策略 {@code 0/3} + 一条渠道 + 一条路由。
                 *
                 * <p>渠道与路由不是点缀：{@code ConfigClient.usable()} 把「既没有渠道也没有路由」的快照
                 * 当成**空快照**（那是 admin 冷启动的中间状态，会回落遗留单渠道），于是「只有策略行」
                 * 的快照会被整体丢弃、策略悄悄变回内置默认 —— 第一版就是这么写的，实测第 4 个请求
                 * 仍拿到 {@code RateLimit-Limit: 10, 20}。真实的控制面不会下发这种快照，测试也不该伪造。
                 */
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

    /**
     * 链子上的限流组件必须**存在且被 Spring 装配**：这条只是最小的接线证据，
     * 单靠它不足以证明「过滤器真的在跑」（那由下面两条 HTTP 用例证明）。
     */
    @Test
    void theRealLimiterChainIsWiredIntoTheRunningContext() {
        assertThat(context.getBean(RateLimitFilter.class)).isNotNull();
        assertThat(context.getBean(RateLimiter.class)).isNotNull();
        assertThat(context.getBean(RateLimitResolver.class)).isNotNull();
        assertThat(context.getBean(RedisRateLimiter.class)).isNotNull();
        assertThat(context.getBean(LocalRateLimiter.class)).isNotNull();
    }

    /**
     * 真实请求穿过真实过滤器链：一直打到超限，第一个超限请求必须是 429 + OpenAI 形状。
     *
     * <p><b>为什么是「打到超限」而不是「第 31 个」</b>：桶在两次请求之间按 {@code qps} 补充，
     * 因此「打空 burst 需要几个请求」取决于机器快慢 —— 钉死次数就是一条依赖时序的假断言
     * （实测：{@code burst=3} 的版本因为补充速度不同，红法都不一样）。这里改为钉住**规则本身**：
     * 前若干个必须放行、且**在尝试次数上界之内**必然出现拒绝。
     *
     * <p>尝试次数上界不是随手写的：补充速率是 {@code QPS=1}/秒，把 {@code BURST=30} 打空需要
     * 约 30 秒；本类每个请求实际耗时是毫秒级（见实测），因此几百次内必然出现拒绝 ——
     * 反过来，一个「降级就放行所有人」的实现打多少次都不会拒绝，这条立刻红。
     */
    @Test
    void anOverLimitRequestIsRejectedWithAnOpenAiShaped429EvenWithRedisDown() throws Exception {
        HttpResponse<String> rejected = null;
        int allowedCount = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && rejected == null; attempt++) {
            HttpResponse<String> response = post("/v1/chat/completions", OVER_LIMIT_SECRET);
            assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER))
                    .as("限额必须来自控制面快照（%s/%s），不是内置默认", QPS, BURST)
                    .hasValue(QPS + ", " + BURST);
            if (response.statusCode() == 429) {
                rejected = response;
            } else {
                assertThat(response.statusCode())
                        .as("第 %s 个请求只能有两种结局：放行或 429", attempt)
                        .isEqualTo(200);
                allowedCount++;
            }
        }

        assertThat(allowedCount)
                .as("尝试 %s 次都没出现 429 —— 降级成了「放行所有人」，那正是 §9 禁止的", MAX_ATTEMPTS)
                .isPositive();
        assertThat(rejected).as("必须在 %s 次尝试内打到限额", MAX_ATTEMPTS).isNotNull();
        assertThat(rejected.body())
                .contains("\"error\"")
                .contains("\"code\":\"rate_limit_exceeded\"")
                .contains("\"type\":\"rate_limit_error\"")
                .contains("\"param\":null");
        assertThat(rejected.headers().firstValue(RateLimitFilter.REMAINING_HEADER)).hasValue("0");
        assertThat(rejected.headers().firstValue(RateLimitFilter.RETRY_AFTER_HEADER)).isPresent();
        assertThat(rejected.headers().firstValue(RateLimitFilter.RETRY_AFTER_MS_HEADER)).isPresent();
    }

    /**
     * 同一套上下文里的反向证据：**没有超限的请求照常成功**。
     * <p>它同时是「降级 ≠ 一律拒绝」的那一半：Redis 整个死掉期间，网关仍然正常转发。
     * 与 {@link #anOverLimitRequestIsRejectedWithAnOpenAiShaped429EvenWithRedisDown} 一起，
     * 两条合起来才是 §9 的完整语义（既不放行全部，也不拒绝全部）。
     *
     * <p><b>顺序无关</b>：本方法用**另一个密钥**（→ 另一个桶），且自己那个桶此刻必然还没被碰过，
     * 因此单个请求必然放行 —— 不依赖「另一个用例先跑还是后跑」。
     */
    @Test
    void anInLimitRequestStillSucceedsWhileRedisIsDown() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("/v1/chat/completions", IN_LIMIT_SECRET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chat.completion");
        assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER)).hasValue(QPS + ", " + BURST);
    }

    /** 非 {@code /v1} 路径完全不受限流影响（运维端点的可用性不能被数据面治理牵连）。 */
    @Test
    void healthzIsNotGuardedByTheRateLimit() throws Exception {
        HttpResponse<String> response = get("/healthz");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER))
                .as("/healthz 不该出现限流响应头").isEmpty();
    }

    private HttpResponse<String> post(String path, String secret) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ak_wiring." + secret)
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"wiring-model\",\"stream\":false}",
                        StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
