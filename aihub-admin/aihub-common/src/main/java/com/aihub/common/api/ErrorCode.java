package com.aihub.common.api;

/**
 * 全局错误码。枚举名即响应体中的 code 字段，httpStatus 决定 HTTP 状态码。
 */
public enum ErrorCode {

    INVALID_PARAM(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    RATE_LIMITED(429),
    QUOTA_EXCEEDED(429),
    UPSTREAM_ERROR(502),
    /**
     * 平台**配置**故障（D16）：密钥缺失/过短、必需的配置项没配 —— 与凭证故障严格区分。
     *
     * <p>为什么必须是一个**独立**的码：把"我没配密钥"伪装成 401 会让运维去翻口令，去重置一个
     * 根本没坏的账号；而 503-vs-401 那条纪律（本项目已裁决过）正是同一件事的另一个面 ——
     * **不要把平台故障伪装成凭证错误**。M4 里它的唯一使用点是控制台登录接口（{@code /api/**}
     * 的过滤器在密钥不可用时仍然一律 401，那是 fail-closed，不是"告诉运维原因"的通道）。
     */
    CONFIGURATION_ERROR(500),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return name();
    }
}
