package com.aihub.gateway.config;

import com.aihub.common.config.ConfigInvalidateCodec;
import com.aihub.common.config.ConfigInvalidateMessage;
import com.aihub.common.config.ConfigInvalidateTopology;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.LifecycleProcessor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 订阅侧的两种证据：**监听逻辑**（直接驱动 {@code onMessage}）与**装配**
 * （{@link ApplicationContextRunner} + 开关的真值表）。
 *
 * <p>刻意不起生产上下文、连任何真 Redis：网关的测试永远不允许依赖 Docker / 活 broker / 活 Redis
 * （全局约束）。生产上下文里 {@code RedisMessageListenerContainer} 会在后台无限重连一个不存在的
 * Redis 并污染日志，因此 {@code src/test/resources/application.properties} 把订阅显式关掉；
 * 端到端由 Task 17 的 compose 验收覆盖。
 */
class ConfigSubscriberTest {

    /**
     * 一条有效消息触发一次失效（且带着**解码出来的版本**——水位必须来自消息，不是本地已知的版本）；
     * 一条畸形消息既不清缓存也不抛异常。
     *
     * <p>「不抛」是硬要求：畸形载荷没有可抬水位的版本、也没有可据以行动的 reason，抛出去只会被
     * {@code RedisMessageListenerContainer} 自己的 {@code handleListenerException} 吞掉
     * （Pub/Sub 没有重投），白白赔上这条原因。
     *
     * <p>夹具把载荷放在 {@code body} 里（{@code MessageListener} 的约定：{@code body} = 载荷，
     * {@code pattern} = 匹配到的模式，频道订阅下为 {@code null}）。
     */
    @Test
    void aValidMessageInvalidatesAndAMalformedOneDoesNot() {
        ConfigClient client = mock(ConfigClient.class);
        ConfigSubscriber subscriber = new ConfigSubscriber(client);

        byte[] valid = ConfigInvalidateCodec.encode(new ConfigInvalidateMessage(9L, "channel.update"))
                .getBytes(UTF_8);
        subscriber.onMessage(new DefaultMessage(ConfigInvalidateTopology.CHANNEL.getBytes(UTF_8), valid), null);
        verify(client).invalidate(9L);

        subscriber.onMessage(new DefaultMessage(ConfigInvalidateTopology.CHANNEL.getBytes(UTF_8),
                "garbage".getBytes(UTF_8)), null);
        verifyNoMoreInteractions(client);                    // 坏消息不触发失效，也不抛

        // 空 body（畸形载荷的极端形式）：同样只 WARN、不抛、不失效 —— 无版本可抬、无 reason 可行动。
        Message withoutBody = mock(Message.class);
        when(withoutBody.getBody()).thenReturn(null);
        subscriber.onMessage(withoutBody, null);
        verifyNoMoreInteractions(client);
    }

    /**
     * 开关的真值表：{@code aihub.config.invalidate-subscription=true} 时容器 bean 必须存在，
     * {@code false} 时**必须不存在**（测试环境靠它避免连一个不存在的 Redis）。
     *
     * <p><b>每个 runner 都替换掉 {@code lifecycleProcessor}</b>，这不是装饰：{@code ApplicationContextRunner}
     * 会走完整的 {@code finishRefresh()}，而 {@code RedisMessageListenerContainer} 是一个
     * {@code SmartLifecycle} —— 它一被启动就要求**真的**把订阅注册上
     * （{@code lazyListen()} 里 {@code future.get(2000ms)}，超时抛
     * {@code IllegalStateException: Subscription registration timeout exceeded}）。
     * 一个 mock 的 {@code RedisConnectionFactory} 永远注册不上，于是「装配是否正确」这条断言会被
     * 「有没有活 Redis」污染 —— 正是全局约束禁止的那种测试。用 no-op 的 {@link LifecycleProcessor}
     * 把启动这一步去掉，本用例就只回答它真正要回答的问题：**条件注解有没有把 bean 装上**。
     * 容器能不能连上 Redis，是 Task 17 的 compose 验收要回答的问题。
     */
    @Test
    void theSubscriptionBeanIsWiredExactlyWhenTheFlagIsOn() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConfigInvalidateSubscriptionConfig.class)
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withBean(ConfigSubscriber.class, () -> mock(ConfigSubscriber.class))
                .withBean(LifecycleProcessor.class, () -> mock(LifecycleProcessor.class))
                .withPropertyValues("aihub.config.invalidate-subscription=true")
                .run(ctx -> assertThat(ctx).hasBean("configInvalidateListenerContainer"));

        new ApplicationContextRunner()
                .withUserConfiguration(ConfigInvalidateSubscriptionConfig.class)
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withBean(ConfigSubscriber.class, () -> mock(ConfigSubscriber.class))
                .withBean(LifecycleProcessor.class, () -> mock(LifecycleProcessor.class))
                .withPropertyValues("aihub.config.invalidate-subscription=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("configInvalidateListenerContainer"));
    }

    /**
     * <b>生产默认值必须真的是「开」</b>（与 {@code AihubGatewayApplicationTests} 给限流开关
     * {@code aihub.ratelimit.enabled} 写的那条纪律同源）：这个键在**测试里**被
     * {@code src/test/resources/application.properties} 钉成 {@code false}（理由见那个文件），
     * 于是「生产默认是什么」在整套测试里没有任何断言 —— 主配置里把键名写错（例如
     * {@code invalidate-subscrition}）会让生产静默退回 M3 的 TTL 收敛（最长 10 分钟不可见），
     * 而全量测试照样全绿。
     *
     * <p>为什么不能断言 {@code environment.getProperty(...)}：那是**合并后**的结果，测试副本的
     * {@code false} 优先级更高。所以这里读**出厂** {@code src/main/resources/application.yml}
     * 本身，并且断言那一行的**原文**：必须是
     * {@code ${AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION:true}} —— 环境变量的**名字**与**默认值**两者
     * 缺一不可。名字尤其重要：Task 17 的 compose 正是用 {@code AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION}
     * 做反证的，占位符写错就等于 compose 的那个开关永远不起作用。
     *
     * <p><b>原文只是「源码里写了什么」，所以下面再把出厂 yml 真的喂进一个
     * {@link ApplicationContextRunner}，断言解析后的**行为**</b>：不给环境变量时开关绑成
     * {@code true}（读 {@link ConfigInvalidateProperties} 这个 bean 本身，也就是**解析后的绑定**）
     * 且容器 bean 存在；{@code AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION=false} 时容器 bean 消失。
     * 「占位符没被条件注解解析」这类漂移在原文断言下依然全绿 —— 只有让
     * {@code @ConditionalOnProperty} 真去读一次才会现形（读的是解析后的值，值的来源是
     * 环境里的 {@code AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION}）。
     *
     * <p>反例那一半**不能**断言 {@code ConfigInvalidateProperties} 的绑定值：条件为假时整个
     * {@link ConfigInvalidateSubscriptionConfig} 都不生效，连带它上面的
     * {@code @EnableConfigurationProperties} 也不执行，那个 record 根本不是 bean
     * （强行断言只会得到 {@code NoSuchBeanDefinition}）。关掉时的可观测行为就是**容器不存在**。
     */
    @Test
    void theProductionDefaultOfTheInvalidateSubscriptionIsOn() throws IOException {
        var shipped = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(shipped).as("主配置必须能被读到").isNotEmpty();
        assertThat(String.valueOf(shipped.get(0).getProperty("aihub.config.invalidate-subscription")))
                .as("生产默认必须解析为 true，且覆盖用的环境变量名必须是 AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION；"
                        + "否则一个 typo 就能在全绿测试下静默关掉主动失效（退回最长 10 分钟的 TTL 收敛）")
                .isEqualTo("${AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION:true}");

        // 出厂 yml + 真条件判定：ApplicationContextRunner 与上面那条装配用例同一套夹具
        // （mock connectionFactory / subscriber + no-op lifecycleProcessor），差别只在配置来源。
        ApplicationContextRunner shippedYml = new ApplicationContextRunner()
                .withInitializer(context -> shipped
                        .forEach(source -> context.getEnvironment().getPropertySources().addLast(source)))
                .withUserConfiguration(ConfigInvalidateSubscriptionConfig.class)
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withBean(ConfigSubscriber.class, () -> mock(ConfigSubscriber.class))
                .withBean(LifecycleProcessor.class, () -> mock(LifecycleProcessor.class));

        shippedYml.run(ctx -> {
            assertThat(ctx).hasBean("configInvalidateListenerContainer");
            assertThat(ctx.getBean(ConfigInvalidateProperties.class).invalidateSubscription())
                    .as("出厂 yml 的占位符默认值必须真的被绑定成 true，而不只是文本里写了 true")
                    .isTrue();
        });

        shippedYml.withPropertyValues("AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("configInvalidateListenerContainer"));
    }
}
