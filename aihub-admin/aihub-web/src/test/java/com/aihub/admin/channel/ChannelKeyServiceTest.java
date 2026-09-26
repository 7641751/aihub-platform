package com.aihub.admin.channel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.service.channel.ChannelKeyService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * admin 侧的加密服务。**不需要容器、不需要 Spring**：它只是一层「主密钥 → 密文」的薄封装，
 * 真正的密码学在 {@code aihub-common} 的 {@code AesGcmChannelCipher} 里（两侧共用）。
 *
 * <p>这里最重要的一条是**跨侧互操作**：admin 产出的密文必须能被 gateway 侧的实现解开。
 * 那也是「加密实现只许有一份」这条决策的可证伪形式。
 *
 * <p>另外三条守卫也是本类的核心：
 * <ul>
 *   <li>无主密钥时**响亮地失败**（{@code IllegalStateException}），而不是把坏数据写进
 *       {@code channel.api_key_cipher}；</li>
 *   <li>明文为 {@code null}/空白时同样**响亮地失败**（{@code IllegalArgumentException}），
 *       而不是把它当成空串加密出一条「key 为空的渠道」（
 *       {@link #encryptRejectsNullAndBlankInsteadOfStoringACipherTextOfTheEmptyString()}）；</li>
 *   <li>加解密**一个字都不进日志**（{@link #cryptoOperationsNeverWriteKeyMaterialToTheLog()}）——
 *       明文只可能从日志泄漏，这条必须被证明，不能只靠「实现里没有 log 语句」的承诺。</li>
 * </ul>
 */
class ChannelKeyServiceTest {

    private static final String SYNTHETIC = "sk-channel-plaintext-synthetic";

    /** 正对照用的命名 logger：与被测类无关，事件靠 additivity 冒泡到 root（root 上挂着捕获 appender）。 */
    private static final org.slf4j.Logger WITNESS = LoggerFactory.getLogger("aihub.crypto.log-capture.witness");

    /** 正对照事件的消息；它本身不含任何密钥材料。 */
    private static final String WITNESS_MESSAGE = "witness:crypto-log-capture-is-live";

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

    /**
     * 写入路径对「一定是调用方 bug」的输入也要响亮地失败。
     *
     * <p>早先的实现把 {@code null} 映射成空串，于是 {@code encrypt(null)} 会**成功地**返回一段
     * 「空字符串的密文」—— 调用方一路无阻地把「key 为空的渠道」写进 {@code channel.api_key_cipher}，
     * 直到请求打上去才以「上游 401」的形式暴露。这与本类「写入路径必须响亮地失败」的纪律相矛盾。
     */
    @Test
    void encryptRejectsNullAndBlankInsteadOfStoringACipherTextOfTheEmptyString() {
        String masterKeyB64 = b64Key(1);
        ChannelKeyService service = new ChannelKeyService("v1:" + masterKeyB64);

        Throwable fromNull = catchThrowable(() -> service.encrypt(null));
        Throwable fromBlank = catchThrowable(() -> service.encrypt(" \t\n "));

        assertThat(fromNull).as("null 必须被拒绝，而不是被当成空串加密").isInstanceOf(IllegalArgumentException.class);
        assertThat(fromBlank).as("空白（isBlank：\"\"、\" \"、制表符/换行）同样必须被拒绝")
                .isInstanceOf(IllegalArgumentException.class);
        // 「消息里不含任何值」的可证伪形式：两个不同的被拒输入必须得到**逐字相同**的消息
        // （回显值的实现会给出「加密 null」与「加密  \t\n 」两种），且消息里没有主密钥。
        assertThat(fromBlank.getMessage()).isEqualTo(fromNull.getMessage());
        assertThat(fromNull.getMessage()).isNotBlank().doesNotContain(masterKeyB64);
        // 守卫只拒绝「值缺失」：合法输入照旧加密（没有把写入路径收窄成拒绝正常数据）。
        assertThat(service.decrypt(service.encrypt(SYNTHETIC))).contains(SYNTHETIC);
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
     * 再断言**所有**日志事件里既没有明文、也没有落库密文、也没有主密钥的 base64。
     *
     * <p>用 root logger（而不是 {@code ChannelKeyService} 自己的 logger）是有意的：泄漏可能来自
     * 本类之外的任何一层（例如某天有人在 {@code AesGcmChannelCipher} 里加了日志），只监听本类会漏掉它。
     *
     * <p>两处「让断言真的能红」的构造，缺一不可：
     * <ul>
     *   <li>{@link #captureAllEvents} 在动作期间把 root 的级别降到 {@code TRACE}、并在 {@code finally}
     *       里**恢复**：logback 是**先按 logger 的有效级别过滤、再把事件交给 appender**，
     *       不设级别时本用例的敏感度就取决于「哪个类先初始化了 Logback」—— 全反应堆里 root 常是
     *       {@code INFO}，一句 {@code log.debug("加密 {}", plaintext)} 会被静默漏掉；</li>
     *   <li>动作跑完后，用**同一条到达路径**发一条正对照事件（{@link #WITNESS}，DEBUG 级，
     *       走的正是威胁模型里的那一条）并断言它**确实被捕获**：没有它，「一个事件都没捕获到」
     *       与「没有泄漏」无法区分，空捕获会让下面每一条断言恒真。</li>
     * </ul>
     */
    @Test
    void cryptoOperationsNeverWriteKeyMaterialToTheLog() {
        String masterKeyB64 = b64Key(7);
        ChannelKeyService service = new ChannelKeyService("v1:" + masterKeyB64);
        AtomicReference<String> cipherText = new AtomicReference<>();

        List<ILoggingEvent> events = captureAllEvents(() -> {
            cipherText.set(service.encrypt(SYNTHETIC));
            assertThat(service.decrypt(cipherText.get())).contains(SYNTHETIC);
            assertThat(service.decrypt("garbage")).isEmpty();
            assertThatThrownBy(() -> new ChannelKeyService("").encrypt(SYNTHETIC))
                    .isInstanceOf(IllegalStateException.class);
            // 正对照：以 DEBUG 级发一条自己的事件 —— 这正是「log.debug 泄漏会被抓到吗」那条路径。
            WITNESS.debug(WITNESS_MESSAGE);
        });

        // 先证明捕获链路是活的（appender 还挂在 root 上、且当前级别允许 DEBUG 抵达它），
        // 再断言没泄漏。顺序不能反：没有这条，「捕获为空」会让下面三条断言全部静默通过。
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .as("正对照：探针事件必须被捕获，否则本用例的「没有泄漏」是空的")
                .contains(WITNESS_MESSAGE);
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .as("任何一条日志里出现明文渠道密钥都算泄漏")
                .allSatisfy(message -> assertThat(message).doesNotContain(SYNTHETIC));
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .as("任何一条日志里出现落库密文都算泄漏")
                .allSatisfy(message -> assertThat(message).doesNotContain(cipherText.get()));
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .as("任何一条日志里出现主密钥 base64 都算泄漏")
                .allSatisfy(message -> assertThat(message).doesNotContain(masterKeyB64));
    }

    /**
     * 捕获 root 上的所有事件（含 DEBUG/TRACE）。
     *
     * <p>级别是**必须显式设置**的：logback 在事件抵达 appender 之前就按有效级别过滤，而 root 的级别
     * 由「谁先初始化 Logback」决定（Spring Boot 的 logback 配置是 INFO）。把它降到 {@code TRACE}
     * 并在 {@code finally} 里恢复原值，既让 debug 级泄漏可见，也不把级别留给下一个用例。
     */
    private static List<ILoggingEvent> captureAllEvents(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level previousLevel = root.getLevel();
        root.addAppender(appender);
        root.setLevel(Level.TRACE);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            root.setLevel(previousLevel);
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
