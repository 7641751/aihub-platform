package com.aihub.admin.web.error;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
     * 兜底分支：本类的最后一个 @ExceptionHandler，优先级高于 Spring 的
     * DefaultHandlerExceptionResolver，因此所有没被上面更具体分支接住的异常都在这里定型。
     * <p>
     * Spring 自己抛出的异常（404、405、415 等）自带正确状态码，必须原样保留，
     * 否则会被统一改写成 500。但它们并不都继承 ErrorResponseException：
     * NoResourceFoundException 直接继承 ServletException，HttpRequestMethodNotSupportedException、
     * HttpMediaTypeNotSupportedException 也是 ServletException 的子类，它们只是
     * <em>实现</em>了 ErrorResponse。所以这里不做异常类型的枚举，而是用 instanceof 做类型匹配：
     * 任何实现了 ErrorResponse 的异常都能保留自己的状态码，同时也不需要 (ErrorResponse) 强转。
     * <p>
     * 不携带状态码的异常才是真正的未知故障：记录服务端日志，只返回固定文案，避免泄漏内部信息。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            // 5xx 才是真实的服务端故障，必须留下服务端日志；4xx 是调用方的问题，不产生 ERROR 噪音。
            if (status.is5xxServerError()) {
                log.error("unhandled server error", ex);
            }
            return ResponseEntity.status(status)
                    .body(ApiResponse.fail(errorCodeFor(status), messageFor(errorResponse)));
        }
        log.error("unhandled exception", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "internal error"));
    }

    /**
     * ErrorCode 有 9 个常量（INVALID_PARAM、UNAUTHORIZED、FORBIDDEN、NOT_FOUND、RATE_LIMITED、
     * QUOTA_EXCEEDED、UPSTREAM_ERROR、CONFIGURATION_ERROR、INTERNAL_ERROR），
     * 但**没有** METHOD_NOT_ALLOWED / UNSUPPORTED_MEDIA_TYPE，
     * 405、415 只能归到 INVALID_PARAM；其余状态码取语义最接近的专用常量，
     * 无法对应的（如 503）一律 INTERNAL_ERROR。
     * <p>
     * CONFIGURATION_ERROR（500）刻意不在这张表里：它不由 HTTP 状态码反推，只经 BizException 明确指定
     * （见 {@link #handleBizException}），否则任何 500 都会被误标成"平台配置故障"。
     */
    private ErrorCode errorCodeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400, 405, 415 -> ErrorCode.INVALID_PARAM;
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }

    /** message 必须非空：优先 detail，其次 title，最后回退到状态码文本。 */
    private String messageFor(ErrorResponse errorResponse) {
        ProblemDetail body = errorResponse.getBody();
        if (body != null) {
            String detail = body.getDetail();
            if (detail != null && !detail.isBlank()) {
                return detail;
            }
            String title = body.getTitle();
            if (title != null && !title.isBlank()) {
                return title;
            }
        }
        return errorResponse.getStatusCode().toString();
    }

    private String defaultMessage(FieldError fieldError) {
        String message = fieldError.getDefaultMessage();
        return message == null ? "invalid" : message;
    }
}
