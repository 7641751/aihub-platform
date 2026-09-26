package com.aihub.gateway.ratelimit;

import com.aihub.common.config.RatePolicy;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **冷配置缓存 + 鉴权关闭 + 异步控制面**：把「限流判定留在 event loop 上」做成配置事实，从而给出
 * 「冷缓存时 {@code ConfigClient.current()} 在 event loop 上 {@code Mono.block()} 抛异常、策略被
 * 静默忽略」这个症状的**判别性 RED**（复审 Fix 2）。
 *
 * <p><b>为什么「鉴权关闭」还不够，还要异步控制面</b>：判定留在 event loop 上会命中
 * {@code ConfigClient.refreshBlocking()}。但 {@code Mono.block()} 的**非阻塞线程检查发生在订阅
 * 之后**（{@code subscribe()} 先把源跑起来，随后 {@code blockingGet()} 才抛
 * {@code IllegalStateException}）。因此如果控制面是**同步**的（{@code Mono.just}），这次回源会在
 * 抛异常之前就把快照写进本地缓存，紧接着 {@code current()} 的第二次 {@code resolve()} 命中缓存、
 * 照样给出 {@code 1, 30} —— 症状被夹具本身掩盖，用例在修复前也会是绿的（**实测就是这样**：
 * 第一版用同步假 admin 写的这条用例修前修后都绿，没有任何判别力）。
 * 真实的 {@code AdminClient.Http} 是 WebClient、**异步**（{@code AdminClient.java:108-124}），
 * 结果不可能在订阅的同一拍到达，所以本类把
 * {@link RateLimitWiringSupport#FAKE_ADMIN_LATENCY_PROPERTY} 设为 250ms：这既是**更忠实**的夹具，
 * 也让「回源在飞的这段时间里 {@code current()} 只能回落到遗留单渠道」成为确定性事实。
 *
 * <p>为什么选「鉴权关闭」这个触发条件：{@code RateLimitColdStartTest} 的冷启动请求证不了症状 ——
 * 第一个请求的认证缓存必然是冷的，{@code ApiKeyResolver} 因此把下游链切到 {@code boundedElastic}
 * （M2 的处置），限流那一跳顺带落在可阻塞线程上，{@code block()} 于是没有抛。只要判定留在
 * event loop 上就会命中 {@code refreshBlocking()}；「鉴权关闭」不依赖任何缓存状态，是确定性的那个
 * 构造（另一个等价构造是「认证本地缓存命中」——生产常见路径，但要先把认证缓存预热成事实）。
 *
 * <p>没有 {@code ApiKeyView} ⇒ 租户 {@code 0} + 匿名桶，策略由快照里的**租户 0 行**给出
 * （{@code 1/30}，与内置默认 {@code 10/20} 刻意不同）。
 *
 * <p>两种实现下的预期（实测见任务报告）：
 * <ul>
 *   <li><b>修复前</b>（判定同步跑在 event loop 上）：{@code blockingGet()} 抛
 *       {@code IllegalStateException} → 被 {@code ConfigClient.refreshBlocking} 吞成一次
 *       「回源失败」→ 两级缓存此刻仍空（回源还在飞）→ 回落遗留单渠道（没有策略行）→
 *       {@code RateLimit-Limit: 10, 20}（**配置被静默忽略**）；</li>
 *   <li><b>修复后</b>（判定在 {@code boundedElastic} 上）：{@code block()} 合法 → 等回源返回
 *       → 拿到快照 → {@code 1, 30}。</li>
 * </ul>
 *
 * <p><b>为什么自成一个类</b>：{@code aihub.auth.enabled} 与假 admin 的时延都是**上下文级**配置，
 * 而 Spring 的测试上下文按类缓存；把它们塞进 {@code RateLimitColdStartTest} 会连那条用例的鉴权与
 * 时延一起改掉。装配（死端口 Redis、假 admin、请求辅助、快照）全部复用
 * {@link RateLimitWiringSupport}，本类只多声明两条上下文属性；
 * {@code @DirtiesContext}(BEFORE_CLASS) 保证拿到全新上下文（即「冷」），
 * {@link #theContextStartsWithAColdConfigCache()} 把这个前提钉成断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=false", "aihub.test.fake-admin-latency=250"})
@Import({MeteringTestConfig.class, RateLimitWiringSupport.FakeAdmin.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitColdCacheAuthDisabledTest extends RateLimitWiringSupport {

    @Autowired
    private ConfigClient configClient;

    /**
     * 「冷」不是本类的假设，而是被断言出来的前提（{@code 0} = 本进程还没读过任何控制面快照）。
     * 它必须最先跑（{@code @Order(1)}）：同一个类里的用例共享上下文，任何一个发请求的用例先跑都会
     * 把版本推到 1，让下面那条用例静默地失去判别力。
     */
    @Test
    @Order(1)
    void theContextStartsWithAColdConfigCache() {
        assertThat(configClient.visibleVersion())
                .as("本类必须跑在从没读过控制面快照的上下文里，否则「冷缓存」这条用例没有判别力")
                .isZero();
    }

    /**
     * 冷配置缓存 + 鉴权关闭 + 降级限流：第一个请求的限额必须来自**控制面快照**的租户 0 策略
     * （{@code 1, 30}），而不是内置默认（{@code 10, 20}）。
     *
     * <p>断言里的 {@code 1, 30} 与内置默认必须不同 —— 这正是判别力的来源：一个「在 event loop 上
     * 同步判定」的实现拿不到快照（控制面还在飞），只会写出 {@code 10, 20}。
     */
    @Test
    void aColdCacheRequestWithAuthDisabledUsesTheConfiguredPolicy() throws Exception {
        upstream().enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = postAnonymous("/v1/chat/completions");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(RateLimitFilter.LIMIT_HEADER))
                .as("鉴权关闭 ⇒ 判定留在 event loop；冷缓存的 Mono.block() 若在那里执行就会抛，"
                        + "策略静默回落成内置默认 %s/%s", RatePolicy.DEFAULT_QPS, RatePolicy.DEFAULT_BURST)
                .hasValue(QPS + ", " + BURST);
        // 一次成功回源之后 gauge 必须前进：它证明「策略真的是这一跳从控制面拿到的」，
        // 而不是某个与快照无关的常量恰好等于 1/30。
        assertThat(configClient.visibleVersion())
                .as("冷启动回源成功后可见版本必须离开 0（0 = 仍在服务遗留单渠道，配置被忽略）")
                .isEqualTo(1L);
    }
}
