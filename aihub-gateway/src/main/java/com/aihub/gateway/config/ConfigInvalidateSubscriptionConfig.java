package com.aihub.gateway.config;

import com.aihub.common.config.ConfigInvalidateTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 把 {@link ConfigSubscriber} 接到 Redis Pub/Sub 上的装配点（D4）。
 *
 * <p><b>装配的所有权在这里，不在 {@code ConfigConfig}</b>：订阅是一个可以整体关掉的旁路
 * （{@code aihub.config.invalidate-subscription=false}），而 {@code ConfigConfig} 里那些 bean
 * 是数据面的必需品；把「能关掉的东西」塞进「必需的装配类」会让关闭开关变成到处是条件注解。
 *
 * <p><b>{@code @EnableConfigurationProperties} 是必需的</b>：{@link ConfigInvalidateProperties}
 * 是一个 {@code @ConfigurationProperties} record，不显式启用它就不是 bean，下面那个
 * {@code @Bean} 方法的参数注入不到、上下文起不来。
 *
 * <p>{@code @ConditionalOnProperty} 默认 {@code matchIfMissing = true}：生产（{@code application.yml}
 * 里是 {@code ${AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION:true}}）默认**订阅**。网关的测试环境把它显式
 * 设成 {@code false} —— 那里没有活 Redis，{@code RedisMessageListenerContainer} 会在后台按退避
 * 策略**无限重连**并污染日志；订阅逻辑本身由 {@code ConfigSubscriberTest} 直接驱动
 * （{@code onMessage}），端到端由 Task 17 的 compose 验收覆盖。
 */
@Configuration
@EnableConfigurationProperties(ConfigInvalidateProperties.class)
@ConditionalOnProperty(name = "aihub.config.invalidate-subscription", havingValue = "true",
        matchIfMissing = true)
public class ConfigInvalidateSubscriptionConfig {

    private static final Logger log = LoggerFactory.getLogger(ConfigInvalidateSubscriptionConfig.class);

    /** 频道名来自共享常量（admin 发布方用同一个）：改名只可能在这里被发现。 */
    @Bean
    public RedisMessageListenerContainer configInvalidateListenerContainer(
            RedisConnectionFactory connectionFactory, ConfigSubscriber subscriber,
            ConfigInvalidateProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(ConfigInvalidateTopology.CHANNEL));
        // 开关本身由类上的 @ConditionalOnProperty 判定（配置项存在即启用）；这里把生效值打进启动
        // 日志，运维因此能一眼看出「这次启动到底订没订」——Task 17 的反证就是把它设成 false 再验一次。
        log.info("配置失效订阅已启用：channel={}, aihub.config.invalidate-subscription={}",
                ConfigInvalidateTopology.CHANNEL, properties.invalidateSubscription());
        return container;
    }
}
