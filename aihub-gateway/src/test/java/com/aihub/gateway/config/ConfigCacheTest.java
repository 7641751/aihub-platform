package com.aihub.gateway.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ConfigSnapshotCodec;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.QuotaDescriptor;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * 生产默认形状（30s / 10m / 300 / **5s 冷却**）。
     *
     * <p>冷却窗口刻意取**生产默认的那 5 秒**而不是测试自己的短值：「N 个请求只产生一次回源」这条
     * 证据只有在冷却窗口与真实配置一致时才对生产有意义 —— 用一个 50 ms 的测试值去证明它，
     * 等于什么都没证明。
     */
    private final GatewayConfigProperties properties =
            new GatewayConfigProperties(Duration.ofSeconds(30), Duration.ofMinutes(10), 300, Duration.ofSeconds(5));

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

    /**
     * Task 13：网关从快照里读额度（不连数据库）。这里只量**本地层那份快照**的额度查找，且要求
     * **按 {@code (tenant, period)} 精确匹配** —— 变异体④（只按 {@code tenantId} 找）会让「另一个周期」
     * 那条断言返回 202601 的那份、精确变红。
     */
    @Test
    void quotaIsReadFromTheLocalSnapshotByTenantAndPeriod() {
        ConfigCache cache = cache();
        cache.putLocal(new ConfigSnapshot(5L, 50L,
                List.of(new ChannelDescriptor(1L, "ch", "https://ch.example.com", "v1:QUJD", 1, 60_000, "ACTIVE",
                        100, 0)),
                List.of(), List.of(), "m",
                List.of(new QuotaDescriptor(7L, "202601", 100L, 4L),
                        new QuotaDescriptor(7L, "202602", 200L, 8L))));

        assertThat(cache.quota(7L, "202601")).contains(new QuotaDescriptor(7L, "202601", 100L, 4L));
        assertThat(cache.quota(7L, "202602"))
                .as("同一租户的不同周期必须各自取到自己的额度（忽略 period 的实现会在这里返回 202601 那份）")
                .contains(new QuotaDescriptor(7L, "202602", 200L, 8L));
        assertThat(cache.quota(7L, "202603")).as("没有该周期的行 = 不限（D15）").isEmpty();
        assertThat(cache.quota(8L, "202601")).as("别的租户取不到").isEmpty();
    }

    /** 本地层还空着时（快照尚未回填）额度查询返回空 —— 调用方据此走「不限」（D15）。 */
    @Test
    void quotaIsEmptyWhenNoSnapshotIsCachedLocally() {
        assertThat(cache().quota(7L, "202601")).isEmpty();
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
     * 可推进的假时钟：{@code ConfigCache} 的 Caffeine TTL（{@link Ticker}，纳秒）与
     * {@code ConfigClient} 的冷却窗口（{@link Clock}，毫秒）读的是**同一个**计数器。
     *
     * <p>它只服务于那些「必须观察到某个 TTL 窗口**之内**的状态」的用例：用真实时钟写这种断言等于
     * 把用例的成败交给调度器（GC / CI 负载 / 整反应堆并发）。推进是显式的 —— 测试说「150 ms 过去了」
     * 才是真的过去了，而不是「希望 150 ms 里没有别的线程抢走 CPU」。
     */
    private static final class FakeClock extends Clock {

        private final AtomicLong millis = new AtomicLong();

        Ticker ticker() {
            return () -> millis.get() * 1_000_000L;
        }

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    /**
     * 返回 {@link Mono} 的 admin 替身：{@link #adminReturning} 只能表达「立即给出一个 Optional」，
     * 而「永不终结的流」与「按订阅次数改变行为」都需要自己控制 Publisher。{@code Mono.defer} 保证
     * supplier 在**每次订阅**时才求值（与真实 {@code AdminClient} 的惰性一致）。
     */
    private static AdminClient adminDeferring(Supplier<Mono<Optional<ConfigSnapshot>>> supplier) {
        return new AdminClient() {
            @Override
            public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
                return Mono.just(Optional.empty());
            }

            @Override
            public Mono<Optional<ConfigSnapshot>> configSnapshot() {
                return Mono.defer(supplier);
            }
        };
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
     * 决策 6：admin 挂了也要继续服务手上已有的陈旧快照 —— 而且**不再为它付出回源代价**。
     *
     * <p><b>这个场景必须真的走到 admin 那一跳</b>：本地非空时 {@code resolve()} 直接返回本地副本，
     * 那个会抛异常的 admin 替身根本不会被调用 —— 于是「先问 admin 再退回缓存」的实现也会通过。
     * 所以这里先让第一跳成功（拿到 v66 并让「最近一次成功快照」记住它），再让本地过期、Redis 读空、
     * admin 开始抛异常。
     *
     * <p><b>2026 二次复审修复 1 改变了这第二次读的期望</b>：旧实现把 {@code refreshBlocking()}
     * 放在读「最近快照」**之前**，所以第二次读要先付一次完整回源（最长 5 s 阻塞）才肯服务那份
     * 本来就在手上的快照，本用例因此断言过 {@code adminCalls >= 2}。现在正确的行为是
     * **一次回源都不做**：手上还有快照时回源毫无意义，只会白白拖住 event loop 并砸向已经出问题的
     * admin。所以断言改成 {@code adminCalls == 1}（只有第一跳那一次），并且必须仍然服务 66。
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
        assertThat(adminCalls).as("前置条件：第一跳恰好回源一次").hasValue(1);

        // 「已经过期」的最强形式：缓存里一份都不剩（本地被清、Redis 读空），admin 也开始抛。
        cache.invalidateLocal();
        assertThat(cache.local()).as("前置条件：本地层必须为空，否则本用例到不了 admin 那一跳").isEmpty();

        ConfigSnapshot served = client.current();

        assertThat(served.channels()).as("admin 挂了也要继续用陈旧快照（决策 6）")
                .extracting(ChannelDescriptor::id).containsExactly(66L);
        assertThat(served.version()).isEqualTo(4L);
        assertThat(adminCalls).as("手上还有快照时**一次回源都不该做**（修复 1：旧实现每次请求都先付一次回源）")
                .hasValue(1);
    }

    /**
     * 复审修复 3（控制器裁决）：两级缓存**都**不可用且 admin 也挂了时，仍然要服务最近一次成功快照
     * —— 决策 6 的字面要求是「**哪怕已过期**」，而仅靠两级缓存兜底的实现在这里会退化成单条遗留渠道、
     * 丢掉多渠道路由。
     *
     * <p>这三条同时成立才算通过：本地层为空、Redis 读空（模拟 TTL 到期或 Redis 不可用）、admin
     * 不可达；仍然拿到那条多渠道路由（id=31，不是 {@link LegacyChannel#ID}），并且不抛异常，
     * 且这份内存副本**不写回 Redis**（否则会复活一条陈旧共享条目）。
     *
     * <p><b>三次复审修复 3：故障期内要读两次。</b>只读一次时，「admin 只被调用一次」这条断言对
     * 修复前的实现同样成立（第一次读无论如何都要回源一次），所以它证明不了「故障期内的重复读不
     * 再回源」。第二次读把这条区分开：修复前每次读都先付一次回源，计数会到 2。
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

        // 故障期内**再读一次**：这一条才让下面的计数真正有判别力（三次复审修复 3）。
        // 只读一次时，`>= 1` 与 `hasValue(1)` 对修复前的实现**都**成立 —— 第一次读本来就是一次回源，
        // 所以上一轮那次「改成精确 1」的断言改动是空转的。加上第二次读之后，只有「窗口内一次回源
        // 都不做」的实现才会停在 1；修复前（每个请求都先付一次回源）这里会变成 2。
        ConfigSnapshot servedAgain = client.current();

        assertThat(servedAgain.channels()).as("故障期内重复读必须继续服务最近一次成功快照")
                .extracting(ChannelDescriptor::id).containsExactly(31L);
        assertThat(servedAgain.version()).isEqualTo(3L);
        assertThat(adminCalls).as("故障期内的第二次读也不得回源（修复前每个请求都先回源一次，这里会是 2）")
                .hasValue(1);
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
     *
     * <p><b>冷却窗口取 100 ms 并在重试前等 250 ms</b>（2026 二次复审修复 1）：成功回源同样会把
     * 「下一次回源」推后一个窗口 —— 这是修复 1 的「尝试之间最小间隔」在成功路径上的同一份语义。
     * 本用例要证明的是「槽位被释放」，所以跨过窗口再读；用 100 ms 而不是 5 s 只是为了不等 5 秒。
     */
    @Test
    void secondRefreshCallsAdminAgainAfterTheLocalTtlLapses() throws Exception {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        GatewayConfigProperties shortCooldown = new GatewayConfigProperties(Duration.ofMillis(100),
                Duration.ofMinutes(10), 300, Duration.ofMillis(100));
        ConfigCache cache = cache(shortCooldown);
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            int call = adminCalls.incrementAndGet();
            return Optional.of(snapshot(call, 70L + call));
        }), upstream, shortCooldown, registry);

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
     *
     * <p><b>冷却窗口改成 100 ms</b>（2026 二次复审修复 1）：修复 1 之后，失败的尝试会开启一个冷却
     * 窗口，窗口内的第二次读**按设计**不碰 admin。本用例要证明的是「不会被永久钉死」，所以必须
     * 跨过那个窗口再读 —— 这里用 100 ms 窗口 + 200 ms 等待（而不是 5 s 生产默认值）只是为了不让用例
     * 白等 5 秒；「窗口内一次都不回源」由 {@code originRetriesAreSuppressedInsideTheCooldownWindow} 钉住。
     */
    @Test
    void failedFirstLoadDoesNotWedgeTheInstance() throws Exception {
        // Redis 全程读空（模拟 Redis 也挂）：唯一能救回来的只有重新回源 admin。
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        GatewayConfigProperties shortCooldown = new GatewayConfigProperties(Duration.ofSeconds(30),
                Duration.ofMinutes(10), 300, Duration.ofMillis(100));
        ConfigCache cache = cache(shortCooldown);
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            if (adminCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("admin 不可达");
            }
            return Optional.of(snapshot(5L, 55L));
        }), upstream, shortCooldown, registry);

        ConfigSnapshot degraded = client.current();

        assertThat(degraded.channels()).as("第一次回源失败且 Redis 也空，只能退化到遗留单渠道")
                .extracting(ChannelDescriptor::id).containsExactly(LegacyChannel.ID);
        assertThat(adminCalls.get()).as("第一次读必须真的试过 admin").isGreaterThanOrEqualTo(1);
        assertThat(cache.local()).as("失败的第一次回源不得回填任何缓存").isEmpty();

        // 跨过冷却窗口（按设计，窗口内的读不会碰 admin）。
        Thread.sleep(200L);

        // admin 恢复：下一次读必须重新回源并恢复正常路由，而不是继续重放那个空信号。
        int beforeRetry = adminCalls.get();
        ConfigSnapshot recovered = client.current();

        assertThat(recovered.channels()).as("admin 恢复后必须能重新回源，不能被第一次失败永久钉死")
                .extracting(ChannelDescriptor::id).containsExactly(55L);
        assertThat(recovered.version()).isEqualTo(5L);
        assertThat(adminCalls.get()).as("冷却窗口过后必须再次调用 admin").isEqualTo(beforeRetry + 1);
    }

    @Test
    void legacyFallbackIsNotCachedAsARealSnapshot() {
        when(values.get(anyString())).thenReturn(null);

        client(adminReturning(Optional::empty)).current();

        verify(values, never()).set(eq(ConfigCache.REDIS_KEY), anyString(), any(Duration.class));
    }

    /**
     * 失效必须**真的丢掉本地层**：本地副本与 Redis 的版本**相同**（渠道不同）时，失效之后仍然
     * 服务那份陈旧的本地副本，就说明「失效」是空实现。
     *
     * <p>{@code invalidate(long)} 现在还会删共享条目并把水位抬到该版本（D4）—— 那两半由
     * {@code ConfigInvalidateContractTest} 钉住，**不在这里**断言：Mockito 的普通桩不会因为
     * {@code delete()} 而改变 {@code get()} 的返回值，用它证明「共享条目被删掉了」是假的。
     * 本用例因此只量本地层让位（以及「让位之后不会再把失效前那份答出去」）。
     *
     * <p>brief 原本的断言是 {@code verify(values, atLeast(2)).get(REDIS_KEY)} —— 那个断言**不会失败**：
     * 每次 {@code current()} 都要做一次版本探测，所以哪怕 {@code invalidate} 是空实现，
     * 两次读也一定是 2 次 {@code get}。而且那个版本号本身是错的（原用例只调了一次
     * {@code current()}，压根达不到 2 次）。这里改成直接量**本地层本身**是否被清掉：
     * 版本号刻意与 Redis 相同，{@code fromRedis.version() > local.version()} 不成立，
     * 于是「没有真的失效」就一定会拿着陈旧的本地副本答下去。
     */
    @Test
    void invalidationDropsTheLocalCopySoTheStaleLocalSnapshotIsNeverServedAgain() {
        // Redis 里是 v5/id=22；本地被塞进同样 v5 但渠道不同的陈旧副本。
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshot(5L, 22L)));
        ConfigCache cache = cache();
        cache.putLocal(snapshot(5L, 99L));
        ConfigClient client = new ConfigClient(cache, adminReturning(Optional::empty), upstream, properties, registry);

        // 消息里带的版本就是这份共享条目的版本（5）：清本地、删共享条目、水位抬到 5。
        client.invalidate(5L);

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

    /**
     * 复审修复 1（重要）：**回源失败之后必须进入冷却窗口**。
     *
     * <p>上一轮修复释放了单飞槽位，但释放之后就再也没有任何东西限制重试节奏 —— 「5 s 超时」
     * 管的是单次尝试的**时长上限**，不是两次尝试之间的**间隔下限**。admin 快速失败（连接被拒，
     * 或这份桩这样返回空快照）时，每个请求都会各付一次回源：重试速率 ≈ 请求速率。
     *
     * <p>fixture 刻意让**两份不同的快照先后注入**（v1 → v2）：
     * <ul>
     *   <li>第一步：本地空 + Redis 空 + admin 快速失败 → 冷却窗口开启，服务遗留渠道；</li>
     *   <li>第二步：本地空 + Redis 空 + admin **已经好了**（回 v2），但在窗口内 ——
     *       必须**一次都不回源**，继续服务遗留渠道。若冷却不存在，这里会立刻拿到 v2；</li>
     *   <li>第三步：等窗口过去，回源恢复、拿到 v2。</li>
     * </ul>
     * 第三步同时钉住了「冷却不是永久熔断」：窗口一过就必须能重新回源。
     *
     * <p>注意这里走的是**冷启动**路径（手上没有 last-good，只有遗留渠道）：一旦手上有一份
     * 可用快照，冷却窗口内同样短路；但**进入窗口之前**的那一次回源仍然会发生（冷却只管
     * 「两次尝试之间的间隔」，不管「第一次尝试」）—— 那种「手上已有快照」的场景由
     * {@code adminFailureKeepsServingTheCachedSnapshot} 与
     * {@code combinedRedisAndAdminOutageStillServesTheLastGoodSnapshot} 覆盖。
     */
    @Test
    void originRetriesAreSuppressedInsideTheCooldownWindow() throws Exception {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        AtomicInteger served = new AtomicInteger(1); // 1 = 尚未恢复, 2 = 已恢复
        // 冷却窗口取 **1 s**（生产默认是 5 s，理由见 GatewayConfigProperties）：本用例要证明的是
        // 「窗口内一次都不回源、窗口过后必须回源」，这一点与窗口的具体长度无关 —— 用 5 s 只会让
        // 用例白等 5 秒。生产的 5 s 由 GatewayConfigProperties 的 @DefaultValue 与 application.yml
        // 钉住，那才是它该待的地方。窗口从 300 ms 放宽到 1 s 是**去抖动**（三次复审修复 4）：
        // 8 次 current() 必须落在窗口内，机器一卡就可能越过 300 ms，那是一条与实现无关的红。
        ConfigCache cache = cache(new GatewayConfigProperties(Duration.ofSeconds(30), Duration.ofMinutes(10), 300,
                Duration.ofMillis(1000)));
        ConfigClient client = new ConfigClient(cache, adminDeferring(() -> {
            adminCalls.incrementAndGet();
            return Mono.just(served.get() == 2
                    ? Optional.of(snapshot(2L, 22L))         // 控制面恢复
                    : Optional.<ConfigSnapshot>empty());     // 快速失败：控制面「没有快照」
        }), upstream, new GatewayConfigProperties(Duration.ofSeconds(30), Duration.ofMinutes(10), 300,
                Duration.ofMillis(1000)), registry);

        ConfigSnapshot first = client.current();
        assertThat(first.channels()).as("第一跳快速失败，手上什么都没有 → 遗留单渠道")
                .extracting(ChannelDescriptor::id).containsExactly(LegacyChannel.ID);
        assertThat(adminCalls).as("第一跳必须真的试过一次").hasValue(1);

        served.set(2);
        // 冷却窗口内连打 8 次：admin 已经好了，但窗口没过，一次回源都不该发生。
        for (int i = 0; i < 8; i++) {
            ConfigSnapshot duringCooldown = client.current();
            assertThat(duringCooldown.channels()).as("冷却窗口内不得回源，继续服务手上那份（这里是遗留渠道）")
                    .extracting(ChannelDescriptor::id).containsExactly(LegacyChannel.ID);
        }
        assertThat(adminCalls).as("冷却窗口内的 8 个请求一次回源都不该产生（旧实现是 8 次，重试速率≈请求速率）")
                .hasValue(1);
        assertThat(client.refreshFailureCount())
                .as("只有那一次真实尝试算失败；被冷却挡下的 8 次请求不是尝试，也就没有多记 8 个失败")
                .isEqualTo(1.0d);

        // 窗口过去：必须能重新回源（冷却不是永久熔断）。
        Thread.sleep(1200L);
        assertThat(client.current().channels()).as("窗口过后必须重新回源并拿到新快照")
                .extracting(ChannelDescriptor::id).containsExactly(22L);
        assertThat(adminCalls).as("窗口过后只多一次回源").hasValue(2);
    }

    /**
     * 复审修复 2（重要）：**WARN 的条数必须有界**。
     *
     * <p>上一轮修复把单飞槽位释放之后，「配置快照回源失败」的 WARN 变成了**每个失败的尝试一条**，
     * 而「最近快照」的 WARN 是**每个被服务的请求一条** —— 快速失败时整个故障期最多每请求两条
     * WARN。现在两条路径共用同一个「一次故障期只播报一条（之后每分钟一条）」的闸门，
     * 同时 {@code aihub.config.snapshot.refresh.failures} 把每次真实尝试都计数：
     * <b>日志给人看、计数给告警看</b>。
     *
     * <p>本用例把日志事件抓下来数条数（与 {@code ChannelKeyDecryptorLoggingTest} 同一套手法），
     * 同时钉住指标确实是 1 —— 只限流日志而不计数，等于把可观测性一起限没了。
     */
    @Test
    void outageWarningsAreBoundedToOnePerEpisode() {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigCache cache = cache();
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.empty(); // 控制面不可达：与「admin 挂了」同一条降级路径
        }), upstream, properties, registry);

        Logger clientLogger = (Logger) LoggerFactory.getLogger(ConfigClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        clientLogger.addAppender(appender);
        try {
            // 第一次读：三级全空 → 一次回源（失败）→ 转遗留渠道；此后 4 次都在冷却窗口内。
            for (int i = 0; i < 5; i++) {
                ConfigSnapshot served = client.current();
                assertThat(served.channels()).as("冷启动 + 控制面不可达 → 遗留单渠道（决策 6），绝不抛异常")
                        .extracting(ChannelDescriptor::id).containsExactly(LegacyChannel.ID);
            }
        } finally {
            clientLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(adminCalls).as("5 个请求只产生一次回源（旧实现是 5 次：重试速率≈请求速率）").hasValue(1);
        assertThat(appender.list).as("一次故障期只能播报一条 WARN（旧实现是每请求最多两条）").hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).as("那条 WARN 必须说明失败原因")
                .contains("配置快照回源失败");
        assertThat(client.refreshFailureCount()).as("WARN 被限流，但每一次真实失败尝试都必须计数（告警架在计数上）")
                .isEqualTo(1.0d);
    }

    /**
     * 三次复审修复 2（次要）：**「一次故障期一条」的 WARN 上界不能被「命中/未命中交替」绕过**。
     *
     * <p>{@link ConfigClient#current()} 在任何一次缓存命中时都会调 {@code clearDegraded()}，把
     * 「正处于故障期」的标记清回 {@code false}；而 {@code logDegradedOncePerEpisode} 原来只在
     * **CAS 失败**（即「本期已经打过」）的那条路径上检查 60 s 播报间隔 —— CAS **成功**那条
     * （「重新进入故障期」）直接放行。于是二级缓存抖动（命中 → 未命中 → 命中 → 未命中）会让每个
     * 周期都立刻打一条 WARN：上界从「一次故障期一条」退化成「每周期一条」，无界。
     *
     * <p>fixture 就是那个抖动：每一轮先让 Redis 命中（命中会把 lastGood 填成 v9，并清掉故障期标记），
     * 再让本地层过期 + Redis 读空 —— 此时冷却窗口仍然有效，于是 {@code current()} 走
     * {@code serveRetainedOrLegacy()} 并尝试播报一条降级 WARN。admin 全程不可达，只被真正问过一次。
     *
     * <p>断言 {@code hasSize(1)}：修复前这里会是 5（每轮一条）。
     */
    @Test
    void flappingSecondLevelCacheKeepsOutageWarningsBounded() {
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigCache cache = cache();
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.empty(); // admin 全程不可达
        }), upstream, properties, registry);

        Logger clientLogger = (Logger) LoggerFactory.getLogger(ConfigClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        clientLogger.addAppender(appender);
        try {
            for (int i = 0; i < 5; i++) {
                // 「命中」：二级缓存回来了（别的实例重新写入 / 网络抖动恢复）—— 这一跳会清掉故障期标记。
                when(values.get(anyString())).thenReturn(ConfigSnapshotCodec.encode(snapshot(9L, 21L)));
                cache.invalidateLocal();
                assertThat(client.current().channels()).as("二级命中必须照常服务它那一份")
                        .extracting(ChannelDescriptor::id).containsExactly(21L);

                // 「未命中」：二级又读不到、本地也过期了，而 admin 仍然不可达。
                // 冷却窗口内 → 服务最近一次成功快照，并**尝试**播报一条降级 WARN。
                when(values.get(anyString())).thenReturn(null);
                cache.invalidateLocal();
                assertThat(client.current().channels()).as("抖动期间仍然必须服务最近一次成功快照")
                        .extracting(ChannelDescriptor::id).containsExactly(21L);
            }
        } finally {
            clientLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(adminCalls).as("admin 只被真正问过一次（其余请求都落在冷却窗口内）").hasValue(1);
        assertThat(appender.list).as("命中/未命中交替不得让 WARN 变成每周期一条（修复前是 5 条）").hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).as("那一条仍是「回源失败」那条")
                .contains("配置快照回源失败");
    }

    /**
     * 复审修复 3（次要）：**缓存写入必须拒绝版本倒退**。
     *
     * <p>单飞槽位被释放之后，输掉 CAS 的调用者可能读到 {@code null} 并把**自己那条从未发布**的
     * {@link Mono} 交出去，于是同一次 miss 产生第二个并发回源。而 {@code load()} 的两级写入
     * 原本是**无条件**的，所以先发起的旧响应晚到时会覆盖掉新快照 —— 本地与 Redis 两侧都能倒退。
     *
     * <p>本用例针对的正是那个方向：v5 已经在两级缓存里（回源拿到的），随后把 v3 直接写进去。
     * 没有版本守卫时，本地会变成 v3、Redis 会被 v3 覆盖，后续读也会服务 v3（回归）。
     */
    @Test
    void olderSnapshotNeverOverwritesANewerOneInEitherCacheLayer() {
        // 前置：回源拿到 v5/id=55，两级都被回填。
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigCache cache = cache();
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.of(snapshot(5L, 55L));
        }), upstream, properties, registry);
        client.refresh().block();
        assertThat(cache.local()).as("前置条件：本地层里有 v5").isPresent()
                .get().extracting(ConfigSnapshot::version).isEqualTo(5L);
        assertThat(adminCalls).as("前置条件：恰好一次回源").hasValue(1);
        clearInvocations(values);

        // 晚到的一条**更旧**的快照（旧实现会无条件覆盖两级缓存）。
        cache.putLocal(snapshot(3L, 33L));
        cache.writeRedis(snapshot(3L, 33L));

        assertThat(cache.local()).as("本地层绝不能被更旧的版本覆盖")
                .get().extracting(ConfigSnapshot::version).isEqualTo(5L);
        verify(values, never()).set(eq(ConfigCache.REDIS_KEY), eq(ConfigSnapshotCodec.encode(snapshot(3L, 33L))),
                any(Duration.class));

        // 新版本仍然可以正常写入（守卫只拦倒退，不拦前进）。
        cache.putLocal(snapshot(6L, 66L));
        assertThat(cache.local()).as("更新的版本必须照常写入").isPresent()
                .get().extracting(ConfigSnapshot::version).isEqualTo(6L);

        // 回归方向：后续读到的必须还是更新的那一份，而不是那条晚到的 v3。
        ConfigSnapshot served = client.current();
        assertThat(served.version()).as("更旧的快照绝不能赢得后续的服务").isEqualTo(6L);
        assertThat(served.channels()).extracting(ChannelDescriptor::id).containsExactly(66L);
    }

    /**
     * 三次复审修复 1（重要）：**同版本的重新写入必须放行**。
     *
     * <p>守卫写成 {@code version() <= 水位} 时，「两级缓存都过期 → 回源拿到一个**没有变化**的版本」
     * 这条路上，Redis 层会**永远**填不回去：本地层因为 key 已经过期而正常回填（{@code merge} 在
     * 键不存在时直接插入），共享缓存却因为 {@code 10 <= 10} 被拒。此后每个实例都以本地 TTL 的
     * 节奏（≈2 次/分钟/实例）去敲 admin，而不是大约每 10 分钟一次 —— 正是两级缓存要消除的那种
     * 控制面压力；Redis 重启或一次瞬时写失败对那个版本留下同样的永久后果。等号不是「倒退」。
     *
     * <p>fixture 里控制面**始终**返回 v10（没有停机、也没有配置变更）：第一次读写入 Redis
     * （水位=10），等本地 TTL 与冷却窗口都过去（两者都取 80 ms，模拟 Redis 的 10 分钟 TTL 也到期）
     * 后第二次读三级全空、重新回源，拿到的还是 v10 —— 它必须被重新写进 Redis。
     * 改成严格比较之前，下面第二个 {@code verify(values).set(...)} 会「Wanted but not invoked」。
     *
     * <p>反方向（更旧的版本仍被拒绝）由
     * {@link #olderSnapshotNeverOverwritesANewerOneInEitherCacheLayer} 钉住，本修复没有放宽它。
     */
    @Test
    void equalVersionIsRewrittenToRedisAfterBothCacheLayersLapse() {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        GatewayConfigProperties shortLived = new GatewayConfigProperties(Duration.ofMillis(80),
                Duration.ofMinutes(10), 300, Duration.ofMillis(80));
        // 假时钟：Caffeine 的本地 TTL 与 ConfigClient 的冷却窗口接在**同一个**读数上。
        //
        // 为什么必须这样：本用例的判别点是「两级缓存都过期之后，同一个版本必须被重新写回 Redis」
        // （等号放行，三次复审修复 1）。用真实时钟时它有两个互相拉扯的时间要求 —— 先要 80 ms TTL 与
        // 80 ms 冷却窗口**都**过去（否则第二跳根本到不了 admin），然后还必须在**紧接着的 80 ms 之内**
        // 观察到刚填回本地的那个条目（旧断言 `assertThat(cache.local()).isPresent()`）。
        // 后一半是纯运气：任何 ≥80 ms 的停顿（GC、CI 负载、整反应堆并发）都会把它翻成空。
        // M4 Task 1 的全量跑里实测红过一次（FAIL/PASS/PASS），机制就是这一条。
        // 推进假时钟取代 `Thread.sleep(150)`，断言一条都没有放宽。
        FakeClock clock = new FakeClock();
        when(redis.opsForValue()).thenReturn(values);
        ConfigCache cache = new ConfigCache(redis, shortLived, clock.ticker());
        ConfigClient client = new ConfigClient(cache, adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.of(snapshot(10L, 44L)); // 控制面没有变化：每次都是同一个 v10
        }), upstream, shortLived, registry, clock);

        assertThat(client.current().version()).as("第一次读：回源并回填两级").isEqualTo(10L);
        verify(values).set(eq(ConfigCache.REDIS_KEY), eq(ConfigSnapshotCodec.encode(snapshot(10L, 44L))),
                eq(shortLived.snapshotTtl()));

        // 本地 80 ms TTL 与 80 ms 冷却窗口都过去（Redis 那份按 fixture 也已经读空）。
        clock.advance(Duration.ofMillis(150));
        assertThat(cache.local()).as("前置条件：本地副本必须已经过期").isEmpty();
        clearInvocations(values);

        ConfigSnapshot second = client.current();

        assertThat(second.version()).as("控制面没变，第二次读仍然服务 v10").isEqualTo(10L);
        assertThat(adminCalls).as("前置条件：确实重新回过源（不是被冷却窗口挡下的）").hasValue(2);
        assertThat(cache.local()).as("本地层被重新填入").isPresent();
        verify(values).set(eq(ConfigCache.REDIS_KEY), eq(ConfigSnapshotCodec.encode(snapshot(10L, 44L))),
                eq(shortLived.snapshotTtl()));
    }

    /**
     * 三次复审修复 1（后半，重要）：**从 Redis 观察到的版本必须抬升写入水位**。
     *
     * <p>只让 {@code writeRedis} 记住「本实例写过的版本」是不够的：如果这条实例在**回源在途**时
     * 从 Redis 读到了一个更新的版本（别的实例刚写进去的 v10），随后那条更旧的在途响应（v5）回来，
     * 本地层会被守卫正确拒绝，但 Redis 的水位仍是「从未写过」，于是 v5 会被写进**共享**条目 ——
     * 毒化所有其他实例。修复：{@code readRedis()} 把观察到的版本也推进水位
     * （{@code accumulateAndGet(..., Math::max)}），配合严格 {@code <} 比较，同版本的正常回填仍然放行。
     *
     * <p>这一个子场景**不需要 Lua/CAS**（与代码里原先的披露相反）：观察与写入都在同一个进程里，
     * 水位就是那次观察的结论。仍然拦不住的是**跨进程**那一半（本实例既没读到也没写过别人刚写的
     * v7 时，更旧的 v5 会覆盖它），那才需要 Redis 侧的原子「读-比-写」。
     *
     * <p>fixture 用一个在订阅时才求值的 admin 替身：它在回源真正开始后调用
     * {@code cache.readRedis()} 并采用那份 v10（复现 {@code resolve()} 在同一次在途回源里做的
     * 「读到 Redis 的更新版本 → 刷本地」），然后才返回那条更旧的 v5。修复前 v5 会写进 Redis。
     */
    @Test
    void olderInFlightResultIsNotWrittenToRedisAfterANewerVersionWasObservedInRedis() {
        when(values.get(anyString())).thenReturn(ConfigSnapshotCodec.encode(snapshot(10L, 44L)));
        ConfigCache cache = cache();
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigClient client = new ConfigClient(cache, adminDeferring(() -> {
            adminCalls.incrementAndGet();
            // 回源在途时本实例从 Redis 观察到 v10（并像 resolve() 那样刷新本地副本）。
            cache.readRedis().ifPresent(cache::putLocal);
            return Mono.just(Optional.of(snapshot(5L, 55L))); // 晚到的、更旧的一次回源结果
        }), upstream, properties, registry);

        ConfigSnapshot loaded = client.refresh().block();

        assertThat(loaded).as("回源本身仍返回它拿到的快照；被拒绝的只是对共享缓存的写入").isNotNull();
        assertThat(loaded.version()).isEqualTo(5L);
        assertThat(adminCalls).hasValue(1);
        assertThat(cache.local()).as("本地层已由更新的 v10 占据，v5 不得覆盖它").isPresent()
                .get().extracting(ConfigSnapshot::version).isEqualTo(10L);
        verify(values, never()).set(eq(ConfigCache.REDIS_KEY), eq(ConfigSnapshotCodec.encode(snapshot(5L, 55L))),
                any(Duration.class));

        // 正向：更新的版本照常写得进去（守卫只拦倒退）。
        cache.writeRedis(snapshot(11L, 66L));
        verify(values).set(eq(ConfigCache.REDIS_KEY), eq(ConfigSnapshotCodec.encode(snapshot(11L, 66L))),
                eq(properties.snapshotTtl()));
    }

    /**
     * 复审修复 4（次要）：**第四个终态是「取消/超时」**，它也必须释放单飞槽位。
     *
     * <p>修复 1 的论证枚举了成功 / 空完成 / 异常三种终态，但 {@code refreshBlocking()} 的
     * {@code block(5 s)} 超时走的是**取消**：{@code doFinally} 同样会触发，所以名额不会泄漏。
     * 这条此前没有任何用例覆盖。
     *
     * <p>fixture：admin 第一次返回一条**永不终结**的流（挂起的控制面），{@code refreshBlocking()}
     * 在 5 s 上限处超时并取消；随后让 admin 正常应答，下一次读必须能重新回源。
     * 若超时不释放名额，第二次读会永远拿到同一条挂起的流（或那条被重放的空信号），
     * 于是这里读不到 v5/id=55。
     *
     * <p>真实等待 5 s 而不是缩短超时：改动 {@code REFRESH_TIMEOUT} 或为测试加宽构造器，
     * 都会让这条用例钉住的「生产配置」不再是被验证的那一个。
     *
     * <p>冷却窗口在这里取 100 ms 并在重试前等待：超时本身也是一次失败的尝试，窗口内按设计不再回源。
     * 本用例要证明的是**取消释放了单飞名额**，所以必须跨过冷却窗口再读。
     */
    @Test
    void timeoutCancellationReleasesTheSingleflightSlot() throws Exception {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        AtomicInteger replyAfterTheHang = new AtomicInteger(); // 0 = 挂起, 1 = 正常应答
        GatewayConfigProperties shortCooldown = new GatewayConfigProperties(Duration.ofSeconds(30),
                Duration.ofMinutes(10), 300, Duration.ofMillis(100));
        ConfigCache cache = cache(shortCooldown);
        ConfigClient client = new ConfigClient(cache, adminDeferring(() -> {
            adminCalls.incrementAndGet();
            return replyAfterTheHang.get() == 0
                    // 永不终结：触发 refreshBlocking 的超时。
                    ? Mono.<Optional<ConfigSnapshot>>never()
                    : Mono.just(Optional.of(snapshot(5L, 55L)));
        }), upstream, shortCooldown, registry);

        ConfigSnapshot hung = client.current();

        assertThat(hung.channels()).as("回源超时且手上什么都没有 → 遗留单渠道，绝不抛异常")
                .extracting(ChannelDescriptor::id).containsExactly(LegacyChannel.ID);
        assertThat(adminCalls).as("第一次读必须真的试着回源（并在这里超时）").hasValue(1);
        assertThat(cache.local()).as("超时的回源不得回填任何缓存").isEmpty();
        assertThat(registry.get(ConfigClient.REFRESH_FAILURES_METRIC).counter().count())
                .as("超时也是一次失败的尝试，必须计数").isEqualTo(1.0d);

        // 跨过冷却窗口（超时已经耗掉 5 s，这里只是让 100 ms 的测试窗口确定过期）。
        Thread.sleep(200L);

        // 控制面恢复：这一次读必须能重新回源，说明超时/取消已经把单飞名额释放了。
        replyAfterTheHang.set(1);
        ConfigSnapshot recovered = client.current();

        assertThat(recovered.channels()).as("超时后名额必须被释放，否则实例永远卡在那条挂起的回源上")
                .extracting(ChannelDescriptor::id).containsExactly(55L);
        assertThat(recovered.version()).isEqualTo(5L);
        assertThat(adminCalls).as("超时后必须能再回源一次").hasValue(2);
    }
}
