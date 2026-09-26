package com.aihub.gateway.ratelimit;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.RatePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策略解析是「限流到底按什么数字执行」的唯一来源。**两个维度都生效**（决策 7，2026-09-26 依控制器
 * pre-flight 评审修订）：先按 {@code (tenantId, apiKeyId)} 找 key 级策略，没有有效的才回落到该租户的
 * 租户级策略，最后才是内置默认。原文那条「key 级被忽略」的用例是**错的**（数值主键由决策 14 随
 * {@code ApiKeyView} 下发、限流过滤器排在鉴权之后即可拿到），已被下面两条替换。
 */
class RateLimitResolverTest {

    private static final ConfigSnapshot SNAPSHOT = new ConfigSnapshot(1L, 2L, List.of(), List.of(), List.of(
            new RatePolicy(7L, null, 20, 40),
            new RatePolicy(7L, 42L, 100, 200),
            new RatePolicy(8L, null, 5, 10),
            new RatePolicy(null, null, 999, 999)), null);

    private static RateLimitResolver resolverOf(ConfigSnapshot snapshot) {
        return new RateLimitResolver(() -> snapshot);
    }

    @Test
    void usesTheBuiltInDefaultWhenNoPolicyExists() {
        RatePolicy policy = resolverOf(ConfigSnapshot.empty()).resolve(7L, null);

        assertThat(policy.qps()).isEqualTo(RatePolicy.DEFAULT_QPS);
        assertThat(policy.burst()).isEqualTo(RatePolicy.DEFAULT_BURST);
        assertThat(policy.tenantId()).isEqualTo(7L);
        assertThat(policy.tenantLevel()).isTrue();
    }

    /** 决策 7 的第一级：`apiKeyId` 命中时，key 级策略**赢过**同一租户的租户级策略。 */
    @Test
    void prefersTheKeyLevelPolicyWhenTheApiKeyIdsMatch() {
        RatePolicy policy = resolverOf(SNAPSHOT).resolve(7L, 42L);

        assertThat(policy.qps()).as("key 级的 100/200 必须生效").isEqualTo(100);
        assertThat(policy.burst()).isEqualTo(200);
        assertThat(policy.apiKeyId()).isEqualTo(42L);
        assertThat(policy.tenantLevel()).isFalse();
    }

    /**
     * 决策 7 的第二级（**回落**）：这个 (租户, key) 没有 key 级行时用该租户的租户级行。
     * 两种形态都要覆盖：① 该 key 根本没有策略（99L）；② 鉴权关闭、请求上下文里没有数值主键
     * （{@code null}）—— 后者是匿名桶的正常路径，绝不能因为 `apiKeyId` 为空就丢掉租户级策略。
     */
    @Test
    void usesTheTenantLevelPolicyWhenThereIsNoKeyLevelRow() {
        RatePolicy otherKey = resolverOf(SNAPSHOT).resolve(7L, 99L);
        assertThat(otherKey.qps()).isEqualTo(20);
        assertThat(otherKey.burst()).isEqualTo(40);
        assertThat(otherKey.tenantLevel()).isTrue();

        RatePolicy anonymous = resolverOf(SNAPSHOT).resolve(7L, null);
        assertThat(anonymous.qps()).as("apiKeyId 为 null 时只能走租户级").isEqualTo(20);
        assertThat(anonymous.apiKeyId()).isNull();
    }

    @Test
    void ignoresPoliciesOfOtherTenants() {
        assertThat(resolverOf(SNAPSHOT).resolve(8L, null).qps()).isEqualTo(5);
        assertThat(resolverOf(SNAPSHOT).resolve(8L, 42L).qps())
                .as("租户 8 没有 42 号 key 的 key 级策略 → 回落租户级").isEqualTo(5);
        assertThat(resolverOf(SNAPSHOT).resolve(9L, null).qps()).isEqualTo(RatePolicy.DEFAULT_QPS);
    }

    /**
     * 同维度出现多行时（V1 没有唯一约束，决策 17）：**在各自的维度内取最后一条**。
     * admin 侧组装快照时已经按 {@code id} 升序排好，因此「最后一条」就是「最后插入的那条」。
     */
    @Test
    void usesTheLastPolicyOfTheMatchingDimensionWhenSeveralArePresent() {
        ConfigSnapshot duplicated = new ConfigSnapshot(1L, 2L, List.of(), List.of(), List.of(
                new RatePolicy(7L, null, 20, 40),
                new RatePolicy(7L, null, 60, 80),
                new RatePolicy(7L, 42L, 100, 200),
                new RatePolicy(7L, 42L, 300, 400)), null);

        assertThat(resolverOf(duplicated).resolve(7L, null).qps())
                .as("租户级多条取最后一条").isEqualTo(60);
        assertThat(resolverOf(duplicated).resolve(7L, 42L).qps())
                .as("key 级同键多条同样取最后一条").isEqualTo(300);
    }

    @Test
    void treatsNonPositiveQpsAsTheDefault() {
        // qps=0 会让桶永不补充（只允许 burst 次），这几乎必然是配置事故而不是意图：
        // 把 0/负数当成「没有配置」处理，回落到内置默认值。**两级都按这条规则处理**（保留原有的
        // 按行校验语义：非正值的行不生效，且不因此降级到另一维的行）。
        ConfigSnapshot zero = new ConfigSnapshot(1L, 2L, List.of(), List.of(),
                List.of(new RatePolicy(7L, null, 0, 0)), null);
        ConfigSnapshot zeroKeyLevel = new ConfigSnapshot(1L, 2L, List.of(), List.of(),
                List.of(new RatePolicy(7L, null, 20, 40), new RatePolicy(7L, 42L, 0, 0)), null);

        RatePolicy policy = resolverOf(zero).resolve(7L, null);

        assertThat(policy.qps()).isEqualTo(RatePolicy.DEFAULT_QPS);
        assertThat(policy.burst()).isEqualTo(RatePolicy.DEFAULT_BURST);

        RatePolicy invalidKeyLevel = resolverOf(zeroKeyLevel).resolve(7L, 42L);

        assertThat(invalidKeyLevel.qps()).as("非正的 key 级行同样回落到内置默认")
                .isEqualTo(RatePolicy.DEFAULT_QPS);
        assertThat(invalidKeyLevel.burst()).isEqualTo(RatePolicy.DEFAULT_BURST);
    }

    @Test
    void readsTheSnapshotLazilyPerCall() {
        AtomicReference<ConfigSnapshot> current = new AtomicReference<>(ConfigSnapshot.empty());
        RateLimitResolver resolver = new RateLimitResolver(current::get);

        assertThat(resolver.resolve(7L, 42L).qps()).isEqualTo(RatePolicy.DEFAULT_QPS);

        current.set(SNAPSHOT);

        assertThat(resolver.resolve(7L, 42L).qps()).as("策略必须随快照刷新而生效").isEqualTo(100);
    }
}
