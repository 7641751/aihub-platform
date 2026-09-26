package com.aihub.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 渠道密钥的 AES-GCM 加解密。**JDK 自带实现，不引第三方加密库**。
 *
 * <p><b>密文自描述</b>：{@code v{版本}:{Base64(nonce ‖ ciphertext+tag)}}。网关解密时**从载荷里
 * 读版本**，而不是去查 {@code channel.key_version} 列 —— 轮换期间只要有一条行的那一列写歪，
 * 「查列选密钥」的实现就会把一条本来完好的密文变成永久不可解的数据丢失。自描述载荷让回退安全：
 * 把新密钥从环境变量里去掉，旧密文照样可解。
 *
 * <p><b>失败语义</b>：{@link #decrypt} **永不抛异常**，畸形载荷 / 未知版本 / tag 校验失败一律返回
 * 空 {@link Optional}。调用它的是数据面请求路径，配置错误不能变成客户端 500
 * （与 M1「缓存故障绝不变成 500」同一条纪律）。{@link #encrypt} 相反：它只在 admin 的写入路径上被
 * 调用，没有可用主密钥时直接抛 {@link IllegalStateException} —— 静默写坏数据比启动失败糟得多。
 */
public final class AesGcmChannelCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    /** GCM 推荐 96 位 nonce。 */
    private static final int NONCE_LENGTH_BYTES = 12;
    /** 认证标签 128 位。 */
    private static final int TAG_LENGTH_BITS = 128;
    private static final Pattern LABEL = Pattern.compile("^([vV]\\d{1,9}):(.*)$");

    private final ChannelKeyRegistry registry;
    private final SecureRandom random = new SecureRandom();

    public AesGcmChannelCipher(ChannelKeyRegistry registry) {
        this.registry = registry;
    }

    /**
     * 用 {@link ChannelKeyRegistry#currentVersion()} 加密。
     *
     * @throws IllegalStateException 主密钥表为空（配置缺失）
     */
    public String encrypt(String plaintext) {
        int version = registry.currentVersion();
        if (version == 0) {
            throw new IllegalStateException(
                    "未配置渠道主密钥（aihub.channel.master-key / AIHUB_CHANNEL_MASTER_KEY），拒绝加密渠道密钥");
        }
        byte[] key = registry.key(version).orElseThrow();
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = doFinal(Cipher.ENCRYPT_MODE, key, nonce, plaintext.getBytes(StandardCharsets.UTF_8));
        ByteBuffer combined = ByteBuffer.allocate(nonce.length + ciphertext.length);
        combined.put(nonce).put(ciphertext);
        return "v" + version + ":" + Base64.getEncoder().encodeToString(combined.array());
    }

    /** 解密；任何失败都返回空而不是抛异常。 */
    public Optional<String> decrypt(String payload) {
        if (payload == null) {
            return Optional.empty();
        }
        Matcher matcher = LABEL.matcher(payload.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int version;
        byte[] combined;
        try {
            version = Integer.parseInt(matcher.group(1).substring(1));
            combined = Base64.getDecoder().decode(matcher.group(2).strip());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        Optional<byte[]> key = registry.key(version);
        if (key.isEmpty() || combined.length <= NONCE_LENGTH_BYTES) {
            return Optional.empty();
        }
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        byte[] ciphertext = new byte[combined.length - NONCE_LENGTH_BYTES];
        System.arraycopy(combined, 0, nonce, 0, NONCE_LENGTH_BYTES);
        System.arraycopy(combined, NONCE_LENGTH_BYTES, ciphertext, 0, ciphertext.length);
        try {
            byte[] plaintext = doFinal(Cipher.DECRYPT_MODE, key.get(), nonce, ciphertext);
            return Optional.of(new String(plaintext, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // AEADBadTagException 走这里：被篡改 / 用错密钥 / 载荷被截断。
            return Optional.empty();
        }
    }

    /** 载荷在当前主密钥表里能不能解（运维巡检用：不解密就能数出「有几行解不开」）。 */
    public boolean canDecrypt(String payload) {
        return decrypt(payload).isPresent();
    }

    /** 只读版本标签（{@code v1}），畸形或 null 返回 {@code null}；不解密、不泄漏内容。 */
    public static String labelOf(String payload) {
        if (payload == null) {
            return null;
        }
        Matcher matcher = LABEL.matcher(payload.strip());
        return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static byte[] doFinal(int mode, byte[] key, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // 解密侧由调用方捕获 RuntimeException 折算成空 Optional；这条统一包成
            // IllegalStateException（本机 JDK 的 AES-GCM 不会在这里失败）。
            throw new IllegalStateException("AES-GCM 运算失败", e);
        }
    }
}
