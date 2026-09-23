package com.aihub.gateway.relay;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.NotAcceptableStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 把 {@code /v1/chat/completions} 的 {@code Accept} 归一为端点唯一能产出的
 * {@code text/event-stream}：客户端（如 OpenAI Node SDK）默认发
 * {@code Accept: application/json}，此时 Spring 的内容协商找不到匹配的
 * {@code ServerSentEvent} 写出器，会在进入 handler 之前直接返回 406。
 * <p>归一化只针对「没有对 SSE 表态」的 Accept；显式拒绝（{@code ;q=0}）必须原样拒绝，
 * 畸形 Accept 也不在过滤器里解析，交给 Spring 的协商统一处理。
 * 这是跨切面的 HTTP 关注点，因此独立于 {@link ChatRelayController} 存在，
 * 两者共用 {@link ChatRelayController#CHAT_COMPLETIONS_PATH} 以免守卫与映射漂移。
 */
@Component
public class SseAcceptNormalizingFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!ChatRelayController.CHAT_COMPLETIONS_PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }

        List<MediaType> accepted;
        try {
            accepted = exchange.getRequest().getHeaders().getAccept();
        }
        catch (InvalidMediaTypeException ex) {
            // 畸形 Accept：不在过滤器里决定语义，原样交给 Spring 的协商
            // （HeaderContentTypeResolver 会把它归一为 406，而不是在过滤器里变成 500）。
            return chain.filter(exchange);
        }

        boolean sseAcceptable = accepted.stream().anyMatch(SseAcceptNormalizingFilter::acceptsSse);
        if (sseAcceptable) {
            // 已经接受 SSE（含 */*）或是没有 Accept：保持客户端原样，交给 Spring 协商。
            return chain.filter(exchange);
        }
        if (accepted.stream().anyMatch(SseAcceptNormalizingFilter::refusesSse)) {
            // q=0 是显式拒绝。Spring 的协商只做兼容性匹配、不看 q 值，
            // 会把 text/event-stream;q=0 当成可接受并照常返回 SSE，因此这里必须拒绝。
            return Mono.error(new NotAcceptableStatusException(List.of(MediaType.TEXT_EVENT_STREAM)));
        }

        ServerWebExchange normalized = exchange.mutate()
                .request(request -> request.headers(headers ->
                        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM))))
                .build();
        return chain.filter(normalized);
    }

    /** 兼容性匹配（忽略 quality 值），因此通配的 Accept 也算接受 SSE。 */
    private static boolean acceptsSse(MediaType mediaType) {
        return mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM) && mediaType.getQualityValue() > 0;
    }

    /** 显式拒绝：与 SSE 兼容但 quality 值为 0。 */
    private static boolean refusesSse(MediaType mediaType) {
        return mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM) && mediaType.getQualityValue() == 0;
    }
}
