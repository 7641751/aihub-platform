package com.aihub.common.meter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计量事件的线格式是 gateway（发布）与 admin（消费）之间的**跨服务契约**：
 * 两侧共用 {@link MeteringEventCodec}，任何一侧私自改字段顺序或转义规则都不会编译报错，
 * 只会表现为「消息能收、内容全错」。因此把固定向量与畸形载荷都压在这里。
 * <p>本类不需要 Spring 上下文，也不需要 Docker。
 */
class MeteringEventCodecTest {

    private static MeteringEvent fullyPopulated() {
        return new MeteringEvent(
                "11111111-2222-3333-4444-555555555555",
                7L, 42L, 99L, "deepseek-chat",
                12, 34, 46, 1200, 250,
                MeteringEvent.STATUS_SUCCESS, null,
                1_800_000_000_123L);
    }

    @Test
    void encodingProducesThePinnedFieldOrder() {
        // 13 段，顺序固定：requestId|tenantId|apiKeyId|channelId|model|prompt|completion|total|latency|ttft|status|errorCode|createdAtEpochMilli
        assertThat(MeteringEventCodec.encode(fullyPopulated())).isEqualTo(
                "11111111-2222-3333-4444-555555555555|7|42|99|deepseek-chat|12|34|46|1200|250|SUCCESS||1800000000123");
    }

    @Test
    void roundTripsAFullyPopulatedEvent() {
        assertThat(MeteringEventCodec.decode(MeteringEventCodec.encode(fullyPopulated())))
                .isEqualTo(fullyPopulated());
    }

    @Test
    void roundTripsEventsWithNullOptionalFields() {
        MeteringEvent event = new MeteringEvent("r-1", 0L, null, null, null,
                0, 0, 0, 5, null, MeteringEvent.STATUS_CANCELLED,
                MeteringEvent.ERROR_CLIENT_DISCONNECTED, 1_800_000_000_000L);

        String payload = MeteringEventCodec.encode(event);

        assertThat(payload).isEqualTo("r-1|0||||0|0|0|5||CANCELLED|client_disconnected|1800000000000");
        assertThat(MeteringEventCodec.decode(payload)).isEqualTo(event);
        assertThat(MeteringEventCodec.decode(payload).model()).isNull();
        assertThat(MeteringEventCodec.decode(payload).ttftMs()).isNull();
    }

    /**
     * 唯一真实的风险点：字段里出现分隔符 / 反斜杠 / 换行。换行尤其重要 ——
     * 磁盘 spool 里一条事件就是一个文件，但日志与人工排查时经常按行处理，
     * 转义漏掉 {@code \n} 就会让「一条事件」在文本视角下裂成两条。
     */
    @Test
    void roundTripsFieldsContainingDelimiterBackslashAndNewlines() {
        for (String model : List.of("a|b", "a\\b", "a\\|b", "line\nbreak", "cr\rlf", "\n", "\r", "\\|", "||||", "a\r\nb")) {
            MeteringEvent event = new MeteringEvent("r-" + model.hashCode(), 1L, null, null, model,
                    1, 2, 3, 4, null, MeteringEvent.STATUS_SUCCESS, model, 1L);

            String payload = MeteringEventCodec.encode(event);

            assertThat(payload).as("payload for model %s must stay single-line", model)
                    .doesNotContain("\n").doesNotContain("\r");
            assertThat(MeteringEventCodec.decode(payload))
                    .as("round-trip of model %s (payload %s)", model, payload)
                    .isEqualTo(event);
        }
    }

    @Test
    void decodeRejectsMalformedPayloads() {
        assertThat(MeteringEventCodec.decode(null)).isNull();
        assertThat(MeteringEventCodec.decode("")).isNull();
        assertThat(MeteringEventCodec.decode("r|1||||0|0|0|0||SUCCESS||1|extra")).isNull();   // 14 段
        assertThat(MeteringEventCodec.decode("r|1||||0|0|0|0||SUCCESS||")).isNull();          // 13 段但最后一段空
        assertThat(MeteringEventCodec.decode("r|1||||0|0|0|0||SUCCESS")).isNull();            // 11 段
        assertThat(MeteringEventCodec.decode("r|not-a-long||||0|0|0|0||SUCCESS||1")).isNull();
        assertThat(MeteringEventCodec.decode("r|1||||0|0|0|not-an-int||SUCCESS||1")).isNull();
        assertThat(MeteringEventCodec.decode("|1||||0|0|0|0||SUCCESS||1")).isNull();          // requestId 空
        assertThat(MeteringEventCodec.decode("r|1||||0|0|0|0||||1")).isNull();                // status 空
    }

    /**
     * 拓扑名字是另一条跨服务契约：admin 声明、gateway 发布。单侧改名不会报任何错，
     * 只会变成「消息永远投不到队列」；固定放在共享模块里并由本用例钉住字面量。
     */
    @Test
    void topologyNamesAreThePinnedCrossServiceContract() {
        assertThat(MeteringTopology.EXCHANGE).isEqualTo("aihub.metering.exchange");
        assertThat(MeteringTopology.ROUTING_KEY).isEqualTo("aihub.metering.usage");
        assertThat(MeteringTopology.QUEUE).isEqualTo("aihub.metering.queue");
        assertThat(MeteringTopology.DEAD_LETTER_EXCHANGE).isEqualTo("aihub.metering.dlx");
        assertThat(MeteringTopology.DEAD_LETTER_ROUTING_KEY).isEqualTo("aihub.metering.dlq");
        assertThat(MeteringTopology.DEAD_LETTER_QUEUE).isEqualTo("aihub.metering.dlq");
        assertThat(MeteringTopology.MESSAGE_CONTENT_TYPE).isEqualTo("text/plain;charset=UTF-8");
    }

    @Test
    void upstreamHttpErrorUsesTheUpstreamStatusCode() {
        assertThat(MeteringEvent.upstreamHttpError(429)).isEqualTo("upstream_http_429");
        assertThat(MeteringEvent.upstreamHttpError(401)).isEqualTo("upstream_http_401");
        // VARCHAR(64)：最长也只有 "upstream_http_" + 3 位数字，不可能超列宽。
        assertThat(MeteringEvent.upstreamHttpError(503)).hasSizeLessThan(64);
    }

    @Test
    void createdAtIsExposedAsAnInstantInUtc() {
        MeteringEvent event = new MeteringEvent("r", 1L, null, null, "m", 0, 0, 0, 0, null,
                MeteringEvent.STATUS_SUCCESS, null, 1_800_000_000_123L);

        assertThat(event.createdAt()).isEqualTo(Instant.parse("2027-01-15T08:00:00.123Z"));
    }
}
