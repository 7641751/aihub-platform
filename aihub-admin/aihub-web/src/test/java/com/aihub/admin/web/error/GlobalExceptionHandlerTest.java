package com.aihub.admin.web.error;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        GlobalExceptionHandlerTest.ProbeController.class,
        GlobalExceptionHandlerTest.ValidatedProbeController.class})
// Slice 测试的 TypeExcludeFilter 不会把「测试类内部的嵌套类」当作候选组件，
// 因此这两个探针控制器必须显式 @Import 才会被注册（顶层测试类则会被自动扫描到）。
@Import({GlobalExceptionHandler.class,
        GlobalExceptionHandlerTest.ProbeController.class,
        GlobalExceptionHandlerTest.ValidatedProbeController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bizExceptionMapsToItsHttpStatusAndCode() throws Exception {
        mockMvc.perform(get("/__probe/biz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("tenant not found"))
                // 全局约束把失败响应体固定为 {code,message,data}，data 必须存在且为 null；
                // doesNotExist() 在「字段缺失」和「字段为 null」两种情况下都会通过，钉不住这个形状。
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void unexpectedExceptionBecomesInternalErrorWithoutLeakingMessage() throws Exception {
        mockMvc.perform(get("/__probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("internal error"));
    }

    @Test
    void requestBodyValidationBecomesInvalidParam() throws Exception {
        mockMvc.perform(post("/__probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"n\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void requestParamValidationBecomesInvalidParam() throws Exception {
        mockMvc.perform(get("/__probe/param").param("n", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void unknownPathStaysNotFoundInsteadOfInternalError() throws Exception {
        mockMvc.perform(get("/__probe/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // 方法不支持（405）与媒体类型不支持（415）在 Spring 6.2 里只是「实现了 ErrorResponse 的
    // ServletException」，并不继承 ErrorResponseException；兜底分支必须保留它们自带的状态码，
    // 而不是统一变成 500 INTERNAL_ERROR。
    @Test
    void wrongHttpMethodKeepsItsOwnStatus() throws Exception {
        mockMvc.perform(post("/__probe/biz"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void unsupportedMediaTypeKeepsItsOwnStatus() throws Exception {
        mockMvc.perform(post("/__probe/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("n=1"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/__probe/biz")
        String biz() {
            throw new BizException(ErrorCode.NOT_FOUND, "tenant not found");
        }

        @GetMapping("/__probe/boom")
        String boom() {
            throw new IllegalStateException("database credentials leaked here");
        }

        // 显式声明 consumes JSON：这样 text/plain 请求会在处理器映射阶段就抛出 415。
        @PostMapping(value = "/__probe/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
        String validate(@RequestBody @Valid Payload payload) {
            return "ok:" + payload.n();
        }
    }

    record Payload(@Min(1) int n) {
    }

    @RestController
    @Validated
    static class ValidatedProbeController {

        @GetMapping("/__probe/param")
        String param(@RequestParam("n") @Min(1) int n) {
            return "ok:" + n;
        }
    }
}
