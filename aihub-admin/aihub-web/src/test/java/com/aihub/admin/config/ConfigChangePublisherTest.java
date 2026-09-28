package com.aihub.admin.config;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.config.ConfigInvalidateCodec;
import com.aihub.common.config.ConfigInvalidateTopology;
import com.aihub.dao.mapper.ConfigVersionMapper;
import com.aihub.service.config.ConfigChangePublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * admin 侧的**发布方**（D4/D5）：抬水位 + 把失效消息真的投到 {@code aihub:config:invalidate}。
 *
 * <p><b>为什么这是一条真实的订阅</b>：本用例不断言「方法被调用过」（那是 Mockito 的断言），
 * 而是在真 Redis（{@code AbstractIntegrationTest} 的 Testcontainers 单例）上装一条
 * {@link RedisMessageListenerContainer}，断言**线上真的出现了那条消息**、且它的版本号等于
 * {@code bumpAndPublish} 的返回值。跨服务契约的价值全在这里：gateway 侧订阅方（Task 4）看到的
 * 字节必须能被同一个 codec 解开。仅本任务允许这样装订阅来断言「消息发出去了」。
 *
 * <p><b>{@code container} 是本类自己建的，不是基类给的</b>：{@code AbstractIntegrationTest} 只提供
 * MySQL/Redis/RabbitMQ 三个容器与 {@code spring.data.redis.*} 的注入，不提供任何
 * {@code RedisMessageListenerContainer} 字段，所以这里用注入的 {@link RedisConnectionFactory} 现搭一个
 * （brief 里的 {@code container.addMessageListener(...)} 是伪代码）。
 *
 * <p>两个用例都不需要额外的 Docker 容器（复用基类的单例）：成功路径用真 Redis，
 * 失败路径用替身 Redis —— 「发布失败绝不让业务写失败」是**异常路径**，不能靠真 Redis 去制造。
 */
class ConfigChangePublisherTest extends AbstractIntegrationTest {

    /** 订阅建立的判据：Spring 的监听容器是**异步**订阅的，见 {@link #awaitSubscription}。 */
    private static final Duration SUBSCRIPTION_TIMEOUT = Duration.ofSeconds(5);

    private static final String SUBSCRIPTION_PROBE = "subscription-probe";

    @Autowired
    private ConfigChangePublisher publisher;

    @Autowired
    private ConfigVersionMapper configVersionMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private RedisMessageListenerContainer listenerContainer;

    @AfterEach
    void stopListenerContainer() throws Exception {
        if (listenerContainer != null) {
            // destroy() 同时 stop() 并释放订阅连接；它的签名声明了 throws Exception（DisposableBean）。
            listenerContainer.destroy();
            listenerContainer = null;
        }
    }

    @Test
    void publishesAfterBumpingTheWatermarkAndNeverThrowsOnRedisFailure() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        container().addMessageListener((m, ch) -> received.add(new String(m.getBody())),
                new ChannelTopic(ConfigInvalidateTopology.CHANNEL));
        awaitSubscription(received);

        long version = publisher.bumpAndPublish("channel.create");

        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull()
                .satisfies(body -> assertThat(ConfigInvalidateCodec.decode(body).version()).isEqualTo(version));
        assertThat(configVersionMapper.current()).isGreaterThanOrEqualTo(version);
    }

    /**
     * 「发布失败绝不让业务写失败」的判据（D4）：Redis 抛 {@link RuntimeException} 时
     * {@code bumpAndPublish} 必须**正常返回**（业务写已经提交，把广播失败变成业务失败只会让用户
     * 以为没存上），同时失败被计数（观测面）。
     *
     * <p>用替身而不是真 Redis：这里要制造的是「Redis 命令抛异常」这一条**确定的**路径，
     * 真 Redis 只能靠拔网线/黑障端口去逼近它，慢且不确定。水位那一跳用替身 Mapper 返回
     * {@code null}（空库语义），因此生效版本就是「现在」。
     */
    @Test
    void redisFailureDoesNotFailTheBusinessWriteAndIsCounted() {
        StringRedisTemplate failingRedis = mock(StringRedisTemplate.class);
        when(failingRedis.convertAndSend(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("模拟 Redis 不可用"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ConfigChangePublisher publisherWithFailingRedis = new ConfigChangePublisher(
                failingRedis, mock(ConfigVersionMapper.class), meterRegistry);

        assertThatCode(() -> publisherWithFailingRedis.bumpAndPublish("channel.update"))
                .as("发布失败只记 WARN + 计数，绝不抛给调用方（TTL + 版本比对是兜底）")
                .doesNotThrowAnyException();

        assertThat(meterRegistry.counter(ConfigChangePublisher.PUBLISH_FAILURES_METRIC).count())
                .as("发布失败必须被计数（否则「广播在静默失败」没有任何观测面）")
                .isEqualTo(1.0);
    }

    private RedisMessageListenerContainer container() {
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(redisConnectionFactory);
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        return listenerContainer;
    }

    /**
     * 等订阅真的建立再发布。{@code RedisMessageListenerContainer.start()} 返回时 SUBSCRIBE 可能还没到
     * 服务端（订阅走的是另一个连接 + 线程池，是异步的），紧接着 publish 的消息会被静默丢掉 ——
     * 那会让本用例偶发变红。
     *
     * <p>做法：反复发一条**带唯一后缀的哨兵**，直到 {@code poll} 拿到的**第一条就是刚刚这条哨兵** ——
     * 说明队列里连更早的残留都没有（发布连接与订阅连接各一条，Redis 保序），于是随后的真消息一定是
     * 紧接着被投递的那一条。哨兵不含分隔符，{@link ConfigInvalidateCodec#decode} 对它返回 {@code null}，
     * 因此它不可能被误当成一条有效失效。
     */
    private void awaitSubscription(BlockingQueue<String> received) throws InterruptedException {
        long deadline = System.nanoTime() + SUBSCRIPTION_TIMEOUT.toNanos();
        long sequence = 0L;
        while (System.nanoTime() < deadline) {
            String probe = SUBSCRIPTION_PROBE + "-" + sequence++;
            redisTemplate.convertAndSend(ConfigInvalidateTopology.CHANNEL, probe);
            if (probe.equals(received.poll(200, TimeUnit.MILLISECONDS))) {
                return;
            }
        }
        throw new IllegalStateException("Redis 订阅在 " + SUBSCRIPTION_TIMEOUT + " 内没有建立，用例无法判定消息是否发出");
    }
}
