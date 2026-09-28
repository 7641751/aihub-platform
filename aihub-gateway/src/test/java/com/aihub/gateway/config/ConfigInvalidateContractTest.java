package com.aihub.gateway.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ConfigSnapshotCodec;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失效消息的**语义契约**（D4 / M3 登记的 A14）：一次失效必须同时动三样东西 —— 本地层、
 * 共享条目 {@link ConfigCache#REDIS_KEY}、以及写入水位。
 *
 * <p>它钉的是 M3 那个用户可见的缺口（{@code docs/CONVENTIONS.md} §6.6 的 10 分钟上界）：
 * 只清本地层的失效**什么都没做** —— 紧接着的 {@code resolve()} 会从 Redis 读到**同样陈旧**的
 * 共享条目并采用它，于是「配置变了」这个信号对多实例部署完全无效（实测 101 秒仍不可见，
 * 只有人工 {@code DEL aihub:config:snapshot} 才收敛）。
 *
 * <p>不起 Spring 上下文、不碰 socket：与 {@code ConfigCacheTest} 同一手法（Redis 是桩，
 * {@code AdminClient} 是 Mockito mock），因此不依赖 Docker / 活 Redis / 活 broker（全局约束）。
 *
 * <p><b>水位的方向是「抬到消息里的版本」，不是「重置」</b>：重置成 {@code NO_VERSION} 会拆掉
 * 「挡住在飞的旧回填把刚删掉的陈旧条目写回去」的唯一护栏 —— 那等于让这次失效白做（N1/I9）。
 * 第一条用例的第二半就是这个反例的可证伪形式。
 */
@SuppressWarnings("unchecked")
class ConfigInvalidateContractTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final AdminClient admin = mock(AdminClient.class);
    /** 每个用例一个注册表：不让指标在用例之间串味（与 {@code ConfigCacheTest} 同一纪律）。 */
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final GatewayConfigProperties properties =
            new GatewayConfigProperties(Duration.ofSeconds(30), Duration.ofMinutes(10), 300, Duration.ofSeconds(5));

    private final UpstreamProperties upstream =
            new UpstreamProperties("http://127.0.0.1:11434", "synthetic-upstream-key", "legacy-model");

    /** 一份「可用」的快照（有渠道、有路由），否则 {@code ConfigClient} 会把它当空快照降级掉。 */
    private static ConfigSnapshot snapshotOfVersion(long version) {
        return new ConfigSnapshot(version, version * 10,
                List.of(new ChannelDescriptor(version, "ch-" + version,
                        "https://ch" + version + ".example.com", "v1:QUJD", 1, 60_000, "ACTIVE", 100, 0)),
                List.of(new ModelRouteDescriptor("m", version, 100, 0, "ACTIVE")),
                List.of(), "m");
    }

    @Test
    void invalidationDropsTheSharedEntryAndRaisesTheWatermarkToTheInvalidatedVersion() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshotOfVersion(4L)));
        ConfigCache cache = new ConfigCache(redis, properties);
        cache.readRedis();                                   // 水位到 4

        cache.invalidateAllCaches(9L);                       // 消息里带的是 9

        verify(redis).delete(ConfigCache.REDIS_KEY);         // ① 共享条目必须被删

        // ② 水位不能被重置成 NO_VERSION：那样一个**在飞的旧回填**（版本 5）会把刚删掉的
        //    陈旧共享条目重新写回去，等于让这次失效白做。水位应当抬到消息里的 9。
        cache.writeRedis(snapshotOfVersion(5L));
        verify(valueOps, never()).set(eq(ConfigCache.REDIS_KEY), anyString(), any(Duration.class));

        cache.writeRedis(snapshotOfVersion(9L));             // 不旧于水位 → 允许
        verify(valueOps).set(eq(ConfigCache.REDIS_KEY), anyString(), any(Duration.class));
    }

    @Test
    void afterInvalidationTheNextReadGoesToAdminInsteadOfServingTheStaleSharedEntry() {
        // 本地为空 + Redis 里有一份旧快照 + admin 有一份新快照。
        ConfigSnapshot stale = snapshotOfVersion(4L);
        ConfigSnapshot newSnapshot = snapshotOfVersion(9L);
        // Redis 夹具：DEL 之后必须读不到东西。Mockito 的普通桩不会因为 delete() 改变 get() 的
        // 返回值 —— 那样这条用例在**任何**实现下都会服务旧版本，就永远绿不了（也就测不出 M3 的缺口）。
        // 这里只补上「共享条目真的被删掉了」这一条语义，让下面那句断言有判别力。
        AtomicReference<String> shared = new AtomicReference<>(ConfigSnapshotCodec.encode(stale));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(ConfigCache.REDIS_KEY)).thenAnswer(invocation -> shared.get());
        when(redis.delete(ConfigCache.REDIS_KEY)).thenAnswer(invocation -> shared.getAndSet(null) != null);
        when(admin.configSnapshot()).thenReturn(Mono.just(Optional.of(newSnapshot)));

        ConfigCache cache = new ConfigCache(redis, properties);
        ConfigClient client = new ConfigClient(cache, admin, upstream, properties, registry);

        client.invalidate(9L);
        ConfigSnapshot served = client.current();

        assertThat(served.version()).as("M3 的缺口：invalidate() 只清本地，紧接着 resolve() 又会采用 Redis 里的旧条目")
                .isEqualTo(newSnapshot.version());
        verify(admin, times(1)).configSnapshot();
    }
}
