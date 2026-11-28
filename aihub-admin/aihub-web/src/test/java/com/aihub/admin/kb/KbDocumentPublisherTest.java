package com.aihub.admin.kb;

import com.aihub.mq.kb.KbTopology;
import com.aihub.service.kb.KbDocumentPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class KbDocumentPublisherTest {

    private final TestRabbitTemplate rabbit = new TestRabbitTemplate();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final KbDocumentPublisher publisher = new KbDocumentPublisher(rabbit, metrics);

    @Test
    void acknowledgesRoutablePersistentMessagesWithUnchangedBodyAndGenerationHeader() {
        assertThat(publisher.publish(KbTopology.EMBED_ROUTING_KEY, "embed:7:0:2", 3)).isTrue();
        assertThat(rabbit.exchange).isEqualTo(KbTopology.EXCHANGE);
        assertThat(rabbit.routingKey).isEqualTo(KbTopology.EMBED_ROUTING_KEY);
        assertThat(rabbit.message.getBody()).asString(UTF_8).isEqualTo("embed:7:0:2");
        assertThat(rabbit.message.getMessageProperties().getContentType()).isEqualTo(KbTopology.MESSAGE_CONTENT_TYPE);
        assertThat(rabbit.message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(rabbit.message.getMessageProperties().getHeaders()).containsEntry("x-aihub-kb-generation", 3L);
        assertThat(failures()).isZero();
    }

    @Test
    void brokerReturnIsAFailureEvenWhenAcknowledged() {
        rabbit.returned = true;
        assertThat(publisher.publish(KbTopology.PARSE_ROUTING_KEY, "parse:7", 0)).isFalse();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void negativeConfirmationIsAFailure() {
        rabbit.ack = false;
        assertThat(publisher.publish(KbTopology.PARSE_ROUTING_KEY, "parse:7", 0)).isFalse();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void missingConfirmationIsAFailure() {
        rabbit.confirm = false;
        assertThat(publisher.publish(KbTopology.PARSE_ROUTING_KEY, "parse:7", 0)).isFalse();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void sendExceptionIsAFailure() {
        rabbit.offline = true;
        assertThat(publisher.publish(KbTopology.PARSE_ROUTING_KEY, "parse:7", 0)).isFalse();
        assertThat(failures()).isEqualTo(1);
    }

    private double failures() {
        return metrics.counter(KbDocumentPublisher.PUBLISH_FAILURES_METRIC).count();
    }

    private static final class TestRabbitTemplate extends RabbitTemplate {

        private boolean confirm = true;
        private boolean ack = true;
        private boolean returned;
        private boolean offline;
        private String exchange;
        private String routingKey;
        private Message message;

        @Override
        public void send(String exchange, String routingKey, Message message, CorrelationData correlation) {
            if (offline) {
                throw new AmqpException("connection unavailable");
            }
            this.exchange = exchange;
            this.routingKey = routingKey;
            this.message = message;
            if (returned) {
                correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", exchange, routingKey));
            }
            if (confirm) {
                correlation.getFuture().complete(new CorrelationData.Confirm(ack, null));
            }
        }
    }
}
