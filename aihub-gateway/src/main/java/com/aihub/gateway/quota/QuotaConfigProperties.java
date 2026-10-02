package com.aihub.gateway.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code aihub.quota.*}。所有项都带默认值：任何一个键没配都不该让网关起不来 ——
 * 配额是**记账**，它的配置缺失绝不能影响数据面可用性（与 {@code MeteringProperties} 同一纪律）。
 *
 * <p><b>{@code enabled} 默认 {@code true}（生产）</b>：配额是保护付费额度的手段，不该靠部署时记得打开。
 * 但 {@code src/test/resources/application.properties} 把它默认关掉（与 {@code aihub.metering.enabled} /
 * {@code aihub.ratelimit.enabled} 同一条纪律）：绝大多数网关测试只关心转发与鉴权，不希望每个请求
 * 都先撞一次「Redis 指向不存在端口」的超时。需要配额的测试用 {@code properties} /
 * {@code @DynamicPropertySource} 显式打开。
 *
 * <p><b>{@code maxInMemoryBytes} 必须严格小于 {@code aihub.metering.max-capture-bytes}（默认 1048576）</b>：
 * 配额这一层为了估算要**整体 join** 请求体（峰值内存 = 整个 body），而计量只捕获响应体的**尾部滑窗**
 * （{@code TailBuffer}，上限是 {@code maxCaptureBytes}）。两者若相等或配额更大，配额这一层的峰值内存
 * 就会超过计量预算，且「配额层先失败 ⇒ 计量根本看不到这次请求」这条关系会被翻转。
 * 取 {@code 262144}（256 KiB）而不是贴着 1048576：chat/completions 的请求体在现实中是 KB 级，
 * 256 KiB 已远超正常值，同时给「配额层」留出明确的、比计量层更紧的内存上界。
 *
 * @param enabled        配额总开关；关掉后 {@link QuotaFilter} 直接放行，不读体、不判定
 * @param maxInMemoryBytes 单请求请求体的**整体**读入上限（见上）；超过它 join 会报错
 * @param reservationTtl {@link QuotaReservationRegistry} 条目的存活时间（短 TTL：一次请求的生命周期量级）
 * @param maxReservations {@link QuotaReservationRegistry} 的内存条目上界（防止无界增长）
 */
@ConfigurationProperties(prefix = "aihub.quota")
public record QuotaConfigProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("262144") int maxInMemoryBytes,
        @DefaultValue("2m") Duration reservationTtl,
        @DefaultValue("100000") int maxReservations) {
}
