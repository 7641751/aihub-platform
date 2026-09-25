package com.aihub.admin.metering;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.common.meter.MeteringEventCodec;
import com.aihub.common.meter.MeteringTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MQ → 落库的端到端链路，用的是真 RabbitMQ 与真 MySQL（Testcontainers）。
 * 幂等与死信这两条只能这样测：内存假实现测不到 broker 的 reject/DLX 行为与 MySQL 的唯一键。
 */
class MeteringConsumerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static MeteringEvent event(String requestId, long createdAtMillis) {
        return new MeteringEvent(requestId, 7L, null, null, "deepseek-chat",
                12, 34, 46, 1200, 250, MeteringEvent.STATUS_SUCCESS, null, createdAtMillis);
    }

    private void send(String payload) {
        rabbitTemplate.convertAndSend(MeteringTopology.EXCHANGE, MeteringTopology.ROUTING_KEY, payload);
    }

    private Integer rows(String requestId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from request_log where request_id = ?", Integer.class, requestId);
    }

    /** 轮询等结果：MQ 是异步的，固定 sleep 会既慢又不稳。 */
    private void awaitRows(String requestId, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (rows(requestId) >= expected) {
                return;
            }
            Thread.sleep(100);
        }
    }

    @Test
    void meteringEventOverTheQueueLandsInRequestLog() throws Exception {
        String requestId = "req-mq-1";
        long createdAtMillis = 1_800_000_000_123L;   // 2027-01-15T08:00:00.123Z
        send(MeteringEventCodec.encode(event(requestId, createdAtMillis)));

        awaitRows(requestId, 1, Duration.ofSeconds(10));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select tenant_id, model, prompt_tokens, completion_tokens, total_tokens,"
                        + " latency_ms, ttft_ms, status, created_at from request_log where request_id = ?",
                requestId);
        assertThat(row.get("tenant_id")).isEqualTo(7L);
        assertThat(row.get("model")).isEqualTo("deepseek-chat");
        assertThat(row.get("prompt_tokens")).isEqualTo(12);
        assertThat(row.get("completion_tokens")).isEqualTo(34);
        assertThat(row.get("total_tokens")).isEqualTo(46);
        assertThat(row.get("latency_ms")).isEqualTo(1200);
        assertThat(row.get("ttft_ms")).isEqualTo(250);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        // UTC 墙上时间必须等于事件里的 epoch 毫秒按 UTC 换算出的值（断言里不出现 JVM 默认时区），
        // 且必须取**事件**里的值 —— 消费端用 now() 会让这一行变成「消费时刻」，本断言立刻红。
        //
        // 期望值用 LocalDateTime 而不是 brief 里的 Timestamp：本项目的 mysql-connector-j 9.7.0
        // 在 queryForMap 里对 DATETIME(3) 返回 LocalDateTime（实测，见 task-8-report.md），
        // 用 Timestamp 断言是**类型**失配（值其实是对的），会让用例永远红。Task 8 已踩过同一个坑。
        LocalDateTime expectedCreatedAt =
                LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAtMillis), ZoneOffset.UTC);
        assertThat(expectedCreatedAt).isEqualTo(LocalDateTime.parse("2027-01-15T08:00:00.123"));
        assertThat(row.get("created_at")).isEqualTo(expectedCreatedAt);
    }

    /**
     * 至少一次投递 + 幂等消费：同一份载荷投两次，库里只能有一行。
     * 反证：消费端若用 {@code now()} 生成 created_at，本用例会看到两行（Task 8 已用服务级用例证明）。
     */
    @Test
    void theSameEventTwiceInsertsExactlyOneRow() throws Exception {
        String requestId = "req-mq-idempotent";
        String payload = MeteringEventCodec.encode(event(requestId, 1_800_000_000_999L));

        send(payload);
        awaitRows(requestId, 1, Duration.ofSeconds(10));
        send(payload);
        Thread.sleep(2_000);

        assertThat(rows(requestId)).isEqualTo(1);
    }

    /**
     * 垃圾载荷**不许被静默 ACK**：它必须重试 3 次然后进死信队列，等人排查。
     * 反证：把消费者改成「解码失败就 log + return」→ 本用例拿不到死信消息。
     */
    @Test
    void undecodablePayloadEndsUpInTheDeadLetterQueue() throws Exception {
        drainDeadLetterQueue();
        String garbage = "this-is-not-a-metering-event";

        send(garbage);

        Message dead = receiveFromDeadLetterQueue(Duration.ofSeconds(15));
        assertThat(dead).as("解不开的载荷必须进死信队列，而不是被静默 ACK").isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(garbage);

        // 仅「DLQ 里有一条消息」还不够：要证明它是被 **broker 死信**过来的。x-death 由 DLX 转发时写入——
        // reason=rejected 钉住「拒绝且不 requeue」（default-requeue-rejected=false），
        // queue 钉住它先经过了业务队列（而不是被谁直接投进 DLQ）。
        // 反证：把 default-requeue-rejected 改成 true、或摘掉业务队列的 x-dead-letter-*，本用例立刻红。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> xDeath =
                (List<Map<String, Object>>) dead.getMessageProperties().getHeaders().get("x-death");
        assertThat(xDeath).as("死信消息必须带 broker 写入的 x-death 头").isNotNull().isNotEmpty();
        assertThat(xDeath.get(0)).containsEntry("reason", "rejected");
        assertThat(xDeath.get(0)).containsEntry("queue", MeteringTopology.QUEUE);
    }

    private void drainDeadLetterQueue() {
        while (rabbitTemplate.receive(MeteringTopology.DEAD_LETTER_QUEUE, 200) != null) {
            // 清空历史遗留，避免误判
        }
    }

    private Message receiveFromDeadLetterQueue(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Message message = rabbitTemplate.receive(MeteringTopology.DEAD_LETTER_QUEUE, 500);
            if (message != null) {
                return message;
            }
        }
        return null;
    }
}
