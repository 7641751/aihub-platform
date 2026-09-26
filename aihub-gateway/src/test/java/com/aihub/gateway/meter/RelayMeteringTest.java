package com.aihub.gateway.meter;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.auth.ApiKeyAuthFilter;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.relay.RelayAttempts;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.SignalType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「上游发生了什么」→「request_log 那一行长什么样」的映射表。压在这里是因为它最容易写错，
 * 而错误的表现是「账单悄悄少了/多了」，线上极难发现。
 */
class RelayMeteringTest {

    private static final DefaultDataBufferFactory FACTORY = new DefaultDataBufferFactory();

    private static MockServerWebExchange exchangeWithTenant(Long tenantId) {
        return exchangeWithView(tenantId == null
                ? null
                : new ApiKeyView("ak_1", tenantId, "demo", ApiKeyView.STATUS_ACTIVE, null, 42L));
    }

    private static MockServerWebExchange exchangeWithView(ApiKeyView view) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions").build());
        if (view != null) {
            exchange.getAttributes().put(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW, view);
        }
        return exchange;
    }

    private static DataBuffer buffer(String value) {
        return FACTORY.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static RelayMetering meteringFor(MockServerWebExchange exchange, boolean streaming) {
        return RelayMetering.start(exchange, "req-1", "deepseek-chat", streaming, 4096);
    }

    @Test
    void successfulNonStreamingRequestProducesSuccessWithExactUsage() {
        RelayMetering metering = meteringFor(exchangeWithTenant(7L), false);

        metering.onUpstreamStatus(HttpStatus.OK);
        metering.onChunk(buffer(UsageExtractorTest.COMPLETION_JSON));
        MeteringEvent event = metering.toEvent(SignalType.ON_COMPLETE);

        assertThat(event.requestId()).isEqualTo("req-1");
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_SUCCESS);
        assertThat(event.errorCode()).isNull();
        assertThat(event.promptTokens()).isEqualTo(9);
        assertThat(event.completionTokens()).isEqualTo(2);
        assertThat(event.totalTokens()).isEqualTo(11);
        assertThat(event.model()).isEqualTo("deepseek-chat");
        // 非流式没有「首字延迟」：不许编造，列可空（计划「决策登记」第 6 条）。
        assertThat(event.ttftMs()).isNull();
        assertThat(event.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void twoHundredWithoutUsageProducesUsageMissing() {
        RelayMetering metering = meteringFor(exchangeWithTenant(7L), true);

        metering.onUpstreamStatus(HttpStatus.OK);
        metering.onChunk(buffer("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"));
        MeteringEvent event = metering.toEvent(SignalType.ON_COMPLETE);

        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_SUCCESS);
        assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_USAGE_MISSING);
        assertThat(event.completionTokens()).isEqualTo(2);   // 估算值
        assertThat(event.ttftMs()).isNotNull();
    }

    @Test
    void non2xxUpstreamProducesErrorWithUpstreamHttpCode() {
        RelayMetering metering = meteringFor(exchangeWithTenant(7L), false);

        metering.onUpstreamStatus(HttpStatus.TOO_MANY_REQUESTS);
        metering.onChunk(buffer("{\"error\":{\"message\":\"rate limited\"}}"));
        MeteringEvent event = metering.toEvent(SignalType.ON_COMPLETE);

        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_ERROR);
        assertThat(event.errorCode()).isEqualTo("upstream_http_429");
        // 错误 body 里的文本不是模型回答，绝不能用它估算 token。
        assertThat(event.totalTokens()).isZero();
    }

    @Test
    void unreachableUpstreamProducesErrorWithUnreachableCode() {
        RelayMetering metering = meteringFor(exchangeWithTenant(7L), false);

        metering.onUpstreamFailure(MeteringEvent.ERROR_UPSTREAM_UNREACHABLE);
        MeteringEvent event = metering.toEvent(SignalType.ON_COMPLETE);

        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_ERROR);
        assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_UPSTREAM_UNREACHABLE);
        assertThat(event.promptTokens()).isZero();
    }

    @Test
    void cancelledRequestProducesCancelledWithEstimatedTokens() {
        RelayMetering metering = meteringFor(exchangeWithTenant(7L), true);

        metering.onChunk(buffer("data: {\"choices\":[{\"delta\":{\"content\":\"你好你好\"}}]}\n\n"));
        MeteringEvent event = metering.toEvent(SignalType.CANCEL);

        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_CANCELLED);
        assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_CLIENT_DISCONNECTED);
        assertThat(event.completionTokens()).isEqualTo(3);   // ceil(4 * 0.6) = 3
    }

    @Test
    void unexpectedErrorProducesGatewayError() {
        RelayMetering metering = meteringFor(exchangeWithTenant(7L), false);

        metering.onUpstreamStatus(HttpStatus.OK);
        metering.onUnexpectedError();
        MeteringEvent event = metering.toEvent(SignalType.ON_ERROR);

        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_ERROR);
        assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_GATEWAY);
    }

    /**
     * M2 决策 8 留下的缺口（{@code api_key_id} 恒为 NULL）在 Task 11 关闭：数值主键取自
     * {@code ApiKeyView.apiKeyId()}（Task 2 就已就位的共享契约）。
     *
     * <p>{@code channel_id} 在这里仍然是 NULL —— 本用例没有经过控制器，**没有任何候选被选中**。
     * 「谁服务了这次请求」只能由 {@code onChannelSelected} 给出（见下面那条故障转移用例）。
     */
    @Test
    void tenantAndNumericApiKeyComeFromTheKeyViewWhileChannelAwaitsRouting() {
        RelayMetering metering = meteringFor(exchangeWithTenant(7L), false);
        MeteringEvent withKey = metering.toEvent(SignalType.ON_COMPLETE);
        MeteringEvent withoutKey = meteringFor(exchangeWithTenant(null), false).toEvent(SignalType.ON_COMPLETE);
        // 决策 2：幂等键的两半各只生成一次。用**同一个**请求状态再组装一次事件，
        // 就模拟了重投/重放（投递失败后重发同一个对象）—— 两次必须是逐字相同的两个值。
        MeteringEvent replayed = metering.toEvent(SignalType.ON_COMPLETE);

        assertThat(withKey.tenantId()).isEqualTo(7L);
        assertThat(withoutKey.tenantId()).isEqualTo(RelayMetering.TENANT_UNKNOWN);
        assertThat(withKey.apiKeyId())
                .as("api_key_id 必须来自鉴权视图的数值主键，而不是发明一个值")
                .isEqualTo(42L);
        assertThat(withKey.channelId()).as("没有候选被选中时 channel_id 保持 NULL").isNull();
        assertThat(withoutKey.apiKeyId()).as("鉴权关闭（没有视图）时 api_key_id 保持 NULL").isNull();

        // created_at 必须是「毫秒单位的、接近当前时刻」的值：写成 0 / 秒 / 每次组装重新采样都会变红。
        assertThat(Math.abs(withKey.createdAtEpochMilli() - System.currentTimeMillis()))
                .as("created_at 必须接近当前时刻（且单位是毫秒而不是秒/微秒）")
                .isLessThan(5_000L);
        assertThat(replayed.requestId()).isEqualTo(withKey.requestId());
        assertThat(replayed.createdAtEpochMilli()).isEqualTo(withKey.createdAtEpochMilli());

        // 「截断到毫秒」在事件边界上唯一可观测的口径：带 123456789ns 亚毫秒部分的时刻，
        // 落到事件里只剩 .123 —— 亚毫秒部分不得进入事件（旧的 % 1_000 >= 0 是恒真断言，钉不住任何东西）。
        Instant withNanos = Instant.parse("2026-09-25T10:15:30.123456789Z");
        MeteringEvent truncated = new RelayMetering("req-nanos", withNanos, 7L, null, "deepseek-chat",
                UsageCapture.start(false, 4096)).toEvent(SignalType.ON_COMPLETE);
        assertThat(truncated.createdAtEpochMilli()).isEqualTo(withNanos.toEpochMilli());
        assertThat(truncated.createdAtEpochMilli() % 1_000L)
                .as("毫秒位必须是输入时刻的毫秒位（实现若按微秒/纳秒写入，这里会是 0）")
                .isEqualTo(123L);
    }

    /**
     * 视图存在但**没有**数值主键（契约允许的 {@code null}，例如 Task 2 之前铸造的 key）时，
     * {@code api_key_id} 保持 NULL：宁可空着，也不能编一个 id 让用量聚合到别人头上。
     */
    @Test
    void apiKeyIdStaysNullWhenTheViewHasNoNumericId() {
        RelayMetering metering = meteringFor(exchangeWithView(
                new ApiKeyView("ak_1", 7L, "demo", ApiKeyView.STATUS_ACTIVE, null, null)), false);

        MeteringEvent event = metering.toEvent(SignalType.ON_COMPLETE);

        assertThat(event.tenantId()).as("租户维度仍然有值").isEqualTo(7L);
        assertThat(event.apiKeyId()).isNull();
    }

    /**
     * 决策 15：超长 model **只**在计量事件里被截断（{@code request_log.model} 是 {@code VARCHAR(128)}），
     * 否则这一行 INSERT 会失败 → 重投 → 死信队列，而 DLQ 是任何持合法 API key 的客户端都能触碰的入口。
     *
     * <p>事件里剩下的是**前缀**（前 128 个字符），不是摘要也不是空值 —— 前缀才能让人在库里认出是哪个模型。
     * 截断只发生在这里：转发给上游的请求体由 {@code RelayRequestBody} 原样保留（端到端证据见
     * {@code RelayMeteringFlowTest#oversizedModelIsTruncatedInTheEventButForwardedVerbatim}）。
     */
    @Test
    void oversizedModelIsTruncatedToTheColumnWidthInTheEvent() {
        String longModel = "m".repeat(200);

        MeteringEvent event = RelayMetering.start(exchangeWithTenant(7L), "req-1", longModel, false, 4096)
                .toEvent(SignalType.ON_COMPLETE);

        assertThat(RelayAttempts.MODEL_MAX_LENGTH)
                .as("必须与 V1 的 model VARCHAR(128) 一致")
                .isEqualTo(128);
        assertThat(event.model()).hasSize(RelayAttempts.MODEL_MAX_LENGTH);
        assertThat(event.model()).isEqualTo(longModel.substring(0, RelayAttempts.MODEL_MAX_LENGTH));
        // 边界之内逐字不变（截断不能顺手改动合法模型名）。
        assertThat(meteringFor(exchangeWithTenant(7L), false).toEvent(SignalType.ON_COMPLETE).model())
                .isEqualTo("deepseek-chat");
    }

    /**
     * 多渠道（M3）起事件里必须带上**实际服务**的那条渠道。判别性在于第二次选择：故障转移会在一次
     * 请求里走过好几条候选，若实现用 {@code compareAndSet(null, ...)}（或只记首选），事件里留下的是
     * **首选**那条（11），而真正产生响应的那条是 12 —— {@code request_log.channel_id} 从此与事实不符。
     */
    @Test
    void theChannelThatServedTheRequestIsCarriedIntoTheEvent() {
        RelayMetering metering = meteringFor(exchangeWithTenant(7L), false);

        metering.onChannelSelected(channel(11L, "primary"));
        assertThat(metering.toEvent(SignalType.ON_COMPLETE).channelId()).isEqualTo(11L);

        metering.onChannelSelected(channel(12L, "standby"));

        assertThat(metering.toEvent(SignalType.ON_COMPLETE).channelId())
                .as("记录的是最后真正被调用（并产生响应）的那条候选，不是最先被选中的那条")
                .isEqualTo(12L);
    }

    /** 遗留单渠道的哨兵 id 也写进事件（不是 NULL）：这样「走了兜底路径」在 request_log 里可查。 */
    @Test
    void theLegacySentinelChannelIsRecordedRatherThanLeftNull() {
        RelayMetering metering = meteringFor(exchangeWithTenant(7L), false);

        metering.onChannelSelected(LegacyChannel.of(
                new UpstreamProperties("http://127.0.0.1:11434", "", "m")));

        assertThat(metering.toEvent(SignalType.ON_COMPLETE).channelId()).isEqualTo(LegacyChannel.ID);
    }

    private static ChannelDescriptor channel(long id, String name) {
        return new ChannelDescriptor(id, name, "https://ch" + id + ".example.com", "v1:QUJD", 1, 5_000,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }
}
