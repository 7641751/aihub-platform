package com.aihub.gateway.config;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ConfigSnapshotCodec;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 两级缓存的存储层（§6.3）：Caffeine（本机，30s TTL）+ Redis（跨实例共享）。**不含回源逻辑**
 * —— 回源、singleflight 与版本比对都在 {@link ConfigClient} 里，这样本类只关心「怎么存取」。
 *
 * <p>本地只有**一个** key（整个快照是一份文档），因此 Caffeine 的 {@code maximumSize} 只是形式上的
 * 兜底；真正的失效手段是 TTL 与 {@link #invalidateLocal()}。
 *
 * <p>Redis 侧用 {@link ConfigSnapshotCodec} 的分隔符载荷（决策 4），而不是 JSON：
 * 「缓存载荷」与「本地载荷」共用一份编解码只需维护一个转义器。**这不是跨服务契约**
 * （admin 不读它，admin 只发 JSON）。
 *
 * <p><b>两级写入都拒绝「版本倒退」</b>（2026 二次复审修复 3）：写进来的快照 version 必须
 * **严格大于**当前缓存里的那一份才会落地。回源是并发的（单飞只保证「同一时刻一次」，不保证
 * 「先发先回」），一个晚到的旧响应本来可以把新快照覆盖掉 —— 本地与 Redis 两侧都有这个方向。
 * 这条守卫只影响「覆盖已有条目」，不影响「首次写入」，因此不改变正常回填语义。
 *
 * <p>所有 Redis 调用都吞异常：Redis 挂掉只是「二级缓存不可用」，一级与三级照常工作。
 */
public class ConfigCache {

    private static final Logger log = LoggerFactory.getLogger(ConfigCache.class);

    /** Redis 里快照的唯一键。 */
    public static final String REDIS_KEY = "aihub:config:snapshot";

    /** 「还没有任何已写入版本」的哨兵：任何真实 version（含 0）都比它大，所以首次写入永远放行。 */
    private static final long NO_VERSION = Long.MIN_VALUE;

    private final StringRedisTemplate redis;
    private final GatewayConfigProperties properties;
    private final Cache<String, ConfigSnapshot> local;

    private volatile boolean redisUsable = true;
    /**
     * 本实例最后一次**写入** Redis 的 version。用它做「版本倒退」守卫，是为了避免每次回填都要
     * 先读一次 Redis 去比版本（那样每次成功回源会多一次 RTT）。它的初值是「没有」。
     */
    private final AtomicLong writtenRedisVersion = new AtomicLong(NO_VERSION);

    public ConfigCache(StringRedisTemplate redis, GatewayConfigProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.local = Caffeine.newBuilder()
                .maximumSize(Math.max(1, properties.maxLocalSnapshotSources()))
                .expireAfterWrite(properties.localTtl())
                .build();
    }

    public Optional<ConfigSnapshot> local() {
        return Optional.ofNullable(local.getIfPresent(REDIS_KEY));
    }

    /**
     * 一级写入，**带版本守卫**：不比手上那份新就不覆盖。
     *
     * <p>{@code asMap().merge} 在 Caffeine 的 {@code ConcurrentMap} 视图上是**原子**的
     * （{@code compute} 语义），所以并发的两次回填不会交错出「旧盖新」的结果。
     */
    public void putLocal(ConfigSnapshot snapshot) {
        local.asMap().merge(REDIS_KEY, snapshot, ConfigCache::newer);
    }

    public void invalidateLocal() {
        local.invalidateAll();
    }

    /** 二级读取；Redis 不可用或载荷畸形都返回空（调用方回源）。 */
    public Optional<ConfigSnapshot> readRedis() {
        try {
            ConfigSnapshot decoded = ConfigSnapshotCodec.decode(redis.opsForValue().get(REDIS_KEY));
            redisUsable = true;
            return Optional.ofNullable(decoded);
        } catch (RuntimeException e) {
            if (redisUsable) {
                log.warn("读取配置快照缓存失败（降级为直接回源 admin）: {}", e.toString());
            }
            redisUsable = false;
            return Optional.empty();
        }
    }

    /**
     * 二级写入；失败只记日志（缓存是可丢的派生数据）。**带版本守卫**：version 必须比本实例
     * 上一次写入的那一份**严格更新**才写。守卫用的版本是内存里的 {@link #writtenRedisVersion}，
     * 因此不增加任何 Redis 往返；代价是「另一个实例写了一条更新的条目、本实例却拿一条更旧的回源
     * 结果去覆盖」这种**跨进程**倒退不被本守卫拦住（那需要 Lua/CAS，M3 不具备条件）。
     * 本进程内由 {@code ConfigClient} 的 singleflight 收敛，而它才是评审指出的那条路径。
     */
    public void writeRedis(ConfigSnapshot snapshot) {
        if (snapshot.version() <= writtenRedisVersion.get()) {
            log.debug("配置快照缓存拒绝版本倒退的写入（已有 {}，本次 {}）", writtenRedisVersion.get(),
                    snapshot.version());
            return;
        }
        try {
            redis.opsForValue().set(REDIS_KEY, ConfigSnapshotCodec.encode(snapshot), properties.snapshotTtl());
            writtenRedisVersion.accumulateAndGet(snapshot.version(), Math::max);
            redisUsable = true;
        } catch (RuntimeException e) {
            // 写失败**不**推进版本水位：否则一次失败的写入会把后面所有重试都挡在门外。
            log.warn("写入配置快照缓存失败，忽略: {}", e.toString());
            redisUsable = false;
        }
    }

    /** 最近一次 Redis 访问是否成功（指标与日志用）。 */
    public boolean redisAvailable() {
        return redisUsable;
    }

    /** 「谁更新」的合并函数：平局时保留**已有**那份（同 version 的两份快照里，先到的那份已经服务出去了）。 */
    private static ConfigSnapshot newer(ConfigSnapshot existing, ConfigSnapshot candidate) {
        return candidate.version() > existing.version() ? candidate : existing;
    }
}
