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
 * <p><b>标记总是镜像到本机表，Redis 写成功也要镜像</b>：否则「Redis 接受了这次标记、随后在这
 * 30 秒内变得不可读」就会出现最糟的组合 —— 本机表里什么都没有，判定静默变回「未熔断」，
 * 流量又打回正在返回 429 的上游，正是降级表要防的那件事。
 *
 * <p><b>优先级：Redis 只要给出答案就以它为准</b>（跨实例权威），本机表**仅在 Redis 无答案时**
 * （抛异常，或返回 {@code null}）被查阅。所以 Redis 明确回答「无此标记」时会覆盖本机表里的旧标记；
 * 反之 Redis 变哑时本机表兜底。
 *
 * <p><b>本机表为什么是有界的</b>：它的键只有渠道 id，且只在打熔断标记时写入（每次 {@link #markOpen}
 * 都镜像一笔，Redis 成功与否都写），因此条目数上界是**配置里存在的渠道条数**（由 admin 配置
 * 决定的一个小数字，与请求量无关）——不是「每个请求一个键」的无界增长。另外读判定与
 * {@link #localOpenCount()} 都会顺带剔除已过期条目，所以正常恢复后表会自然收缩，无需后台清理线程。
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

    /** 上游 429 时由调用方调用。标记同时写入 Redis（跨实例权威）与本机降级表（Redis 变哑时的兜底）。 */
    public void markOpen(long channelId) {
        // 无条件镜像：Redis 这次写成功，也不代表这 30 秒内它一直可读。写失败只 WARN，绝不抛出。
        localOpen.put(channelId, clockMillis.getAsLong());
        try {
            redis.opsForValue().set(key(channelId), OPEN_VALUE, OPEN_TTL);
        } catch (RuntimeException e) {
            log.warn("写入熔断标记失败（Redis 不可用），退化为本机熔断: {}", e.toString());
        }
        // 只描述本方法确知的事实：调用方把该渠道标成了打开。本方法拿不到状态码，
        // 因此不在这里断言「上游返回 429」——那是调用方（转发层）的上下文。
        log.warn("渠道 {} 已标记熔断 {} 秒", channelId, OPEN_TTL.toSeconds());
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
        // 先取键快照，再用双参数 remove(id, openedAt)：只在值仍是当时看到的那一笔时才删。
        // 双参数形式是刻意的：删除条件落在调用点上，不依赖容器 removeIf 的内部语义，
        // 也不必假设单参数形式在并发下会怎么做。
        for (Long channelId : localOpen.keySet().toArray(new Long[0])) {
            Long openedAt = localOpen.get(channelId);
            if (openedAt != null && expired(openedAt)) {
                localOpen.remove(channelId, openedAt);
            }
        }
        return localOpen.size();
    }

    /** 某个渠道的状态，含「判断来自哪一级」。 */
    public CircuitState state(long channelId) {
        Boolean exists = readRedisFlag(channelId);
        if (exists != null) {
            return exists ? CircuitState.open(CircuitState.SOURCE_REDIS) : CircuitState.closed();
        }
        // Redis 没给出答案：这次判断来自本机降级表。即便结论是「未熔断」，source 也必须报本机 ——
        // 报成 redis 会让指标分不清「Redis 说健康」与「Redis 是黑的」。
        return isLocallyOpen(channelId)
                ? CircuitState.open(CircuitState.SOURCE_LOCAL)
                : CircuitState.closed(CircuitState.SOURCE_LOCAL);
    }

    /**
     * @return {@code TRUE} / {@code FALSE}（Redis 给了明确答案），或 {@code null}（Redis 不可用，
     *         调用方必须改查本机表）
     */
    private Boolean readRedisFlag(long channelId) {
        try {
            // 直接返回：null（本方法契约里的「无答案」）必须降级到本机表，
            // 绝不能在这里被折算成「Redis 说未熔断」——那会跳过一次本机兜底。
            return redis.hasKey(key(channelId));
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
