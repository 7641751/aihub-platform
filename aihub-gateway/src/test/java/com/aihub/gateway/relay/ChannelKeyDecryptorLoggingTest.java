package com.aihub.gateway.relay;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「明文渠道密钥绝不进日志」这句话的**可证伪**形式。
 *
 * <p>{@code ChannelKeyDecryptorTest#neverExposesThePlaintext} 只能钉住 {@code toString()} 与返回值：
 * 它在「主密钥为空 → 根本解不开」的前提下求值，因此任何实现都不可能把明文写进那句断言里 ——
 * 那个用例永远绿，也就永远抓不到回归。本类改成**捕获日志事件**：
 * <ul>
 *   <li>解不开时的 WARN 只能带渠道 id/名与密文**版本标签**：一旦有人图省事把
 *       {@code ChannelDescriptor}（它的 {@code toString()} 含 {@code apiKeyCipher}）或密文本身
 *       塞进日志，密文就会在这里出现并让用例变红；</li>
 *   <li>解成功的那条路径必须**一个字都不打** —— 那是明文唯一可能被写进日志的分支。</li>
 * </ul>
 *
 * <p>主密钥格式与 {@code ChannelKeyDecryptorTest} 保持同一套（`v1:` + 32 字节），
 * 明文一律是显然合成的值。
 */
class ChannelKeyDecryptorLoggingTest {

    private static final String SYNTHETIC_PLAINTEXT = "sk-logging-synthetic-plaintext";

    private static final Logger LOGGER = (Logger) LoggerFactory.getLogger(ChannelKeyDecryptor.class);

    @Test
    void theUndecryptableWarningNamesTheChannelAndVersionWithoutTheCipherOrThePlaintext() {
        String ciphertext = cipher().encrypt(SYNTHETIC_PLAINTEXT);
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(noMasterKey(), upstream());

        List<ILoggingEvent> events = captureEvents(() ->
                assertThat(decryptor.upstreamKey(channel(ciphertext))).isEmpty());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFormattedMessage())
                .as("WARN 里必须能定位到渠道与版本，否则运维无从下手")
                .contains("11")
                .contains("primary")
                .contains("v1")
                .as("WARN 里绝不允许出现密文或明文")
                .doesNotContain(ciphertext)
                .doesNotContain(SYNTHETIC_PLAINTEXT);
    }

    @Test
    void aSuccessfulDecryptionLogsNothingAboutTheKey() {
        String ciphertext = cipher().encrypt(SYNTHETIC_PLAINTEXT);
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(cipher(), upstream());

        List<ILoggingEvent> events = captureEvents(() ->
                assertThat(decryptor.upstreamKey(channel(ciphertext))).contains(SYNTHETIC_PLAINTEXT));

        assertThat(events)
                .as("成功路径一旦打日志，明文就多了一条泄漏途径：这条路径必须保持沉默")
                .isEmpty();
    }

    private static List<ILoggingEvent> captureEvents(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        LOGGER.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            LOGGER.detachAppender(appender);
            appender.stop();
        }
    }

    private static AesGcmChannelCipher cipher() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 1);
        }
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse("v1:" + Base64.getEncoder().encodeToString(key)));
    }

    /** 与 {@code ChannelKeyDecryptorTest#neverExposesThePlaintext} 同一种退化：主密钥表为空。 */
    private static AesGcmChannelCipher noMasterKey() {
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse(""));
    }

    private static ChannelDescriptor channel(String cipherText) {
        return new ChannelDescriptor(11L, "primary", "https://primary.example.com", cipherText, 1, 60_000,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    private static UpstreamProperties upstream() {
        return new UpstreamProperties("http://127.0.0.1:11434", null, "legacy-model");
    }
}
