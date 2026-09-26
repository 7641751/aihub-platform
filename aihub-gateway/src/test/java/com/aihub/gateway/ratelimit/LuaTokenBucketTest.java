package com.aihub.gateway.ratelimit;

import com.aihub.common.ratelimit.RateLimitScript;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关侧看到的脚本与键布局必须与共享模块里的那一份**逐字节一致**。这里的断言主要是
 * 「委托没被写歪」：任何一次「顺手在 gateway 里改一下脚本」都会先在这里变红。
 *
 * <p>脚本的**行为**证据在 admin 侧的真 Redis 集成测试里（{@code RedisTokenBucketIntegrationTest}）：
 * 网关测试不允许依赖 Docker，所以本类只钉结构与约定，不假装跑过 Redis。
 */
class LuaTokenBucketTest {

    @Test
    void scriptIsASingleAtomicServerSideProgram() {
        assertThat(LuaTokenBucket.SCRIPT).isSameAs(RateLimitScript.SCRIPT);
        assertThat(LuaTokenBucket.SCRIPT).contains("redis.call('HGET'").contains("redis.call('HSET'");
        assertThat(LuaTokenBucket.SCRIPT).contains("return {");
        assertThat(LuaTokenBucket.SCRIPT).doesNotContain("pcall");
    }

    /**
     * 时间**必须**来自 ARGV，不许用 Redis 的 {@code TIME}：否则一次拒绝的判定时刻与网关日志里的
     * 时刻来自两个时钟，排查限流问题时无法对齐（决策 9）。
     */
    @Test
    void scriptUsesOnlyThePassedClockAndNeverRedisTime() {
        assertThat(LuaTokenBucket.SCRIPT).contains("ARGV[1]");
        assertThat(LuaTokenBucket.SCRIPT).doesNotContain("'TIME'").doesNotContain("\"TIME\"");
    }

    @Test
    void scriptReadsKeysAndArgsAtThePinnedPositions() {
        assertThat(LuaTokenBucket.SCRIPT).contains("KEYS[1]");
        assertThat(LuaTokenBucket.SCRIPT).contains("tonumber(ARGV[1])");
        assertThat(LuaTokenBucket.SCRIPT).contains("tonumber(ARGV[2])");
        assertThat(LuaTokenBucket.SCRIPT).contains("tonumber(ARGV[3])");
        assertThat(LuaTokenBucket.SCRIPT).contains("ARGV[4]");
        assertThat(LuaTokenBucket.FIELD_TOKENS).isEqualTo("t");
        assertThat(LuaTokenBucket.FIELD_LAST_REFILL).isEqualTo("k");
    }

    @Test
    void scriptReturnsAllowedRemainingAndRetryAfter() {
        // 三个返回值，顺序固定：allowed / remaining / retryAfterMillis。
        assertThat(LuaTokenBucket.SCRIPT).contains("return {allowed, remaining, retryAfter}");
    }

    @Test
    void keyPrefixIsThePinnedLayout() {
        assertThat(LuaTokenBucket.KEY_PREFIX).isEqualTo("aihub:ratelimit:");
        assertThat(LocalRateLimiter.KEY_PREFIX).as("本机桶必须与 Redis 布局分开，避免混淆两种存储")
                .isEqualTo("local:ratelimit:");
    }

    /** 空闲 TTL 要有下界与上界：太小会让桶频繁重建（限流被打穿），太大则键永不回收。 */
    @Test
    void idleTtlIsBoundedBelowAndAbove() {
        assertThat(LuaTokenBucket.idleTtlMillis(10, 20)).isEqualTo(60_000L);
        assertThat(LuaTokenBucket.idleTtlMillis(1, 1)).isEqualTo(60_000L);
        assertThat(LuaTokenBucket.idleTtlMillis(1, 100)).isEqualTo(2_000_000L);
        assertThat(LuaTokenBucket.idleTtlMillis(0, 0)).isEqualTo(60_000L);
    }
}
