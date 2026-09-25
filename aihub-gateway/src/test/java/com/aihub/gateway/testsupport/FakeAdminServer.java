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
 * 基于 JDK 内置 {@link HttpServer} 的假 admin 内部接口：无需 Docker、无需 Redis，与
 * {@link FakeUpstream} 同款。每个 {@link #start()} 绑定随机端口；按入队顺序（FIFO）返回预置响应，
 * 队列空时返回 200 + 空 JSON。
 * <p>与 {@code FakeUpstream} 的唯一区别：这里记录 {@code getRawPath()}（**原始**路径），
 * 因为「签名/请求打的是应用内相对路径而不是带前缀的 URL」正是要钉住的契约，用解码后的
 * {@code getPath()} 断言会掩盖一次编码/前缀改写。
 */
public final class FakeAdminServer {

    public record CapturedRequest(String method, String rawPath, Map<String, String> headers, String body) {
    }

    private record Response(int status, String contentType, String body) {
    }

    private final HttpServer server;
    private final Deque<Response> queued = new ArrayDeque<>();
    private volatile CapturedRequest lastRequest;

    private FakeAdminServer(HttpServer server) {
        this.server = server;
    }

    public static FakeAdminServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeAdminServer fake = new FakeAdminServer(server);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动假 admin", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized void enqueueJson(int status, String body) {
        queued.add(new Response(status, "application/json; charset=utf-8", body));
    }

    /** 原样发送 {@code Content-Type}；{@code null} 表示完全不发该头。 */
    public synchronized void enqueueRaw(int status, String contentType, String body) {
        queued.add(new Response(status, contentType, body));
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
                exchange.getRequestURI().getRawPath(), headers, body);

        Response response;
        synchronized (this) {
            response = queued.poll();
        }
        if (response == null) {
            response = new Response(200, "application/json; charset=utf-8", "{}");
        }
        byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
