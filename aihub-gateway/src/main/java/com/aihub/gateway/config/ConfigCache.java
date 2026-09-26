package com.aihub.gateway.config;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ConfigSnapshotCodec;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

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
 * <p>所有 Redis 调用都吞异常：Redis 挂掉只是「二级缓存不可用」，一级与三级照常工作。
 */
public class ConfigCache {

    private static final Logger log = LoggerFactory.getLogger(ConfigCache.class);

    /** Redis 里快照的唯一键。 */
    public static final String REDIS_KEY = "aihub:config:snapshot";

    private final StringRedisTemplate redis;
    private final GatewayConfigProperties properties;
    private final Cache<String, ConfigSnapshot> local;

    private volatile boolean redisUsable = true;

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

    public void putLocal(ConfigSnapshot snapshot) {
        local.put(REDIS_KEY, snapshot);
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

    /** 二级写入；失败只记日志（缓存是可丢的派生数据）。 */
    public void writeRedis(ConfigSnapshot snapshot) {
        try {
            redis.opsForValue().set(REDIS_KEY, ConfigSnapshotCodec.encode(snapshot), properties.snapshotTtl());
            redisUsable = true;
        } catch (RuntimeException e) {
            log.warn("写入配置快照缓存失败，忽略: {}", e.toString());
            redisUsable = false;
        }
    }

    /** 最近一次 Redis 访问是否成功（指标与日志用）。 */
    public boolean redisAvailable() {
        return redisUsable;
    }
}
