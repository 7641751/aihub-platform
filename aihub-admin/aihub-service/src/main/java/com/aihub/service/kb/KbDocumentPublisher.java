package com.aihub.service.kb;

import com.aihub.mq.kb.KbEmbedBatch;
import com.aihub.mq.kb.KbMessageCodec;
import com.aihub.mq.kb.KbTopology;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * kb 流水线的**发布端**（M5 Task 3）：把「这份文档可以开始解析了」的消息在业务写**提交之后**
 * 投到 {@code kb.parse}。
 *
 * <p><b>为什么必须 after commit</b>：解析消费者（Task 4）会去读 {@code kb_document} 那一行；若在事务里
 * 就发、而事务随后回滚，消费者会读到「查无此文档」（或读到旧状态），要么死信、要么白跑一遍。
 * after-commit 钩子在事务提交后才执行，事务回滚时钩子不执行 —— 于是「消息发出去了但行没提交」不可能发生。
 *
 * <p><b>发布失败绝不让业务写失败</b>（照 {@code ConfigChangePublisher.publish} 的先例，裁定 #6）：
 * 调用本方法时业务写**已经提交**了。把「发不出去」升级成「业务失败」只会让用户以为没存上，
 * 然后重试一次已经成功的写。因此 {@link #publishParse(long)} 吞掉所有 {@link RuntimeException}，
 * 只记账 + 打 WARN。
 *
 * <p><b>一次发布丢失的代价（诚实登记）</b>：该 {@code kb_document} 行会**永远停在 {@code PENDING}**
 * （没有别的机制会重发）。Task 6 的 DLQ 只管**消费端**失败，管不到「消息根本没进 broker」⇒
 * 只能靠计数器 {@value #PUBLISH_FAILURES_METRIC} 告警 + 运维重发。
 */
@Service
public class KbDocumentPublisher {

    /**
     * 发布失败计数器名（照 {@code ConfigChangePublisher.PUBLISH_FAILURES_METRIC} 的先例）：
     * 名字是**观测契约**，出现在告警规则与仪表盘里，因此作为常量暴露、由用例钉住，而不是散落的字面量。
     */
    public static final String PUBLISH_FAILURES_METRIC = "aihub.kb.publish_failures";

    private static final Logger log = LoggerFactory.getLogger(KbDocumentPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final Counter publishFailures;

    public KbDocumentPublisher(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.publishFailures = Counter.builder(PUBLISH_FAILURES_METRIC)
                .description("kb 解析消息发布失败次数（业务写已提交，该行会停在 PENDING 等运维重发）")
                .register(meterRegistry);
    }

    /**
     * 事务安全的发布入口：**有活动事务就注册 after-commit 钩子，没有活动事务就立即发布**
     * （与 {@code ConfigChangePublisher.publishAfterCommit} 同形，两个分支都被钉死）。
     *
     * <p>用例：{@code KbDocumentPublisherTest#publishesOnlyAfterCommitNeverBefore}（提交前 {@code never()}）
     * 与 {@code KbDocumentPublisherTest#publishesImmediatelyWhenNoTransactionIsActive}（无事务立即发）。
     *
     * @param docId 已提交（或将随本事务提交）的 {@code kb_document.id}
     */
    public void publishParseAfterCommit(long docId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishParse(docId);
                }
            });
            return;
        }
        // 没有活动事务：没有任何东西可以回滚，立即发布就是正确的语义。
        publishParse(docId);
    }

    /**
     * 把 {@code parse:{docId}} 投到 {@code kb.parse}。**永不抛异常**：发布是可丢的触发手段，
     * 文档的真相源是 MySQL（{@code kb_document} + 原件）。
     */
    public void publishParse(long docId) {
        try {
            // 显式声明线格式的内容类型（照计量链路的发布端）：消费端（Task 4）因此不必猜
            // "这条消息是谁用什么转换器发的"，`KbTopology.MESSAGE_CONTENT_TYPE` 也不再是死常量。
            rabbitTemplate.convertAndSend(KbTopology.EXCHANGE, KbTopology.PARSE_ROUTING_KEY,
                    KbMessageCodec.parse(docId),
                    message -> {
                        message.getMessageProperties().setContentType(KbTopology.MESSAGE_CONTENT_TYPE);
                        return message;
                    });
        } catch (RuntimeException e) {
            publishFailures.increment();
            log.warn("kb 解析消息发布失败（业务写已提交，该行会停在 PENDING 等运维重发）: docId={} {}",
                    docId, e.toString());
        }
    }

    /**
     * 事务安全的嵌入阶段发布入口（Task 4）：**有活动事务就注册 after-commit 钩子，没有就立即发**。
     *
     * <p>与 {@link #publishParseAfterCommit(long)} 同形、同样的理由：embed 消费者（Task 5）会按
     * {@code (docId, seq)} 去读 {@code kb_chunk}，必须"段先提交、消息后可见"。
     *
     * @param batch 已写好段的闭区间批次
     */
    public void publishEmbedAfterCommit(KbEmbedBatch batch) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishEmbed(batch);
                }
            });
            return;
        }
        publishEmbed(batch);
    }

    /**
     * 把 {@code embed:{docId}:{seqFrom}:{seqTo}} 投到 {@code kb.embed}。**永不抛异常**：发布是可丢的
     * 触发手段，文档的真相源是 MySQL（{@code kb_document} + {@code kb_chunk} + 原件）。
     *
     * <p>残余（诚实登记）：发布丢了 ⇒ 该 doc 会停在 {@code EMBEDDING}（没有别的机制会重发）⇒
     * 靠计数器 {@value #PUBLISH_FAILURES_METRIC} 告警 + 运维重发（重新上传即现成的重试手势）。
     */
    public void publishEmbed(KbEmbedBatch batch) {
        try {
            rabbitTemplate.convertAndSend(KbTopology.EXCHANGE, KbTopology.EMBED_ROUTING_KEY,
                    KbMessageCodec.embed(batch.docId(), batch.seqFrom(), batch.seqTo()),
                    message -> {
                        message.getMessageProperties().setContentType(KbTopology.MESSAGE_CONTENT_TYPE);
                        return message;
                    });
        } catch (RuntimeException e) {
            publishFailures.increment();
            log.warn("kb 嵌入消息发布失败（段已提交，该 doc 会停在 EMBEDDING 等运维重发）: batch={} {}",
                    batch, e.toString());
        }
    }
}
