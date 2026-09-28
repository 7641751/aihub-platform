package com.aihub.gateway.relay;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.route.ChannelCircuitBreaker;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import com.aihub.gateway.testsupport.RecordingMeteringTransport;
import com.aihub.gateway.trace.RequestIdFilter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>M3 的正式验收（里程碑表那一行的原文口径）</b>：用 <b>WireMock</b> 扮**多条命名渠道**，
 * 分别注入 <b>429 / 挂住超时 / 中途断流</b>，验证网关按规则**自动切换**。
 *
 * <p>设计文档 §10 / §12 把多渠道故障注入的验收方式点名成 WireMock；此前所有任务都刻意把这层留给
 * Task 15（{@code FailoverRelayTest} 用 JDK {@code HttpServer} 夹具承担细粒度口径，两者并存、不是
 * 替代关系）。本类**完全进程内**：WireMock 与网关在同一个 JVM 里，<b>不需要 Docker、不需要活 broker、
 * 不需要活 Redis</b>（Redis 指向死端口 → 熔断走本机降级表，这本身也是降级路径的证据）。
 *
 * <p><b>每条命名桩 = 一条渠道</b>（各自一个 {@link WireMockServer}、各自一个带名字的 mapping），
 * 因此「渠道」这个概念在这里是真实的 HTTP 端点，而不是同一台服务器上的不同路径：
 *
 * <table border="1">
 *   <caption>故障注入矩阵</caption>
 *   <tr><th>桩名</th><th>渠道 id</th><th>注入的故障</th></tr>
 *   <tr><td>{@code alpha-429}</td><td>21</td><td>上游 {@code 429}（+{@code Retry-After}）</td></tr>
 *   <tr><td>{@code beta-ok}</td><td>22</td><td>健康：200 + OpenAI 完成体</td></tr>
 *   <tr><td>{@code gamma-slow}</td><td>23</td><td>连上但把响应头扣住 3 秒（渠道超时 500ms → 首字节前超时）</td></tr>
 *   <tr><td>{@code delta-broken}</td><td>24</td><td>先发第一帧 SSE、然后连接被对端关闭（中途断流）</td></tr>
 *   <tr><td>{@code epsilon-5xx}</td><td>25</td><td>上游 {@code 503}</td></tr>
 * </table>
 *
 * <p><b>本类证明的东西</b>（每条都由一个会因该行为被破坏而变红的用例钉住）：
 * <ol>
 *   <li>{@code 429} → 给该渠道打 30 秒熔断标记，**立即**换下一个候选，客户端拿到健康桩的 200；
 *       而且**下一个请求直接走健康桩**（熔断标记真的参与了路由，不只是个计数）；</li>
 *   <li>{@code 5xx} 与「首字节之前的超时」→ 换下一个候选，但**不打熔断标记**（决策 10：熔断只由 429 触发）；</li>
 *   <li>三条命名桩串起来的一次请求：429 → 超时 → 健康，说明切换是**按 priority 逐个候选**走的，
 *       最终服务的那条只被调用一次（上界 = {@code min(候选数, MAX_ATTEMPTS)}）；</li>
 *   <li><b>中途断流</b>：第一帧已经转发给客户端（响应已提交）之后上游断开 → <b>结束这段流，
 *       绝不把备用的完整响应拼进来</b>（备用桩一次都不许被调用）。</li>
 * </ol>
 *
 * <p><b>本类**不**声称的东西</b>（如实登记，见 README「已知边界」）：
 * <ul>
 *   <li>中途断流的**计量归类**仍是 {@code CANCELLED / client_disconnected} —— 一次「非超时」的上游
 *       中途中断在响应提交之后与客户端断连在 Reactor 里形状相同（{@code RelayAttempts.isUpstreamFailure}
 *       按原因链只认超时与连接失败）。本类用 {@code midStreamBreakIsMeteredAsAClientDisconnect} 把这个
 *       **已知缺口**钉成可执行事实，而不是把它写成「已修复」；</li>
 *   <li>熔断的 30 秒 TTL 边界（29.999s 仍开 / 30.001s 已恢复）由 {@code ChannelCircuitBreakerTest}
 *       用注入时钟钉住，本类的 Redis 是死的，只能验「降级为本机表时标记生效」；</li>
 *   <li>真实 Redis 上的跨实例共享熔断由 M3 的 compose 验收承担（需要真容器），不在本类。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=true", "aihub.metering.enabled=true",
                "aihub.ratelimit.enabled=false", "aihub.internal.secret=test-internal-secret"})
@Import({MeteringTestConfig.class, WireMockChannelFaultInjectionTest.FakeAdmin.class})
class WireMockChannelFaultInjectionTest {

    private static final String SECRET = "wiremock-matrix-secret";
    private static final String VALID_HASH = sha256Hex(SECRET);
    private static final String MODEL = "wiremock-matrix-model";
    private static final String CHAT = ChatRelayController.CHAT_COMPLETIONS_PATH;

    /** 每条渠道**自己的**渠道密钥（合成）：用来证明凭据是逐请求、按渠道注入的。 */
    private static final String ALPHA_KEY = "sk-alpha-synthetic";
    private static final String BETA_KEY = "sk-beta-synthetic";
    private static final String GAMMA_KEY = "sk-gamma-synthetic";
    private static final String DELTA_KEY = "sk-delta-synthetic";
    private static final String EPSILON_KEY = "sk-epsilon-synthetic";

    /** 中途断流那一幕的**第一帧**：它一定会在连接被关掉之前到达客户端。 */
    private static final String FIRST_FRAME =
            "data: {\"choices\":[{\"delta\":{\"content\":\"delta-first-frame\"}}]}\n\n";

    /**
     * WireMock 用「声明一个远大于实际 body 的 Content-Length，然后只写第一帧就关连接」来构造
     * **真正的**中途断流：客户端先收到响应头与第一帧（响应因此被提交），随后是 EOF/对端关闭。
     * 这不是 WireMock 的 {@code Fault} 枚举（{@code MALFORMED_RESPONSE_CHUNK} 只发头 + 垃圾字节，
     * 一帧合法字节都到不了客户端，因此证明不了「已提交之后不得切换」）；这是本机实测过、
     * 唯一能在**同一响应内**先交付字节再断开的构造。
     */
    private static final int BROKEN_CONTENT_LENGTH = 4096;

    /**
     * 一条「命名桩」= 一个独立进程内 WireMock 服务器 + 一个具名 mapping。渠道 id 与 priority/weight
     * 由用例显式给（路由行为只有「候选是谁、按什么顺序」确定时才有唯一答案）。
     */
    private record NamedStub(String name, long channelId, String plaintextKey, WireMockServer server) {
    }

    private static NamedStub alpha429;
    private static NamedStub betaOk;
    private static NamedStub gammaSlow;
    private static NamedStub deltaBroken;
    private static NamedStub epsilon5xx;
    private static List<NamedStub> stubs;

    /** 本次快照里的候选渠道。**每个用例都显式声明**（同 {@code FailoverRelayTest} 的理由）。 */
    private static final AtomicReference<List<ChannelDescriptor>> CHANNELS = new AtomicReference<>(List.of());
    private static final AtomicInteger SNAPSHOT_VERSION = new AtomicInteger(1);

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private RecordingMeteringTransport recorder;

    @Autowired
    private ConfigClient configClient;

    @Autowired
    private ChannelCircuitBreaker circuitBreaker;

    @BeforeAll
    static void startNamedStubs() {
        alpha429 = startStub("alpha-429", 21L, ALPHA_KEY, post(urlEqualTo(CHAT))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        // 上游的退避信号：客户端只在**它成为最后一个候选**时才会看到它
                        // （前面还有候选时这条响应会被换掉），因此它不参与本类的断言。
                        .withHeader("Retry-After", "1")
                        .withBody("{\"error\":{\"message\":\"alpha rate limited\"}}")));

        betaOk = startStub("beta-ok", 22L, BETA_KEY, post(urlEqualTo(CHAT))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(completionJson("chatcmpl-beta"))));

        gammaSlow = startStub("gamma-slow", 23L, GAMMA_KEY, post(urlEqualTo(CHAT))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        // 3 秒 ≫ 渠道超时 500ms：网关必须在**响应头到达之前**超时并换下一个候选。
                        // 这个响应最终仍会写出（对端已被取消），对断言没有影响。
                        .withFixedDelay(3_000)
                        .withBody(completionJson("chatcmpl-gamma"))));

        deltaBroken = startStub("delta-broken", 24L, DELTA_KEY, post(urlEqualTo(CHAT))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream; charset=utf-8")
                        .withHeader("Content-Length", String.valueOf(BROKEN_CONTENT_LENGTH))
                        .withBody(FIRST_FRAME)));

        epsilon5xx = startStub("epsilon-5xx", 25L, EPSILON_KEY, post(urlEqualTo(CHAT))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"epsilon unavailable\"}}")));

        stubs = List.of(alpha429, betaOk, gammaSlow, deltaBroken, epsilon5xx);
        CHANNELS.set(List.of());
    }

    @AfterAll
    static void stopNamedStubs() {
        for (NamedStub stub : stubs) {
            stub.server().stop();
        }
    }

    /**
     * 每个用例都从「干净命名桩 + 空候选 + 空熔断表 + 无本地配置缓存」开始。
     *
     * <p>请求日志必须清（WireMock 会累计，而「备用桩一次都没被打」这类断言只有在清零之后才成立）；
     * 熔断表必须清（Redis 是死的，标记留在整个类共用的本机表里）；配置缓存必须失效
     * （快照内容随用例变化，而本地 TTL 是 30 秒）。
     */
    @BeforeEach
    void reset() {
        recorder.reset();
        for (NamedStub stub : stubs) {
            stub.server().resetRequests();
        }
        CHANNELS.set(List.of());
        for (NamedStub stub : stubs) {
            circuitBreaker.clear(stub.channelId());
        }
        // 夹具复位（不是广播）：0 低于本夹具 admin 会给出的任何版本，因此既清掉两级缓存
        // 又不会挡住紧接着的回填。生产路径是 ConfigSubscriber 传进来的**消息版本**（D4）。
        configClient.invalidate(0L);
    }

    // --- 用例 -------------------------------------------------------------

    /** 夹具自证：五条渠道确实是**各自独立**的 WireMock 服务器上的**具名** stub。
     * 这条用例防的是「以后有人把 {@code withName} 删掉、或把几条渠道塌成同一台服务器」——
     * 那样「用 WireMock 的多条命名 stub 扮多条渠道」这句话就不再成立，而其余用例看不出来。
     */
    @Test
    void theFiveChannelsAreSeparateNamedWireMockStubs() {
        assertThat(stubs).extracting(NamedStub::name)
                .containsExactly("alpha-429", "beta-ok", "gamma-slow", "delta-broken", "epsilon-5xx");

        assertThat(stubs).allSatisfy(stub -> {
            assertThat(stub.server().isRunning()).as("%s 必须在跑", stub.name()).isTrue();
            assertThat(stub.server().getStubMappings())
                    .as("%s 必须有一个具名 mapping", stub.name())
                    .extracting(StubMapping::getName)
                    .containsExactly(stub.name());
        });

        assertThat(stubs).extracting(stub -> stub.server().port())
                .as("每条渠道是**独立**的进程内端点（不是同一台服务器上的不同路径）")
                .doesNotHaveDuplicates();
    }

    /**
     * 429：**立即**换下一个候选 + 给该渠道打 30 秒熔断标记，客户端拿到健康桩的 200，
     * 计量里的 {@code channel_id} 是**实际服务**的那条（22，不是被 429 的 21）。
     */
    @Test
    void upstream429MarksTheChannelOpenAndSwitchesToTheHealthyStubImmediately() throws Exception {
        CHANNELS.set(List.of(channel(alpha429, 5_000, 0), channel(betaOk, 5_000, 1)));

        Instant startedAt = Instant.now();
        HttpResponse<String> response = postToGateway(nonStreamingBody());

        assertThat(response.statusCode()).as("429 之后必须由健康桩服务").isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-beta");
        assertThat(requestsTo(betaOk)).as("健康桩恰好被调用一次").isEqualTo(1);
        assertThat(Duration.between(startedAt, Instant.now()))
                .as("429 必须**立即**切换：切换路径上没有退避/睡眠（同时给本用例一个失败上界）")
                .isLessThan(Duration.ofSeconds(5));

        assertThat(circuitBreaker.isOpen(alpha429.channelId()))
                .as("上游 429 → 该渠道 30 秒熔断（Redis 是死的，标记落在本机降级表里）")
                .isTrue();
        assertThat(circuitBreaker.isOpen(betaOk.channelId())).as("健康桩不得被标记").isFalse();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_SUCCESS);
        assertThat(event.channelId()).as("计量记的是实际服务的那条渠道").isEqualTo(betaOk.channelId());
        assertThat(alpha429.server().findAll(postRequestedFor(urlEqualTo(CHAT))).get(0).getHeader("Authorization"))
                .as("命名桩收到的凭据是**这条渠道自己的**（逐请求注入，不挂在共享客户端上）")
                .isEqualTo("Bearer " + ALPHA_KEY);
    }

    /**
     * 熔断标记必须**参与路由**，而不只是个计数：第一次请求被 429 之后，第二次请求应当**直接**走健康桩，
     * 被熔断的渠道一次都不再被打（这正是 compose 验收里「第 2 次应该直接走好上游」那一条）。
     */
    @Test
    void theMarkedChannelIsSkippedOnTheNextRequest() throws Exception {
        CHANNELS.set(List.of(channel(alpha429, 5_000, 0), channel(betaOk, 5_000, 1)));

        HttpResponse<String> first = postToGateway(nonStreamingBody());
        HttpResponse<String> second = postToGateway(nonStreamingBody());

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).contains("chatcmpl-beta");

        assertThat(requestsTo(alpha429))
                .as("被 429 熔断的渠道在整个用例里只被打了第一次那一下")
                .isEqualTo(1);
        assertThat(requestsTo(betaOk)).as("两次请求都由健康桩服务").isEqualTo(2);

        MeteringEvent secondEvent = awaitEvent(second);
        assertThat(secondEvent).isNotNull();
        assertThat(secondEvent.channelId()).isEqualTo(betaOk.channelId());
    }

    /**
     * 「首字节之前的超时」→ 换下一个候选；**不**打熔断标记（决策 10：熔断只由 429 触发，
     * 5xx 与超时只做**当次**切换）。
     */
    @Test
    void aHangBeforeTheFirstByteSwitchesButDoesNotCircuitBreak() throws Exception {
        CHANNELS.set(List.of(channel(gammaSlow, 500, 0), channel(betaOk, 5_000, 1)));

        Instant startedAt = Instant.now();
        HttpResponse<String> response = postToGateway(nonStreamingBody());
        Duration elapsed = Duration.between(startedAt, Instant.now());

        assertThat(response.statusCode()).as("首字节之前的上游超时必须换到下一个候选").isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-beta");
        assertThat(requestsTo(gammaSlow)).as("挂住的桩真的被调用过（否则这条用例证明不了超时切换）").isEqualTo(1);
        assertThat(elapsed)
                .as("必须在渠道超时（500ms）附近切换，而不是等挂住的桩在 3 秒后自己回答")
                .isLessThan(Duration.ofMillis(2_500));

        assertThat(circuitBreaker.isOpen(gammaSlow.channelId()))
                .as("超时只切换**当次**，不得打熔断标记")
                .isFalse();
        assertThat(circuitBreaker.localOpenCount()).as("本机熔断表必须是空的").isZero();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId()).isEqualTo(betaOk.channelId());
    }

    /** 5xx 同样只做当次切换、不熔断（与 429 用例构成双向钉子）。 */
    @Test
    void upstream5xxSwitchesWithoutCircuitBreaking() throws Exception {
        CHANNELS.set(List.of(channel(epsilon5xx, 5_000, 0), channel(betaOk, 5_000, 1)));

        HttpResponse<String> response = postToGateway(nonStreamingBody());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-beta");
        assertThat(requestsTo(epsilon5xx)).isEqualTo(1);
        assertThat(requestsTo(betaOk)).isEqualTo(1);
        assertThat(circuitBreaker.isOpen(epsilon5xx.channelId())).as("5xx 不得熔断").isFalse();
        assertThat(circuitBreaker.localOpenCount()).isZero();
    }

    /**
     * 一次请求串起三条命名桩：{@code alpha-429 → gamma-slow → beta-ok}，最终由健康桩服务。
     * 这条用例是「多渠道故障注入矩阵」最直白的形态：三个**不同的真实端点**在同一次请求里依次失败，
     * 切换严格按 priority 前进，且每条候选**恰好被订阅一次**（上界 = {@code min(候选数, MAX_ATTEMPTS)}）。
     */
    @Test
    void oneRequestWalksTheNamedStubsInPriorityOrderAndTheHealthyOneAnswers() throws Exception {
        CHANNELS.set(List.of(
                channel(alpha429, 5_000, 0),
                channel(gammaSlow, 500, 1),
                channel(betaOk, 5_000, 2)));

        HttpResponse<String> response = postToGateway(nonStreamingBody());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-beta");

        assertThat(requestsTo(alpha429)).as("第 1 个候选：429").isEqualTo(1);
        assertThat(requestsTo(gammaSlow)).as("第 2 个候选：首字节前超时").isEqualTo(1);
        assertThat(requestsTo(betaOk)).as("第 3 个候选：健康，恰好一次").isEqualTo(1);

        assertThat(circuitBreaker.isOpen(alpha429.channelId())).as("429 熔断").isTrue();
        assertThat(circuitBreaker.isOpen(gammaSlow.channelId())).as("超时不熔断").isFalse();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_SUCCESS);
        assertThat(event.channelId()).isEqualTo(betaOk.channelId());
        assertThat(betaOk.server().findAll(postRequestedFor(urlEqualTo(CHAT))).get(0).getHeader("Authorization"))
                .as("最终服务的那条桩收到的是它自己的渠道密钥")
                .isEqualTo("Bearer " + BETA_KEY);
    }

    /**
     * <b>中途断流</b>（本类的第四格）：第一帧已经转发给客户端（响应因此提交）之后，上游把连接关掉。
     *
     * <p>必须发生两件事，缺一不可：
     * <ol>
     *   <li>客户端拿到的字节**只有第一帧**（说明它确实收到了上游已经开始回写的证据）；</li>
     *   <li>健康桩<b>一次都不许被打</b>，备用的完整响应绝不能被拼进这段流里 —— 那就是脏数据。</li>
     * </ol>
     *
     * <p>同时把「已知缺口」钉成事实：这次「非超时」的中途中断在响应提交之后与客户端断连形状相同，
     * 因此计量是 {@code CANCELLED/client_disconnected}（另见 {@link #midStreamBreakIsMeteredAsAClientDisconnect}）。
     */
    @Test
    void aMidStreamBreakEndsTheStreamInsteadOfSplicingTheHealthyStub() throws Exception {
        CHANNELS.set(List.of(channel(deltaBroken, 5_000, 0), channel(betaOk, 5_000, 1)));

        HttpResponse<String> response = postToGateway(streamingBody());

        assertThat(response.statusCode())
                .as("上游已经发过 200 + 第一帧：状态码改不了，仍是 200")
                .isEqualTo(200);
        assertThat(response.body())
                .as("第一帧必须已经转发给客户端（否则这条用例没有在验「已提交之后」）")
                .contains("delta-first-frame");
        assertThat(response.body())
                .as("已开始回写之后不得把健康桩的完整响应拼进来（那是脏数据，不是故障转移）")
                .doesNotContain("chatcmpl-beta");
        assertThat(requestsTo(betaOk))
                .as("响应已提交 → 切换路径不可达：备用桩一次都不许被调用")
                .isZero();
        assertThat(requestsTo(deltaBroken)).isEqualTo(1);
    }

    /**
     * 「中途断流的计量归类」这一格是**已知缺口**的可执行登记（README「已知边界」里如实披露）：
     * 响应提交之后的**非超时**上游中断，在 Reactor 里的形状与客户端断连无法区分
     * （{@code RelayAttempts.isUpstreamFailure} 按原因链只认超时与连接失败），因此被记成
     * {@code CANCELLED / client_disconnected}，而不是 {@code ERROR / upstream_stream_error}。
     *
     * <p>这条用例的价值是**把缺口钉死、并让「有人改了分类」立刻可见**：它既不是「已修复」，
     * 也不是「没测过」。
     */
    @Test
    void midStreamBreakIsMeteredAsAClientDisconnect() throws Exception {
        CHANNELS.set(List.of(channel(deltaBroken, 5_000, 0), channel(betaOk, 5_000, 1)));

        HttpResponse<String> response = postToGateway(streamingBody());

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_CANCELLED);
        assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_CLIENT_DISCONNECTED);
        assertThat(event.channelId())
                .as("即便归类成断连，服务过这条请求的渠道仍然被如实记下")
                .isEqualTo(deltaBroken.channelId());
    }

    // --- 命名桩与快照的脚手架 ---------------------------------------------

    /**
     * 起一条命名桩：一个独立进程内的 WireMock 服务器 + 一个**以渠道名命名**的 mapping。
     * 名字在这里统一挂上（而不是每个调用点各写一次）：这样「桩名 = mapping 名」是结构性的，
     * {@code theFiveChannelsAreSeparateNamedWireMockStubs} 断言的正是这条结构。
     */
    private static NamedStub startStub(String name, long channelId, String plaintextKey, MappingBuilder mapping) {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        server.stubFor(mapping.withName(name));
        return new NamedStub(name, channelId, plaintextKey, server);
    }

    private static int requestsTo(NamedStub stub) {
        return stub.server().findAll(postRequestedFor(urlEqualTo(CHAT))).size();
    }

    private static ChannelDescriptor channel(NamedStub stub, int timeoutMs, int priority) {
        return new ChannelDescriptor(stub.channelId(), stub.name(), stub.server().baseUrl(),
                cipherText(stub.plaintextKey()), 1, timeoutMs, ChannelDescriptor.STATUS_ACTIVE, 100, priority);
    }

    private static String completionJson(String id) {
        return "{\"id\":\"" + id + "\",\"object\":\"chat.completion\",\"model\":\"" + MODEL + "\",\"created\":1,"
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}";
    }

    private static String nonStreamingBody() {
        return "{\"model\":\"" + MODEL + "\",\"stream\":false}";
    }

    private static String streamingBody() {
        return "{\"model\":\"" + MODEL + "\",\"stream\":true}";
    }

    /** 主密钥表：只放一个 v1（合成，与 {@code FailoverRelayTest} 同一套构造）。 */
    private static String masterKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 3 + 7);
        }
        return "v1:" + Base64.getEncoder().encodeToString(key);
    }

    private static String cipherText(String plaintext) {
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey())).encrypt(plaintext);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // 遗留单渠道兜底指向**健康桩**：真要走到那条路径时，客户端拿到的也是可诊断的响应
        // （本类所有用例的模型都有显式候选，因此不会走兜底）。
        registry.add("aihub.upstream.base-url", () -> betaOk.server().baseUrl());
        registry.add("aihub.upstream.default-model", () -> "legacy-fallback-model");
        registry.add("aihub.channel.master-key", WireMockChannelFaultInjectionTest::masterKey);
        // Redis 指向死端口：鉴权回源到假 admin，熔断退化成本机表（两条降级路径都真跑一遍）。
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("spring.data.redis.timeout", () -> "500ms");
    }

    @TestConfiguration
    static class FakeAdmin {
        @Bean
        @Primary
        AdminClient adminClient() {
            return new AdminClient() {
                @Override
                public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
                    return keyHash.equals(VALID_HASH)
                            ? Mono.just(Optional.of(new ApiKeyView("ak_wiremock", 7L, "demo",
                                    ApiKeyView.STATUS_ACTIVE, null, 42L)))
                            : Mono.just(Optional.empty());
                }

                @Override
                public Mono<Optional<ConfigSnapshot>> configSnapshot() {
                    return Mono.just(Optional.of(snapshot()));
                }
            };
        }
    }

    /**
     * 按 {@link #CHANNELS} 组装快照：每条候选一条同模型路由，priority 就是用例给的那个
     * （数字小的组先服务 ⇒「首选 / 下一个候选」是确定的）。版本号每次 +1，避免本地缓存让用例互相影响。
     */
    private static ConfigSnapshot snapshot() {
        List<ChannelDescriptor> channels = CHANNELS.get();
        List<ModelRouteDescriptor> routes = new ArrayList<>(channels.size());
        for (ChannelDescriptor channel : channels) {
            routes.add(new ModelRouteDescriptor(MODEL, channel.id(), 100, channel.priority(),
                    ModelRouteDescriptor.STATUS_ACTIVE));
        }
        return new ConfigSnapshot(SNAPSHOT_VERSION.incrementAndGet(), System.currentTimeMillis(),
                channels, routes, List.of(), MODEL);
    }

    /**
     * 打网关的 HTTP helper。刻意**不**叫 {@code post}：那会遮蔽 WireMock 的静态
     * {@code post(...)}（内层作用域优先于静态导入），让 {@code post(urlEqualTo(...))} 变成一次
     * 「UrlPattern 不能转成 String」的编译错误。
     */
    private HttpResponse<String> postToGateway(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + CHAT))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ak_wiremock." + SECRET)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private MeteringEvent awaitEvent(HttpResponse<String> response) throws InterruptedException {
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        return recorder.awaitEvent(requestId, Duration.ofSeconds(5));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
