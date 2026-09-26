package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.meter.MeteringDispatcher;
import com.aihub.gateway.meter.MeteringProperties;
import com.aihub.gateway.meter.MeteringPublisher;
import com.aihub.gateway.meter.MeteringSpool;
import com.aihub.gateway.route.ChannelCircuitBreaker;
import com.aihub.gateway.route.RouteResolver;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.testsupport.RecordingMeteringTransport;
import com.aihub.gateway.trace.RequestIdFilter;
import com.aihub.gateway.upstream.UpstreamClientFactory;
import com.aihub.gateway.upstream.UpstreamProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 决策 7 的「响应**已提交之后**写回客户端失败」分支（{@code ChatRelayController} 里那个
 * {@code onErrorResume(ex)} → {@code metering.onClientDisconnected()}）的钉子。
 *
 * <p><b>为什么必须单独钉</b>：{@code SseStreamingTest.clientDisconnectIsMeteredAsCancelled()}
 * 虽然在真实 HTTP 上断连，但 JDK HttpClient 关流走的是**取消订阅**：{@code doFinally} 收到 CANCEL，
 * 事件里的 {@code CANCELLED} / {@code client_disconnected} 由
 * {@code RelayMetering.toEvent} 的 CANCEL 分支产生，与控制器这个分支无关。实测：把
 * {@code onClientDisconnected()} 改成直接抛异常，那条端到端用例**照样全绿** —— 也就是说，
 * 该分支此前没有任何测试覆盖，而且「断连用例绿」这件事本身证明不了它。
 *
 * <p><b>为什么不用端到端</b>：端到端无法稳定造出「已提交 + 写失败」（Netty 在客户端半关时给的是
 * 取消而不是错误）。所以这里把**客户端侧**换成确定性的 {@link MockServerWebExchange}：
 * 字节照常读出去（计量观察者因此看到内容），随后写回失败；其余全是生产路径 —— 真实 WebClient、
 * 真实假上游、真实控制器与计量链（{@code MeteringPublisher → MeteringDispatcher → transport}）。
 *
 * <p><b>判别力</b>：控制器在这个分支里用 {@code response.setComplete()} 收尾，所以终态信号是
 * {@code ON_COMPLETE} 而不是 {@code CANCEL}。{@code ON_COMPLETE} 下事件仍是 {@code CANCELLED} +
 * {@code client_disconnected}，**只可能**是因为 {@code onClientDisconnected()} 真的被调用过
 * （把该调用去掉，事件会变成 SUCCESS + usage_missing，本条即红）。
 */
class RelayCommittedWriteFailureTest {

    private static FakeUpstream upstream;

    private RecordingMeteringTransport transport;
    private MeteringDispatcher dispatcher;
    private ChatRelayController controller;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    /**
     * 每个用例手工装配网关侧依赖：drain 循环**不启动**，改用 {@link MeteringDispatcher#drainOnce()}
     * 手工投递，测试因此既不依赖线程也不需要 sleep。
     *
     * <p>M3 起控制器按**候选渠道**转发，因此这里喂一条候选：遗留单渠道（哨兵 id → 密钥来自
     * {@code aihub.upstream.api-key}，这里是空串 = 不发 Authorization），base-url 指向假上游。
     */
    @BeforeEach
    void wireGateway() throws IOException {
        Path spoolDir = Files.createTempDirectory("aihub-relay-write-failure");
        MeteringProperties properties =
                new MeteringProperties(true, 65536, 100, 10, 30_000L, 5_000L, 5_000L, spoolDir.toString());
        transport = new RecordingMeteringTransport();
        dispatcher = new MeteringDispatcher(properties, transport, new MeteringSpool(spoolDir, 10),
                new SimpleMeterRegistry());

        UpstreamProperties upstreamProperties = new UpstreamProperties(upstream.baseUrl(), null, "write-failure-model");
        ChannelDescriptor legacy = LegacyChannel.of(upstreamProperties);
        RouteResolver routeResolver = mock(RouteResolver.class);
        when(routeResolver.candidates(any())).thenReturn(List.of(legacy));
        ChannelCircuitBreaker circuitBreaker = mock(ChannelCircuitBreaker.class);
        lenient().when(circuitBreaker.isOpen(anyLong())).thenReturn(false);
        ConfigClient configClient = mock(ConfigClient.class);
        lenient().when(configClient.current()).thenReturn(ConfigSnapshot.empty());

        controller = new ChatRelayController(
                new UpstreamClientFactory(upstreamProperties), configClient, routeResolver, circuitBreaker,
                new ChannelKeyDecryptor(new AesGcmChannelCipher(ChannelKeyRegistry.parse("")), upstreamProperties),
                new MeteringPublisher(properties, dispatcher), properties);
    }

    @Test
    void committedWriteFailureIsMeteredAsClientDisconnected() throws Exception {
        upstream.enqueueSse(FakeUpstream.sseFrames());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"stream\":true}"));
        // 客户端侧：先把上游字节真的读出去（计量观察者因此捕获到内容 → 估算 token > 0），
        // 再让写回失败。MockServerHttpResponse 一旦进入 writeAndFlushWith 就把响应置为已提交，
        // 因此控制器看到的正是「已提交 + 写失败」这个生产分支。
        exchange.getResponse().setWriteHandler(body -> body
                .doOnNext(DataBufferUtils::release)
                .then(Mono.error(new IOException("模拟：响应已提交之后写回客户端失败"))));

        controller.chatCompletions("{\"stream\":true}", exchange).block(Duration.ofSeconds(10));

        String requestId = exchange.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER);
        assertThat(requestId).as("x-request-id 由 RequestIdFilter 写在响应头上，是计量的幂等键").isNotNull();

        // publish 只是把事件入队（非阻塞，且发生在 doFinally 里）—— 这里手工 drain，不靠线程不靠睡。
        boolean drained = false;
        for (int attempt = 0; attempt < 50 && !drained; attempt++) {
            drained = dispatcher.drainOnce();
        }
        assertThat(drained).as("写回失败后仍必须投递一条计量事件（设计文档 §8.1 ⑤）").isTrue();

        MeteringEvent event = transport.awaitEvent(requestId, Duration.ofSeconds(5));
        assertThat(event).isNotNull();
        assertThat(event.status())
                .as("终态信号是 ON_COMPLETE（控制器用 setComplete 收尾），所以 CANCELLED 只能来自"
                        + " metering.onClientDisconnected()")
                .isEqualTo(MeteringEvent.STATUS_CANCELLED);
        assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_CLIENT_DISCONNECTED);
        assertThat(event.completionTokens()).as("按已收内容估算").isGreaterThan(0);
    }

    /**
     * 客户端断连的**第二种收尾形状**：Netty 把「响应写被取消」以 {@code onComplete} 收尾，同时取消
     * 上游 body 的订阅。这在真实网关里实测出现过（全量套件下 {@code SseStreamingTest} 的断连用例
     * 偶发红：上游 body 收到 {@code cancel}，而控制器顶层 {@code doFinally} 拿到的是 {@code ON_COMPLETE}），
     * 一次被客户端掐断的流于是被记成 SUCCESS。
     *
     * <p>本用例把那种形状**确定化**：响应写只取第一块就完成（{@code take(1)} 会取消上游 body），
     * 模拟客户端读完第一帧就走。计量必须与「写失败」那条路径给出同一个事实：CANCELLED +
     * client_disconnected + 按已收内容估算的 token。判别性在于：只依赖顶层终态信号的实现会得到
     * SUCCESS（{@code usage_missing}），本用例立刻红。
     */
    @Test
    void cancelledBodyIsMeteredAsClientDisconnectedEvenWhenTheWritePathCompletes() throws Exception {
        upstream.enqueueSse(FakeUpstream.sseFrames());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"stream\":true}"));
        // 客户端读完第一帧就消失：写路径以 onComplete 收尾，上游 body 的订阅被取消。
        exchange.getResponse().setWriteHandler(body -> body.take(1)
                .doOnNext(DataBufferUtils::release)
                .then());

        controller.chatCompletions("{\"stream\":true}", exchange).block(Duration.ofSeconds(10));

        String requestId = exchange.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER);
        assertThat(requestId).isNotNull();
        boolean drained = false;
        for (int attempt = 0; attempt < 50 && !drained; attempt++) {
            drained = dispatcher.drainOnce();
        }
        assertThat(drained).as("即使写路径正常收尾，断连也必须留下一条计量事件").isTrue();

        MeteringEvent event = transport.awaitEvent(requestId, Duration.ofSeconds(5));
        assertThat(event).isNotNull();
        assertThat(event.status())
                .as("上游 body 被取消 = 客户端不再读我们的响应；这正是设计文档 §8.1 ⑤ 的「取消上游」")
                .isEqualTo(MeteringEvent.STATUS_CANCELLED);
        assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_CLIENT_DISCONNECTED);
        assertThat(event.completionTokens()).as("按已收内容估算").isGreaterThan(0);
    }
}
