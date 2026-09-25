package com.aihub.service.metering;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度开关独立成一个配置类：与 {@code MybatisMapperConfig} 同理，避免被
 * {@code @WebMvcTest} 之类的切片连带 import（切片里没必要拉起调度器）。
 */
@Configuration
@EnableScheduling
public class MeteringSchedulingConfig {
}
