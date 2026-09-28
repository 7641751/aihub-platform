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

/**
 * 控制面配置写的收尾动作（D4 + D5）：**抬水位** + **广播失效**。
 *
 * <p>每一次成功的配置写（渠道 / 路由 / 限流策略 / API Key 状态…）在事务提交后调用
 * {@link #bumpAndPublish(String)} 一次，声明「配置变了，而且现在的版本是这个数」。gateway 侧的订阅方
 * （Task 4）收到后清本地 Caffeine、删共享快照条目、并把写入水位抬到消息里的版本 —— 这就是
 * M3 记录的「控制面改配置，数据面最长 10 分钟不收敛」那个缺口的修复。
 *
 * <p><b>抬水位为什么是「只抬不降」的 upsert</b>：{@code ConfigVersionMapper.raiseTo(now)} 用
 * {@code GREATEST(version, ?)} 写入，并发下谁都不会把水位压低。生效版本取
 * {@code max(现在, 读回的水位)}，这样即使另一个实例刚刚写了一个更大的值，本方法返回的也是**真正生效**的
 * 版本，而不是本地时钟的猜测。
 *
 * <p><b>绝不用 {@code raiseTo} 的受影响行数判断「发生了什么」</b>：MySQL 在
 * {@code ON DUPLICATE KEY UPDATE} 把值更新成**相同值**时返回 0，而 Connector/J 默认的
 * {@code useAffectedRows=false}（即设了 {@code CLIENT_FOUND_ROWS}）下又会返回 1 —— 同一个实现可能给出
 * 0 / 1 / 2，那是驱动语义，不是业务事实（Task 1 已登记）。
 *
 * <p><b>发布失败绝不让业务写失败</b>：调用本方法时控制台的写**已经提交**了。把「广播失败」变成
 * 「业务失败」只会让用户以为没存上，然后重试一次已经成功的写。因此 {@link #publish(long, String)}
 * 吞掉所有 {@link RuntimeException}，只记账 + 打 WARN。
 *
 * <p><b>一次广播丢失的代价（诚实登记）</b>：其他 gateway 实例不会收到信号，只能等**本地 TTL（30 秒）**
 * 过期后回源 admin，并靠快照里的版本号比对（D4/D5）决定是否采用新配置。而共享快照条目
 * （{@code aihub:config:snapshot}）在广播丢失时没人删，若某个实例此前采取过一次带**更大**版本的旧快照，
 * 版本比对就会一直把它挡在门外 —— 这正是 M3 记录的**最长 10 分钟**收敛上界（{@code docs/CONVENTIONS.md}
 * §6.6）。广播失败因此有计数器（{@value #PUBLISH_FAILURES_METRIC}）与 WARN，是**可观测的降级**，
 * 不是静默故障。
 */
@Service
public class ConfigChangePublisher {

    /**
     * 发布失败计数器名。与 {@link ConfigInvalidateTopology#CHANNEL} 同类：名字是观测契约，
     * 出现在告警规则与仪表盘里，因此作为常量暴露、由用例钉住，而不是散落的字符串字面量。
     */
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

    /**
     * 抬水位（D5）并广播失效（D4）。返回生效版本。
     *
     * <p><b>发布失败绝不让业务写失败</b>：控制台的写已经提交了，把「广播失败」变成「业务失败」
     * 只会让用户以为没存上 —— 而本地 TTL（30s）+ 版本比对是设计文档 §6.3 写明的兜底。
     * 代价（诚实登记）：广播失败时其他实例只能等 TTL，最长回到 M3 的 10 分钟上界。
     *
     * @param reason 失效原因（有限枚举，如 {@code "channel.update"}），只进日志与消息正文
     * @return 生效版本 = {@code max(现在, 抬升后读回的水位)}；订阅方按它抬写入水位
     */
    public long bumpAndPublish(String reason) {
        // 抬水位 = max(现在, 水位)：GREATEST 的 upsert 保证并发下不会把水位压低。
        // **不要**用 affected rows 判断"发生了什么"：MySQL 在「更新成相同值」时返回 0，
        // 而 Connector/J 默认的 CLIENT_FOUND_ROWS 语义下又会返回 1 —— 那是在猜驱动（Task 1 已登记）。
        long now = System.currentTimeMillis();
        configVersionMapper.raiseTo(now);
        Long stored = configVersionMapper.current();
        long version = stored == null ? now : Math.max(now, stored);
        publish(version, reason);
        return version;
    }

    /**
     * 把一条失效消息投到 {@link ConfigInvalidateTopology#CHANNEL}。**永不抛异常**：
     * 广播是可丢的加速手段，配置的真相源是 MySQL + 快照 TTL（见类注释里的代价登记）。
     *
     * <p>载荷由 {@link ConfigInvalidateCodec} 编码 —— 与 gateway 订阅方共用同一份实现，
     * 因此频道名与线格式只有一处真相。
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
