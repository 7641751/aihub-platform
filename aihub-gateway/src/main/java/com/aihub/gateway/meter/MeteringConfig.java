package com.aihub.gateway.meter;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;

/**
 * M2 计量链路的装配点。{@code @EnableScheduling} 放在这里而不是启动类上：
 * 切片测试（{@code @WebFluxTest} 等）按「非 Web 组件」把它过滤掉，不会白白拉起调度器
 * —— 与 {@code MybatisMapperConfig} 处理 {@code @MapperScan} 的理由一致。
 *
 * <p>这里**没有**任何拓扑声明（队列 / 交换器）：唯一声明方是 admin（见计划「决策登记」第 4 条），
 * 因此网关的 {@code spring.rabbitmq.dynamic} 显式关掉（见 application.yml）。
 */
@Configuration
@EnableConfigurationProperties(MeteringProperties.class)
@EnableScheduling
public class MeteringConfig {

    @Bean
    public MeteringSpool meteringSpool(MeteringProperties properties) {
        return new MeteringSpool(Path.of(properties.spoolDir()), properties.spoolMaxFiles());
    }

    @Bean
    public MeteringTransport meteringTransport(RabbitTemplate rabbitTemplate, MeteringProperties properties) {
        return new RabbitMeteringTransport(rabbitTemplate, properties.confirmTimeoutMs());
    }

    @Bean
    public MeteringDispatcher meteringDispatcher(MeteringProperties properties, MeteringTransport transport,
                                                 MeteringSpool spool, MeterRegistry registry) {
        return new MeteringDispatcher(properties, transport, spool, registry);
    }

    @Bean
    public MeteringPublisher meteringPublisher(MeteringProperties properties, MeteringDispatcher dispatcher) {
        return new MeteringPublisher(properties, dispatcher);
    }
}
