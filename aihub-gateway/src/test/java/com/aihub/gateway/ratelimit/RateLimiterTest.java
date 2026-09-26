package com.aihub.gateway.ratelimit;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.RatePolicy;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimiter} 是「Redis 挂了怎么办」这条规则的**唯一落点**，因此它的降级语义必须自己有条用例，
 * 不能只靠 {@link RedisRateLimiterTest}（那一类只证明「Redis 这一级会说 null」）与 Task 9 的过滤器
 * （那一类用的是假 limiter，根本碰不到真门面）。
 *
 * <p>本类钉住四件事：
 * <ol>
 *   <li>Redis 抛异常时 {@code acquire} **返回**一个本机桶判定（`source == LOCAL`），既不抛也不拒绝；</li>
 *   <li>降级是**粘性**的：一秒内不再重复撞 Redis（否则每个请求都要等一次 2 秒超时）；</li>
 *   <li>策略解析真的接进了门面（key 级 100/200 而不是租户级 20/40），而**桶 key 不随策略维度变化**；</li>
 *   <li>降级桶**仍然限流**（不是一张万能通行证），超限时照样带退避提示。</li>
 * </ol>
 */
@SuppressWarnings("unchecked")
class RateLimiterTest {

    /** 租户 7：租户级 20/40，42 号 key 的 key 级 100/200（决策 7 的两级）。 */
    private static final ConfigSnapshot SNAPSHOT = new ConfigSnapshot(1L, 2L, List.of(), List.of(), List.of(
            new RatePolicy(7L, null, 20, 40),
            new RatePolicy(7L, 42L, 100, 200)), null);

    private static final String BUCKET_KEY = "aihub:ratelimit:7:abc";

    private static final long LONG_STICKY = 60_000L;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final AtomicLong clock = new AtomicLong(1_000L);

    private RateLimiter facade(ConfigSnapshot snapshot, long stickyMillis) {
        return new RateLimiter(new RedisRateLimiter(redis),
                new LocalRateLimiter(100, clock::get),
                new RateLimitResolver(() -> snapshot),
                stickyMillis);
    }

    private void redisUnavailable() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));
    }

    /**
     * 设计文档 §9 的核心：控制面（Redis）不可用**不能**变成数据面不可用。故障只能转化为
     * 「换一种近似」（本机桶），绝不能转化为异常或拒绝。
     */
    @Test
    void redisFailureStillReturnsALocalDecisionInsteadOfThrowing() {
        redisUnavailable();
        RateLimiter limiter = facade(ConfigSnapshot.empty(), LONG_STICKY);

        RateLimitDecision decision = limiter.acquire(7L, 42L, "abc");

        assertThat(decision).as("故障绝不能变成异常，也不用 null 表达").isNotNull();
        assertThat(decision.allowed()).as("新桶是满的，第一次请求必须放行").isTrue();
        assertThat(decision.source()).isEqualTo(RateLimitDecision.Source.LOCAL);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.limit()).as("降级沿用的仍是解析出来的策略").isEqualTo(RatePolicy.DEFAULT_QPS);
        assertThat(decision.burst()).isEqualTo(RatePolicy.DEFAULT_BURST);
        assertThat(limiter.redisDegraded()).as("降级必须留下可观测的标记（Task 9 打指标用）").isTrue();
    }

    /**
     * 粘性一秒：Redis 不可达时每次调用会被按住 {@code spring.data.redis.timeout}，若每个请求都先撞一次
     * Redis，故障期间整个网关的延迟就是「超时 × 请求数」。因此降级窗口内必须直接走本机桶。
     */
    @Test
    void theDegradeIsStickySoRedisIsNotHitOncePerRequest() {
        redisUnavailable();
        RateLimiter limiter = facade(ConfigSnapshot.empty(), LONG_STICKY);

        RateLimitDecision first = limiter.acquire(7L, 42L, "abc");
        RateLimitDecision second = limiter.acquire(7L, 42L, "abc");

        assertThat(first.source()).isEqualTo(RateLimitDecision.Source.LOCAL);
        assertThat(second.source()).isEqualTo(RateLimitDecision.Source.LOCAL);
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    /** 粘性窗口过去后必须真的回到 Redis —— 降级是性能取舍，不是「一旦降级就永久降级」。 */
    @Test
    void returnsToRedisOnceTheStickyWindowHasPassed() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"))
                .thenReturn(List.of(1L, 5L, 0L));
        // sticky = 0：任何一次调用都视为「窗口已过」，无需 sleep、也不依赖真实时间。
        RateLimiter limiter = facade(ConfigSnapshot.empty(), 0L);

        assertThat(limiter.acquire(7L, 42L, "abc").source())
                .isEqualTo(RateLimitDecision.Source.LOCAL);
        assertThat(limiter.redisDegraded()).isTrue();

        RateLimitDecision recovered = limiter.acquire(7L, 42L, "abc");

        assertThat(recovered.source()).as("恢复后必须回到 Redis 判定").isEqualTo(RateLimitDecision.Source.REDIS);
        assertThat(recovered.remaining()).isEqualTo(5);
        assertThat(limiter.redisDegraded()).as("恢复后标记必须清掉").isFalse();
    }

    /**
     * 策略解析必须真的接进门面，并且**桶的维度与策略的维度是两件事**：key 级策略生效时，
     * 桶 key 仍是 {@code aihub:ratelimit:{tenantId}:{sha256(secret)}}（决策 8）。
     */
    @Test
    void usesTheKeyLevelPolicyWhileTheBucketKeyStaysTheTenantAndKeyHashOne() {
        AtomicReference<List<String>> keys = new AtomicReference<>();
        AtomicReference<Object[]> args = new AtomicReference<>();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            keys.set(invocation.getArgument(1));
            // Mockito 把变参 `Object...` 摊开：下标 2 起才是 ARGV（见 RedisRateLimiterTest 的同类注释）。
            Object[] received = invocation.getArguments();
            args.set(Arrays.copyOfRange(received, 2, received.length));
            return List.of(1L, 7L, 0L);
        });
        RateLimiter limiter = facade(SNAPSHOT, LONG_STICKY);

        RateLimitDecision decision = limiter.acquire(7L, 42L, "abc");

        assertThat(decision.limit()).as("42 号 key 的 key 级策略赢过租户级").isEqualTo(100);
        assertThat(decision.burst()).isEqualTo(200);
        assertThat(decision.remaining()).isEqualTo(7);
        assertThat(keys.get()).containsExactly(BUCKET_KEY);
        assertThat(args.get()[1]).as("策略数字必须一路传进脚本").isEqualTo(String.valueOf(100));
        assertThat(args.get()[2]).isEqualTo(String.valueOf(200));
    }

    /** 没有 key 级行（含匿名桶的 {@code apiKeyId == null}）时，租户级策略仍要生效。 */
    @Test
    void fallsBackToTheTenantLevelPolicyWhenThereIsNoKeyLevelRow() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 3L, 0L));
        RateLimiter limiter = facade(SNAPSHOT, LONG_STICKY);

        RateLimitDecision unknownKey = limiter.acquire(7L, 99L, "abc");
        RateLimitDecision anonymous = limiter.acquire(7L, null, "abc");

        assertThat(unknownKey.limit()).isEqualTo(20);
        assertThat(unknownKey.burst()).isEqualTo(40);
        assertThat(anonymous.limit()).as("apiKeyId 为 null 不能把租户级策略也丢掉").isEqualTo(20);
    }

    /** 超限判定必须带上过滤器要用的退避提示（Task 9 写 `Retry-After`）。 */
    @Test
    void anOverLimitDecisionCarriesTheRetryHint() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, 0L, 250L));
        RateLimiter limiter = facade(SNAPSHOT, LONG_STICKY);

        RateLimitDecision decision = limiter.acquire(7L, 42L, "abc");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterMs()).isEqualTo(250L);
        assertThat(decision.source()).isEqualTo(RateLimitDecision.Source.REDIS);
        assertThat(decision.limit()).isEqualTo(100);
    }

    /**
     * 降级**不是**「限流失效」：本机桶照样按同一套算术判定，只是作用域变成单机。
     * 把降级写成「一律放行」的实现在这条上必红。
     */
    @Test
    void theDegradedBucketStillLimitsAndCarriesARetryHint() {
        redisUnavailable();
        ConfigSnapshot tiny = new ConfigSnapshot(1L, 2L, List.of(), List.of(),
                List.of(new RatePolicy(7L, null, 1, 1)), null);
        RateLimiter limiter = facade(tiny, LONG_STICKY);

        assertThat(limiter.acquire(7L, null, "abc").allowed()).as("burst=1，第一次放行").isTrue();
        RateLimitDecision denied = limiter.acquire(7L, null, "abc");

        assertThat(denied.allowed()).as("降级桶也必须真的限流").isFalse();
        assertThat(denied.degraded()).isTrue();
        assertThat(denied.retryAfterMs()).as("qps=1 → 差一个令牌要 1000ms").isEqualTo(1_000L);
    }

    /**
     * 端到端版本的降级证据：**真的** Lettuce 客户端指向一个死端口（与 M1/M2 的
     * {@code spring.data.redis.port=1} 同一条做法），走完「真连接失败 → {@link RedisRateLimiter}
     * 折算 null → 本机桶判定」整条链路。上面的桩测试钉的是语义，这条钉的是「真驱动抛出来的那种异常
     * 也确实被吃掉了」。
     */
    @Test
    void aRealRedisClientPointedAtADeadPortStillYieldsALocalDecision() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder()
                        // 短连接超时：这条用例是故意连不上的，不该为一次必然失败多等几秒。
                        .clientOptions(ClientOptions.builder()
                                .socketOptions(SocketOptions.builder()
                                        .connectTimeout(Duration.ofMillis(200))
                                        .build())
                                .build())
                        .commandTimeout(Duration.ofMillis(300))
                        .build());
        factory.afterPropertiesSet();
        try {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            RateLimiter limiter = new RateLimiter(new RedisRateLimiter(template),
                    new LocalRateLimiter(100, clock::get),
                    new RateLimitResolver(ConfigSnapshot::empty), LONG_STICKY);

            RateLimitDecision decision = limiter.acquire(7L, 42L, "dead-port");

            assertThat(decision).isNotNull();
            assertThat(decision.source()).isEqualTo(RateLimitDecision.Source.LOCAL);
            assertThat(decision.allowed()).isTrue();
            assertThat(limiter.redisDegraded()).isTrue();
        } finally {
            factory.destroy();
        }
    }
}
