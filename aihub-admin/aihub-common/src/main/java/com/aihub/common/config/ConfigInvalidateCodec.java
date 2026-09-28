package com.aihub.common.config;

/**
 * 配置失效消息的线格式：{@code {version}|{escaped reason}}，与
 * {@code com.aihub.common.meter.MeteringEventCodec} 同一纪律（分隔符文本 + 自定义转义 + 单趟扫描解码）。
 *
 * <p><b>为什么不是 JSON</b>：{@code aihub-common} 的 main 作用域必须零第三方依赖（不能引 Jackson），
 * 而发布端（admin）与订阅端（gateway）必须共用同一份编解码 —— 让两侧各造一份 JSON，等于把跨服务契约
 * 撕成两半，谁都能单方面改而不被发现。频道名（{@link ConfigInvalidateTopology#CHANNEL}）与这里的
 * 载荷格式因此都放在 {@code aihub-common}，是**唯一真相**。
 *
 * <p><b>转义覆盖 {@code \} {@code |} {@code \n} {@code \r}</b>：{@code |} 是字段分隔符，必须转义；
 * {@code \} 必须在它之前处理（否则 {@code a\|b} 的往返会丢掉反斜杠）；换行是运维要求 ——
 * 消息在日志、工单、终端里都会被当文本看，字段里漏出一个换行就会让「一条消息」在文本视角下裂成两条。
 *
 * <p><b>畸形载荷一律返回 {@code null}，绝不抛异常</b>：订阅端不能因为一条坏消息崩掉，
 * 也不能把一条解不开的消息当成有效失效（那会让它去清一个不该清的缓存、或把水位抬到错误的值）。
 * 与 {@code MeteringEventCodec} 一致：畸形走「拒绝」，不是「尽力解释」。
 * 这条纪律只约束**解码**（订阅端）；**编码**（发布端）方向恰好相反：能造出订阅端必然拒绝的载荷的输入
 * 直接快速失败，见 {@link #encode(ConfigInvalidateMessage)}。
 */
public final class ConfigInvalidateCodec {

    private static final char DELIMITER = '|';

    private ConfigInvalidateCodec() {
    }

    /**
     * 线格式：{version}|{escaped reason}。reason 是**有限枚举**（如 "channel.update"），不是自由文本。
     *
     * <p><b>{@code reason} 必须是非空的原因 token，否则抛 {@link IllegalArgumentException}（快速失败）</b>：
     * {@code encode(null)} 曾经退化成线格式 {@code "42|"}，而 {@link #decode} 按结构把 {@code "42|"}
     * 判为畸形返回 {@code null} —— 订阅端只打一条 WARN、**不做任何失效**，其他实例默默等满本地 TTL
     * （最长回到 M3 的 10 分钟上界），而发布端**一条都没记**。这就是「一条订阅端会拒绝的载荷」：
     * 它比没有载荷更糟，因为它是**静默**的。所以这里拒绝 {@code null} 与空串。
     *
     * <p><b>刻意不 trim 归一化</b>：{@code " "} 这类空白但非空的 reason 仍按字面量上线（线格式不是清洗层）。
     * <b>刻意不改 {@link #decode}</b>：让空 reason 变成一条**有效**失效，比拒绝它更危险；两侧的不对称是
     * 有意的 —— 发布端保证不发出这种载荷，订阅端则一律拒绝无法解释的载荷。
     *
     * @throws IllegalArgumentException {@code message} 的 reason 为 {@code null} 或空串
     */
    public static String encode(ConfigInvalidateMessage message) {
        String reason = message.reason();
        if (reason == null || reason.isEmpty()) {
            throw new IllegalArgumentException(
                    "配置失效消息的 reason 必须是非空的原因 token（有限枚举，如 \"channel.update\"）："
                            + "空 reason 编码出的载荷会被订阅端判为畸形并静默丢弃");
        }
        return message.version() + String.valueOf(DELIMITER) + escape(reason);
    }

    /** 畸形一律返回 null（**不抛**）：一条坏消息不该让订阅端崩掉，也不该被当成有效失效。 */
    public static ConfigInvalidateMessage decode(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        int split = indexOfUnescapedDelimiter(payload);
        if (split <= 0 || split == payload.length() - 1) {
            return null;
        }
        try {
            long version = Long.parseLong(payload.substring(0, split));
            return new ConfigInvalidateMessage(version, unescape(payload.substring(split + 1)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 第一个**未被转义**的 {@code |} 的位置；没有则返回 {@code -1}。
     *
     * <p>{@code \} 后面的那个字符（无论是什么）都跳过：它要么是转义序列的一部分，要么是一个孤立的
     * 反斜杠字面量 —— 两种情况下它都不可能是分隔符。
     */
    private static int indexOfUnescapedDelimiter(String payload) {
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == DELIMITER) {
                return i;
            }
        }
        return -1;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '|' -> out.append("\\|");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * {@link #escape} 的严格逆运算（同一个转义表，逐字照搬 {@code MeteringEventCodec} 的单趟扫描）：
     * 只认 {@code \\} {@code \|} {@code \n} {@code \r} 四个转义；孤立的转义符（含末尾那个）按字面量保留，
     * 因此解码路径上**永远不会抛异常**（"尽力解释"只针对转义，不针对整条消息的结构 ——
     * 结构不对由 {@link #decode} 返回 {@code null}）。
     */
    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                switch (next) {
                    case '\\' -> {
                        out.append('\\');
                        i++;
                        continue;
                    }
                    case '|' -> {
                        out.append('|');
                        i++;
                        continue;
                    }
                    case 'n' -> {
                        out.append('\n');
                        i++;
                        continue;
                    }
                    case 'r' -> {
                        out.append('\r');
                        i++;
                        continue;
                    }
                    default -> {
                        // 孤立的转义符按字面量保留，交给下一轮处理。
                    }
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
