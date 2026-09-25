package com.aihub.gateway.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 JDK 内置 HttpServer 的假上游：无需 Docker、无需额外依赖。
 * 每个 start() 绑定随机端口；按入队顺序（FIFO）返回预置响应，队列空时返回 200 + 空 JSON。
 */
public final class FakeUpstream {

    public record CapturedRequest(String method, String path, Map<String, String> headers, String body) {
    }

    private record Response(int status, String contentType, String body) {
    }

    private final HttpServer server;
    private final Deque<Response> queued = new ArrayDeque<>();
    private volatile CapturedRequest lastRequest;

    private FakeUpstream(HttpServer server) {
        this.server = server;
    }

    public static FakeUpstream start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeUpstream fake = new FakeUpstream(server);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动假上游", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized void enqueueSse(String frames) {
        queued.add(new Response(200, "text/event-stream; charset=utf-8", frames));
    }

    public synchronized void enqueueJson(int status, String body) {
        queued.add(new Response(status, "application/json; charset=utf-8", body));
    }

    public CapturedRequest lastRequest() {
        return lastRequest;
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        lastRequest = new CapturedRequest(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(), headers, body);

        Response response;
        synchronized (this) {
            response = queued.poll();
        }
        if (response == null) {
            response = new Response(200, "application/json; charset=utf-8", "{}");
        }
        byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.status(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    public static String sseFrames() {
        return """
                data: {"choices":[{"delta":{"content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: [DONE]

                """;
    }

    public static String completionJson() {
        return """
                {"id":"chatcmpl-1","object":"chat.completion","model":"m1","created":1,\
                "choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],\
                "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}""";
    }
}
