package com.aihub.gateway.upstream;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.gateway.relay.ChannelKeyDecryptor;
import com.aihub.gateway.testsupport.FakeUpstream;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道级客户端的**隔离**性质。{@link UpstreamClientFactoryTest} 那 5 条各自只钉住了一半，
 * 剩下的一半在这里补成真的能红的断言（Task 8 的自审要求）：
 *
 * <ol>
 *   <li><b>凭据逐请求注入，绝不挂在客户端上</b>：{@code channelClientCarriesNoDefaultBearerToken}
 *       只断言「渠道客户端没有默认 Authorization」。它反面的场景 —— 一旦某个渠道的密钥成了默认头，
 *       共享同一个客户端的另一条渠道就会带上它 —— 没有被证伪。这里把两条渠道的**解密结果**分别注入
 *       各自的请求，断言上游每次**只**收到本次请求该有的那一个；并在连续两次带凭据的请求之后，
 *       发一次**不带凭据**的请求：任何「记在客户端上」的实现都会在这里把上一条渠道的密钥送到上游。</li>
 *   <li><b>超时按渠道生效</b>：{@code clientsAreCachedByBaseUrlTimeoutAndMode} 只能证明「超时不同 →
 *       客户端实例不同」；把 {@code responseTimeout} 写成常数（例如遗留客户端的 120 秒），它照样全绿。
 *       这里用「上游写完第一帧就把响应体扣住」的握手来区分：同一个 base-url、同一个工厂，
 *       300ms 的渠道必须在中途失败，3000ms 的渠道必须把整段读完 —— 区别只可能来自各自的
 *       {@code timeout_ms}。流式渠道（100ms）同样必须读完：它**不设** responseTimeout。</li>
 * </ol>
 *
 * <p>本夹具的 {@code HttpServer} 只有一条 dispatch 线程（与 {@code SseStreamingTest} 同一处警告），
 * 因此每个用例独占一个假上游，且**每一次扣帧都保证放行**：成功路径由后台线程延时放行，
 * 失败路径在 {@code finally} 里放行。
 */
class ChannelClientIsolationTest {

    private static final String CHANNEL_A_PLAINTEXT = "sk-channel-a-synthetic";
    private static final String CHANNEL_B_PLAINTEXT = "sk-channel-b-synthetic";

    private static final String FIRST_FRAME = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n";
    private static final String SECOND_FRAME = "data: [DONE]\n\n";

    /** 扣帧时长：**明显大于**短超时渠道的 300ms，又**明显小于**长超时渠道的 3000ms。 */
    private static final long HOLD_MILLIS = 700;

    private static final AesGcmChannelCipher CIPHER = cipher();

    private FakeUpstream upstream;
    private UpstreamClientFactory factory;

    @BeforeEach
    void setUp() {
        upstream = FakeUpstream.start();
        // 遗留 api-key 非空：这样「渠道客户端上意外出现了一个凭据」一定会被断言抓住（哪怕它是遗留密钥）。
        factory = new UpstreamClientFactory(
                new UpstreamProperties(upstream.baseUrl(), "legacy-bearer", "legacy-model"));
    }

    @AfterEach
    void tearDown() {
        // 仍在阻塞等放行的 handler 会拖住下一个用例（单条 dispatch 线程）。
        upstream.releaseSecondFrame();
        upstream.stop();
    }

    @Test
    void theDecryptedKeyReachesTheUpstreamAsThisRequestsCredential() {
        ChannelDescriptor channel = channelWith(11L, CHANNEL_A_PLAINTEXT, 5_000);
        String credential = new ChannelKeyDecryptor(CIPHER, legacy()).upstreamKey(channel).orElseThrow();

        upstream.enqueueJson(200, FakeUpstream.completionJson());
        factory.forChannel(channel, false).post().uri("/v1/chat/completions").bodyValue("{}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));

        assertThat(upstream.lastRequest().headers())
                .as("解密出来的明文必须真的作为凭据被上游收到，而不是只构造出了一个客户端")
                .containsEntry("authorization", "Bearer " + CHANNEL_A_PLAINTEXT);
    }

    /**
     * 两条渠道共享**同一个客户端实例**（同 base-url / 同超时 / 同模式，按设计共享），
     * 因此这是「凭据绝不能挂在客户端上」最硬的场景：任何默认头都会串到另一条渠道。
     */
    @Test
    void aClientSharedByTwoChannelsOnlyEverCarriesTheCredentialOfTheRequest() {
        ChannelDescriptor first = channelWith(11L, CHANNEL_A_PLAINTEXT, 5_000);
        ChannelDescriptor second = channelWith(12L, CHANNEL_B_PLAINTEXT, 5_000);
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(CIPHER, legacy());

        WebClient shared = factory.forChannel(first, false);
        assertThat(factory.forChannel(second, false)).isSameAs(shared);

        send(shared, decryptor.upstreamKey(first).orElseThrow());
        assertThat(upstream.lastRequest().headers())
                .containsEntry("authorization", "Bearer " + CHANNEL_A_PLAINTEXT);

        upstream.clearLastRequest();
        send(shared, decryptor.upstreamKey(second).orElseThrow());
        assertThat(upstream.lastRequest().headers())
                .as("第二条渠道的请求不能带上第一条渠道的凭据")
                .containsEntry("authorization", "Bearer " + CHANNEL_B_PLAINTEXT);

        upstream.clearLastRequest();
        send(shared, null);
        assertThat(upstream.lastRequest().headers())
                .as("连续两次带凭据的请求之后，不带凭据的请求到上游时也必须没有 Authorization"
                        + "（把凭据记在客户端或默认头上的实现会在这里泄露上一条渠道的密钥）")
                .doesNotContainKey("authorization");
    }

    @Test
    void channelsWithDifferentTimeoutsDoNotInheritEachOthersValue() {
        ChannelDescriptor shortTimeout = channelWith(21L, CHANNEL_A_PLAINTEXT, 300);
        ChannelDescriptor longTimeout = channelWith(22L, CHANNEL_A_PLAINTEXT, 3_000);
        WebClient shortClient = factory.forChannel(shortTimeout, false);
        WebClient longClient = factory.forChannel(longTimeout, false);
        assertThat(longClient).isNotSameAs(shortClient);

        upstream.enqueueHandshakeSse(FIRST_FRAME, SECOND_FRAME);
        try {
            assertThatThrownBy(() -> shortClient.post().uri("/v1/chat/completions").bodyValue("{}")
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)))
                    .as("300ms 渠道的非流式客户端必须在响应体被扣住超过 300ms 时失败"
                            + "（把 responseTimeout 写成常数超时的实现会一直等到 5 秒的 block 预算）")
                    // 响应头已经收到（200），失败发生在**读响应体**的中途，因此 WebClient 把它包成
                    // WebClientResponseException（遗留客户端那条「连不上」的路径才是
                    // WebClientRequestException）。根因必须是读超时本身。
                    .isInstanceOf(WebClientException.class)
                    .hasRootCauseInstanceOf(ReadTimeoutException.class);
        } finally {
            upstream.releaseSecondFrame();
        }

        upstream.enqueueHandshakeSse(FIRST_FRAME, SECOND_FRAME);
        releaseSecondFrameAfter(HOLD_MILLIS);
        String body = longClient.post().uri("/v1/chat/completions").bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));

        assertThat(body)
                .as("3000ms 渠道不能被 300ms 那条渠道的超时顶掉：扣帧 " + HOLD_MILLIS + "ms 后仍必须读完")
                .contains("data: [DONE]");
    }

    @Test
    void streamingClientOutlivesItsOwnChannelTimeoutWhenTheStreamIsHeld() {
        ChannelDescriptor streamingChannel = channelWith(23L, CHANNEL_A_PLAINTEXT, 100);
        WebClient streaming = factory.forChannel(streamingChannel, true);

        upstream.enqueueHandshakeSse(FIRST_FRAME, SECOND_FRAME);
        releaseSecondFrameAfter(HOLD_MILLIS);

        String body = streaming.post().uri("/v1/chat/completions").bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));

        assertThat(body)
                .as("流式客户端不设 responseTimeout：100ms 的渠道超时不能掐断一段被扣住 "
                        + HOLD_MILLIS + "ms 的 SSE")
                .contains(FIRST_FRAME.strip())
                .contains("data: [DONE]");
    }

    private void send(WebClient client, String credential) {
        upstream.enqueueJson(200, FakeUpstream.completionJson());
        WebClient.RequestHeadersSpec<?> request = client.post().uri("/v1/chat/completions").bodyValue("{}");
        if (credential != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
        }
        request.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));
    }

    /** 延时放行上游的第二帧：请求必须在**扣帧期间**一直活着，超时语义才真的被验证。 */
    private void releaseSecondFrameAfter(long millis) {
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            upstream.releaseSecondFrame();
        }, "fake-upstream-release");
        releaser.setDaemon(true);
        releaser.start();
    }

    private static AesGcmChannelCipher cipher() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 1);
        }
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse("v1:" + Base64.getEncoder().encodeToString(key)));
    }

    private ChannelDescriptor channelWith(long id, String plaintext, int timeoutMs) {
        return new ChannelDescriptor(id, "ch-" + id, upstream.baseUrl(), CIPHER.encrypt(plaintext), 1, timeoutMs,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    private UpstreamProperties legacy() {
        return new UpstreamProperties(upstream.baseUrl(), "legacy-bearer", "legacy-model");
    }
}
