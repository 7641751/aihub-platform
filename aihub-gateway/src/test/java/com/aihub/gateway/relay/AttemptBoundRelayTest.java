package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.config.ConfigClient;
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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上界（{@link RelayAttempts#MAX_ATTEMPTS}）必须**落在转发循环本身**，而不能只靠「进入循环之前把
 * 候选列表截断」。截断管得住「候选有几条」，管不住「同一条候选被订阅几次」。
 *
 * <p><b>被钉住的缺陷</b>：当「下一个候选」是在 {@code Mono.defer} 的受保护区域内递归订阅、而同一个
 * 逃逸的上游失败又在该区域的 {@code onErrorResume} 里**再次**订阅同一个下标时，一次
 * {@code attempt(i)} 会展开成 {@code C(m) = 1 + 2·C(m-1)}：3 条候选的混合故障
 * （503 → 429 → 连不上）实测产生 **7 次**上游调用（第 3 条被订阅 4 次，并且把刚刚被打上熔断标记的
 * 429 渠道又打了一遍）。
 *
 * <p>本类用**记录型工厂**（每次 {@code forChannel} 恰好对应一次上游调用）把这件事变成可数的数字，
 * 并用 {@link FakeUpstream#requestCount()} 证明「每条候选各一次」；客户端拿到的仍是真实的最后一个
 * 失败（连不上 → 502 {@code upstream_unreachable}），因此这条上界没有以牺牲行为为代价。
 *
 * <p>用 {@link MockServerWebExchange} + 桩 {@code RouteResolver}：候选顺序与故障形状由用例显式
 * 声明，毫秒级、不依赖 Redis、不依赖 Spring 上下文。
 */
class AttemptBoundRelayTest {

    private static final String SYNTHETIC_PLAINTEXT = "sk-channel-plaintext-synthetic";
    private static final String MODEL = "attempt-bound-model";

    /** 没有任何进程监听的端口：连上去必然被拒（本套件已有多处用同一构造，见 {@code UpstreamClientFactoryTest}）。 */
    private static final String DEAD_BASE_URL = "http://127.0.0.1:1";

    private static final AesGcmChannelCipher CIPHER = cipher();

    private FakeUpstream primary;
    private FakeUpstream standby;
    private RecordingClientFactory clientFactory;
    private ChannelCircuitBreaker circuitBreaker;
    private MeteringPublisher meteringPublisher;
    private ChatRelayController controller;

    @BeforeEach
    void setUp() {
        primary = FakeUpstream.start();
        standby = FakeUpstream.start();
        clientFactory = new RecordingClientFactory(new UpstreamProperties(DEAD_BASE_URL, null, MODEL));
        circuitBreaker = mock(ChannelCircuitBreaker.class);
        lenient().when(circuitBreaker.isOpen(anyLong())).thenReturn(false);
        meteringPublisher = mock(MeteringPublisher.class);

        RouteResolver routeResolver = mock(RouteResolver.class);
        // priority 升序 = 尝试顺序：11（503）→ 12（429）→ 13（连不上）。
        when(routeResolver.candidates(MODEL)).thenReturn(List.of(
                channel(11L, "primary", primary.baseUrl(), 0),
                channel(12L, "standby", standby.baseUrl(), 1),
                channel(13L, "third-refuses-connections", DEAD_BASE_URL, 2)));
        ConfigClient configClient = mock(ConfigClient.class);
        lenient().when(configClient.current()).thenReturn(ConfigSnapshot.empty());

        controller = new ChatRelayController(clientFactory, configClient, routeResolver, circuitBreaker,
                new ChannelKeyDecryptor(CIPHER, new UpstreamProperties(DEAD_BASE_URL, null, MODEL)),
                meteringPublisher,
                new MeteringProperties(false, 65536, 100, 10, 30_000L, 5_000L, 5_000L, "unused-spool-dir"));
    }

    @AfterEach
    void tearDown() {
        primary.stop();
        standby.stop();
    }

    /**
     * 混合故障：候选 1 返回 503（切）、候选 2 返回 429（打熔断标记后切）、候选 3 连不上（上游失败）。
     *
     * <p>三条断言分别钉住上界的三个面：
     * <ol>
     *   <li>{@link #clientFactory} 的调用序列 = 每条候选**恰好一次**（同一条候选被订阅两次就会多出
     *       一个 id）；</li>
     *   <li>总上游调用数 ≤ {@link RelayAttempts#MAX_ATTEMPTS}（放大上界的字面形式）；</li>
     *   <li>两条真实夹具各收到**恰好一个**请求（{@code lastRequest != null} 这种老断言看不见重复）。</li>
     * </ol>
     * 429 渠道只能被打一次：被重复打意味着刚写下的熔断标记又被自己重新命中，上界因此失去意义。
     */
    @Test
    void mixedFailuresContactEveryCandidateExactlyOnceAndNeverExceedTheCap() throws Exception {
        // 队列各放两份：缺陷实现会把 503 / 429 渠道**再打一遍**，第二份保证那一次仍然得到同一个
        // 状态码（否则夹具会按「队列空 → 200」放行，把计数问题伪装成一次成功的切换）。
        primary.enqueueError(503, "{\"error\":{\"message\":\"down-primary\"}}");
        primary.enqueueError(503, "{\"error\":{\"message\":\"down-primary\"}}");
        standby.enqueueError(429, "{\"error\":{\"message\":\"rate limited\"}}");
        standby.enqueueError(429, "{\"error\":{\"message\":\"rate limited\"}}");

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body()));

        controller.chatCompletions(body(), exchange).block(Duration.ofSeconds(15));

        assertThat(exchange.getResponse().getStatusCode())
                .as("最后一个候选连不上：客户端拿到的是 502 upstream_unreachable（不是被吞掉的 200）")
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"upstream_unreachable\"");

        assertThat(clientFactory.requestedChannelIds())
                .as("每条候选**恰好被订阅一次**（缺陷实现的实测序列是 [11, 12, 13, 13, 12, 13, 13]）")
                .containsExactly(11L, 12L, 13L);
        assertThat(clientFactory.requestedChannelIds().size())
                .as("一次客户端请求的上游调用总数绝不得超过上界 %s", RelayAttempts.MAX_ATTEMPTS)
                .isLessThanOrEqualTo(RelayAttempts.MAX_ATTEMPTS);
        assertThat(primary.requestCount()).as("首选渠道只被打过一次").isEqualTo(1);
        assertThat(standby.requestCount()).as("429 渠道只被打过一次（不得被重新命中）").isEqualTo(1);

        // 熔断标记只属于返回 429 的那条，且只打一次：5xx 与连不上都不得打（决策 10）。
        verify(circuitBreaker, times(1)).markOpen(12L);
        verify(circuitBreaker, never()).markOpen(11L);
        verify(circuitBreaker, never()).markOpen(13L);

        ArgumentCaptor<MeteringEvent> events = ArgumentCaptor.forClass(MeteringEvent.class);
        verify(meteringPublisher).publish(events.capture());
        assertThat(events.getValue().channelId())
                .as("事件里的 channel_id 是最后一次**真正发出上游请求**的那条候选")
                .isEqualTo(13L);
    }

    private static String body() {
        return "{\"model\":\"" + MODEL + "\",\"stream\":false}";
    }

    private static ChannelDescriptor channel(long id, String name, String baseUrl, int priority) {
        return new ChannelDescriptor(id, name, baseUrl, CIPHER.encrypt(SYNTHETIC_PLAINTEXT), 1, 5_000,
                ChannelDescriptor.STATUS_ACTIVE, 100, priority);
    }

    private static AesGcmChannelCipher cipher() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 5 + 3);
        }
        return new AesGcmChannelCipher(
                ChannelKeyRegistry.parse("v1:" + Base64.getEncoder().encodeToString(key)));
    }

    /** 真实的工厂 + 一层记录：{@code forChannel} 每被调用一次就对应一次上游请求。 */
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
