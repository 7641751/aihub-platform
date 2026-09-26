package com.aihub.gateway.upstream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上游客户端的装配点。
 *
 * <p>M1/M2 时这里直接构造唯一一个 {@code WebClient}；M3 起渠道是**多条**，构造逻辑搬到
 * {@link UpstreamClientFactory}（按渠道 base-url + 超时缓存）。
 *
 * <p><b>这里不再提供任何 {@code WebClient} bean</b>（M3 评审的清理）：那条 bean 是
 * {@code factory.legacy()} 的产物 —— 一个把 {@code aihub.upstream.api-key} 装成**客户端默认头**的
 * 共享客户端。控制器 M3 起走 {@code forChannel(...)} + **逐请求**注入密钥，M2 的鉴权/限流也不经过它，
 * 仓库里已无任何消费者；留着一个没有调用方、却长期持有上游凭据的对象只是把密钥多留在内存里一份。
 * 需要遗留语义的既有测试直接从 {@link UpstreamClientFactory#legacy()} 取（那是工厂的能力，不是 bean），
 * 遗留兜底路径本身走 {@code clientFactory.forChannel(legacyChannel, ...)}，密钥同样来自
 * {@code aihub.upstream.api-key} —— 删掉 bean 不会让兜底路径丢掉上游密钥。
 */
@Configuration
@EnableConfigurationProperties(UpstreamProperties.class)
public class UpstreamClientConfig {

    @Bean
    public UpstreamClientFactory upstreamClientFactory(UpstreamProperties properties) {
        return new UpstreamClientFactory(properties);
    }
}
