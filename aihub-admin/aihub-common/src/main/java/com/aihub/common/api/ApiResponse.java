package com.aihub.common.api;

/**
 * 所有 HTTP 接口的统一响应体。字段顺序固定为 code / message / data。
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "success", data);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null);
    }
}
