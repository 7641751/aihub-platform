package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解密发生在**网关本地**（设计文档 §6.1：admin 只下发密文，gateway 持主密钥在本地解密），
 * 因此这个类就是「明文密钥不跨越网络」这句话的落点。
 *
 * <p>失败语义：解不开的渠道必须变成**空 Optional**（调用方跳过它去试下一个候选），
 * 而不是抛异常 —— 否则一次主密钥轮换事故会变成全量 500。
 */
class ChannelKeyDecryptorTest {

    private static final String SYNTHETIC_PLAINTEXT = "sk-channel-plaintext-synthetic";

    private static String masterKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 1);
        }
        return "v1:" + Base64.getEncoder().encodeToString(key);
    }

    private static AesGcmChannelCipher cipher() {
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey()));
    }

    private static ChannelDescriptor channel(String cipherText) {
        return new ChannelDescriptor(11L, "primary", "https://primary.example.com", cipherText, 1, 60_000,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    private static UpstreamProperties upstream(String apiKey) {
        return new UpstreamProperties("http://127.0.0.1:11434", apiKey, "legacy-model");
    }

    @Test
    void decryptsAChannelKeyWithTheConfiguredMasterKey() {
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(cipher(), upstream(null));

        assertThat(decryptor.upstreamKey(channel(cipher().encrypt(SYNTHETIC_PLAINTEXT))))
                .contains(SYNTHETIC_PLAINTEXT);
    }

    @Test
    void legacyChannelUsesTheConfiguredUpstreamApiKey() {
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(cipher(), upstream("legacy-configured-key"));

        assertThat(decryptor.upstreamKey(LegacyChannel.of(upstream("legacy-configured-key"))))
                .contains("legacy-configured-key");
    }

    /** 本地 Ollama 这类无密钥上游：**空字符串的语义是「不注入任何 Authorization」**，不是失败。 */
    @Test
    void legacyChannelWithNoConfiguredKeyYieldsEmptyString() {
        UpstreamProperties legacy = upstream("");
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(cipher(), legacy);

        assertThat(decryptor.upstreamKey(LegacyChannel.of(legacy))).contains("");
        assertThat(decryptor.canServe(LegacyChannel.of(legacy))).isTrue();
    }

    @Test
    void undecryptableCipherYieldsEmptyInsteadOfThrowing() {
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(cipher(), upstream(null));

        assertThat(decryptor.upstreamKey(channel("v9:QUJD"))).isEmpty();
        assertThat(decryptor.upstreamKey(channel("garbage"))).isEmpty();
        assertThat(decryptor.upstreamKey(channel(null))).isEmpty();
        assertThat(decryptor.canServe(channel("v9:QUJD"))).isFalse();
    }

    @Test
    void canServeIsFalseForAnUndecryptableChannel() {
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(
                new AesGcmChannelCipher(ChannelKeyRegistry.parse("")), upstream(null));

        assertThat(decryptor.canServe(channel("v1:QUJD"))).isFalse();
        assertThat(decryptor.canServe(null)).isFalse();
    }
}
