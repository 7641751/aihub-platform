package com.aihub.gateway.auth;

import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.ratelimit.RateLimitFilter;
import com.aihub.gateway.testsupport.FakeAdminServer;
import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1（Redis 不可用 + 鉴权缓存未命中 ⇒ valid key 变 401，并被负缓存锁死约 30 秒）的回归。
 *
 * <p><b>症状的两半，分别在两个模块里被证</b>：
 * <ul>
 *   <li>admin 那一半（「健康的 admin 为什么会慢过网关的内部跳预算」）由
 *       {@code ApiKeyResolveRedisOutageTest} 用真实 Lettuce + 黑障 Redis 度量；</li>
 *   <li>网关这一半（「一次**故障**为什么会被当成『这把 key 不存在』并锁死 30 秒」）由本类证。
 *       本类让假 admin 的 resolve 迟到 3.5 秒 —— 跨过 {@code AdminClientConfig} 的
 *       {@code responseTimeout(3s)} —— 于是网关这次回源**必然**以传输故障收场，
 *       这正是 compose 上实测到的那个形状（WARN→ERROR 恰好 3.01 秒）。</li>
 * </ul>
 * 两半合起来才是 D1 的完整因果链，任何一半单独都不足以复现它。
 *
 * <p><b>本类刻意不提供 {@code @Primary} 假 admin</b>：要跑的正是真实 {@code AdminClient.Http}
 * 与它那 3 秒预算。Redis 指向死端口（本仓库既有的「Redis 不可用」构造法），因此鉴权缓存必然未命中，
 * 请求必然落在这条路上。
 *
 * <p><b>不削弱鉴权</b>：故障期间仍然 401（fail-closed 是既有且已登记的决策，本类不推翻它）；
 * 本类钉的是「故障**不是**『key 不存在』」—— 它不得进入负缓存，因此故障清除后同一把 key
 * 必须**立刻**恢复，而不是等约 30 秒的负缓存过期。真正不存在的 key 照旧被拒，并且照旧被负缓存
 * （第 2 条用例就是这条红线的哨兵）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyRedisOutageAuthTest {

    private static final String INTERNAL_SECRET = "d1-internal-secret";

    /** 合法密钥的 admin 应答：{@code tenantId=7}，与断言里的租户一致。 */
    private static final String VALID_VIEW_ENVELOPE = """
            {"code":"OK","message":"success","data":{"keyId":"ak_d1","tenantId":7,\
            "tenantName":"d1","status":"ACTIVE","expireAt":null,"apiKeyId":42}}""";

    /** admin 的「这把 key 不存在」信封：404 + {@code NOT_FOUND} 是**权威**的否定答案。 */
    private static final String NOT_FOUND_ENVELOPE =
            "{\"code\":\"NOT_FOUND\",\"message\":\"key not found\",\"data\":null}";

    /** 跨过网关给内部跳的 {@code responseTimeout(3s)}：这一跳必然以传输故障收场。 */
    private static final long BEYOND_GATEWAY_INTERNAL_HOP_BUDGET_MILLIS = 3_500L;

    /**
     * 打空本地令牌桶的尝试次数上界。Redis 不可用时策略回落到内置默认 {@code qps=10 / burst=20}，
     * 补充速率 10/秒、而每次请求是毫秒级，因此几十次内必然出现 429；一个「降级就放行所有人」的
     * 实现永远打不到。上界取得与既有 {@code RateLimitColdStartTest} 同一个量级。
     */
    private static final int MAX_ATTEMPTS = 400;

    private static FakeUpstream upstream;
    private static FakeAdminServer admin;

    @LocalServerPort
    private int gatewayPort;

    @BeforeAll
    static void startStubs() {
        upstream = FakeUpstream.start();
        admin = FakeAdminServer.start();
    }

    @AfterAll
    static void stopStubs() {
        upstream.stop();
        admin.stop();
    }

    /**
     * 用例之间共享同一个假 admin（Spring 上下文按类缓存），而"排一个诱饵响应来证明某条路径
     * **没有**回源"会把诱饵留在队列里 —— 不 reset 的话，前一个用例的诱饵会被后一个用例吃掉，
     * 实测就是这么红的（失败信息与被测行为完全无关）。每个用例必须从干净的队列与计数器开始。
     */
    @BeforeEach
    void resetStubs() {
        admin.reset();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> upstream.baseUrl());
        registry.add("aihub.upstream.default-model", () -> "d1-model");
        registry.add("aihub.auth.enabled", () -> "true");
        registry.add("aihub.auth.admin-base-url", () -> admin.baseUrl());
        registry.add("aihub.internal.secret", () -> INTERNAL_SECRET);
        // Redis 不可用：鉴权缓存必然未命中，请求必然回源 admin（本仓库既有的构造法）。
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("spring.data.redis.timeout", () -> "500ms");
        // 本仓库的网关测试默认关掉限流；本类要断言「请求真的走到了限流器」。
        registry.add("aihub.ratelimit.enabled", () -> "true");
    }

    /**
     * **D1 的核心回归**：一次回源**故障**（迟到 3.5 秒 ⇒ 撞上网关 3 秒的内部跳预算）绝不能被当成
     * 「这把 key 不存在」写进负缓存。
     *
     * <p>RED 证据（修前）：第二个请求拿到 401、且 admin 只被调用过 1 次 —— 那一次的**故障**
     * 被负缓存了，于是故障早就清除了、key 也一直有效，客户端仍要吃约 30 秒的 401。
     */
    @Test
    void faultedResolveIsNotNegativeCachedSoTheKeyWorksImmediatelyAfterTheFaultClears() throws Exception {
        String secret = "d1-fault-then-recovery";

        // 第一次回源：假 admin 迟到 3.5 秒 ⇒ 网关 3 秒预算到点、按传输故障收场。
        admin.enqueueStalledJsonForPath(AdminClient.RESOLVE_PATH, 200, VALID_VIEW_ENVELOPE,
                BEYOND_GATEWAY_INTERNAL_HOP_BUDGET_MILLIS);

        HttpResponse<String> duringFault = get("/v1/models", bearer(secret));

        assertThat(duringFault.statusCode())
                .as("故障期间仍然 fail-closed 回 401 —— 这条既有决策本类不推翻")
                .isEqualTo(401);
        assertThat(admin.requestCountForPath(AdminClient.RESOLVE_PATH))
                .as("前提检查：第一次请求确实回源了 admin（否则本用例证明不了任何事）")
                .isEqualTo(1);

        // 故障清除：admin 立刻给出正确视图。
        admin.enqueueJsonForPath(AdminClient.RESOLVE_PATH, 200, VALID_VIEW_ENVELOPE);

        HttpResponse<String> afterFault = get("/v1/models", bearer(secret));

        assertThat(admin.requestCountForPath(AdminClient.RESOLVE_PATH))
                .as("故障结果绝不能被写进负缓存：故障清除后的第一个请求必须**重新回源**")
                .isEqualTo(2);
        assertThat(afterFault.statusCode())
                .as("故障清除后同一把 key 必须立刻恢复，而不是被负缓存锁死约 30 秒（local-cache-ttl）")
                .isEqualTo(200);
    }

    /**
     * **安全红线**：真正不存在的 key 仍然被拒，而且它仍然进负缓存 ——
     * 「故障不缓存」只对故障成立，不能顺手把 M1 登记的负缓存决策一起删掉
     * （否则每个不存在的 key 都会以每个请求一次的频率去敲 admin/MySQL）。
     *
     * <p>判别手法：第二次请求之前排一份**有效**视图。若这个未知 key 的回源再次发生，它就会拿到
     * 那份有效视图并放行成 200；实测 401 且 {@code requestCountForPath == 1}，两条一起说明
     * 它命中的是**权威否定**留下的负缓存。
     */
    @Test
    void genuinelyUnknownKeyIsStillRejectedAndItsAuthoritativeMissIsStillNegativeCached() throws Exception {
        String secret = "d1-genuinely-unknown";

        admin.enqueueJsonForPath(AdminClient.RESOLVE_PATH, 404, NOT_FOUND_ENVELOPE);

        assertThat(get("/v1/models", bearer(secret)).statusCode())
                .as("404 + NOT_FOUND 信封 = admin 权威地说了「没有这把 key」，必须 401")
                .isEqualTo(401);

        admin.enqueueJsonForPath(AdminClient.RESOLVE_PATH, 200, VALID_VIEW_ENVELOPE);

        assertThat(get("/v1/models", bearer(secret)).statusCode())
                .as("权威的不存在仍然进负缓存：第二次仍是 401，而不是拿到诱饵视图后变成 200")
                .isEqualTo(401);
        assertThat(admin.requestCountForPath(AdminClient.RESOLVE_PATH))
                .as("负缓存必须仍然生效：未知 key 的第二次请求不该再回源 admin")
                .isEqualTo(1);
    }

    /**
     * **验收级结论**：Redis 不可用 + 一把有效但未缓存的 key ⇒ 请求必须穿过鉴权、被限流器判定、
     * 并（在额度内）打到上游。
     *
     * <p>这正是 D1 让全栈验收**观察不到**的那一步：修前请求在鉴权处就变成 401，
     * 限流器根本没被走到（所以「Redis 挂了 ⇒ 降级到本机桶、仍拒绝超额」在真实全栈上不可观测）。
     * 断言 {@code RateLimit-Limit} 头的存在就是「限流器真的被走到了」的直接证据 ——
     * 它对放行与拒绝都会写这条头。
     */
    @Test
    void redisOutageDoesNotStopAValidKeyFromReachingTheLimiterAndTheUpstream() throws Exception {
        String secret = "d1-healthy-admin";
        admin.enqueueJsonForPath(AdminClient.RESOLVE_PATH, 200, VALID_VIEW_ENVELOPE);
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("/v1/chat/completions", bearer(secret));

        assertThat(response.statusCode())
                .as("Redis 只是缓存：它挂了，一把有效（但未缓存）的 key 仍必须被放行")
                .isEqualTo(200);
        assertThat(response.body()).contains("chat.completion");
        assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER))
                .as("限流器必须真的被走到（D1 之前请求在鉴权处就 401 了，永远到不了这一层）")
                .isPresent();
    }

    /**
     * **验收级结论之二（D1 真正挡住的那一条）**：Redis 不可用 + 一次**瞬时**控制面故障之后，
     * 同一把有效 key 的请求必须**真的被限流**（429），而且**故障绝不能反复把它变成 401**。
     *
     * <p>这正是计划 Step 5 第 5 步「`stop redis` 后继续发请求 —— 必须继续被限流」在全栈上
     * **观察不到**的那一步：修前请求在鉴权处就 fail-closed 成 401（并被负缓存锁死约 30 秒），
     * 限流器根本没被走到，所以「降级到本机令牌桶、仍然拒绝超额」这句话在真实全栈上没有证据。
     *
     * <p><b>故障只允许发生一次</b>：第一次回源迟到 3.5 秒（= 真实 admin 被自己的 Redis 超时拖慢
     * 的形状），之后 admin 立刻给出正确视图。于是「故障期间 401」是允许的（fail-closed 不变），
     * 但**第二个 401 就是缺陷** —— 它意味着那次故障被缓存成了「这把 key 不存在」。
     * 修前实测就是第二个请求仍然是 401（见 {@link #faultedResolveIsNotNegativeCachedSoTheKeyWorksImmediatelyAfterTheFaultClears}
     * 的 RED），因此本用例在修前必然变红；「降级 = 放行所有人」的实现则永远打不到 429。
     */
    @Test
    void afterATransientFaultOverLimitRequestsAreStillLimitedInsteadOfRejectedAsBadCredentials()
            throws Exception {
        String secret = "d1-over-limit-after-fault";
        // 第一次回源：跨过网关的内部跳预算（瞬时故障）。
        admin.enqueueStalledJsonForPath(AdminClient.RESOLVE_PATH, 200, VALID_VIEW_ENVELOPE,
                BEYOND_GATEWAY_INTERNAL_HOP_BUDGET_MILLIS);
        // 故障已清除：此后 admin 立刻回答。
        admin.enqueueJsonForPath(AdminClient.RESOLVE_PATH, 200, VALID_VIEW_ENVELOPE);

        boolean faultAlreadyObserved = false;
        HttpResponse<String> rejected = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && rejected == null; attempt++) {
            HttpResponse<String> response = get("/v1/models", bearer(secret));

            if (response.statusCode() == 401) {
                assertThat(faultAlreadyObserved)
                        .as("第 %s 个请求仍然是 401 —— 一次瞬时故障被缓存成了「这把 key 不存在」，"
                                + "于是它被反复拒绝（修前实测：第二个请求 401，且 admin 只被调用过 1 次）", attempt)
                        .isFalse();
                faultAlreadyObserved = true;
                continue;
            }

            assertThat(response.statusCode())
                    .as("第 %s 个请求：Redis 挂了也只能是「放行」或「限流」，"
                            + "绝不能是「你的 key 是错的」", attempt)
                    .isIn(200, 429);
            assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER))
                    .as("第 %s 个请求：限流头是「判定确实由限流器做出」的证据", attempt)
                    .isPresent();
            if (response.statusCode() == 429) {
                rejected = response;
            }
        }

        assertThat(rejected)
                .as("降级 ≠ 放行：Redis 不可用时必须在 %s 次内打到限额（内置默认 qps=10 / burst=20）",
                        MAX_ATTEMPTS)
                .isNotNull();
        assertThat(rejected.body())
                .contains("\"code\":\"rate_limit_exceeded\"")
                .contains("\"type\":\"rate_limit_error\"");
    }

    private String bearer(String secret) {
        return "Bearer ak_d1." + secret;
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + path))
                .header("Authorization", authorization)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String authorization) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + path))
                .header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":false}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
