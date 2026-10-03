package com.aihub.service.audit;

/**
 * {@code audit_log.action} 的取值集合（决策 D1/D8）。
 *
 * <p>取值刻意是**扁平常量**而不是枚举：这张表是审计承载物，读它的人（运维、合规、Task 15 的对账、
 * SQL 客户端）拿到的就是裸字符串；一旦用枚举，落库形态就会被 {@code @EnumValue} / ORM 的默认
 * {@code name()} 策略在无声中改变，而审计的历史行不能被一次重构改写含义。
 *
 * <p>命名用 {@code 对象_动作}（{@code CHANNEL_ROTATE_KEY}），与 {@code target_type} 的取值一一对应，
 * 便于「按对象看变更史」的查询（V2 里有 {@code idx_audit_log_target (target_type, target_id)}）。
 *
 * <p>{@code action} 列是 {@code VARCHAR(32)}：新增常量时必须先确认长度放得下 ——
 * 超长在严格模式下是硬失败（好事），在非严格模式下会被**静默截断**成另一个动作（坏事）。
 */
public final class AuditAction {

    // ---- 租户 ----
    public static final String TENANT_CREATE = "TENANT_CREATE";
    public static final String TENANT_UPDATE = "TENANT_UPDATE";

    // ---- API Key（D11：销毁是显式 DEL，因此 DELETE 与 DISABLE 是两个动作） ----
    public static final String API_KEY_CREATE = "API_KEY_CREATE";
    public static final String API_KEY_DISABLE = "API_KEY_DISABLE";
    public static final String API_KEY_ENABLE = "API_KEY_ENABLE";
    public static final String API_KEY_DELETE = "API_KEY_DELETE";

    // ---- 渠道（ROTATE_KEY 是换渠道密钥：审计只记「换了」，绝不记密钥本身） ----
    public static final String CHANNEL_CREATE = "CHANNEL_CREATE";
    public static final String CHANNEL_UPDATE = "CHANNEL_UPDATE";
    public static final String CHANNEL_DELETE = "CHANNEL_DELETE";
    public static final String CHANNEL_ROTATE_KEY = "CHANNEL_ROTATE_KEY";

    // ---- 模型路由 ----
    public static final String ROUTE_CREATE = "ROUTE_CREATE";
    public static final String ROUTE_UPDATE = "ROUTE_UPDATE";
    public static final String ROUTE_DELETE = "ROUTE_DELETE";

    // ---- 限流策略 ----
    public static final String RATE_LIMIT_CREATE = "RATE_LIMIT_CREATE";
    public static final String RATE_LIMIT_UPDATE = "RATE_LIMIT_UPDATE";
    public static final String RATE_LIMIT_DEACTIVATE = "RATE_LIMIT_DEACTIVATE";

    // ---- 配额 ----
    public static final String QUOTA_UPDATE = "QUOTA_UPDATE";

    // ---- 控制台登录（Task 7 只提供常量；生产方是 Task 6 之后的一个专门提交，见附录 E.3 第 11 条） ----
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";

    // ---- 对账 ----
    public static final String RECONCILE_REPORT = "RECONCILE_REPORT";

    // ---- 知识库文档（D13）：上传由**用户**写（actor_type=USER）；
    //      READY/FAILED 是流水线终态、由**系统**写（actor_type=SYSTEM），在各自的 Task 里再加。
    //      审计**绝不记**原件内容、chunk 文本、向量，也绝不记任何密钥。
    public static final String KB_DOCUMENT_UPLOAD = "KB_DOCUMENT_UPLOAD";

    private AuditAction() {
    }
}
