package com.aihub.gateway.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关 {@code /v1/**} 的 OpenAI 兼容错误体。
 * <p>注意：这里**不用** admin 的 {@code {code,message,data}} 信封 —— 数据面遵循 OpenAI 协议，
 * 客户端（各种 OpenAI SDK）按 {@code error.message} 取值。
 */
public final class GatewayErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GatewayErrors() {
    }

    public static Mono<Void> write(ServerHttpResponse response, HttpStatus status,
                                  String type, String code, String message) {
        // 响应已提交（典型：上游中途断开、头部已 flush 给客户端）时，改状态码/头是非法的，
        // 会从错误处理器里再抛一次异常。此时只把响应收尾。
        if (response.isCommitted()) {
            return response.setComplete();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] payload = serialize(type, code, message);
        DataBuffer buffer = response.bufferFactory().wrap(payload);
        return response.writeWith(Mono.just(buffer));
    }

    public static byte[] serialize(String type, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        error.put("param", null);
        error.put("code", code);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", error);
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            return ("{\"error\":{\"message\":\"internal error\",\"type\":\"api_error\","
                    + "\"param\":null,\"code\":\"internal_error\"}}").getBytes(StandardCharsets.UTF_8);
        }
    }
}
