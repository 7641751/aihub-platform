package com.aihub.gateway.error;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/** 在 Spring Boot 的默认处理器之前，为 {@code /v1/} 路由错误生成 OpenAI 兼容错误体。 */
@Component
@Order(-2)
public class GatewayRouteErrorHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        var response = exchange.getResponse();
        if (response.isCommitted()
                || !exchange.getRequest().getPath().pathWithinApplication().value().startsWith("/v1/")
                || !(error instanceof ResponseStatusException failure)) {
            return Mono.error(error);
        }

        var status = HttpStatus.resolve(failure.getStatusCode().value());
        if (status != HttpStatus.NOT_FOUND && status != HttpStatus.METHOD_NOT_ALLOWED) {
            return Mono.error(error);
        }

        response.getHeaders().putAll(failure.getHeaders());
        boolean missing = status == HttpStatus.NOT_FOUND;
        return GatewayErrors.write(response, status, "invalid_request_error",
                missing ? "route_not_found" : "method_not_allowed",
                missing ? "Route not found" : "Method not allowed");
    }
}
