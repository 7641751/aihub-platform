package com.aihub.gateway.config;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ConfigSnapshotCodec;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 *
 * <p><b>「singleflight 名额必须被释放」也在这里被钉住</b>（2026 复审修复 1）：单飞槽位只在成功
 * 时释放会让第一次的终态信号被永久重放 —— 第一次回源失败即永久卡死，缓存过期后也永不重新回源。
 * {@code secondRefreshCallsAdminAgainAfterTheLocalTtlLapses} 与
 * {@code failedFirstLoadDoesNotWedgeTheInstance} 分别覆盖这两半。
 *
 * <p><b>所有用例都显式传 {@link SimpleMeterRegistry}</b>：4 参重载落进
 * {@code Metrics.globalRegistry}，在测试之间会互相污染（同名 counter 被复用、gauge 互相覆盖）。
 */
@SuppressWarnings("unchecked")
class ConfigCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    /** 每个用例一个注册表：绝不让指标在用例之间串味。 */
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

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
        return cache(properties);
    }

    private ConfigCache cache(GatewayConfigProperties configProperties) {
        lenient().when(redis.opsForValue()).thenReturn(values);
        return new ConfigCache(redis, configProperties);
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
        return new ConfigClient(cache(), adminClient, upstream, properties, registry);
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
     *
     * <p>另外钉住**冷启动只花一次 Redis 探测**：{@code current()} 曾在回源前后各调一次
     * {@code resolve()}，于是冷启动要付两次 Redis 往返；现在只在回源返回空时才重读。
     */
    @Test
    void coldStartPopulatesBothCacheLevelsWithOneAdminCall() {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigCache cache = cache();
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.of(snapshot(7L, 88L));
        }), upstream, properties, registry);

        ConfigSnapshot loaded = client.current();

        assertThat(loaded.channels()).as("冷启动必须拿到控制面快照").extracting(ChannelDescriptor::id)
                .containsExactly(88L);
        assertThat(adminCalls).as("冷启动只回源一次").hasValue(1);
        assertThat(cache.local()).as("本地一级必须被回填，且 version 取自快照").isPresent()
                .get().extracting(ConfigSnapshot::version).isEqualTo(7L);
        verify(values, times(1)).get(ConfigCache.REDIS_KEY);
        verify(values).set(eq(ConfigCache.REDIS_KEY), eq(ConfigSnapshotCodec.encode(loaded)),
                eq(properties.snapshotTtl()));
    }

    /**
     * 二级命中的副作用：本地副本必须被刷新成 Redis 的版本。
     *
     * <p><b>断言的是本地层本身，而不是「第二次读到的还是 9」</b>：只要 Redis 桩还在应答，
     * 删掉 {@code resolve()} 里的 {@code cache.putLocal(fromRedis)} 之后第二次读**照样**从 Redis
     * 拿到 v9 —— 这样的用例钉不住回填。因此这里先直接查本地层，再把 Redis 桩打空
     * （{@code thenReturn(null)}），于是第二次读只能由刚被刷新的本地副本服务（admin 仍是 0 次）。
     */
    @Test
    void redisHitRefreshesTheLocalCopyWithTheRedisVersion() {
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshot(9L, 21L)));
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigCache cache = cache();
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.empty();
        }), upstream, properties, registry);

        ConfigSnapshot first = client.current();

        assertThat(first.channels()).extracting(ChannelDescriptor::id).containsExactly(21L);
        assertThat(first.version()).isEqualTo(9L);
        assertThat(cache.local()).as("二级命中必须回填本地副本，且携带 Redis 的 version").isPresent()
                .get().extracting(ConfigSnapshot::version).isEqualTo(9L);

        // Redis 从此读不到（模拟 10 分钟 TTL 到期 / Redis 掉线）：第二次读只能由本地副本服务。
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(null);
        clearInvocations(values, redis);
        ConfigSnapshot second = client.current();

        assertThat(second.version()).as("第二次必须由本地副本服务（Redis 已经读不到了）").isEqualTo(9L);
        assertThat(second.channels()).extracting(ChannelDescriptor::id).containsExactly(21L);
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
        ConfigClient client = new ConfigClient(cache, adminReturning(Optional::empty), upstream, properties, registry);

        assertThat(client.current().channels()).extracting(ChannelDescriptor::id).containsExactly(44L);
    }

    @Test
    void redisFailureFallsThroughToAdmin() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));
        ConfigClient client = client(adminReturning(() -> Optional.of(snapshot(1L, 55L))));

        assertThat(client.current().channels()).extracting(ChannelDescriptor::id).containsExactly(55L);
    }

    /**
     * 决策 6：admin 挂了也要继续服务手上已有的陈旧快照。
     *
     * <p><b>这个场景必须真的走到 admin 那一跳</b>：本地非空时 {@code resolve()} 直接返回本地副本，
     * 那个会抛异常的 admin 替身根本不会被调用 —— 于是「先问 admin 再退回缓存」的实现也会通过。
     * 所以这里先让第一跳成功（拿到 v66 并让「最近一次成功快照」记住它），再让本地过期、Redis 读空、
     * admin 开始抛异常。此时唯一还能服务的来源就是那份**永不失效**的最近快照，且全程不得抛异常。
     */
    @Test
    void adminFailureKeepsServingTheCachedSnapshot() {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigCache cache = cache();
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            if (adminCalls.getAndIncrement() == 0) {
                return Optional.of(snapshot(4L, 66L));
            }
            throw new IllegalStateException("admin 不可达");
        }), upstream, properties, registry);

        assertThat(client.current().channels()).as("第一跳必须由 admin 提供，测试才有意义")
                .extracting(ChannelDescriptor::id).containsExactly(66L);

        // 「已经过期」的最强形式：缓存里一份都不剩（本地被清、Redis 读空），admin 也开始抛。
        cache.invalidateLocal();
        assertThat(cache.local()).as("前置条件：本地层必须为空，否则本用例到不了 admin 那一跳").isEmpty();

        ConfigSnapshot served = client.current();

        assertThat(served.channels()).as("admin 挂了也要继续用陈旧快照（决策 6）")
                .extracting(ChannelDescriptor::id).containsExactly(66L);
        assertThat(adminCalls.get()).as("必须真的试过 admin 才能证明「admin 挂了」").isGreaterThanOrEqualTo(2);
    }

    /**
     * 复审修复 3（控制器裁决）：两级缓存**都**不可用且 admin 也挂了时，仍然要服务最近一次成功快照
     * —— 决策 6 的字面要求是「**哪怕已过期**」，而仅靠两级缓存兜底的实现在这里会退化成单条遗留渠道、
     * 丢掉多渠道路由。
     *
     * <p>这三条同时成立才算通过：本地层为空、Redis 读空（模拟 TTL 到期或 Redis 不可用）、admin
     * 不可达；仍然拿到那条多渠道路由（id=31，不是 {@link LegacyChannel#ID}），并且不抛异常，
     * 且这份内存副本**不写回 Redis**（否则会复活一条陈旧共享条目）。
     */
    @Test
    void combinedRedisAndAdminOutageStillServesTheLastGoodSnapshot() {
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshot(3L, 31L)));
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigCache cache = cache();
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.empty();
        }), upstream, properties, registry);

        assertThat(client.current().channels()).as("前置条件：先成功服务过一次").extracting(ChannelDescriptor::id)
                .containsExactly(31L);

        // 组合故障：本地过期（清空）+ Redis 不可达（读空）+ admin 不可达（返回空）。
        cache.invalidateLocal();
        when(values.get(anyString())).thenReturn(null);
        clearInvocations(values);

        ConfigSnapshot served = client.current();

        assertThat(served.channels()).as("组合故障下多渠道路由必须存活（决策 6 的「哪怕已过期」）")
                .extracting(ChannelDescriptor::id).containsExactly(31L);
        assertThat(served.channels()).extracting(ChannelDescriptor::id).doesNotContain(LegacyChannel.ID);
        assertThat(served.version()).isEqualTo(3L);
        assertThat(adminCalls.get()).as("admin 必须被真的试过一次才谈得上不可达").isGreaterThanOrEqualTo(1);
        assertThat(cache.local()).as("最近快照只留在内存里，绝不回填本地层").isEmpty();
        verify(values, never()).set(eq(ConfigCache.REDIS_KEY), anyString(), any(Duration.class));
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

    /**
     * 复审修复 1（a）：回源**结束之后**单飞槽位必须被释放，否则一次成功回源就永久封死了回源路径
     * —— 本地 30 s / Redis 10 m TTL 一过（或 Redis 不可用），实例只会一遍遍重放第一次的终态信号，
     * 明明 admin 健康也不再回源、也不再改写 Redis。
     *
     * <p>本用例的本地 TTL 是 100 ms：第一次读回源并回填；等它过期后第二次读三级全 miss，
     * 只有槽位真的被释放了才会再次调用 admin。
     */
    @Test
    void secondRefreshCallsAdminAgainAfterTheLocalTtlLapses() throws Exception {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigCache cache = cache(new GatewayConfigProperties(Duration.ofMillis(100), Duration.ofMinutes(10), 300));
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            int call = adminCalls.incrementAndGet();
            return Optional.of(snapshot(call, 70L + call));
        }), upstream, properties, registry);

        assertThat(client.current().version()).as("第一次回源").isEqualTo(1L);
        assertThat(adminCalls).as("第一次读必须回源一次").hasValue(1);

        Thread.sleep(250L);
        assertThat(cache.local()).as("前置条件：本地副本必须已经过期").isEmpty();

        ConfigSnapshot second = client.current();

        assertThat(second.version()).as("槽位释放后第二次读必须重新回源，拿到新版本而不是被重放的第一份")
                .isEqualTo(2L);
        assertThat(adminCalls).as("单飞槽位必须在回源结束后释放，否则永远只有一次回源").hasValue(2);
    }

    /**
     * 复审修复 1（b）：**第一次回源失败不能把实例钉死**。admin 挂掉（或回一份空快照）时
     * {@code Mono.empty()} 是终态但不是「成功」；若只在 {@code doOnSuccess} 释放槽位，这个空信号会被
     * 永久重放 —— {@code refreshBlocking()} 从此恒为 null，网关永久停在遗留单渠道，admin 恢复后
     * 也再不会回源。
     *
     * <p>fixture 必须让**第一次读三级全空**（本地空、Redis 空、admin 抛），否则 Redis 会在同一次读里
     * 就把快照补给上层，掩盖掉卡死。这里让 Redis 全程读空，只放开 admin：于是「第二次读有没有再调
     * admin」就是唯一能把卡死与正常区分开的观测量 —— 卡死的实现连 admin 都不会再试一次。
     */
    @Test
    void failedFirstLoadDoesNotWedgeTheInstance() {
        // Redis 全程读空（模拟 Redis 也挂）：唯一能救回来的只有重新回源 admin。
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigCache cache = cache();
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            if (adminCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("admin 不可达");
            }
            return Optional.of(snapshot(5L, 55L));
        }), upstream, properties, registry);

        ConfigSnapshot degraded = client.current();

        assertThat(degraded.channels()).as("第一次回源失败且 Redis 也空，只能退化到遗留单渠道")
                .extracting(ChannelDescriptor::id).containsExactly(LegacyChannel.ID);
        assertThat(adminCalls.get()).as("第一次读必须真的试过 admin").isGreaterThanOrEqualTo(1);
        assertThat(cache.local()).as("失败的第一次回源不得回填任何缓存").isEmpty();

        // admin 恢复：下一次读必须重新回源并恢复正常路由，而不是继续重放那个空信号。
        int beforeRetry = adminCalls.get();
        ConfigSnapshot recovered = client.current();

        assertThat(recovered.channels()).as("admin 恢复后必须能重新回源，不能被第一次失败永久钉死")
                .extracting(ChannelDescriptor::id).containsExactly(55L);
        assertThat(recovered.version()).isEqualTo(5L);
        assertThat(adminCalls.get()).as("第二次读必须再次调用 admin").isEqualTo(beforeRetry + 1);
    }

    @Test
    void legacyFallbackIsNotCachedAsARealSnapshot() {
        when(values.get(anyString())).thenReturn(null);

        client(adminReturning(Optional::empty)).current();

        verify(values, never()).set(eq(ConfigCache.REDIS_KEY), anyString(), any(Duration.class));
    }

    /**
     * {@code invalidate()} 只丢本地层：本地副本与 Redis 的版本**相同**（渠道不同）时，
     * 失效之后必须重新读到 Redis 的那一份。
     *
     * <p>brief 原本的断言是 {@code verify(values, atLeast(2)).get(REDIS_KEY)} —— 那个断言**不会失败**：
     * 每次 {@code current()} 都要做一次版本探测，所以哪怕 {@code invalidate()} 是空实现，
     * 两次读也一定是 2 次 {@code get}。而且那个版本号本身是错的（原用例只调了一次
     * {@code current()}，压根达不到 2 次）。这里改成直接量**本地层本身**是否被清掉：
     * 版本号刻意与 Redis 相同，{@code fromRedis.version() > local.version()} 不成立，
     * 于是「没有真的失效」就一定会拿着陈旧的本地副本答下去。
     */
    @Test
    void invalidateDropsTheLocalLayerOnly() {
        // Redis 里是 v5/id=22；本地被塞进同样 v5 但渠道不同的陈旧副本。
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshot(5L, 22L)));
        ConfigCache cache = cache();
        cache.putLocal(snapshot(5L, 99L));
        ConfigClient client = new ConfigClient(cache, adminReturning(Optional::empty), upstream, properties, registry);

        client.invalidate();

        assertThat(cache.local()).as("invalidate() 必须清掉本地层").isEmpty();

        ConfigSnapshot afterInvalidate = client.current();

        assertThat(afterInvalidate.channels()).as("本地被清掉后必须重新读 Redis 并采用它的快照")
                .extracting(ChannelDescriptor::id).containsExactly(22L);
        assertThat(cache.local()).as("采用 Redis 快照时顺带刷新本地副本").isPresent()
                .get().extracting(ConfigSnapshot::version).isEqualTo(5L);
        // 采用 Redis 后本地副本就是 Redis 的那一份，等价于「本地层已经让位」。
        assertThat(cache.local()).get().extracting(ConfigSnapshot::channels).as("本地副本不得再是失效前的那份")
                .isNotEqualTo(snapshot(5L, 99L).channels());
        verify(values, atLeast(1)).get(ConfigCache.REDIS_KEY);
    }

    /**
     * 复审修复 4：被服务的那份快照的 version 必须**在服务它的那一次**就读进指标。
     * 只在 {@code resolve()} 里报的话，第一次控制面快照对外会显示 version=0，直到下一次读才纠正。
     */
    @Test
    void firstServedSnapshotReportsItsVersion() {
        when(values.get(anyString())).thenReturn(null);
        ConfigClient client = client(adminReturning(() -> Optional.of(snapshot(42L, 12L))));

        ConfigSnapshot served = client.current();

        assertThat(served.version()).isEqualTo(42L);
        assertThat(client.visibleVersion()).as("第一次服务就必须报出这份快照的版本，而不是 0").isEqualTo(42L);
        assertThat(registry.get(ConfigClient.VERSION_METRIC).gauge().value()).isEqualTo(42.0d);
    }
}
