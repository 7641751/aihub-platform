package com.aihub.gateway.route;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.upstream.UpstreamProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGeneratorFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由是「一次请求打到哪条渠道」的唯一决策点，也是故障转移的**输入**：它返回的是**有序候选列表**，
 * 不是单个渠道 —— 切换能力来自「列表里还有下一个」。
 *
 * <p>选择算法的两条硬规则：① `priority` **数字小的先**（分层，主备就是两个 priority）；
 * ② 同一 priority 内按**权重随机**（而不是轮询或取第一个），这是「按权重分流」的字面含义。
 * 随机源由构造器注入，因此「权重分布」可以被确定性地断言（固定种子），测试不赌运气。
 *
 * <p>熔断渠道被排到**候选组最后**而不是直接删掉：删掉会让「唯一候选恰好被熔断」变成无候选
 * （客户端 404），而那是一种比「试一试」更糟的结果（决策 10 对全熔断场景的同一套理由）。
 */
class RouteResolverTest {

    private static ChannelDescriptor channel(long id, String name) {
        return new ChannelDescriptor(id, name, "https://" + name + ".example.com", "v1:QUJD", 1, 60_000,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    private static ConfigSnapshot snapshot(List<ModelRouteDescriptor> routes, List<ChannelDescriptor> channels) {
        return new ConfigSnapshot(1L, 2L, channels, routes, List.of(), "default-model");
    }

    private static RouteResolver resolver(ConfigSnapshot snapshot, ChannelCircuitBreaker breaker, long seed) {
        return new RouteResolver(() -> snapshot, breaker,
                RandomGeneratorFactory.of("L64X128MixRandom").create(seed));
    }

    /** 带注册表的测试接缝（brief 的 3 参数构造器仍原样保留）。 */
    private static RouteResolver resolver(ConfigSnapshot snapshot, ChannelCircuitBreaker breaker, long seed,
                                          MeterRegistry registry) {
        return new RouteResolver(() -> snapshot, breaker,
                RandomGeneratorFactory.of("L64X128MixRandom").create(seed), registry);
    }

    /** 永不熔断的替身：只覆盖 isOpen，其余方法不被本类使用。 */
    private static ChannelCircuitBreaker noCircuit() {
        return new ChannelCircuitBreaker(null, System::currentTimeMillis) {
            @Override
            public boolean isOpen(long channelId) {
                return false;
            }
        };
    }

    /** 指定一组「已熔断」渠道的替身。 */
    private static ChannelCircuitBreaker brokenOnly(long... brokenIds) {
        List<Long> broken = new ArrayList<>();
        for (long id : brokenIds) {
            broken.add(id);
        }
        return new ChannelCircuitBreaker(null, System::currentTimeMillis) {
            @Override
            public boolean isOpen(long channelId) {
                return broken.contains(channelId);
            }
        };
    }

    @Test
    void picksTheLowestPriorityNumberGroupFirst() {
        // 权重刻意**反着配**：高优先级的 id=2 只有 weight=1，低优先级的 id=1 有 weight=1000。
        // 若实现只做一次「忽略 priority 的全局权重抽取」，首位几乎必然是 id=1，这条用例就会红；
        // 因此它真正钉住的是「priority 压过 weight」，而不是两种排法都凑巧给出 [2, 1]。
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 1000, 5, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 1, 0, "ACTIVE")),
                List.of(channel(1L, "low-priority-number"), channel(2L, "primary")));

        assertThat(resolver(snapshot, noCircuit(), 7L).candidates("m"))
                .extracting(ChannelDescriptor::id)
                .containsExactly(2L, 1L);
    }

    @Test
    void fallsBackToTheNextPriorityGroupWhenTheFirstIsFullyCircuitBroken() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 1, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 1, 1, "ACTIVE")),
                List.of(channel(1L, "primary"), channel(2L, "standby")));

        assertThat(resolver(snapshot, brokenOnly(1L), 7L).candidates("m"))
                .extracting(ChannelDescriptor::id)
                .containsExactly(2L, 1L);
    }

    @Test
    void skipsCircuitBrokenChannelsInsideTheChosenGroup() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 100, 0, "ACTIVE")),
                List.of(channel(1L, "broken"), channel(2L, "healthy")));

        assertThat(resolver(snapshot, brokenOnly(1L), 7L).candidates("m"))
                .extracting(ChannelDescriptor::id)
                .as("熔断的排最后而不是被删掉")
                .containsExactly(2L, 1L);
    }

    /** 决策 10：全候选熔断时**仍然放行最好的一组**，而不是回 503/404。 */
    @Test
    void servesTheBestGroupAnywayWhenEveryCandidateIsCircuitBroken() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 100, 1, "ACTIVE")),
                List.of(channel(1L, "broken"), channel(2L, "standby")));

        List<ChannelDescriptor> candidates = resolver(snapshot, brokenOnly(1L, 2L), 7L).candidates("m");

        assertThat(candidates).as("全熔断也必须给出候选（best-effort）").isNotEmpty();
        assertThat(candidates).extracting(ChannelDescriptor::id).contains(1L, 2L);
        assertThat(candidates.get(0).id()).as("仍然优先最高优先级的组").isEqualTo(1L);
    }

    /**
     * 决策 10 的「最后手段」层同样必须**按权重随机**：全熔断时首选渠道不能永远由配置顺序决定，
     * 否则一条 weight=0 的渠道会永久压过 weight=1000 的渠道，relay 的首次尝试也就不再按权重分散。
     *
     * <p>{@code rounds} 从 4000 降到 600：每次 {@code candidates()} 在全熔断分支都会打一条 WARN
     * （决策 10 要求 WARN + 计数器），4000 轮会往日志里灌几千行噪声。600 轮对 1:3 的权重、
     * 25% 的期望比例来说，15%–35% 的带宽仍有 ~3 个标准差的余量，而修复前的行为是
     * **100%**（配置顺序永远赢），因此判别力没有被削弱 —— 这一点由反向变异实测过。
     */
    @Test
    void distributesByWeightInsideTheAllBrokenTierToo() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 300, 0, "ACTIVE")),
                List.of(channel(1L, "one"), channel(2L, "three")));
        RouteResolver resolver = resolver(snapshot, brokenOnly(1L, 2L), 20260923L);
        int firstCount = 0;
        int rounds = 600;

        for (int i = 0; i < rounds; i++) {
            if (resolver.candidates("m").get(0).id() == 1L) {
                firstCount++;
            }
        }

        assertThat(firstCount)
                .as("全熔断时最高优先级组内同样按权重随机（配置顺序不得成为永久首选）")
                .isBetween((int) (rounds * 0.15), (int) (rounds * 0.35));
    }

    @Test
    void unknownModelHasNoCandidates() {
        RouteResolver resolver = resolver(ConfigSnapshot.empty(), noCircuit(), 7L);

        assertThat(resolver.candidates("nope")).isEmpty();
        assertThatThrownBy(() -> resolver.primary("nope"))
                .isInstanceOf(RouteSelectionException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void distributesByWeightWithinTheSamePriority() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 300, 0, "ACTIVE")),
                List.of(channel(1L, "one"), channel(2L, "three")));
        RouteResolver resolver = resolver(snapshot, noCircuit(), 20260923L);
        int firstCount = 0;
        int rounds = 4_000;

        for (int i = 0; i < rounds; i++) {
            if (resolver.candidates("m").get(0).id() == 1L) {
                firstCount++;
            }
        }

        // 1:3 的权重 → 首位被 id=1 拿到的比例应在 25% 附近（±5% 的宽区间，避免偶发抖动）。
        assertThat(firstCount).isBetween((int) (rounds * 0.20), (int) (rounds * 0.30));
    }

    @Test
    void equalWeightsAreRoughlyFair() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 100, 0, "ACTIVE")),
                List.of(channel(1L, "a"), channel(2L, "b")));
        RouteResolver resolver = resolver(snapshot, noCircuit(), 1L);
        int firstCount = 0;
        int rounds = 2_000;

        for (int i = 0; i < rounds; i++) {
            if (resolver.candidates("m").get(0).id() == 1L) {
                firstCount++;
            }
        }

        assertThat(firstCount).isBetween((int) (rounds * 0.40), (int) (rounds * 0.60));
    }

    @Test
    void nonPositiveWeightIsTreatedAsOne() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 0, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 1, 0, "ACTIVE")),
                List.of(channel(1L, "zero-weight"), channel(2L, "one")));
        RouteResolver resolver = resolver(snapshot, noCircuit(), 42L);
        boolean sawZeroWeight = false;

        for (int i = 0; i < 500 && !sawZeroWeight; i++) {
            sawZeroWeight = resolver.candidates("m").get(0).id() == 1L;
        }

        assertThat(sawZeroWeight).as("权重 0 视为 1，仍有机会被选到（不是隐形禁用）").isTrue();
    }

    @Test
    void ignoresInactiveChannelsAndRoutes() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, 2L,
                List.of(channel(1L, "ok"),
                        new ChannelDescriptor(2L, "off", "https://off", "v1:QUJD", 1, 1000, "DISABLED", 1, 0)),
                List.of(new ModelRouteDescriptor("m", 1L, 1, 0, "ACTIVE"),
                        new ModelRouteDescriptor("m", 2L, 1, 0, "ACTIVE"),
                        new ModelRouteDescriptor("m", 1L, 1, 0, "DISABLED")),
                List.of(), null);

        assertThat(resolver(snapshot, noCircuit(), 7L).candidates("m"))
                .extracting(ChannelDescriptor::id).containsExactly(1L);
    }

    @Test
    void legacyChannelIsTheOnlyCandidateForTheLegacySentinelId() {
        UpstreamProperties properties =
                new UpstreamProperties("http://127.0.0.1:11434", "synthetic-upstream-key", "m");

        ChannelDescriptor legacy = LegacyChannel.of(properties);

        assertThat(legacy.id()).isEqualTo(LegacyChannel.ID);
        assertThat(LegacyChannel.isLegacy(legacy.id())).isTrue();
        assertThat(LegacyChannel.isLegacy(11L)).isFalse();
        assertThat(legacy.usable()).isTrue();
        assertThat(legacy.apiKeyCipher()).as("遗留渠道的密钥不走密文").isNull();
        assertThat(legacy.timeoutMs()).isPositive();
    }

    @Test
    void emptySnapshotYieldsNoCandidates() {
        assertThat(resolver(ConfigSnapshot.empty(), noCircuit(), 7L).candidates("m")).isEmpty();
    }

    @Test
    void candidateOrderIsDeterministicForAFixedSeed() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 3L, 100, 0, "ACTIVE")),
                List.of(channel(1L, "a"), channel(2L, "b"), channel(3L, "c")));

        List<Long> first = ids(resolver(snapshot, noCircuit(), 999L).candidates("m"));
        List<Long> second = ids(resolver(snapshot, noCircuit(), 999L).candidates("m"));

        assertThat(first).isEqualTo(second);
    }

    /**
     * 决策 10 要求全熔断分支「WARN + **计数器**」：API 只返回 {@code List<ChannelDescriptor>}，
     * 里面没有任何熔断标记，调用方**无法**自己认出这种情况，所以计数只能在这里做。
     */
    @Test
    void countsTheAllBrokenFallbackInTheRegistry() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 100, 1, "ACTIVE")),
                List.of(channel(1L, "broken"), channel(2L, "standby")));
        RouteResolver resolver = resolver(snapshot, brokenOnly(1L, 2L), 7L, registry);

        resolver.candidates("m");
        resolver.candidates("m");

        assertThat(registry.counter(RouteResolver.ALL_BROKEN_METRIC).count())
                .as("全熔断分支每次调用都计数（决策 10 的计数器）").isEqualTo(2.0);

        // 反例：健康路径不该碰到这个计数器，否则「全站熔断」这个告警信号会被日常流量淹没。
        MeterRegistry healthyRegistry = new SimpleMeterRegistry();
        resolver(snapshot, noCircuit(), 7L, healthyRegistry).candidates("m");

        assertThat(healthyRegistry.counter(RouteResolver.ALL_BROKEN_METRIC).count())
                .as("非全熔断路径不得计数").isZero();
    }

    /** 同一个 (model, channel) 出现两行 ACTIVE 路由时，渠道不能被放进候选两次（否则权重被双计）。 */
    @Test
    void deduplicatesRepeatedModelChannelRows() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 1000, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 1, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 1L, 1, 0, "ACTIVE")),
                List.of(channel(1L, "heavy"), channel(2L, "thin")));
        RouteResolver resolver = resolver(snapshot, noCircuit(), 20260923L);
        int headIsOne = 0;
        int rounds = 1_000;

        for (int i = 0; i < rounds; i++) {
            if (resolver.candidates("m").get(0).id() == 1L) {
                headIsOne++;
            }
        }

        assertThat(resolver.candidates("m")).as("重复的 (model, channel) 行不得把渠道放进候选两次")
                .hasSize(2);
        assertThat(headIsOne).as("保留第一次出现的 weight=1000，而不是最后一行的 weight=1")
                .isGreaterThan((int) (rounds * 0.90));
    }

    /** 冷启动时 supplier 可能还没有快照（返回 null）：必须退化为空快照，而不是让请求路径 NPE。 */
    @Test
    void nullSnapshotFallsBackToTheEmptySnapshot() {
        RouteResolver resolver = new RouteResolver(() -> null, noCircuit(),
                RandomGeneratorFactory.of("L64X128MixRandom").create(7L));

        assertThat(resolver.candidates("m")).isEmpty();
        assertThatThrownBy(() -> resolver.primary("m"))
                .isInstanceOf(RouteSelectionException.class)
                .hasMessageContaining("m");
    }

    private static List<Long> ids(List<ChannelDescriptor> channels) {
        List<Long> ids = new ArrayList<>();
        for (ChannelDescriptor channel : channels) {
            ids.add(channel.id());
        }
        return ids;
    }
}
