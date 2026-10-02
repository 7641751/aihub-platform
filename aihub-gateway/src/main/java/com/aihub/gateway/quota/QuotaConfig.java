package com.aihub.gateway.quota;

import com.aihub.gateway.config.ConfigClient;
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
}
