package com.aihub.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌桶的**纯算术**是「Redis Lua 实现」与「Redis 挂了时的本机实现」共享的单一真相：
 * 两边的补充速率、封顶、拒绝时的退避时间都必须逐字一致，否则降级前后客户端会看到两种行为。
 * 因此这里把语义钉死，Redis 侧只负责「原子地调用它」。
 *
 * <p>令牌数用**千分之一**的整数（{@code tokensMilli}）存：浮点在 Lua 与 Java 两侧的取整规则
 * 不完全一致，整数毫令牌让「同一状态、同一时刻」在两侧得到完全相同的判定。
 */
class TokenBucketTest {

    @Test
    void freshBucketStartsFull() {
        // 新桶 = 空状态（令牌 0、时间戳 0）→ 立即按 qps 补满到 burst。
        RateLimitDecision decision = TokenBucket.tryConsume(new TokenBucket.State(0L, 0L), 10_000L, 10, 20);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).as("补满 20 个、用掉 1 个 → 余 19").isEqualTo(19);
        assertThat(decision.limit()).isEqualTo(10);
        assertThat(decision.burst()).isEqualTo(20);
        assertThat(decision.source()).isEqualTo(RateLimitDecision.Source.REDIS);
    }

    @Test
    void consumesOneTokenPerRequest() {
        TokenBucket.State state = new TokenBucket.State(5_000L, 1_000L);

        // 同一毫秒内连续消费：不允许补充，令牌逐个减少。
        // tryConsume 是**纯函数**，因此「连续」必须把 nextState 的结果喂回来（Redis 侧由脚本原子完成）——
        // 同一个 state 调两次必然得到同样的判定，那正是纯函数该有的样子。
        RateLimitDecision first = TokenBucket.tryConsume(state, 1_000L, 10, 20);
        TokenBucket.State afterFirst = TokenBucket.nextState(state, 1_000L, 10, 20, first);
        RateLimitDecision second = TokenBucket.tryConsume(afterFirst, 1_000L, 10, 20);

        assertThat(first.remaining()).isEqualTo(4);
        assertThat(afterFirst.tokensMilli()).isEqualTo(4_000L);
        assertThat(second.remaining()).isEqualTo(3);
        // 纯函数：同样的输入必得同样的结果。
        assertThat(TokenBucket.tryConsume(state, 1_000L, 10, 20)).isEqualTo(first);
    }

    @Test
    void refillsAtTheConfiguredQps() {
        // qps=10 → 每 100ms 补 1 个。
        TokenBucket.State empty = new TokenBucket.State(0L, 1_000L);

        RateLimitDecision afterOneHundredMillis = TokenBucket.tryConsume(empty, 1_100L, 10, 20);

        assertThat(afterOneHundredMillis.allowed()).isTrue();
        assertThat(afterOneHundredMillis.remaining()).isZero();
    }

    @Test
    void neverExceedsBurst() {
        TokenBucket.State state = new TokenBucket.State(5_000L, 1_000L);

        // 过了一小时也只补到 burst（20），不是无限的。
        assertThat(TokenBucket.tryConsume(state, 3_601_000L, 10, 20).remaining()).isEqualTo(19);
    }

    @Test
    void deniesWhenTokensAreExhausted() {
        RateLimitDecision decision = TokenBucket.tryConsume(new TokenBucket.State(0L, 1_000L), 1_000L, 10, 20);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
    }

    /**
     * 被拒时 `Retry-After` 必须是「再攒出一个令牌需要多久」：
     * {@code ceil((1000 - tokensMilli) / qps)} 毫秒。qps=10、桶空 → 100ms。
     * 这条公式会被网关直接写进响应的 {@code Retry-After} 头与错误体，客户端据此退避。
     */
    @Test
    void retryAfterIsTheTimeForOneToken() {
        assertThat(TokenBucket.tryConsume(new TokenBucket.State(0L, 1_000L), 1_000L, 10, 20).retryAfterMs())
                .isEqualTo(100L);
        // qps=1 → 一秒一个令牌。
        assertThat(TokenBucket.tryConsume(new TokenBucket.State(0L, 1_000L), 1_000L, 1, 2).retryAfterMs())
                .isEqualTo(1_000L);
        // 半个令牌：还需 500 毫令牌 → ceil(500/10)=50ms。
        assertThat(TokenBucket.tryConsume(new TokenBucket.State(500L, 1_000L), 1_000L, 10, 20).retryAfterMs())
                .isEqualTo(50L);
    }

    /** 时钟回拨（NTP 校正、容器迁移）不能让桶凭空多出令牌，也不能把它算成负数。 */
    @Test
    void clockRollbackDoesNotCreateTokensFromTheFuture() {
        TokenBucket.State state = new TokenBucket.State(3_000L, 5_000L);

        RateLimitDecision decision = TokenBucket.tryConsume(state, 1_000L, 10, 20);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(2);
    }

    @Test
    void nextStateNeverGoesNegative() {
        TokenBucket.State state = new TokenBucket.State(0L, 1_000L);
        RateLimitDecision decision = TokenBucket.tryConsume(state, 1_000L, 0, 1);

        TokenBucket.State next = TokenBucket.nextState(state, 1_000L, 0, 1, decision);

        assertThat(next.tokensMilli()).isZero();
        assertThat(next.lastRefillMillis()).isEqualTo(1_000L);
        // 拒绝时时间戳同样推进：否则下一次补充会把已经算过的时间重复补一遍。
        assertThat(TokenBucket.nextState(new TokenBucket.State(0L, 0L), 100L, 10, 1,
                RateLimitDecision.denied(100L, 10, 1, RateLimitDecision.Source.REDIS)).tokensMilli())
                .isEqualTo(1_000L);
    }
}
