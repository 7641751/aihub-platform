package com.aihub.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本机令牌桶是 **Redis 不可用时的降级路径**（设计文档 §9：绝不因为控制面故障而阻断数据面）。
 * 它的语义必须与 Redis 侧一致（共用 {@link TokenBucket}），并且**有界**（Caffeine 上限），
 * 否则一个恶意客户端可以用无数个 key 把网关内存撑爆 —— 降级反而变成攻击面。
 *
 * <p>时钟由构造器注入：所有判定都不依赖真实时间，因此没有 sleep、没有抖动。
 */
class LocalRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    private LocalRateLimiter limiter(int maxBuckets) {
        return new LocalRateLimiter(maxBuckets, clock::get);
    }

    @Test
    void allowsUpToBurstThenDenies() {
        LocalRateLimiter limiter = limiter(100);

        // qps=0：不补充，只能消费 burst 次。
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("k", 0, 5).allowed()).as("第 %s 次突发", i + 1).isTrue();
        }
        RateLimitDecision denied = limiter.tryConsume("k", 0, 5);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.source()).isEqualTo(RateLimitDecision.Source.LOCAL);
        assertThat(denied.degraded()).isTrue();
    }

    @Test
    void refillsAfterTheWindow() {
        LocalRateLimiter limiter = limiter(100);
        limiter.tryConsume("k", 10, 1);
        assertThat(limiter.tryConsume("k", 10, 1).allowed()).isFalse();

        clock.addAndGet(100L);   // qps=10 → 100ms 补一个

        assertThat(limiter.tryConsume("k", 10, 1).allowed()).isTrue();
    }

    @Test
    void differentKeysHaveIndependentBuckets() {
        LocalRateLimiter limiter = limiter(100);
        limiter.tryConsume("a", 0, 1);

        assertThat(limiter.tryConsume("a", 0, 1).allowed()).as("a 已耗尽").isFalse();
        assertThat(limiter.tryConsume("b", 0, 1).allowed()).as("b 不受 a 影响").isTrue();
    }

    /**
     * **降级路径也绝不能超发**。Caffeine 的 {@code asMap().compute} 是「单键原子」的，
     * 因此同一 key 的并发请求在桶层面是串行的；把它换成「get → 判定 → put」会让这条变红。
     */
    @Test
    void refusesToExceedBurstUnderConcurrency() throws Exception {
        LocalRateLimiter limiter = limiter(100);
        int threads = 16;
        int attemptsPerThread = 50;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < attemptsPerThread; j++) {
                    if (limiter.tryConsume("hot", 0, 7).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        }

        // qps=0 → 总共只能放行 burst=7 次，无论多少线程并发。
        assertThat(allowed.get()).isEqualTo(7);
    }

    @Test
    void evictsIdleBucketsAtTheCap() {
        LocalRateLimiter limiter = limiter(10);

        for (int i = 0; i < 200; i++) {
            limiter.tryConsume("key-" + i, 10, 5);
        }

        assertThat(limiter.trackedBuckets()).isLessThanOrEqualTo(10);
    }

    @Test
    void zeroQpsDeniesEverything() {
        LocalRateLimiter limiter = limiter(100);

        assertThat(limiter.tryConsume("k", 0, 1).allowed()).isTrue();
        RateLimitDecision denied = limiter.tryConsume("k", 0, 1);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).as("qps=0 时退避时间有明确上界").isEqualTo(3_600_000L);
    }

    @Test
    void nonPositiveBurstIsTreatedAsOne() {
        LocalRateLimiter limiter = limiter(100);

        assertThat(limiter.tryConsume("k", 0, 0).allowed()).isTrue();
        assertThat(limiter.tryConsume("k", 0, 0).allowed()).isFalse();
    }

    @Test
    void clearResetsAllBuckets() {
        LocalRateLimiter limiter = limiter(100);
        limiter.tryConsume("k", 0, 1);

        limiter.clear();

        assertThat(limiter.trackedBuckets()).isZero();
        assertThat(limiter.tryConsume("k", 0, 1).allowed()).isTrue();
    }
}
