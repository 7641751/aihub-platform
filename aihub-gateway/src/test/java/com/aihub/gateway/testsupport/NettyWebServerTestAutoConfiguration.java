package com.aihub.gateway.testsupport;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;

/**
 * <b>把网关测试的嵌入式 Web 服务器钉死成 Netty</b>（生产用的那一个）。
 *
 * <p>为什么需要这个类：Spring Boot 3.5.16 的 {@link ReactiveWebServerFactoryAutoConfiguration}
 * 用 {@code @Import} 按
 * <b>Tomcat → Jetty → Undertow → Netty</b> 的顺序引入四个候选，每个候选都带
 * {@code @ConditionalOnMissingBean(ReactiveWebServerFactory.class)} 与各自的
 * {@code @ConditionalOnClass}。也就是说 <b>第一个 classpath 上成立的候选胜出</b>，而 Netty 排在最后。
 *
 * <p>M3 的 WireMock 验收需要 {@code org.wiremock:wiremock-jetty12}（core 自带的 Jetty 11 绑定与父 POM
 * 管理的 Jetty 12 冲突，见 {@code aihub-gateway/pom.xml}），它把
 * {@code org.eclipse.jetty.ee10.servlet.ServletHolder} 与 {@code org.eclipse.jetty.server.Server}
 * 带进测试 classpath —— 于是 Boot 的 {@code EmbeddedJetty} 条件成立并**先于 Netty 注册**，
 * <b>整个网关测试套件会静默地从 Netty 换成「跑在 Servlet 模式下的 Jetty」</b>。
 *
 * <p>后果不是「换个实现也一样」：在 Servlet 那条写回路径上，
 * {@code ServletServerHttpResponse} 的 async 完成回调会在**每一个响应正常结束时**
 * 取消写-flush 处理器（{@code ChannelSendOperator.WriteBarrier.cancel}），
 * 于是中继挂在上游 body 上的 {@code doFinally(CANCEL)}（Task 10 用来识别客户端断连的那条）
 * 会在**每次成功响应**上误报「客户端断连」，把 {@code request_log.status} 记成
 * {@code CANCELLED/client_disconnected}。实测：只要 classpath 上有 Jetty 的 servlet 绑定，
 * {@code RelayMeteringFlowTest}（3）、{@code SseStreamingTest}（1）、{@code FailoverRelayTest}（1）
 * 这五个既有用例就会稳定变红。
 *
 * <p>因此这里显式提供一个 {@link NettyReactiveWebServerFactory}，并用
 * {@code @AutoConfiguration(before = …)} 让它在 Boot 自己那批候选之前注册：四个候选随后都因
 * {@code @ConditionalOnMissingBean} 退让，测试环境与生产环境（{@code spring-boot-starter-webflux}
 * → Netty）重新一致。这条约束是可执行的：
 * {@code AihubGatewayApplicationTests#theGatewayTestContextRunsOnNettyNotOnAServletContainer()}
 * 会断言容器里跑的就是 {@code NettyWebServer}。
 *
 * <p>刻意**不用**「把 Jetty 从测试 classpath 里排掉」的写法：WireMock 的 Jetty 12 绑定就是它的
 * HTTP 服务器，排掉它验收测试根本起不来。把「用哪个 Web 服务器」写成显式选择，比依赖
 * 「classpath 上恰好没有别人」稳健得多 —— 将来任何再引入 servlet 容器的测试依赖都不会再偷偷换掉它。
 */
@AutoConfiguration(before = ReactiveWebServerFactoryAutoConfiguration.class)
public class NettyWebServerTestAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ReactiveWebServerFactory.class)
    NettyReactiveWebServerFactory nettyReactiveWebServerFactory() {
        return new NettyReactiveWebServerFactory();
    }
}
