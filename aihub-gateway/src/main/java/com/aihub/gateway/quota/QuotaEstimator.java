package com.aihub.gateway.quota;

import com.aihub.gateway.meter.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 预扣额度的估算：**估算 prompt token + max_tokens**（设计文档 §6.2 原文）。
 *
 * <p><b>复用 {@link TokenEstimator}，不新写一份（D17 明文）</b>：同一份内容在「预扣」与
 * 「拿不到 usage 时的计量」两条路径上必须算出同一个数。两份估算器会漂移，而对账会把它报成
 * 「Redis 与 MySQL 的偏差」—— 一个由我们自己制造、永远查不出根因的告警。
 *
 * <p><b>{@link TokenEstimator} 的既有签名是 {@code static int estimate(String content)}</b>
 * （单参、返回 int，口径：1 个汉字 ≈ 0.6 token、其它字符 ≈ 0.3 token，向上取整）。
 * 它估算的是**内容文本**，不是「一次调用」。因此本类必须自己从请求体里取出 user 文本
 * （{@code messages[].content}，兼容字符串与 OpenAI 的多模态分片数组）再交给它 —— 复用它的
 * **估算法**，而不是假设它已经能估算一次调用。
 *
 * <p><b>估算口径故意偏保守（偏大）</b>：多压一点，收尾时退回（{@code QuotaLimiter#adjust}）。
 * 反过来（偏小）会超发，而超发是**不可逆**的（钱已经花出去了）。
 *
 * <p>解析失败 / 结构不符时**回落到「整段 body 当作内容」**：那是一个确定的上界近似，绝不让
 * 估算器抛异常到请求路径上（估算发生在过滤器里，一次异常就是一次 5xx）。
 */
public final class QuotaEstimator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 缺 {@code max_tokens} 时的补全上限。取 1024 的理由：它是绝大多数 OpenAI 兼容客户端没显式
     * 指定时常见的默认量级，既不会把余额一口压光（用 {@code Integer.MAX_VALUE} 会让任何配额都判定不足），
     * 也不会小到让正常请求频繁走「预扣不够 → 补扣」这条路。
     */
    public static final long DEFAULT_MAX_COMPLETION_TOKENS = 1_024L;

    private QuotaEstimator() {
    }

    /**
     * 一次请求的预扣额度 = 估算 prompt + {@code maxTokens}。结果**永不为负**。
     *
     * @param bodyOrPrompt 请求体（JSON 文本）。拿不到 {@code messages}（解析失败 / 结构不符）时
     *                     整段文本被当作内容估算 —— 一个确定的上界近似
     * @param maxTokens    {@code max_tokens}；{@code null} / 非正时改用 {@link #DEFAULT_MAX_COMPLETION_TOKENS}
     */
    public static long estimate(String bodyOrPrompt, Integer maxTokens) {
        long prompt = TokenEstimator.estimate(promptContent(bodyOrPrompt));
        long completion = maxTokens != null && maxTokens > 0 ? maxTokens : DEFAULT_MAX_COMPLETION_TOKENS;
        return Math.max(0L, prompt + completion);
    }

    /**
     * 从请求体里取 {@code max_tokens}；缺失 / 非数字 / 解析失败返回 {@code null}。
     * 供过滤器把客户端的补全上限喂给 {@link #estimate(String, Integer)}。
     */
    public static Integer maxTokens(String body) {
        JsonNode root = parse(body);
        if (root == null) {
            return null;
        }
        JsonNode maxTokens = root.path("max_tokens");
        return maxTokens.isNumber() ? maxTokens.asInt() : null;
    }

    /**
     * 取出会被当成 prompt 的内容：{@code messages[].content} 拼接（兼容字符串与多模态分片数组）。
     * 一条都没取到（解析失败 / 没有 messages）时回落到整段 body。
     */
    private static String promptContent(String bodyOrPrompt) {
        if (bodyOrPrompt == null || bodyOrPrompt.isEmpty()) {
            return bodyOrPrompt == null ? "" : bodyOrPrompt;
        }
        JsonNode root = parse(bodyOrPrompt);
        if (root == null) {
            return bodyOrPrompt;
        }
        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            return bodyOrPrompt;
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode message : messages) {
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                out.append(content.asText());
            } else if (content.isArray()) {
                // OpenAI 多模态：content 是 [{type:text,text:...}, {type:image_url,...}]
                for (JsonNode part : content) {
                    JsonNode text = part.path("text");
                    if (text.isTextual()) {
                        out.append(text.asText());
                    }
                }
            }
        }
        return out.isEmpty() ? bodyOrPrompt : out.toString();
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            // 解析失败不是错误路径：回落到「整段文本」估算（本类负责绝不抛到请求路径上）。
            return null;
        }
    }
}
