package com.aihub.admin.quota;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.quota.QuotaKeys;
import com.aihub.dao.entity.BillingDailyEntity;
import com.aihub.dao.entity.QuotaEntity;
import com.aihub.dao.mapper.BillingDailyMapper;
import com.aihub.dao.mapper.QuotaMapper;
import com.aihub.service.quota.QuotaReconciliationJob;
import com.aihub.service.quota.QuotaReconciliationService;
import com.aihub.service.quota.QuotaReconciliationService.ReconciliationReport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 15：每日 02:00 对账任务的**真容器**证据（真 MySQL + 真 Redis）。
 *
 * <p>它钉住四类互相独立的行为：
 * <ol>
 *   <li><b>重算</b>：{@code request_log} 按 (tenant, 日) 幂等 UPSERT 进 {@code billing_daily}
 *       （{@code uk_billing_daily(tenant_id, stat_date)} 是幂等的锚点）；{@code cost} 固定 0。</li>
 *   <li><b>偏差判据</b>：比值 {code |周期至今重算值 − 桶值| / max(周期至今重算值, 1)}，{@code > tolerance}
 *       才算（<b>等于不算</b>），且「重算值为 0 而桶值 &gt; 0」<b>必须仍报</b>（不许当除零静默跳过）。</li>
 *   <li><b>比较口径</b>（裁定 1）：比的是**周期至今**，不是当天 —— 「当天与桶不同、周期至今与桶一致」
 *       的夹具**不许报**，否则每月除最后一天外全误报。</li>
 *   <li><b>只报告不改账</b>（D12）：命中偏差的租户，{@code quota.token_used} 必须**一字不动**。</li>
 * </ol>
 *
 * <p><b>为什么用默认 Spring 上下文</b>：本类**不加** {@code @TestPropertySource} / {@code @Import}，
 * 因此复用既有默认上下文（{@link AbstractIntegrationTest}），套件 Spring 上下文总数仍是 7
 * （{@code docs/CONVENTIONS.md} §8 item 5/6 的预算）。
 *
 * <p><b>夹具纪律</b>：{@code billing_daily} / {@code request_log} / {@code quota} 与 Testcontainers
 * 容器都是**JVM 级共享**的，所以每个用例用**自己的** {@code tenantId}（{@code 903_0xx} 段，与 900/901/902
 * 段不重叠），前后各清一次，断言一律按**本用例的 (tenantId, stat_date)** 定向查 —— 绝不做全表计数。
 *
 * <p><b>为什么 statDate 取 2026-09-26</b>：它落在 V1 建的 {@code p202609} 分区里（分区存在，写入不被拒）。
 * 同一 reason（period = {@code 202609}）也与其它 quota 用例隔离：那些用例一律用
 * {@code QuotaPeriod.of(System.currentTimeMillis())}（本机当前月 {@code 202610}），不会制造本 period 的候选行。
 *
 * <p><b>RED 证据（诚实登记）</b>：实现落地之前本类**无法编译**（{@code QuotaReconciliationService} /
 * {@code QuotaReconciliationJob} 尚不存在）—— 自然 RED 是编译错误，不是断言失败；判别力因此由**变异体**
 * 提供（见交付报告），与 Task 11 的登记形态一致。
 */
class QuotaReconciliationTest extends AbstractIntegrationTest {

    private static final String MISMATCH_METRIC = "aihub.quota.reconcile.mismatch";
    private static final String PERIOD = "202609";
    private static final LocalDate STAT_DATE = LocalDate.of(2026, 9, 26);

    private static final long T_RECOMPUTE = 903_001L;
    private static final long T_RECOMPUTE2 = 903_002L;
    private static final long T_IDEMPOTENT = 903_003L;
    private static final long T_DEVIATION = 903_004L;
    private static final long T_MATCHING = 903_005L;
    private static final long T_ZERO = 903_006L;
    private static final long T_JOB = 903_007L;
    private static final long T_EXACT = 903_008L;

    private static final long[] FIXTURE_TENANTS = {
            T_RECOMPUTE, T_RECOMPUTE2, T_IDEMPOTENT, T_DEVIATION, T_MATCHING, T_ZERO, T_JOB, T_EXACT,
    };

    /** request_log 的 request_id 必须逐行唯一（唯一键 (request_id, created_at)）。 */
    private static final AtomicLong REQUEST_SEQ = new AtomicLong();

    @Autowired
    private QuotaReconciliationService service;

    @Autowired
    private BillingDailyMapper billingDailyMapper;

    @Autowired
    private QuotaMapper quotaMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanBefore() {
        cleanFixtures();
    }

    @AfterEach
    void cleanAfter() {
        cleanFixtures();
    }

    // ------------------------------------------------------------------ 1) 重算

    @Test
    void recomputesBillingDailyForTheStatDateFromRequestLog() {
        insertRequestLog(T_RECOMPUTE, LocalDateTime.of(2026, 9, 26, 10, 0), 30);
        insertRequestLog(T_RECOMPUTE2, LocalDateTime.of(2026, 9, 26, 11, 0), 300);

        ReconciliationReport report = service.reconcile(STAT_DATE);

        assertThat(billingDailyFor(T_RECOMPUTE, STAT_DATE).getTokens()).isEqualTo(30L);
        assertThat(billingDailyFor(T_RECOMPUTE, STAT_DATE).getRequests()).isEqualTo(1L);
        assertThat(billingDailyFor(T_RECOMPUTE2, STAT_DATE).getTokens()).isEqualTo(300L);
        assertThat(billingDailyFor(T_RECOMPUTE2, STAT_DATE).getRequests()).isEqualTo(1L);
        assertThat(billingDailyFor(T_RECOMPUTE, STAT_DATE).getCost())
                .as("本里程碑没有单价表：cost 固定写 0")
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(report.tokens()).as("report.tokens 是**这一天**的重算值（跨全部租户）").isEqualTo(330L);
        assertThat(report.requests()).isEqualTo(2L);
        assertThat(report.mismatches()).as("本用例没有任何受限配额行 ⇒ 候选集为空 ⇒ 无偏差").isEmpty();
    }

    @Test
    void runningTwiceIsIdempotentBecauseOfTheUniqueKey() {
        insertRequestLog(T_IDEMPOTENT, LocalDateTime.of(2026, 9, 26, 12, 0), 42);

        service.reconcile(STAT_DATE);
        BillingDailyEntity first = billingDailyFor(T_IDEMPOTENT, STAT_DATE);

        service.reconcile(STAT_DATE);
        List<BillingDailyEntity> rows = billingDailyRows(T_IDEMPOTENT, STAT_DATE);

        assertThat(rows).as("幂等：不许新增行（uk_billing_daily 是幂等的锚点）").hasSize(1);
        assertThat(rows.get(0).getTokens()).isEqualTo(first.getTokens()).isEqualTo(42L);
        assertThat(rows.get(0).getRequests()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ 2/3) 偏差判据 + 只报告不改账

    @Test
    void aDeviationBeyondTheToleranceIsCountedAndAuditedButDoesNotChangeTheQuota() {
        insertQuota(T_DEVIATION, PERIOD, 1_000L, 7L);
        setBucketTokens(T_DEVIATION, 5_000L);
        insertRequestLog(T_DEVIATION, LocalDateTime.of(2026, 9, 26, 13, 0), 100);

        double mismatchBefore = meterRegistry.counter(MISMATCH_METRIC).count();
        long auditBefore = reconcileAuditRows();

        ReconciliationReport report = service.reconcile(STAT_DATE);

        // 周期至今重算 = 100，桶 = 5000 ⇒ ratio = |100−5000|/100 = 49 > 0.01 ⇒ 报。
        assertThat(report.mismatches()).containsKey(T_DEVIATION);
        assertThat(meterRegistry.counter(MISMATCH_METRIC).count() - mismatchBefore)
                .as("每个命中偏差的租户 +1").isEqualTo(1.0);
        assertThat(reconcileAuditRows() - auditBefore).as("命中偏差必须留一条审计行").isEqualTo(1L);
        assertThat(quotaTokenUsed(T_DEVIATION)).as("D12：对账只检测、绝不改账").isEqualTo(7L);
    }

    @Test
    void aDeviationExactlyAtTheToleranceIsNotCounted() {
        insertQuota(T_EXACT, PERIOD, 1_000L, 0L);
        setBucketTokens(T_EXACT, 101L);
        insertRequestLog(T_EXACT, LocalDateTime.of(2026, 9, 26, 15, 0), 100);

        double mismatchBefore = meterRegistry.counter(MISMATCH_METRIC).count();
        ReconciliationReport report = service.reconcile(STAT_DATE);

        // 周期至今重算 = 100，桶 = 101 ⇒ ratio = 1/100 = 0.01，**等于** tolerance（0.01）⇒ 不算。
        assertThat(report.mismatches()).as("ratio > tolerance 才算：等于不算").doesNotContainKey(T_EXACT);
        assertThat(meterRegistry.counter(MISMATCH_METRIC).count()).isEqualTo(mismatchBefore);
    }

    // ------------------------------------------------------------------ 4) 比较口径（裁定 1 的唯一有判别力形式）

    @Test
    void aMatchingPeriodToDateIsNotReportedEvenWhenTheDayItselfDiffers() {
        insertQuota(T_MATCHING, PERIOD, 1_000L, 0L);
        // 周期内的历史日（09-10）已有 60 的账单行；09-26 当天重算 40 ⇒ 周期至今 = 100 = 桶。
        insertBillingDaily(T_MATCHING, LocalDate.of(2026, 9, 10), 60L);
        insertRequestLog(T_MATCHING, LocalDateTime.of(2026, 9, 26, 14, 0), 40);
        setBucketTokens(T_MATCHING, 100L);

        double mismatchBefore = meterRegistry.counter(MISMATCH_METRIC).count();
        ReconciliationReport report = service.reconcile(STAT_DATE);

        assertThat(report.mismatches())
                .as("当天 40 ≠ 桶 100，但周期至今 100 = 桶 ⇒ **不许报**（否则每月除最后一天外全误报）")
                .doesNotContainKey(T_MATCHING);
        assertThat(meterRegistry.counter(MISMATCH_METRIC).count()).isEqualTo(mismatchBefore);
    }

    // ------------------------------------------------------------------ 5) 除零：重算值 0 而桶值 > 0 仍必须报

    @Test
    void aZeroRecomputationWithAPositiveBucketIsStillReported() {
        insertQuota(T_ZERO, PERIOD, 1_000L, 0L);
        setBucketTokens(T_ZERO, 50L);
        // 本 period 内**没有**该租户的任何 request_log ⇒ 周期至今重算 = 0。

        ReconciliationReport report = service.reconcile(STAT_DATE);

        assertThat(report.mismatches())
                .as("「预扣了但没落账」是最严重的一类，绝不因除零被静默跳过")
                .containsKey(T_ZERO);
        assertThat(report.mismatches().get(T_ZERO)).as("|0−50| / max(0,1) = 50").isEqualTo(50.0);
    }

    // ------------------------------------------------------------------ 调度与 UTC 折算

    @Test
    void theScheduledReconcileIsPinnedToUtcAndOffsetsThePartitionMaintenance() throws Exception {
        Scheduled scheduled = QuotaReconciliationJob.class
                .getMethod("reconcileYesterday")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.zone())
                .as("必须是显式 UTC（默认 JVM 时区会让本机 UTC+8 上的 02:00 漂到 UTC 18:00）")
                .isEqualTo("UTC");
        assertThat(scheduled.cron())
                .as("默认 02:00，与 M3 分区维护的 03:10 错开")
                .isEqualTo("${aihub.quota.reconcile-cron:0 0 2 * * *}");
    }

    @Test
    void theJobReconcilesYesterdayInUtcDerivedFromTheClock() {
        insertRequestLog(T_JOB, LocalDateTime.of(2026, 9, 26, 9, 0), 15);

        // 固定瞬时 2026-09-27T03:00:00Z ⇒ 昨天（UTC）= 2026-09-26。用 JVM 默认时区 / LocalDate.now() 折算都会错。
        QuotaReconciliationJob job = new QuotaReconciliationJob(service,
                Clock.fixed(Instant.parse("2026-09-27T03:00:00Z"), ZoneOffset.UTC));
        job.reconcileYesterday();

        assertThat(billingDailyFor(T_JOB, LocalDate.of(2026, 9, 26)))
                .as("「昨天」必须由注入的 Clock 按 UTC 折算出来").isNotNull();
        assertThat(billingDailyFor(T_JOB, LocalDate.of(2026, 9, 26)).getTokens()).isEqualTo(15L);
    }

    // ------------------------------------------------------------------ 脚手架

    /** 定向查（本用例的 tenant + 日），不是全表计数。 */
    private BillingDailyEntity billingDailyFor(long tenantId, LocalDate statDate) {
        return billingDailyMapper.selectOne(new LambdaQueryWrapper<BillingDailyEntity>()
                .eq(BillingDailyEntity::getTenantId, tenantId)
                .eq(BillingDailyEntity::getStatDate, statDate));
    }

    private List<BillingDailyEntity> billingDailyRows(long tenantId, LocalDate statDate) {
        return billingDailyMapper.selectList(new LambdaQueryWrapper<BillingDailyEntity>()
                .eq(BillingDailyEntity::getTenantId, tenantId)
                .eq(BillingDailyEntity::getStatDate, statDate));
    }

    private long quotaTokenUsed(long tenantId) {
        QuotaEntity row = quotaMapper.selectOne(new LambdaQueryWrapper<QuotaEntity>()
                .eq(QuotaEntity::getTenantId, tenantId)
                .eq(QuotaEntity::getPeriod, PERIOD));
        return row == null ? -1L : row.getTokenUsed();
    }

    private long reconcileAuditRows() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'RECONCILE_REPORT' and target_id = ?",
                Long.class, PERIOD);
        return count == null ? 0L : count;
    }

    /** request_log 的列清单照抄 {@code RequestLogPartitionMaintainerTest:118}。 */
    private void insertRequestLog(long tenantId, LocalDateTime utcCreatedAt, int totalTokens) {
        jdbcTemplate.update(
                "insert into request_log (request_id, tenant_id, model, prompt_tokens, completion_tokens,"
                        + " total_tokens, latency_ms, status, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "req-m4t15-" + REQUEST_SEQ.incrementAndGet(), tenantId, "deepseek-chat",
                totalTokens / 2, totalTokens - totalTokens / 2, totalTokens, 100, "SUCCESS",
                java.sql.Timestamp.valueOf(utcCreatedAt));
    }

    private void insertBillingDaily(long tenantId, LocalDate statDate, long tokens) {
        jdbcTemplate.update("insert into billing_daily (tenant_id, stat_date, requests, tokens, cost) "
                + "values (?, ?, 1, ?, 0)", tenantId, java.sql.Date.valueOf(statDate), tokens);
    }

    private void insertQuota(long tenantId, String period, long tokenLimit, long tokenUsed) {
        jdbcTemplate.update("insert into quota (tenant_id, period, token_limit, token_used, request_limit, "
                + "request_used, version) values (?, ?, ?, ?, 0, 0, 0)", tenantId, period, tokenLimit, tokenUsed);
    }

    private void setBucketTokens(long tenantId, long tokens) {
        redis.opsForHash().put(QuotaKeys.bucketKey(tenantId, PERIOD), QuotaKeys.FIELD_TOKENS, Long.toString(tokens));
    }

    /** 只清本类的租户（绝不 FLUSHALL / 全前缀删）：容器与各表都是 JVM 级共享的。 */
    private void cleanFixtures() {
        for (long tenant : FIXTURE_TENANTS) {
            jdbcTemplate.update("delete from billing_daily where tenant_id = ?", tenant);
            jdbcTemplate.update("delete from request_log where tenant_id = ?", tenant);
            jdbcTemplate.update("delete from quota where tenant_id = ?", tenant);
            Set<String> keys = redis.keys(QuotaKeys.KEY_PREFIX + tenant + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        jdbcTemplate.update("delete from audit_log where action = 'RECONCILE_REPORT' and target_id = ?", PERIOD);
    }
}
