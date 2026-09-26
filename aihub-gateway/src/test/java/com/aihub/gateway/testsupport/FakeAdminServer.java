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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 JDK 内置 {@link HttpServer} 的假 admin 内部接口：无需 Docker、无需 Redis，与
 * {@link FakeUpstream} 同款。每个 {@link #start()} 绑定随机端口；按入队顺序（FIFO）返回预置响应，
 * 队列空时返回 200 + 空 JSON。
 * <p>与 {@code FakeUpstream} 的唯一区别：这里记录 {@code getRawPath()}（**原始**路径），
 * 因为「签名/请求打的是应用内相对路径而不是带前缀的 URL」正是要钉住的契约，用解码后的
 * {@code getPath()} 断言会掩盖一次编码/前缀改写。
 *
 * <p><b>按路径入队</b>（{@link #enqueueJsonForPath} / {@link #enqueueStalledJsonForPath}）：真实的
 * {@code AdminClient.Http} 会为**两个**内部接口回源 —— {@code POST /internal/api-keys/resolve} 与
 * {@code GET /internal/config/snapshot}。用同一个 FIFO 队列时，控制面快照那一跳会悄悄吃掉本来预置给
 * 密钥解析的响应（顺序还取决于控制面刷新的时机），于是"第几个请求拿到哪一份响应"变成不确定的。
 * 按路径分开之后，每条内部接口各有一条自己的队列；没有专属队列的路径仍然走全局队列（既有语义不变）。
 * 未入队的路径返回 200 + {@code {}} —— 对快照而言就是"没有快照"，网关按既有约定回落到遗留渠道。
 */
public final class FakeAdminServer {

    public record CapturedRequest(String method, String rawPath, Map<String, String> headers, String body) {
    }

    /** {@code delayMillis > 0} 表示先睡这么久再回答 —— 用来跨过网关给内部跳的 responseTimeout。 */
    private record Response(int status, String contentType, String body, long delayMillis) {
    }

    private final HttpServer server;
    private final Deque<Response> queued = new ArrayDeque<>();
    private final Map<String, Deque<Response>> queuedByPath = new ConcurrentHashMap<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final Map<String, AtomicInteger> requestCountByPath = new ConcurrentHashMap<>();
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
        queued.add(new Response(status, "application/json; charset=utf-8", body, 0L));
    }

    /** 原样发送 {@code Content-Type}；{@code null} 表示完全不发该头。 */
    public synchronized void enqueueRaw(int status, String contentType, String body) {
        queued.add(new Response(status, contentType, body, 0L));
    }

    /** 只为某一条内部接口路径入队一个响应（见类注释「按路径入队」）。 */
    public synchronized void enqueueJsonForPath(String path, int status, String body) {
        queueFor(path).add(new Response(status, "application/json; charset=utf-8", body, 0L));
    }

    /**
     * 为该路径入队一个**迟到**的响应：先睡 {@code delayMillis} 再回答。
     * {@code 3500} 会跨过网关给内部跳的 {@code responseTimeout(3s)}，于是网关这次回源以
     * 传输故障收场 —— 这正是 D1 在 compose 上实测到的形状（WARN→ERROR 恰好 3.01 秒）。
     */
    public synchronized void enqueueStalledJsonForPath(String path, int status, String body, long delayMillis) {
        queueFor(path).add(new Response(status, "application/json; charset=utf-8", body, delayMillis));
    }

    private Deque<Response> queueFor(String path) {
        return queuedByPath.computeIfAbsent(path, ignored -> new ArrayDeque<>());
    }

    /** 假 admin 收到的**全部**请求数（含控制面快照那一跳）。 */
    public int requestCount() {
        return requestCount.get();
    }

    /**
     * 清空所有预置响应与计数器。同一个类里的用例共享同一个假 admin（Spring 上下文按类缓存），
     * 而"排一个诱饵响应来证明某条路径**没有**回源"这种手法会把诱饵**留在队列里**、
     * 污染下一个用例。用例之间必须显式 {@code reset()}。
     */
    public synchronized void reset() {
        queued.clear();
        queuedByPath.clear();
        requestCount.set(0);
        requestCountByPath.clear();
    }

    /** 只统计某一条内部接口路径收到的请求数 —— 「负缓存有没有生效」靠它说话。 */
    public int requestCountForPath(String path) {
        AtomicInteger counter = requestCountByPath.get(path);
        return counter == null ? 0 : counter.get();
    }

    public CapturedRequest lastRequest() {
        return lastRequest;
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestCount.incrementAndGet();
        requestCountByPath.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();

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
            Deque<Response> byPath = queuedByPath.get(path);
            response = byPath == null ? null : byPath.poll();
            if (response == null) {
                response = queued.poll();
            }
        }
        if (response == null) {
            response = new Response(200, "application/json; charset=utf-8", "{}", 0L);
        }
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        try {
            exchange.sendResponseHeaders(response.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (IOException clientGone) {
            // 「迟到」响应正是要客户端先放弃：那时连接已经被网关掐掉，写回必然失败。
            // 它不是夹具的错误，忽略即可（否则每个迟到用例都会在日志里留下一次假异常）。
        }
    }
}
