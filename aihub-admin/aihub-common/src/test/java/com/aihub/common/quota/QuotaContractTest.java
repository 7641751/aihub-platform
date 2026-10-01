package com.aihub.common.quota;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配额预扣的**跨模块契约**（gateway 跑它、admin 的集成测试验它），因此把字面量与必须存在的调用点
 * 钉在这里 —— 与 {@code com.aihub.common.ratelimit.RateLimitScriptTest} 完全同一纪律。
 * 任何一侧「顺手改一下脚本/键布局/参数顺序」都会先在这里变红。
 *
 * <p><b>五条用例各自对应一个可证伪的最小变异</b>（CONVENTIONS §8「不可证伪的断言不算断言」）：
 * <ol>
 *   <li>{@link #periodIsYyyymmInUtc()}：周期按 **UTC** 折算成 {@code YYYYMM}。把折算换成 JVM 默认时区
 *       （本机 Asia/Shanghai）即红 —— 月末最后一秒会落到下个月。</li>
 *   <li>{@link #ttlReachesTheDayAfterTheNextPeriodStarts()}：TTL 覆盖到「**下个周期开始 + 1 天**」。
 *       把 {@code +1 天} 去掉（只到周期开始）即红（D13：让跨月边界的补扣也被罩住）。</li>
 *   <li>{@link #theLuaScriptUsesThePinnedKeyLayoutAndArgOrder()}：键布局、ARGV 顺序、以及脚本正文里
 *       必须出现的三个 Redis 调用（{@code HMGET}/{@code HSET}/{@code PEXPIRE}）被固定向量钉住。</li>
 *   <li>{@link #assertWithinRangeAllowsTwoPow53AndRejectsAnythingAbove()}（F4）：ARGV 走 Lua 的 double，
 *       超过 {@code 2^53} 会丢整数精度，因此上界**只在这里定义一次**；把 {@code >} 改成 {@code >=}
 *       即红（{@code 2^53} 恰好被误拒）。</li>
 *   <li>{@link #theLuaScriptEnforcesTheRequestDimensionWhenItsLimitIsPositive()}（I-1）：请求维的
 *       **强制判定**必须逐字在脚本里。把 {@code (requestUsed + 1) > requestLimit} 改成永不成立即红 ——
 *       这是「请求维强制分支零覆盖」的**不依赖 Docker** 的那一层哨兵（行为层证据在集成类里）。</li>
 * </ol>
 *
 * <p>本类**不碰 Redis、不碰数据库**：它只是纯函数契约，因此不需要容器上下文。
 */
class QuotaContractTest {

    @Test
    void periodIsYyyymmInUtc() {
        assertThat(QuotaPeriod.of(Instant.parse("2026-09-30T23:59:59Z").toEpochMilli())).isEqualTo("202609");
        assertThat(QuotaPeriod.of(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli())).isEqualTo("202610");
    }

    @Test
    void ttlReachesTheDayAfterTheNextPeriodStarts() {
        long now = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli();
        long ttl = QuotaKeys.ttlMillis("202609", now);
        // 2026-10-01T00:00:00Z 是「下个周期开始」，+1 天 = 2026-10-02T00:00:00Z（D13）。
        assertThat(now + ttl).isEqualTo(Instant.parse("2026-10-02T00:00:00Z").toEpochMilli());
    }

    @Test
    void theLuaScriptUsesThePinnedKeyLayoutAndArgOrder() {
        assertThat(QuotaKeys.bucketKey(7L, "202609")).isEqualTo("aihub:quota:7:202609");
        assertThat(QuotaScript.keys(7L, "202609")).containsExactly("aihub:quota:7:202609");
        assertThat(QuotaScript.args(1_000L, 100_000L, 0L, 60_000L)).containsExactly("1000", "100000", "0", "60000");

        // HSET（不是 HINCRBY）：Lua 先 HMGET 读、脚本内自算、再 HSET 写回。
        assertThat(QuotaScript.SCRIPT).contains("HMGET").contains("HSET").contains("PEXPIRE");
    }

    /**
     * 请求维的**强制判定**必须逐字在脚本里（覆盖缺口 I-1）：{@code requestLimit > 0} 时，
     * {@code (requestUsed + 1) > requestLimit} 才拒绝 —— 且被拒的那次**不写回**已用量。
     *
     * <p>判别力：把该条件改成永不成立（如 {@code > requestLimit + 1000000}，评审 V-7 的形状）即红。
     * 这是**不依赖 Docker** 的一层哨兵：脚本一漂移就立刻失败，不必等到真 Redis 的集成用例。
     * 行为层证据见 {@code com.aihub.admin.quota.QuotaPreDeductionIntegrationTest#
     * aPositiveRequestLimitIsEnforcedOnTheRequestDimension}。
     */
    @Test
    void theLuaScriptEnforcesTheRequestDimensionWhenItsLimitIsPositive() {
        assertThat(QuotaScript.SCRIPT)
                .as("请求维的强制判定必须逐字存在：requestLimit > 0 且 (requestUsed + 1) > requestLimit")
                .contains("if requestLimit > 0 and (requestUsed + 1) > requestLimit then")
                .contains("requestUsed = requestUsed + 1");
    }

    @Test
    void assertWithinRangeAllowsTwoPow53AndRejectsAnythingAbove() {
        long twoPow53 = 9_007_199_254_740_992L;
        assertThatCode(() -> QuotaScript.assertWithinRange(twoPow53))
                .as("等于 2^53 必须被允许：边界只在这里定义一次，判据是「超过」（>）")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> QuotaScript.assertWithinRange(twoPow53 + 1L))
                .as("超过 2^53 会被 Lua 的 double 丢精度 ⇒ 必须拒绝")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
