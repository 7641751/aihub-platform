package com.aihub.gateway.route;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    void keyPrefixAndTtlAreThePinnedLiterals() {
        assertThat(ChannelCircuitBreaker.KEY_PREFIX).isEqualTo("aihub:channel:circuit:");
        assertThat(ChannelCircuitBreaker.OPEN_TTL).isEqualTo(Duration.ofSeconds(30));
        assertThat(ChannelCircuitBreaker.OPEN_VALUE).isEqualTo("OPEN");
    }
}
