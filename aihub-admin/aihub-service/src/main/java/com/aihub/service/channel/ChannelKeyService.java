package com.aihub.service.channel;

import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * admin 侧的渠道密钥加解密入口（**写入路径**：把明文加密后存进 {@code channel.api_key_cipher}）。
 *
 * <p>M3 只有「开发演示数据 seeder」会调用 {@link #encrypt}；M4 的渠道 CRUD 会复用它。
 * 之所以现在就把它独立出来（而不是把加解密散在 seeder 里）：M4 的 CRUD 必须用**同一把**
 * 主密钥与**同一份**格式，否则会出现两种互不相认的密文。
 *
 * <p><b>主密钥只来自环境变量</b>（{@code AIHUB_CHANNEL_MASTER_KEY} → {@code aihub.channel.master-key}），
 * 不落库、不进镜像、不打日志。本类**永不打印**主密钥或其 base64。
 *
 * <p>{@link #encrypt} 在无主密钥时**抛异常**（写入路径必须响亮地失败）；{@link #decrypt} 永不抛
 * （读路径不能因为配置事故把整个流程打断）。
 *
 * <p><b>本类没有 logger，这是刻意的</b>：它每一次调用手上都同时握着明文与密文，一句
 * {@code log.debug("加密 {}", plaintext)} 就能让「明文渠道密钥从不入库/不进日志」这条铁律
 * 在无声中失效。需要可观测性时请打**渠道 id**（调用方手里有），不要在这里打内容 ——
 * 这条约束由 {@code ChannelKeyServiceTest#cryptoOperationsNeverWriteKeyMaterialToTheLog} 从
 * root logger 上钉住（它不只看本类，任何一层泄漏都会变红）。
 */
@Service
public class ChannelKeyService {

    private static final String CONFIG_HINT =
            "未配置渠道主密钥（aihub.channel.master-key / 环境变量 AIHUB_CHANNEL_MASTER_KEY）。"
                    + "生成一把（本机 pwsh）：$k=[byte[]]::new(32); "
                    + "(New-Object Security.Cryptography.RNGCryptoServiceProvider).GetBytes($k); "
                    + "'v1:' + [Convert]::ToBase64String($k)";

    private final ChannelKeyRegistry registry;
    private final AesGcmChannelCipher cipher;

    public ChannelKeyService(@Value("${aihub.channel.master-key:}") String masterKey) {
        this.registry = ChannelKeyRegistry.parse(masterKey);
        this.cipher = new AesGcmChannelCipher(registry);
    }

    /** 加密一条渠道明文密钥。返回自描述密文（{@code v{n}:{base64}}）。 */
    public String encrypt(String plaintextChannelKey) {
        if (registry.isEmpty()) {
            throw new IllegalStateException(CONFIG_HINT);
        }
        return cipher.encrypt(plaintextChannelKey == null ? "" : plaintextChannelKey);
    }

    /** 解密（读路径：只用于「探测渠道」这类未来功能）；任何失败返回空而不是抛异常。 */
    public Optional<String> decrypt(String cipherText) {
        return cipher.decrypt(cipherText);
    }

    public int currentKeyVersion() {
        return registry.currentVersion();
    }

    public boolean configured() {
        return !registry.isEmpty();
    }

    /** 只暴露「有几个版本」，**绝不含密钥内容**。 */
    @Override
    public String toString() {
        return "ChannelKeyService[" + registry.describe() + "]";
    }
}
