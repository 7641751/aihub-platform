package com.aihub.service.quota;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.common.quota.QuotaScript;
import com.aihub.dao.entity.QuotaEntity;
import com.aihub.dao.mapper.QuotaMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.aihub.service.config.ConfigChangePublisher;
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
 * <p><b>数据面生效（Task 13 接通）</b>：{@code quota} 的额度行现在**会被组装进配置快照**
 * （{@code ConfigSnapshotService#quotas()}），网关（数据面）从快照里读它。因此一次成功的 {@code update}
 * 必须在事务提交后发布 {@code quota.update} 失效消息，让网关立刻丢掉手上的旧快照 —— 否则「控制台改了
 * 额度、数据面最长一个 TTL 之后才生效」与渠道/路由/限流的写路径不一致。
 *
 * <p>发布走 {@link ConfigChangePublisher#publishAfterCommit(String)}（不是 {@code bumpAndPublish}）：
 * 本类的 {@link #update} 是 {@code @Transactional}，发布必须在**提交之后**（回滚的写不许广播，
 * 也不许抬水位）。发布失败只计数 + WARN，绝不把「广播失败」升级成「业务失败」（控制台的写已经提交了）。
 */
@Service
public class QuotaAdminService {

    /** {@code audit_log.target_type} 的取值。 */
    private static final String TARGET_TYPE = "QUOTA";

    /** 配额写路径的失效 reason（有限枚举，进日志与消息正文）。 */
    private static final String INVALIDATE_REASON_QUOTA_UPDATE = "quota.update";

    private final QuotaMapper quotaMapper;
    private final AuditService auditService;
    private final ConfigChangePublisher configChangePublisher;
    private final Clock clock;

    /** Spring 注入用的构造器：时钟默认 {@code Clock.systemUTC()}（不依赖 JVM 默认时区）。 */
    @Autowired
    public QuotaAdminService(QuotaMapper quotaMapper, AuditService auditService,
                             ConfigChangePublisher configChangePublisher) {
        this(quotaMapper, auditService, configChangePublisher, Clock.systemUTC());
    }

    /** 可注入时钟的构造器（用例钉固定瞬时）。 */
    public QuotaAdminService(QuotaMapper quotaMapper, AuditService auditService,
                             ConfigChangePublisher configChangePublisher, Clock clock) {
        this.quotaMapper = quotaMapper;
        this.auditService = auditService;
        this.configChangePublisher = configChangePublisher;
        this.clock = clock;
    }

    /** 配额的展示视图。已用量（{@code tokenUsed}/{@code requestUsed}）只读 —— 控制面不写它们。 */
    public record QuotaSummary(long id, long tenantId, String period, long tokenLimit, long tokenUsed,
                               long requestLimit, long requestUsed, long version) {
    }

    /**
     * 取该 {@code (tenantId, period)} 的配额行；**不存在则惰性物化一行零额度行**（见类注释）。
     *
     * <p><b>并发首建是幂等的（2026-10-01 定稿的做法）</b>：本方法**刻意不带 {@code @Transactional}** ——
     * 它没有跨语句不变量，而「读 → 原子插或忽略 → 再读」三步各自成事务，才能同时满足两件事：
     * ① 并发首建**不经过异常路径**（{@code uk_quota_tenant_period} 由
     * {@link com.aihub.dao.mapper.QuotaMapper#insertZeroRowIfAbsent} 的
     * {@code ON DUPLICATE KEY UPDATE id = id} 吸收，不再有 {@code DuplicateKeyException} ⇒ 不再 500）；
     * ② 第二次读是**新事务的新一致性读视图**，因此能看见并发对手已提交的那一行
     * （若把三步塞进同一个 REPEATABLE READ 事务里，视图在第一次 {@code SELECT} 返回 null 时就固定了，
     * 重读仍是 null）。
     * 早先「catch {@code DuplicateKeyException} + {@code SELECT … FOR UPDATE}「重读」的写法**在并发下死锁**
     * （8 线程实测 {@code Deadlock found when trying to get lock}），已弃用；见
     * {@code QuotaMapper#insertZeroRowIfAbsent} 的注释。
     *
     * @throws BizException {@code period} 不是合法的 UTC {@code YYYYMM}（{@code INVALID_PARAM}）
     */
    public QuotaSummary getOrCreate(long tenantId, String period) {
        requirePeriod(period);
        QuotaEntity existing = find(tenantId, period);
        if (existing != null) {
            return summary(existing);
        }
        // 显式 UTC 墙上时间（不用库默认值、不用 Instant）：CONVENTIONS §7。
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        // 零额度 = 不限（D15）：与「没有行」行为等价，升级是惰性的。
        quotaMapper.insertZeroRowIfAbsent(tenantId, period, now);
        QuotaEntity created = find(tenantId, period);
        if (created == null) {
            // 理论上不可达：insertZeroRowIfAbsent 之后必然存在一行（本事务刚写或并发已提交。
            // 后者要求本次「再读」是新视图 —— 这正是本方法不带 @Transactional 的原因）。宁可响亮失败。
            throw new IllegalStateException("quota row vanished after an atomic insert-if-absent: "
                    + tenantId + "/" + period);
        }
        return summary(created);
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

        // 让数据面（网关）立刻丢掉手上的旧快照：quota 现在会被组装进快照（Task 13）。
        // 走 publishAfterCommit —— 本方法在事务里，发布必须在**提交之后**（回滚的写不许广播、不许抬水位）。
        configChangePublisher.publishAfterCommit(INVALIDATE_REASON_QUOTA_UPDATE);

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
