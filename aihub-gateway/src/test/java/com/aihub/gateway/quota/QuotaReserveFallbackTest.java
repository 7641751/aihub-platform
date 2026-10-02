package com.aihub.gateway.quota;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.QuotaDescriptor;
import com.aihub.common.quota.QuotaDecision;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.testsupport.FakeAdminServer;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Task 14 的**端到端**证据：{@link QuotaFilter} 在「Redis 不可用」时回源 admin 预扣（{@code QUOTA-FALLBACK}）。
 *
 * <p><b>网关测试永远不允许依赖 Docker / 真 Redis</b>（CONVENTIONS §8 item 3）：Redis 因此指向死端口
 * （{@code spring.data.redis.port=1}，本仓库既有的「Redis 不可用」构造法），而**真实 HTTP + 真 HMAC 签名**
 * 由 {@link FakeAdminServer} 承担 —— 本类**不**提供 {@code @Primary AdminClient}，跑的就是
 * {@code AdminClientConfig} 造出来的 {@code AdminClient.Http}（3 秒内部跳预算）。
 *
 * <p>要钉的五件事：
 * <ol>
 *   <li><b>正常路径永不回源</b>：Redis 可用（判定非空）时兜底一次都不该打；</li>
 *   <li><b>兜底失败/拿不到判定仍然放行</b>（裁定 6，D7 延伸）：500、畸形体、超时三种都必须是 200 + 一次
 *       {@code aihub.quota.degraded}，**绝不升级成业务故障**，也**不重复计数**；</li>
 *   <li><b>兜底的「拒绝」仍然是拒绝</b>：admin 说余额不足 ⇒ 429 {@code insufficient_quota}
 *       （降级不得变成绕过配额的手段）；</li>
 *   <li><b>只在「Redis 不可用」这一条分支上试兜底</b>（裁定 2）：本周期不限（D15）与脚本形状异常
 *       （缺陷，不是降级）都**不试**；</li>
 *   <li><b>开关的默认值是「开」</b>（裁定 5）：生产默认由出厂 {@code application.yml} 的
 *       {@code ${AIHUB_QUOTA_FALLBACK_ENABLED:true}} 提供，关掉时一次都不回源。</li>
 * </ol>
 *
 * <p><b>夹具</b>：{@code @Primary} 的 {@link StubResolver}/{@link StubLimiter} 只覆盖「额度来源」与
 * 「Redis 判定结果」两处，兜底链路（过滤器 → 窄接口 → AdminClient.Http → 假 admin）**全是真的**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 刻意**不设** aihub.quota.fallback-enabled：本类要跑的就是「出厂默认 = 开」。
        properties = {"aihub.quota.enabled=true", "aihub.auth.enabled=false"})
@Import({MeteringTestConfig.class, QuotaReserveFallbackTest.StubWiring.class})
class QuotaReserveFallbackTest {

    private static final String RESERVE_PATH = AdminClient.QUOTA_RESERVE_PATH;

    /** 合成密钥（不是真实秘密）：内部跳的签名密钥。 */
    private static final String INTERNAL_SECRET = "t14-fallback-internal-secret";

    /** 跨过网关给内部跳的 {@code responseTimeout(3s)}：这一跳必然以传输故障收场。 */
    private static final long BEYOND_GATEWAY_INTERNAL_HOP_BUDGET_MILLIS = 3_500L;

    /** 「有界」的观测上界：假 admin 迟到 3.5 秒，整个客户端请求仍必须在这个预算内返回。 */
    private static final long BOUNDED_RESPONSE_MILLIS = 7_000L;

    private static final long TENANT = 0L;

    private static final FakeUpstream UPSTREAM = FakeUpstream.start();
    private static final FakeAdminServer ADMIN = FakeAdminServer.start();

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private StubResolver resolver;

    @Autowired
    private StubLimiter limiter;

    @AfterAll
    static void stopStubs() {
        UPSTREAM.stop();
        ADMIN.stop();
    }

    /**
     * 用例之间共享同一个假 admin（Spring 上下文按类缓存）：排一个诱饵响应来证明某条路径**没有**回源，
     * 会把诱饵留在队列里污染下一个用例，因此每个用例都必须从干净的队列与计数器开始。
     */
    @BeforeEach
    void reset() {
        ADMIN.reset();
        UPSTREAM.clearLastRequest();
        resolver.limited = true;
        limiter.decision = null;      // 默认 = 「Redis 这一级不可用」，兜底链路的入口
        limiter.scriptError = null;
        limiter.failWith = null;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> UPSTREAM.baseUrl());
        registry.add("aihub.upstream.default-model", () -> "t14-model");
        registry.add("aihub.internal.secret", () -> INTERNAL_SECRET);
        // 真实 AdminClient.Http 打到这里（真 HTTP + 真 HMAC 签名）。
        registry.add("aihub.auth.admin-base-url", () -> ADMIN.baseUrl());
        // Redis 不可用：本类要证的正是「降级 → 兜底」这条链。
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("spring.data.redis.timeout", () -> "500ms");
        registry.add("aihub.config.local-ttl", () -> "60s");
    }

    // ------------------------------------------------------------------ ① 正常路径不回源

    @Test
    void redisUpNeverConsultsTheFallback() throws Exception {
        limiter.decision = new QuotaDecision(true, 90L, -1L);
        UPSTREAM.enqueueJson(200, FakeUpstream.completionJson());
        double degradedBefore = degraded();

        HttpResponse<String> response = post();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chat.completion");
        assertThat(ADMIN.requestCountForPath(RESERVE_PATH))
                .as("Redis 正常时兜底一次都不该打 —— 正常路径上多一跳会把每个请求的延迟绑到 admin 上")
                .isZero();
        assertThat(degraded() - degradedBefore).as("正常放行不是降级").isZero();
    }

    // ------------------------------------------------------------------ ② 兜底失败仍放行（裁定 6）

    /**
     * **本任务的验收判据**：兜底**自己**失败（admin 回 500）时仍然放行 + 计数 degraded。
     * 判别力：把「兜底失败」写成「拒绝请求」⇒ 本用例立刻红（那等于把一次记账组件的抖动变成对客户端的 429）。
     */
    @Test
    void whenTheReserveCallItselfFailsTheGatewayStillAllowsTheRequest() throws Exception {
        ADMIN.enqueueJsonForPath(RESERVE_PATH, 500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\"}");
        UPSTREAM.enqueueJson(200, FakeUpstream.completionJson());
        double degradedBefore = degraded();

        HttpResponse<String> response = post();

        assertThat(response.statusCode()).as("D7：兜底也坏了仍然放行，绝不升级成业务故障").isEqualTo(200);
        assertThat(response.body()).contains("chat.completion");
        assertThat(degraded() - degradedBefore)
                .as("放行必须伴随一次 degraded 计数（且只计一次，不重复计数）").isEqualTo(1.0);
        assertThat(ADMIN.requestCountForPath(RESERVE_PATH))
                .as("前提检查：兜底确实被尝试过，否则本用例证明不了「尝试了但失败了仍放行」").isEqualTo(1);
    }

    /** 畸形响应（2xx 但没有布尔 {@code allowed}）是「判不了」，不是「拒绝」——不能被误翻成 429。 */
    @Test
    void aMalformedReserveResponseFailsOpenRatherThanDenying() throws Exception {
        ADMIN.enqueueJsonForPath(RESERVE_PATH, 200, "{\"code\":\"OK\",\"message\":\"success\",\"data\":{}}");
        UPSTREAM.enqueueJson(200, FakeUpstream.completionJson());
        double degradedBefore = degraded();

        HttpResponse<String> response = post();

        assertThat(response.statusCode())
                .as("平台故障不得伪装成「配额用尽」（与 D16 同一纪律的镜像）").isEqualTo(200);
        assertThat(degraded() - degradedBefore).isEqualTo(1.0);
    }

    /** 兜底调用**有界**：假 admin 迟到 3.5 秒（跨过内部跳的 3 秒预算）⇒ 请求仍必须在预算内返回。 */
    @Test
    void aStalledReserveCallIsBoundedAndStillAllowsTheRequest() throws Exception {
        ADMIN.enqueueStalledJsonForPath(RESERVE_PATH, 200, allowEnvelope(700L),
                BEYOND_GATEWAY_INTERNAL_HOP_BUDGET_MILLIS);
        UPSTREAM.enqueueJson(200, FakeUpstream.completionJson());
        double degradedBefore = degraded();

        long start = System.nanoTime();
        HttpResponse<String> response = post();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elapsedMillis)
                .as("兜底调用必须有界（WebClient 的 responseTimeout 3s + 窄接口的 block 预算）："
                        + "假 admin 迟到 %d ms，整个请求仍必须在这个预算内返回，实际 %d ms",
                        BEYOND_GATEWAY_INTERNAL_HOP_BUDGET_MILLIS, elapsedMillis)
                .isLessThan(BOUNDED_RESPONSE_MILLIS);
        assertThat(degraded() - degradedBefore).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------ ③ 兜底的拒绝仍然是拒绝

    /** 降级是为了「不因为组件坏了而拒绝」，**不是**为了「绕过配额」：兜底说余额不足 ⇒ 429。 */
    @Test
    void aFallbackDenialIsHonouredAsInsufficientQuota() throws Exception {
        ADMIN.enqueueJsonForPath(RESERVE_PATH, 200, denyEnvelope());
        double degradedBefore = degraded();

        HttpResponse<String> response = post();

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body())
                .contains("\"code\":\"insufficient_quota\"")
                .contains("\"type\":\"insufficient_quota\"")
                .doesNotContain("rate_limit_exceeded");
        assertThat(UPSTREAM.requestCount()).as("被拒的请求不许到达上游").isZero();
        assertThat(degraded() - degradedBefore)
                .as("「拒绝」是业务结果，不是降级：degraded 不该被记（否则「Redis 挂了」的计数会被拒绝污染）")
                .isZero();
    }

    /** 兜底说还有额度 ⇒ 与 fail-open 同效（放行 + degraded）。 */
    @Test
    void aFallbackAllowanceLetsTheRequestThroughAndCountsADegrade() throws Exception {
        ADMIN.enqueueJsonForPath(RESERVE_PATH, 200, allowEnvelope(700L));
        UPSTREAM.enqueueJson(200, FakeUpstream.completionJson());
        double degradedBefore = degraded();

        HttpResponse<String> response = post();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chat.completion");
        assertThat(ADMIN.requestCountForPath(RESERVE_PATH)).isEqualTo(1);
        assertThat(degraded() - degradedBefore)
                .as("Redis 不可用（无论兜底放行还是失败）都必须计一次 degraded").isEqualTo(1.0);
    }

    // ------------------------------------------------------------------ ④ 只在降级分支试兜底（裁定 2）

    /** 本周期**不限**（D15）没有预扣可言 ⇒ 不试兜底（`limits.isEmpty()` 处已提前返回）。 */
    @Test
    void anUnlimitedTenantNeverConsultsTheFallback() throws Exception {
        resolver.limited = false;
        UPSTREAM.enqueueJson(200, FakeUpstream.completionJson());
        double degradedBefore = degraded();

        HttpResponse<String> response = post();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ADMIN.requestCountForPath(RESERVE_PATH))
                .as("不限 = 没有可兜底的预扣").isZero();
        assertThat(degraded() - degradedBefore).as("不限不是降级").isZero();
    }

    /**
     * 脚本**形状异常**是缺陷（{@code aihub.quota.script_error}），**不是**降级 ⇒ 不试兜底。
     * 判别力：让 script_error 路径也去试兜底 ⇒ 本用例红（否则「Lua 写错了」会被藏进「Redis 挂了」里，
     * 而 admin 跑的是同一份 Lua，必然也失败）。
     */
    @Test
    void anUnexpectedScriptShapeNeverConsultsTheFallback() throws Exception {
        limiter.scriptError = new IllegalStateException("配额预扣脚本返回形状异常：期望 3 个元素，实际 2 个");
        UPSTREAM.enqueueJson(200, FakeUpstream.completionJson());
        double scriptErrorsBefore = scriptErrors();
        double degradedBefore = degraded();

        HttpResponse<String> response = post();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(scriptErrors() - scriptErrorsBefore)
                .as("形状异常落 script_error（缺陷）").isEqualTo(1.0);
        assertThat(degraded() - degradedBefore).as("形状异常不是降级").isZero();
        assertThat(ADMIN.requestCountForPath(RESERVE_PATH))
                .as("脚本形状异常时 admin 必然也失败 ⇒ 不试兜底（否则缺陷被藏进降级里）").isZero();
    }

    // ------------------------------------------------------------------ ⑤ 开关默认值（裁定 5）

    /**
     * {@code aihub.quota.fallback-enabled} 的默认值必须真的是「开」。
     *
     * <p>这里照抄 {@code ConfigSubscriberTest.theProductionDefaultOfTheInvalidateSubscriptionIsOn} 的口径：
     * ① 读**出厂** {@code src/main/resources/application.yml} 的那一行原文（环境变量名 + 默认值两者都要对）；
     * ② 再把出厂 yml 喂进 {@link ApplicationContextRunner}，断言**解析后的行为** —— 不给环境变量时兜底会回源
     * admin，{@code AIHUB_QUOTA_FALLBACK_ENABLED=false} 时一次都不回源。
     *
     * <p>另加一条「连 yml 也没有」的最小上下文：它单独钉住 {@code QuotaConfig} 里
     * {@code @Value("${aihub.quota.fallback-enabled:true}")} 的默认值（与出厂 yml 是两个独立的口子，
     * 谁被改成 false 都会让本用例红）。
     */
    @Test
    void theFallbackSwitchDefaultsToOnAndCanBeTurnedOff() throws Exception {
        var shipped = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(shipped).as("主配置必须能被读到").isNotEmpty();
        assertThat(String.valueOf(shipped.get(0).getProperty("aihub.quota.fallback-enabled")))
                .as("生产默认必须解析为 true，且环境变量名必须是 AIHUB_QUOTA_FALLBACK_ENABLED —— "
                        + "一个 typo 就能在全绿的测试下静默关掉兜底")
                .isEqualTo("${AIHUB_QUOTA_FALLBACK_ENABLED:true}");

        // ① 出厂 yml，不给环境变量 ⇒ 默认开 ⇒ 兜底回源 admin。
        RecordingAdmin byShippedYml = new RecordingAdmin();
        QuotaFallback fromShippedYml = fallbackFrom(new ApplicationContextRunner()
                .withInitializer(context -> shipped
                        .forEach(source -> context.getEnvironment().getPropertySources().addLast(source)))
                .withUserConfiguration(QuotaConfig.class)
                .withBean(AdminClient.class, () -> byShippedYml)
                .withBean(ConfigClient.class, () -> mock(ConfigClient.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new));
        assertThat(fromShippedYml.reserveFallback(TENANT, 100L))
                .as("出厂 yml 的默认值必须真的被绑定成「开」，而不只是文本里写了 true").isPresent();
        assertThat(byShippedYml.calls()).isEqualTo(1);

        // ② 出厂 yml + AIHUB_QUOTA_FALLBACK_ENABLED=false ⇒ 关 ⇒ 一次都不回源。
        RecordingAdmin whenOff = new RecordingAdmin();
        QuotaFallback disabled = fallbackFrom(new ApplicationContextRunner()
                .withInitializer(context -> shipped
                        .forEach(source -> context.getEnvironment().getPropertySources().addLast(source)))
                .withPropertyValues("AIHUB_QUOTA_FALLBACK_ENABLED=false")
                .withUserConfiguration(QuotaConfig.class)
                .withBean(AdminClient.class, () -> whenOff)
                .withBean(ConfigClient.class, () -> mock(ConfigClient.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new));
        assertThat(disabled.reserveFallback(TENANT, 100L))
                .as("关掉兜底后拿不到判定 ⇒ 调用方走 D7 放行").isEmpty();
        assertThat(whenOff.calls()).as("关掉兜底后一次都不许回源 admin").isZero();

        // ③ 连 yml 都没有（最小上下文）⇒ QuotaConfig 里 @Value 的默认值也必须是「开」。
        RecordingAdmin withoutYml = new RecordingAdmin();
        QuotaFallback valueDefault = fallbackFrom(new ApplicationContextRunner()
                .withUserConfiguration(QuotaConfig.class)
                .withBean(AdminClient.class, () -> withoutYml)
                .withBean(ConfigClient.class, () -> mock(ConfigClient.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new));
        assertThat(valueDefault.reserveFallback(TENANT, 100L))
                .as("@Value 的默认值（没有 yml 时的口子）也必须是「开」").isPresent();
        assertThat(withoutYml.calls()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 脚手架

    private HttpResponse<String> post() throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"t14-model\",\"stream\":false}", StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private double degraded() {
        return meterRegistry.counter(QuotaFilter.DEGRADED_METRIC).count();
    }

    private double scriptErrors() {
        return meterRegistry.counter(QuotaFilter.SCRIPT_ERROR_METRIC).count();
    }

    private static QuotaFallback fallbackFrom(ApplicationContextRunner runner) {
        AtomicReference<QuotaFallback> bean = new AtomicReference<>();
        runner.run(context -> {
            assertThat(context).as("QuotaConfig 必须能在这个最小上下文里装配成功").hasNotFailed();
            bean.set(context.getBean(QuotaFallback.class));
        });
        return bean.get();
    }

    private static String allowEnvelope(long remainingTokens) {
        return "{\"code\":\"OK\",\"message\":\"success\",\"data\":{\"allowed\":true,\"remainingTokens\":"
                + remainingTokens + ",\"remainingRequests\":-1}}";
    }

    private static String denyEnvelope() {
        return "{\"code\":\"OK\",\"message\":\"success\",\"data\":{\"allowed\":false,\"remainingTokens\":0,"
                + "\"remainingRequests\":-1}}";
    }

    /** 只覆盖「额度来源」与「Redis 判定结果」两处；兜底链路全是真的（见类注释）。 */
    @TestConfiguration
    static class StubWiring {
        @Bean
        @Primary
        StubResolver stubResolver() {
            return new StubResolver();
        }

        @Bean
        @Primary
        StubLimiter stubLimiter() {
            return new StubLimiter();
        }
    }

    /** 可开关的额度来源替身：{@code limited=false} 时按 D15 回报「不限」。 */
    static final class StubResolver extends QuotaResolver {
        private volatile boolean limited = true;

        StubResolver() {
            super(ConfigSnapshot::empty);
        }

        @Override
        public Optional<QuotaDescriptor> resolve(long tenantId, String period) {
            return limited ? Optional.of(new QuotaDescriptor(tenantId, period, 100L, 0L)) : Optional.empty();
        }
    }

    /** 可编排的判定替身：{@code decision == null} = 「Redis 这一级不可用」。 */
    static final class StubLimiter implements QuotaLimiter {
        private volatile QuotaDecision decision;
        private volatile RuntimeException failWith;
        private volatile IllegalStateException scriptError;

        @Override
        public QuotaDecision reserve(QuotaDescriptor limits, long estimatedTokens) {
            if (scriptError != null) {
                throw scriptError;
            }
            if (failWith != null) {
                throw failWith;
            }
            return decision;
        }

        @Override
        public void adjust(long tenantId, String period, long estimatedTokens, long actualTokens) {
            // 不碰 Redis（网关测试不允许依赖 Docker）。
        }
    }

    /** 只记录「兜底有没有打到 admin」的替身（ApplicationContextRunner 用）。 */
    static final class RecordingAdmin implements AdminClient {
        private final AtomicInteger calls = new AtomicInteger();

        int calls() {
            return calls.get();
        }

        @Override
        public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
            return Mono.just(Optional.empty());
        }

        @Override
        public Mono<QuotaDecision> reserveQuota(long tenantId, long estimatedTokens) {
            calls.incrementAndGet();
            return Mono.just(new QuotaDecision(true, 1L, 1L));
        }
    }
}
