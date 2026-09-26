package com.aihub.gateway.ratelimit;

import com.aihub.common.ratelimit.RateLimitScript;

/**
 * 网关侧的令牌桶脚本入口。**本类不含任何实现**：脚本与键布局的唯一真相在
 * {@link RateLimitScript}（`aihub-common`），理由是 admin 侧的集成测试也要验这段脚本，
 * 而 gateway 的测试不允许依赖 Docker。委托保持公开常量名不变，因此生产代码与测试都只认这一个名字。
 */
public final class LuaTokenBucket {

    /** 桶键前缀：完整键是 {@code aihub:ratelimit:{tenantId}:{sha256(secret)}}（决策 8）。 */
    public static final String KEY_PREFIX = RateLimitScript.KEY_PREFIX;

    public static final String FIELD_TOKENS = RateLimitScript.FIELD_TOKENS;
    public static final String FIELD_LAST_REFILL = RateLimitScript.FIELD_LAST_REFILL;

    public static final String SCRIPT = RateLimitScript.SCRIPT;

    private LuaTokenBucket() {
    }

    /** 空闲 TTL：至少 1 分钟；正常取「把满桶放空所需时间」的 20 倍。 */
    public static long idleTtlMillis(int qps, int burst) {
        return RateLimitScript.idleTtlMillis(qps, burst);
    }
}
