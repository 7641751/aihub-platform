package com.aihub.gateway.relay;

import com.aihub.gateway.error.GatewayErrors;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;

/**
 * 字节透传代理：把请求体原样交给上游，再把上游的**状态码、响应体字节与 Content-Type** 原样交回客户端。
 * <p>因此流式（SSE）与非流式（JSON）用同一段代码覆盖，客户端 {@code Accept} 不参与决策
 * —— 由请求体里的 {@code stream} 字段决定上游返回什么，我们只负责透传。
 * <p>上游的错误响应（401/429/502…）同样原样透传，不再被折叠成通用 500。
 * <p>鉴权、限流、配额、多渠道路由与计量分别在 M1（{@code ApiKeyAuthFilter}）、M2、M3 加在它前面。
 */
@RestController
public class ChatRelayController {

    public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** 需要从上游回传给客户端的具体头；其余（如 Content-Length、Transfer-Encoding）由本服务自行决定。 */
    private static final Set<String> RELAYED_HEADERS = Set.of("x-request-id");

    private final WebClient upstreamWebClient;

    public ChatRelayController(WebClient upstreamWebClient) {
        this.upstreamWebClient = upstreamWebClient;
    }

    @PostMapping(path = CHAT_COMPLETIONS_PATH)
    public Mono<Void> chatCompletions(@RequestBody String body, ServerHttpResponse response) {
        return upstreamWebClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(upstream -> relay(upstream, response))
                .onErrorResume(WebClientRequestException.class, ex -> GatewayErrors.write(response,
                        HttpStatus.BAD_GATEWAY, "api_error", "upstream_unreachable",
                        "上游服务不可达: " + ex.getMessage()));
    }

    private Mono<Void> relay(ClientResponse upstream, ServerHttpResponse response) {
        response.setStatusCode(upstream.statusCode());
        response.getHeaders().setContentType(
                upstream.headers().contentType().orElse(MediaType.APPLICATION_JSON));

        HttpHeaders upstreamHeaders = upstream.headers().asHttpHeaders();
        upstreamHeaders.forEach((name, values) -> {
            if (RELAYED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                response.getHeaders().put(name, values);
            }
        });

        Flux<DataBuffer> body = upstream.bodyToFlux(DataBuffer.class);
        // writeAndFlushWith 逐块 flush：SSE 因此是真流式，而不是攒完再发。
        return response.writeAndFlushWith(body.map(Mono::just));
    }
}
