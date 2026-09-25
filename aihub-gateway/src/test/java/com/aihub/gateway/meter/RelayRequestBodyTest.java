package com.aihub.gateway.meter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求体的**唯一**允许改写点（计划「决策登记」第 10 条）：只有 {@code stream:true} 且还没要求
 * {@code include_usage} 时才注入该字段。其余情况必须逐字节转发 —— M1 的
 * {@code forwardsRequestBodyVerbatim} 正是靠这条不变式活着。
 */
class RelayRequestBodyTest {

    @Test
    void nonStreamingBodyIsForwardedByteForByte() {
        String body = "{\"stream\":false,\"model\":\"deepseek-chat\",\"messages\":[]}";

        RelayRequestBody.Prepared prepared = RelayRequestBody.prepare(body);

        assertThat(prepared.bodyToForward()).isEqualTo(body);
        assertThat(prepared.streaming()).isFalse();
        assertThat(prepared.model()).isEqualTo("deepseek-chat");
    }

    @Test
    void streamingBodyGetsIncludeUsageInjected() {
        RelayRequestBody.Prepared prepared =
                RelayRequestBody.prepare("{\"stream\":true,\"model\":\"m\",\"messages\":[]}");

        assertThat(prepared.streaming()).isTrue();
        assertThat(prepared.bodyToForward()).contains("\"include_usage\":true");
        assertThat(prepared.bodyToForward()).contains("\"stream\":true");
        assertThat(prepared.bodyToForward()).contains("\"model\":\"m\"");
    }

    @Test
    void streamingBodyThatAlreadyRequestsUsageIsUnchanged() {
        String body = "{\"stream\":true,\"stream_options\":{\"include_usage\":true}}";

        assertThat(RelayRequestBody.prepare(body).bodyToForward()).isEqualTo(body);
    }

    /** 解析不了就原样转发（fail-open）：宁可拿不到 usage，也不能让请求失败。 */
    @Test
    void malformedJsonIsForwardedUnchanged() {
        String body = "{\"stream\":true, oops";

        RelayRequestBody.Prepared prepared = RelayRequestBody.prepare(body);

        assertThat(prepared.bodyToForward()).isEqualTo(body);
        assertThat(prepared.streaming()).isFalse();
        assertThat(prepared.model()).isNull();
    }

    /**
     * 决策 10 的 fail-open 边界：JSON **后面还跟着别的字节**时必须按「解析失败」处理，原样转发。
     * <p>默认的宽松 Jackson 会把 {@code {"stream":true,...} garbage} 解析成对象，于是走「流式注入」分支
     * 重新序列化 —— 尾部那些字节被**静默丢掉**，请求体被改写了，而计划里说过只有
     * {@code stream_options.include_usage} 这一种改写、且解析不了就必须逐字节转发。
     */
    @Test
    void bodyWithTrailingTokensIsForwardedUnchanged() {
        String body = "{\"stream\":true,\"messages\":[]} garbage";

        RelayRequestBody.Prepared prepared = RelayRequestBody.prepare(body);

        assertThat(prepared.bodyToForward()).isEqualTo(body);
        assertThat(prepared.streaming()).isFalse();
        assertThat(prepared.model()).isNull();

        // 反向界线：行尾空白**不是**尾部残留（查尾部 token 时会跳过空白）。否则打开严格解析就会把
        // 合法的流式请求推进 fail-open，静默丢掉 usage —— 那是「把能计量的请求变得不能计量」。
        assertThat(RelayRequestBody.prepare("{\"stream\":true,\"messages\":[]}\n").streaming())
                .as("行尾空白不得让合法流式请求失去 include_usage 注入")
                .isTrue();
    }

    @Test
    void extractsTheModelFromTheBody() {
        assertThat(RelayRequestBody.prepare("{\"model\":\"deepseek-reasoner\"}").model())
                .isEqualTo("deepseek-reasoner");
        assertThat(RelayRequestBody.prepare("{\"stream\":false}").model()).isNull();
        assertThat(RelayRequestBody.prepare("{\"model\":123}").model()).isNull();
    }

    @Test
    void blankBodyIsForwardedUnchanged() {
        assertThat(RelayRequestBody.prepare("").bodyToForward()).isEmpty();
        assertThat(RelayRequestBody.prepare(null).bodyToForward()).isNull();
    }
}
