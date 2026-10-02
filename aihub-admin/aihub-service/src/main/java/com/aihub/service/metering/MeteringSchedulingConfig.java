package com.aihub.service.metering;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度开关独立成一个配置类：与 {@code MybatisMapperConfig} 同理，避免被
 * {@code @WebMvcTest} 之类的切片连带 import（切片里没必要拉起调度器）。
 *
 * <p>{@code @EnableScheduling} 是这个应用里**唯一**的调度开关，因此它同时驱动所有 {@code @Scheduled}：
 * 本包的分区维护（{@code RequestLogPartitionMaintainer}，默认 03:10 UTC）与 M4 的每日对账
 * （{@code com.aihub.service.quota.QuotaReconciliationJob}，默认 02:00 UTC，见决策 D12）。
 * 两者的 cron 都由 {@code @Scheduled} 表达式上的属性占位符覆盖，且都**显式钉在 {@code zone = "UTC"}** ——
 * 默认 JVM 时区（本机 UTC+8）会把字面时刻整体推走，这是本里程碑的硬规则。
 */
@Configuration
@EnableScheduling
public class MeteringSchedulingConfig {
}
