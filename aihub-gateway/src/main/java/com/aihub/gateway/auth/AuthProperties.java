package com.aihub.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 鉴权相关配置。
 *
 * @param enabled       是否启用 API Key 鉴权。测试与本地裸跑可置 false；生产必须为 true
 * @param localCacheTtl Caffeine 本地缓存 TTL（二级缓存的第一级）
 * @param keyCacheTtl   Redis 共享缓存 TTL（第二级）
 * @param adminBaseUrl  admin 内部接口地址（第三级回源）
 */
@ConfigurationProperties(prefix = "aihub.auth")
public record AuthProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("30s") Duration localCacheTtl,
                             @DefaultValue("5m") Duration keyCacheTtl,
                             @DefaultValue("http://127.0.0.1:8081") String adminBaseUrl) {
}
