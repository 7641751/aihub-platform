package com.aihub.common.apikey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 缓存载荷的紧凑编解码：{@code keyId|tenantId|tenantName|status|expireAtEpochSecond}。
 * 用自定义格式而不是 JSON，是因为 {@code aihub-common} 必须保持零依赖（不能引 Jackson），
 * 而这里的字段固定且都由本类写入。
 *
 * <p>编码只做两件事：{@code \} → {@code \\}，{@code |} → {@code \|}（顺序固定，先转义反斜杠）。
 * 于是「反斜杠成对出现」+「{@code \|} 是数据」是唯一合法形态，解码按同一约定**单趟左到右**扫描。
 * 不能改用正则切分：字段边界自带成对的反斜杠（{@code d\\|7}），用 {@code split} 会把它们
 * 当成边界的一部分吞掉 / 或把 {@code \|} 误判为边界，两种写法都有反例。
 */
public final class ApiKeyCacheCodec {

    private static final String DELIMITER = "|";
    private static final int FIELD_COUNT = 5;

    private ApiKeyCacheCodec() {
    }

    public static String encode(ApiKeyView view) {
        return escape(view.keyId()) + DELIMITER
                + view.tenantId() + DELIMITER
                + escape(view.tenantName()) + DELIMITER
                + escape(view.status()) + DELIMITER
                + (view.expireAt() == null ? "" : String.valueOf(view.expireAt().getEpochSecond()));
    }

    /** 格式非法时返回 {@code null}，调用方应视作缓存未命中并回源，而不是抛错。 */
    public static ApiKeyView decode(String payload) {
        if (payload == null) {
            return null;
        }
        List<String> parts = splitFields(payload);
        if (parts.size() != FIELD_COUNT) {
            return null;
        }
        try {
            String expireAt = parts.get(4);
            return new ApiKeyView(parts.get(0), Long.parseLong(parts.get(1)), parts.get(2), parts.get(3),
                    expireAt.isEmpty() ? null : Instant.ofEpochSecond(Long.parseLong(expireAt)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace(DELIMITER, "\\" + DELIMITER);
    }

    /**
     * 单趟扫描：遇到 {@code \\} 或 {@code \|} 就还原成一个字符（不是字段边界），
     * 遇到光秃秃的 {@code |} 才切分。这样解码就是 {@link #escape} 的严格逆运算。
     */
    private static List<String> splitFields(String payload) {
        List<String> parts = new ArrayList<>(FIELD_COUNT);
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < payload.length()) {
            char c = payload.charAt(i);
            if (c == '\\' && i + 1 < payload.length()) {
                char next = payload.charAt(i + 1);
                if (next == '\\' || next == DELIMITER.charAt(0)) {
                    current.append(next);
                    i += 2;
                    continue;
                }
            }
            if (c == DELIMITER.charAt(0)) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
            i++;
        }
        parts.add(current.toString());
        return parts;
    }
}
