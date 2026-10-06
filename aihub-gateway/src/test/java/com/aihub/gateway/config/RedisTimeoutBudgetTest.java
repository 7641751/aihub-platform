package com.aihub.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>生产的 Redis 命令超时必须是一个「小预算」</b>（2026-10-06 加，用于钉住一次真实故障的根因修复）。
 *
 * <p><b>它防的是什么（实测原文，不是猜测）</b>：{@code docker compose stop redis} 之后，网关对
 * {@code /v1/chat/completions} 的单请求耗时实测为 <b>10.157 s / 10.153 s / 12.160 s</b>（全部 HTTP 200，
 * 即「不误拒」这条设计成立），网关日志里反复出现
 * {@code io.lettuce.core.RedisCommandTimeoutException: Command timed out after 2 second(s)}。
 * 也就是说：**每条 Redis 命令各付满一次 2 s**，而一个请求路径上有 5–6 个串行的阻塞式 Redis 触点
 * （配置版本探测、{@code ApiKeyResolver} 的读、限流 Lua、配额预扣 Lua + 校正、渠道熔断读，见
 * {@code ConfigClient.resolve()} / {@code RedisRateLimiter} / {@code RedisQuotaLimiter} /
 * {@code ChannelCircuitBreaker}），于是 5 条 = 10 s、6 条 = 12 s。
 *
 * <p><b>为什么是「小预算」而不是「0」</b>：这些调用本身是必需的（降级路径要有结论可拿），
 * 且 {@code StringRedisTemplate} 是 Lettuce 的<b>同步</b>驱动、调用方在 WebFlux 过滤器链上 ——
 * 超时值直接就是「这个线程被按住多久」。{@code 300 ms} 的依据是与 admin 侧在 D1 修复里
 * 已经落地的口径一致（admin 的 {@code spring.data.redis.timeout} 当时从 2 s 收到 500 ms，
 * 理由逐字相同：「读缓存 + 回写缓存各等一次超时，会把答案拖过网关给内部跳的 3 s」）；
 * 网关侧当轮没改，这条用例把该决定在**网关**这一端钉住。
 *
 * <p><b>为什么读「出厂」{@code application.yml} 而不是 {@code environment.getProperty(...)}</b>：
 * 测试资源里有自己的 {@code application.properties}，且部分用例用动态属性源把
 * {@code spring.data.redis.timeout} 覆盖成 500 ms（见 {@code RateLimitWiringSupport}）——
 * 合并后的环境值反映不了**生产实际发的那个数字**。这与
 * {@code ConfigSubscriberTest.theProductionDefaultOfTheInvalidateSubscriptionIsOn} 和
 * {@code QuotaReserveFallbackTest.theFallbackSwitchDefaultsToOnAndCanBeTurnedOff} 是同一套口径。
 *
 * <p><b>本类不起 Spring 上下文</b>（只读一个 yml）——按 {@code docs/CONVENTIONS.md} §8 的上下文预算纪律。
 *
 * <p>⚠️ <b>登记的残余</b>：生产可以用 {@code SPRING_DATA_REDIS_TIMEOUT} 环境变量把这个值改大，
 * 而本用例只看仓库里发出去的那一份。要防住部署侧的改动需要外部配置校验，不在本用例职责内。
 */
class RedisTimeoutBudgetTest {

    /** 上界：单个 Redis 命令最多按住调用线程多久。 */
    private static final Duration BUDGET = Duration.ofSeconds(1);

    @Test
    void theShippedRedisCommandTimeoutStaysWithinASmallBudget() throws IOException {
        var shipped = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(shipped).as("主配置必须能被读到").isNotEmpty();
        Object raw = shipped.get(0).getProperty("spring.data.redis.timeout");
        assertThat(raw)
                .as("application.yml 必须显式写死 spring.data.redis.timeout —— 缺失时 Lettuce 用的是"
                        + "默认值，而「每请求 5–6 次串行等待」的代价与它成正比")
                .isNotNull();

        Duration timeout = DurationStyle.detectAndParse(String.valueOf(raw));
        assertThat(timeout)
                .as("单个 Redis 命令的预算必须 ≤ %s（实测：2 s × 5–6 条串行 = 单请求 10–12 s）。"
                        + "当前出厂值 = %s", BUDGET, timeout)
                .isGreaterThan(Duration.ZERO)
                .isLessThanOrEqualTo(BUDGET);
    }
}
