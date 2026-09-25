package com.aihub.gateway.meter;

import com.aihub.common.meter.MeteringTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ 投递。三点都是「至少一次」的前提（设计决策 C：至少一次投递 + 幂等消费）：
 * <ol>
 *   <li><b>publisher confirm</b>（{@code spring.rabbitmq.publisher-confirm-type=correlated}）：
 *       只有 broker 确认了才算投递成功，否则落 spool 重投；</li>
 *   <li><b>persistent</b>：broker 重启不丢；</li>
 *   <li><b>mandatory</b>：交换器存在但没绑队列时能被 {@code ReturnsCallback} 发现 ——
 *       否则 broker 会**静默丢弃**这条消息，客户端毫无察觉。
 *       <br>注意：被退回的消息 broker **照样 ack**（只是另发一条 {@code basic.return} 并把消息挂到
 *       {@code CorrelationData} 上），所以「confirm 是 ack」并不等于「投递成功」，
 *       {@link #send} 必须同时检查 {@link CorrelationData#getReturned()}，否则不可路由会被误报成功、
 *       事件永远不会落 spool。</li>
 * </ol>
 *
 * <p>本方法**阻塞**（等 confirm），因此只能在 {@code MeteringDispatcher} 的守护线程或调度线程上调用，
 * 绝不允许在 event loop 上调用。
 *
 * <p>不声明任何拓扑：唯一的声明方是 admin（见计划「决策登记」第 4 条）。admin 还没起过时，
 * 交换器不存在 → 投递失败 → 落 spool → 等 admin 起来后由定时任务重投，这正是我们要的降级行为。
 */
public final class RabbitMeteringTransport implements MeteringTransport {

    private static final Logger log = LoggerFactory.getLogger(RabbitMeteringTransport.class);

    private final RabbitTemplate template;
    private final long confirmTimeoutMs;

    public RabbitMeteringTransport(RabbitTemplate template, long confirmTimeoutMs) {
        this.template = template;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.template.setMandatory(true);
        this.template.setReturnsCallback(returned -> log.error(
                "计量事件不可路由，已被 broker 退回（没人消费）: exchange={} routingKey={} replyCode={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode()));
    }

    @Override
    public boolean send(String payload) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            template.convertAndSend(MeteringTopology.EXCHANGE, MeteringTopology.ROUTING_KEY, payload,
                    message -> {
                        message.getMessageProperties().setContentType(MeteringTopology.MESSAGE_CONTENT_TYPE);
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return message;
                    },
                    correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            // 被退回的消息同样算投递失败：mandatory + publisher-returns 下 broker 对**不可路由**
            // （交换器在、没人绑队列）的消息照样 ack，只是另发一条 basic.return 并把消息挂到
            // CorrelationData 上。只看 confirm 会把这种消息报成成功 → 事件永不落 spool，
            // 恰好是 mandatory 想暴露的那个失败模式。
            ReturnedMessage returned = correlation.getReturned();
            if (confirm == null || !confirm.isAck() || returned != null) {
                if (returned != null) {
                    // 与「broker 拒绝（nack）」区分开：这一条说明交换器在、只是没人绑队列。
                    log.error("计量事件不可路由，已被 broker 退回（confirm 虽为 ack 但没人收）: "
                                    + "exchange={} routingKey={} replyCode={} replyText={}",
                            returned.getExchange(), returned.getRoutingKey(),
                            returned.getReplyCode(), returned.getReplyText());
                } else {
                    log.error("broker 未确认计量事件: {}",
                            confirm == null ? "没有确认结果" : confirm.getReason());
                }
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.error("投递计量事件失败（将转落磁盘 spool）: {}", e.toString());
            return false;
        }
    }
}
