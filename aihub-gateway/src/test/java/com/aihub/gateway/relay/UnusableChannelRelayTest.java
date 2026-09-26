package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.meter.MeteringProperties;
import com.aihub.gateway.meter.MeteringPublisher;
import com.aihub.gateway.route.ChannelCircuitBreaker;
import com.aihub.gateway.route.RouteResolver;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.upstream.UpstreamClientFactory;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G15：候选渠道必须过 {@link ChannelDescriptor#usable()} 这道**唯一**的可用性判据，否则中继会把一条
 * 不可用的渠道交给 {@link UpstreamClientFactory}：
 * <ul>
 *   <li>{@code baseUrl} 为空/空白 → WebClient builder 直接拒绝（异常从中继里穿出去 = 客户端 500）；</li>
 *   <li>{@code timeoutMs <= 0} → 被静默夹到 1ms，一条配错的渠道变成「每个请求都瞬间超时」。</li>
 * </ul>
 *
 * <p>为什么这里需要一个**桩** RouteResolver：真实的 {@code RouteResolver} 自己也会过滤
 * {@code usable()}，所以端到端用例无法区分「中继过滤了」与「路由过滤了」—— 改动路由（或将来有人
 * 从别处喂候选，例如遗留兜底路径）就会让中继的这道门形同虚设。这里直接喂一条不可用的候选，
 * 判别性是二值的：中继不过滤 → 客户端拿不到 200（工厂收到了一条不可用渠道 / 异常穿出）。
 *
 * <p>用 {@link MockServerWebExchange} 而不是端到端：本类要证的是**中继自己的**一道门，
 * 与鉴权/限流/控制面无关，全部依赖（除了真实的工厂与真实的假上游）都用桩，因此毫秒级且不依赖 Redis。
 */
class UnusableChannelRelayTest {

    private static final String SYNTHETIC_PLAINTEXT = "sk-channel-plaintext-synthetic";
    private static final String MODEL = "unusable-model";

    private static final AesGcmChannelCipher CIPHER = cipher();

    private FakeUpstream upstream;
    private RecordingClientFactory clientFactory;
    private RouteResolver routeResolver;
    private ConfigClient configClient;
    private ChannelCircuitBreaker circuitBreaker;
    private ChatRelayController controller;

    @BeforeEach
    void setUp() {
        upstream = FakeUpstream.start();
        clientFactory = new RecordingClientFactory(
                new UpstreamProperties(upstream.baseUrl(), null, MODEL));
        routeResolver = mock(RouteResolver.class);
        configClient = mock(ConfigClient.class);
        circuitBreaker = mock(ChannelCircuitBreaker.class);
        lenient().when(circuitBreaker.isOpen(anyLong())).thenReturn(false);
        lenient().when(configClient.current()).thenReturn(ConfigSnapshot.empty());

        controller = new ChatRelayController(clientFactory, configClient, routeResolver, circuitBreaker,
                new ChannelKeyDecryptor(CIPHER, new UpstreamProperties("http://127.0.0.1:1", null, MODEL)),
                mock(MeteringPublisher.class),
                new MeteringProperties(false, 65536, 100, 10, 30_000L, 5_000L, 5_000L, "unused-spool-dir"));
    }

    @AfterEach
    void tearDown() {
        upstream.stop();
    }

    /**
     * 候选顺序里**第一条**就是不可用的（空白 base-url），第二条带密文密钥且可用。中继必须丢掉第一条、
     * 直接用第二条服务；工厂**一次都不该**看到第一条。
     */
    @Test
    void anUnusableCandidateIsNeverHandedToTheClientFactory() throws Exception {
        ChannelDescriptor unusable = new ChannelDescriptor(12L, "broken-no-base-url", "   ",
                cipherText(), 1, 5_000, ChannelDescriptor.STATUS_ACTIVE, 100, 0);
        ChannelDescriptor usable = new ChannelDescriptor(11L, "primary", upstream.baseUrl(),
                cipherText(), 1, 5_000, ChannelDescriptor.STATUS_ACTIVE, 100, 1);
        assertThat(unusable.usable()).as("夹具前提：第一条候选必须确实是不可用的").isFalse();
        assertThat(usable.usable()).isTrue();
        when(routeResolver.candidates(MODEL)).thenReturn(List.of(unusable, usable));
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        MockServerWebExchange exchange = exchange();

        controller.chatCompletions(body(), exchange).block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("chatcmpl-1");
        assertThat(clientFactory.requestedChannelIds())
                .as("不可用的候选绝不能被交给工厂（G15）；可用那条才是被服务的那条")
                .containsExactly(11L);
    }

    /**
     * 遗留兜底路径是同一道门的第二个落点：快照里确实有遗留渠道、但它的 base-url 是空白
     * （= 不可用）时，中继必须回 404 {@code model_not_found}，而不是把这条渠道交给工厂
     * （那会变成客户端 500，且泄漏一个内部异常）。
     */
    @Test
    void anUnusableLegacyFallbackChannelYields404InsteadOfBeingHandedToTheFactory() throws Exception {
        ChannelDescriptor brokenLegacy = new ChannelDescriptor(LegacyChannel.ID, "legacy-single-channel", "",
                null, 0, 60_000, ChannelDescriptor.STATUS_ACTIVE, 100, 0);
        assertThat(brokenLegacy.usable()).as("夹具前提：遗留渠道必须是不可用的").isFalse();
        when(routeResolver.candidates(MODEL)).thenReturn(List.of());
        when(configClient.current()).thenReturn(new ConfigSnapshot(1L, 1L, List.of(brokenLegacy),
                List.of(), List.of(), MODEL));
        when(configClient.legacyChannel()).thenReturn(brokenLegacy);


        MockServerWebExchange exchange = exchange();

        controller.chatCompletions(body(), exchange).block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"model_not_found\"");
        assertThat(clientFactory.requestedChannelIds()).isEmpty();
    }

    /**
     * 回落路径的第二道门：{@code configClient.legacyChannel()} 交回来的描述符必须**真的是遗留哨兵**
     * （id = {@link LegacyChannel#ID}）。
     *
     * <p>为什么需要这道门：{@code keyDecryptor.canServe(legacy)} 在这条路径上是**恒真**的
     * （{@code ChannelKeyDecryptor} 对哨兵无条件返回 {@code Optional.of}），因此它钉不住任何东西；
     * 而「回落路径会不会把一条**任意**渠道当成兜底渠道发出去」是一个能真实出错的判断。这里喂一条
     * id=99 的真实渠道（base-url 可用、密钥可解），中继必须拒绝它并回 404 —— 而不是悄悄服务它。
     */
    @Test
    void aFallbackDescriptorThatIsNotTheLegacySentinelIsNeverServed() throws Exception {
        ChannelDescriptor snapshotLegacy = new ChannelDescriptor(LegacyChannel.ID, "legacy-single-channel",
                upstream.baseUrl(), null, 0, 60_000, ChannelDescriptor.STATUS_ACTIVE, 100, 0);
        ChannelDescriptor impostor = new ChannelDescriptor(99L, "not-the-legacy-channel", upstream.baseUrl(),
                cipherText(), 1, 5_000, ChannelDescriptor.STATUS_ACTIVE, 100, 0);
        assertThat(impostor.usable()).as("夹具前提：这条「假兜底」自己是可用的（密钥也可解）").isTrue();
        when(routeResolver.candidates(MODEL)).thenReturn(List.of());
        when(configClient.current()).thenReturn(new ConfigSnapshot(1L, 1L, List.of(snapshotLegacy),
                List.of(), List.of(), MODEL));
        when(configClient.legacyChannel()).thenReturn(impostor);

        MockServerWebExchange exchange = exchange();

        controller.chatCompletions(body(), exchange).block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode())
                .as("不是哨兵的兜底描述符不得被服务（它是配置/装配错误，不是一条可用路由）")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(clientFactory.requestedChannelIds()).isEmpty();
    }

    /**
     * 遗留单渠道返回 429 时**不得**写熔断键：哨兵 id
     * （{@code -9223372036854775808}）不是 {@code channel} 表里的真实主键，为它写一条
     * {@code aihub:channel:circuit:<哨兵>} 会让「这个键空间里只有真实渠道 id」不再成立。
     *
     * <p>判别性：修前那条路径会调用 {@code markOpen(Long.MIN_VALUE)}，{@code never()} 立刻红。
     * 429 本身仍然必须原样透传（这条渠道是唯一候选，没有下一个可切）。
     */
    @Test
    void aLegacySentinelFourTwoNineIsRelayedWithoutWritingACircuitKey() throws Exception {
        ChannelDescriptor legacy = LegacyChannel.of(
                new UpstreamProperties(upstream.baseUrl(), null, MODEL));
        when(routeResolver.candidates(MODEL)).thenReturn(List.of());
        when(configClient.current()).thenReturn(new ConfigSnapshot(1L, 1L, List.of(legacy),
                List.of(), List.of(), MODEL));
        when(configClient.legacyChannel()).thenReturn(legacy);
        upstream.enqueueError(429, "{\"error\":{\"message\":\"legacy upstream rate limited\"}}");

        MockServerWebExchange exchange = exchange();

        controller.chatCompletions(body(), exchange).block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("legacy upstream rate limited");
        verify(circuitBreaker, never()).markOpen(anyLong());
        assertThat(clientFactory.requestedChannelIds()).containsExactly(LegacyChannel.ID);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body()));
    }

    private static String body() {
        return "{\"model\":\"" + MODEL + "\",\"stream\":false}";
    }

    private static String cipherText() {
        return CIPHER.encrypt(SYNTHETIC_PLAINTEXT);
    }

    private static AesGcmChannelCipher cipher() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 5 + 3);
        }
        return new AesGcmChannelCipher(
                ChannelKeyRegistry.parse("v1:" + Base64.getEncoder().encodeToString(key)));
    }

    /** 真实的工厂 + 一层记录：断言「哪条渠道被交给过工厂」，这是 G15 的可证伪形式。 */
    private static final class RecordingClientFactory extends UpstreamClientFactory {

        private final List<Long> requested = new ArrayList<>();

        private RecordingClientFactory(UpstreamProperties properties) {
            super(properties);
        }

        @Override
        public WebClient forChannel(ChannelDescriptor channel, boolean streaming) {
            requested.add(channel.id());
            return super.forChannel(channel, streaming);
        }

        private List<Long> requestedChannelIds() {
            return List.copyOf(requested);
        }
    }
}
