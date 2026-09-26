package com.aihub.gateway.relay;

import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * OpenAI 兼容的模型列表：**快照里出现过的模型名 ∪ 遗留默认模型**（去重排序）。
 *
 * <p>M1/M2 只回报一个配置的默认模型（单渠道）；M3 起真正的模型集合来自配置快照的
 * {@code model_route}（「这个平台提供哪些模型」= 路由表里出现过的模型名）。
 * **仍然并入遗留默认模型**：迁移期（或 admin 不可达走了兜底）时不能突然把这个端点变成空列表
 * —— 那会让所有客户端以为平台没有任何模型。
 *
 * <p>与 {@code ChatRelayController} 一样，这里读的是 {@link ConfigClient#current()} 手上的**缓存快照**
 * （最差回落到兜底快照）；{@code current()} **永不抛异常**，因此上游或控制面抖动不会把这个端点
 * 变成故障（它与上游其实完全无关）。但**冷缓存（或缓存过期）时它会同步回源一次**——最长 5 s，
 * 见 {@link ConfigClient} 登记的阻塞面：说这里「一次都不回源」是不成立的。
 * 鉴权由 {@code ApiKeyAuthFilter} 统一加在 {@code /v1/**} 前面，本类不重复实现
 * （限流、计量、路由同理，分别属于 M2/M3）。
 */
@RestController
public class ModelsController {

    private final UpstreamProperties properties;
    private final ConfigClient configClient;

    public ModelsController(UpstreamProperties properties, ConfigClient configClient) {
        this.properties = properties;
        this.configClient = configClient;
    }

    /**
     * 没有配置默认模型、快照里也没有任何模型时返回**空列表**（OpenAI 协议允许），
     * 既不 NPE 也不编造 id。
     *
     * <p>用 {@link TreeSet} 而不是「两个列表拼接」：同一个模型既在路由表里、又是遗留默认值是
     * 常见状态（遗留兜底快照就是这么合成的），拼接会让它在 {@code data} 里出现两次，而模型列表
     * 是**集合**，客户端缓存与「这个平台有哪些模型」的语义都要求去重；排序则让响应稳定可比。
     */
    @GetMapping("/v1/models")
    public Mono<Map<String, Object>> listModels() {
        Set<String> models = new TreeSet<>(configClient.current().modelNames());
        String defaultModel = properties.defaultModel();
        if (StringUtils.hasText(defaultModel)) {
            models.add(defaultModel);
        }
        List<Map<String, Object>> data = models.stream()
                .map(id -> Map.<String, Object>of("id", id, "object", "model", "owned_by", "aihub"))
                .toList();
        return Mono.just(Map.of("object", "list", "data", data));
    }
}
