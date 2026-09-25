package com.aihub.common.apikey;

import java.time.Instant;

/**
 * 密钥视图：MySQL 真相源、Redis 缓存载荷、admin 内部接口响应、gateway 解析结果**共用同一个类型**，
 * 因此「什么样的密钥算可用」只有一处定义。
 */
public record ApiKeyView(String keyId, long tenantId, String tenantName, String status, Instant expireAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public boolean usable() {
        return STATUS_ACTIVE.equals(status) && (expireAt == null || expireAt.isAfter(Instant.now()));
    }
}
