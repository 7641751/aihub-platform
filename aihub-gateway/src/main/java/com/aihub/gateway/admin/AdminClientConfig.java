package com.aihub.gateway.admin;

import com.aihub.gateway.auth.AuthProperties;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 注册 {@link AdminClient}。
 *
 * <p><b>{@code @EnableConfigurationProperties(AuthProperties.class)} 是必需的，不是装饰：</b>
 * 网关没有 {@code @ConfigurationPropertiesScan}，{@code AuthProperties} 只在自己头上标了
 * {@code @ConfigurationProperties}，不会被任何组件扫描发现。少了这一行，
 * {@code ApiKeyAuthFilter} 与 {@code ApiKeyResolver} 会因缺少 {@link AuthProperties} bean 而启动失败。
 * 全工程只有这一处注册它（重复注册会产生两个 bean）。
 *
 * <p>超时故意比上游中继短得多：回源是同步阻塞在请求路径上的，宁可快速判 401 也不能拖住客户端。
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AdminClientConfig {

    /**
     * bean 名故意不叫 {@code adminClient}：测试用 {@code @TestConfiguration} 提供的假 {@code AdminClient}
     * bean 就叫这个名字，而 Spring Boot 默认 {@code allow-bean-definition-overriding=false}，
     * 同名会直接让上下文启动失败（{@code BeanDefinitionOverrideException}）。换名之后「测试替身」
     * 靠 {@code @Primary} 胜出，两条定义可以共存，也不必为测试去打开全局 bean 覆盖开关。
     */
    @Bean
    public AdminClient httpAdminClient(AuthProperties properties,
                                       @Value("${aihub.internal.secret:}") String internalSecret) {
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.adminBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
                        .responseTimeout(Duration.ofSeconds(3))))
                .build();
        return AdminClient.http(webClient, internalSecret);
    }
}
