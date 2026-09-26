package com.aihub.gateway.ratelimit;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.gateway.config.ConfigClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 限流链的装配点（G1/G8）。**没有这个类，整条限流链在生产里就是不可达的** ——
 * {@code LocalRateLimiter} / {@code RedisRateLimiter} / {@code RateLimitResolver} / {@code RateLimiter}
 * 都只是普通类，没有任何 {@code @Component}，因此必须在这里显式声明为 bean。
 *
 * <p>与 {@link com.aihub.gateway.config.ConfigConfig} 同一套纪律：
 * <ul>
 *   <li><b>构造 bean 不发起任何 I/O</b>。Redis 连接是惰性的，配置快照只在第一次判定时读；
 *       因此「Redis 挂 + admin 不可达」不会让网关起不来（决策 6）。</li>
 *   <li><b>策略每次惰性读快照</b>（{@link RateLimitResolver} 收的是一个 {@code Supplier}）：
 *       控制面改了额度，下一个请求就生效，不需要重启也不需要清缓存。</li>
 *   <li>不依赖 {@code Metrics.globalRegistry}：指标注册表由过滤器注入，见
 *       {@code RateLimitFilter} 的构造器。</li>
 * </ul>
 *
 * <p>开关 {@code aihub.ratelimit.enabled} **不在这里**判断：关掉限流是「过滤器不执行判定」，
 * 而不是「链不存在」。这样运维可以热切换开关而不用改 bean 图，并且端到端测试能断言
 * 「链在、但被开关挡掉」与「链不在」是两种不同的状态。
 */
@Configuration
public class RateLimiterConfig {

    /**
     * 本机令牌桶的容量上界。默认 100 000 个桶：单机降级期间常见租户/密钥规模远小于它，
     * 而有了上界，一个用无数 key 打过来的客户端也没法把网关内存撑爆（降级不能变成攻击面）。
     */
    @Bean
    public LocalRateLimiter localRateLimiter(
            @Value("${aihub.ratelimit.max-local-buckets:100000}") int maxBuckets) {
        return new LocalRateLimiter(maxBuckets, System::currentTimeMillis);
    }

    @Bean
    public RedisRateLimiter redisRateLimiter(StringRedisTemplate redis) {
        return new RedisRateLimiter(redis);
    }

    /**
     * {@code (tenantId, apiKeyId) → RatePolicy}。传的是 {@code ConfigClient::current} 这**一个方法引用**
     * 而不是某一时刻的快照：快照是每请求惰性取的，策略改动因此立刻生效。
     */
    @Bean
    public RateLimitResolver rateLimitResolver(ConfigClient configClient) {
        return new RateLimitResolver(configClient::current);
    }

    /** 限流的唯一入口（也是「Redis 挂了怎么办」的唯一落点）。 */
    @Bean
    public RateLimiter rateLimiter(RedisRateLimiter redis, LocalRateLimiter local,
                                   RateLimitResolver resolver) {
        return new RateLimiter(redis, local, resolver);
    }
}
