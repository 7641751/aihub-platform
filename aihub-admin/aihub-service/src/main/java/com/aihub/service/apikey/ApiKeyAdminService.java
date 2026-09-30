package com.aihub.service.apikey;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.apikey.ApiKeyCacheCodec;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.exception.BizException;
import com.aihub.dao.entity.ApiKeyEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.ApiKeyMapper;
import com.aihub.dao.mapper.TenantMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.aihub.service.config.ConfigChangePublisher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * API Key 的控制面写路径（Task 9）：签发 / 列表 / 停用 / 启用 / 删除，以及**吊销时的显式
 * {@code DEL} 共享缓存**（D11 —— M3 决策 16 交接下来的那一条）。
 *
 * <p><b>明文密钥的生命周期只有一次函数调用</b>：{@code plaintextKey} 从
 * {@link ApiKeyService#issue} 出来、原样放进 {@link ApiKeyCreated} 的返回里，此后任何接口
 * （列表、审计、日志、指标）都不许再出现它，也不许出现 {@code key_hash}。{@link ApiKeySummary}
 * 因此**没有** {@code plaintextKey} / {@code keyHash} 字段。
 *
 * <p><b>生成的唯一实现是 {@link ApiKeyService#issue}</b>：本类不写第二份生成逻辑，只负责
 * 「按 {@code tenantId} 找租户 → 委托签发 → 读回主键 → 审计」。密钥格式被
 * {@code ApiKeyToolingTest} 的固定向量钉死，一个字节都不许变。
 *
 * <p><b>每个写方法都在同一个 {@code @Transactional} 里做四件事</b>：业务写（改状态 / 删行）+
 * 审计（{@link AuditService} 刻意不加 {@code REQUIRES_NEW}，因此加入本方法的事务）+
 * {@code DEL} 共享缓存 + {@link ConfigChangePublisher#publishAfterCommit(String)}
 * （注册 after-commit 钩子，**提交之后**才抬水位并广播）。
 *
 * <p><b>{@code DEL} 在事务体内、失败只 WARN</b>：见 {@link #evictSharedCache(String)} 的完整理由。
 *
 * <p><b>审计的 {@code tenant_id} 是「该 key 的租户 id」</b>（裁定 4）：API Key 是租户级资源，
 * 所以这一维**存在**，必须写它 —— 与 {@code ChannelAdminService} 传 {@code null} 的理由相反
 * （渠道在本 schema 里没有租户维度）。操作者另外记在 {@code actor} 列里。
 *
 * <p><b>状态字面量</b>：{@code ACTIVE} 复用跨服务契约
 * {@link ApiKeyView#STATUS_ACTIVE}；{@code DISABLED} 是本模块的私有常量 —— 共享类型上只有
 * {@code ACTIVE}，不为它去改跨服务契约（裁定 4）。
 */
@Service
public class ApiKeyAdminService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAdminService.class);

    private static final String TARGET_TYPE = "API_KEY";

    /**
     * 「停用」的状态字面量。**私有**：它不进跨服务契约（网关只认 {@code ACTIVE} 与
     * {@link ApiKeyView#usable()}，任何非 {@code ACTIVE} 都不可用），所以没有必要把它放上共享类型。
     */
    private static final String STATUS_DISABLED = "DISABLED";

    private final ApiKeyMapper apiKeyMapper;
    private final TenantMapper tenantMapper;
    private final ApiKeyService apiKeyService;
    private final AuditService auditService;
    private final StringRedisTemplate redis;
    private final ConfigChangePublisher publisher;

    public ApiKeyAdminService(ApiKeyMapper apiKeyMapper, TenantMapper tenantMapper, ApiKeyService apiKeyService,
                              AuditService auditService, StringRedisTemplate redis, ConfigChangePublisher publisher) {
        this.apiKeyMapper = apiKeyMapper;
        this.tenantMapper = tenantMapper;
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
        this.redis = redis;
        this.publisher = publisher;
    }

    /**
     * 签发请求。{@code validDays} 为 {@code null} 或非正数表示**永不过期**
     * （与 {@code ApiKeyMintRunner} 的语义一致）。
     */
    public record ApiKeyCreateRequest(long tenantId, String name, Integer validDays) {
    }

    /** 签发结果：明文 {@code plaintextKey} **只有这一个出口**。 */
    public record ApiKeyCreated(long id, String keyId, String plaintextKey) {
    }

    /**
     * 列表视图：**无明文、无 {@code key_hash}**。
     *
     * <p>刻意**不叫** {@code ApiKeyView}：那与 {@code aihub-common} 的跨服务类型
     * {@code com.aihub.common.apikey.ApiKeyView} 同名，而本 record 与 {@link ApiKeyService} **同包** ——
     * 同一个包里再声明一个同名类型之后，{@code ApiKeyCacheCodec.encode(...)} 的实参类型极易混错
     * （计划 Step 3 的 2026-09-30 控制器更正）。
     *
     * <p>{@code lastUsedAt} 今天**恒为 {@code null}**：{@code api_key.last_used_at} 列存在
     * （{@code V1__init_schema.sql:36}），但**没有任何代码写它**。不为了让它有值而在读路径写库。
     */
    public record ApiKeySummary(long id, String keyId, long tenantId, String name, String status,
                                Instant expireAt, Instant lastUsedAt) {
    }

    /**
     * 签发：按 {@code tenantId} 找租户（查不到 → 404 {@code NOT_FOUND}），再委托
     * {@link ApiKeyService#issue}。**审计与签发在同一个事务里**。
     */
    @Transactional
    public ApiKeyCreated create(ApiKeyCreateRequest request, AuditService.Actor actor) {
        String name = requireText(request.name(), "name");
        TenantEntity tenant = tenantMapper.selectById(request.tenantId());
        if (tenant == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "租户不存在: id=" + request.tenantId());
        }

        ApiKeyService.IssuedKey issued = apiKeyService.issue(tenant.getId(), tenant.getName(), name,
                expireAt(request.validDays()));
        ApiKeyEntity entity = requireByKeyId(issued.keyId());

        audit(actor, AuditAction.API_KEY_CREATE, entity,
                Map.of("keyId", entity.getKeyId(), "name", entity.getName()));
        return new ApiKeyCreated(entity.getId(), issued.keyId(), issued.token());
    }

    /**
     * 列出某个租户的 key。{@code tenantId} 是**必填维度**（不是可选维度），因此这里用 {@code eq}
     * 是安全的 —— {@code eq(column, null)} 恒不成立那条陷阱（CONVENTIONS §7）针对的是「可选维度」。
     */
    public List<ApiKeySummary> list(long tenantId) {
        return apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKeyEntity>()
                        .eq(ApiKeyEntity::getTenantId, tenantId)
                        .orderByAsc(ApiKeyEntity::getId)).stream()
                .map(ApiKeyAdminService::summary)
                .toList();
    }

    /** 停用：{@code status=DISABLED} + 审计 + {@code DEL} 缓存 + 广播 {@code apikey.disable}。 */
    @Transactional
    public void disable(long apiKeyId, AuditService.Actor actor) {
        updateStatus(apiKeyId, STATUS_DISABLED, AuditAction.API_KEY_DISABLE, "apikey.disable", actor);
    }

    /** 启用：{@code status=ACTIVE}（共享契约里的那个字面量）+ 审计 + {@code DEL} 缓存 + 广播。 */
    @Transactional
    public void enable(long apiKeyId, AuditService.Actor actor) {
        updateStatus(apiKeyId, ApiKeyView.STATUS_ACTIVE, AuditAction.API_KEY_ENABLE, "apikey.enable", actor);
    }

    /**
     * 删除：**真删行**（{@code deleteById}）。审计/缓存/广播与其它写一致 —— 注意审计行查的
     * {@code target_id} 仍然可用（{@code audit_log} 是另一张表，删 key 不动它）。
     */
    @Transactional
    public void delete(long apiKeyId, AuditService.Actor actor) {
        ApiKeyEntity entity = requireKey(apiKeyId);
        apiKeyMapper.deleteById(apiKeyId);
        audit(actor, AuditAction.API_KEY_DELETE, entity,
                Map.of("keyId", entity.getKeyId(), "name", entity.getName()));
        evictSharedCache(entity.getKeyHash());
        publisher.publishAfterCommit("apikey.delete");
    }

    // ---------------------------------------------------------------- 内部

    private void updateStatus(long apiKeyId, String status, String action, String reason,
                              AuditService.Actor actor) {
        ApiKeyEntity entity = requireKey(apiKeyId);
        entity.setStatus(status);
        apiKeyMapper.updateById(entity);
        audit(actor, action, entity, Map.of("keyId", entity.getKeyId(), "status", status));
        evictSharedCache(entity.getKeyHash());
        publisher.publishAfterCommit(reason);
    }

    /**
     * 共享层的**即时**失效（D11）：控制台改状态后立刻删掉网关要读的那条缓存条目，
     * 前缀取自 {@link ApiKeyCacheCodec#CACHE_KEY_PREFIX}（跨服务契约，**不写字面量**）。
     *
     * <p><b>为什么在事务体内、而不是 after-commit</b>：若事务随后回滚，最坏结果只是「多一次缓存未命中、
     * 下次从 MySQL 回填」—— **不可能**给出错误答案。反过来放在 afterCommit 会留下**更坏**的形态：
     * 提交之后 {@code DEL} 失败时，缓存会继续放行一把**已被停用**的 key。
     *
     * <p><b>失败必须吞掉（只 WARN）</b>：Redis 不可用时 {@code delete} 会抛，而控制面的写**不能**因为
     * 缓存清理失败而失败 —— 网关侧还有 TTL 兜底（残余：本实例本地 Caffeine 最长 30 秒，见 D11）。
     * 方向与「配额降级 fail-open」的裁决一致：控制面的可用性优先于缓存的新鲜度。
     *
     * <p>日志里**不打 {@code keyHash}**：它是可离线爆破的凭证材料，与「日志里不许出现明文或
     * {@code key_hash}」是同一条纪律。
     */
    private void evictSharedCache(String keyHash) {
        try {
            redis.delete(ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash);
        } catch (RuntimeException e) {
            log.warn("清理共享密钥缓存失败（控制面写已按原样进行，网关侧靠 TTL 兜底）: {}", e.toString());
        }
    }

    /** 审计写在**调用方事务**里（{@link AuditService#record} 不加 {@code REQUIRES_NEW}）。 */
    private void audit(AuditService.Actor actor, String action, ApiKeyEntity entity, Map<String, Object> detail) {
        auditService.record(entity.getTenantId(), actor, action, TARGET_TYPE, String.valueOf(entity.getId()), detail);
    }

    private ApiKeyEntity requireKey(long id) {
        ApiKeyEntity entity = apiKeyMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "API Key 不存在: id=" + id);
        }
        return entity;
    }

    /**
     * 读回刚签发那一行的**数值主键**（{@link ApiKeyService.IssuedKey} 只带 token 与 keyId）。
     * 查不到是不可能的（同一个事务里刚 insert 过），因此这是**程序不变量**失败而不是 404。
     */
    private ApiKeyEntity requireByKeyId(String keyId) {
        ApiKeyEntity entity = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKeyEntity>()
                .eq(ApiKeyEntity::getKeyId, keyId));
        if (entity == null) {
            throw new IllegalStateException("刚签发的 key 在库里查不到: keyId=" + keyId);
        }
        return entity;
    }

    /** {@code validDays} → 瞬时：{@code null} / 非正 = 永不过期（与 {@code ApiKeyMintRunner} 同语义）。 */
    private static Instant expireAt(Integer validDays) {
        if (validDays == null || validDays <= 0) {
            return null;
        }
        return Instant.now().plus(Duration.ofDays(validDays));
    }

    /**
     * 实体 → 视图。{@code expire_at} / {@code last_used_at} 都是 {@code DATETIME(3)}、按 **UTC
     * 墙上时间**存储（与兄弟列同基准），因此读侧显式声明基准折算（{@code toInstant(ZoneOffset.UTC)}）
     * —— 不用 {@code Instant} 字段（那会让驱动按 JDBC 连接时区解释这一格，见 CONVENTIONS §7）。
     */
    private static ApiKeySummary summary(ApiKeyEntity entity) {
        return new ApiKeySummary(entity.getId(), entity.getKeyId(), entity.getTenantId(), entity.getName(),
                entity.getStatus(), toInstant(entity.getExpireAt()), toInstant(entity.getLastUsedAt()));
    }

    private static Instant toInstant(LocalDateTime wallClock) {
        return wallClock == null ? null : wallClock.toInstant(ZoneOffset.UTC);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为空");
        }
        return value.strip();
    }
}
