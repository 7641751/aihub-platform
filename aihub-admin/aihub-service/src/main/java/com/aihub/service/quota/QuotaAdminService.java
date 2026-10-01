package com.aihub.service.quota;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.common.quota.QuotaScript;
import com.aihub.dao.entity.QuotaEntity;
import com.aihub.dao.mapper.QuotaMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 配额的控制面（{@code quota} 表）：读取/惰性建行 + 更新周期额度。周期折算见
 * {@link QuotaPeriod}（UTC 的 {@code YYYYMM}，决策 D13）；{@code 0} 的限额表示**不限**（决策 D15）。
 *
 * <p>{@code quota} 是**租户维度资源**（按 {@code docs/CONVENTIONS.md} §10）：
 * <ul>
 *   <li><b>R2（写）</b>：写是平台级（{@code tenantId} 由请求体/调用方给出），任何 {@code ADMIN} 都有权；
 *       但审计的 {@code tenant_id} **必须**记**该配额行的**租户 id —— 不是操作者的租户、不是 NULL；</li>
 *   <li><b>R3.2（查）</b>：{@link #getOrCreate} 缺省只服务**令牌租户**的行（由控制器负责取令牌租户）。</li>
 * </ul>
 *
 * <p><b>维度是「租户 + 周期」，不是「租户 + key」</b>：{@code quota} 表只有
 * {@code uk_quota_tenant_period (tenant_id, period)}，没有 {@code api_key_id} —— 配额是租户级的周期预算，
 * 不要照抄限流的「两维」结构。
 *
 * <p><b>{@code getOrCreate} 是「惰性物化」</b>：表里没有该 {@code (tenant, period)} 的行时，插一行
 * **两个额度都是 0**（= 不限）的行。这与「没有行」在行为上**完全等价**（D15：没配额度 = 与 M3 一致），
 * 差别只是控制台从此有一个稳定行 id 可以编辑。因此这个读路径上的写是幂等的、无语义影响的。
 *
 * <p><b>更新用手写乐观锁，不用 {@code @Version}</b>（本仓库不注册 {@code MybatisPlusInterceptor}，
 * 见 {@code QuotaMapper.compareAndSwapLimits}）：拿到当前 {@code version} 后走 CAS，
 * **受影响行数为 0 ⇒ 并发冲突 ⇒ 抛 {@link BizException}（绝不静默覆盖）**。
 *
 * <p><b>数据面生效不在本类</b>：{@code config snapshot} 目前**不携带** {@code quota}（gateway 不连数据库，
 * 它怎么拿到额度是 Task 13 的职责）；因此本类**不**发布配置失效消息（与渠道/路由/限流的写路径不同）。
 * 若 Task 13 把额度接进快照，再回来在这里补 {@code ConfigChangePublisher.publishAfterCommit("quota.update")}。
 */
@Service
public class QuotaAdminService {

    /** {@code audit_log.target_type} 的取值。 */
    private static final String TARGET_TYPE = "QUOTA";

    private final QuotaMapper quotaMapper;
    private final AuditService auditService;
    private final Clock clock;

    /** Spring 注入用的构造器：时钟默认 {@code Clock.systemUTC()}（不依赖 JVM 默认时区）。 */
    @Autowired
    public QuotaAdminService(QuotaMapper quotaMapper, AuditService auditService) {
        this(quotaMapper, auditService, Clock.systemUTC());
    }

    /** 可注入时钟的构造器（用例钉固定瞬时）。 */
    public QuotaAdminService(QuotaMapper quotaMapper, AuditService auditService, Clock clock) {
        this.quotaMapper = quotaMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** 配额的展示视图。已用量（{@code tokenUsed}/{@code requestUsed}）只读 —— 控制面不写它们。 */
    public record QuotaSummary(long id, long tenantId, String period, long tokenLimit, long tokenUsed,
                               long requestLimit, long requestUsed, long version) {
    }

    /**
     * 取该 {@code (tenantId, period)} 的配额行；**不存在则惰性物化一行零额度行**（见类注释）。
     *
     * @throws BizException {@code period} 不是合法的 UTC {@code YYYYMM}（{@code INVALID_PARAM}）
     */
    @Transactional
    public QuotaSummary getOrCreate(long tenantId, String period) {
        requirePeriod(period);
        QuotaEntity existing = find(tenantId, period);
        if (existing != null) {
            return summary(existing);
        }
        QuotaEntity row = new QuotaEntity();
        row.setTenantId(tenantId);
        row.setPeriod(period);
        // 零额度 = 不限（D15）：与「没有行」行为等价，升级是惰性的。
        row.setTokenLimit(0L);
        row.setTokenUsed(0L);
        row.setRequestLimit(0L);
        row.setRequestUsed(0L);
        row.setVersion(0L);
        // 显式 UTC 墙上时间（不用库默认值、不用 Instant）：CONVENTIONS §7。
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        quotaMapper.insert(row);
        return summary(row);
    }

    /**
     * 更新周期额度（{@code token_limit} / {@code request_limit}），用手写乐观锁；不写已用量。
     *
     * @param actor 操作者（审计用）；R2：审计的 {@code tenant_id} = 该配额行的租户
     * @throws BizException {@code INVALID_PARAM}（周期非法 / 额度为负 / 超过 2^53 / **version 冲突**）
     */
    @Transactional
    public QuotaSummary update(long tenantId, String period, long tokenLimit, long requestLimit,
                               AuditService.Actor actor) {
        requirePeriod(period);
        requireNonNegative(tokenLimit, "tokenLimit");
        requireNonNegative(requestLimit, "requestLimit");
        requireWithinLuaExactRange(tokenLimit, "tokenLimit");
        requireWithinLuaExactRange(requestLimit, "requestLimit");

        // 保证行存在。自调用不经过 @Transactional 代理，但外层 update 已经是事务（REQUIRED）⇒ 同一事务。
        QuotaSummary current = getOrCreate(tenantId, period);

        int affected = quotaMapper.compareAndSwapLimits(tenantId, period, tokenLimit, requestLimit,
                current.version());
        if (affected == 0) {
            // 手写乐观锁：受影响行数 0 = 读到的 version 在写入前已被别人推进 ⇒ 冲突。
            // **绝不静默覆盖** —— 调用方重试即可拿到新的 version。
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "配额已被并发修改（version=" + current.version() + " 已过期），请重试");
        }

        // R2：审计 tenant_id = 该配额行的租户 id（不是操作者的、不是 NULL）。
        auditService.record(tenantId, actor, AuditAction.QUOTA_UPDATE, TARGET_TYPE, String.valueOf(current.id()),
                Map.of("period", period, "tokenLimit", tokenLimit, "requestLimit", requestLimit));

        return summary(find(tenantId, period));
    }

    // ---------------------------------------------------------------- 内部

    private QuotaEntity find(long tenantId, String period) {
        return quotaMapper.selectOne(new LambdaQueryWrapper<QuotaEntity>()
                .eq(QuotaEntity::getTenantId, tenantId)
                .eq(QuotaEntity::getPeriod, period));
    }

    private static void requirePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "period 不能为空");
        }
        try {
            QuotaPeriod.nextPeriodStartMillis(period);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, "period 必须是 UTC 的 YYYYMM：" + period);
        }
    }

    /**
     * 负额度没有语义（Lua 里 {@code tokenLimit > 0} 会把负数当「不限」），必须显式拒绝 ——
     * 否则「-1」会静默变成「不限」，与调用方的意图相反。
     */
    private static void requireNonNegative(long limit, String field) {
        if (limit < 0) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为负：" + limit);
        }
    }

    /** 上界（F4）：{@code > 2^53} 会被 Lua 的 double 丢精度；边界只在 {@link QuotaScript} 定义一次。 */
    private static void requireWithinLuaExactRange(long limit, String field) {
        try {
            QuotaScript.assertWithinRange(limit);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " " + e.getMessage());
        }
    }

    private static QuotaSummary summary(QuotaEntity entity) {
        return new QuotaSummary(nz(entity.getId()), entity.getTenantId(), entity.getPeriod(),
                nz(entity.getTokenLimit()), nz(entity.getTokenUsed()),
                nz(entity.getRequestLimit()), nz(entity.getRequestUsed()), nz(entity.getVersion()));
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
