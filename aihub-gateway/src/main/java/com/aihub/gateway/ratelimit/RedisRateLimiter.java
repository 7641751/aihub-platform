package com.aihub.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Redis + Lua 令牌桶（正常路径）。
 *
 * <p><b>返回 {@code null} 表示「Redis 这一级不可用」</b>（连不上 / 超时 / 脚本返回值不合法 /
 * key 为空），由 {@link RateLimiter} 决定降级到 {@link LocalRateLimiter}。**绝不把故障表达成
 * 「拒绝」**：那等于把控制面故障变成对客户端的限流拒绝。
 *
 * <p><b>阻塞 I/O</b>：{@code StringRedisTemplate} 是 Lettuce 的**同步** API。本类只提供同步方法，
 * 调用方（过滤器路径）必须明白它的代价：Redis 不可达时每次调用会被按住
 * {@code spring.data.redis.timeout}（2 秒）。缓解手段是 {@link RateLimiter} 的**粘性降级**
 * （一旦发现不可用，接下来 1 秒内直接走本机桶，不再每次撞超时）。
 */
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(LuaTokenBucket.SCRIPT, List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 见类注释：返回 {@code null} = 「Redis 这一级不可用」。 */
    @SuppressWarnings("unchecked")
    public RateLimitDecision tryConsume(String bucketKey, int qps, int burst) {
        if (bucketKey == null || bucketKey.isBlank()) {
            return null;
        }
        try {
            List<Long> result = redis.execute(SCRIPT, List.of(bucketKey),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(qps),
                    String.valueOf(burst),
                    String.valueOf(LuaTokenBucket.idleTtlMillis(qps, burst)));
            if (result == null || result.size() != 3) {
                log.warn("限流脚本返回了意外结果（{}），本次按「Redis 不可用」降级", result);
                return null;
            }
            long allowed = result.get(0);
            int remaining = (int) Math.max(0L, result.get(1));
            long retryAfter = Math.max(0L, result.get(2));
            int capacity = Math.max(burst, 1);
            return allowed == 1L
                    ? RateLimitDecision.allowed(remaining, qps, capacity, RateLimitDecision.Source.REDIS)
                    : RateLimitDecision.denied(retryAfter, qps, capacity, RateLimitDecision.Source.REDIS);
        } catch (RuntimeException e) {
            log.warn("Redis 限流失败，降级为本地令牌桶: {}", e.toString());
            return null;
        }
    }
}
