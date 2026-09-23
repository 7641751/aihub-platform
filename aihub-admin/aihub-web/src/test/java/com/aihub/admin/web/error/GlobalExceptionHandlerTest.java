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
                .andExpect(jsonPath("$.data").doesNotExist());
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
                .andExpect(status().isNotFound());
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

        @PostMapping("/__probe/validate")
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
