package com.aihub.gateway.quota;

import com.aihub.common.config.QuotaDescriptor;
import com.aihub.common.quota.QuotaDecision;
import com.aihub.common.quota.QuotaKeys;
import com.aihub.common.quota.QuotaScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Redis + Lua 的配额预扣 / 校正（正常路径）。
 *
 * <p><b>预扣是「读已用量 → 判定 → 写回」的读改写三步，必须原子</b>：脚本在 Redis 里单线程执行，
 * 并发下不可能插入别的请求，因此不会超发（见 {@link QuotaScript} 的注释）。
 *
 * <p><b>返回 {@code null} 表示「Redis 这一级不可用」</b>（连不上 / 超时）：由调用方按 D7 **放行** +
 * 计数。**绝不把故障表达成「拒绝」**：那等于把记账组件故障变成对客户端的 429。
 *
 * <p><b>形状异常（{@code IllegalStateException}）刻意**不**被兜成 {@code null}</b>：脚本返回
 * 非预期形状是**缺陷**，必须让调用方计到独立的 {@code aihub.quota.script_error} 上。因此
 * {@link #reserve} 只把 **Redis I/O 的 {@code RuntimeException}** 折成 {@code null}，
 * {@link QuotaScript#parse} 抛出的 {@code IllegalStateException} 原样逃逸。
 *
 * <p><b>测试纪律</b>（CONVENTIONS §8 item 3：网关的测试永远不允许依赖 Docker）：本类的**行为**
 * （脚本真的在 Redis 里原子地补/退）由 **admin 侧真 Redis** 集成测试负责；网关侧只以
 * Mockito mock {@link StringRedisTemplate} 钉**调用约定**（脚本、键、ARGV、增量符号）——
 * 与 {@code ratelimit/RedisRateLimiterTest} 同一套纪律。
 */
public class RedisQuotaLimiter implements QuotaLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisQuotaLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(QuotaScript.SCRIPT, List.class);

    private final StringRedisTemplate redis;

    public RedisQuotaLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    @SuppressWarnings("unchecked")
    public QuotaDecision reserve(QuotaDescriptor limits, long estimatedTokens) {
        List<?> raw;
        try {
            // QuotaKeys.ttlMillis 用**调用方的时钟**（M3 决策 9：不读 Redis 的 TIME）。
            raw = redis.execute(SCRIPT, QuotaScript.keys(limits.tenantId(), limits.period()),
                    QuotaScript.args(estimatedTokens, limits.tokenLimit(), limits.requestLimit(),
                            QuotaKeys.ttlMillis(limits.period(), System.currentTimeMillis())).toArray());
        } catch (RuntimeException e) {
            log.warn("Redis 配额预扣失败，本次放行（fail-open，交由每日对账兜底）: {}", e.toString());
            return null;
        }
        // **在 try 之外**解析：形状异常是缺陷（IllegalStateException），必须逃逸给调用方计 script_error。
        return QuotaScript.parse(raw);
    }

    @Override
    public void adjust(long tenantId, String period, long estimatedTokens, long actualTokens) {
        // 差额：正 = 真实比估算多（补扣），负 = 真实比估算少（退回）。
        // HINCRBY 单字段原子；这是「预扣 + 校正」两段式收敛到真实用量的那一步。
        long delta = actualTokens - estimatedTokens;
        redis.opsForHash().increment(QuotaKeys.bucketKey(tenantId, period), QuotaKeys.FIELD_TOKENS, delta);
    }
}
