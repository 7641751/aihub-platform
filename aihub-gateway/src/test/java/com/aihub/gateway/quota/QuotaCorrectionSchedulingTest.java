package com.aihub.gateway.quota;

import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.config.ConfigClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class QuotaCorrectionSchedulingTest {

    private static final long TENANT = 7L;
    private static final String PERIOD = "202611";
    private static final String REQUEST_ID = "quota-scheduling";

    @Test
    void blockedRedisWriteLeavesTheEventLoopAvailableAndSubmitsOnce() throws Exception {
        var limiter = mock(QuotaLimiter.class);
        var entered = new CompletableFuture<String>();
        var release = new CompletableFuture<Void>();
        var finished = new CompletableFuture<Void>();
        doAnswer(invocation -> {
            entered.complete(Thread.currentThread().getName());
            release.get(15, TimeUnit.SECONDS);
            finished.complete(null);
            return null;
        }).when(limiter).adjust(TENANT, PERIOD, 100L, 35L);

        try (var context = quotaContext(limiter)) {
            var eventLoop = Schedulers.newSingle("quota-test-loop");
            try {
                var corrector = context.getBean(QuotaCorrector.class);
                writeReservation(context);
                var submission = Mono.fromRunnable(() -> corrector.correct(event()))
                        .subscribeOn(eventLoop).then().toFuture();

                assertThat(entered.get(5, TimeUnit.SECONDS)).startsWith("quota-correction-");
                submission.get(5, TimeUnit.SECONDS);
                assertThat(Mono.just("alive").subscribeOn(eventLoop).block(Duration.ofSeconds(5)))
                        .isEqualTo("alive");
                corrector.correct(event());
                assertThat(context.getBean(MeterRegistry.class)
                        .counter(QuotaCorrector.MISSING_METRIC).count()).isEqualTo(1.0);

                release.complete(null);
                finished.get(5, TimeUnit.SECONDS);
                verify(limiter).adjust(TENANT, PERIOD, 100L, 35L);
            } finally {
                release.complete(null);
                eventLoop.dispose();
            }
        }
    }

    @Test
    void aFullQueueCountsRejectionAndKeepsTheEstimate() throws Exception {
        var limiter = mock(QuotaLimiter.class);
        try (var context = quotaContext(limiter)) {
            var executor = context.getBean("quotaCorrectionExecutor", ThreadPoolTaskExecutor.class);
            var release = new CompletableFuture<Void>();
            try {
                blockWorkers(executor, release);
                for (int i = 0; i < 256; i++) {
                    executor.execute(() -> { });
                }
                assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(256);

                writeReservation(context);
                context.getBean(QuotaCorrector.class).correct(event());

                var meters = context.getBean(MeterRegistry.class);
                assertThat(meters.counter(QuotaCorrector.REJECTED_METRIC).count()).isEqualTo(1.0);
                assertThat(meters.counter(QuotaCorrector.FAILED_METRIC).count()).isEqualTo(1.0);
                assertThat(context.getBean(QuotaReservationRegistry.class).consume(REQUEST_ID)).isNull();
            } finally {
                release.complete(null);
            }
        }
        verifyNoInteractions(limiter);
    }

    @Test
    void contextCloseDrainsAcceptedCorrectionsBeforeStoppingRedis() throws Exception {
        var limiter = mock(QuotaLimiter.class);
        try (var context = quotaContext(limiter)) {
            var executor = context.getBean("quotaCorrectionExecutor", ThreadPoolTaskExecutor.class);
            var redis = context.getBean(RedisLifecycle.class);
            var release = new CompletableFuture<Void>();
            var writeWhileRedisRunning = new CompletableFuture<Boolean>();
            doAnswer(invocation -> {
                writeWhileRedisRunning.complete(redis.isRunning());
                return null;
            }).when(limiter).adjust(TENANT, PERIOD, 100L, 35L);

            CompletableFuture<Void> closing;
            try {
                blockWorkers(executor, release);
                writeReservation(context);
                context.getBean(QuotaCorrector.class).correct(event());
                assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);

                closing = CompletableFuture.runAsync(context::close);
                await().atMost(Duration.ofSeconds(5)).until(executor.getThreadPoolExecutor()::isShutdown);
            } finally {
                release.complete(null);
            }
            closing.get(5, TimeUnit.SECONDS);
            assertThat(writeWhileRedisRunning.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(redis.isRunning()).isFalse();
        }
    }

    private static void blockWorkers(ThreadPoolTaskExecutor executor, CompletableFuture<Void> release)
            throws InterruptedException {
        var running = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            executor.execute(() -> {
                running.countDown();
                release.join();
            });
        }
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static AnnotationConfigApplicationContext quotaContext(QuotaLimiter limiter) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("quota-scheduling-test", Map.of("aihub.quota.enabled", "true")));
        context.registerBean(AdminClient.class, () -> mock(AdminClient.class));
        context.registerBean(ConfigClient.class, () -> mock(ConfigClient.class));
        context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
        context.registerBean(RedisLifecycle.class);
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean("testQuotaLimiter", QuotaLimiter.class, () -> limiter,
                definition -> definition.setPrimary(true));
        context.register(QuotaConfig.class);
        context.refresh();
        return context;
    }

    private static void writeReservation(AnnotationConfigApplicationContext context) {
        context.getBean(QuotaReservationRegistry.class).write(REQUEST_ID,
                new QuotaReservationRegistry.QuotaReservation(TENANT, PERIOD, 100L, false));
    }

    private static MeteringEvent event() {
        return new MeteringEvent(REQUEST_ID, TENANT, 42L, 11L, "test-model", 20, 15, 35,
                5, null, MeteringEvent.STATUS_SUCCESS, null, 1_700_000_000_000L);
    }

    // Match LettuceConnectionFactory's lifecycle phase without requiring Redis in the gateway tests.
    private static class RedisLifecycle implements SmartLifecycle {
        private final AtomicBoolean running = new AtomicBoolean();

        @Override
        public int getPhase() {
            return 0;
        }

        @Override
        public void start() {
            running.set(true);
        }

        @Override
        public void stop() {
            running.set(false);
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }
    }
}
