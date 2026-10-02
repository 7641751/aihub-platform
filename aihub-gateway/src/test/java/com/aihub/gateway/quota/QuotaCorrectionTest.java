package com.aihub.gateway.quota;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.common.quota.QuotaKeys;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.meter.MeteringProperties;
import com.aihub.gateway.meter.MeteringPublisher;
import com.aihub.gateway.relay.ChannelKeyDecryptor;
import com.aihub.gateway.relay.ChatRelayController;
import com.aihub.gateway.route.ChannelCircuitBreaker;
import com.aihub.gateway.route.RouteResolver;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.upstream.UpstreamClientFactory;
import com.aihub.gateway.upstream.UpstreamProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实际校正的两半：
 * <ol>
 *   <li><b>算术与关联存储</b>（纯单元）：真实用量取自 {@code MeteringEvent.totalTokens()}；estimate
 *       取自 {@link QuotaReservationRegistry}（过滤器写入、这里**消费即移除**）；缺失条目只计数不崩；
 *       {@code adjust} 每请求**恰一次**；失败只计数不抛。</li>
 *   <li><b>控制器两个终端发布点都校正</b>（13b 裁定 1）：提前终止（{@code model_not_found}，
 *       不经过 {@code doFinally}）与正常收尾（{@code doFinally}）都必须调用校正器。若只改
 *       {@code doFinally}，每个 {@code model_not_found} 请求会**永久吃掉一份估算配额**。</li>
 * </ol>
 *
 * <p><b>网关测试不依赖 Docker</b>（CONVENTIONS §8 item 3）：与 Redis 交互只以 Mockito mock 钉
 * **调用约定**（键布局 + 增量符号）；「桶里的最终值等于真实用量」由 admin 侧真 Redis 用例负责
 * （同键布局，见 {@code QuotaPreDeductionIntegrationTest} 所在模块）。
 */
class QuotaCorrectionTest {

    private static final String PERIOD = "202609";
    private static final long TENANT = 1L;
    private static final long ESTIMATE = 100L;

    private static FakeUpstream upstream;

    private QuotaReservationRegistry registry;
    private RecordingLimiter limiter;
    private QuotaCorrector corrector;
    private SimpleMeterRegistry meters;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void setUp() {
        QuotaConfigProperties properties = new QuotaConfigProperties(true, 65536, Duration.ofMinutes(2), 1_000);
        registry = new QuotaReservationRegistry(properties);
        limiter = new RecordingLimiter();
        meters = new SimpleMeterRegistry();
        corrector = new QuotaCorrector(registry, limiter, properties, meters);
    }

    // --- 算术与关联存储 ----------------------------------------------------------------

    @Test
    void theRegisteredEstimateIsUsedAndTheAdjustmentHappensExactlyOnce() {
        registry.write("req-1", reservation(false));

        corrector.correct(event("req-1", 1, 2, 3));

        assertThat(limiter.adjustments).hasSize(1);
        assertThat(limiter.adjustments.get(0)).isEqualTo(new Adjustment(TENANT, PERIOD, ESTIMATE, 3L));
    }

    @Test
    void theActualTokensComeFromTotalTokensNotTheSumOfParts() {
        registry.write("req-1", reservation(false));

        // prompt=4 / completion=5 / total=9：与两分量之和恰好一致，但**取的是 total**。
        corrector.correct(event("req-1", 4, 5, 9));

        assertThat(limiter.adjustments.get(0).actual()).isEqualTo(9L);
    }

    @Test
    void theReservationIsRemovedAfterItIsConsumed() {
        registry.write("req-1", reservation(false));

        corrector.correct(event("req-1", 1, 2, 3));

        assertThat(registry.consume("req-1")).as("消费即移除（adjust 非幂等，留条目会双重扣账）").isNull();
        assertThat(registry.size()).isZero();

        // 第二次校正：条目已不在 ⇒ 只计数、**绝不再调 adjust**。
        corrector.correct(event("req-1", 1, 2, 3));
        assertThat(meters.counter(QuotaCorrector.MISSING_METRIC).count()).isEqualTo(1.0);
        assertThat(limiter.adjustments).as("adjust 非幂等：重复校正不得再动作").hasSize(1);
    }

    @Test
    void aMissingReservationOnlyCountsAndDoesNotThrow() {
        corrector.correct(event("never-reserved", 1, 1, 2));

        assertThat(meters.counter(QuotaCorrector.MISSING_METRIC).count()).isEqualTo(1.0);
        assertThat(limiter.adjustments).isEmpty();
    }

    @Test
    void aDegradedReservationIsConsumedButNotAdjusted() {
        registry.write("req-1", reservation(true));

        corrector.correct(event("req-1", 1, 2, 3));

        assertThat(limiter.adjustments)
                .as("degraded = 没有发生预扣：校正会把从未压掉的额度补进桶里").isEmpty();
        assertThat(registry.consume("req-1")).as("条目仍被消费掉").isNull();
        assertThat(meters.find(QuotaCorrector.MISSING_METRIC).counter())
                .as("降级条目被消费掉 ⇒ 不算「缺失」").isNull();
    }

    @Test
    void anErrorEventCorrectsTheEstimateBackToZero() {
        registry.write("req-1", reservation(false));

        corrector.correct(event("req-1", 0, 0, 0));

        assertThat(limiter.adjustments.get(0).actual())
                .as("失败请求的 total=0 ⇒ 差额 = 0 − estimate（把预扣全额退回）").isZero();
    }

    @Test
    void anAdjustFailureIsCountedAndNeverThrownIntoTheRequestPath() {
        registry.write("req-1", reservation(false));
        limiter.failWith = new RuntimeException("Redis 写失败");

        corrector.correct(event("req-1", 1, 2, 3));   // 不得抛出

        assertThat(meters.counter(QuotaCorrector.FAILED_METRIC).count()).isEqualTo(1.0);
    }

    /** 校正器退出时配额关闭：**不**消费、**不**计缺失（否则关掉配额会把 MISSING 顶到失真）。 */
    @Test
    void whenQuotaIsDisabledTheCorrectorDoesNothing() {
        QuotaConfigProperties disabled = new QuotaConfigProperties(false, 65536, Duration.ofMinutes(2), 1_000);
        QuotaCorrector off = new QuotaCorrector(registry, limiter, disabled, meters);

        off.correct(event("req-1", 1, 2, 3));

        assertThat(limiter.adjustments).isEmpty();
        assertThat(meters.find(QuotaCorrector.MISSING_METRIC).counter()).isNull();
    }

    // --- 差额的符号与大小（mock Redis，不依赖 Docker） ------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void adjustSendsTheSignedDeltaToRedisWithThePinnedKeyLayout() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        RedisQuotaLimiter worker = new RedisQuotaLimiter(redis);
        String key = QuotaKeys.bucketKey(TENANT, PERIOD);

        worker.adjust(TENANT, PERIOD, 100L, 3L);
        verify(hash).increment(key, QuotaKeys.FIELD_TOKENS, -97L);   // 真实 < 估算 → 退回

        clearInvocations(hash);
        worker.adjust(TENANT, PERIOD, 3L, 100L);
        verify(hash).increment(key, QuotaKeys.FIELD_TOKENS, 97L);    // 真实 > 估算 → 补扣
    }

    // --- 两个终端发布点都校正（13b 裁定 1） ----------------------------------------------

    /**
     * **提前终止路径**：没有候选渠道 → 404 {@code model_not_found}，在 {@code doFinally} 之前就 return。
     * {@code QuotaFilter} 此时已经预扣，因此这条路径**必须**校正，否则每个这种请求永久吃掉一份估算配额。
     * 判别性：删掉 {@code ChatRelayController} 里 {@code :163} 那处的 {@code publishAndCorrect} ⇒ 本用例红。
     */
    @Test
    void theEarlyTerminationModelNotFoundPathAlsoCorrectsTheReservation() {
        QuotaCorrector recorder = mock(QuotaCorrector.class);
        ConfigClient configClient = mock(ConfigClient.class);
        RouteResolver routeResolver = mock(RouteResolver.class);
        lenient().when(configClient.current()).thenReturn(ConfigSnapshot.empty());
        lenient().when(routeResolver.candidates(any())).thenReturn(List.of());
        ChatRelayController controller = controller(routeResolver, configClient, recorder);

        MockServerWebExchange exchange = post("{\"model\":\"no-such-model\",\"stream\":false}");
        controller.chatCompletions("{\"model\":\"no-such-model\",\"stream\":false}", exchange)
                .block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        verify(recorder, times(1)).correct(any(MeteringEvent.class));
    }

    /**
     * **正常收尾路径**（{@code doFinally}）：真实上游 → 200，校正器必须被调用恰一次，
     * 且回写给客户端的字节**不被校正改写**（M1 铁律）。
     */
    @Test
    void theNormalCompletionPathAlsoCorrectsTheReservation() {
        QuotaCorrector recorder = mock(QuotaCorrector.class);
        ConfigClient configClient = mock(ConfigClient.class);
        RouteResolver routeResolver = mock(RouteResolver.class);
        UpstreamProperties properties = new UpstreamProperties(upstream.baseUrl(), null, "correct-model");
        ChannelDescriptor legacy = LegacyChannel.of(properties);
        lenient().when(configClient.current()).thenReturn(ConfigSnapshot.empty());
        lenient().when(routeResolver.candidates(any())).thenReturn(List.of(legacy));
        ChatRelayController controller = controller(routeResolver, configClient, recorder, properties);

        upstream.enqueueJson(200, FakeUpstream.completionJson());
        MockServerWebExchange exchange = post("{\"model\":\"correct-model\",\"stream\":false}");
        controller.chatCompletions("{\"model\":\"correct-model\",\"stream\":false}", exchange)
                .block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5)))
                .as("校正绝不改写回写的响应体").isEqualTo(FakeUpstream.completionJson());
        // ⚠️ **有界等待**，不是 `verify(times(1))`：Reactor 的 `doFinally` 是在终止信号**向下游传播之后**
        // 才执行的，所以 `block()` 可能在回调跑起来**之前**就返回 —— 直接 `times(1)` 会与回调竞态
        // （实测：同一用例偶发红）。`timeout` 是「带条件的等待」（同 `RecordingMeteringTransport.awaitEvent`），
        // 不是 sleep、也不是重跑；它同时仍强制**恰一次**（多于一次会一直等到超时并失败）。
        verify(recorder, timeout(5_000).times(1)).correct(any(MeteringEvent.class));
    }

    // --- 脚手架 ----------------------------------------------------------------------

    private QuotaReservationRegistry.QuotaReservation reservation(boolean degraded) {
        return new QuotaReservationRegistry.QuotaReservation(TENANT, PERIOD, ESTIMATE, degraded);
    }

    private static MeteringEvent event(String requestId, int prompt, int completion, int total) {
        return new MeteringEvent(requestId, TENANT, 42L, 11L, "m", prompt, completion, total, 5, null,
                MeteringEvent.STATUS_SUCCESS, null, 1_700_000_000_000L);
    }

    private ChatRelayController controller(RouteResolver routeResolver, ConfigClient configClient,
                                           QuotaCorrector corrector) {
        return controller(routeResolver, configClient, corrector,
                new UpstreamProperties("http://127.0.0.1:1", null, "m"));
    }

    private ChatRelayController controller(RouteResolver routeResolver, ConfigClient configClient,
                                           QuotaCorrector corrector, UpstreamProperties properties) {
        ChannelCircuitBreaker circuitBreaker = mock(ChannelCircuitBreaker.class);
        lenient().when(circuitBreaker.isOpen(anyLong())).thenReturn(false);
        return new ChatRelayController(
                new UpstreamClientFactory(properties), configClient, routeResolver, circuitBreaker,
                new ChannelKeyDecryptor(new AesGcmChannelCipher(ChannelKeyRegistry.parse("")), properties),
                mock(MeteringPublisher.class),
                new MeteringProperties(false, 65536, 100, 10, 30_000L, 5_000L, 5_000L, "unused-spool-dir"),
                corrector);
    }

    private static MockServerWebExchange post(String body) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).body(body));
    }

    /** 一次校正调用的参数快照。 */
    private record Adjustment(long tenantId, String period, long estimate, long actual) {
    }

    /** 记录型判定替身：不碰 Redis，只把 {@code adjust} 的参数留下来给断言看。 */
    private static final class RecordingLimiter implements QuotaLimiter {
        private final List<Adjustment> adjustments = new ArrayList<>();
        private RuntimeException failWith;

        @Override
        public com.aihub.common.quota.QuotaDecision reserve(
                com.aihub.common.config.QuotaDescriptor limits, long estimatedTokens) {
            return new com.aihub.common.quota.QuotaDecision(true, 0L, -1L);
        }

        @Override
        public void adjust(long tenantId, String period, long estimatedTokens, long actualTokens) {
            adjustments.add(new Adjustment(tenantId, period, estimatedTokens, actualTokens));
            if (failWith != null) {
                throw failWith;
            }
        }
    }
}
