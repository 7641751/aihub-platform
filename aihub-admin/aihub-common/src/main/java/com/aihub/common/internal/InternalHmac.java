package com.aihub.common.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 内部调用的最小守卫：共享密钥 HMAC。admin 与 gateway 共用这一份实现。
 * 签名内容为 {@code timestamp + "\n" + METHOD + "\n" + path}，配合 ±300 秒时间窗防重放。
 * body 不参与签名 —— M1 的内部接口只传一个哈希，body 签名留待需要时再加。
 */
public final class InternalHmac {

    private static final String ALGORITHM = "HmacSHA256";

    private InternalHmac() {
    }

    public static String sign(String secret, String timestamp, String method, String path) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String payload = timestamp + "\n" + method.toUpperCase() + "\n" + path;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算内部签名", e);
        }
    }

    /** 常量时间比较，避免按字节提前返回泄露信息。 */
    public static boolean verify(String secret, String timestamp, String method, String path, String signature) {
        if (signature == null || timestamp == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(secret, timestamp, method, path).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
