package com.aihub.gateway.quota;

import com.aihub.common.meter.MeteringEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 消费请求的预扣关联并在后台校正。实际用量取自 {@link MeteringEvent#totalTokens()}，
 * 租户、周期和估算量取自 {@link QuotaReservationRegistry}。
 *
 * <p>调度失败和 Redis 写入失败计入 {@link #FAILED_METRIC}。应用按请求最多提交一次校正。
 */
public class QuotaCorrector {

    private static final Logger log = LoggerFactory.getLogger(QuotaCorrector.class);

    /** 收尾时找不到预扣关联的次数。 */
    public static final String MISSING_METRIC = "aihub.quota.correction_missing";

    /** 调度失败及 Redis 校正失败的总次数。 */
    public static final String FAILED_METRIC = "aihub.quota.correction_failed";

    /** 执行器拒绝接收任务的次数，已计入总失败指标。 */
    public static final String REJECTED_METRIC = "aihub.quota.correction_rejected";

    private final QuotaReservationRegistry reservations;
    private final QuotaLimiter limiter;
    private final QuotaConfigProperties properties;
    private final MeterRegistry registry;
    private final Executor correctionExecutor;

    public QuotaCorrector(QuotaReservationRegistry reservations, QuotaLimiter limiter,
                          QuotaConfigProperties properties, MeterRegistry registry,
                          Executor correctionExecutor) {
        this.reservations = reservations;
        this.limiter = limiter;
        this.properties = properties;
        this.registry = registry;
        this.correctionExecutor = correctionExecutor;
    }

    /** 消费预扣关联并提交一次后台校正。 */
    public void correct(MeteringEvent event) {
        if (!properties.enabled()) {
            return;
        }
        String requestId = event.requestId();
        var reservation = reservations.consume(requestId);
        if (reservation == null) {
            count(MISSING_METRIC);
            log.debug("配额校正缺少预扣关联 requestId={}", requestId);
            return;
        }
        if (reservation.degraded()) {
            return;
        }

        long actualTokens = event.totalTokens();
        try {
            correctionExecutor.execute(() -> adjust(requestId, reservation, actualTokens));
        } catch (RuntimeException e) {
            count(FAILED_METRIC);
            if (e instanceof RejectedExecutionException) {
                count(REJECTED_METRIC);
            } else {
                log.warn("配额校正调度失败 requestId={}: {}", requestId, e.toString());
            }
        }
    }

    private void adjust(String requestId, QuotaReservationRegistry.QuotaReservation reservation,
                        long actualTokens) {
        try {
            limiter.adjust(reservation.tenantId(), reservation.period(),
                    reservation.estimatedTokens(), actualTokens);
        } catch (RuntimeException e) {
            count(FAILED_METRIC);
            log.warn("配额校正写入失败 requestId={}: {}", requestId, e.toString());
        }
    }

    private void count(String metric) {
        try {
            registry.counter(metric).increment();
        } catch (RuntimeException e) {
            log.warn("配额校正指标记录失败: {}", e.toString());
        }
    }
}
