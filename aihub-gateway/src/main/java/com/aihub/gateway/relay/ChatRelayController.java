package com.aihub.gateway.relay;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * M0 的最小流式转发：把请求体原样交给上游，把上游的 SSE 帧原样交回客户端。
 * 鉴权、限流、配额、路由与计量在 M1–M3 加在它前面。
 */
@RestController
public class ChatRelayController {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

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

    /**
     * 本端点只有 SSE 一种表示形式，但客户端（如 OpenAI Node SDK）默认会发
     * {@code Accept: application/json}。此时 Spring 的内容协商找不到匹配的
     * {@code ServerSentEvent} 写出器，会在进入 handler 之前直接返回 406。
     * 因此把该端点的 Accept 归一为它唯一能产出的 {@code text/event-stream}。
     */
    @Component
    static class SseAcceptNormalizingFilter implements WebFilter {

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            if (!CHAT_COMPLETIONS_PATH.equals(exchange.getRequest().getPath().value())) {
                return chain.filter(exchange);
            }
            List<MediaType> accepted = exchange.getRequest().getHeaders().getAccept();
            boolean alreadySse = accepted.contains(MediaType.TEXT_EVENT_STREAM)
                    || accepted.contains(MediaType.ALL);
            if (alreadySse) {
                return chain.filter(exchange);
            }
            ServerWebExchange normalized = exchange.mutate()
                    .request(request -> request.headers(headers ->
                            headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM))))
                    .build();
            return chain.filter(normalized);
        }
    }
}
