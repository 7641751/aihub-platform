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
import com.aihub.gateway.route.CircuitState;
import com.aihub.gateway.route.RouteResolver;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import com.aihub.gateway.testsupport.RecordingMeteringTransport;
import com.aihub.gateway.trace.RequestIdFilter;
import com.aihub.gateway.upstream.UpstreamClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **M3 的验收核心**：多候选渠道下的自动故障转移与熔断，全部用真 HTTP（真实网关 + JDK HttpServer
 * 假上游），**不需要 Docker、不需要真 Redis**（Redis 指向不存在的端口 → 熔断退化成本机表，
 * 这本身也是降级路径的证据）。
 *
 * <p>必须成立的行为（每一条都由下面的用例钉住，评审按此判断）：
 * <ol>
 *   <li>上游 429 → **立即**换下一个候选、标记该渠道熔断（30s，{@code markOpen}），并在计量里记下
 *       **实际服务**的那条渠道；</li>
 *   <li>上游 5xx → 换下一个候选，但**不打熔断**（决策 10）；</li>
 *   <li>上游超时（首字节之前）→ 换下一个候选；</li>
 *   <li>上游 400 → **不切换**（备用渠道一次都不该被调用）；</li>
 *   <li>候选全挂 → 客户端拿到**真实的最后一个失败**（原样状态码与 body）；</li>
 *   <li>未知模型（快照里没有遗留渠道）→ 404 + OpenAI 形状 {@code model_not_found}；</li>
 *   <li>每条渠道的密钥**逐请求**注入，且候选数有**上界**（G10）；</li>
 *   <li>响应已提交之后（第一批字节已经转发）**不得**再切换；
 *       密钥解不开的候选被**跳过**而不是抛异常。</li>
 * </ol>
 *
 * <p>「第一个字节已转发之后不得切换」这条铁律在 {@code ChatRelayControllerTest} 的字节透传用例、
 * {@code RelayCommittedWriteFailureTest}（已提交 + 写失败）之外，本类用「响应头与第一帧原样到客户端、
 * 上游随后读超时」的构造再钉一次：切换路径在这种时序下**不可达**，且这次超时必须计成
 * 上游流失败（{@code upstream_stream}）而不是客户端断连或成功。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=true", "aihub.metering.enabled=true",
                "aihub.ratelimit.enabled=false", "aihub.internal.secret=test-internal-secret"})
@Import({MeteringTestConfig.class, FailoverRelayTest.FakeAdmin.class})
class FailoverRelayTest {

    private static final String SECRET = "failover-secret";
    private static final String VALID_HASH = sha256Hex(SECRET);
    private static final String CHANNEL_KEY_PLAINTEXT = "sk-channel-plaintext-synthetic";
    private static final String SECOND_CHANNEL_KEY_PLAINTEXT = "sk-second-channel-plaintext-synthetic";
    private static final String MODEL = "failover-model";
    private static final AtomicInteger SNAPSHOT_VERSION = new AtomicInteger(1);

    /** 已提交之后断流那一幕用的第一帧：它一定会被原样转发给客户端。 */
    private static final String FIRST_FRAME = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n";
    private static final String SECOND_FRAME = "data: [DONE]\n\n";

    private static FakeUpstream primary;
    private static FakeUpstream standby;
    private static FakeUpstream third;
    private static FakeUpstream fourth;
    private static FakeUpstream stalling;

    /**
     * 本节快照里的候选渠道。**每个用例都显式声明**（默认两条：11 首选 / 12 备用）：
     * 故障转移的行为只有「候选是谁、按什么顺序」确定时才有唯一答案。
     */
    private static final AtomicReference<List<ChannelDescriptor>> CHANNELS =
            new AtomicReference<>(List.of());

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private RecordingMeteringTransport recorder;

    @Autowired
    private ConfigClient configClient;

    @Autowired
    private RouteResolver routeResolver;

    @Autowired
    private ChannelCircuitBreaker circuitBreaker;

    @Autowired
    private UpstreamClientFactory clientFactory;

    @Autowired
    private ApplicationContext context;

    @BeforeAll
    static void startUpstreams() {
        primary = FakeUpstream.start();
        standby = FakeUpstream.start();
        third = FakeUpstream.start();
        fourth = FakeUpstream.start();
        stalling = FakeUpstream.start();
        CHANNELS.set(defaultChannels());
    }

    @AfterAll
    static void stopUpstreams() {
        for (FakeUpstream upstream : List.of(primary, standby, third, fourth, stalling)) {
            upstream.stop();
        }
    }

    /**
     * 每个用例都从「干净的假上游 + 默认两条候选 + 空熔断表 + 无本地配置缓存」开始。
     *
     * <p>熔断表必须清：Redis 是死的，{@code markOpen} 的标记留在**本机表**里，而整个类共用一个
     * Spring 上下文 —— 上一个用例打开的渠道会把下一个用例的候选顺序改掉（这正是本机降级路径的
     * 真实行为，只是在这里必须显式复位）。
     *
     * <p>配置缓存必须失效：快照内容随用例变化（{@code CHANNELS}），而本地 TTL 是 30 秒。
     */
    @BeforeEach
    void reset() {
        recorder.reset();
        for (FakeUpstream upstream : List.of(primary, standby, third, fourth, stalling)) {
            upstream.clearLastRequest();
        }
        CHANNELS.set(defaultChannels());
        for (long channelId : new long[]{11L, 12L, 13L, 14L}) {
            circuitBreaker.clear(channelId);
        }
        // 夹具复位（不是广播）：0 低于本夹具 admin 会给出的任何版本（它每次 +1），因此既清掉两级缓存
        // 又不会挡住紧接着的回填。生产路径是 ConfigSubscriber 传进来的**消息版本**（D4）。
        configClient.invalidate(0L);
    }

    /** 任何仍在等放行的握手都要放掉：假上游只有一条 dispatch 线程，留着会拖住后面的用例。 */
    @AfterEach
    void releaseHandshakes() {
        for (FakeUpstream upstream : List.of(primary, standby, third, fourth, stalling)) {
            upstream.releaseSecondFrame();
        }
    }

    // --- 用例 -------------------------------------------------------------

    @Test
    void upstream429SwitchesImmediatelyAndMarksTheChannel() throws Exception {
        primary.enqueueError(429, "{\"error\":{\"message\":\"rate limited by upstream\"}}");
        standby.enqueueJson(200, FakeUpstream.completionJson());

        Instant startedAt = Instant.now();
        HttpResponse<String> response = post("{\"model\":\"" + MODEL + "\",\"stream\":false}");

        assertThat(response.statusCode()).as("切换成功后客户端看到的是备用渠道的 200").isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-1");
        assertThat(standby.lastRequest()).as("备用渠道真的被调用了").isNotNull();
        assertThat(Duration.between(startedAt, Instant.now()))
                .as("429 必须**立即**切换：切换路径上不允许有退避/睡眠（这条同时也给本用例一个失败上界）")
                .isLessThan(Duration.ofSeconds(5));

        assertThat(circuitBreaker.isOpen(11L))
                .as("429 必须给该渠道打 30 秒熔断标记（决策 10）；Redis 死了也必须在本机表里记住")
                .isTrue();
        assertThat(circuitBreaker.state(11L).source())
                .as("Redis 指向死端口 → 这次判定来自本机降级表（降级路径也是本类的证据之一）")
                .isEqualTo(CircuitState.SOURCE_LOCAL);
        assertThat(circuitBreaker.isOpen(12L)).as("只有返回 429 的那条渠道被熔断").isFalse();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId()).as("计量里的 channel_id 必须是**实际服务**的那条").isEqualTo(12L);
    }

    @Test
    void upstream5xxSwitchesToTheNextCandidate() throws Exception {
        primary.enqueueError(503, "{\"error\":{\"message\":\"upstream unavailable\"}}");
        standby.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"" + MODEL + "\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-1");
        assertThat(standby.lastRequest()).isNotNull();
        // 决策 10 的另一半（与上面那条 429 用例构成双向钉子）：5xx **不**熔断 —— 一次坏请求
        // 不该把整条渠道关 30 秒。实现若把 5xx 也 markOpen，这里立刻红。
        assertThat(circuitBreaker.isOpen(11L)).as("5xx 不得打熔断标记").isFalse();
        assertThat(circuitBreaker.localOpenCount()).as("本机熔断表必须是空的").isZero();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId()).isEqualTo(12L);
    }

    @Test
    void upstreamTimeoutSwitchesToTheNextCandidate() throws Exception {
        // 首选渠道超时 500ms；它连上但不回响应头（回响应头之前没有「已提交」可言，因此允许切换）。
        // 用一个**专用**的假上游扣住响应：假上游只有一条 dispatch 线程，共用 primary 会拖住别的用例。
        CHANNELS.set(List.of(
                channel(11L, "primary", stalling, cipherText(CHANNEL_KEY_PLAINTEXT), 500, 0),
                channel(12L, "standby", standby, cipherText(CHANNEL_KEY_PLAINTEXT), 5_000, 1)));
        stalling.enqueueStall(2_000L);
        standby.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"" + MODEL + "\",\"stream\":false}");

        assertThat(response.statusCode())
                .as("首字节之前的上游超时必须换到备用渠道，而不是把 502 交给客户端")
                .isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-1");
        assertThat(standby.lastRequest()).isNotNull();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId()).isEqualTo(12L);
    }

    /** 客户端的错（400）**不得**触发切换：那会把同一个错误在多个上游各计费一次。 */
    @Test
    void upstream400IsRelayedVerbatimWithoutSwitching() throws Exception {
        primary.enqueueError(400, "{\"error\":{\"message\":\"bad request from client\"}}");

        HttpResponse<String> response = post("{\"model\":\"" + MODEL + "\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("bad request from client");
        assertThat(standby.lastRequest()).as("400 不得切换渠道").isNull();
        assertThat(circuitBreaker.isOpen(11L)).as("400 既不该切换也不该熔断").isFalse();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId())
                .as("客户端的错由首选渠道原样回写：事件里记的就是它")
                .isEqualTo(11L);
    }

    @Test
    void everyCandidateFailingYieldsTheRealLastFailure() throws Exception {
        primary.enqueueError(503, "{\"error\":{\"message\":\"primary down\"}}");
        standby.enqueueError(502, "{\"error\":{\"message\":\"standby down\"}}");

        HttpResponse<String> response = post("{\"model\":\"" + MODEL + "\",\"stream\":false}");

        assertThat(response.statusCode())
                .as("最后一个候选的上游状态必须原样透传（不是网关自造的 502 upstream_unreachable）")
                .isEqualTo(502);
        assertThat(response.body()).contains("standby down");
        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId()).isEqualTo(12L);
    }

    @Test
    void unknownModelReturns404WithTheOpenAiBody() throws Exception {
        HttpResponse<String> response = post("{\"model\":\"no-such-model\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body())
                .contains("\"error\"")
                .contains("\"type\":\"invalid_request_error\"")
                .contains("\"param\":null")
                .contains("\"code\":\"model_not_found\"");
        assertThat(response.body())
                .as("必须是 /v1 的 OpenAI 形状，绝不是 admin 的 {code,message,data} 信封")
                .doesNotContain("\"data\"");
        assertThat(primary.lastRequest()).as("没有任何可用路由时一次上游都不该被打").isNull();
    }

    @Test
    void perChannelKeyIsInjectedIntoTheUpstreamRequest() throws Exception {
        primary.enqueueJson(200, FakeUpstream.completionJson());

        post("{\"model\":\"" + MODEL + "\",\"stream\":false}");

        assertThat(primary.lastRequest().headers())
                .as("网关必须用解密出来的渠道密钥覆盖客户端带来的 Authorization")
                .containsEntry("authorization", "Bearer " + CHANNEL_KEY_PLAINTEXT);
    }

    /**
     * 凭据**逐请求**注入，绝不挂在共享客户端上：两条渠道的 base-url / 超时 / 模式全同（= 工厂
     * 缓存里是**同一个** WebClient 实例），第一次请求 429 后切到第二条渠道 —— 第二条渠道的上游请求
     * 必须带**它自己**的密钥。任何「第一次请求把密钥塞进客户端默认头」的实现都会在这里把第一条
     * 渠道的密钥送到上游（{@code ChannelClientIsolationTest} 在客户端层面钉同一件事，这里是端到端）。
     */
    @Test
    void aClientSharedByTwoChannelsOnlyCarriesTheCredentialOfTheRequestThatIsRunning() throws Exception {
        ChannelDescriptor sharedClientFirst = channel(11L, "primary", primary,
                cipherText(CHANNEL_KEY_PLAINTEXT), 5_000, 0);
        ChannelDescriptor sharedClientSecond = channel(12L, "standby", primary,
                cipherText(SECOND_CHANNEL_KEY_PLAINTEXT), 5_000, 1);
        CHANNELS.set(List.of(sharedClientFirst, sharedClientSecond));
        assertThat(clientFactory.forChannel(sharedClientFirst, false))
                .as("夹具前提：两条渠道必须共用同一个客户端实例，否则这条用例证明不了「不挂在客户端上」")
                .isSameAs(clientFactory.forChannel(sharedClientSecond, false));

        primary.enqueueError(429, "{\"error\":{\"message\":\"rate limited\"}}");
        primary.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"" + MODEL + "\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(primary.lastRequest().headers())
                .as("切换后的那次请求必须带第二条渠道自己的密钥")
                .containsEntry("authorization", "Bearer " + SECOND_CHANNEL_KEY_PLAINTEXT);
        assertThat(primary.lastRequest().headers().get("authorization"))
                .as("共享客户端上绝不能残留上一条渠道的密钥")
                .isNotEqualTo("Bearer " + CHANNEL_KEY_PLAINTEXT);
    }

    /**
     * G10：候选数有上界。四条候选全挂时，客户端拿到的是**第 {@code MAX_ATTEMPTS} 条**候选的原样失败，
     * 第 4 条**一次都不会被打** —— 没有上界时这一个请求会放大成 4 次上游调用。
     */
    @Test
    void attemptsAreCappedSoOneRequestCannotAmplifyIntoEveryCandidate() throws Exception {
        CHANNELS.set(List.of(
                channel(11L, "primary", primary, cipherText(CHANNEL_KEY_PLAINTEXT), 5_000, 0),
                channel(12L, "standby", standby, cipherText(CHANNEL_KEY_PLAINTEXT), 5_000, 1),
                channel(13L, "third", third, cipherText(CHANNEL_KEY_PLAINTEXT), 5_000, 2),
                channel(14L, "fourth", fourth, cipherText(CHANNEL_KEY_PLAINTEXT), 5_000, 3)));
        primary.enqueueError(503, "{\"error\":{\"message\":\"down-1\"}}");
        standby.enqueueError(503, "{\"error\":{\"message\":\"down-2\"}}");
        third.enqueueError(503, "{\"error\":{\"message\":\"down-3\"}}");
        fourth.enqueueError(503, "{\"error\":{\"message\":\"down-4\"}}");

        HttpResponse<String> response = post("{\"model\":\"" + MODEL + "\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body())
                .as("客户端拿到的是第 %s 条候选的原样失败", RelayAttempts.MAX_ATTEMPTS)
                .contains("down-" + RelayAttempts.MAX_ATTEMPTS)
                .doesNotContain("down-" + (RelayAttempts.MAX_ATTEMPTS + 1));
        assertThat(primary.lastRequest()).isNotNull();
        assertThat(standby.lastRequest()).isNotNull();
        assertThat(third.lastRequest()).isNotNull();
        assertThat(fourth.lastRequest())
                .as("上界之外的第 %s 条候选一次都不许被打（G10：一次请求最多 %s 次上游调用）",
                        RelayAttempts.MAX_ATTEMPTS + 1, RelayAttempts.MAX_ATTEMPTS)
                .isNull();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId()).as("事件记的是最后一次被调用（并回写）的那条").isEqualTo(13L);
    }

    /** 密钥解不开的候选是**不可用**，不是错误：跳过它、让下一个候选服务，绝不在请求路径上抛异常。 */
    @Test
    void undecryptableCandidatesAreSkippedInFavourOfTheNextOne() throws Exception {
        CHANNELS.set(List.of(
                channel(11L, "primary", primary, "v9:QUJD", 5_000, 0),
                channel(12L, "standby", standby, cipherText(CHANNEL_KEY_PLAINTEXT), 5_000, 1)));
        standby.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"" + MODEL + "\",\"stream\":false}");

        assertThat(response.statusCode()).as("解不开密钥的候选被跳过后仍要正常服务").isEqualTo(200);
        assertThat(primary.lastRequest()).as("注定失败的候选不该浪费一次往返").isNull();
        assertThat(standby.lastRequest()).isNotNull();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId()).isEqualTo(12L);
    }

    /**
     * **全部**候选的密钥都解不开时不许把它伪装成「模型不存在」（404），也不是 500：模型是存在的，
     * 只是网关此刻没有凭据可用 —— 这与「上游连不上」同属「现在服务不了」，因此是 502
     * {@code upstream_unreachable}（{@code RelayAttempts.servable} 保留原列表正是为了走到这里）。
     */
    @Test
    void aModelWhoseCandidatesAreAllUndecryptableFailsWith502RatherThan404Or500() throws Exception {
        CHANNELS.set(List.of(
                channel(11L, "primary", primary, "v9:QUJD", 5_000, 0),
                channel(12L, "standby", standby, "v8:QUJD", 5_000, 1)));

        HttpResponse<String> response = post("{\"model\":\"" + MODEL + "\",\"stream\":false}");

        assertThat(response.statusCode())
                .as("主密钥配错不能让模型伪装成不存在（404），也不能是 500")
                .isEqualTo(502);
        assertThat(response.body()).contains("\"code\":\"upstream_unreachable\"");
        assertThat(primary.lastRequest()).isNull();
        assertThat(standby.lastRequest()).isNull();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId())
                .as("两条候选都在**发出上游请求之前**就被跳过（密钥解不开）：事件里不得记下任何一条"
                        + "从未被联系过的渠道（记成 12 会让 request_log 指向一条本次请求根本没碰过的渠道）")
                .isNull();
    }

    /**
     * 「第一批字节已经转发之后不得再切换」的机器可判定形式：响应头与第一帧原样到客户端之后，
     * 上游在读响应体的中途超时 —— 此时响应**已提交**，切换路径不可达，失败只能以「结束这段流」
     * 收尾（绝不能把备用渠道的完整响应拼进来）。
     *
     * <p>它同时是 G14 的端到端钉子：这次失败是**状态 200 之后**的读超时，按异常类型判定会把它
     * 当成「客户端断连」（甚至成功）；只有按原因链（{@code ReadTimeoutException}）才能计成
     * {@code upstream_stream}。
     */
    @Test
    void aFailureAfterTheFirstForwardedByteEndsTheStreamInsteadOfSplicingTheNextChannel() throws Exception {
        CHANNELS.set(List.of(
                channel(11L, "primary", primary, cipherText(CHANNEL_KEY_PLAINTEXT), 500, 0),
                channel(12L, "standby", standby, cipherText(CHANNEL_KEY_PLAINTEXT), 5_000, 1)));
        primary.enqueueHandshakeSse(FIRST_FRAME, SECOND_FRAME);
        standby.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response;
        try {
            response = post("{\"model\":\"" + MODEL + "\",\"stream\":false}");
        } finally {
            primary.releaseSecondFrame();
        }

        assertThat(response.body())
                .as("第一帧必须已经原样转发给客户端（否则这条用例没有在验「已提交之后」）")
                .contains("你");
        assertThat(response.body())
                .as("已开始回写之后不得把备用渠道的完整响应拼进来（那是脏数据，不是故障转移）")
                .doesNotContain("chatcmpl-1");
        assertThat(standby.lastRequest()).as("响应已提交 → 切换路径不可达").isNull();

        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_ERROR);
        assertThat(event.errorCode())
                .as("按原因链认出来的上游读超时：既不是客户端断连也不是成功")
                .isEqualTo(MeteringEvent.ERROR_UPSTREAM_STREAM);
    }

    /**
     * G9：熔断器与路由解析器必须是**真实 bean**。单靠「context 起得来」不算 —— 这里直接断言
     * 注入的就是容器里那一个，并且它真的能从快照解析出候选（顺序 = priority 升序，因此是确定的）。
     * 上面那条 429 用例断言 {@code circuitBreaker.isOpen(11L)} 为真，也只有「中继调用了这个 bean」
     * 才可能成立。
     */
    @Test
    void theRoutingAndCircuitBreakerBeansAreWiredIntoTheRunningContext() {
        assertThat(context.getBean(RouteResolver.class)).isSameAs(routeResolver);
        assertThat(context.getBean(ChannelCircuitBreaker.class)).isSameAs(circuitBreaker);
        assertThat(routeResolver.candidates(MODEL))
                .as("真实 bean：候选来自控制面快照，首选是 priority=0 的 11")
                .extracting(ChannelDescriptor::id)
                .containsExactly(11L, 12L);
    }

    // --- 脚手架 ---------------------------------------------------------

    /** 主密钥表：只放一个 v1（合成）。 */
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

    private static ChannelDescriptor channel(long id, String name, FakeUpstream upstream, String cipher,
                                            int timeoutMs, int priority) {
        return new ChannelDescriptor(id, name, upstream.baseUrl(), cipher, 1, timeoutMs,
                ChannelDescriptor.STATUS_ACTIVE, 100, priority);
    }

    /**
     * 默认两条同模型候选：11（priority 0，weight 100）与 12（priority 1，weight 100）。
     * **priority 不同**是刻意的：组内是权重随机，同组两条的先后不确定，而「下一个候选是谁」
     * 必须是确定的（否则「400 不切换」「上界」这类断言会随机红）。
     */
    private static List<ChannelDescriptor> defaultChannels() {
        return List.of(
                channel(11L, "primary", primary, cipherText(CHANNEL_KEY_PLAINTEXT), 5_000, 0),
                channel(12L, "standby", standby, cipherText(CHANNEL_KEY_PLAINTEXT), 5_000, 1));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> primary.baseUrl());
        registry.add("aihub.upstream.default-model", () -> "fallback-model");
        registry.add("aihub.channel.master-key", FailoverRelayTest::masterKey);
        // Redis 指向不存在的端口：鉴权回源到假 admin，熔断退化成本机表（两条降级路径都真跑一遍）。
        registry.add("spring.data.redis.port", () -> "1");
        // 连接被拒本来就是毫秒级；显式收短超时，避免任何情况下退化成「每请求 2 秒」。
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
                            ? Mono.just(Optional.of(new ApiKeyView("ak_failover", 7L, "demo",
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
     * 按 {@link #CHANNELS} 组装快照：每条候选一条同模型路由，priority 取自渠道自己的 priority
     * （数字小的组先服务，因此「首选 / 下一个候选」是确定的）。
     *
     * <p>版本号每次都 +1：让 {@code ConfigClient} 的版本比对始终认为「拿到的是更新的快照」，
     * 避免本地缓存让不同用例互相影响（同一个 Spring 上下文在整类里复用）。
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

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ak_failover." + SECRET)
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
