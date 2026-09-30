package com.aihub.service.ratelimit;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.dao.entity.RateLimitPolicyEntity;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.aihub.service.config.ConfigChangePublisher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 限流策略的控制面写路径（Task 10）：upsert / 列表 / 更新 / 停用。
 * {@code rate_limit_policy} 是**租户维度资源**，按 {@code docs/CONVENTIONS.md} §10：
 * <ul>
 *   <li><b>R2（写）</b>：写是平台级（请求体带 {@code tenantId}），任何 {@code ADMIN} 都有权；
 *       但审计的 {@code tenant_id} **必须**记**该策略的**租户 id —— 不是操作者的租户、不是 NULL；</li>
 *   <li><b>R3.2（查）</b>：{@link #list(long)} 缺省只回**令牌租户**的行（least privilege）。</li>
 * </ul>
 *
 * <p><b>维度的定义是 {@code (tenant_id, api_key_id)}，其中 {@code api_key_id IS NULL} = 租户级</b>
 * （非空 = key 级）。因此 {@link #deactivateActiveRows} 查「同维度」时，租户级那一支**必须**
 * {@code isNull()} —— {@code eq(column, null)} 在 MyBatis-Plus 下恒不成立、会静默返回 0 行
 * （{@code CONVENTIONS.md} §7），那样「两个维度共存」就会退化成「全租户只剩一条 ACTIVE」或者
 * 「同维度出现两条 ACTIVE、M3 的『取最后一条』变成依赖插入顺序的运气」。
 *
 * <p><b>upsert 的两步落库只发一条广播</b>：先停用同维度旧 ACTIVE 行、再插新 ACTIVE 行（两次写库），
 * 但只调用**一次** {@link ConfigChangePublisher#publishAfterCommit(String)}（reason
 * {@code rate_limit.create}）—— 一次写 = 一次广播。
 *
 * <p><b>DELETE 是软停用（不删行）</b>：依据是 {@code AuditAction} 里只有
 * {@code RATE_LIMIT_DEACTIVATE}（**没有** {@code RATE_LIMIT_DELETE}），且 M3 决策 17 的「同维度取
 * 最后一条」需要历史行继续存在 —— 与 Task 9 的 api-keys {@code DELETE}（真删行）**刻意不同**。
 *
 * <p><b>PUT 只改 {@code qps}/{@code burst}，且该行必须仍是 ACTIVE</b>（否则 404）：停用的行不该再被
 * 当作「当前生效策略」来改，改它没有意义（它不会进快照）。
 */
@Service
public class RateLimitPolicyAdminService {

    /** {@code audit_log.target_type} 的取值（{@code AuditLogEntity} 的类注释里登记了这一档）。 */
    private static final String TARGET_TYPE = "RATE_LIMIT";

    /** 状态字面量：私有 —— 与 {@link com.aihub.common.config.RatePolicy}（没有状态分量）解耦。 */
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";

    private final RateLimitPolicyMapper policyMapper;
    private final AuditService auditService;
    private final ConfigChangePublisher publisher;

    public RateLimitPolicyAdminService(RateLimitPolicyMapper policyMapper, AuditService auditService,
                                       ConfigChangePublisher publisher) {
        this.policyMapper = policyMapper;
        this.auditService = auditService;
        this.publisher = publisher;
    }

    /**
     * upsert 请求。{@code apiKeyId} 为 {@code null} 表示**租户级**维度；{@code qps}/{@code burst} 必填且 ≥ 0。
     */
    public record PolicyWrite(Long tenantId, Long apiKeyId, Integer qps, Integer burst) {
    }

    /** PUT 请求：只改 {@code qps}/{@code burst}（{@code null} = 不改动）。 */
    public record PolicyUpdate(Integer qps, Integer burst) {
    }

    /** 策略的展示视图。{@code apiKeyId} 为 {@code null} 表示租户级。 */
    public record PolicySummary(long id, long tenantId, Long apiKeyId, int qps, int burst, String status) {
    }

    /**
     * 写入一条策略：**先把同维度的旧 ACTIVE 行置 INACTIVE，再插新的 ACTIVE 行**，这样 M3 决策 17 的
     * 「同维度取最后一条」在任何时候都只有一条候选。两行落库、**一次**广播。
     */
    @Transactional
    public PolicySummary upsert(PolicyWrite write, AuditService.Actor actor) {
        if (write.tenantId() == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "tenantId 不能为空");
        }
        long tenantId = write.tenantId();
        Long apiKeyId = write.apiKeyId();
        int qps = requireNonNegative(write.qps(), "qps");
        int burst = requireNonNegative(write.burst(), "burst");

        deactivateActiveRows(tenantId, apiKeyId);

        RateLimitPolicyEntity entity = new RateLimitPolicyEntity();
        entity.setTenantId(tenantId);
        entity.setApiKeyId(apiKeyId);
        entity.setQps(qps);
        entity.setBurst(burst);
        entity.setStatus(STATUS_ACTIVE);
        policyMapper.insert(entity);

        // R2：审计 tenant_id = 该策略的租户 id（不是操作者的、不是 NULL）。
        audit(tenantId, actor, AuditAction.RATE_LIMIT_CREATE, entity,
                Map.of("tenantId", tenantId, "apiKeyId", apiKeyId == null ? "" : apiKeyId,
                        "qps", qps, "burst", burst));
        publisher.publishAfterCommit("rate_limit.create");
        return summary(entity);
    }

    /** R3.2：缺省 = 令牌里的 {@code tenantId}（最小权限）。 */
    public List<PolicySummary> list(long tenantId) {
        return policyMapper.selectList(forTenant(tenantId)).stream()
                .map(RateLimitPolicyAdminService::summary)
                .toList();
    }

    /** 只改 {@code qps}/{@code burst}；目标行必须仍是 ACTIVE，否则 404。 */
    @Transactional
    public PolicySummary update(long id, PolicyUpdate update, AuditService.Actor actor) {
        RateLimitPolicyEntity entity = requireActiveRow(id);
        if (update.qps() != null) {
            entity.setQps(requireNonNegative(update.qps(), "qps"));
        }
        if (update.burst() != null) {
            entity.setBurst(requireNonNegative(update.burst(), "burst"));
        }
        policyMapper.updateById(entity);

        audit(entity.getTenantId(), actor, AuditAction.RATE_LIMIT_UPDATE, entity,
                Map.of("qps", entity.getQps(), "burst", entity.getBurst()));
        publisher.publishAfterCommit("rate_limit.update");
        return summary(entity);
    }

    /**
     * {@code DELETE /api/rate-limits/{id}} = **置 {@code INACTIVE}（软停用、不删行）**（见类注释）。
     */
    @Transactional
    public void deactivate(long id, AuditService.Actor actor) {
        RateLimitPolicyEntity entity = requirePolicy(id);
        entity.setStatus(STATUS_INACTIVE);
        policyMapper.updateById(entity);

        audit(entity.getTenantId(), actor, AuditAction.RATE_LIMIT_DEACTIVATE, entity,
                Map.of("status", STATUS_INACTIVE));
        publisher.publishAfterCommit("rate_limit.deactivate");
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 把**同一维度**（{@code (tenant_id, api_key_id)}）中仍是 ACTIVE 的行置 INACTIVE。
     *
     * <p>租户级维度（{@code apiKeyId == null}）**必须** {@code isNull()}：{@code eq(column, null)} 恒不成立
     * （CONVENTIONS §7），用它查会静默 0 行。
     */
    private void deactivateActiveRows(long tenantId, Long apiKeyId) {
        LambdaQueryWrapper<RateLimitPolicyEntity> dimension = new LambdaQueryWrapper<RateLimitPolicyEntity>()
                .eq(RateLimitPolicyEntity::getTenantId, tenantId)
                .eq(RateLimitPolicyEntity::getStatus, STATUS_ACTIVE);
        if (apiKeyId == null) {
            dimension.isNull(RateLimitPolicyEntity::getApiKeyId);
        } else {
            dimension.eq(RateLimitPolicyEntity::getApiKeyId, apiKeyId);
        }
        RateLimitPolicyEntity patch = new RateLimitPolicyEntity();
        patch.setStatus(STATUS_INACTIVE);
        policyMapper.update(patch, dimension);
    }

    /** R3.2 的列表过滤：只按令牌租户（今天不做跨租户列举，登记为待办）。 */
    private LambdaQueryWrapper<RateLimitPolicyEntity> forTenant(long tenantId) {
        return new LambdaQueryWrapper<RateLimitPolicyEntity>()
                .eq(RateLimitPolicyEntity::getTenantId, tenantId)
                .orderByAsc(RateLimitPolicyEntity::getId);
    }

    private RateLimitPolicyEntity requirePolicy(long id) {
        RateLimitPolicyEntity entity = policyMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "限流策略不存在: id=" + id);
        }
        return entity;
    }

    /** 目标行必须仍是 ACTIVE（停用的行不再代表「当前生效策略」）→ 否则 404。 */
    private RateLimitPolicyEntity requireActiveRow(long id) {
        RateLimitPolicyEntity entity = requirePolicy(id);
        if (!STATUS_ACTIVE.equals(entity.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "限流策略不存在或已停用: id=" + id);
        }
        return entity;
    }

    private void audit(Long tenantId, AuditService.Actor actor, String action, RateLimitPolicyEntity entity,
                       Map<String, Object> detail) {
        auditService.record(tenantId, actor, action, TARGET_TYPE, String.valueOf(entity.getId()), detail);
    }

    private static int requireNonNegative(Integer value, String field) {
        if (value == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为空");
        }
        if (value < 0) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为负: " + value);
        }
        return value;
    }

    private static PolicySummary summary(RateLimitPolicyEntity entity) {
        return new PolicySummary(entity.getId(), entity.getTenantId(), entity.getApiKeyId(),
                entity.getQps() == null ? 0 : entity.getQps(),
                entity.getBurst() == null ? 0 : entity.getBurst(), entity.getStatus());
    }
}
