package com.aihub.gateway.meter;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.auth.ApiKeyAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.SignalType;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「上游发生了什么」→「request_log 那一行长什么样」的映射表。压在这里是因为它最容易写错，
 * 而错误的表现是「账单悄悄少了/多了」，线上极难发现。
 */
class RelayMeteringTest {

    private static final DefaultDataBufferFactory FACTORY = new DefaultDataBufferFactory();

    private static MockServerWebExchange exchangeWithTenant(Long tenantId) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions").build());
        if (tenantId != null) {
            exchange.getAttributes().put(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW,
                    new ApiKeyView("ak_1", tenantId, "demo", ApiKeyView.STATUS_ACTIVE, null));
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

    @Test
    void tenantComesFromTheKeyViewWhileIdsStayNull() {
        MeteringEvent withKey = meteringFor(exchangeWithTenant(7L), false).toEvent(SignalType.ON_COMPLETE);
        MeteringEvent withoutKey = meteringFor(exchangeWithTenant(null), false).toEvent(SignalType.ON_COMPLETE);

        assertThat(withKey.tenantId()).isEqualTo(7L);
        assertThat(withoutKey.tenantId()).isEqualTo(RelayMetering.TENANT_UNKNOWN);
        assertThat(withKey.apiKeyId()).isNull();
        assertThat(withKey.channelId()).isNull();
        assertThat(withKey.createdAtEpochMilli() % 1_000L).isGreaterThanOrEqualTo(0L);
    }
}
