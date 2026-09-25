package com.aihub.gateway.meter;

import com.aihub.common.meter.MeteringTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * confirm / mandatory / 持久化这三件事只能在**发布端**保证，而网关测试不允许起 broker。
 * 因此这里用 {@link RabbitTemplate} 的子类顶替真实 broker：手工完成或**故意不完成**
 * {@code CorrelationData} 的 future，就能覆盖「nack」「无确认（超时）」「连接异常」三种失败；
 * 再手工 {@code setReturned(...)}，就能覆盖第四种 —— ack 但不可路由（被 basic.return 退回）。
 */
class RabbitMeteringTransportTest {

    /** 假 broker：可以 ack / nack / 退回（unroutable）/ 抛连接异常 / 永不确认，并记录被投递的消息。 */
    static final class FakeRabbitTemplate extends RabbitTemplate {

        private final List<String> deliveries = new ArrayList<>();
        private boolean ack = true;
        private boolean throwOnSend;
        private boolean neverConfirm;
        private boolean returnAsUnroutable;
        private Message processed;

        @Override
        public void convertAndSend(String exchange, String routingKey, Object message,
                                   MessagePostProcessor messagePostProcessor, CorrelationData correlationData) {
            if (throwOnSend) {
                throw new AmqpConnectException(new ConnectException("Connection refused"));
            }
            String body = (String) message;
            deliveries.add(exchange + "|" + routingKey + "|" + body);
            // 故意先给一个 NON_PERSISTENT 的初始消息：否则「断言它持久化」是**空断言** ——
            // MessageProperties 的默认投递模式本来就是 PERSISTENT（实测：把 transport 里的
            // setDeliveryMode 注释掉，断言照样绿）。这样 post-processor 必须真的把它翻成 PERSISTENT。
            processed = messagePostProcessor.postProcessMessage(
                    MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                            .setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT)
                            .build());
            if (returnAsUnroutable) {
                // 真实语义：mandatory + publisher-returns 下 broker 先发 basic.return，Spring 把它挂到
                // CorrelationData 上，然后**照样**回一条 ack。先挂再 ack，避免读到的 confirm 早于 returned。
                correlationData.setReturned(new ReturnedMessage(processed, 312, "NO_ROUTE", exchange, routingKey));
            }
            if (!neverConfirm) {
                correlationData.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
            }
        }
    }

    @Test
    void returnsTrueWhenTheBrokerAcks() {
        FakeRabbitTemplate template = new FakeRabbitTemplate();

        assertThat(new RabbitMeteringTransport(template, 500L).send("payload")).isTrue();
    }

    /**
     * 不可路由（交换器在、没人绑队列）时 broker **照样 ack**，只是另外发一条 basic.return。
     * 只看 confirm 会把这种情况报成投递成功 → 事件永远不会落 spool，而这正是 mandatory
     * 想暴露的那个失败模式（类注释承诺「投递失败 → 落 spool」）。
     */
    @Test
    void returnsFalseWhenTheMessageIsReturnedAsUnroutable() {
        FakeRabbitTemplate template = new FakeRabbitTemplate();
        template.returnAsUnroutable = true;

        assertThat(new RabbitMeteringTransport(template, 500L).send("payload")).isFalse();
    }

    @Test
    void returnsFalseWhenTheBrokerNacks() {
        FakeRabbitTemplate template = new FakeRabbitTemplate();
        template.ack = false;

        assertThat(new RabbitMeteringTransport(template, 500L).send("payload")).isFalse();
    }

    @Test
    void returnsFalseWhenTheSendThrows() {
        FakeRabbitTemplate template = new FakeRabbitTemplate();
        template.throwOnSend = true;

        // 契约是「不抛异常，返回 false」：抛出去会打断投递线程的循环。
        assertThat(new RabbitMeteringTransport(template, 500L).send("payload")).isFalse();
    }

    @Test
    void returnsFalseWhenNoConfirmArrives() {
        FakeRabbitTemplate template = new FakeRabbitTemplate();
        template.neverConfirm = true;

        assertThat(new RabbitMeteringTransport(template, 100L).send("payload")).isFalse();
    }

    @Test
    void marksMessagesPersistentWithTheSharedContentType() {
        FakeRabbitTemplate template = new FakeRabbitTemplate();

        new RabbitMeteringTransport(template, 500L).send("payload");

        assertThat(template.processed.getMessageProperties().getContentType())
                .isEqualTo(MeteringTopology.MESSAGE_CONTENT_TYPE);
        assertThat(template.processed.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        // mandatory：消息不可路由（交换器在但没绑队列）时必须能被发现，而不是被 broker 静默丢掉。
        assertThat(template.isMandatoryFor(template.processed)).isTrue();
    }

    @Test
    void publishesToTheSharedExchangeAndRoutingKey() {
        FakeRabbitTemplate template = new FakeRabbitTemplate();

        new RabbitMeteringTransport(template, 500L).send("payload");

        assertThat(template.deliveries).containsExactly(
                MeteringTopology.EXCHANGE + "|" + MeteringTopology.ROUTING_KEY + "|payload");
    }
}
