package com.aihub.common.apikey;

import java.time.Instant;

/**
 * 密钥视图：MySQL 真相源、Redis 缓存载荷、admin 内部接口响应、gateway 解析结果**共用同一个类型**，
 * 因此「什么样的密钥算可用」只有一处定义。
 */
public record ApiKeyView(String keyId, long tenantId, String tenantName, String status, Instant expireAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    /**
     * 「不可用」的**唯一**哨兵：解析不到密钥、密钥不存在、以及调用方（过滤器）遇到空 {@code Mono}
     * 的兜底，都用这一个实例。
     *
     * <p>它存在的意义是让「不可用」有单一表达：{@code usable()} 为 false，于是所有调用方只需判断
     * {@code usable()} 一次。之前 gateway 侧有两个各自 {@code new} 出来的同形实例（解析器的 MISS 与
     * 过滤器的 UNRESOLVED）；今天它们恰好都是 {@code status="MISSING"} 才等价，一旦将来
     * {@code usable()} 的判据从 {@code status} 改成别的（例如认实例身份），两份哨兵就会分裂成
     * 两种行为。放在这里（{@code aihub-common}，零依赖、JDK-only）后，两侧共用同一份 notion。
     */
    public static final ApiKeyView UNUSABLE = new ApiKeyView("", 0L, "", "MISSING", null);

    public boolean usable() {
        return STATUS_ACTIVE.equals(status) && (expireAt == null || expireAt.isAfter(Instant.now()));
    }
}
