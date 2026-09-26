package com.aihub.common.apikey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 缓存载荷的紧凑编解码：{@code keyId|tenantId|tenantName|status|expireAtEpochSecond|apiKeyId}
 * （第 6 段空串表示 null）。用自定义格式而不是 JSON，是因为 {@code aihub-common} 必须保持零依赖
 * （不能引 Jackson），而这里的字段固定且都由本类写入。
 *
 * <p><b>段数是跨服务契约</b>：gateway 读 admin 写的载荷，任何一侧改了段数都会让另一侧
 * {@code decode} 返回 {@code null}（缓存未命中 → 回源 → 重写）。这**不是**故障，是收敛行为，
 * 因此不需要清 Redis；但改段数时必须同步 {@code ApiKeyToolingTest} 的固定向量。
 *
 * <p>编码只做两件事：{@code \} → {@code \\}，{@code |} → {@code \|}（顺序固定，先转义反斜杠）。
 * 于是「反斜杠成对出现」+「{@code \|} 是数据」是唯一合法形态，解码按同一约定**单趟左到右**扫描。
 * 不能改用正则切分：字段边界自带成对的反斜杠（{@code d\\|7}），用 {@code split} 会把它们
 * 当成边界的一部分吞掉 / 或把 {@code \|} 误判为边界，两种写法都有反例。
 */
public final class ApiKeyCacheCodec {

    /**
     * 密钥缓存的 Redis key 前缀，完整 key 是 {@code CACHE_KEY_PREFIX + key_hash}。
     * <p>这是一条**跨服务契约**：admin 铸造时写入，gateway 校验时读取（以及回填）。任何一侧
     * 私自改动或写错前缀都不会报错，只会变成永久缓存未命中，因此固定放在共享模块里，
     * 并由 {@code ApiKeyToolingTest} 钉住字面量。
     */
    public static final String CACHE_KEY_PREFIX = "aihub:apikey:";

    private static final String DELIMITER = "|";
    /**
     * 载荷段数。M3 起是 6（新增 {@code apiKeyId}）；旧载荷会被 {@link #decode} 判为畸形
     * → 缓存未命中 → 回源重写。这是**收敛行为**，不是故障，因此不需要清 Redis。
     */
    private static final int FIELD_COUNT = 6;

    private ApiKeyCacheCodec() {
    }

    public static String encode(ApiKeyView view) {
        return escape(view.keyId()) + DELIMITER
                + view.tenantId() + DELIMITER
                + escape(view.tenantName()) + DELIMITER
                + escape(view.status()) + DELIMITER
                + (view.expireAt() == null ? "" : String.valueOf(view.expireAt().getEpochSecond())) + DELIMITER
                + (view.apiKeyId() == null ? "" : String.valueOf(view.apiKeyId()));
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
            String apiKeyId = parts.get(5);
            return new ApiKeyView(parts.get(0), Long.parseLong(parts.get(1)), parts.get(2), parts.get(3),
                    expireAt.isEmpty() ? null : Instant.ofEpochSecond(Long.parseLong(expireAt)),
                    apiKeyId.isEmpty() ? null : Long.valueOf(apiKeyId));
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
