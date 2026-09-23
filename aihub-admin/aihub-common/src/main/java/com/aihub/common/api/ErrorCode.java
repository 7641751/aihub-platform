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
