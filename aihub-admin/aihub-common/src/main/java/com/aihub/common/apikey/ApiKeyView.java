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
 * （计划决策 14）。{@code UNUSABLE} 与「查不到」的哨兵仍然带 {@code null}：**匿名桶没有数值主键，
 * 那是正常路径而不是错误**。
 */
public record ApiKeyView(String keyId, long tenantId, String tenantName, String status, Instant expireAt,
                         Long apiKeyId) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    /**
     * 「不可用视图」的**唯一**哨兵：{@code usable()} 恒为 false，需要「一个不可用的视图」的调用方
     * （{@code aihub-common} 侧，以及将来任何需要此表达的调用方）都用这一个实例。
     *
     * <p><b>（D4 起）gateway 的鉴权过滤器不再用它。</b>那里改用
     * {@code AdminResolution.unavailable()} 表达「我们**判不了**这把 key 是否有效」—— 因为
     * 「判不了」根本**不是一种视图**（它不是关于这把 key 的结论，而是关于我们自己能否得出结论），
     * 用视图类型去表达它会让「权威地判定不可用」（对客 401）与「判不了」（对客 503）在类型上无法区分。
     *
     * <p>它存在的意义仍然是让「不可用」有单一表达：{@code usable()} 为 false，于是所有调用方只需判断
     * {@code usable()} 一次。之前 gateway 侧有两个各自 {@code new} 出来的同形实例（解析器的 MISS 与
     * 过滤器的 UNRESOLVED）；今天它们恰好都是 {@code status="MISSING"} 才等价，一旦将来
     * {@code usable()} 的判据从 {@code status} 改成别的（例如认实例身份），两份哨兵就会分裂成
     * 两种行为。放在这里（{@code aihub-common}，零依赖、JDK-only）后，两侧共用同一份 notion。
     */
    public static final ApiKeyView UNUSABLE = new ApiKeyView("", 0L, "", "MISSING", null, null);

    public boolean usable() {
        return STATUS_ACTIVE.equals(status) && (expireAt == null || expireAt.isAfter(Instant.now()));
    }
}
