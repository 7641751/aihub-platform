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
     * 「不可用视图」的**唯一**哨兵：{@code status="MISSING"}，故 {@code usable()} 恒为 false。
     * 它留在共享类型（{@code aihub-common}，零依赖、JDK-only）上。
     *
     * <p>它历史上存在，是为了让 gateway 侧两处不再各自 {@code new} 一个同形哨兵（解析器的 MISS 与
     * 过滤器的 UNRESOLVED）：当时两份哨兵靠 {@code status="MISSING"} 恰好等价，一旦 {@code usable()}
     * 的判据从 {@code status} 改成别的（例如认实例身份），就会分裂成两种行为；统一到共享类型之后，
     * 「不可用」只有一份表达，调用方只需判断 {@code usable()} 一次。
     *
     * <p><b>（D4 起）gateway 侧不再引用它。</b>gateway 那侧要表达的是「我们**判不了**这把 key 是否有效」，
     * 而那根本**不是一种视图** —— 它不是关于这把 key 的结论，而是关于我们自己能否得出结论 —— 所以那里
     * 改用 {@code AdminResolution.unavailable()}（对客 {@code 503 service_unavailable}），从而与
     * 「权威地判定不可用」（对客 401）在类型上可区分。真正需要「一个不可用的视图」的
     * {@code aihub-common} 侧调用方，或将来任何需要此表达的调用方，仍然**可以**用这一个实例。
     *
     * <p><b>现状登记</b>：本常量目前**没有生产调用方**（{@code grep -rn UNUSABLE} 只剩它自己的声明与
     * {@code aihub-common} 的测试），因此是清理候选 —— 要么后续删除它，要么给它一个真实调用方；
     * 两者都超出本次改动的范围，故本常量**保留、不改值、不改修饰符**。
     */
    public static final ApiKeyView UNUSABLE = new ApiKeyView("", 0L, "", "MISSING", null, null);

    public boolean usable() {
        return STATUS_ACTIVE.equals(status) && (expireAt == null || expireAt.isAfter(Instant.now()));
    }
}
