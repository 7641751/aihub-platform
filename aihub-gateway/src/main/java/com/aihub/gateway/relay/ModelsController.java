package com.aihub.gateway.relay;

import com.aihub.gateway.upstream.UpstreamProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的模型列表：单渠道场景下只回报一个模型（{@code aihub.upstream.default-model}）；
 * 多渠道与渠道级模型列表属于 M3。
 *
 * <p>与 {@code ChatRelayController} 不同，这里**不回源**：模型名是网关自己的配置，回源既无必要，
 * 又会把「上游挂掉」传导成本端点的故障。鉴权由 {@code ApiKeyAuthFilter} 统一加在 {@code /v1/**} 前面，
 * 本类不重复实现（限流、计量、路由同理，分别属于 M2/M3）。
 */
@RestController
public class ModelsController {

    private final UpstreamProperties properties;

    public ModelsController(UpstreamProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/v1/models")
    public Mono<Map<String, Object>> listModels() {
        Map<String, Object> model = Map.of(
                "id", properties.defaultModel(),
                "object", "model",
                "owned_by", "aihub");
        return Mono.just(Map.of(
                "object", "list",
                "data", List.of(model)));
    }
}
