package com.aihub.gateway.meter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析发生在**响应已经回写客户端之后**（我们只读捕获到的副本），因此任何解析失败都绝不能抛异常 ——
 * 尤其「尾部滑窗丢掉了开头，第一行是半截 JSON」这种情况是设计内的正常状态。
 */
class UsageExtractorTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static final String COMPLETION_JSON = """
            {"id":"chatcmpl-1","object":"chat.completion",\
            "choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],\
            "usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}""";

    static final String STREAMING_SSE = """
            data: {"id":"1","choices":[{"index":0,"delta":{"content":"你"}}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"content":"好"}}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}

            data: [DONE]

            """;

    @Test
    void readsUsageFromANonStreamingBody() {
        assertThat(UsageExtractor.fromJsonBody(bytes(COMPLETION_JSON)))
                .contains(new UsageExtractor.Usage(9, 2, 11));
    }

    @Test
    void readsUsageFromTheFinalSseFrame() {
        assertThat(UsageExtractor.fromSse(bytes(STREAMING_SSE)))
                .contains(new UsageExtractor.Usage(9, 2, 11));
    }

    @Test
    void returnsEmptyWhenUsageIsAbsent() {
        assertThat(UsageExtractor.fromSse(bytes("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n")))
                .isEmpty();
        assertThat(UsageExtractor.fromJsonBody(bytes("{\"choices\":[]}"))).isEmpty();
        assertThat(UsageExtractor.fromJsonBody(bytes("not json"))).isEmpty();
        assertThat(UsageExtractor.fromJsonBody(new byte[0])).isEmpty();
    }

    /** 全 0 的 usage 视为「没有 usage」：这是上游发了个空壳，估算比记 0 更有信息量。 */
    @Test
    void treatsAnAllZeroUsageAsAbsent() {
        assertThat(UsageExtractor.fromJsonBody(bytes("{\"usage\":{}}"))).isEmpty();
        assertThat(UsageExtractor.fromJsonBody(
                bytes("{\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}")))
                .isEmpty();
    }

    /** 尾部滑窗丢头之后的真实形态：第一行是半截 JSON。必须跳过它、继续解后面的行。 */
    @Test
    void skipsAPartialFirstLine() {
        // 半截首行里带着一组**不同**的 usage（9/9/18）：若实现去抢救残行，下面的断言会拿到 9/9/18 而不是 1/2/3。
        String truncated = "hoices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":9,\"total_tokens\":18}}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}\n\n"
                + "data: [DONE]\n\n";

        assertThat(UsageExtractor.fromSse(bytes(truncated)))
                .contains(new UsageExtractor.Usage(1, 2, 3));
    }

    @Test
    void concatenatesSseDeltaContent() {
        assertThat(UsageExtractor.sseContent(bytes(STREAMING_SSE))).isEqualTo("你好");
    }

    @Test
    void ignoresGarbageFramesWithoutThrowing() {
        String garbage = "data: {oops\n\ndata: [DONE]\n\nnot-a-data-line\n";

        assertThat(UsageExtractor.fromSse(bytes(garbage))).isEmpty();
        assertThat(UsageExtractor.sseContent(bytes(garbage))).isEmpty();
        assertThat(UsageExtractor.jsonContent(bytes("}{"))).isEmpty();
    }
}
