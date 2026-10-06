package com.aihub.gateway.config;

import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.relay.ChannelKeyDecryptor;
import com.aihub.gateway.upstream.UpstreamProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

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

    /**
     * 配置读取入口。
     *
     * <p><b>「本地命中时要不要探一次 Redis 版本」由这里的装配结论决定</b>（2026-10-06）：
     * 主动失效通道**真的接上**（{@code aihub.config.invalidate-subscription=true} —— 此时
     * {@link ConfigInvalidateProperties} 才是 bean，见 {@code ConfigInvalidateSubscriptionConfig} 的
     * 条件与 {@code ConfigSubscriberTest} 钉住的"开关为真时容器 bean 才存在"）时，逐请求的版本探测是
     * **多余**的（收敛已由广播承担）；通道没接上时必须保留它（那是 M3 形态下唯一的跨实例收敛手段）。
     *
     * <p>用 {@link ObjectProvider#getIfAvailable()} 而不是读一遍属性值：这样做绑定的是
     * **"通道到底有没有被装配"**这个事实，而不是"某个键写成了什么"—— 一个 typo 改不了 bean 的装配结果。
     */
    @Bean
    public ConfigClient configClient(ConfigCache cache, AdminClient adminClient, UpstreamProperties upstream,
                                     GatewayConfigProperties properties, MeterRegistry registry,
                                     ObjectProvider<ConfigInvalidateProperties> invalidateProperties) {
        boolean invalidationWired = invalidateProperties.getIfAvailable() != null;
        return new ConfigClient(cache, adminClient, upstream, properties, registry,
                Clock.systemUTC(), !invalidationWired);
    }

    /**
     * 渠道密钥的解密器（**主密钥只在网关本地**，设计文档 §6.1）。
     * 主密钥为空时 {@link com.aihub.common.crypto.ChannelKeyRegistry} 是空表，
     * 所有真实渠道都会「解不开」并被路由跳过 —— 这是**可启动**的降级，不是启动失败
     * （与 admin 侧的「无主密钥拒绝加密」相反，理由见决策 3）。
     */
    @Bean
    public AesGcmChannelCipher channelCipher(
            @Value("${aihub.channel.master-key:}") String masterKey) {
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey));
    }

    @Bean
    public ChannelKeyDecryptor channelKeyDecryptor(AesGcmChannelCipher cipher, UpstreamProperties upstream) {
        return new ChannelKeyDecryptor(cipher, upstream);
    }
}
