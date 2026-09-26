package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.upstream.UpstreamProperties;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败转移的**分类规则**是 M3 最容易写错的地方：哪些状态码该换渠道、哪些必须原样透传。
 * 规则本身是纯函数，因此可以在这里逐条钉死，而不必靠端到端测试去凑。
 *
 * <p>核心区分：
 * <ul>
 *   <li><b>可切换</b>：连接失败/超时（{@code WebClientRequestException}）、上游 5xx、上游 429；</li>
 *   <li><b>不可切换</b>：上游 4xx（非 429）——那是客户端的错，换渠道一样错，重试只会放大；</li>
 *   <li><b>必须保留候选</b>：所有候选的密钥都解不开时，也要给出候选（让真实的上游错误浮现），
 *       而不是把它变成 {@code model_not_found} 404；</li>
 *   <li><b>按原因而不是按异常类型</b>判定「上游失败」：响应头已到（200）之后发生的**读超时**
 *       会被包成 {@code WebClientResponseException}（Task 8 实测），按类型判会把它当成成功或客户端断连。</li>
 * </ul>
 */
class RelayAttemptsTest {

    private static final String SYNTHETIC_PLAINTEXT = "sk-channel-plaintext-synthetic";

    private static String masterKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 5);
        }
        return "v1:" + Base64.getEncoder().encodeToString(key);
    }

    private static AesGcmChannelCipher cipher() {
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey()));
    }

    private static ChannelDescriptor channel(long id, String cipherText) {
        return new ChannelDescriptor(id, "ch-" + id, "https://ch" + id + ".example.com", cipherText, 1, 5_000,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    private static ChannelKeyDecryptor decryptor(AesGcmChannelCipher cipher, UpstreamProperties upstream) {
        return new ChannelKeyDecryptor(cipher, upstream);
    }

    private static UpstreamProperties noLegacyKey() {
        return new UpstreamProperties("http://127.0.0.1:1", null, "m");
    }

    @Test
    void failoverIsAllowedFor5xxAnd429() {
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(500)).isTrue();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(502)).isTrue();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(503)).isTrue();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(429)).isTrue();
    }

    @Test
    void failoverIsRejectedForClientErrorsAndSuccess() {
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(400)).isFalse();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(401)).isFalse();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(403)).isFalse();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(404)).isFalse();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(422)).isFalse();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(200)).isFalse();
    }

    @Test
    void clientErrorsAreNotRetriedEvenThoughTheyAreErrors() {
        assertThat(RelayAttempts.isClientErrorThatMustNotBeRetried(400)).isTrue();
        assertThat(RelayAttempts.isClientErrorThatMustNotBeRetried(429)).as("429 是唯一要切换的 4xx").isFalse();
        assertThat(RelayAttempts.isClientErrorThatMustNotBeRetried(500)).isFalse();
    }

    @Test
    void servableFiltersOutUndecryptableChannelsInOrder() {
        AesGcmChannelCipher cipher = cipher();
        List<ChannelDescriptor> candidates = List.of(
                channel(1L, "v9:QUJD"),
                channel(2L, cipher.encrypt(SYNTHETIC_PLAINTEXT)),
                channel(3L, cipher.encrypt(SYNTHETIC_PLAINTEXT)));

        assertThat(RelayAttempts.servable(candidates, decryptor(cipher, noLegacyKey())))
                .extracting(ChannelDescriptor::id).containsExactly(2L, 3L);
    }

    /** 全部解不开时**不返回空**：否则「主密钥配错」会伪装成「这个模型不存在」（404）。 */
    @Test
    void servableKeepsEverythingWhenNoCandidateIsDecryptable() {
        ChannelKeyDecryptor broken = decryptor(new AesGcmChannelCipher(ChannelKeyRegistry.parse("")), noLegacyKey());
        List<ChannelDescriptor> candidates = List.of(channel(1L, "v1:QUJD"), channel(2L, "v2:QUJD"));

        assertThat(RelayAttempts.servable(candidates, broken)).isEqualTo(candidates);
    }

    /**
     * 遗留单渠道的哨兵渠道**永远可服务**，与主密钥无关（它的密钥来自 {@code aihub.upstream.api-key}，
     * 不是密文）。
     *
     * <p>判别力来自那个**坏掉的**解密器：同一个 {@code servable} 调用里还放了一条真实渠道，它的密文
     * 在这个解密器下解不开。因此「结果恰好只含哨兵」这件事只有在「哨兵不走密文解密」时才成立 ——
     * 把哨兵也送去解密，它就会与真实渠道一起被滤掉，{@code servable} 于是退回**原列表**，断言红。
     * 旧版本用「好解密器 + 只有哨兵」断言 {@code hasSize(1)}，那是恒真的：{@code ChannelKeyDecryptor}
     * 对哨兵无条件返回 {@code Optional.of}，所以无论实现怎么改它都会绿。
     */
    @Test
    void legacyChannelIsAlwaysServable() {
        UpstreamProperties legacy = new UpstreamProperties("http://127.0.0.1:11434", "", "m");
        ChannelKeyDecryptor broken = decryptor(new AesGcmChannelCipher(ChannelKeyRegistry.parse("")), legacy);
        ChannelDescriptor legacyChannel = LegacyChannel.of(legacy);
        ChannelDescriptor realButUndecryptable = channel(1L, "v1:QUJD");

        assertThat(RelayAttempts.servable(List.of(legacyChannel, realButUndecryptable), broken))
                .as("哨兵的密钥来自 aihub.upstream.api-key：主密钥为空也必须留在可服务列表里")
                .containsExactly(legacyChannel);
        assertThat(broken.canServe(realButUndecryptable))
                .as("对照：这个解密器确实什么都解不开（否则上面那条断言没有判别力）")
                .isFalse();
    }

    @Test
    void modelIsTruncatedToTheRequestLogColumnWidth() {
        assertThat(RelayAttempts.MODEL_MAX_LENGTH).as("必须与 V1 的 model VARCHAR(128) 一致").isEqualTo(128);
        assertThat(RelayAttempts.truncateModel("x".repeat(200))).hasSize(128);
        assertThat(RelayAttempts.truncateModel("short")).isEqualTo("short");
        assertThat(RelayAttempts.truncateModel(null)).isNull();
        // 中文按字符截断（JDBC 的 VARCHAR(128) 在 utf8mb4 下按字符计）。
        assertThat(RelayAttempts.truncateModel("模".repeat(200))).hasSize(128);
    }

    /**
     * 尝试次数上界（G10）：候选列表里可能含有该模型**全部**优先级组的渠道，没有上界时一次客户端
     * 请求会放大成「每一条渠道各一次上游调用」。上界必须是显式的小字面量（首选 + 两条备用）。
     */
    @Test
    void attemptCapIsAnExplicitSmallLiteral() {
        assertThat(RelayAttempts.MAX_ATTEMPTS)
                .as("上界必须是显式登记的小数字：改动它必须同时改这里（评审按此判断）")
                .isEqualTo(3);
    }

    /**
     * G14：**按原因链**判定上游失败，而不是按异常类型。
     *
     * <p>判别性在于第二条：一个状态 200 的 {@code WebClientResponseException}（响应头已经收到，
     * 失败发生在读响应体的中途 —— Task 8 实测的形状）如果把根因藏起来就会被判成「非上游失败」，
     * 于是一次被掐断的流在计量里变成 SUCCESS 或客户端断连。
     */
    @Test
    void upstreamFailuresAreClassifiedByCauseNotByExceptionType() {
        WebClientRequestException connectFailure = new WebClientRequestException(
                new IOException("Connection refused"), HttpMethod.POST,
                URI.create("http://127.0.0.1:1/v1/chat/completions"), HttpHeaders.EMPTY);
        assertThat(RelayAttempts.isUpstreamFailure(connectFailure))
                .as("连不上：响应头根本没到，WebClient 包成 WebClientRequestException")
                .isTrue();

        WebClientResponseException readTimeoutWithStatus200 = WebClientResponseException.create(
                200, "OK", HttpHeaders.EMPTY, new byte[0], null);
        readTimeoutWithStatus200.initCause(ReadTimeoutException.INSTANCE);
        assertThat(RelayAttempts.isUpstreamFailure(readTimeoutWithStatus200))
                .as("读超时：状态是 200，类型是 WebClientResponseException，只有原因链认得出来")
                .isTrue();

        assertThat(RelayAttempts.isUpstreamFailure(new IOException("模拟：响应已提交之后写回客户端失败")))
                .as("普通 IOException 不是上游失败：响应已提交之后的写失败属于客户端断连那条分支")
                .isFalse();
        assertThat(RelayAttempts.isUpstreamFailure(null)).isFalse();
    }
}
