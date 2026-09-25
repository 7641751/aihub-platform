package com.aihub.gateway.testsupport;

import com.aihub.gateway.meter.MeteringTransport;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 把生产的 RabbitMQ 投递器换成内存记录器：网关测试不允许依赖 Docker（M1 定下的纪律）。
 * {@code @Primary} 覆盖 {@code MeteringConfig#meteringTransport}，因此
 * {@code MeteringDispatcher} 注入的也是它。
 */
@TestConfiguration
public class MeteringTestConfig {

    @Bean
    @Primary
    public RecordingMeteringTransport recordingMeteringTransport() {
        return new RecordingMeteringTransport();
    }
}
