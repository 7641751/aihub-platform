package com.aihub.service.config;

import com.aihub.common.config.ConfigInvalidateCodec;
import com.aihub.common.config.ConfigInvalidateMessage;
import com.aihub.common.config.ConfigInvalidateTopology;
import com.aihub.dao.mapper.ConfigVersionMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在配置写入事务末尾分配版本。配置、审计与版本一起提交或回滚，Redis 只在提交后收到已分配的版本。
 *
 * <p>MySQL 的单行版本记录会串行分配版本，因此调用方应把版本分配放在业务事务末尾。
 * 提交后的通知可丢失或乱序，网关用版本水位防止回退。MySQL 是持久数据源，回源时可读取已提交的配置版本。
 */
@Service
public class ConfigChangePublisher {

    /** 发布失败计数器名，供告警和仪表盘使用。 */
    public static final String PUBLISH_FAILURES_METRIC = "aihub.config.publish_failures";

    private static final Logger log = LoggerFactory.getLogger(ConfigChangePublisher.class);

    private final StringRedisTemplate redis;
    private final ConfigVersionMapper configVersionMapper;
    private final Counter publishFailures;

    public ConfigChangePublisher(StringRedisTemplate redis, ConfigVersionMapper configVersionMapper,
                                 MeterRegistry meterRegistry) {
        this.redis = redis;
        this.configVersionMapper = configVersionMapper;
        this.publishFailures = Counter.builder(PUBLISH_FAILURES_METRIC)
                .description("配置失效消息发布失败次数（业务写已提交，其他实例只能等 TTL 兜底）")
                .register(meterRegistry);
    }

    /** 加入当前事务或开启事务，分配版本并在提交后广播。返回这次事务分配的版本。 */
    @Transactional
    public long bumpAndPublish(String reason) {
        return allocateAndSchedulePublication(reason);
    }

    /** 在当前事务内分配版本并注册提交后的失效通知。通过 Spring 代理独立调用时会开启事务。 */
    @Transactional
    public void publishAfterCommit(String reason) {
        allocateAndSchedulePublication(reason);
    }

    private long allocateAndSchedulePublication(String reason) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("配置版本分配需要活动事务和事务同步");
        }
        configVersionMapper.advance(System.currentTimeMillis());
        Long version = configVersionMapper.current();
        if (version == null) {
            throw new IllegalStateException("无法读回已分配的配置版本");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(version, reason);
            }
        });
        return version;
    }

    /**
     * 广播已提交的版本。编码或 Redis 发布失败时记录计数和 WARN，让网关回源读取已提交的配置。
     */
    public void publish(long version, String reason) {
        try {
            redis.convertAndSend(ConfigInvalidateTopology.CHANNEL,
                    ConfigInvalidateCodec.encode(new ConfigInvalidateMessage(version, reason)));
        } catch (RuntimeException e) {
            publishFailures.increment();
            log.warn("配置失效消息发布失败（业务写已提交，其他实例只能等 TTL 兜底）: {}", e.toString());
        }
    }
}
