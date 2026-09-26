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
 * 于是它证的只是「缓存有货时限流链正确」。而「缓存没货」才是生产里第一个请求的真实状态 ——
 * 那正是 Task 9 复审发现的两个症状的共同成因：
 *
 * <ol>
 *   <li>{@code ConfigClient.current()} 在两级缓存皆空时走 {@code refreshBlocking()} → {@code Mono.block()}，
 *       而当时的过滤器是**在 event loop 上**同步调用 {@code limiter.acquire} 的，于是 {@code block()}
 *       必然抛 {@code IllegalStateException}、被吞掉、{@code lastGood} 仍为 null，最后回落到内置
 *       {@code 10/20} —— <b>租户级与密钥级策略在冷启动期间被静默忽略</b>；</li>
 *   <li>{@code src/main} 里没有任何地方调用 {@code ConfigClient.refresh()}，所以这个窗口只有
 *       「外部写入方把 Redis 快照键填上」才能结束。</li>
 * </ol>
 *
 * <p>本类因此**不预热**，并断言第一个请求的 {@code RateLimit-Limit} 就是快照里的
 * {@code 1, 30}，而不是内置的 {@code 10, 20}。判别性是完整的：任何「没走上事件循环之外」的
 * 实现都会在这里报 {@code 10, 20}（修复前的实测红法见任务报告的修复段）。
 *
 * <p><b>{@code @DirtiesContext} 是这条用例能成立的前提</b>：Spring 按类缓存测试上下文，而
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
     * 也就是说「冷启动策略被静默忽略」这条症状在这个代码形态下**没有复现**。
     *
     * <p>那为什么还留着它：它钉的是一个**真实且必要**的行为（冷缓存也必须用上控制面策略），
     * 而复审要求的修复（把判定显式切到 {@code boundedElastic}）把「这条路径能不能阻塞」
     * 从「依赖认证过滤器恰好先跑过」变成**本类自己的不变式**。判定不再依赖别的过滤器的线程位置，
     * 这条不变式由 {@code RateLimitEventLoopTest} 直接证明。
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
