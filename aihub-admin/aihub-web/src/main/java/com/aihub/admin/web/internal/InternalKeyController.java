package com.aihub.admin.web.internal;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.service.apikey.ApiKeyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 网关回源用的内部接口。调用方必须带合法内部签名（见 InternalAuthFilter）。 */
@RestController
@RequestMapping("/internal/api-keys")
public class InternalKeyController {

    private final ApiKeyService apiKeyService;

    public InternalKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    public record ResolveRequest(String keyHash) {
    }

    /** 直接返回共享的 {@link ApiKeyView}，避免再定义一层只有 admin 才认识的 DTO。 */
    @PostMapping("/resolve")
    public ResponseEntity<ApiResponse<ApiKeyView>> resolve(@RequestBody ResolveRequest request) {
        if (request == null || request.keyHash() == null || request.keyHash().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail(ErrorCode.INVALID_PARAM, "keyHash 不能为空"));
        }
        return apiKeyService.resolve(request.keyHash())
                .map(view -> ResponseEntity.ok(ApiResponse.ok(view)))
                .orElseGet(() -> ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
                        .body(ApiResponse.fail(ErrorCode.NOT_FOUND, "key not found")));
    }
}
