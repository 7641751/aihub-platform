package com.aihub.common.apikey;

import java.time.Instant;

/**
 * 密钥视图：MySQL 真相源、Redis 缓存载荷、admin 内部接口响应、gateway 解析结果**共用同一个类型**，
 * 因此「什么样的密钥算可用」只有一处定义。
 *
 * <p>{@code apiKeyId} 是 {@code api_key} 表的**数值主键**，M3 起随密钥视图一起下发：
 * M2 时它恒为 {@code null}（共享类型里没有它），导致 {@code request_log.api_key_id} 无法填充、
 * 用量无法按 API Key 聚合，也导致限流无法把请求映射到 {@code rate_limit_policy.api_key_id}
 * （决策 7 的两维策略需要一个数值键）。补齐它需要同时改三处 —— 本 record、{@code ApiKeyCacheCodec}
 * 的载荷段数、两侧的解析（admin 的 {@code ApiKeyService} 与 gateway 的 {@code AdminClient.Http.parse}）
 * （计划决策 14）。「查不到」的哨兵带 {@code null}：**匿名桶没有数值主键，那是正常路径而不是错误**。
 *
 * <p><b>（Task 9，2026-09-30）此处曾经的 {@code UNUSABLE} 常量已删除</b>：D4 之后它在生产代码里
 * **没有任何调用方**（gateway 侧要表达的是「我们**判不了**这把 key 是否有效」，而那根本不是一种密钥
 * 视图，所以改用了 {@code AdminResolution.unavailable()}，与「权威地判定不可用」在类型上可区分），
 * 删掉它是附录 A8 要求的那个结论（{@code grep -rn UNUSABLE} 当时只剩它的声明与
 * {@code ApiKeyToolingTest} 的一条用例）。判断「可用与否」今天仍然只有 {@link #usable()} 一个出口。
 */
public record ApiKeyView(String keyId, long tenantId, String tenantName, String status, Instant expireAt,
                         Long apiKeyId) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public boolean usable() {
        return STATUS_ACTIVE.equals(status) && (expireAt == null || expireAt.isAfter(Instant.now()));
    }
}
