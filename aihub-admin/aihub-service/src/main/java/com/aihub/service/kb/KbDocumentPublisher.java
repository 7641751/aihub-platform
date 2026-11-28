package com.aihub.service.kb;

import com.aihub.mq.kb.KbTopology;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;

/** 等待 RabbitMQ 确认知识库消息已路由。发布失败时由 outbox 保留消息并重试。 */
@Service
public class KbDocumentPublisher {

    /** 知识库消息发布失败次数。 */
    public static final String PUBLISH_FAILURES_METRIC = "aihub.kb.publish_failures";

    private static final Logger log = LoggerFactory.getLogger(KbDocumentPublisher.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 2;

    private final RabbitTemplate rabbitTemplate;
    private final Counter publishFailures;

    public KbDocumentPublisher(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.publishFailures = Counter.builder(PUBLISH_FAILURES_METRIC)
                .description("知识库 outbox 发布失败次数")
                .register(meterRegistry);
    }

    /** 成功需要肯定的发布确认，以及没有对应的 mandatory return。 */
    public boolean publish(String routingKey, String payload, long generation) {
        try {
            Message message = MessageBuilder.withBody(payload.getBytes(UTF_8))
                    .setContentType(KbTopology.MESSAGE_CONTENT_TYPE)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setHeader(KbTopology.GENERATION_HEADER, generation)
                    .build();
            CorrelationData correlation = new CorrelationData();
            rabbitTemplate.send(KbTopology.EXCHANGE, routingKey, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ReturnedMessage returned = correlation.getReturned();
            if (returned != null) {
                return fail(routingKey, generation, returned.getReplyText());
            }
            if (!confirm.isAck()) {
                return fail(routingKey, generation, "NACK: " + confirm.getReason());
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(routingKey, generation, e.toString());
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return fail(routingKey, generation, e.toString());
        }
    }

    private boolean fail(String routingKey, long generation, String reason) {
        publishFailures.increment();
        log.warn("知识库 outbox 发布失败: routingKey={} generation={} reason={}", routingKey, generation, reason);
        return false;
    }
}
