package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.error.GatewayErrors;
import com.aihub.gateway.meter.MeteringProperties;
import com.aihub.gateway.meter.MeteringPublisher;
import com.aihub.gateway.meter.RelayMetering;
import com.aihub.gateway.meter.RelayRequestBody;
import com.aihub.gateway.route.ChannelCircuitBreaker;
import com.aihub.gateway.route.RouteResolver;
import com.aihub.gateway.route.RouteSelectionException;
import com.aihub.gateway.trace.RequestIdFilter;
import com.aihub.gateway.upstream.UpstreamClientFactory;
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
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 字节透传代理：把请求体原样交给**某一条候选渠道**的上游，再把上游的**状态码、响应体字节与
 * Content-Type** 原样交回客户端。
 * <p>因此流式（SSE）与非流式（JSON）用同一段代码覆盖，客户端 {@code Accept} 不参与决策
 * —— 由请求体里的 {@code stream} 字段决定上游返回什么，我们只负责透传。
 * <p>上游的错误响应（401/429/502…）同样原样透传，不再被折叠成通用 500。
 * <p>M2 起它同时是**计量观察者**：在响应体流上挂一个只读 tap（{@code RelayMetering}），
 * 请求收尾时把计量事件交给 {@code MeteringPublisher}。tap 只复制字节，不改写任何被转发的字节。
 *
 * <p><b>M3 起它是渠道感知的</b>（本里程碑的核心）：
 * <ul>
 *   <li>候选来自 {@link RouteResolver#candidates(String)}（健康渠道按 priority/weight 排序，
 *       熔断渠道作为最后手段排在末尾），经 {@link RelayAttempts#servable} 与
 *       {@link ChannelDescriptor#usable()} 过滤，并被 {@link RelayAttempts#MAX_ATTEMPTS} 截断；</li>
 *   <li>每条渠道用它**自己的** base-url / 超时（{@link UpstreamClientFactory#forChannel}）与
 *       **自己解密出来的密钥**（逐请求注入 {@code Authorization}，绝不挂在共享客户端上）；</li>
 *   <li>上游 5xx / 超时 / 连不上 → 试下一个候选；上游 **429** → 先给该渠道打熔断标记（30s）再试下一个；
 *       上游 **4xx（非 429）** → 不切换，原样透传；</li>
 *   <li><b>切换的前提是「响应尚未提交」</b>。{@link #relay} 开始写字节之后响应即提交，因此
 *       「上游响应头到达但 body 还没写」这段窗口是唯一的合法切换时机 —— 一旦有字节转发出去，
 *       任何后续异常都只会走顶层的收尾分支。这就是「仅在未输出任何 token 时允许切换」的机器实现；</li>
 *   <li>没有候选可用（且快照里没有遗留渠道）→ 404 {@code model_not_found}（OpenAI 形状）；
 *       有候选但连不上 → 502 {@code upstream_unreachable}；所有候选都返回了响应 →
 *       **最后一个候选的响应原样透传**。</li>
 * </ul>
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
     *
     * <p><b>包内可见（不是 private）是刻意的</b>：{@code ChatRelayControllerTest} 用**字面量集合**
     * 钉住它的成员资格（既不许静默放宽成前缀匹配，也不许静默删掉一个名字）。集合本身不可变
     * （{@code Set.of}），因此放宽可见性不带来任何写入口。
     */
    static final Set<String> RELAYED_HEADERS = Set.of(
            "content-type",
            // 上游限流/退避信号：客户端唯一的依据，吃掉它等于让 SDK 瞎猜（M3 的治理也依赖它）。
            "retry-after",
            // M3 新增：Azure 风格的毫秒级退避（M2 刻意留下的 M3 尾巴之一）。
            "retry-after-ms",
            "x-ratelimit-limit-requests",
            "x-ratelimit-limit-tokens",
            "x-ratelimit-remaining-requests",
            "x-ratelimit-remaining-tokens",
            "x-ratelimit-reset-requests",
            "x-ratelimit-reset-tokens",
            // M3 新增：IETF 的 RateLimit 字段族（同样是**精确名**，不是前缀匹配）。
            "ratelimit-limit",
            "ratelimit-remaining",
            "ratelimit-reset");

    private static final Logger log = LoggerFactory.getLogger(ChatRelayController.class);

    private final UpstreamClientFactory clientFactory;
    private final ConfigClient configClient;
    private final RouteResolver routeResolver;
    private final ChannelCircuitBreaker circuitBreaker;
    private final ChannelKeyDecryptor keyDecryptor;
    private final MeteringPublisher meteringPublisher;
    private final MeteringProperties meteringProperties;

    public ChatRelayController(UpstreamClientFactory clientFactory, ConfigClient configClient,
                               RouteResolver routeResolver, ChannelCircuitBreaker circuitBreaker,
                               ChannelKeyDecryptor keyDecryptor, MeteringPublisher meteringPublisher,
                               MeteringProperties meteringProperties) {
        this.clientFactory = clientFactory;
        this.configClient = configClient;
        this.routeResolver = routeResolver;
        this.circuitBreaker = circuitBreaker;
        this.keyDecryptor = keyDecryptor;
        this.meteringPublisher = meteringPublisher;
        this.meteringProperties = meteringProperties;
    }

    /**
     * OpenAI 兼容入口：按候选顺序把客户端请求体原样 POST 给上游，再把上游响应交给 {@link #relay} 回写。
     * <p>{@code Accept} 同时声明 SSE 与 JSON：具体返回哪种由请求体里的 {@code stream} 字段决定，
     * 本方法不做判断（见类注释）。只有「没有候选可服务」才在这里返回 404/502；
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

        List<ChannelDescriptor> candidates;
        try {
            candidates = candidateList(prepared.model());
        } catch (RouteSelectionException e) {
            // 「一个候选都没有」与「候选全都不可用」在这里无法区分（RouteResolver 刻意如此），
            // 两者对客户端都是同一件事：我们不提供这个模型。响应体必须是 /v1 的 OpenAI 形状
            // （客户端 SDK 只认 error.message），绝不是 admin 的 {code,message,data} 信封。
            metering.onUnexpectedError();
            meteringPublisher.publish(metering.toEvent(SignalType.ON_COMPLETE));
            log.debug("模型没有可用渠道: {}", e.model());
            return GatewayErrors.write(response, HttpStatus.NOT_FOUND,
                    "invalid_request_error", "model_not_found", "不提供该模型: " + e.model());
        }

        return attempt(candidates, 0, prepared, response, metering)
                // 上游失败的分类**按原因链**（G14）：响应头已到之后（状态 200）发生的读超时是
                // WebClientResponseException 而不是 WebClientRequestException，按类型判会把它
                // 当成客户端断连（甚至成功）。
                .onErrorResume(RelayAttempts::isUpstreamFailure, ex -> {
                    if (response.isCommitted()) {
                        // 响应已提交说明是**流中途**断的：状态码改不了，只能收尾 + 记 ERROR。
                        // 这里绝不能换渠道 —— 那会把两条上游的响应拼成一段脏数据。
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
     * 候选列表：路由结果经「密钥可服务」过滤 + {@link ChannelDescriptor#usable()} 过滤，并截断到
     * {@link RelayAttempts#MAX_ATTEMPTS}；为空时回落到「遗留单渠道」（**只在快照里真的有遗留渠道、
     * 且它确实可用时才回落**）；再为空 → 抛 {@link RouteSelectionException} 由调用方翻成 404。
     *
     * <p>{@code usable()} 那道门不能省（G15）：{@code RouteResolver} 自己也过滤，但中继不该依赖调用方
     * 已经过滤过 —— 遗留兜底这条路径的候选就是在这里造出来的，而把一条 base-url 为空 / 超时非正的
     * 渠道交给 {@link UpstreamClientFactory} 的后果是「异常穿出请求路径（客户端 500）」或
     * 「每个请求都瞬间超时」。
     */
    private List<ChannelDescriptor> candidateList(String model) {
        List<ChannelDescriptor> candidates = RelayAttempts.servable(routeResolver.candidates(model), keyDecryptor)
                .stream()
                .filter(ChannelDescriptor::usable)
                .limit(RelayAttempts.MAX_ATTEMPTS)
                .toList();
        if (!candidates.isEmpty()) {
            return candidates;
        }
        ConfigSnapshot snapshot = configClient.current();
        ChannelDescriptor legacy = configClient.legacyChannel();
        boolean hasLegacy = snapshot.channels().stream().anyMatch(channel -> LegacyChannel.isLegacy(channel.id()));
        if (hasLegacy && legacy.usable() && keyDecryptor.canServe(legacy)) {
            return List.of(legacy);
        }
        throw new RouteSelectionException(model);
    }

    /**
     * 逐个候选尝试。**每次尝试都是延迟订阅**（{@code Mono.defer}），因此前一个候选只要
     * 「没有被写出去一个字节」，就还可以换下一个。
     *
     * <p>切换的判据是上游**响应头到达之后、响应体写回之前**这个窗口：
     * <ul>
     *   <li>连接失败/超时 → 抛上游失败异常 → 未提交且还有下一个候选就换（否则交给顶层：502 或收尾）；</li>
     *   <li>429 → 打熔断标记（30s）后换下一个；</li>
     *   <li>5xx → 换下一个（**不打熔断**，决策 10）；</li>
     *   <li>2xx / 4xx（非 429）→ **直接回写**，不再有下一次；</li>
     *   <li>没有下一个候选时（含被 {@link RelayAttempts#MAX_ATTEMPTS} 截断后的最后一个）→
     *       **把它自己的响应原样回写**，这样客户端拿到的永远是「真实的最后一个失败」。</li>
     * </ul>
     * 一旦 {@link #relay} 开始写字节，响应就被提交，此后异常只会走收尾分支 ——
     * 这就是「仅在未输出任何 token 时允许切换」的机器实现。
     */
    private Mono<Void> attempt(List<ChannelDescriptor> candidates, int index, RelayRequestBody.Prepared prepared,
                               ServerHttpResponse response, RelayMetering metering) {
        if (index >= candidates.size()) {
            // 走到这里说明所有候选都在**发出上游请求之前**就被跳过了（密钥解不开）。没有真实的
            // 上游响应可以透传，因此给一个明确的 502（与「连不上」共用同一个稳定 body），
            // 而不是 404（模型是存在的）也不是 500（网关没出错）。
            metering.onUpstreamFailure(MeteringEvent.ERROR_UPSTREAM_UNREACHABLE);
            log.warn("所有候选渠道都不可用（密钥解不开或列表为空），返回 502 upstream_unreachable");
            return GatewayErrors.write(response, HttpStatus.BAD_GATEWAY,
                    "api_error", "upstream_unreachable", "Upstream service is unreachable");
        }
        ChannelDescriptor channel = candidates.get(index);
        // 计量里记的是**最后被尝试**的那条：最后一个候选总会把响应回写，因此正常路径下它等于
        // 「实际服务的那条」；全都失败时它等于最后被尝试的那条（故障因此可归因）。
        metering.onChannelSelected(channel);
        Optional<String> upstreamKey = keyDecryptor.upstreamKey(channel);
        if (upstreamKey.isEmpty()) {
            // 解不开密钥：不浪费一次往返，直接跳到下一个候选。绝不在这里抛异常。
            log.warn("渠道 {} 的密钥不可用，跳过该候选", channel.id());
            return attempt(candidates, index + 1, prepared, response, metering);
        }
        WebClient client = clientFactory.forChannel(channel, prepared.streaming());
        String apiKey = upstreamKey.get();
        boolean hasNext = index + 1 < candidates.size();

        return Mono.defer(() -> {
            WebClient.RequestBodySpec spec = client.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 同时接受 SSE 与 JSON：上游返回哪种都能透传，无需在网关侧分流。
                    .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON);
            if (!apiKey.isEmpty()) {
                // 密钥挂在**本次请求**上，不是共享客户端的默认头：一个客户端实例会被多条渠道复用。
                spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }
            return spec.bodyValue(prepared.bodyToForward())
                    .exchangeToMono(upstream -> {
                        int status = upstream.statusCode().value();
                        if (status == 429) {
                            // 429 = 该渠道的速率/配额已满：立刻跨实例熔断 30 秒（决策 10）。
                            // 即便这是最后一个候选、马上就要把 429 透传给客户端，标记也必须打 ——
                            // 它影响的正是**后续**请求的路由。
                            circuitBreaker.markOpen(channel.id());
                        }
                        if (hasNext && RelayAttempts.shouldFailoverBeforeCommit(status)) {
                            log.warn("渠道 {}（{}）返回 {}，尝试下一个候选渠道（第 {} 个候选失败）",
                                    channel.id(), channel.name(), status, index + 1);
                            // releaseBody 防止连接泄漏，然后再切下一个。
                            return upstream.releaseBody().then(
                                    Mono.defer(() -> attempt(candidates, index + 1, prepared, response, metering)));
                        }
                        return relay(upstream, response, metering);
                    });
        }).onErrorResume(RelayAttempts::isUpstreamFailure, ex -> {
            if (hasNext && !response.isCommitted()) {
                // 「响应尚未提交」是切换的唯一前提：连接失败/首字节之前的超时都发生在这个窗口里。
                log.warn("渠道 {}（{}）的上游失败（{}），尝试下一个候选渠道",
                        channel.id(), channel.name(), ex.getClass().getName());
                return Mono.defer(() -> attempt(candidates, index + 1, prepared, response, metering));
            }
            // 没有下一个候选，或响应已经提交：交给顶层收尾（502 / 结束这段流）。
            return Mono.error(ex);
        });
    }

    /**
     * 把上游响应原样回写给客户端：状态码 → 白名单头 → 响应体字节，三者都不做语义加工。
     * <p>刻意不用 {@code bodyToMono(String)} 之类会「攒完整体」的读法，而是以 {@link DataBuffer}
     * 逐块透传，才能同时覆盖 SSE 流式与 JSON 非流式两种上游返回。
     * <p>{@code doOnNext(metering::onChunk)} 只是**只读观察**：{@code UsageCapture} 用
     * {@code DataBuffer.asByteBuffer()} 的只读视图复制字节，不推进原 buffer 的读写位置，
     * 因此被写回客户端的字节与上游发出的完全一致。
     *
     * <p><b>本方法是「最后一次尝试」</b>：它一开始写字节（{@code writeAndFlushWith}）响应就被提交，
     * 因此它之后发生的一切都不允许再换渠道。
     *
     * <p><b>上游 body 被取消 = 客户端断连</b>：Netty 发现客户端不再读我们的响应时会取消这次响应写，
     * 于是上游 body 的订阅被取消。这个取消有**两种收尾形状**（实测：全量套件下
     * {@code SseStreamingTest} 的断连用例偶发红，日志里上游 body 收到 {@code cancel} 而顶层
     * {@code doFinally} 拿到的是 {@code ON_COMPLETE}）：<b>有在飞的写</b> → 写失败（异常，走顶层
     * 的客户端断连分支）；<b>没有在飞的写</b> → 响应写以 {@code onComplete} 收尾，顶层看到的是一次
     * 「正常完成」—— 一次被客户端掐断的流会被记成 SUCCESS。因此这里在**最靠近取消发生的地方**
     * 把它记成客户端断连：计量结果不再取决于哪个形状先到（设计文档 §8.1 ⑤ 要求取消上游 +
     * 按已收内容估算）。
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
                .doOnNext(metering::onChunk)
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        metering.onClientDisconnected();
                    }
                });
        // writeAndFlushWith 逐块 flush：SSE 因此是真流式，而不是攒完再发。
        return response.writeAndFlushWith(body.map(Mono::just));
    }
}
