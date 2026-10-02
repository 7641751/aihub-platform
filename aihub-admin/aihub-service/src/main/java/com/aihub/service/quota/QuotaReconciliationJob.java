package com.aihub.service.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 每日 02:00（UTC）触发对账：把「昨天」交给 {@link QuotaReconciliationService#reconcile(LocalDate)}。
 *
 * <p><b>⚠️ cron 必须显式 {@code zone = "UTC"}</b>：{@code @Scheduled} 默认用 **JVM 默认时区**，
 * 本机是 UTC+8，于是字面上的「02:00」会漂到 UTC 18:00。既有 {@code RequestLogPartitionMaintainer}
 * 的 {@code @Scheduled(..., zone = "UTC")} 就是这条纪律的先例。默认时刻 02:00 与 M3 的分区维护
 * 03:10 **错开**，避免同时对表下手。
 *
 * <p><b>⚠️「昨天」按 UTC 折算，且时钟可注入</b>：用
 * {@code LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1)}，
 * <b>绝不</b> {@code LocalDate.now()}（那是 JVM 默认时区 —— CONVENTIONS §7 的原陷阱）。
 * 用例用固定 {@code Clock} 钉住「昨天」。
 *
 * <p><b>失败绝不终止后续调度</b>：{@code @Scheduled} 方法抛出会停掉这个任务后续的所有触发，
 * 因此这里吞掉 {@link RuntimeException} 并带 throwable 记 ERROR（下一轮重试）。
 */
@Component
public class QuotaReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(QuotaReconciliationJob.class);

    private final QuotaReconciliationService service;
    private final Clock clock;

    /** Spring 注入用的构造器：时钟默认 {@code Clock.systemUTC()}（生产路径不依赖 JVM 默认时区）。 */
    @Autowired
    public QuotaReconciliationJob(QuotaReconciliationService service) {
        this(service, Clock.systemUTC());
    }

    /** 可注入时钟的构造器：用例用它把「昨天」钉在固定瞬时上。 */
    public QuotaReconciliationJob(QuotaReconciliationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /**
     * 每天 02:00（UTC）对帐「昨天（UTC）」。
     *
     * <p>{@code zone = "UTC"} 是硬要求（见类注释）；cron 可由
     * {@code aihub.quota.reconcile-cron} 覆盖（默认 {@code 0 0 2 * * *}）。
     */
    @Scheduled(cron = "${aihub.quota.reconcile-cron:0 0 2 * * *}", zone = "UTC")
    public void reconcileYesterday() {
        try {
            LocalDate statDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1);
            service.reconcile(statDate);
        } catch (RuntimeException e) {
            // 定时任务抛出会终止后续调度，必须吞掉并等下一轮。带上 throwable（SQL state / error code 只在栈里）。
            log.error("每日对账失败（下一轮重试）", e);
        }
    }
}
