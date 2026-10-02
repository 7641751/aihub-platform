package com.aihub.service.quota;

import com.aihub.common.quota.QuotaKeys;
import com.aihub.dao.entity.QuotaEntity;
import com.aihub.dao.mapper.BillingDailyMapper;
import com.aihub.dao.mapper.QuotaMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 每日对账（决策 D12）：按 {@code request_log} **重算**某一天的 {@code billing_daily}，并把
 * 「周期至今的重算用量」与 Redis 预扣桶里的估算用量比对，**只报告偏差，绝不改账**。
 *
 * <p><b>为什么是「报告」而不是「修正」（D12）</b>：计量是「至少一次 + 幂等」，02:00 时
 * {@code request_log} 可能还没补全（DLQ 延迟到达）。自动改账会在这种时候把**正确**的账改**错**。
 * 先报告、人工确认、再执行修正是更安全的顺序。因此本类**从不**写 {@code quota.token_used} /
 * {@code request_used}（有 {@code QuotaReconciliationTest#aDeviationBeyondTheToleranceIsCountedAndAuditedButDoesNotChangeTheQuota}
 * 钉住）。
 *
 * <h2>⚠️ 两个不同 scope（裁定 1：先定死口径）</h2>
 * <p>被重算的是**一天**，而 Redis 桶 {@code aihub:quota:<tenant>:<YYYYMM>} 装的是**当月累计**。
 * 把「这一天的 tokens」直接与桶比，**每月除最后一天外都会误报**。因此：
 * <ul>
 *   <li>{@link ReconciliationReport#requests()} / {@link ReconciliationReport#tokens()} = **当天**的重算值
 *       （跨全部租户，来自 {@code billing_daily} 的该日合计）；</li>
 *   <li>{@link ReconciliationReport#mismatches()} = **周期至今**的口径
 *       （{@code SUM(billing_daily.tokens WHERE stat_date ∈ [周期首日, statDate])} vs 桶的 {@code tok}）。</li>
 * </ul>
 * 两个 scope 刻意不同，且{@code aMatchingPeriodToDateIsNotReportedEvenWhenTheDayItselfDiffers} 钉住
 * 「当天与桶不同、周期至今与桶一致 ⇒ 不许报」。
 *
 * <h2>⚠️ 偏差判据（裁定 5，含除零）</h2>
 * <p>{@code ratio = |周期至今重算值 − 桶值| / max(周期至今重算值, 1)}，{@code ratio > tolerance} 才算
 * （**等于不算**，边界只定义一次）。**当重算值为 0 而桶值 &gt; 0 时必须仍能报出** —— 那是
 * 「预扣了但没落账」，最严重的一类，**不许**把它当除零静默跳过（分母的 {@code max(..., 1)} 就是为此）。
 *
 * <h2>候选租户（只比对「受限」的租户）</h2>
 * <p>候选集 = **该 period 下两个限额任一为正**的 {@code quota} 行。理由：桶只对受限租户创建
 * （{@code 0 = 不限}，决策 D15 —— 不限的租户连记账都不做，见 {@code InternalQuotaController}）。
 * 若把「不限」的租户也拉进来，其「有落账、无桶」会被算成 100% 偏差，纯噪声。
 * <b>已知边界</b>：某租户的 {@code quota} 行被删（转为不限）但桶里仍残留 {@code tok} 时，本类不再比它 ——
 * 该窗口里的残留会漏报，登记为边界而非假装不存在。
 *
 * <h2>已知近似值（裁定 9 / CONVENTIONS §6.5）</h2>
 * <p>{@code request_log} 里 {@code error_code = usage_missing / client_disconnected} 的行是**已知近似值**，
 * 重算**不去区分**它们 —— 于是偏差计数器**天然包含**这部分近似数据。
 * 另外 {@code billing_daily.cost} 由重算语句固定写 {@code 0}（本里程碑没有单价表）。
 *
 * <h2>时间口径</h2>
 * <p>全部按 UTC：窗口 {@code [statDate, statDate+1)} 是 UTC 墙钟的 {@link LocalDateTime}
 * （{@code request_log.created_at} 是无时区 {@code DATETIME(3)}，绝不用 {@code Instant} 绑参数）；
 * {@code period} 是 UTC 的 {@code YYYYMM}（{@link #periodOf(LocalDate)}，与 {@code QuotaPeriod.of} 同口径）。
 */
@Service
public class QuotaReconciliationService {

    /** 命中偏差的租户数计数器名（观测契约，出现在告警规则/仪表盘里）。 */
    public static final String MISMATCH_METRIC = "aihub.quota.reconcile.mismatch";

    /** 对账运行次数计数器名。 */
    public static final String RUNS_METRIC = "aihub.quota.reconcile.runs";

    /** {@code audit_log.target_type} 的取值。 */
    private static final String TARGET_TYPE = "QUOTA";

    /** 审计的 system actor（对账是定时任务，没有用户上下文）。 */
    private static final String SYSTEM_ACTOR_ID = "quota-reconcile";

    private static final Logger log = LoggerFactory.getLogger(QuotaReconciliationService.class);

    private final BillingDailyMapper billingDailyMapper;
    private final QuotaMapper quotaMapper;
    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final double toleranceRatio;
    private final Counter mismatchCounter;
    private final Counter runsCounter;

    public QuotaReconciliationService(BillingDailyMapper billingDailyMapper,
                                      QuotaMapper quotaMapper,
                                      StringRedisTemplate redis,
                                      AuditService auditService,
                                      MeterRegistry meterRegistry,
                                      @Value("${aihub.quota.reconcile-tolerance-ratio:0.01}") double toleranceRatio) {
        this.billingDailyMapper = billingDailyMapper;
        this.quotaMapper = quotaMapper;
        this.redis = redis;
        this.auditService = auditService;
        this.toleranceRatio = toleranceRatio;
        this.mismatchCounter = Counter.builder(MISMATCH_METRIC)
                .description("每日对账里命中偏差（周期至今：重算 vs 预扣桶）的租户数")
                .register(meterRegistry);
        this.runsCounter = Counter.builder(RUNS_METRIC)
                .description("每日对账运行次数")
                .register(meterRegistry);
    }

    /**
     * 对账报告。
     *
     * @param requests   **当天**的重算请求数（跨全部租户）
     * @param tokens     **当天**的重算 token 数（跨全部租户）
     * @param mismatches **周期至今**口径下超阈值的租户 → ratio（{@code ratio > tolerance} 才在表内）
     */
    public record ReconciliationReport(long requests, long tokens, Map<Long, Double> mismatches) {
    }

    /**
     * 重算 {@code statDate} 一天的 {@code billing_daily}，并按**周期至今**口径比对偏差。
     *
     * @param statDate UTC 自然日（由调用方按 UTC 折算；本类不做 {@code now()} —— 见 {@code QuotaReconciliationJob}）
     * @return 非空报告；{@code mismatches} 为空表示没有租户超阈值（此时**不写审计行、不加偏差计数**）
     */
    public ReconciliationReport reconcile(LocalDate statDate) {
        Objects.requireNonNull(statDate, "statDate");

        // 窗口是 UTC 墙上时间的半开区间 [statDate 00:00, statDate+1 00:00)。用 LocalDateTime（不是 Instant）。
        LocalDateTime from = statDate.atStartOfDay();
        LocalDateTime to = statDate.plusDays(1).atStartOfDay();
        billingDailyMapper.recomputeDaily(from, to);
        runsCounter.increment();

        long requests = billingDailyMapper.sumRequestsOn(statDate);
        long tokens = billingDailyMapper.sumTokensOn(statDate);

        String period = periodOf(statDate);
        LocalDate periodStart = statDate.withDayOfMonth(1);

        Map<Long, Double> mismatches = new LinkedHashMap<>();
        for (QuotaEntity quota : limitedQuotasOf(period)) {
            long tenantId = quota.getTenantId();
            long recomputed = billingDailyMapper.sumTokensForTenantBetween(tenantId, periodStart, statDate);
            long bucket = bucketTokens(tenantId, period);
            // ratio = |周期至今重算值 − 桶值| / max(周期至今重算值, 1)：分母的下界 1 让「重算值为 0、桶值 > 0」
            // 仍然报（而不是除零 / 静默跳过）。等于 tolerance 不算。
            double ratio = Math.abs((double) recomputed - (double) bucket) / Math.max(recomputed, 1L);
            if (ratio > toleranceRatio) {
                mismatches.put(tenantId, ratio);
            }
        }

        if (!mismatches.isEmpty()) {
            mismatchCounter.increment(mismatches.size());
            // 审计的 tenant_id 为 NULL：对账是平台级定时任务，不对应某个租户（与 LOGIN_FAILURE 的情形同类）。
            // detail 只放非敏感值（日期 / 数量 / 阈值 / 各租户的比值）；AuditService 还会再走一遍脱敏安全网。
            auditService.record(null, new AuditService.Actor("SYSTEM", SYSTEM_ACTOR_ID),
                    AuditAction.RECONCILE_REPORT, TARGET_TYPE, period,
                    Map.of("statDate", statDate.toString(),
                            "mismatches", mismatches.size(),
                            "toleranceRatio", toleranceRatio,
                            "ratioByTenant", mismatches));
            log.warn("每日对账（period={} statDate={}）发现 {} 个租户的周期至今用量与预扣桶偏差超过 {}：{}"
                    + "（只报告，不改账：D12）", period, statDate, mismatches.size(), toleranceRatio, mismatches);
        }
        return new ReconciliationReport(requests, tokens, mismatches);
    }

    // ---------------------------------------------------------------- 内部

    /** 该 period 下**受限**的配额行（两个限额任一为正）。不限的租户没有桶，拉进来只会制造噪声。 */
    private List<QuotaEntity> limitedQuotasOf(String period) {
        return quotaMapper.selectList(new LambdaQueryWrapper<QuotaEntity>().eq(QuotaEntity::getPeriod, period))
                .stream()
                .filter(quota -> nz(quota.getTokenLimit()) > 0 || nz(quota.getRequestLimit()) > 0)
                .toList();
    }

    /** 读预扣桶里的已用 token 估算累计；桶不存在 ⇒ 0（该租户本周期还没有任何预扣）。 */
    private long bucketTokens(long tenantId, String period) {
        String key = QuotaKeys.bucketKey(tenantId, period);
        Object raw = redis.opsForHash().get(key, QuotaKeys.FIELD_TOKENS);
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配额桶的 " + QuotaKeys.FIELD_TOKENS + " 不是整数：" + key + " = " + raw, e);
        }
    }

    /** UTC 的 {@code YYYYMM}；与 {@code QuotaPeriod.of} 同口径（statDate 本身已按 UTC 折算）。 */
    private static String periodOf(LocalDate statDate) {
        return String.format("%04d%02d", statDate.getYear(), statDate.getMonthValue());
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
