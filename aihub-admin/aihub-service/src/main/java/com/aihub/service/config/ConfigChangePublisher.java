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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p><b>调用方义务：只能在事务提交之后调用，不能在事务里调</b>。本方法写的是**持久水位**，而广播是
 * **不可撤回**的：若在事务内调用而事务随后回滚，消息里的版本会比水位更高，其他实例按版本比对就会把
 * **真实但更低**的快照挡在门外，直到后续某次写超过它才自愈 —— 有界、可自愈，但是**静默**的。
 * 「提交后调用」这件事**已经在本类里落成机制**：{@link #publishAfterCommit(String)}（Task 8 引入）
 * 在事务里注册 after-commit 钩子，事务回滚时钩子不执行；不变量由
 * {@code ChannelAdminIntegrationTest#rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark} 钉住
 * （业务写、审计、钩子一起作废），正向对照是
 * {@code ChannelAdminIntegrationTest#everyWritePublishesAnInvalidationMessage}。
 * <b>已被取代的历史说明（保留以示演变，不要当成本类今天的性质）</b>：在本类还只有
 * {@code bumpAndPublish} 的那些任务里，这一段写的是「把『提交后调用』落成 after-commit 钩子（连同它的
 * 用例）属于**引入调用方的任务**（Task 8/9/10）；本任务只登记这条义务，**不声称它已被验证**（这里没有
 * 回滚用例，也没有 after-commit 机制）」—— 那两句话对**今天的代码不再成立**。
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
     * <p><b>调用方义务：必须在事务提交之后调用</b>（见类注释）：本方法写持久水位 + 发不可撤回的广播，
     * 在事务内调用、事务随后回滚，会让发布出去的版本高于水位，其他实例便会把真实但更低的快照挡在门外
     * （有界、可自愈，但静默）。after-commit 钩子由 {@link #publishAfterCommit(String)} 提供
     * （Task 8 引入，回滚用例见 {@code ChannelAdminIntegrationTest}），控制面写路径一律走它，
     * 不要在本方法的调用点自己判断事务。
     *
     * <p><b>发布失败绝不让业务写失败</b>：控制台的写已经提交了，把「广播失败」变成「业务失败」
     * 只会让用户以为没存上 —— 而本地 TTL（30s）+ 版本比对是设计文档 §6.3 写明的兜底。
     * 代价（诚实登记）：广播失败时其他实例只能等 TTL，最长回到 M3 的 10 分钟上界。
     *
     * @param reason 失效原因 token（**有限枚举**，如 {@code "channel.update"}）：只进日志与消息正文，
     *               但**必须非空** —— 空/ {@code null} 会被 {@link ConfigInvalidateCodec#encode} 拒绝，
     *               由 {@link #publish(long, String)} 转成计数 + WARN（订阅端会丢弃这种载荷，静默不失效）
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
     * 事务安全的发布入口：**有活动事务就注册 after-commit 钩子，没有活动事务就立即发布**。
     *
     * <p><b>两个分支都被钉死</b>（这是「先命名机制」的产物，Task 8 引入、Task 9/10 一律复用）：
     * <ul>
     *   <li>{@link TransactionSynchronizationManager#isSynchronizationActive()} 为真（即调用发生在某个
     *       {@code @Transactional} 方法里）：
     *       {@code registerSynchronization(new TransactionSynchronization() { afterCommit() { bumpAndPublish(reason); } })} ——
     *       事务**提交之后**才抬水位并广播；事务回滚时钩子不执行，于是「广播了但水位没抬」不可能发生。
     *       用例：{@code ChannelAdminIntegrationTest#everyWritePublishesAnInvalidationMessage}（正向）与
     *       {@code ChannelAdminIntegrationTest#rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark}（反向）；</li>
     *   <li>为假（服务层被非事务方式调用，例如单元测试或运维脚本直接调）：
     *       **立即** {@link #bumpAndPublish(String)} —— 与旧行为一致，不会静默不发布。
     *       用例：{@code ConfigChangePublisherTest#publishAfterCommitPublishesImmediatelyWhenNoTransactionIsActive}。</li>
     * </ul>
     *
     * <p><b>为什么必须走 afterCommit 而不是在事务里直接调 {@link #bumpAndPublish(String)}</b>：
     * {@code bumpAndPublish} 自己会**写数据库水位**。在事务里调、随后事务回滚，会让「已广播的版本 V」
     * 高于「持久水位」—— 而订阅方（gateway，Task 4）收到 V 之后会把**自己的写入水位抬到 V**。
     * 于是任何**真实但更旧**的快照都会被 {@code writeRedis}/lastGood 双双拒绝：共享条目一直是空的、
     * 每个实例每一轮 TTL 都要回源 admin，直到某次成功的写产生一个超过 V 的版本才自愈
     * （有界、能自愈，但是**静默**的 —— 没有任何一条 WARN 指向它）。
     *
     * <p><b>调用纪律</b>：控制面的写路径（Task 8/9/10）一律调本方法，**不许**直接调
     * {@link #bumpAndPublish(String)}。发布失败仍然只计数 + WARN（见 {@link #publish(long, String)}），
     * 绝不把广播失败升级成业务失败。
     *
     * @param reason 失效原因 token（**有限枚举**，如 {@code "channel.create"}），语义与
     *               {@link #bumpAndPublish(String)} 完全一致
     */
    public void publishAfterCommit(String reason) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bumpAndPublish(reason);
                }
            });
            return;
        }
        // 没有活动事务：没有任何东西可以回滚，立即发布就是正确的语义。
        bumpAndPublish(reason);
    }

    /**
     * 把一条失效消息投到 {@link ConfigInvalidateTopology#CHANNEL}。**永不抛异常**：
     * 广播是可丢的加速手段，配置的真相源是 MySQL + 快照 TTL（见类注释里的代价登记）。
     *
     * <p><b>调用方义务：必须在事务提交之后调用</b>（见类注释）：这里发出去的是**不可撤回**的广播，
     * 事务回滚不会把它收回来，而水位会回滚 —— 消息里的版本因此可能长期高于真实水位。
     *
     * <p>载荷由 {@link ConfigInvalidateCodec} 编码 —— 与 gateway 订阅方共用同一份实现，
     * 因此频道名与线格式只有一处真相。
     *
     * @param reason 失效原因 token（**有限枚举**，如 {@code "channel.update"}），**必须非空**：
     *               {@link ConfigInvalidateCodec#encode} 对 {@code null} / 空串快速失败，而本方法的
     *               {@code catch (RuntimeException)} 把它转成计数 + WARN —— 因为订阅端会拒绝这种载荷，
     *               让它上线等于**静默**地不做失效
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
