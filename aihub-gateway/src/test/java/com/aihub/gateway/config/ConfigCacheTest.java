package com.aihub.gateway.config;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ConfigSnapshotCodec;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §6.3 的三级读取与两级缓存，加上它的两条兜底（Redis 挂 / admin 挂）。
 *
 * <p>{@code concurrentMissesCollapseIntoASingleAdminCall} 是**缓存击穿防护**的可证伪形式：
 * 把 singleflight 去掉（每次 miss 都直接回源）会让 admin 被调用 N 次，用例立刻变红。
 *
 * <p>本类不需要 Docker / Redis / Spring 上下文：{@code StringRedisTemplate} 是桩，
 * {@code AdminClient} 是匿名类（靠接口的 {@code default configSnapshot()} 覆写）。
 */
@SuppressWarnings("unchecked")
class ConfigCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final GatewayConfigProperties properties =
            new GatewayConfigProperties(Duration.ofSeconds(30), Duration.ofMinutes(10), 300);

    private final UpstreamProperties upstream =
            new UpstreamProperties("http://127.0.0.1:11434", "synthetic-upstream-key", "legacy-model");

    private static ConfigSnapshot snapshot(long version, long channelId) {
        return new ConfigSnapshot(version, version * 10,
                List.of(new ChannelDescriptor(channelId, "ch-" + channelId,
                        "https://ch" + channelId + ".example.com", "v1:QUJD", 1, 60_000, "ACTIVE", 100, 0)),
                List.of(new ModelRouteDescriptor("m", channelId, 100, 0, "ACTIVE")),
                List.of(), "m");
    }

    private ConfigCache cache() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        return new ConfigCache(redis, properties);
    }

    /** 一个总是返回给定快照的 admin 替身（覆写 configSnapshot 的默认实现）。 */
    private static AdminClient adminReturning(Supplier<Optional<ConfigSnapshot>> supplier) {
        return new AdminClient() {
            @Override
            public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
                return Mono.just(Optional.empty());
            }

            @Override
            public Mono<Optional<ConfigSnapshot>> configSnapshot() {
                return Mono.just(supplier.get());
            }
        };
    }

    private ConfigClient client(AdminClient adminClient) {
        return new ConfigClient(cache(), adminClient, upstream, properties);
    }

    /**
     * 一级命中不再回源、也不再走「值」的二级读取。
     *
     * <p><b>与 brief 的字面断言有一处刻意偏离，记录在此</b>：brief 的验收标准 1 要求本地命中时
     * {@code opsForValue()} 「一次都没被调用」，但同一份 brief 的协议又要求本地命中时做
     * **版本比对**（{@code local.version() < redis.version} → 丢弃本地）。版本存在 Redis 里，
     * 不读 Redis 就无从比对；而且 {@code staleLocalIsDiscardedWhenRedisHasANewerVersion}
     * 的 fixture（本地 v3 + Redis v10 同时存在）在不读 Redis 的实现下**必然**变红。
     * 两者不可兼得，这里保留 §6.3 的版本比对（它同时是决策 16 之下唯一的跨实例收敛手段），
     * 把断言改成它真正想钉住的那件事：二级只做一次**版本探测**，不做值的读取、也不做写入。
     */
    @Test
    void localHitIsServedWithoutTouchingRedisOrAdmin() {
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigClient client = client(adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.of(snapshot(1L, 11L));
        }));

        client.refresh().block();
        // 回源必须回填两级缓存（含 Redis 写），那是三级回源路径的一部分；下面量的是
        // 「回源之后的那次读取」，因此先把回源期间的调用记录清掉。
        clearInvocations(redis, values);
        ConfigSnapshot second = client.current();

        assertThat(second.channels()).extracting(ChannelDescriptor::id).containsExactly(11L);
        assertThat(adminCalls).as("本地命中不得再回源 admin").hasValue(1);
        verify(redis).opsForValue();
        verify(values).get(ConfigCache.REDIS_KEY);
        verify(values, never()).set(eq(ConfigCache.REDIS_KEY), anyString(), any(Duration.class));
    }

    @Test
    void localMissFallsToRedis() {
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshot(5L, 22L)));
        ConfigClient client = client(adminReturning(() -> {
            throw new AssertionError("Redis 命中时不该回源 admin");
        }));

        assertThat(client.current().channels()).extracting(ChannelDescriptor::id).containsExactly(22L);
    }

    @Test
    void redisMissFallsToAdminAndBackfillsBothLevels() {
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(null);
        ConfigClient client = client(adminReturning(() -> Optional.of(snapshot(7L, 33L))));

        ConfigSnapshot loaded = client.current();

        assertThat(loaded.channels()).extracting(ChannelDescriptor::id).containsExactly(33L);
        assertThat(client.current().channels()).as("第二次必须走本地缓存").extracting(ChannelDescriptor::id)
                .containsExactly(33L);
        verify(values).set(eq(ConfigCache.REDIS_KEY), eq(ConfigSnapshotCodec.encode(loaded)),
                eq(properties.snapshotTtl()));
    }

    /**
     * 冷启动 + admin 可用：这一次回源必须**同时**填满两级缓存，且回源只发生一次。
     *
     * <p>{@code redisMissFallsToAdminAndBackfillsBothLevels} 只直接钉住了 Redis 回填的那一半
     * （{@code set} 带 snapshotTtl），本地那一半是靠「第二次读走本地」间接推出来的。本用例把
     * 「两级都被填满」与「admin 只被调用一次」直接钉住 —— 这正是任务书要求自证的
     * 「冷启动且 admin 可用时填充并缓存」这条行为本身。
     */
    @Test
    void coldStartPopulatesBothCacheLevelsWithOneAdminCall() {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigCache cache = cache();
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.of(snapshot(7L, 88L));
        }), upstream, properties);

        ConfigSnapshot loaded = client.current();

        assertThat(loaded.channels()).as("冷启动必须拿到控制面快照").extracting(ChannelDescriptor::id)
                .containsExactly(88L);
        assertThat(adminCalls).as("冷启动只回源一次").hasValue(1);
        assertThat(cache.local()).as("本地一级必须被回填，且 version 取自快照").isPresent()
                .get().extracting(ConfigSnapshot::version).isEqualTo(7L);
        verify(values).set(eq(ConfigCache.REDIS_KEY), eq(ConfigSnapshotCodec.encode(loaded)),
                eq(properties.snapshotTtl()));
    }

    /**
     * 二级命中的副作用：本地副本必须被刷新成 Redis 的版本，于是**紧接着的下一次读取不再需要
     * 回源 admin，直接拿到同一个 version**。
     *
     * <p>注意这里**不能**断言「第二次不碰 Redis」：§6.3 的版本比对要求每次读取都拿 Redis 比一次
     * 版本（这正是 {@code localHitIsServedWithoutTouchingRedisOrAdmin} 里记录的那处偏离）。
     * 能钉住的是「每次读取只读 Redis 一次、且 admin 不再被调用，版本号沿用 Redis 的那一个」。
     */
    @Test
    void redisHitRefreshesTheLocalCopyWithTheRedisVersion() {
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshot(9L, 21L)));
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigClient client = client(adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.empty();
        }));

        ConfigSnapshot first = client.current();
        clearInvocations(values, redis);
        ConfigSnapshot second = client.current();

        assertThat(first.channels()).extracting(ChannelDescriptor::id).containsExactly(21L);
        assertThat(first.version()).isEqualTo(9L);
        assertThat(second.version()).as("第二次沿用被刷新过的本地副本版本").isEqualTo(9L);
        assertThat(adminCalls).as("二级命中不得回源 admin").hasValue(0);
        // 每次读取**恰好**一次版本探测（多读几次就是白花的 Redis RTT，少读就是跳过了版本比对）。
        verify(values, times(1)).get(ConfigCache.REDIS_KEY);
    }

    /**
     * §6.3 的「快照 version 比对」：本地版本落后就丢弃并回源（这里是回 Redis）。
     * 删掉版本比对 → 本地会一直返回 v3，本用例变红。
     */
    @Test
    void staleLocalIsDiscardedWhenRedisHasANewerVersion() {
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshot(10L, 44L)));
        ConfigCache cache = cache();
        cache.putLocal(snapshot(3L, 99L));
        ConfigClient client = new ConfigClient(cache, adminReturning(Optional::empty), upstream, properties);

        assertThat(client.current().channels()).extracting(ChannelDescriptor::id).containsExactly(44L);
    }

    @Test
    void redisFailureFallsThroughToAdmin() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));
        ConfigClient client = client(adminReturning(() -> Optional.of(snapshot(1L, 55L))));

        assertThat(client.current().channels()).extracting(ChannelDescriptor::id).containsExactly(55L);
    }

    @Test
    void adminFailureKeepsServingTheCachedSnapshot() {
        when(values.get(anyString())).thenReturn(null);
        ConfigCache cache = cache();
        cache.putLocal(snapshot(4L, 66L));
        ConfigClient warmed = new ConfigClient(cache,
                adminReturning(() -> {
                    throw new IllegalStateException("admin 不可达");
                }), upstream, properties);

        assertThat(warmed.current().channels()).as("admin 挂了也要继续用陈旧快照（决策 6）")
                .extracting(ChannelDescriptor::id).containsExactly(66L);
    }

    @Test
    void noSnapshotAnywhereFallsBackToTheLegacySingleChannel() {
        when(values.get(anyString())).thenReturn(null);

        ConfigSnapshot fallback = client(adminReturning(Optional::empty)).current();

        assertThat(fallback.channels()).extracting(ChannelDescriptor::id).containsExactly(LegacyChannel.ID);
        assertThat(fallback.defaultModel()).isEqualTo("legacy-model");
        assertThat(fallback.channelsSupporting("legacy-model")).hasSize(1);
    }

    @Test
    void emptySnapshotFromAdminStillAllowsTheLegacyFallback() {
        when(values.get(anyString())).thenReturn(null);

        ConfigSnapshot fallback = client(adminReturning(() -> Optional.of(ConfigSnapshot.empty()))).current();

        assertThat(fallback.channels()).extracting(ChannelDescriptor::id).containsExactly(LegacyChannel.ID);
    }

    /**
     * **缓存击穿防护**（§6.3 的 singleflight）：16 个并发同时 miss 时，admin 只应被调用一次。
     * 去掉 singleflight 会让 admin 被调用多次 —— 在一个「配置回源是同步阻塞」的系统里，
     * 那就是把一次缓存失效放大成一次对控制面的小规模雪崩。
     */
    @Test
    void concurrentMissesCollapseIntoASingleAdminCall() throws Exception {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigClient client = client(adminReturning(() -> {
            adminCalls.incrementAndGet();
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(snapshot(1L, 77L));
        }));
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    assertThat(client.current().channels()).isNotEmpty();
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(adminCalls).as("并发 miss 必须合并成一次回源").hasValue(1);
    }

    @Test
    void legacyFallbackIsNotCachedAsARealSnapshot() {
        when(values.get(anyString())).thenReturn(null);

        client(adminReturning(Optional::empty)).current();

        verify(values, never()).set(eq(ConfigCache.REDIS_KEY), anyString(), any(Duration.class));
    }

    @Test
    void invalidateDropsTheLocalLayerOnly() {
        when(values.get(anyString())).thenReturn(null);
        ConfigClient client = client(adminReturning(() -> Optional.of(snapshot(1L, 99L))));
        client.current();

        client.invalidate();
        client.current();

        verify(values, atLeast(2)).get(ConfigCache.REDIS_KEY);
    }
}
