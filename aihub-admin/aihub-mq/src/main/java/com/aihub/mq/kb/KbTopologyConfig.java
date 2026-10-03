package com.aihub.mq.kb;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
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
}
