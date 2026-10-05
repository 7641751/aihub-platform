package com.aihub.mq.kb;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * kb 流水线拓扑的**唯一声明方**（M5 Task 3）：业务 exchange + **两条**业务队列（parse/embed）
 * + 一个 DLX + 一个 DLQ。
 *
 * <p><b>死信六件套</b>照既有 {@code MeteringTopologyConfig}，只是这里要**两套**业务队列/绑定：
 * 两个业务队列都声明 {@code x-dead-letter-exchange/routing-key}，DLX 绑一个 DLQ。
 * 载荷解不开或落库反复失败的消息会被 reject 并进 DLQ，而不是无限 requeue 把 broker 打爆，
 * 也不是静默丢弃。
 *
 * <p><b>所有名字都取自 {@link KbTopology}</b>，本类里一个硬编码字面量都没有：单侧改名不会编译失败，
 * 只会变成「消息永远投不到队列」—— 因此常量是唯一的真相源。
 */
@Configuration
public class KbTopologyConfig {

    @Bean
    public TopicExchange kbExchange() {
        return new TopicExchange(KbTopology.EXCHANGE, true, false);
    }

    @Bean
    public Queue kbParseQueue() {
        return QueueBuilder.durable(KbTopology.PARSE_QUEUE)
                .deadLetterExchange(KbTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(KbTopology.DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding kbParseBinding(Queue kbParseQueue, TopicExchange kbExchange) {
        return BindingBuilder.bind(kbParseQueue).to(kbExchange).with(KbTopology.PARSE_ROUTING_KEY);
    }

    @Bean
    public Queue kbEmbedQueue() {
        return QueueBuilder.durable(KbTopology.EMBED_QUEUE)
                .deadLetterExchange(KbTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(KbTopology.DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding kbEmbedBinding(Queue kbEmbedQueue, TopicExchange kbExchange) {
        return BindingBuilder.bind(kbEmbedQueue).to(kbExchange).with(KbTopology.EMBED_ROUTING_KEY);
    }

    @Bean
    public TopicExchange kbDeadLetterExchange() {
        return new TopicExchange(KbTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue kbDeadLetterQueue() {
        return QueueBuilder.durable(KbTopology.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding kbDeadLetterBinding(Queue kbDeadLetterQueue, TopicExchange kbDeadLetterExchange) {
        return BindingBuilder.bind(kbDeadLetterQueue).to(kbDeadLetterExchange)
                .with(KbTopology.DEAD_LETTER_ROUTING_KEY);
    }

    /**
     * 失败终态恢复器（M5 Task 6，D7）。它的处置动作（cleanup + 置 FAILED + 审计）由 service 侧的
     * {@link com.aihub.mq.kb.KbMessageRecoverer.TerminalFailureHandler} 实现（{@code KbDocumentService}）
     * 提供 —— 本类只按**接口**注入，因此 {@code aihub-mq} 不必（也不能）看到 {@code aihub-service}。
     */
    @Bean
    public KbMessageRecoverer kbMessageRecoverer(KbMessageRecoverer.TerminalFailureHandler handler) {
        return new KbMessageRecoverer(handler);
    }

    /**
     * kb 流水线**专用**的监听容器工厂（M5 Task 6）—— 只有 {@code KbParseConsumer} / {@code KbEmbedConsumer}
     * 走它，计量链路（{@code MeteringConsumer}）仍用框架默认工厂、行为**一点不改**。
     *
     * <p><b>为什么必须"专用"而不是改默认工厂</b>（裁定 #2）：本仓的
     * {@code spring.rabbitmq.listener.simple.retry.*} 与 {@code default-requeue-rejected} 是**全局**的；
     * 把自定义 {@link KbMessageRecoverer} 设到默认工厂上，会**一起改掉计量链路的死信行为**
     * （它有自己的 DLQ 用例）。
     *
     * <p><b>四个重试数从注入的 {@link RabbitProperties} 派生</b>（max-attempts / initial-interval /
     * multiplier / max-interval）—— **不把 yml 里的数抄成字面量**：抄死之后 yml 改了这里不会跟着改，
     * "重试策略只有一份真相源"就没了。恢复器只在重试**耗尽后**被调用（见 {@link KbMessageRecoverer}）。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory kbListenerContainerFactory(
            ConnectionFactory connectionFactory,
            RabbitProperties rabbitProperties,
            KbMessageRecoverer kbMessageRecoverer) {
        RabbitProperties.SimpleContainer simple = rabbitProperties.getListener().getSimple();
        RabbitProperties.ListenerRetry retry = simple.getRetry();

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // 与全局同值（取自 properties，不是字面量）：非 AmqpRejectAndDontRequeue 的异常也不 requeue。
        factory.setDefaultRequeueRejected(simple.getDefaultRequeueRejected());
        // 只要这一条差别：恢复器换成"先处置失败终态，再拒绝进 DLQ"的那个。其余重试参数与全局一致。
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(retry.getMaxAttempts())
                .backOffOptions(retry.getInitialInterval().toMillis(), retry.getMultiplier(),
                        retry.getMaxInterval().toMillis())
                .recoverer(kbMessageRecoverer)
                .build());
        return factory;
    }
}
