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
