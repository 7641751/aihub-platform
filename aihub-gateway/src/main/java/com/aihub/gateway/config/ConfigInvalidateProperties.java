package com.aihub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 配置失效订阅的开关：{@code aihub.config.invalidate-subscription}（环境变量
 * {@code AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION}），默认 **true**。
 *
 * <p><b>刻意是一个独立的 record，而不是给 {@link GatewayConfigProperties} 加一个分量</b>：
 * 后者是 record，加一个分量会让**每一个** {@code new GatewayConfigProperties(...)} 的调用点
 * 编译失败（评审核实有 8 处，其中 7 处在 {@code ConfigCacheTest}、1 处在
 * {@code ModelsControllerTest}），而那两个文件不在本任务的改动面里。两个 record 绑定同一个前缀
 * {@code aihub.config} 互不影响（各绑各的键）。
 *
 * <p>默认开着是因为它是**生产**的主动失效通道；网关的测试环境显式关掉它
 * （{@code src/test/resources/application.properties}），因为那里没有活 Redis —— 详见该文件的注释。
 */
@ConfigurationProperties(prefix = "aihub.config")
public record ConfigInvalidateProperties(@DefaultValue("true") boolean invalidateSubscription) {
}
