package com.aihub.gateway.relay;

import com.aihub.gateway.error.GatewayErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * 需要从上游原样回传给客户端的头。
     * <p>{@code content-type} 也走这条白名单，**不做解析**：{@code HttpHeaders#contentType()} 用
     * {@code MediaType.parseMediaType} 解析上游值，遇到畸形值（如 {@code not a media type}）会抛
     * {@code InvalidMediaTypeException}，把上游状态码/响应体一起吞成 500。原样拷贝没有这个问题；
     * 上游没发 {@code Content-Type} 时我们也不补默认值 —— 透传的字面意思就是「上游发什么就回什么」。
     * <p>其余头（如 Content-Length、Transfer-Encoding）由本服务自行决定。
     */
    private static final Set<String> RELAYED_HEADERS = Set.of("x-request-id", "content-type");

    private static final Logger log = LoggerFactory.getLogger(ChatRelayController.class);

    private final WebClient upstreamWebClient;

    public ChatRelayController(WebClient upstreamWebClient) {
        this.upstreamWebClient = upstreamWebClient;
    }

    /**
     * OpenAI 兼容入口：把客户端请求体原样 POST 给上游，再把上游响应交给 {@link #relay} 回写。
     * <p>{@code Accept} 同时声明 SSE 与 JSON：具体返回哪种由请求体里的 {@code stream} 字段决定，
     * 本方法不做判断（见类注释）。只有「连不上上游」才走 {@code onErrorResume} 返回 502；
     * 上游正常返回的错误状态码（401/429…）由 {@link #relay} 原样透传，不进这里。
     *
     * @param body     客户端原始请求体（JSON 字符串），不做解析直接透传
     * @param response 要回写给客户端的响应，供 {@link #relay} 与错误分支就地写入
     */
    @PostMapping(path = CHAT_COMPLETIONS_PATH)
    public Mono<Void> chatCompletions(@RequestBody String body, ServerHttpResponse response) {
        return upstreamWebClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                // 同时接受 SSE 与 JSON：上游返回哪种都能透传，无需在网关侧分流。
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .bodyValue(body)
                // 拿到上游响应后立即交给 relay 回写客户端。
                .exchangeToMono(upstream -> relay(upstream, response))
                .onErrorResume(WebClientRequestException.class, ex -> {
                    // 上游内网地址/异常细节只写日志，不回给客户端（避免泄漏如 "Connection refused: /10.0.0.5:443"）。
                    log.warn("upstream request failed, returning 502 upstream_unreachable ({}) : {}",
                            ex.getClass().getName(), ex.getMessage());
                    return GatewayErrors.write(response, HttpStatus.BAD_GATEWAY,
                            "api_error", "upstream_unreachable", "Upstream service is unreachable");
                });
    }

    /**
     * 把上游响应原样回写给客户端：状态码 → 白名单头 → 响应体字节，三者都不做语义加工。
     * <p>刻意不用 {@code bodyToMono(String)} 之类会「攒完整体」的读法，而是以 {@link DataBuffer}
     * 逐块透传，才能同时覆盖 SSE 流式与 JSON 非流式两种上游返回。
     *
     * @param upstream 上游的原始响应（状态码、头、体都从这里取）
     * @param response 要回写给客户端的响应，就地写入
     */
    private Mono<Void> relay(ClientResponse upstream, ServerHttpResponse response) {
        // 上游状态码原样透传：401/429/502… 保持原样，不被折叠成通用 500。
        response.setStatusCode(upstream.statusCode());

        // 只回传白名单里的头，且原样拷贝不解析（理由见 RELAYED_HEADERS 注释）。
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
