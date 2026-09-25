package com.aihub.service.apikey;

import com.aihub.common.apikey.ApiKeyCacheCodec;
import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.dao.entity.ApiKeyEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.ApiKeyMapper;
import com.aihub.dao.mapper.TenantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * API Key 的铸造与解析。
 * <p>铸造：生成 key_id 与 secret，只把 {@code SHA-256(secret)} 入库，明文只在返回值里出现一次。
 * <p>解析：Redis 缓存优先，未命中回源 MySQL 并回填 —— 缓存是可丢的派生数据，MySQL 才是真相源。
 * <p>缓存载荷用 {@link ApiKeyCacheCodec} 而不是 JSON：gateway 要读同一份数据，
 * 而 {@code aihub-common} 必须保持零依赖。
 * <p>密钥视图与「是否可用」的规则都来自 {@link ApiKeyView}，两个服务共用同一个定义。
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyMapper apiKeyMapper;
    private final TenantMapper tenantMapper;
    private final StringRedisTemplate redis;
    private final Duration cacheTtl;

    public ApiKeyService(ApiKeyMapper apiKeyMapper, TenantMapper tenantMapper,
                         StringRedisTemplate redis,
                         @Value("${aihub.apikey.cache-ttl:5m}") Duration cacheTtl) {
        this.apiKeyMapper = apiKeyMapper;
        this.tenantMapper = tenantMapper;
        this.redis = redis;
        this.cacheTtl = cacheTtl;
    }

    public record IssuedKey(String token, String keyId) {
    }

    @Transactional
    public IssuedKey mint(String tenantName, String keyName, Instant expireAt) {
        TenantEntity tenant = findOrCreateTenant(tenantName);

        String keyId = ApiKeyHasher.newKeyId();
        String secret = ApiKeyHasher.newSecret();
        String keyHash = ApiKeyHasher.hash(secret);

        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId(keyId);
        entity.setTenantId(tenant.getId());
        entity.setKeyHash(keyHash);
        entity.setName(keyName);
        entity.setStatus(ApiKeyView.STATUS_ACTIVE);
        entity.setExpireAt(expireAt);
        apiKeyMapper.insert(entity);

        cache(keyHash, new ApiKeyView(keyId, tenant.getId(), tenantName, ApiKeyView.STATUS_ACTIVE, expireAt));
        log.info("已铸造 API Key keyId={} tenant={} name={}", keyId, tenantName, keyName);
        return new IssuedKey(keyId + "." + secret, keyId);
    }

    public Optional<ApiKeyView> resolve(String keyHash) {
        ApiKeyView cached = readCache(keyHash);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<ApiKeyView> fromDb = loadFromDb(keyHash);
        fromDb.ifPresent(view -> cache(keyHash, view));
        return fromDb;
    }

    private Optional<ApiKeyView> loadFromDb(String keyHash) {
        ApiKeyEntity entity = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKeyEntity>()
                .eq(ApiKeyEntity::getKeyHash, keyHash));
        if (entity == null) {
            return Optional.empty();
        }
        TenantEntity tenant = tenantMapper.selectById(entity.getTenantId());
        String tenantName = tenant == null ? "" : tenant.getName();
        return Optional.of(new ApiKeyView(entity.getKeyId(), entity.getTenantId(), tenantName,
                entity.getStatus(), entity.getExpireAt()));
    }

    private TenantEntity findOrCreateTenant(String tenantName) {
        TenantEntity existing = tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getName, tenantName));
        if (existing != null) {
            return existing;
        }
        TenantEntity created = new TenantEntity();
        created.setName(tenantName);
        created.setStatus(ApiKeyView.STATUS_ACTIVE);
        tenantMapper.insert(created);
        return created;
    }

    /** 缓存读写都吞掉异常：Redis 不可用时降级回 MySQL，而不是让鉴权失败。 */
    private void cache(String keyHash, ApiKeyView view) {
        try {
            redis.opsForValue().set(cacheKey(keyHash), ApiKeyCacheCodec.encode(view), cacheTtl);
        } catch (RuntimeException e) {
            log.warn("写入密钥缓存失败，忽略: {}", e.toString());
        }
    }

    private ApiKeyView readCache(String keyHash) {
        try {
            return ApiKeyCacheCodec.decode(redis.opsForValue().get(cacheKey(keyHash)));
        } catch (RuntimeException e) {
            log.warn("读取密钥缓存失败，回源 MySQL: {}", e.toString());
            return null;
        }
    }

    /**
     * 缓存 key 的唯一构造点。前缀来自 {@link ApiKeyCacheCodec#CACHE_KEY_PREFIX}（跨服务共享），
     * Task 4 的 gateway 会用**同一个**前缀读同一批 entry，这里绝不能出现第二个字面量。
     */
    private static String cacheKey(String keyHash) {
        return ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash;
    }
}
