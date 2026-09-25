package com.aihub.mq.meter;

import com.aihub.common.meter.MeteringTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 计量链路的**唯一声明方**（gateway 只发布，见计划「决策登记」第 4 条）。
 *
 * <p>死信三件套：业务队列声明 {@code x-dead-letter-exchange/routing-key}，DLX 绑一个 DLQ。
 * 载荷解不开或落库反复失败的消息会被 reject 并进 DLQ，而不是无限 requeue 把 broker 打爆，
 * 也不是静默丢弃。
 *
 * <p><b>reject 的实际来源</b>是重试耗尽，不是 {@code default-requeue-rejected}：
 * {@code spring.rabbitmq.listener.simple.retry.enabled=true} 让 Spring Boot 装上
 * {@code RejectAndDontRequeueRecoverer}，重试 3 次后它抛 {@code AmqpRejectAndDontRequeueException}，
 * 容器据此以 requeue=false 拒绝（实测日志：「Retries exhausted for message」→
 * {@code ConditionalRejectingErrorHandler}）。{@code default-requeue-rejected=false} 管的是
 * **另一条**路径（未开重试时容器自身的兜底），是可留的保险；实测把它改成 true，本任务的死信用例
 * 依然绿 —— 真正把关的是 recoverer，别误以为那个开关是死信的唯一开关。
 *
 * <p><b>所有名字都取自 {@link MeteringTopology}</b>，与本类里一个硬编码字面量都没有：
 * 网关用同一份常量发布（{@code RabbitMeteringTransport}），单侧改名不会编译失败，
 * 只会变成「消息永远投不到队列」—— 因此常量是唯一的真相源。
 */
@Configuration
public class MeteringTopologyConfig {

    @Bean
    public TopicExchange meteringExchange() {
        return new TopicExchange(MeteringTopology.EXCHANGE, true, false);
    }

    @Bean
    public Queue meteringQueue() {
        return QueueBuilder.durable(MeteringTopology.QUEUE)
                .deadLetterExchange(MeteringTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(MeteringTopology.DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding meteringBinding(Queue meteringQueue, TopicExchange meteringExchange) {
        return BindingBuilder.bind(meteringQueue).to(meteringExchange).with(MeteringTopology.ROUTING_KEY);
    }

    @Bean
    public TopicExchange meteringDeadLetterExchange() {
        return new TopicExchange(MeteringTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue meteringDeadLetterQueue() {
        return QueueBuilder.durable(MeteringTopology.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding meteringDeadLetterBinding(Queue meteringDeadLetterQueue,
                                            TopicExchange meteringDeadLetterExchange) {
        return BindingBuilder.bind(meteringDeadLetterQueue).to(meteringDeadLetterExchange)
                .with(MeteringTopology.DEAD_LETTER_ROUTING_KEY);
    }
}
