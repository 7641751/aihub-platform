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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * API Key 的铸造与解析。
 * <p>铸造：生成 key_id 与 secret，只把 {@code SHA-256(secret)} 入库，明文只在返回值里出现一次。
 * <p>解析：Redis 缓存优先，未命中回源 MySQL 并回填 —— 缓存是可丢的派生数据，MySQL 才是真相源。
 * <p>缓存载荷用 {@link ApiKeyCacheCodec} 而不是 JSON：gateway 要读同一份数据，
 * 而 {@code aihub-common} 必须保持零依赖。
 * <p>密钥视图与「是否可用」的规则都来自 {@link ApiKeyView}，两个服务共用同一个定义。
 *
 * <p><b>Redis 不可用时必须**快速**降级（D1）</b>：{@code resolve} 是
 * {@code POST /internal/api-keys/resolve} 的全部业务逻辑，而 gateway 给这一跳的预算是
 * {@code AdminClientConfig.responseTimeout(3s)}。Redis 停机时每一次缓存访问都要等满
 * {@code spring.data.redis.timeout}，**读缓存 + 回写缓存**两次叠加就会超过 3 秒 ——
 * 网关于是把一条**健康**的回答当成传输故障（compose 全栈验收实测 WARN→ERROR 恰好 3.01 秒），
 * 一把有效的 key 因此变成 401。所以：
 * <ul>
 *   <li><b>粘性降级</b>：一次 Redis 访问失败之后，接下来 {@link #REDIS_DEGRADE_STICKY_MILLIS}
 *       毫秒内**不再碰 Redis**（连试都不试），请求直接走 MySQL。与网关限流器
 *       {@code RateLimiter} 的粘性降级同款；</li>
 *   <li><b>一次故障只赔一次</b>：「读缓存失败」当场就进入粘性降级，因此同一次 {@code resolve}
 *       里的**回写缓存**会被跳过，不会让同一个请求连赔两次超时；</li>
 *   <li>粘性窗口只影响**缓存**，不影响**正确性**：窗口内照常从 MySQL（真相源）解析，
 *       窗口过后自动再试一次 Redis。</li>
 * </ul>
 * 这段窗口与 Redis 客户端自己的命令超时（{@code spring.data.redis.timeout}）是两件事：
 * 前者决定「多久不再试」，后者决定「试的那一次最多赔多久」；两者都得小，才能稳稳落进 3 秒。
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    /**
     * 「Redis 密钥缓存不可用」的粘性窗口。取 5 秒的理由：它只推迟**缓存**的恢复，
     * 不推迟**服务**的恢复（窗口内照常从 MySQL 解析），因此可以比网关限流器的 1 秒更宽 ——
     * 宽一点才能把「Redis 挂了期间每个请求都去撞一次超时」真正按下去。
     */
    private static final long REDIS_DEGRADE_STICKY_MILLIS = 5_000L;

    private final ApiKeyMapper apiKeyMapper;
    private final TenantMapper tenantMapper;
    private final StringRedisTemplate redis;
    private final Duration cacheTtl;

    private final AtomicBoolean redisDegraded = new AtomicBoolean(false);
    private volatile long degradedAtMillis;

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

    /**
     * 铸造一把 key。{@code expireAt} 是**瞬时**（跨服务契约用 {@code Instant}）；
     * 落库时显式折成 {@code api_key.expire_at} 的基准 —— **UTC 墙上时间**
     * （{@code DATETIME(3)}，与兄弟列 {@code created_at} / {@code updated_at} 同基准）。
     *
     * <p><b>这个换算是承重的</b>：实体字段是 {@code LocalDateTime}（不做时区换算的载体），
     * 库里的那串数字就是 UTC 墙上时间。若这里直接塞 {@code Instant}（或让字段退回 {@code Instant}），
     * 驱动会按 **JVM 默认时区**（本机 Asia/Shanghai）折墙上时间，实体往返虽然自洽，但列里的值与
     * 兄弟列差 8 小时 —— 裸 SQL 写入方、以及将来 {@code where expire_at > now()} 的比较都会错。
     * 基准只写在这里与 {@link #loadFromDb} 的读回处各一次，不依赖 JVM 设置。
     */
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
        entity.setExpireAt(expireAt == null ? null : LocalDateTime.ofInstant(expireAt, ZoneOffset.UTC));
        apiKeyMapper.insert(entity);

        // 缓存载荷仍用**瞬时**（{@link ApiKeyCacheCodec} 存 epoch 秒，跨服务契约）：换算只发生在
        // 实体 ↔ 数据库之间，Redis / JSON 的线格式一个字节都没变。
        cache(keyHash, new ApiKeyView(keyId, tenant.getId(), tenantName, ApiKeyView.STATUS_ACTIVE, expireAt,
                entity.getId()));
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

    /**
     * 回源 MySQL。{@code api_key.expire_at} 是 {@code DATETIME(3)}、按 **UTC 墙上时间**存储
     * （与列 {@code created_at} / {@code updated_at} 同基准），实体字段因此是
     * {@link LocalDateTime}；这里显式声明基准折回 {@link ApiKeyView} 需要的**瞬时**
     * （{@code toInstant(ZoneOffset.UTC)}）。
     *
     * <p>不这么做（字段留 {@code Instant}、或在这里用 {@code Timestamp}）会让驱动按 **JVM 默认
     * 时区**解释那一格，读出的瞬时整整差 8 小时（本机 Asia/Shanghai）——而
     * {@link ApiKeyView#usable()} 正是拿这个瞬时与 {@code Instant.now()} 比的，落在鉴权路径上。
     */
    private Optional<ApiKeyView> loadFromDb(String keyHash) {
        ApiKeyEntity entity = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKeyEntity>()
                .eq(ApiKeyEntity::getKeyHash, keyHash));
        if (entity == null) {
            return Optional.empty();
        }
        TenantEntity tenant = tenantMapper.selectById(entity.getTenantId());
        String tenantName = tenant == null ? "" : tenant.getName();
        // 视图（跨服务契约）仍是瞬时：null 保持 null（= 永不过期），不做任何默认值兜底。
        Instant expireAt = entity.getExpireAt() == null ? null : entity.getExpireAt().toInstant(ZoneOffset.UTC);
        return Optional.of(new ApiKeyView(entity.getKeyId(), entity.getTenantId(), tenantName,
                entity.getStatus(), expireAt, entity.getId()));
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

    /**
     * 回填缓存：失败只记日志；**正处于粘性降级窗口内时直接跳过**（见类注释「一次故障只赔一次」）。
     * 这一步不参与「回答是什么」，只影响下一次能不能少走一趟 MySQL。
     */
    private void cache(String keyHash, ApiKeyView view) {
        if (isDegradedNow()) {
            return;
        }
        try {
            redis.opsForValue().set(cacheKey(keyHash), ApiKeyCacheCodec.encode(view), cacheTtl);
            clearDegraded();
        } catch (RuntimeException e) {
            markDegraded();
            log.warn("写入密钥缓存失败，忽略: {}", e.toString());
        }
    }

    /**
     * 读缓存：失败或**正处于粘性降级窗口内**都返回 {@code null}（调用方回源 MySQL）。
     * 「窗口内直接返回 null」是 D1 的关键：它让故障期间每个请求只赔**一次**超时（甚至零次），
     * 而不是每次都赔满两次。
     */
    private ApiKeyView readCache(String keyHash) {
        if (isDegradedNow()) {
            return null;
        }
        try {
            ApiKeyView view = ApiKeyCacheCodec.decode(redis.opsForValue().get(cacheKey(keyHash)));
            clearDegraded();
            return view;
        } catch (RuntimeException e) {
            markDegraded();
            log.warn("读取密钥缓存失败，回源 MySQL: {}", e.toString());
            return null;
        }
    }

    private boolean isDegradedNow() {
        return redisDegraded.get() && System.currentTimeMillis() - degradedAtMillis < REDIS_DEGRADE_STICKY_MILLIS;
    }

    private void markDegraded() {
        degradedAtMillis = System.currentTimeMillis();
        if (redisDegraded.compareAndSet(false, true)) {
            log.error("Redis 密钥缓存不可用，已降级为直连 MySQL（真相源）；{} 秒内不再尝试 Redis"
                    + "（避免每个请求都等满一次 Redis 超时、把网关给内部跳的 3 秒预算耗光）",
                    REDIS_DEGRADE_STICKY_MILLIS / 1000);
        }
    }

    private void clearDegraded() {
        if (redisDegraded.compareAndSet(true, false)) {
            log.info("Redis 密钥缓存已恢复");
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
