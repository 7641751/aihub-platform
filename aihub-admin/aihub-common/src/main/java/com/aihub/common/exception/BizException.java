package com.aihub.common.exception;

import com.aihub.common.api.ErrorCode;

/**
 * 业务异常：携带错误码，由 GlobalExceptionHandler 统一转换为响应体。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
