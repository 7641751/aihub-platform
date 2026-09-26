package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 把 {@link ChannelDescriptor#apiKeyCipher()} 解成本次请求要用的明文渠道密钥。
 *
 * <p><b>设计文档 §6.1 的落点</b>：admin 只把密文放进快照，**主密钥在网关本地**，
 * 明文既不跨网络也不进任何 admin 侧的可观测链路。
 *
 * <p><b>失败语义</b>：解不开 → 空 {@link Optional}（路由层据此跳过这条候选）。**绝不抛异常**：
 * 一次主密钥配置事故不该把全部请求变成 500，而应该退化成「这条渠道暂时不可用」。
 * **空字符串是有意义的值**（本地 Ollama 不需要密钥 → 不注入 Authorization），
 * 因此这里用 {@code Optional.of("")} 而不是 {@code Optional.empty()} 表达「有渠道但无需密钥」。
 *
 * <p><b>永不打印明文</b>：本类不打日志内容里的密钥；连 {@link #toString()} 都不暴露字段。
 */
public class ChannelKeyDecryptor {

    private static final Logger log = LoggerFactory.getLogger(ChannelKeyDecryptor.class);

    private final AesGcmChannelCipher cipher;
    private final UpstreamProperties legacyProperties;

    public ChannelKeyDecryptor(AesGcmChannelCipher cipher, UpstreamProperties legacyProperties) {
        this.cipher = cipher;
        this.legacyProperties = legacyProperties;
    }

    /** @return 该渠道要用的明文密钥；{@code Optional.empty()} 表示「这条渠道这次不可用」 */
    public Optional<String> upstreamKey(ChannelDescriptor channel) {
        if (channel == null) {
            return Optional.empty();
        }
        if (LegacyChannel.isLegacy(channel.id())) {
            String configured = legacyProperties.apiKey();
            return Optional.of(configured == null ? "" : configured);
        }
        if (channel.apiKeyCipher() == null || channel.apiKeyCipher().isBlank()) {
            // 没有密文的真实渠道 = 配置不完整（渠道行要求 api_key_cipher NOT NULL）。
            log.warn("渠道 {} 没有密钥密文，跳过（渠道名: {}）", channel.id(), channel.name());
            return Optional.empty();
        }
        Optional<String> plaintext = cipher.decrypt(channel.apiKeyCipher());
        if (plaintext.isEmpty()) {
            // 只打版本标签与渠道 id/名，**绝不打密文或明文**。
            log.warn("渠道 {}（{}）的密钥解不开（密文版本 {}）；请检查 aihub.channel.master-key 是否包含该版本",
                    channel.id(), channel.name(), AesGcmChannelCipher.labelOf(channel.apiKeyCipher()));
        }
        return plaintext;
    }

    public boolean canServe(ChannelDescriptor channel) {
        return upstreamKey(channel).isPresent();
    }

    /** 刻意不暴露任何字段：本对象持有的是「能解密所有渠道密钥」的能力。 */
    @Override
    public String toString() {
        return "ChannelKeyDecryptor[主密钥已加载]";
    }
}
