package com.aihub.gateway.trace;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 给每个 {@code /v1/**} 请求分配一个 {@code x-request-id}，并立刻写进响应头。
 *
 * <p><b>为什么由网关生成</b>：这个值就是计量事件的 {@code request_id}，也是 {@code request_log}
 * 幂等键的一半（见计划「决策登记」第 1 条）。上游的 {@code x-request-id} 我们看不到、
 * 也不保证唯一，因此**不再透传上游的同名头**（M1 的白名单里有它，Task 2 移除）。
 *
 * <p><b>为什么不回显客户端带来的值</b>：那等于把幂等键的决定权交给调用方。
 *
 * <p><b>顺序</b>：排在 {@code ApiKeyAuthFilter}（{@code HIGHEST_PRECEDENCE + 100}）之**前**，
 * 因此 401 / 404 这类被短路或异常收尾的响应也带 {@code x-request-id} —— 排查问题时最有用的
 * 恰恰是这些失败请求。守备范围与鉴权过滤器一致（{@link PathPattern} 匹配应用内路径），
 * 避免「原始路径 vs 解码路径」那类不一致（M1 已经踩过一次）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "x-request-id";

    /** 计量组装从这里取 request_id（控制器唯一取值点）。 */
    public static final String ATTRIBUTE_REQUEST_ID = "aihub.requestId";

    private static final PathPattern V1_PATH = new PathPatternParser().parse("/v1/**");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!V1_PATH.matches(exchange.getRequest().getPath().pathWithinApplication())) {
            return chain.filter(exchange);
        }
        ensure(exchange);
        return chain.filter(exchange);
    }

    /**
     * 取本次请求的 id；没有就生成一个**并同时写进响应头**。
     * <p>这样「响应头里的 id」与「计量事件里的 id」由同一处产生，任何调用顺序都不会分叉。
     */
    public static String ensure(ServerWebExchange exchange) {
        Object existing = exchange.getAttributes().get(ATTRIBUTE_REQUEST_ID);
        if (existing != null) {
            return existing.toString();
        }
        String requestId = UUID.randomUUID().toString();
        exchange.getAttributes().put(ATTRIBUTE_REQUEST_ID, requestId);
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        return requestId;
    }

    /** 只读：未经过本过滤器时为 {@code null}（需要保证非空时请用 {@link #ensure}）。 */
    public static String requestId(ServerWebExchange exchange) {
        Object value = exchange.getAttributes().get(ATTRIBUTE_REQUEST_ID);
        return value == null ? null : value.toString();
    }
}
