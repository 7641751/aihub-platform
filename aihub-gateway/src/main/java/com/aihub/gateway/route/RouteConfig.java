package com.aihub.gateway.route;

import com.aihub.gateway.config.ConfigClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 路由与熔断的装配点（G9）。**没有这个类，M3 的故障转移在生产里就是不可达的**：
 * {@link RouteResolver} 与 {@link ChannelCircuitBreaker} 都只是普通类，没有任何 {@code @Component}
 * —— 中继拿到的是「没有 bean 可注入」，整个渠道感知路径根本起不来。
 *
 * <p>与 {@code ConfigConfig} / {@code RateLimiterConfig} 同一套纪律：
 * <ul>
 *   <li><b>构造 bean 不发起任何 I/O</b>。Redis 连接是惰性的（{@code ChannelCircuitBreaker} 的读写
 *       都在 try/catch 里，Redis 挂掉只降级不抛），配置快照只在第一次判定时读；因此「Redis 挂 +
 *       admin 不可达」不会让网关起不来（决策 6）。</li>
 *   <li><b>策略/候选每次惰性读快照</b>（{@link RouteResolver} 收的是一个 {@code Supplier}）：
 *       控制面改了路由，下一个请求就生效，不需要重启。</li>
 *   <li>{@code MeterRegistry} 走**注入**而不是 {@code Metrics.globalRegistry} 这类静态全局注册表：
 *       注册表由 Spring Boot 的 actuator 自动配置提供，而全局静态注册表在测试之间会互相污染
 *       （与 {@code ConfigConfig} / {@code MeteringConfig} 的同一套理由）。</li>
 * </ul>
 *
 * <p><b>登记的调度缺口（评审遗留，不在本任务修）</b>：{@code configClient::current} 会被
 * {@link RouteResolver#candidates(String)} 在**每一个被中继的请求**上读到（回源本身被冷却窗口限制成
 * 「每窗口至多一次」，但读快照这件事是逐请求的）。生产默认 {@code aihub.ratelimit.enabled=true}，因此
 * 请求路径在可阻塞的弹性线程池上；而网关的**测试**默认把它关掉（多个 {@code @SpringBootTest} 显式
 * 写 {@code false}），于是被测到的那套调度是 Netty event loop。两者不是同一套调度 —— 将来若把
 * {@code CandidateList} 的读取做成热路径，必须先在**限流开启**的形态下复核（见 {@code ConfigClient} 与
 * Task 10 报告里的同一条登记）。
 */
@Configuration
public class RouteConfig {

    /**
     * 跨实例熔断器。时钟用 {@code System::currentTimeMillis}（本机降级表的过期判定需要它）；
     * Redis 不可用时它退化成本机表，检测与恢复都不依赖 Redis 可用。
     */
    @Bean
    public ChannelCircuitBreaker channelCircuitBreaker(StringRedisTemplate redis) {
        return new ChannelCircuitBreaker(redis, System::currentTimeMillis);
    }

    /**
     * {@code model → 有序候选渠道}。随机源必须是**线程安全**的：本类是请求路径上的单例，而
     * {@code RandomGenerator.getDefault()} 的实现不保证线程安全（见 {@link RouteResolver} 的类注释）。
     * {@code ThreadLocalRandom} 无锁且按调用线程取种子，因此并发抽取不会出现重复或偏斜。
     */
    @Bean
    public RouteResolver routeResolver(ConfigClient configClient, ChannelCircuitBreaker circuitBreaker,
                                       MeterRegistry registry) {
        return new RouteResolver(configClient::current, circuitBreaker,
                ThreadLocalRandom.current(), registry);
    }
}
