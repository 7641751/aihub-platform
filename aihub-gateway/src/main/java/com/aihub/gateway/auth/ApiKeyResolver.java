package com.aihub.gateway.auth;

import com.aihub.common.apikey.ApiKeyCacheCodec;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.admin.AdminClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 三级解析：Caffeine（本地，最快）→ Redis（跨实例共享）→ admin（真相源）。
 * <p>任何一级不可用都必须降级到下一级：缓存是可丢的派生数据，绝不能因为缓存故障而拒绝请求。
 * <p>Redis 载荷用 {@link ApiKeyCacheCodec} 编解码，key 前缀用它的公开常量
 * {@link ApiKeyCacheCodec#CACHE_KEY_PREFIX} —— 与 admin 写入时用的是**同一个常量**，
 * 因此不存在「两边字面量写歪了、缓存永远 miss 却无人报错」的隐患。
 */
@Component
public class ApiKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyResolver.class);

    /** 负缓存：同一个不存在的 key 不必每次都打 admin。usable() 为 false，调用方据此 401。 */
    private static final ApiKeyView MISS = new ApiKeyView("", 0L, "", "MISSING", null);
    private static final String MISS_PAYLOAD = ApiKeyCacheCodec.encode(MISS);

    private final Cache<String, ApiKeyView> local;
    private final StringRedisTemplate redis;
    private final AdminClient adminClient;
    private final Duration keyCacheTtl;

    public ApiKeyResolver(AuthProperties properties, AdminClient adminClient, StringRedisTemplate redis) {
        this.local = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(properties.localCacheTtl())
                .build();
        this.redis = redis;
        this.adminClient = adminClient;
        this.keyCacheTtl = properties.keyCacheTtl();
    }

    /**
     * 解析结果保证非 null：不存在/不可用的 key 会得到 {@link #MISS}（{@code usable() == false}），
     * 而不是空 Mono —— 调用方只需判断一次，不必再处理「没有值」的分支。
     */
    public Mono<ApiKeyView> resolve(String keyHash) {
        ApiKeyView cached = local.getIfPresent(keyHash);
        if (cached != null) {
            return Mono.just(cached);
        }
        ApiKeyView fromRedis = readRedis(keyHash);
        if (fromRedis != null) {
            local.put(keyHash, fromRedis);
            return Mono.just(fromRedis);
        }
        return adminClient.resolve(keyHash).map(maybeView -> {
            ApiKeyView view = maybeView.orElse(MISS);
            local.put(keyHash, view);
            if (view != MISS) {
                writeRedis(keyHash, view);
            }
            return view;
        });
    }

    private ApiKeyView readRedis(String keyHash) {
        try {
            String payload = redis.opsForValue().get(ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash);
            if (payload == null) {
                return null;
            }
            return MISS_PAYLOAD.equals(payload) ? MISS : ApiKeyCacheCodec.decode(payload);
        } catch (RuntimeException e) {
            log.warn("Redis 读取失败，降级回源 admin: {}", e.toString());
            return null;
        }
    }

    private void writeRedis(String keyHash, ApiKeyView view) {
        try {
            redis.opsForValue().set(ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash,
                    ApiKeyCacheCodec.encode(view), keyCacheTtl);
        } catch (RuntimeException e) {
            log.warn("Redis 写入失败，忽略: {}", e.toString());
        }
    }
}
