package com.aihub.common.meter;

import java.util.ArrayList;
import java.util.List;

/**
 * 计量事件的线格式：13 段用 {@code |} 分隔的 UTF-8 文本，字段顺序与
 * {@link MeteringEvent} 的声明顺序一致。
 *
 * <p><b>为什么不是 JSON</b>：{@code aihub-common} 在 main 作用域必须零依赖（不能引 Jackson），
 * 而发布端（gateway）与消费端（admin）必须共用同一份编解码 —— 让 gateway 自造 JSON、
 * admin 用 Jackson 解，等于把契约撕成两半，谁都能单方面改。做法与既有
 * {@code ApiKeyCacheCodec} 一致：自定义转义 + 单趟扫描解码。
 *
 * <p><b>转义覆盖 {@code \} {@code |} {@code \n} {@code \r}</b>：前两个是格式要求，
 * 后两个是运维要求 —— 事件在日志、工单、磁盘 spool 里都会被当文本看，
 * 字段里漏出一个换行就会让「一条事件」在文本视角下裂成两条。
 *
 * <p>本类同时是磁盘 spool 的文件内容格式（一个文件一条 payload）。
 */
public final class MeteringEventCodec {

    private static final String DELIMITER = "|";
    private static final int FIELD_COUNT = 13;

    private MeteringEventCodec() {
    }

    public static String encode(MeteringEvent event) {
        StringBuilder out = new StringBuilder(160);
        out.append(escape(event.requestId())).append(DELIMITER)
                .append(event.tenantId()).append(DELIMITER)
                .append(event.apiKeyId() == null ? "" : event.apiKeyId()).append(DELIMITER)
                .append(event.channelId() == null ? "" : event.channelId()).append(DELIMITER)
                .append(escape(event.model())).append(DELIMITER)
                .append(event.promptTokens()).append(DELIMITER)
                .append(event.completionTokens()).append(DELIMITER)
                .append(event.totalTokens()).append(DELIMITER)
                .append(event.latencyMs()).append(DELIMITER)
                .append(event.ttftMs() == null ? "" : event.ttftMs()).append(DELIMITER)
                .append(escape(event.status())).append(DELIMITER)
                .append(escape(event.errorCode())).append(DELIMITER)
                .append(event.createdAtEpochMilli());
        return out.toString();
    }

    /** 载荷畸形（段数不对、数字解析失败、requestId/status 为空）时返回 {@code null}，调用方据此走死信。 */
    public static MeteringEvent decode(String payload) {
        if (payload == null) {
            return null;
        }
        List<String> parts = splitFields(payload);
        if (parts.size() != FIELD_COUNT) {
            return null;
        }
        try {
            String requestId = parts.get(0);
            String status = parts.get(10);
            if (requestId.isBlank() || status.isBlank()) {
                return null;
            }
            return new MeteringEvent(
                    requestId,
                    Long.parseLong(parts.get(1)),
                    optionalLong(parts.get(2)),
                    optionalLong(parts.get(3)),
                    emptyToNull(parts.get(4)),
                    Integer.parseInt(parts.get(5)),
                    Integer.parseInt(parts.get(6)),
                    Integer.parseInt(parts.get(7)),
                    Integer.parseInt(parts.get(8)),
                    optionalInt(parts.get(9)),
                    status,
                    emptyToNull(parts.get(11)),
                    Long.parseLong(parts.get(12)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long optionalLong(String value) {
        return value.isEmpty() ? null : Long.valueOf(value);
    }

    private static Integer optionalInt(String value) {
        return value.isEmpty() ? null : Integer.valueOf(value);
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
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
     * 单趟扫描：{@code \\} {@code \|} {@code \n} {@code \r} 还原成一个字符，光秃秃的 {@code |}
     * 才是字段边界。这样解码就是 {@link #escape} 的严格逆运算（不能改用正则切分，理由同
     * {@code ApiKeyCacheCodec}：字段边界自带成对反斜杠）。
     */
    private static List<String> splitFields(String payload) {
        List<String> parts = new ArrayList<>(FIELD_COUNT);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c == '\\' && i + 1 < payload.length()) {
                char next = payload.charAt(i + 1);
                switch (next) {
                    case '\\' -> {
                        current.append('\\');
                        i++;
                        continue;
                    }
                    case '|' -> {
                        current.append('|');
                        i++;
                        continue;
                    }
                    case 'n' -> {
                        current.append('\n');
                        i++;
                        continue;
                    }
                    case 'r' -> {
                        current.append('\r');
                        i++;
                        continue;
                    }
                    default -> {
                        // 孤立的转义符按字面量保留，交给下一轮处理。
                    }
                }
            }
            if (c == '|') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }
}
