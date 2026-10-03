package com.aihub.admin.kb;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aihub.mq.kb.KbMessageCodec;
import com.aihub.mq.kb.KbTopology;
import com.aihub.service.kb.KbDocumentPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * M5 Task 3 的**零上下文单测**（不启 Spring）：{@link KbDocumentPublisher} 的 after-commit 判别力。
 *
 * <p><b>为什么是"零上下文单测"而不是"回滚对照"</b>：仓库里既有的回滚对照
 * （{@code ChannelAdminIntegrationTest#rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark}）
 * 靠 {@code @Import} 一个 probe 配置造回滚 ⇒ **会 fork 第 8 个 Spring 上下文**，违反 M5 的
 * 「{@code Tomcat started on port} 保持 7」硬约束。这里手工驱动
 * {@link TransactionSynchronizationManager}，既拿到同样的判别力，又**一个上下文都不占**。
 *
 * <p><b>判别力来源</b>：把 {@code publishParseAfterCommit} 改成"立即发" ⇒ {@link #publishesOnlyAfterCommitNeverBefore()}
 * 的"提交前 {@code verify(never())}"必红。这是本任务的自然 RED。
 */
class KbDocumentPublisherTest {

    /**
     * 兜底：{@link TransactionSynchronizationManager} 的同步集合是 **ThreadLocal**，一个用例若在
     * 断言失败时把注册表留在了本线程上，会污染同一线程里的其它用例。用例自己的 {@code finally} 已清一次，
     * 这里再清一次以防万一。
     */
    @AfterEach
    void clearAnyLeftoverSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 核心：**提交前一条都不许发**；只有手动触发 after-commit 之后才允许发。实现改成"立即发"本用例必红。
     */
    @Test
    void publishesOnlyAfterCommitNeverBefore() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        KbDocumentPublisher publisher = new KbDocumentPublisher(rabbit, new SimpleMeterRegistry());

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishParseAfterCommit(7L);

            verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                    any(MessagePostProcessor.class));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(rabbit).convertAndSend(eq(KbTopology.EXCHANGE), eq(KbTopology.PARSE_ROUTING_KEY),
                    eq(KbMessageCodec.parse(7L)), any(MessagePostProcessor.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 正向对照（"另一个分支被钉死"）：**没有**活动事务时立即发布，绝不静默不发。
     * （服务层被非事务方式调用、或运维脚本直接调时会走到这里。）
     */
    @Test
    void publishesImmediatelyWhenNoTransactionIsActive() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        KbDocumentPublisher publisher = new KbDocumentPublisher(rabbit, new SimpleMeterRegistry());

        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        publisher.publishParseAfterCommit(7L);

        verify(rabbit).convertAndSend(eq(KbTopology.EXCHANGE), eq(KbTopology.PARSE_ROUTING_KEY),
                eq(KbMessageCodec.parse(7L)), any(MessagePostProcessor.class));
    }

    /**
     * 线格式的内容类型由**发布端显式声明**（照计量链路的发布端；{@code KbTopology.MESSAGE_CONTENT_TYPE}
     * 因此不再是"声明了没人用"的死常量）。消费端（Task 4）据此解码，不必猜上一条消息是谁用什么转换器发的。
     */
    @Test
    void theParseMessageDeclaresItsContentType() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        KbDocumentPublisher publisher = new KbDocumentPublisher(rabbit, new SimpleMeterRegistry());

        publisher.publishParse(7L);

        ArgumentCaptor<MessagePostProcessor> postProcessor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbit).convertAndSend(eq(KbTopology.EXCHANGE), eq(KbTopology.PARSE_ROUTING_KEY),
                eq(KbMessageCodec.parse(7L)), postProcessor.capture());

        Message processed = postProcessor.getValue().postProcessMessage(
                MessageBuilder.withBody(KbMessageCodec.parse(7L).getBytes(StandardCharsets.UTF_8)).build());
        assertThat(processed.getMessageProperties().getContentType())
                .as("内容类型必须是 MeteringTopology 同款的线格式声明")
                .isEqualTo(KbTopology.MESSAGE_CONTENT_TYPE);
    }

    /**
     * 「发布失败绝不让业务写失败」（照 {@code ConfigChangePublisher.publish} 的先例，裁定 #6）：
     * broker 抛 {@link RuntimeException} 时**正常返回**，同时留下**恰好一条 WARN** + 计数器
     * {@value KbDocumentPublisher#PUBLISH_FAILURES_METRIC}。缺 WARN 或缺计数，本用例必红。
     *
     * <p>残余（诚实登记）：消息真丢了 ⇒ 该行**永远 {@code PENDING}**；Task 6 的 DLQ 只管**消费端**失败，
     * 管不到这个 ⇒ 只能靠计数器告警 + 运维重发。
     */
    @Test
    void aBrokerFailureIsCountedAndWarnedButNeverPropagates() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doThrow(new AmqpException("模拟 broker 不可用"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                        any(MessagePostProcessor.class));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        KbDocumentPublisher publisher = new KbDocumentPublisher(rabbit, meterRegistry);

        List<ILoggingEvent> warnings = capturePublisherWarnings(() ->
                assertThatCode(() -> publisher.publishParseAfterCommit(7L))
                        .as("发布失败只记 WARN + 计数，绝不让已提交的业务写看起来失败")
                        .doesNotThrowAnyException());

        assertThat(meterRegistry.counter(KbDocumentPublisher.PUBLISH_FAILURES_METRIC).count())
                .as("发布失败必须被计数（否则「消息在静默失败」没有任何观测面）")
                .isEqualTo(1.0);
        assertThat(warnings).as("发布失败必须留下恰好一条 WARN").hasSize(1);
        assertThat(warnings.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(warnings.get(0).getFormattedMessage())
                .as("WARN 必须指向这次失败的原因，而不是别的噪声")
                .contains("AmqpException");
    }

    /** 抓 {@link KbDocumentPublisher} 自己打的 WARN（照 {@code ConfigChangePublisherTest} 的 ListAppender 形状）。 */
    private static List<ILoggingEvent> capturePublisherWarnings(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KbDocumentPublisher.class);
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
