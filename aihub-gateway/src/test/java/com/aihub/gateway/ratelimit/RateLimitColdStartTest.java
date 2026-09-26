package com.aihub.gateway.ratelimit;

import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **冷启动**端到端：没有任何预热，请求路径上的第一个判定必须用上控制面配置的策略。
 *
 * <p><b>为什么必须单独一个类</b>：{@link RateLimitWiringTest} 在测试线程上预热了配置缓存，
 * 于是它证的只是「缓存有货时限流链正确」。而「缓存没货」才是生产里第一个请求的真实状态。
 *
 * <p><b>实测说明（诚实记录，本轮更正）</b>：本类**修前也是绿的** —— 冷启动的第一个请求拿到的就是
 * {@code 1, 30}，不是内置 {@code 10, 20}。原因是认证过滤器在**它自己**的缓存未命中时把下游链切到了
 * {@code boundedElastic}（M2 给 {@code ApiKeyResolver} 的处置），而第一个请求的认证必然是未命中，
 * 于是限流那一跳顺带落在一个可阻塞线程上，{@code ConfigClient.current()} 里的 {@code Mono.block()}
 * 因此没有抛。**不能**从这里推出「冷缓存策略被静默忽略这个症状不可能发生」：它同样不能证明
 * 「任何非 offload 的实现都会在这里报 {@code 10, 20}」（本类在那种实现下照样绿，因为让
 * {@code block()} 合法的是认证过滤器，不是限流过滤器）。
 *
 * <p>症状真实的触发条件是「判定留在 event loop 上」——认证本地缓存**命中**（生产常见路径）或
 * 鉴权关闭 —— 且 {@code ConfigClient.current()} 走到 {@code refreshBlocking()}。那一条由
 * {@link RateLimitColdCacheAuthDisabledTest} 判别（鉴权关闭 ⇒ 判定必然留在 event loop）。
 * 本类因此只主张它能主张的事：**冷缓存下策略必须生效**（这是必要行为，只是判别力有限）。
 *
 * <p><b>{@code @DirtiesContext} 是本类能成立的前提</b>：Spring 按类缓存测试上下文，而
 * {@link RateLimitWiringTest} 会把同一个上下文里的 {@code ConfigClient} 预热。若共享上下文，
 * 「冷启动」就会**静默地**变成同义反复（拿到的是已经被 {@code current()} 调用过、{@code lastGood}
 * 已非空的那个实例）。类前置清脏让本类必然拿到全新上下文；
 * {@link #configSnapshotVersionGaugeStartsAtZero()} 则把「上下文确实是冷的」钉死 —— 前提塌了立刻红。
 *
 * <p>类前置而不是类后置：{@code BEFORE_CLASS} 一定发生在**建上下文之前**；后置清脏依赖
 * 「哪个类先跑」，一个将来新增的同配置测试类就能把它绕过去。
 *
 * <p><b>类内方法顺序也是被钉住的</b>（{@code @TestMethodOrder} + {@code @Order(1)}）：
 * {@code @DirtiesContext} 只保证「本类开始时上下文是新的」，**同一个类里的用例仍然共享那个上下文**。
 * 于是只要任何一个发请求的用例先跑，{@code visibleVersion} 就会变成 1，那条「前提检查」随即失败
 * —— 前提检查失败是这个类**应当**的报错方式（前提塌了不能绿），但它必须是在真的塌了时失败，
 * 而不是因为方法顺序。曾实测过：把三个用例放在一起按默认顺序跑，请求用例先跑，前提检查就报
 * {@code expected: 0L but was: 1L}。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitColdStartTest extends RateLimitWiringSupport {

    @Autowired
    private ConfigClient configClient;

    /**
     * 「冷」不是本类的假设，而是被断言出来的：{@code 0} 表示本进程还**没有任何**控制面快照
     * （{@code ConfigClient.visibleVersion()} 只在两级缓存命中或一次成功回源之后才会变成非 0）。
     * 它不是本类要证的业务事实，而是这条用例的前提 —— 前提塌了必须立刻红，不能绿。
     */
    @Test
    @Order(1)
    void configSnapshotVersionGaugeStartsAtZero() {
        assertThat(configClient.visibleVersion())
                .as("本类必须跑在一个从没读过控制面快照的上下文里，否则「冷启动」这条用例没有判别力")
                .isZero();
    }

    /**
     * 冷缓存 + 降级限流：第一个请求的限额必须来自**控制面快照**（{@code 1, 30}），
     * 而请求路径上从来没有谁预热过缓存。
     *
     * <p><b>实测说明（诚实记录）</b>：在本提交的基线上，这条用例修**前**就已经是绿的
     * —— 冷启动的第一个请求拿到的就是 {@code 1, 30}，不是内置 {@code 10, 20}。
     * 原因是认证过滤器在**它自己**的缓存未命中时把下游链切到了 {@code boundedElastic}
     * （M2 给 {@code ApiKeyResolver} 的处置），而第一个请求的认证必然是未命中，于是限流那一跳
     * 顺带落在一个可阻塞线程上，{@code ConfigClient.current()} 里的 {@code Mono.block()} 因此没有抛。
     * 这**只**说明「在这个被探测的形状下没有观察到症状」，**不是**「症状不可能发生」：
     * 让 {@code block()} 合法的是认证过滤器，不是限流过滤器，所以本类对「判定留在 event loop 上」
     * 的实现同样会绿（判别力为零）。真正的判别性用例是
     * {@link RateLimitColdCacheAuthDisabledTest}（鉴权关闭 ⇒ 判定必然留在 event loop）。
     *
     * <p>那为什么还留着它：它钉的是一个**真实且必要**的行为（冷缓存也必须用上控制面策略）。
     * 而修复（把判定显式切到 {@code boundedElastic}）把「这条路径能不能阻塞」从
     * 「依赖认证过滤器恰好先跑过」变成**本类自己的不变式**；该不变式由
     * {@code RateLimitEventLoopTest} 直接证明，与冷缓存无关。
     */
    @Test
    void aColdCacheRequestStillUsesTheConfiguredPolicy() throws Exception {
        upstream().enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("/v1/chat/completions", IN_LIMIT_SECRET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER))
                .as("冷启动的第一个请求必须用上快照里的策略，而不是内置默认 %s/20",
                        com.aihub.common.config.RatePolicy.DEFAULT_QPS)
                .hasValue(QPS + ", " + BURST);
        assertThat(response.body()).contains("chat.completion");
        // 一次成功回源之后 gauge 必须前进 —— 它证明「策略真的是这一跳从控制面拿到的」，
        // 而不是某个与快照无关的常量恰好等于 1/30。
        assertThat(configClient.visibleVersion())
                .as("冷启动回源成功后可见版本必须离开 0")
                .isEqualTo(1L);
    }

    /**
     * 冷启动的另一半：超限请求**在冷缓存下**仍然被拒（降级 ≠ 放行全部）。
     *
     * <p>与 {@link #aColdCacheRequestStillUsesTheConfiguredPolicy()} 用**不同**的密钥
     * （⇒ 不同桶），因此两条用例互不消耗对方的名额，执行顺序无关。
     */
    @Test
    void aColdCacheOverLimitRequestIsStillRejected() throws Exception {
        HttpResponse<String> rejected = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && rejected == null; attempt++) {
            HttpResponse<String> response = post("/v1/chat/completions", OVER_LIMIT_SECRET);
            assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER))
                    .as("冷缓存下的每一次判定都必须用快照策略（%s/%s）", QPS, BURST)
                    .hasValue(QPS + ", " + BURST);
            if (response.statusCode() == 429) {
                rejected = response;
            } else {
                assertThat(response.statusCode())
                        .as("第 %s 个请求只能有两种结局：放行或 429", attempt)
                        .isEqualTo(200);
            }
        }

        assertThat(rejected).as("冷缓存下必须在 %s 次尝试内打到限额（降级不是「放行所有人」）", MAX_ATTEMPTS)
                .isNotNull();
        assertThat(rejected.body())
                .contains("\"code\":\"rate_limit_exceeded\"")
                .contains("\"type\":\"rate_limit_error\"")
                .contains("\"param\":null");
        assertThat(rejected.headers().firstValue(RateLimitFilter.REMAINING_HEADER)).hasValue("0");
    }
}
