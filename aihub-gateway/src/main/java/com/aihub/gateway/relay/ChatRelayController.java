package com.aihub.gateway.relay;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * M0 的最小流式转发：把请求体原样交给上游，把上游的 SSE 帧原样交回客户端。
 * 鉴权、限流、配额、路由与计量在 M1–M3 加在它前面。
 * <p>本端点只有 SSE 一种表示形式，{@code Accept} 的归一化由
 * {@link SseAcceptNormalizingFilter} 负责，两者共用 {@link #CHAT_COMPLETIONS_PATH}。
 */
@RestController
public class ChatRelayController {

    public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final WebClient upstreamWebClient;

    public ChatRelayController(WebClient upstreamWebClient) {
        this.upstreamWebClient = upstreamWebClient;
    }

    @PostMapping(path = CHAT_COMPLETIONS_PATH)
    public Flux<ServerSentEvent<String>> chatCompletions(@RequestBody String body) {
        return upstreamWebClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                });
    }
}
