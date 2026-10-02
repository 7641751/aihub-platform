package com.aihub.gateway.quota;

import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.config.ConfigClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 配额链的装配点（与 {@code RateLimiterConfig} 同一纪律）：**没有这个类，整条配额链在生产里就是
 * 不可达的** —— {@link QuotaResolver} / {@link RedisQuotaLimiter} / {@link QuotaReservationRegistry}
 * 都只是普通类（或接口实现），必须在这里显式声明为 bean。
 *
 * <p>与 {@code RateLimiterConfig} 一样：
 * <ul>
 *   <li><b>构造 bean 不发起任何 I/O</b>（Redis 连接惰性、快照在第一次判定时读）；</li>
 *   <li><b>额度每次惰性读快照</b>（{@link QuotaResolver} 收 {@code ConfigClient::current}），
 *       控制面改了额度下一个请求就生效；</li>
 *   <li>{@code aihub.quota.enabled} **不在这里**判断：关掉是「过滤器直接放行」，
 *       而不是「链不存在」（同 {@code RateLimiterConfig} 的热切换理由）。</li>
 *   <li><b>兜底（{@link QuotaFallback}）只影响「Redis 不可用时怎么办」</b>，因此这里是它唯一的开关点：
 *       {@code aihub.quota.fallback-enabled=false} 时装配 {@link QuotaFallback#disabled()}，
 *       且**不新增任何 HTTP 客户端**（{@link AdminClient} 本来就是既有 bean）。</li>
 * </ul>
 *
 * <p>{@code @ConditionalOnMissingBean} 刻意**不加**：本里程碑没有第二实现；若要给测试覆写，
 * 用 {@code @Primary} 替身（本项目既有手法）。
 */
@Configuration
@EnableConfigurationProperties(QuotaConfigProperties.class)
public class QuotaConfig {

    /** 额度来源 = 配置快照（{@code ConfigClient::current} 这**一个方法引用**，每次惰性取）。 */
    @Bean
    public QuotaResolver quotaResolver(ConfigClient configClient) {
        return new QuotaResolver(configClient::current);
    }

    /** 预扣 / 校正的 Redis 实现。返回类型用接口，便于测试以 {@code @Primary} 替身覆写。 */
    @Bean
    public QuotaLimiter quotaLimiter(StringRedisTemplate redis) {
        return new RedisQuotaLimiter(redis);
    }

    /** {@code requestId → 预扣关联} 的有界存储（过滤器写入、校正器消费）。 */
    @Bean
    public QuotaReservationRegistry quotaReservationRegistry(QuotaConfigProperties properties) {
        return new QuotaReservationRegistry(properties);
    }

    /** 收尾侧的校正钩子（{@code ChatRelayController} 的终端发布点调用它）。 */
    @Bean
    public QuotaCorrector quotaCorrector(QuotaReservationRegistry reservations, QuotaLimiter limiter,
                                         QuotaConfigProperties properties, MeterRegistry registry) {
        return new QuotaCorrector(reservations, limiter, properties, registry);
    }

    /**
     * Redis 不可用时回源 admin 预扣的兜底（{@link QuotaFallback}）。
     *
     * <p>{@code aihub.quota.fallback-enabled} **默认开启**：与限流的「降级仍拒绝」不同，配额是**记账** ——
     * Redis 挂了必须放行（D7），而兜底是「放行之外还能在 Redis 故障期间**如实拒绝**超预算的租户」的唯一手段
     * （它绝不改变拒绝语义）。关掉它 = 回到 Task 13 的纯 fail-open（{@link QuotaFallback#disabled()}），
     * 每次判定都不再有任何内部往返。
     *
     * <p>这里只做「开/关」的装配：真正的 HTTP + HMAC 在 {@link AdminClient#reserveQuota} 里，
     * 而 {@link QuotaFilter} 只认窄接口。
     */
    @Bean
    public QuotaFallback quotaFallback(AdminClient adminClient,
                                       @Value("${aihub.quota.fallback-enabled:true}") boolean fallbackEnabled) {
        return fallbackEnabled
                ? QuotaFallback.admin(adminClient, QuotaFallback.DEFAULT_TIMEOUT_MILLIS)
                : QuotaFallback.disabled();
    }
}
