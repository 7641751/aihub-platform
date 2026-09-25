package com.aihub.gateway.relay;

import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.error.GatewayErrors;
import com.aihub.gateway.meter.MeteringProperties;
import com.aihub.gateway.meter.MeteringPublisher;
import com.aihub.gateway.meter.RelayMetering;
import com.aihub.gateway.meter.RelayRequestBody;
import com.aihub.gateway.trace.RequestIdFilter;
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
import org.springframework.web.server.ServerWebExchange;
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
 * <p>M2 起它同时是**计量观察者**：在响应体流上挂一个只读 tap（{@code RelayMetering}），
 * 请求收尾时把计量事件交给 {@code MeteringPublisher}。tap 只复制字节，不改写任何被转发的字节。
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
     * <p>{@code x-request-id} 曾经也在这张白名单里。M2 起网关自己生成 {@code x-request-id}
     * （见 {@code RequestIdFilter}）并把它作为计量幂等键，因此**不再透传上游的同名头**：
     * 同一响应里同名的两个值无法共存，而幂等键必须是网关自产的那一个。
     * <p>限流/退避头是**显式列举的精确名**，不是前缀匹配（{@code x-ratelimit-*} 在这里只是
     * 命名上的族，匹配机制仍是 {@code Set#contains}）：白名单要的是「只回传已知且安全的头」，
     * 前缀匹配会让上游随手新增的 {@code x-ratelimit-<任意>} 自动穿过网关，等于把白名单
     * 变成开放集合。新增头必须像下面这样显式登记。
     * <p>加头**不改变**状态码、{@code Content-Type} 与响应体字节：它们只是额外的键值对，
     * 不参与「字节级透传」那条路径。
     */
    private static final Set<String> RELAYED_HEADERS = Set.of(
            "content-type",
            // 上游限流/退避信号：客户端唯一的依据，吃掉它等于让 SDK 瞎猜（M3 的治理也依赖它）。
            "retry-after",
            "x-ratelimit-limit-requests",
            "x-ratelimit-limit-tokens",
            "x-ratelimit-remaining-requests",
            "x-ratelimit-remaining-tokens",
            "x-ratelimit-reset-requests",
            "x-ratelimit-reset-tokens");

    private static final Logger log = LoggerFactory.getLogger(ChatRelayController.class);

    private final WebClient upstreamWebClient;
    private final MeteringPublisher meteringPublisher;
    private final MeteringProperties meteringProperties;

    public ChatRelayController(WebClient upstreamWebClient, MeteringPublisher meteringPublisher,
                               MeteringProperties meteringProperties) {
        this.upstreamWebClient = upstreamWebClient;
        this.meteringPublisher = meteringPublisher;
        this.meteringProperties = meteringProperties;
    }

    /**
     * OpenAI 兼容入口：把客户端请求体原样 POST 给上游，再把上游响应交给 {@link #relay} 回写。
     * <p>{@code Accept} 同时声明 SSE 与 JSON：具体返回哪种由请求体里的 {@code stream} 字段决定，
     * 本方法不做判断（见类注释）。只有「连不上上游」才走 {@code onErrorResume} 返回 502；
     * 上游正常返回的错误状态码（401/429…）由 {@link #relay} 原样透传，不进这里。
     * <p>M2 起请求体先过 {@link RelayRequestBody}：非流式逐字节不变，流式会补上
     * {@code stream_options.include_usage}（否则上游最后一帧不带 usage）。
     *
     * @param body     客户端原始请求体（JSON 字符串）
     * @param exchange 提供响应对象、{@code ApiKeyView}（租户）与 {@code x-request-id}
     */
    @PostMapping(path = CHAT_COMPLETIONS_PATH)
    public Mono<Void> chatCompletions(@RequestBody String body, ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        RelayRequestBody.Prepared prepared = RelayRequestBody.prepare(body);
        // 必须在请求入口（响应提交之前）取 request_id：RequestIdFilter.ensure 会写响应头，
        // 而响应一旦提交，getHeaders() 变成只读，迟到的 ensure 会直接抛异常。
        String requestId = RequestIdFilter.ensure(exchange);
        RelayMetering metering = RelayMetering.start(exchange, requestId, prepared.model(),
                prepared.streaming(), meteringProperties.maxCaptureBytes());

        return upstreamWebClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                // 同时接受 SSE 与 JSON：上游返回哪种都能透传，无需在网关侧分流。
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .bodyValue(prepared.bodyToForward())
                // 拿到上游响应后立即交给 relay 回写客户端。
                .exchangeToMono(upstream -> relay(upstream, response, metering))
                .onErrorResume(WebClientRequestException.class, ex -> {
                    if (response.isCommitted()) {
                        // 响应已提交说明是**流中途**断的：状态码改不了，只能收尾 + 记 ERROR。
                        metering.onUpstreamFailure(MeteringEvent.ERROR_UPSTREAM_STREAM);
                        log.warn("上游流中途失败（响应已提交，无法改状态码）: {} : {}",
                                ex.getClass().getName(), ex.getMessage());
                        return response.setComplete();
                    }
                    // 上游内网地址/异常细节只写日志，不回给客户端（避免泄漏如 "Connection refused: /10.0.0.5:443"）。
                    metering.onUpstreamFailure(MeteringEvent.ERROR_UPSTREAM_UNREACHABLE);
                    log.warn("upstream request failed, returning 502 upstream_unreachable ({}) : {}",
                            ex.getClass().getName(), ex.getMessage());
                    return GatewayErrors.write(response, HttpStatus.BAD_GATEWAY,
                            "api_error", "upstream_unreachable", "Upstream service is unreachable");
                })
                // 响应已提交之后的**其它**异常：上游断流的更具体信号上面已经拦掉，因此这里
                // 一律按「客户端断连」计（设计文档 §8.1 ⑤：不计入错误告警，按已收内容估算）。
                // 未提交的异常原样交回框架：CONVENTIONS 明确 /v1 没有全局 500 处理器，形状由框架决定。
                .onErrorResume(ex -> {
                    if (!response.isCommitted()) {
                        metering.onUnexpectedError();
                        return Mono.error(ex);
                    }
                    metering.onClientDisconnected();
                    log.warn("回写客户端失败（响应已提交，按客户端断连计量）: {} : {}",
                            ex.getClass().getName(), ex.getMessage());
                    return response.setComplete();
                })
                // 收尾即计量：CANCEL 表示订阅被取消（客户端断连的另一种表现）；此处**不阻塞**。
                .doFinally(signal -> meteringPublisher.publish(metering.toEvent(signal)));
    }

    /**
     * 把上游响应原样回写给客户端：状态码 → 白名单头 → 响应体字节，三者都不做语义加工。
     * <p>刻意不用 {@code bodyToMono(String)} 之类会「攒完整体」的读法，而是以 {@link DataBuffer}
     * 逐块透传，才能同时覆盖 SSE 流式与 JSON 非流式两种上游返回。
     * <p>{@code doOnNext(metering::onChunk)} 只是**只读观察**：{@code UsageCapture} 用
     * {@code DataBuffer.asByteBuffer()} 的只读视图复制字节，不推进原 buffer 的读写位置，
     * 因此被写回客户端的字节与上游发出的完全一致。
     *
     * @param upstream 上游的原始响应（状态码、头、体都从这里取）
     * @param response 要回写给客户端的响应，就地写入
     * @param metering 本次请求的计量累加器（只读观察者）
     */
    private Mono<Void> relay(ClientResponse upstream, ServerHttpResponse response, RelayMetering metering) {
        // 上游状态码原样透传：401/429/502… 保持原样，不被折叠成通用 500。
        response.setStatusCode(upstream.statusCode());
        metering.onUpstreamStatus(upstream.statusCode());

        // 只回传白名单里的头，且原样拷贝不解析（理由见 RELAYED_HEADERS 注释）。
        HttpHeaders upstreamHeaders = upstream.headers().asHttpHeaders();
        upstreamHeaders.forEach((name, values) -> {
            if (RELAYED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                response.getHeaders().put(name, values);
            }
        });

        Flux<DataBuffer> body = upstream.bodyToFlux(DataBuffer.class)
                .doOnNext(metering::onChunk);
        // writeAndFlushWith 逐块 flush：SSE 因此是真流式，而不是攒完再发。
        return response.writeAndFlushWith(body.map(Mono::just));
    }
}
