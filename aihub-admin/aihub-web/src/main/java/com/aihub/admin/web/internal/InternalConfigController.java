package com.aihub.admin.web.internal;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.service.config.ConfigSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关拉配置快照的内部接口（设计文档 §7.2）。
 *
 * <p>调用方必须带合法内部签名（{@link InternalAuthFilter} 守 {@code /internal/**}，
 * 用 {@code UrlPathHelper.getPathWithinApplication} 取路径 —— 因此被签名的路径是
 * {@code /internal/config/snapshot}，不含 context path）。
 *
 * <p>**直接返回共享的 {@link ConfigSnapshot}**，与 {@code InternalKeyController} 返回
 * {@code ApiKeyView} 同款：网关侧有同一个类型，再定义一层只有 admin 认识的 DTO 只会让两侧漂移
 * （{@code AdminClient.Http.parseSnapshot} 是按字面键名读 JSON 的，多一层 DTO 不会有任何编译期信号）。
 *
 * <p>响应里**只有密文**，没有任何明文渠道密钥。
 */
@RestController
@RequestMapping("/internal/config")
public class InternalConfigController {

    private final ConfigSnapshotService configSnapshotService;

    public InternalConfigController(ConfigSnapshotService configSnapshotService) {
        this.configSnapshotService = configSnapshotService;
    }

    @GetMapping("/snapshot")
    public ApiResponse<ConfigSnapshot> snapshot() {
        return ApiResponse.ok(configSnapshotService.snapshot());
    }
}
