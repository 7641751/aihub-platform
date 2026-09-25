package com.aihub.common.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 内部调用的最小守卫：共享密钥 HMAC。admin 与 gateway 共用这一份实现。
 * <p>签名内容为 {@code timestamp + "\n" + METHOD + "\n" + path}；METHOD 统一按 {@link Locale#ROOT}
 * 转大写，因此与调用方传入的大小写、与两端各自的默认 locale 都无关。
 * body 不参与签名 —— M1 的内部接口只传一个哈希，body 签名留待需要时再加。
 * <p><b>本类只判定「签名是否一致」，不做防重放。</b>时间戳本身是否是合法数字、是否落在允许的时间窗
 * （±300 秒）内，都是调用方（内部调用过滤器）的职责：本类既不解析也不检查时间戳，
 * {@link #verify} 只比较签名。
 */
public final class InternalHmac {

    private static final String ALGORITHM = "HmacSHA256";

    private InternalHmac() {
    }

    /**
     * 计算签名。{@code secret} 为 null/空属于调用方的编程错误，这里照旧直接抛出
     * {@link IllegalStateException}（只有 {@link #verify} 才 fail-closed）。
     */
    public static String sign(String secret, String timestamp, String method, String path) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String payload = timestamp + "\n" + method.toUpperCase(Locale.ROOT) + "\n" + path;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算内部签名", e);
        }
    }

    /**
     * 常量时间比较，避免按字节提前返回泄露信息。
     * <p>鉴权判定必须 fail-closed：密钥为 null/空（配置缺失）时返回 {@code false} 而不是抛异常，
     * 否则未来过滤器里会变成 500 而不是 401。{@code timestamp}/{@code signature} 为 null 同样返回
     * {@code false}。
     */
    public static boolean verify(String secret, String timestamp, String method, String path, String signature) {
        if (secret == null || secret.isBlank() || signature == null || timestamp == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(secret, timestamp, method, path).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
