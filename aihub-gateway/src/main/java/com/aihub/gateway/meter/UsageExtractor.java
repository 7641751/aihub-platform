package com.aihub.gateway.meter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 从**已经捕获到的响应副本**里读 {@code usage} 与增量内容。只在网关侧使用（Jackson 是网关自带依赖，
 * {@code aihub-common} 不能依赖它）。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>绝不抛异常：解析发生在响应已回写之后，抛异常只会变成一条无意义的错误日志；</li>
 *   <li>容忍半截数据：尾部滑窗丢头后第一行必然是残的（见 {@code TailBuffer}），跳过即可；</li>
 *   <li>不改动任何被转发的字节：本类只吃 {@code byte[]} 副本。</li>
 * </ol>
 */
public final class UsageExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";

    /** 上游 usage 的原始三元组（不重算 total：上游说什么就是什么）。 */
    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }

    private UsageExtractor() {
    }

    /** 非流式：整段 body 是一个 JSON 对象。 */
    public static Optional<Usage> fromJsonBody(byte[] body) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        try {
            return usageOf(MAPPER.readTree(body));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * 流式：逐行扫 {@code data:} 帧，取**最后一个**带 usage 的帧（OpenAI 兼容上游把它放在
     * {@code [DONE]} 之前的那一帧）。逐行解析，单行坏掉不影响其它行。
     */
    public static Optional<Usage> fromSse(byte[] body) {
        Usage found = null;
        for (String frame : frames(body)) {
            JsonNode node = parse(frame);
            if (node == null) {
                continue;
            }
            Optional<Usage> usage = usageOf(node);
            if (usage.isPresent()) {
                found = usage.get();
            }
        }
        return Optional.ofNullable(found);
    }

    /** 流式的增量文本（用于估算 completion tokens）。 */
    public static String sseContent(byte[] body) {
        StringBuilder out = new StringBuilder();
        for (String frame : frames(body)) {
            JsonNode node = parse(frame);
            if (node == null) {
                continue;
            }
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            if (content.isTextual()) {
                out.append(content.asText());
            }
        }
        return out.toString();
    }

    /** 非流式的答复文本。 */
    public static String jsonContent(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            JsonNode content = MAPPER.readTree(body).path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static Optional<Usage> usageOf(JsonNode root) {
        if (root == null) {
            return Optional.empty();
        }
        JsonNode usage = root.path("usage");
        if (!usage.isObject()) {
            return Optional.empty();
        }
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(0);
        if (prompt == 0 && completion == 0 && total == 0) {
            // 空壳 usage：当作没有，交给估算兜底（记 0 会丢掉「有内容但没用量」这个事实）。
            return Optional.empty();
        }
        return Optional.of(new Usage(prompt, completion, total));
    }

    /** 逐行取 {@code data:} 之后的载荷；{@code [DONE]} 与空行跳过。半截行会解析失败并被跳过。 */
    private static Iterable<String> frames(byte[] body) {
        java.util.List<String> frames = new java.util.ArrayList<>();
        if (body == null || body.length == 0) {
            return frames;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (!line.startsWith(SSE_DATA_PREFIX)) {
                continue;
            }
            String payload = line.substring(SSE_DATA_PREFIX.length()).strip();
            if (payload.isEmpty() || SSE_DONE.equals(payload)) {
                continue;
            }
            frames.add(payload);
        }
        return frames;
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }
}
