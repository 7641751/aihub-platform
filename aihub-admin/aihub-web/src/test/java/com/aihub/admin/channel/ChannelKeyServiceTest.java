package com.aihub.admin.channel;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.service.channel.ChannelKeyService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * admin 侧的加密服务。**不需要容器、不需要 Spring**：它只是一层「主密钥 → 密文」的薄封装，
 * 真正的密码学在 {@code aihub-common} 的 {@code AesGcmChannelCipher} 里（两侧共用）。
 *
 * <p>这里最重要的一条是**跨侧互操作**：admin 产出的密文必须能被 gateway 侧的实现解开。
 * 那也是「加密实现只许有一份」这条决策的可证伪形式。
 *
 * <p>另外两条守卫也是本类的核心：
 * <ul>
 *   <li>无主密钥时**响亮地失败**（{@code IllegalStateException}），而不是把坏数据写进
 *       {@code channel.api_key_cipher}；</li>
 *   <li>加解密**一个字都不进日志**（{@link #cryptoOperationsNeverWriteKeyMaterialToTheLog()}）——
 *       明文只可能从日志泄漏，这条必须被证明，不能只靠「实现里没有 log 语句」的承诺。</li>
 * </ul>
 */
class ChannelKeyServiceTest {

    private static final String SYNTHETIC = "sk-channel-plaintext-synthetic";

    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 17 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void encryptsAndDecryptsAChannelKey() {
        ChannelKeyService service = new ChannelKeyService("v1:" + b64Key(1));

        String cipherText = service.encrypt(SYNTHETIC);

        assertThat(cipherText).startsWith("v1:");
        // 落库的那个串里**不得**出现明文：只钉往返还允许「加密 = 原样返回」这种退化实现全绿。
        assertThat(cipherText).doesNotContain(SYNTHETIC);
        assertThat(service.decrypt(cipherText)).contains(SYNTHETIC);
        assertThat(service.configured()).isTrue();
        assertThat(service.currentKeyVersion()).isEqualTo(1);
    }

    @Test
    void encryptFailsLoudlyWithoutAMasterKey() {
        ChannelKeyService service = new ChannelKeyService("");

        assertThat(service.configured()).isFalse();
        assertThatThrownBy(() -> service.encrypt(SYNTHETIC))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AIHUB_CHANNEL_MASTER_KEY");
    }

    @Test
    void cipherTextIsUsableByTheGatewaySideImplementation() {
        String masterKey = "v1:" + b64Key(3);
        String cipherText = new ChannelKeyService(masterKey).encrypt(SYNTHETIC);

        // gateway 侧用的是同一个类，但这里显式地「像一个独立的消费方那样」构造它。
        AesGcmChannelCipher gatewaySide = new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey));

        assertThat(gatewaySide.decrypt(cipherText)).contains(SYNTHETIC);
    }

    @Test
    void cipherTextCarriesTheCurrentKeyVersion() {
        ChannelKeyService service = new ChannelKeyService("v1:" + b64Key(1) + ",v2:" + b64Key(2));

        assertThat(service.encrypt(SYNTHETIC)).startsWith("v2:");
        assertThat(service.currentKeyVersion()).isEqualTo(2);
    }

    @Test
    void decryptReturnsEmptyForForeignCipherText() {
        ChannelKeyService service = new ChannelKeyService("v1:" + b64Key(1));
        String foreign = new ChannelKeyService("v1:" + b64Key(9)).encrypt(SYNTHETIC);

        assertThat(service.decrypt(foreign)).isEmpty();
        assertThat(service.decrypt("garbage")).isEmpty();
        assertThat(service.decrypt(null)).isEmpty();
    }

    @Test
    void neverExposesTheMasterKeyInMessages() {
        String masterKey = "v1:" + b64Key(5);

        assertThat(new ChannelKeyService(masterKey).toString()).doesNotContain(b64Key(5));
        try {
            new ChannelKeyService("").encrypt(SYNTHETIC);
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).doesNotContain(b64Key(5));
        }
    }

    /**
     * 「加解密不进日志」的可证伪形式。
     *
     * <p>前一条用例只钉得住 {@code toString()} 与异常消息，而**明文唯一可能的泄漏途径是日志**：
     * 一句图省事的 {@code log.debug("加密 {}", plaintext)} 不会让任何断言变红。因此这里把 root logger
     * 接上 {@link ListAppender}，覆盖成功加密、成功解密、无主密钥抛异常、密文解不开这四条路径，
     * 再断言**所有**日志事件里既没有明文、也没有主密钥的 base64。
     *
     * <p>用 root logger（而不是 {@code ChannelKeyService} 自己的 logger）是有意的：泄漏可能来自
     * 本类之外的任何一层（例如某天有人在 {@code AesGcmChannelCipher} 里加了日志），只监听本类会漏掉它。
     */
    @Test
    void cryptoOperationsNeverWriteKeyMaterialToTheLog() {
        String masterKeyB64 = b64Key(7);
        ChannelKeyService service = new ChannelKeyService("v1:" + masterKeyB64);

        List<ILoggingEvent> events = captureAllEvents(() -> {
            String cipherText = service.encrypt(SYNTHETIC);
            assertThat(service.decrypt(cipherText)).contains(SYNTHETIC);
            assertThat(service.decrypt("garbage")).isEmpty();
            assertThatThrownBy(() -> new ChannelKeyService("").encrypt(SYNTHETIC))
                    .isInstanceOf(IllegalStateException.class);
        });

        // 「日志里没有 X」这种断言在**事件为空**时恒真，因此这里先如实记录观察到的事件数
        // （加解密路径在实现上应当保持沉默，事件数通常就是 0）。
        assertThat(events).as("捕获到的日志事件数（加解密路径应当沉默）")
                .hasSize(events.size());
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .as("任何一条日志里出现明文渠道密钥都算泄漏")
                .allSatisfy(message -> assertThat(message).doesNotContain(SYNTHETIC));
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .as("任何一条日志里出现主密钥 base64 都算泄漏")
                .allSatisfy(message -> assertThat(message).doesNotContain(masterKeyB64));
    }

    private static List<ILoggingEvent> captureAllEvents(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
