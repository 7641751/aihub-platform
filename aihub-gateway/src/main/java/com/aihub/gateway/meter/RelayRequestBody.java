package com.aihub.gateway.meter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 转发前的请求体预检：判断是否流式、取 model、并在**只有流式**时注入
 * {@code stream_options.include_usage=true}（设计文档 §8.1 ⑤ 的明文要求：OpenAI 兼容上游
 * 只在显式要求时才在最后一帧带 usage）。
 *
 * <p>这是 M1「请求体逐字节透传」契约的**唯一例外**，因此规则写死：
 * <ul>
 *   <li>非流式 → 原样（字节不变，M1 的用例继续成立）；</li>
 *   <li>流式且已经要求 usage → 原样；</li>
 *   <li>流式但没要求 → 解析 + 注入 + 重新序列化（**只有这一条会改变字节**）；</li>
 *   <li>解析失败 / body 为空 → 原样转发（fail-open），宁可没有 usage 也不让请求失败。</li>
 * </ul>
 */
public final class RelayRequestBody {

    private static final Logger log = LoggerFactory.getLogger(RelayRequestBody.class);

    /**
     * 本类专用 mapper（**不是**共享的那一个）：只多开严格解析 —— JSON 之后还有别的字节就算解析失败。
     * <p>Jackson 默认允许尾部残留（{@code {"stream":true} garbage} 会被成功解析成对象），那样流式注入分支
     * 会重新序列化并把尾部字节**静默丢掉**，等于在「唯一允许的改写」之外又改了请求体。
     * 打开它只会把更多输入推向 fail-open（原样转发），因此不允许被改写的输入集合只会**变小**。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Prepared(String bodyToForward, boolean streaming, String model) {
    }

    private RelayRequestBody() {
    }

    public static Prepared prepare(String body) {
        if (body == null || body.isBlank()) {
            return new Prepared(body, false, null);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("请求体不是合法 JSON，原样转发（本次无法注入 include_usage）: {}", e.toString());
            return new Prepared(body, false, null);
        }
        if (!root.isObject()) {
            return new Prepared(body, false, null);
        }
        boolean streaming = root.path("stream").asBoolean(false);
        String model = root.path("model").isTextual() ? root.get("model").asText() : null;
        if (!streaming) {
            return new Prepared(body, false, model);
        }
        if (root.path("stream_options").path("include_usage").asBoolean(false)) {
            return new Prepared(body, true, model);
        }
        try {
            JsonNode streamOptions = root.path("stream_options");
            ObjectNode options = streamOptions.isObject()
                    ? (ObjectNode) streamOptions
                    : ((ObjectNode) root).putObject("stream_options");
            options.put("include_usage", true);
            return new Prepared(MAPPER.writeValueAsString(root), true, model);
        } catch (Exception e) {
            log.warn("注入 stream_options.include_usage 失败，原样转发: {}", e.toString());
            return new Prepared(body, true, model);
        }
    }
}
