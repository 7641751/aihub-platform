package com.aihub.common.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照的三件事压在这里：① 四个共享 record 的语义（`usable()` / 候选解析 / 策略过滤）；
 * ② 分隔符编解码的严格互逆；③ 固定向量 —— Redis 里的字节形态从此不再随手可改。
 *
 * <p><b>它不是跨服务契约</b>：admin 发的是 JSON（网关用 Jackson 解），这份编解码只服务网关自己的
 * 两级缓存（Caffeine 与 Redis 载荷）。因此不需要「两侧字面量钉死」那一套，只需要
 * 「编解码互逆 + 畸形不炸 + 字段顺序稳定」。
 */
class ConfigSnapshotCodecTest {

    private static final ChannelDescriptor PRIMARY = new ChannelDescriptor(
            11L, "deepseek-primary", "https://api.deepseek.com", "v1:QUJD", 1, 60000, "ACTIVE", 100, 0);
    private static final ChannelDescriptor BACKUP = new ChannelDescriptor(
            12L, "deepseek-backup", "https://backup.example.com", "v2:QUJD", 2, 30000, "ACTIVE", 300, 0);

    private static ConfigSnapshot populated() {
        return new ConfigSnapshot(
                1_800_000_000_123L,
                1_800_000_000_456L,
                List.of(PRIMARY, BACKUP),
                List.of(new ModelRouteDescriptor("deepseek-chat", 11L, 100, 0, "ACTIVE"),
                        new ModelRouteDescriptor("deepseek-chat", 12L, 300, 0, "ACTIVE")),
                List.of(new RatePolicy(7L, null, 20, 40), new RatePolicy(7L, 42L, 100, 200)),
                "deepseek-chat",
                // 同一租户的两个不同周期：编解码必须逐字保住 period，否则跨周期会取错额度（Task 13）。
                List.of(new QuotaDescriptor(7L, "202601", 100L, 7L),
                        new QuotaDescriptor(7L, "202602", 200L, 8L)));
    }

    @Test
    void encodingProducesThePinnedHeaderAndSectionOrder() {
        String payload = ConfigSnapshotCodec.encode(populated());
        List<String> lines = payload.lines().toList();

        assertThat(lines.get(0)).isEqualTo("#v1|1800000000123|deepseek-chat|1800000000456");
        assertThat(lines.get(1)).isEqualTo("C|11|deepseek-primary|https://api.deepseek.com|v1:QUJD|1|60000|ACTIVE|100|0");
        assertThat(lines.get(2)).isEqualTo("C|12|deepseek-backup|https://backup.example.com|v2:QUJD|2|30000|ACTIVE|300|0");
        assertThat(lines.get(3)).isEqualTo("R|deepseek-chat|11|100|0|ACTIVE");
        // 第二条 R 行同样钉住：少了它，「丢掉/重排第二个路由」不会有任何断言变红。
        assertThat(lines.get(4)).isEqualTo("R|deepseek-chat|12|300|0|ACTIVE");
        assertThat(lines.get(5)).isEqualTo("L|7||20|40");
        assertThat(lines.get(6)).isEqualTo("L|7|42|100|200");
        // Q 段（Task 13）：两条额度行都必须被编码，且 period 逐字保留。
        assertThat(lines.get(7)).isEqualTo("Q|7|202601|100|7");
        assertThat(lines.get(8)).isEqualTo("Q|7|202602|200|8");
    }

    @Test
    void roundTripsAFullyPopulatedSnapshot() {
        assertThat(ConfigSnapshotCodec.decode(ConfigSnapshotCodec.encode(populated()))).isEqualTo(populated());
    }

    /**
     * 额度往返**必须把 {@code quotas} 带回来**（变异体②的判别点）：只编码不解码会让 {@code quotas}
     * 变成空表 —— 而空表正是「所有租户都不限」（D15）的默认值，于是「额度悄悄丢了」在生产上
     * **不可观测**。这条断言要求解码后的额度与原文逐字段相等。
     */
    @Test
    void roundTripsTheQuotasSection() {
        ConfigSnapshot decoded = ConfigSnapshotCodec.decode(ConfigSnapshotCodec.encode(populated()));

        assertThat(decoded).isNotNull();
        assertThat(decoded.quotas()).containsExactlyInAnyOrder(
                new QuotaDescriptor(7L, "202601", 100L, 7L),
                new QuotaDescriptor(7L, "202602", 200L, 8L));
    }

    @Test
    void roundTripsAnEmptySnapshot() {
        ConfigSnapshot empty = ConfigSnapshot.empty();
        String payload = ConfigSnapshotCodec.encode(empty);

        assertThat(payload.lines().toList().get(0)).isEqualTo("#v1|0||0");
        assertThat(ConfigSnapshotCodec.decode(payload)).isEqualTo(empty);
    }

    @Test
    void roundTripsFieldsContainingDelimiterBackslashAndNewlines() {
        for (String name : List.of("a|b", "a\\b", "line\nbreak", "cr\rlf", "\\|", "||||")) {
            ConfigSnapshot snapshot = new ConfigSnapshot(1L, 2L,
                    List.of(new ChannelDescriptor(1L, name, "https://x", "v1:QUJD", 1, 1000, "ACTIVE", 1, 0)),
                    List.of(), List.of(), name);

            String payload = ConfigSnapshotCodec.encode(snapshot);

            assertThat(payload).as("渠道名 %s 不得把载荷撑成多行", name).doesNotContain("\r");
            assertThat(ConfigSnapshotCodec.decode(payload))
                    .as("渠道名 %s（载荷 %s）必须严格往返", name, payload)
                    .isEqualTo(snapshot);
        }
    }

    @Test
    void decodeRejectsMalformedPayloads() {
        assertThat(ConfigSnapshotCodec.decode(null)).isNull();
        assertThat(ConfigSnapshotCodec.decode("")).isNull();
        assertThat(ConfigSnapshotCodec.decode("C|11|x|https://x|v1:QUJD|1|60000|ACTIVE|100|0")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v9|0||0")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v1|not-a-long||0")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v1|0||0\nC|11|x|https://x|v1:QUJD|1|60000|ACTIVE|100")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v1|0||0\nC|11|x|https://x|v1:QUJD|1|bad|ACTIVE|100|0")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v1|0||0\nR|m|11|100")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v1|0||0\nL|7||20")).isNull();
        // Q 段字段数不对同样判死（与 C/R/L 同一口径）。
        assertThat(ConfigSnapshotCodec.decode("#v1|0||0\nQ|7|202601|100")).isNull();
    }

    /**
     * 额度查找**只认「租户 + 周期」**：跨周期取错会让错误的预算生效。变异体④（ConfigCache 只按
     * tenantId 找）在网关侧的对应断言落在 {@code ConfigCacheTest}，这里钉住共享 record 本身的查找语义。
     */
    @Test
    void quotaIsFoundByTenantAndPeriodOnly() {
        ConfigSnapshot snapshot = populated();

        assertThat(snapshot.quota(7L, "202601")).contains(new QuotaDescriptor(7L, "202601", 100L, 7L));
        assertThat(snapshot.quota(7L, "202602")).as("同租户的不同周期必须各自取到自己的额度")
                .contains(new QuotaDescriptor(7L, "202602", 200L, 8L));
        assertThat(snapshot.quota(7L, "202603")).as("没有该周期的行 = 不限（D15）").isEmpty();
        assertThat(snapshot.quota(8L, "202601")).as("别的租户取不到").isEmpty();
        assertThat(snapshot.quota(7L, null)).isEmpty();
    }

    @Test
    void decodeSkipsUnknownSectionLetters() {
        ConfigSnapshot decoded = ConfigSnapshotCodec.decode("#v1|5||9\nX|future|stuff\nL|7||20|40");

        assertThat(decoded).isNotNull();
        assertThat(decoded.version()).isEqualTo(5L);
        assertThat(decoded.ratePolicies()).hasSize(1);
    }

    @Test
    void usableRequiresActiveStatusBaseUrlAndPositiveTimeout() {
        assertThat(PRIMARY.usable()).isTrue();
        assertThat(new ChannelDescriptor(1L, "x", "https://x", "v1:QUJD", 1, 1000, "DISABLED", 1, 0).usable()).isFalse();
        assertThat(new ChannelDescriptor(1L, "x", "", "v1:QUJD", 1, 1000, "ACTIVE", 1, 0).usable()).isFalse();
        assertThat(new ChannelDescriptor(1L, "x", "https://x", "v1:QUJD", 1, 0, "ACTIVE", 1, 0).usable()).isFalse();
    }

    @Test
    void channelsSupportingIgnoresInactiveRoutesAndChannels() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, 2L,
                List.of(PRIMARY, new ChannelDescriptor(12L, "off", "https://b", "v1:QUJD", 1, 1000, "DISABLED", 1, 0)),
                List.of(new ModelRouteDescriptor("m", 11L, 1, 0, "ACTIVE"),
                        new ModelRouteDescriptor("m", 12L, 1, 0, "ACTIVE"),
                        new ModelRouteDescriptor("m", 11L, 1, 0, "DISABLED"),
                        new ModelRouteDescriptor("m2", 11L, 1, 0, "DISABLED")),
                List.of(), null);

        assertThat(snapshot.channelsSupporting("m")).extracting(ChannelDescriptor::id).containsExactly(11L);
        assertThat(snapshot.channelsSupporting("m2")).isEmpty();
        assertThat(snapshot.channelsSupporting("unknown")).isEmpty();
        assertThat(snapshot.channelsSupporting(null)).isEmpty();
        assertThat(snapshot.channel(99L)).isEqualTo(Optional.empty());
        assertThat(snapshot.channel(11L)).contains(PRIMARY);
    }

    @Test
    void routesForReturnsOnlyActiveRoutesOfThatModel() {
        assertThat(populated().routesFor("deepseek-chat"))
                .extracting(ModelRouteDescriptor::channelId).containsExactly(11L, 12L);
        assertThat(populated().routesFor("other")).isEmpty();
        assertThat(populated().routesFor(null)).isEmpty();
    }

    @Test
    void tenantPoliciesFiltersToTenantLevelOfThatTenantAndKeyPoliciesToThatKey() {
        // 决策 7（已按控制器 pre-flight 评审修订）：两个维度都参与判定，因此两个入口都必须
        // 只返回自己那一维的行 —— key 级行不得混进 tenantPolicies（否则「租户级回落」会拿到 key 级策略），
        // 租户级行也不得混进 keyPolicies（否则「key 级优先」会命中不属于这个 key 的策略）。
        assertThat(populated().tenantPolicies(7L))
                .as("key 级策略（apiKeyId=42）不得出现在租户级结果里")
                .hasSize(1);
        assertThat(populated().tenantPolicies(7L).get(0).qps()).isEqualTo(20);
        assertThat(populated().tenantPolicies(8L)).isEmpty();

        assertThat(populated().keyPolicies(7L, 42L))
                .as("只应命中该租户该 key 的那一行")
                .hasSize(1);
        assertThat(populated().keyPolicies(7L, 42L).get(0).qps()).isEqualTo(100);
        assertThat(populated().keyPolicies(7L, 43L)).as("别的 key 不得命中").isEmpty();
        assertThat(populated().keyPolicies(8L, 42L)).as("别的租户不得命中").isEmpty();
    }

    @Test
    void modelNamesIsTheSortedDistinctUnionOfActiveRoutes() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, 2L, List.of(), List.of(
                new ModelRouteDescriptor("b", 1L, 1, 0, "ACTIVE"),
                new ModelRouteDescriptor("a", 1L, 1, 0, "ACTIVE"),
                new ModelRouteDescriptor("b", 2L, 1, 0, "ACTIVE"),
                new ModelRouteDescriptor("off", 1L, 1, 0, "DISABLED")), List.of(), null);

        assertThat(snapshot.modelNames()).containsExactly("a", "b");
    }

    @Test
    void emptySnapshotHasNoChannelsAndNoModels() {
        assertThat(ConfigSnapshot.empty().channels()).isEmpty();
        assertThat(ConfigSnapshot.empty().modelNames()).isEmpty();
        assertThat(ConfigSnapshot.empty().channelsSupporting("m")).isEmpty();
        assertThat(ConfigSnapshot.empty().version()).isZero();
    }
}
