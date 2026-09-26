package com.aihub.gateway.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 渠道熔断：上游回 429 时给该渠道打一个 **30 秒**的标记，让所有网关实例立刻停止往它上面打流量
 * （设计文档 §9：「上游 429 → 在 Redis 给该渠道打 30s 熔断标记，立即换渠道」）。
 *
 * <p><b>为什么是 Redis TTL 而不是本机计时器</b>：熔断必须跨实例共享（一个实例发现的 429
 * 对另外九个同样有效），而 Redis 的 key TTL 是唯一能同时做到「共享」与「自动恢复」的载体。
 *
 * <p><b>为什么只有 429 会熔断（决策 10）</b>：429 表示该渠道的速率/配额已满，继续打只会持续失败；
 * 而 5xx 可能只是一个坏请求触发的单次故障，把整条渠道熔断 30 秒过于激进（5xx 仍然会触发
 * **当次**切换，只是不打熔断标记）。
 *
 * <p><b>Redis 不可用时退化为本机表</b>：{@code ConcurrentHashMap<channelId, openedAtMillis>}，
 * 判定仍按 30 秒。这是单机近似 —— 与限流的降级同一套哲学（不阻断数据面）。
 *
 * <p><b>本机表为什么是有界的</b>：它的键只有渠道 id，且只在本机「Redis 写失败」时才写入，
 * 因此条目数上界是**配置里存在的渠道条数**（由 admin 配置决定的一个小数字，与请求量无关）——
 * 不是「每个请求一个键」的无界增长。另外读判定与 {@link #localOpenCount()} 都会顺带剔除已过期条目，
 * 所以正常恢复后表会自然收缩，无需后台清理线程。
 *
 * <p>本类的所有方法**永不抛异常**：它跑在请求路径上，且熔断器自身的故障绝不能变成客户端 500。
 * 未知渠道默认「未熔断」（fail-open）：默认打开会让一次误标记把所有请求挡在门外。
 */
public class ChannelCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ChannelCircuitBreaker.class);

    /** 熔断标记的 key 前缀：完整键是 {@code aihub:channel:circuit:{channelId}}（决策 10）。 */
    public static final String KEY_PREFIX = "aihub:channel:circuit:";

    /** spec 写死的 30 秒（§9）。改这个数字必须同时改这里与文档。 */
    public static final Duration OPEN_TTL = Duration.ofSeconds(30);

    public static final String OPEN_VALUE = "OPEN";

    private final StringRedisTemplate redis;
    private final LongSupplier clockMillis;
    private final Map<Long, Long> localOpen = new ConcurrentHashMap<>();

    public ChannelCircuitBreaker(StringRedisTemplate redis, LongSupplier clockMillis) {
        this.redis = redis;
        this.clockMillis = clockMillis;
    }

    /** 该渠道当前是否被熔断。Redis 说不可用就查本机表（两处都不抛异常）。 */
    public boolean isOpen(long channelId) {
        Boolean exists = readRedisFlag(channelId);
        if (exists != null) {
            return exists;
        }
        return isLocallyOpen(channelId);
    }

    /** 上游 429 时调用。Redis 写失败就在本机记一笔（多实例下仍然能挡住本实例的重复打击）。 */
    public void markOpen(long channelId) {
        boolean redisOk = false;
        try {
            redis.opsForValue().set(key(channelId), OPEN_VALUE, OPEN_TTL);
            redisOk = true;
        } catch (RuntimeException e) {
            log.warn("写入熔断标记失败（Redis 不可用），退化为本机熔断: {}", e.toString());
        }
        if (!redisOk) {
            localOpen.put(channelId, clockMillis.getAsLong());
        }
        log.warn("渠道 {} 熔断 {} 秒（上游返回 429）", channelId, OPEN_TTL.toSeconds());
    }

    /** 手动清除（测试与运维用；正常恢复靠 TTL）。 */
    public void clear(long channelId) {
        try {
            redis.delete(key(channelId));
        } catch (RuntimeException e) {
            log.debug("清除熔断标记失败（Redis 不可用），忽略: {}", e.toString());
        }
        localOpen.remove(channelId);
    }

    /** 本机表里当前仍打开的数量（指标与测试用）。 */
    public int localOpenCount() {
        localOpen.entrySet().removeIf(entry -> expired(entry.getValue()));
        return localOpen.size();
    }

    /** 某个渠道的状态，含「判断来自哪一级」。 */
    public CircuitState state(long channelId) {
        Boolean exists = readRedisFlag(channelId);
        if (exists != null) {
            return exists ? CircuitState.open(CircuitState.SOURCE_REDIS) : CircuitState.closed();
        }
        return isLocallyOpen(channelId) ? CircuitState.open(CircuitState.SOURCE_LOCAL) : CircuitState.closed();
    }

    /**
     * @return {@code TRUE} / {@code FALSE}（Redis 给了明确答案），或 {@code null}（Redis 不可用，
     *         调用方必须改查本机表）
     */
    private Boolean readRedisFlag(long channelId) {
        try {
            Boolean exists = redis.hasKey(key(channelId));
            return exists == null ? Boolean.FALSE : exists;
        } catch (RuntimeException e) {
            log.debug("读取熔断标记失败（Redis 不可用），改查本机熔断表: {}", e.toString());
            return null;
        }
    }

    private boolean isLocallyOpen(long channelId) {
        Long openedAt = localOpen.get(channelId);
        if (openedAt == null) {
            return false;
        }
        if (expired(openedAt)) {
            localOpen.remove(channelId, openedAt);
            return false;
        }
        return true;
    }

    private boolean expired(long openedAtMillis) {
        long elapsed = clockMillis.getAsLong() - openedAtMillis;
        // elapsed < 0（时钟回拨）时按「仍打开」处理：宁可多熔断一会儿，也不要因为时钟抖动
        // 把刚熔断的坏渠道立刻放回来。
        return elapsed >= OPEN_TTL.toMillis();
    }

    private static String key(long channelId) {
        return KEY_PREFIX + channelId;
    }
}
