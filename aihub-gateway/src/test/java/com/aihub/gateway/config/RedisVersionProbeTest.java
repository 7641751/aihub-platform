package com.aihub.gateway.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>「本地命中时要不要探一次 Redis 版本」必须跟着主动失效通道的有无走</b>（2026-10-06 加）。
 *
 * <p><b>为什么</b>：{@code ConfigClient.resolve()} 过去**每个请求**都会读一次 Redis 版本
 * （{@code cache.readRedis()}），那是「决策 16（不写 Pub/Sub）之下唯一的跨实例收敛手段」——
 * {@code ConfigCacheTest} 里那段刻意偏离的说明就是它。而 **M4 已经接通主动失效广播**
 * （`aihub:config:invalidate`，验收实测收敛 0.03 / 0.1 秒）⇒ 在**通道真的接上**的部署形态里，
 * 这次逐请求读取是**多余**的；在**没接上**的形态里（M3 形态、以及本仓库的测试环境，
 * 见 {@code src/test/resources/application.properties}）它仍然是唯一的收敛手段，必须保留。
 *
 * <p><b>为什么它在故障期特别重要</b>：Redis 挂掉时**失效通道同时也不可用**，
 * 探测既拿不到结论、又要付满一次 `spring.data.redis.timeout`（2026-10-06 实测：2 s × 5–6 条串行
 * = 单请求 10–12 s；收到 300 ms 后 ~1.65 s）。省掉它，省掉的是**纯粹的等待**。
 *
 * <p><b>两个方向都钉</b>：只钉「不读 Redis」会把功能删成「谁都不读」也算绿 ——
 * 那在「没有失效通道」的部署里会**静默退回 M3 的最长 10 分钟收敛**（正是 M4 修掉的缺口）。
 * 因此订阅关闭时必须**恰好读一次**。
 */
class RedisVersionProbeTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final GatewayConfigProperties properties =
            new GatewayConfigProperties(Duration.ofSeconds(30), Duration.ofMinutes(10), 300, Duration.ofSeconds(5));

    private final UpstreamProperties upstream =
            new UpstreamProperties("http://127.0.0.1:11434", "synthetic-upstream-key", "legacy-model");

    @Test
    void withTheInvalidationChannelWiredALocalHitDoesNotTouchRedisAtAll() {
        ConfigCache cache = cache();
        cache.putLocal(snapshot(7L));

        ConfigClient client = new ConfigClient(cache, neverCalledAdmin(), upstream, properties, registry,
                Clock.systemUTC(), /* probeRedisVersionOnLocalHit = */ false);

        assertThat(client.current().version()).as("本地命中必须直接服务本地那份").isEqualTo(7L);
        verify(values, never())
                .get(anyString());
    }

    @Test
    void withoutTheInvalidationChannelALocalHitStillProbesRedisExactlyOnce() {
        lenient().when(values.get(ConfigCache.REDIS_KEY)).thenReturn(null);
        ConfigCache cache = cache();
        cache.putLocal(snapshot(7L));

        ConfigClient client = new ConfigClient(cache, neverCalledAdmin(), upstream, properties, registry);

        assertThat(client.current().version()).as("本地命中（Redis 里没有更新的版本）").isEqualTo(7L);
        verify(values, times(1))
                .get(ConfigCache.REDIS_KEY);
    }

    private ConfigCache cache() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        return new ConfigCache(redis, properties);
    }

    private static ConfigSnapshot snapshot(long version) {
        return new ConfigSnapshot(version, version * 10,
                List.of(new ChannelDescriptor(1L, "ch", "https://ch.example.com", "v1:QUJD", 1, 60_000, "ACTIVE",
                        100, 0)),
                List.of(new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE")),
                List.of(), "m");
    }

    /** 两个用例都必须**一次都不回源**（本地能服务 ⇒ 回源是另一条路径的事）。 */
    private static AdminClient neverCalledAdmin() {
        return mock(AdminClient.class);
    }
}
