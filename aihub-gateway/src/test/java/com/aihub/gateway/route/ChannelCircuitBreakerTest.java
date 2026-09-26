package com.aihub.gateway.route;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 熔断状态必须**跨实例共享**（设计文档 §9 的明文要求）：一个实例发现某渠道 429 了，
 * 另外九个实例应该立刻停止往它上面打流量。Redis 的 key + TTL 是唯一能同时做到「共享」与
 * 「自动过期」的载体（30 秒是 spec 写死的数字）。
 *
 * <p>Redis 不可用时退化为**本机**熔断表：单机近似，但绝不阻断数据面（决策 10）。
 * 所有判定都必须**永不抛异常** —— 它跑在请求路径上。
 */
@SuppressWarnings("unchecked")
class ChannelCircuitBreakerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private ChannelCircuitBreaker breaker() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        return new ChannelCircuitBreaker(redis, clock::get);
    }

    private void breakRedis() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));
        doThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void marksAChannelOpenInRedisWithThePinnedKeyAndTtl() {
        breaker().markOpen(42L);

        verify(values).set(eq("aihub:channel:circuit:42"), eq("OPEN"), eq(Duration.ofSeconds(30)));
    }

    @Test
    void readsBackTheRedisMark() {
        ChannelCircuitBreaker breaker = breaker();
        when(redis.hasKey("aihub:channel:circuit:42")).thenReturn(true);

        assertThat(breaker.isOpen(42L)).isTrue();
        assertThat(breaker.isOpen(43L)).isFalse();
    }

    @Test
    void clearingRemovesTheRedisMark() {
        breaker().clear(42L);

        verify(redis).delete("aihub:channel:circuit:42");
    }

    @Test
    void degradesToALocalMarkWhenRedisThrows() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();

        breaker.markOpen(42L);

        assertThat(breaker.isOpen(42L)).as("Redis 挂了也必须记住这次熔断（本机近似）").isTrue();
        assertThat(breaker.localOpenCount()).isEqualTo(1);
        assertThat(breaker.isOpen(43L)).isFalse();
    }

    @Test
    void localMarkExpiresAfterThePinnedTtl() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();
        breaker.markOpen(42L);

        clock.addAndGet(30_001L);

        assertThat(breaker.isOpen(42L)).isFalse();
    }

    @Test
    void localMarkIsStillOpenJustBeforeTheTtl() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();
        breaker.markOpen(42L);

        clock.addAndGet(29_999L);

        assertThat(breaker.isOpen(42L)).isTrue();
    }

    @Test
    void unknownChannelIsNeverOpen() {
        assertThat(breaker().isOpen(999L)).isFalse();
    }

    @Test
    void stateExplainsWhereTheJudgementCameFrom() {
        ChannelCircuitBreaker breaker = breaker();
        when(redis.hasKey("aihub:channel:circuit:42")).thenReturn(true);

        assertThat(breaker.state(42L).open()).isTrue();
        assertThat(breaker.state(42L).source()).isEqualTo(CircuitState.SOURCE_REDIS);
        assertThat(breaker.state(1L).open()).isFalse();

        breakRedis();
        breaker.markOpen(7L);

        CircuitState local = breaker.state(7L);
        assertThat(local.open()).isTrue();
        assertThat(local.source()).isEqualTo(CircuitState.SOURCE_LOCAL);
        assertThat(local.degraded()).isTrue();
    }

    @Test
    void clearAlsoForgetsTheLocalMark() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();
        breaker.markOpen(42L);
        assertThat(breaker.isOpen(42L)).isTrue();

        breaker.clear(42L);

        assertThat(breaker.isOpen(42L)).isFalse();
        assertThat(breaker.localOpenCount()).isZero();
    }

    @Test
    void keepsTheLocalMarkWhenRedisGoesDarkAfterASuccessfulWrite() {
        ChannelCircuitBreaker breaker = breaker();
        breaker.markOpen(42L);

        breakRedis();

        assertThat(breaker.isOpen(42L))
                .as("写入成功的标记必须同时镜像到本机表：Redis 在这 30 秒内变哑时不能静默解除熔断")
                .isTrue();
    }

    @Test
    void redisFalsyAnswerOverridesAStaleLocalMark() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();
        breaker.markOpen(42L);
        assertThat(breaker.isOpen(42L)).as("先确认本机表里确实有这个标记").isTrue();

        // 用 doReturn 而不是 when：后者会真的调用 mock，而已有的桩正在抛异常。
        doReturn(false).when(redis).hasKey("aihub:channel:circuit:42");

        assertThat(breaker.isOpen(42L))
                .as("Redis 一旦给出明确答案，它才是跨实例权威；本机表只在 Redis 无答案时被查阅")
                .isFalse();
        CircuitState fromRedis = breaker.state(42L);
        assertThat(fromRedis.open()).isFalse();
        assertThat(fromRedis.source()).isEqualTo(CircuitState.SOURCE_REDIS);
        assertThat(fromRedis.degraded()).isFalse();
    }

    @Test
    void degradedClosedReportsTheLocalSource() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();

        CircuitState degradedClosed = breaker.state(55L);

        assertThat(degradedClosed.open()).isFalse();
        assertThat(degradedClosed.source())
                .as("Redis 从未被成功咨询过，不能把它报成 redis（指标侧要能区分「Redis 说健康」与「Redis 是黑的」）")
                .isEqualTo(CircuitState.SOURCE_LOCAL);
        assertThat(degradedClosed.degraded()).isTrue();
    }

    @Test
    void anAnswerlessRedisAlsoDegradesToTheLocalMark() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();
        breaker.markOpen(42L);

        // Redis 既不回 true 也不回 false（null = 「没有答案」），而不是抛异常。
        // 用 doReturn 而不是 when：后者会真的调用 mock，而已有的桩正在抛异常。
        doReturn(null).when(redis).hasKey("aihub:channel:circuit:42");

        assertThat(breaker.isOpen(42L))
                .as("null 是「Redis 没有答案」，必须降级到本机表，不能被折算成「Redis 说未熔断」")
                .isTrue();
        assertThat(breaker.state(42L).source()).isEqualTo(CircuitState.SOURCE_LOCAL);
    }

    @Test
    void aSweptExpiryCannotEvictAConcurrentReMark() {
        // 用假时钟当交错点：sweeper 询问时钟的那一刻，正是它已经判定「旧标记过期」、
        // 即将删除的时候。此时让写方重打一个更晚的标记，就等价于一次并发 re-mark 的竞态
        // （无需真起线程，因此不会 flaky）。
        AtomicInteger clockCalls = new AtomicInteger();
        AtomicReference<ChannelCircuitBreaker> self = new AtomicReference<>();
        LongSupplier interleavingClock = () -> {
            long now = clock.get();
            if (clockCalls.incrementAndGet() == 2) { // 第 1 次是初始打标，第 2 次是 sweeper 的判定
                self.get().markOpen(42L);
            }
            return now;
        };
        ChannelCircuitBreaker breaker = new ChannelCircuitBreaker(redis, interleavingClock);
        self.set(breaker);
        breakRedis();

        breaker.markOpen(42L);
        clock.addAndGet(30_001L);

        assertThat(breaker.localOpenCount())
                .as("剔除过期条目必须用双参数 remove(id, openedAt)：不能按 key 无条件删掉并发重打的更晚标记")
                .isEqualTo(1);
        assertThat(breaker.isOpen(42L)).isTrue();
    }

    @Test
    void keyPrefixAndTtlAreThePinnedLiterals() {
        assertThat(ChannelCircuitBreaker.KEY_PREFIX).isEqualTo("aihub:channel:circuit:");
        assertThat(ChannelCircuitBreaker.OPEN_TTL).isEqualTo(Duration.ofSeconds(30));
        assertThat(ChannelCircuitBreaker.OPEN_VALUE).isEqualTo("OPEN");
    }
}
