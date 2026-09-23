package com.aihub.admin.web.error;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException ex) {
        ErrorCode errorCode = ex.errorCode();
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.fail(errorCode, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + defaultMessage(fieldError))
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_PARAM, message));
    }

    /**
     * 方法参数上的约束（如 @RequestParam @Min）由 @Validated 触发，
     * 抛的是 ConstraintViolationException，与 @RequestBody 校验走的不是同一条路径。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_PARAM, message));
    }

    /**
     * Spring 自己抛出的异常（404、405、415 等）已经带了正确状态码，必须保留，
     * 否则会被下面的兜底分支统一变成 500。
     * 注意：注解值必须是 Throwable 的子类，所以这里用 ErrorResponseException
     * （ResponseStatusException 是它的子类）；但 NoResourceFoundException 只实现了
     * ErrorResponse、直接继承 ServletException，并不在 ErrorResponseException 之下，
     * 必须显式列出，否则未知路径会被兜底分支变成 500。
     */
    @ExceptionHandler({ErrorResponseException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleErrorResponse(Exception ex) {
        ErrorResponse error = (ErrorResponse) ex;
        ErrorCode errorCode = error.getStatusCode().value() == 404 ? ErrorCode.NOT_FOUND : ErrorCode.INVALID_PARAM;
        String detail = error.getBody().getDetail();
        String message = detail == null ? "request rejected" : detail;
        return ResponseEntity.status(error.getStatusCode()).body(ApiResponse.fail(errorCode, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "internal error"));
    }

    private String defaultMessage(FieldError fieldError) {
        String message = fieldError.getDefaultMessage();
        return message == null ? "invalid" : message;
    }
}
