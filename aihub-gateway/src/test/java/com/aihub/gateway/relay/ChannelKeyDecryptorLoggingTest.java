package com.aihub.gateway.relay;

import ch.qos.logback.classic.Level;
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
 * <p>本类**就是**「明文绝不进日志」的可证伪形式。此前同目录的
 * {@code ChannelKeyDecryptorTest#neverExposesThePlaintext} 试图承担这条，但它做不到：那个用例用
 * **空主密钥**构造服务（根本解不开），并只断言一个常量 {@code toString()} 与
 * {@code Optional.empty()} —— 任何实现都不可能让那些断言看见明文，它永远绿（该用例已删除）。
 * 本类改成**真的捕获日志事件**：
 * <ul>
 *   <li>解不开时的 WARN 只能带渠道 id/名与密文**版本标签**：一旦有人图省事把
 *       {@code ChannelDescriptor}（它的 {@code toString()} 含 {@code apiKeyCipher}）或密文本身
 *       塞进日志，密文就会在这里出现并让用例变红；</li>
 *   <li>解成功的那条路径必须**一个字都不打** —— 那是明文唯一可能被写进日志的分支。</li>
 * </ul>
 *
 * <p><b>捕获必须自己把级别调到 {@code TRACE}</b>（M3 最终复审修正）：logback 是**先按 logger 的
 * 有效级别过滤、再把事件交给 appender**，而本类此前只 {@code addAppender} 不设级别。于是
 * 一句 {@code log.debug("解密 {}", plaintext)} 会在抵达 appender **之前**被滤掉，用例照样全绿 ——
 * 它根本抓不到自己点名要抓的那次泄漏（敏感度还取决于「哪个类先初始化了 Logback」）。现在
 * {@link #captureEvents} 显式降级、{@code finally} 恢复；并且每个用例都用一个 **DEBUG 级的正对照
 * 事件**证明捕获链路是活的：没有它，「一个事件都没捕获到」与「没有泄漏」无法区分。
 *
 * <p>主密钥格式与 {@code ChannelKeyDecryptorTest} 保持同一套（`v1:` + 32 字节），
 * 明文一律是显然合成的值。
 */
class ChannelKeyDecryptorLoggingTest {

    private static final String SYNTHETIC_PLAINTEXT = "sk-logging-synthetic-plaintext";

    /** 正对照事件的消息；本身不含任何密钥材料。 */
    private static final String WITNESS_MESSAGE = "witness:decryptor-log-capture-is-live";

    /** 正对照用的命名 logger：与被测类无关，事件靠 additivity 冒泡到 root（root 上挂着捕获 appender）。 */
    private static final org.slf4j.Logger WITNESS = LoggerFactory.getLogger("aihub.gateway.crypto.log-capture.witness");

    private static final Logger ROOT =
            (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @Test
    void theUndecryptableWarningNamesTheChannelAndVersionWithoutTheCipherOrThePlaintext() {
        String ciphertext = cipher().encrypt(SYNTHETIC_PLAINTEXT);
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(noMasterKey(), upstream());

        List<ILoggingEvent> events = captureEvents(() -> {
            assertThat(decryptor.upstreamKey(channel(ciphertext))).isEmpty();
            WITNESS.debug(WITNESS_MESSAGE);
        });

        assertThat(messages(events))
                .as("正对照：探针事件必须被捕获，否则下面的「没有泄漏」是空的")
                .contains(WITNESS_MESSAGE);
        assertThat(decryptorMessages(events))
                .as("解不开的渠道必须留下恰好一条定位用的 WARN")
                .hasSize(1);
        assertThat(decryptorMessages(events).get(0))
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

        List<ILoggingEvent> events = captureEvents(() -> {
            assertThat(decryptor.upstreamKey(channel(ciphertext))).contains(SYNTHETIC_PLAINTEXT);
            WITNESS.debug(WITNESS_MESSAGE);
        });

        // 正对照先断言：捕获链路活着，才谈得上「这条路径沉默了」。
        assertThat(messages(events))
                .as("正对照：探针事件必须被捕获，否则「成功路径没有说话」无法与「什么都没捕获到」区分")
                .contains(WITNESS_MESSAGE);
        assertThat(decryptorMessages(events))
                .as("成功路径一旦打日志，明文就多了一条泄漏途径：这条路径必须保持沉默")
                .isEmpty();
        // 同一条事实的第二半，针对别的 logger：**任何一条**被捕获的日志里出现明文都算泄漏。
        // 没有它，「本类保持沉默」就漏掉了「泄漏发生在别的类里」这个同样真实的场景。
        assertThat(messages(events))
                .as("任何一条日志里出现明文渠道密钥都算泄漏")
                .allSatisfy(message -> assertThat(message).doesNotContain(SYNTHETIC_PLAINTEXT));
    }

    private static List<String> messages(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * 只看 {@link ChannelKeyDecryptor} 自己打的事件。
     *
     * <p>捕获挂在 root 上（探针要走同一条路径），因此窗口里可能混进别人的日志（别的线程、别的类）。
     * 「本类保持沉默 / 只留一条 WARN」这两条断言必须按 logger 名过滤 —— 否则它们会随运行环境
     * 抖动，而不是随被测代码变红。
     */
    private static List<String> decryptorMessages(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> ChannelKeyDecryptor.class.getName().equals(event.getLoggerName()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * 捕获 root 上的所有事件（含 DEBUG/TRACE）。
     *
     * <p>级别是**必须显式设置**的：logback 在事件抵达 appender 之前就按有效级别过滤，而 root 的级别
     * 由「谁先初始化 Logback」决定（Spring Boot 的配置是 INFO）。把它降到 {@code TRACE} 并在
     * {@code finally} 里恢复原值，既让 debug 级泄漏可见，也不把级别留给下一个用例。
     *
     * <p>挂在 **root**（而不是 {@code ChannelKeyDecryptor} 自己的 logger）上：正对照探针
     * {@link #WITNESS} 是另一个名字，只有靠 additivity 冒泡到 root 才能与泄漏事件走**同一条**
     * 捕获路径 —— 挂在被测类上时探针根本不会进 appender，那条「捕获链路是活的」就成了假证据。
     */
    private static List<ILoggingEvent> captureEvents(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previousLevel = ROOT.getLevel();
        ROOT.addAppender(appender);
        ROOT.setLevel(Level.TRACE);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            ROOT.setLevel(previousLevel);
            ROOT.detachAppender(appender);
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
