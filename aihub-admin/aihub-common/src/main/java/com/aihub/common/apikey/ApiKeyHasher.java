package com.aihub.common.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** API Key 的生成与哈希。全项目只有这一份实现，admin 铸造与 gateway 校验都用它。 */
public final class ApiKeyHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private ApiKeyHasher() {
    }

    /** 库中存储的 key_hash：secret 的 SHA-256 小写十六进制（64 字符，正好匹配 CHAR(64)）。 */
    public static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 未提供 SHA-256", e);
        }
    }

    /** 32 字节随机 secret，URL 安全 Base64（无填充）。 */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 形如 {@code ak_xxxxxxxxxxxxxxxx}，总长 19，安全落在 key_id VARCHAR(32) 内。 */
    public static String newKeyId() {
        StringBuilder sb = new StringBuilder("ak_");
        for (int i = 0; i < 16; i++) {
            sb.append(KEY_ID_ALPHABET.charAt(RANDOM.nextInt(KEY_ID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
