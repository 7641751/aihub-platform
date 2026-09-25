package com.aihub.gateway.meter;

/**
 * 无 usage 时的 token 估算。口径取自 DeepSeek 官方文档：1 个英文字符 ≈ 0.3 token、
 * 1 个汉字 ≈ 0.6 token，向上取整（非空内容至少 1）。
 *
 * <p>它**只是估算**：只在拿不到上游 usage 时使用，落库时由
 * {@code error_code = usage_missing} / {@code client_disconnected} 标出这个事实。
 * 设计文档 §8.1 ⑤ 明确要求「客户端断连 → 按已收 chunk 估算 token」。
 */
public final class TokenEstimator {

    private static final double CJK_TOKENS_PER_CHAR = 0.6;
    private static final double OTHER_TOKENS_PER_CHAR = 0.3;

    private TokenEstimator() {
    }

    public static int estimate(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < content.length(); ) {
            int codePoint = content.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(cjk * CJK_TOKENS_PER_CHAR + other * OTHER_TOKENS_PER_CHAR);
    }

    /** 汉字 + 中文标点 + 全角 + 韩文音节（不追求完备，够用且确定）。 */
    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x3000 && codePoint <= 0x303F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF);
    }
}
