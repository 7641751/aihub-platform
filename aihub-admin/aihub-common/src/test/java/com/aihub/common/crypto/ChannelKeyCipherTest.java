package com.aihub.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道密钥的 AES-GCM 加解密是**跨服务**能力：admin 加密（写入 {@code channel.api_key_cipher}），
 * gateway 解密（本地注入上游密钥）。两侧共用本文件里的实现，因此「密文格式」「主密钥解析规则」
 * 「版本自描述」这三件事都压在这里。
 *
 * <p>测试用的明文一律是**一眼可辨的合成值**（{@code sk-channel-plaintext-synthetic}）：
 * 真实渠道密钥不允许出现在任何 tracked 文件里，包括测试夹具。
 *
 * <p>本类不需要 Spring 上下文，也不需要 Docker / Redis。
 */
class ChannelKeyCipherTest {

    private static final String PLAINTEXT = "sk-channel-plaintext-synthetic";

    /** 32 字节（AES-256）的合成主密钥。 */
    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static ChannelKeyRegistry registry(int... versions) {
        StringBuilder out = new StringBuilder();
        for (int version : versions) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append('v').append(version).append(':').append(b64Key(version));
        }
        return ChannelKeyRegistry.parse(out.toString());
    }

    @Test
    void roundTripsAPlaintextChannelKey() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        for (String plaintext : List.of(PLAINTEXT, "", "中文渠道密钥", "x".repeat(4096))) {
            String payload = cipher.encrypt(plaintext);
            assertThat(cipher.decrypt(payload)).contains(plaintext);
        }
    }

    /**
     * AES-GCM 下**复用 nonce 是致命缺陷**（同一密钥 + 同一 nonce 会泄露明文异或并摧毁认证性）。
     * 这条用例是它的防线：同一明文加密两次必须得到两个不同的密文，且都能解回来。
     */
    @Test
    void everyEncryptionUsesAFreshNonce() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        String first = cipher.encrypt(PLAINTEXT);
        String second = cipher.encrypt(PLAINTEXT);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).contains(PLAINTEXT);
        assertThat(cipher.decrypt(second)).contains(PLAINTEXT);
    }

    @Test
    void payloadIsSelfDescribingWithTheVersionLabel() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1, 2));

        String payload = cipher.encrypt(PLAINTEXT);

        // 环境变量里最大的版本号就是 currentVersion。
        assertThat(payload).startsWith("v2:");
        assertThat(AesGcmChannelCipher.labelOf(payload)).isEqualTo("v2");
        assertThat(cipher.canDecrypt(payload)).isTrue();
    }

    @Test
    void tamperedCiphertextFailsToDecrypt() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));
        String payload = cipher.encrypt(PLAINTEXT);

        String flipped = payload.substring(0, payload.length() - 2)
                + (payload.endsWith("A") ? "B" : "A");

        assertThat(cipher.decrypt(flipped)).isEmpty();
    }

    @Test
    void tamperedNonceFailsToDecrypt() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));
        String payload = cipher.encrypt(PLAINTEXT);
        int colon = payload.indexOf(':');
        byte[] nonceAndBody = Base64.getDecoder().decode(payload.substring(colon + 1));
        nonceAndBody[0] ^= 0x01;

        String tampered = payload.substring(0, colon + 1) + Base64.getEncoder().encodeToString(nonceAndBody);

        assertThat(cipher.decrypt(tampered)).isEmpty();
    }

    /**
     * 轮换的完整含义：**旧密文仍然解得开**（旧版本还在表里），而**新加密一定用新版本**。
     * 只有「解密时先查 DB 的 key_version 再选密钥」的实现才会在这条上红。
     */
    @Test
    void rotationDecryptsTheOldVersionAndReencryptsToTheNewOne() {
        String oldPayload = new AesGcmChannelCipher(registry(1)).encrypt(PLAINTEXT);

        AesGcmChannelCipher rotated = new AesGcmChannelCipher(registry(1, 2));

        assertThat(rotated.decrypt(oldPayload)).contains(PLAINTEXT);
        String newPayload = rotated.encrypt(PLAINTEXT);
        assertThat(newPayload).startsWith("v2:");
        assertThat(rotated.decrypt(newPayload)).contains(PLAINTEXT);
    }

    @Test
    void retiringTheOldKeyMakesOldCiphertextUndecryptable() {
        String oldPayload = new AesGcmChannelCipher(registry(1)).encrypt(PLAINTEXT);
        AesGcmChannelCipher retired = new AesGcmChannelCipher(registry(2));

        assertThat(retired.decrypt(oldPayload)).isEmpty();
        assertThat(retired.canDecrypt(oldPayload)).isFalse();
    }

    @Test
    void unknownVersionLabelReturnsEmptyInsteadOfThrowing() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        assertThat(cipher.decrypt("v9:" + Base64.getEncoder().encodeToString(new byte[40]))).isEmpty();
    }

    @Test
    void malformedPayloadsReturnEmpty() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        for (String payload : new String[]{null, "", "v1", "v1:", "nonsense", "v1:!!!not-base64!!!", "v1:AAAA"}) {
            assertThat(cipher.decrypt(payload)).as("payload %s 必须解成空而不是抛异常", payload).isEmpty();
        }
        assertThat(AesGcmChannelCipher.labelOf("nonsense")).isNull();
        assertThat(AesGcmChannelCipher.labelOf(null)).isNull();
    }

    @Test
    void registryParsingAcceptsWhitespaceAndRejectsGarbage() {
        ChannelKeyRegistry parsed = ChannelKeyRegistry.parse(
                " v1:" + b64Key(1) + " , 不是版本 , v2:" + b64Key(2) + ",v3:short ");

        assertThat(parsed.has(1)).isTrue();
        assertThat(parsed.has(2)).isTrue();
        assertThat(parsed.has(3)).as("不足 32 字节的版本必须被跳过").isFalse();
        assertThat(parsed.currentVersion()).as("当前版本 = 解析成功的最大版本号").isEqualTo(2);
        assertThat(parsed.describe()).as("describe 只含版本号，绝不含密钥内容").contains("1").contains("2");
    }

    @Test
    void registryRequiresExactly32ByteKeys() {
        ChannelKeyRegistry parsed = ChannelKeyRegistry.parse(
                "v1:" + Base64.getEncoder().encodeToString(new byte[16])
                        + ",v2:" + Base64.getEncoder().encodeToString(new byte[31])
                        + ",v3:" + Base64.getEncoder().encodeToString(new byte[33]));

        assertThat(parsed.isEmpty()).isTrue();
    }

    @Test
    void emptyRegistryCannotEncryptAndCannotDecrypt() {
        ChannelKeyRegistry empty = ChannelKeyRegistry.parse("");
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(empty);

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.currentVersion()).isZero();
        assertThatThrownBy(() -> cipher.encrypt(PLAINTEXT)).isInstanceOf(IllegalStateException.class);
        assertThat(cipher.decrypt("v1:AAAA")).isEmpty();
        assertThat(empty.key(1)).isEqualTo(Optional.empty());
        assertThat(empty.keys()).isEqualTo(Map.of());
    }
}
