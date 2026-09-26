package com.aihub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code aihub.config.*}：三级配置读取与两级缓存的参数（设计文档 §6.3）。
 *
 * @param localTtl                Caffeine 本地缓存 TTL（§6.3 明文要求 30 秒兜底）
 * @param snapshotTtl             Redis 共享缓存 TTL（spec 没定；取 10 分钟）
 * @param maxLocalSnapshotSources 本地缓存的来源数上界（当前恒为 1，保留是为了让容量在配置里可见）
 */
@ConfigurationProperties(prefix = "aihub.config")
public record GatewayConfigProperties(@DefaultValue("30s") Duration localTtl,
                                      @DefaultValue("10m") Duration snapshotTtl,
                                      @DefaultValue("300") int maxLocalSnapshotSources) {
}
