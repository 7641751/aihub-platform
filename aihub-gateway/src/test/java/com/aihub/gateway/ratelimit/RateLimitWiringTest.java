package com.aihub.gateway.ratelimit;

import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G8：**限流链真的接进了跑起来的网关**（不是「某个 bean 存在」）。
 *
 * <p>为什么必须有这一条：{@code RateLimitFilterTest} 自己 {@code new} 过滤器，因此它对
 * 「生产代码里根本没有 {@code RateLimitResolver} / {@code RateLimiter} 的 bean、过滤器压根不在
 * 链上」这类故障**完全免疫** —— 那正是 M3 前几个任务留下来的真实缺口（限流器全写好了，一行都没接）。
 * 本类不构造任何限流组件，只发真实 HTTP、只读响应，因此它红就等于「线上不会限流」。
 *
 * <p>装配（假 admin、死端口 Redis、请求辅助方法）全部来自 {@link RateLimitWiringSupport} ——
 * 本类是它的**预热**子类（冷缓存那一半由 {@code RateLimitColdStartTest} 与
 * {@code RateLimitColdCacheAuthDisabledTest} 负责）。上一轮本类曾自带一份**平行**的
 * {@code FakeAdmin} / {@code @DynamicPropertySource} / 请求辅助，并在 javadoc 里声称两者「只差预热
 * 与否」——那段声明当时是假的（两份副本已经漂移：support 设了 {@code aihub.config.local-ttl}，
 * 本类没有）。现在本类真的继承 support，声明与事实一致。
 *
 * <p>被钉住的四个事实，每一个都只能由「真的接上了」来解释：
 * <ol>
 *   <li>请求一直打到**出现 429 为止**，且这个 429 必然落在尝试次数上界（{@link #MAX_ATTEMPTS}）之内
 *       —— 钉的是规则（超限必拒）而不是时序：「第几个请求被拒」取决于机器快慢，
 *       写死次数就是一条依赖时序的假断言；</li>
 *   <li>该 429 的 body 是 **OpenAI 形状**（数据面铁律，不是 admin 信封），并带 IETF 头
 *       （{@code RateLimit-Limit: 1, 30} / {@code RateLimit-Remaining: 0} / 退避头）；
 *       这里的 {@code 1, 30} 是**控制面快照**下发的租户级策略，**不是**内置默认 {@code 10, 20}
 *       —— 内置默认的补充速率恰好追平本类的请求速率，桶永远耗不干净，那样连 429 都打不出来；</li>
 *   <li>{@code /healthz} 仍然 200：限流只守 {@code /v1/**}，运维端点不受影响；</li>
 *   <li><b>流式（SSE）路径在限流开启时仍然是流式的</b>：判定被 offload 到
 *       {@code boundedElastic} 后，中继的订阅线程确实变了，因此必须有一条真 HTTP 用例证明
 *       「帧仍然逐帧到达、且响应头上带着限流结论」——见
 *       {@link #aStreamingRequestStaysStreamingAndCarriesTheRateLimitHeaders()}。</li>
 * </ol>
 *
 * <p><b>Redis 指向死端口</b>（{@code spring.data.redis.port=1}，与 M1/M2/M3 既有做法一致）：
 * 本类证明的因此是**降级路径**上的端到端限流 —— 即「Redis 挂了也照样拒绝超限请求」，
 * 而这正是 §9 与控制器 ruling 要求的那一条（降级 ≠ 放行全部）。连接被拒是毫秒级的，
 * 加上短超时，整套用例的附加延迟是秒级而不是「每个请求 2 秒」。
 *
 * <p><b>本类也清脏上下文</b>（{@code @DirtiesContext(BEFORE_CLASS)}）：三个子类的上下文配置
 * （除鉴权开关外）完全相同，于是 Spring 会**复用**上一个类缓存下来的上下文 —— 而那个上下文里的
 * {@code aihub.upstream.base-url} 是上一个类那次 {@code FakeUpstream} 的端口，它的
 * {@code @AfterAll} 已经把服务器停掉了。复用等于让本类打到一台已经关掉的假上游（实测：502 +
 * {@code Connection refused}）。类前置清脏保证每个子类的上下文都按**自己**这次启动的假上游解析
 * base-url，与 {@code RateLimitColdStartTest} 的理由相同。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class RateLimitWiringTest extends RateLimitWiringSupport {

    /**
     * 客户端读到 SSE 第一帧的预算。与 {@code SseStreamingTest} 同一个量级：它必须**远小于**
     * 上游扣留第二帧的预算（{@code FakeUpstream.SECOND_FRAME_HOLD_BUDGET_SECONDS} = 30 s），
     * 否则「谁先到期」由时序抖动决定。
     */
    private static final long CLIENT_READ_TIMEOUT_SECONDS = 5;

    private static final String FIRST_FRAME = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n";
    private static final String SECOND_FRAME = "data: [DONE]\n\n";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ConfigClient configClient;

    /**
     * 在**测试线程**上把配置快照灌进本地缓存（{@code ConfigClient} 的 TTL 是 30 秒，足够本类跑完）。
     *
     * <p><b>为什么必须有这一句</b>：{@code ConfigClient.current()} 在两级缓存都空时走
     * {@code refreshBlocking()}，而它内部是 {@code Mono.block()}。{@code block()} 在**非阻塞线程**
     * （Netty event loop）上会抛 {@code IllegalStateException: block()/blockFirst()/blockLast() are
     * blocking}，被 {@code refreshBlocking} 吞成一次「回源失败」，于是 {@code current()} 只能回落到
     * 遗留单渠道、策略静默变回内置默认 {@code 10/20}。这是 Task 7 的 {@code ConfigClient} 自身的性质，
     * **不是限流链的问题**。
     *
     * <p><b>这条性质的成立条件比看上去窄</b>（本轮实测更正）：那个检查发生在 {@code subscribe()}
     * **之后**，因此若控制面是**同步**的，回源会在抛异常之前就跑完并写进本地缓存，第二次
     * {@code resolve()} 照样拿得到快照 —— 症状被夹具掩盖。真实的 {@code AdminClient.Http} 是 WebClient
     * （异步），所以生产形状下它确实会丢掉这一次回源。判别性用例是
     * {@code RateLimitColdCacheAuthDisabledTest}（鉴权关闭 + 冷缓存 + 异步控制面：修复前实测拿到
     * {@code 10, 20}）。
     *
     * <p>本类的职责是证明「限流链接上了」，因此这里把缓存预热好（缓存有货 → 请求路径只读本地缓存，
     * 不触发回源），让断言指向限流本身。冷缓存那一半由 {@code RateLimitColdStartTest} 负责
     * （它那条只证「冷缓存也用上策略」，判别力有限，理由见该类的 javadoc）。
     */
    @BeforeEach
    void warmTheConfigSnapshotOnTheTestThread() {
        configClient.refresh().block(Duration.ofSeconds(5));
    }

    /**
     * 链子上的限流组件必须**存在且被 Spring 装配**：这条只是最小的接线证据，
     * 单靠它不足以证明「过滤器真的在跑」（那由下面几条 HTTP 用例证明）。
     */
    @Test
    void theRealLimiterChainIsWiredIntoTheRunningContext() {
        assertThat(context.getBean(RateLimitFilter.class)).isNotNull();
        assertThat(context.getBean(RateLimiter.class)).isNotNull();
        assertThat(context.getBean(RateLimitResolver.class)).isNotNull();
        assertThat(context.getBean(RedisRateLimiter.class)).isNotNull();
        assertThat(context.getBean(LocalRateLimiter.class)).isNotNull();
    }

    /**
     * 真实请求穿过真实过滤器链：一直打到超限，第一个超限请求必须是 429 + OpenAI 形状。
     *
     * <p><b>为什么是「打到超限」而不是「第 31 个」</b>：桶在两次请求之间按 {@code qps} 补充，
     * 因此「打空 burst 需要几个请求」取决于机器快慢 —— 钉死次数就是一条依赖时序的假断言
     * （实测：{@code burst=3} 的版本因为补充速度不同，红法都不一样）。这里改为钉住**规则本身**：
     * 前若干个必须放行、且**在尝试次数上界之内**必然出现拒绝。
     *
     * <p>尝试次数上界不是随手写的：补充速率是 {@code QPS=1}/秒，把 {@code BURST=30} 打空需要
     * 约 30 秒；本类每个请求实际耗时是毫秒级（见实测），因此几百次内必然出现拒绝 ——
     * 反过来，一个「降级就放行所有人」的实现打多少次都不会拒绝，这条立刻红。
     */
    @Test
    void anOverLimitRequestIsRejectedWithAnOpenAiShaped429EvenWithRedisDown() throws Exception {
        HttpResponse<String> rejected = null;
        int allowedCount = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && rejected == null; attempt++) {
            HttpResponse<String> response = post("/v1/chat/completions", OVER_LIMIT_SECRET);
            assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER))
                    .as("限额必须来自控制面快照（%s/%s），不是内置默认", QPS, BURST)
                    .hasValue(QPS + ", " + BURST);
            if (response.statusCode() == 429) {
                rejected = response;
            } else {
                assertThat(response.statusCode())
                        .as("第 %s 个请求只能有两种结局：放行或 429", attempt)
                        .isEqualTo(200);
                allowedCount++;
            }
        }

        assertThat(allowedCount)
                .as("尝试 %s 次都没出现 429 —— 降级成了「放行所有人」，那正是 §9 禁止的", MAX_ATTEMPTS)
                .isPositive();
        assertThat(rejected).as("必须在 %s 次尝试内打到限额", MAX_ATTEMPTS).isNotNull();
        assertThat(rejected.body())
                .contains("\"error\"")
                .contains("\"code\":\"rate_limit_exceeded\"")
                .contains("\"type\":\"rate_limit_error\"")
                .contains("\"param\":null");
        assertThat(rejected.headers().firstValue(RateLimitFilter.REMAINING_HEADER)).hasValue("0");
        assertThat(rejected.headers().firstValue(RateLimitFilter.RETRY_AFTER_HEADER)).isPresent();
        assertThat(rejected.headers().firstValue(RateLimitFilter.RETRY_AFTER_MS_HEADER)).isPresent();
    }

    /**
     * 同一套上下文里的反向证据：**没有超限的请求照常成功**。
     * <p>它同时是「降级 ≠ 一律拒绝」的那一半：Redis 整个死掉期间，网关仍然正常转发。
     * 与 {@link #anOverLimitRequestIsRejectedWithAnOpenAiShaped429EvenWithRedisDown} 一起，
     * 两条合起来才是 §9 的完整语义（既不放行全部，也不拒绝全部）。
     *
     * <p><b>顺序无关</b>：本方法用**另一个密钥**（→ 另一个桶），且自己那个桶此刻必然还没被碰过，
     * 因此单个请求必然放行 —— 不依赖「另一个用例先跑还是后跑」。
     */
    @Test
    void anInLimitRequestStillSucceedsWhileRedisIsDown() throws Exception {
        upstream().enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("/v1/chat/completions", IN_LIMIT_SECRET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chat.completion");
        assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER)).hasValue(QPS + ", " + BURST);
    }

    /** 非 {@code /v1} 路径完全不受限流影响（运维端点的可用性不能被数据面治理牵连）。 */
    @Test
    void healthzIsNotGuardedByTheRateLimit() throws Exception {
        HttpResponse<String> response = get("/healthz");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER))
                .as("/healthz 不该出现限流响应头").isEmpty();
    }

    /**
     * <b>流式路径在限流开启时仍然是流式的</b>（复审 Fix 1 的第一部分）。
     *
     * <p>为什么这条必须存在：判定被 {@code subscribeOn(LIMITER_SCHEDULER)} 整体 offload 到
     * {@code boundedElastic} 之后，放行分支的 {@code chain.filter(exchange)}（中继 → 上游 SSE）
     * 也在**弹性线程**上被订阅，订阅线程与 M1/M2 时不再是同一个。这是一个真实的线程模型变更，
     * 而在本轮之前**没有任何用例执行过它**：测试资源里 {@code aihub.ratelimit.enabled=false}，
     * 于是 {@code SseStreamingTest} / {@code RelayMeteringFlowTest} / {@code ChatRelayControllerTest}
     * 全部走的是「过滤器直接 {@code chain.filter}」那条早退分支。引用「全反应堆绿」来为这个
     * blast radius 背书，等于什么都没证。
     *
     * <p>本用例用已有的握手夹具（{@code FakeUpstream.enqueueHandshakeSse}）把两件事一起钉住：
     * <ol>
     *   <li>响应头里有限流结论（{@code RateLimit-Limit: 1, 30} 来自控制面快照）——
     *       证过滤器真的在这一跳上跑了；</li>
     *   <li>第一帧在上游**还没写第二帧**时就到了客户端（随后放行才收到 {@code [DONE]}）——
     *       证 offload 之后中继仍然逐帧 flush，M1 的字节级透传语义没有被线程模型变更破坏。</li>
     * </ol>
     * 断言顺序无关：本用例用 {@link RateLimitWiringSupport#IN_LIMIT_SECRET}（burst 30，
     * 本类至多对这条桶发两个请求），因此不会与打空桶的那条用例互相消耗。
     */
    @Test
    void aStreamingRequestStaysStreamingAndCarriesTheRateLimitHeaders() throws Exception {
        upstream().enqueueHandshakeSse(FIRST_FRAME, SECOND_FRAME);

        HttpResponse<InputStream> response = postSse("/v1/chat/completions", IN_LIMIT_SECRET);
        assertThat(response.statusCode()).isEqualTo(200);
        // 限流头由过滤器在提交响应头**之前**写好，因此流式响应同样带着它们。
        assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER))
                .as("流式响应也必须带上限流结论（判定与写头发生在同一个 offload 块里）")
                .hasValue(QPS + ", " + BURST);
        assertThat(response.headers().firstValue(RateLimitFilter.REMAINING_HEADER)).isPresent();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        ExecutorService readerThread = Executors.newSingleThreadExecutor();
        try {
            Future<String> firstLine = readerThread.submit(reader::readLine);
            String line;
            try {
                line = firstLine.get(CLIENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("限流开启后网关不再逐帧 flush：第一帧没能在 "
                        + CLIENT_READ_TIMEOUT_SECONDS + " 秒内到达客户端（上游仍在扣留第二帧，等待预算 "
                        + FakeUpstream.SECOND_FRAME_HOLD_BUDGET_SECONDS + " 秒）", e);
            }
            assertThat(line).isEqualTo("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}");
            assertThat(upstream().secondFrameWritten())
                    .as("第一帧到达客户端时上游还没写第二帧 —— 判定被 offload 到 boundedElastic 后仍逐帧 flush")
                    .isFalse();

            upstream().releaseSecondFrame();

            assertThat(reader.lines().toList()).contains("data: [DONE]");
        } finally {
            // 任何提前失败都必须放行握手并关掉 body：上游与其它用例共用一条 dispatch 线程，
            // 一个仍在阻塞等放行的 handler 会把后面的用例一起拖住。
            upstream().releaseSecondFrame();
            readerThread.shutdownNow();
            response.body().close();
        }
    }
}
