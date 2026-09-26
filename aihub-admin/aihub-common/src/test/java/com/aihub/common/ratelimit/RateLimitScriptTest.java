package com.aihub.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脚本与键布局是**跨模块契约**（gateway 跑它、admin 的集成测试验它），因此把字面量与
 * 必须存在的调用点钉在这里。任何一侧「顺手改一下脚本」都会先在这里变红。
 */
class RateLimitScriptTest {

    @Test
    void scriptAndKeyLayoutAreThePinnedContract() {
        assertThat(RateLimitScript.KEY_PREFIX).isEqualTo("aihub:ratelimit:");
        assertThat(RateLimitScript.FIELD_TOKENS).isEqualTo("t");
        assertThat(RateLimitScript.FIELD_LAST_REFILL).isEqualTo("k");

        // 一次往返、服务器端原子：读改写三步都在同一个脚本里，参数位置固定。
        for (String required : List.of("HGET", "HSET", "PEXPIRE", "KEYS[1]", "ARGV[1]", "ARGV[2]", "ARGV[3]",
                "ARGV[4]", "return {")) {
            assertThat(RateLimitScript.SCRIPT).as("脚本必须包含 %s", required).contains(required);
        }
        assertThat(RateLimitScript.SCRIPT).as("时间必须来自调用方，不能用 Redis 的 TIME")
                .doesNotContain("'TIME'").doesNotContain("\"TIME\"");
        // 不允许出现「客户端分步调用」的痕迹：脚本里不该有 pcall 包住的多次读改写。
        assertThat(RateLimitScript.SCRIPT).doesNotContain("pcall");

        // TTL 公式的数字写死在这里：任何改动（含把下限从 60 秒挪走）都必须先过这一关。
        assertThat(RateLimitScript.idleTtlMillis(10, 20)).isEqualTo(60_000L);      // 2000*20=40_000 → 下限
        assertThat(RateLimitScript.idleTtlMillis(1, 1)).isEqualTo(60_000L);        // 1000*20=20_000 → 下限
        assertThat(RateLimitScript.idleTtlMillis(1, 100)).isEqualTo(2_000_000L);   // 100_000*20
        assertThat(RateLimitScript.idleTtlMillis(0, 0)).isEqualTo(60_000L);        // 除零保护后 → 下限
    }
}
