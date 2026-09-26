package com.aihub.gateway.config;

import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 配置层的装配点。**注意调用者可能没有配置可读**：所有 bean 都必须能在
 * 「主密钥为空 / Redis 不可用 / admin 不可达」的情况下正常创建（配置缺失不允许拦住网关启动）。
 *
 * <p>构造这些 bean **不发起任何 I/O**：Redis 连接是惰性的，admin 回源只发生在
 * {@code ConfigClient.current()} 第一次 miss 时。因此「控制面全挂」不会让网关起不来
 * —— 这正是决策 6 要保住的启动路径。
 *
 * <p>{@code MeterRegistry} 走**注入**而不是 {@code Metrics.globalRegistry} 这类静态全局注册表：
 * 注册表由 Spring Boot 的 actuator 自动配置提供，切片测试与生产拿到的是同一个东西，
 * 而全局静态注册表在测试之间会互相污染（见 {@code MeteringConfig} 的同一套纪律）。
 */
@Configuration
@EnableConfigurationProperties(GatewayConfigProperties.class)
public class ConfigConfig {

    @Bean
    public ConfigCache configCache(StringRedisTemplate redis, GatewayConfigProperties properties) {
        return new ConfigCache(redis, properties);
    }

    @Bean
    public ConfigClient configClient(ConfigCache cache, AdminClient adminClient, UpstreamProperties upstream,
                                     GatewayConfigProperties properties, MeterRegistry registry) {
        return new ConfigClient(cache, adminClient, upstream, properties, registry);
    }
}
