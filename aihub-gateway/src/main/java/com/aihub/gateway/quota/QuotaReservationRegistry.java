package com.aihub.gateway.quota;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

/**
 * {@code requestId → 本次请求预扣时的关联信息} 的<b>有界</b>存储。
 *
 * <p><b>为什么必须有它（13b 裁定 2）</b>：{@code QuotaCorrector.correct(MeteringEvent)} 手里只有一个
 * {@code MeteringEvent}，它的实测分量是
 * {@code requestId/tenantId/apiKeyId/channelId/model/promptTokens/completionTokens/totalTokens/latencyMs/status/errorCode/createdAtEpochMilli}
 * —— <b>没有</b>预扣时的估算值，也<b>没有</b> {@code period}。而 {@code QuotaReservation}（携带
 * {@code estimate} / {@code period}）是<b>过滤器</b>写进 exchange 属性的，收尾时（另一个对象、另一条
 * 回调）根本拿不到。⇒ 必须显式建立一条 {@code requestId → (tenantId, period, estimatedTokens, degraded)}
 * 的相关性。
 *
 * <p><b>必须有界</b>：用 Caffeine + 短 TTL（{@link QuotaConfigProperties#reservationTtl()}）+
 * {@code maximumSize}（{@link QuotaConfigProperties#maxReservations()}）。一个无界的 {@code Map}
 * 在这里就是一处内存泄漏（每个请求一行、永不回收）。
 *
 * <p><b>消费即移除</b>（{@link #consume(String)}）：定义是「取出并删除」。这条不是风格问题 ——
 * {@code adjust} <b>非幂等</b>，条目若留着，同一个 {@code requestId} 一旦被重复校正就会<b>双重扣账</b>。
 * 消费后条目消失 ⇒ 第二次校正看到「缺失」⇒ 只计数、不动作。
 *
 * <p>{@link #consume(String)} 返回 {@code null} 表示<b>缺失</b>（过期 / 从未写入 / 已被消费）：
 * 调用方必须<b>跳过校正 + 计数</b>，绝不抛异常到请求路径上。
 */
public class QuotaReservationRegistry {

    /**
     * 一次请求预扣时记下的关联信息。
     *
     * @param tenantId       配额维度（租户）
     * @param period         UTC 的 {@code YYYYMM}
     * @param estimatedTokens 预扣时压掉的估算 token（校正的基准）
     * @param degraded       {@code true} = 本次请求<b>没有发生预扣</b>（Redis 不可用按 D7 放行，或该租户
     *                       本周期不限），校正器据此<b>跳过</b> adjust（否则会把从未压掉的额度补进桶里）；
     *                       注意它与指标 {@code aihub.quota.degraded} <b>不是同一件事</b> —— 指标只在
     *                       「Redis 不可用」时 +1，而这里的 {@code degraded} 还涵盖「本周期不限」
     */
    public record QuotaReservation(long tenantId, String period, long estimatedTokens, boolean degraded) {
    }

    private final Cache<String, QuotaReservation> cache;

    public QuotaReservationRegistry(QuotaConfigProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /**
     * 测试专用接缝：TTL 的时间读数改由注入的 {@link Ticker} 提供（与 {@code ConfigCache} 同一手法），
     * 让「过期即缺失」这条变成<b>确定</b>的，而不是靠真实时钟去赌。
     */
    QuotaReservationRegistry(QuotaConfigProperties properties, Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, properties.maxReservations()))
                .expireAfterWrite(properties.reservationTtl())
                .ticker(ticker)
                .build();
    }

    /** 过滤器在放行前写入。 */
    public void write(String requestId, QuotaReservation reservation) {
        cache.put(requestId, reservation);
    }

    /** <b>取出并移除</b>（见类注释）。返回 {@code null} = 缺失。 */
    public QuotaReservation consume(String requestId) {
        return cache.asMap().remove(requestId);
    }

    /** 当前条目数（测试与指标用）。Caffeine 的 {@code estimatedSize} 是近似值。 */
    public long size() {
        return cache.estimatedSize();
    }
}
