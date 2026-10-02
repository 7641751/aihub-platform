package com.aihub.gateway.quota;

import com.aihub.common.meter.MeteringEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 收尾侧的配额校正钩子：{@code ChatRelayController} 在**每个终端发布点**、{@code meteringPublisher.publish(event)}
 * **之后**调用 {@link #correct(MeteringEvent)}。
 *
 * <p><b>为什么用事件而不是「控制器拿到 usage 后校正」</b>：控制器自己**看不到** {@code usage}
 * —— {@code UsageCapture} 是 {@code RelayMetering} 的内部物，usage 只在 {@code metering.toEvent(signal)}
 * 被物化，而那一句在 {@code doFinally} 里。因此校正点**只能**是「事件已经组装出来之后」。
 *
 * <p><b>真实用量取自 {@link MeteringEvent#totalTokens()}</b>（实测分量名），不是
 * {@code promptTokens()+completionTokens()} 自己加 —— 上游给什么就用什么，两个口径必须同源。
 *
 * <p><b>estimate 从哪来（13b 裁定 2）</b>：{@link MeteringEvent} 里**没有**预扣时的估算值，
 * 因此靠 {@link QuotaReservationRegistry} 建立的 {@code requestId → (tenantId, period, estimate)}
 * 关联（过滤器写入、这里**消费并移除**）。
 *
 * <p><b>三条约束</b>：
 * <ol>
 *   <li><b>不参与响应链路</b>：校正发生在响应回写之后，任何失败只记日志 + 计数（
 *       {@link #FAILED_METRIC}），绝不抛到请求路径上；02:00 的对账是兜底。</li>
 *   <li><b>绝不改写已提交的响应</b>（本类不碰 exchange / response）。</li>
 *   <li><b>{@code adjust} 非幂等 ⇒ 每请求只调一次</b>：条目「消费即移除」保证了这一点；
 *       缺失条目（过期 / 从未写入 / 已被消费）⇒ 只计数（{@link #MISSING_METRIC}）+ 跳过，绝不回调。</li>
 * </ol>
 */
public class QuotaCorrector {

    private static final Logger log = LoggerFactory.getLogger(QuotaCorrector.class);

    /** 终端收尾时找不到预扣关联的次数（过期 / 从未写入 / 已被消费）——**异常信号**，不是正常路径。 */
    public static final String MISSING_METRIC = "aihub.quota.correction_missing";

    /** 校正动作本身失败的次数（Redis 写失败等）。对账是兜底，因此只计数不拒绝。 */
    public static final String FAILED_METRIC = "aihub.quota.correction_failed";

    private final QuotaReservationRegistry reservations;
    private final QuotaLimiter limiter;
    private final QuotaConfigProperties properties;
    private final MeterRegistry registry;

    public QuotaCorrector(QuotaReservationRegistry reservations, QuotaLimiter limiter,
                          QuotaConfigProperties properties, MeterRegistry registry) {
        this.reservations = reservations;
        this.limiter = limiter;
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * 用真实用量校正本次请求的预扣。**幂等性由调用方保证**（每请求恰一次）；本方法自身
     * 对重复调用**不安全**（{@code adjust} 非幂等），但它消费即移除，因此第二次调用只会看到「缺失」。
     */
    public void correct(MeteringEvent event) {
        if (!properties.enabled()) {
            // 配额关闭时过滤器从不写关联条目：若不在此短路，每个请求都会把 MISSING 顶到失真。
            return;
        }
        QuotaReservationRegistry.QuotaReservation reservation = reservations.consume(event.requestId());
        if (reservation == null) {
            count(MISSING_METRIC);
            log.debug("配额校正：找不到 requestId={} 的预扣关联（过期 / 从未预扣 / 已被消费），跳过",
                    event.requestId());
            return;
        }
        if (reservation.degraded()) {
            // 本条请求**没有发生预扣**（不限 / Redis 降级 / 形状异常 / 自身故障）：校正会把从未
            // 压掉的额度补进桶里，因此跳过。条目已被消费掉，重复调用不会二次动作。
            return;
        }
        try {
            limiter.adjust(reservation.tenantId(), reservation.period(),
                    reservation.estimatedTokens(), event.totalTokens());
        } catch (RuntimeException e) {
            count(FAILED_METRIC);
            log.warn("配额校正失败（响应已回写，只记日志；02:00 的对账是兜底）requestId={}: {}",
                    event.requestId(), e.toString());
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
