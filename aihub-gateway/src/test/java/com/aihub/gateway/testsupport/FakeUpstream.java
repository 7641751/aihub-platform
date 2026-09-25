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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JDK 内置 HttpServer 的假上游：无需 Docker、无需额外依赖。
 * 每个 start() 绑定随机端口；按入队顺序（FIFO）返回预置响应，队列空时返回 200 + 空 JSON。
 *
 * <p>M2 起队列里放的是 {@link Responder}（怎么写由预置项决定），因此可以构造
 * 「分块 + 中途卡住等测试放行」的响应 —— 这是验证「网关逐帧 flush」唯一可证伪的手段。
 */
public final class FakeUpstream {

    public record CapturedRequest(String method, String path, Map<String, String> headers, String body) {
    }

    /** 一个预置响应。 */
    @FunctionalInterface
    private interface Responder {
        void write(HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final Deque<Responder> queued = new ArrayDeque<>();
    private volatile CapturedRequest lastRequest;
    private volatile CountDownLatch secondFrameRelease = new CountDownLatch(0);
    private volatile boolean secondFrameWritten;

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

    /** 一次性返回整段 SSE（不模拟增量）。 */
    public synchronized void enqueueSse(String frames) {
        queued.add(exchange -> writeBody(exchange, 200, "text/event-stream; charset=utf-8", frames, Map.of()));
    }

    public synchronized void enqueueJson(int status, String body) {
        queued.add(exchange -> writeBody(exchange, status, "application/json; charset=utf-8", body, Map.of()));
    }

    /**
     * 原样发送 {@code Content-Type} 头，用于构造上游异常场景：
     * {@code contentType == null} 表示**完全不发**该头，其它值按字节原样写出（可以是畸形值）。
     */
    public synchronized void enqueueRaw(int status, String contentType, String body) {
        queued.add(exchange -> writeBody(exchange, status, contentType, body, Map.of()));
    }

    /** 额外响应头（例如上游 429 的 {@code Retry-After} / {@code x-ratelimit-*}）。 */
    public synchronized void enqueueWithHeaders(int status, String contentType, String body,
                                               Map<String, String> extraHeaders) {
        queued.add(exchange -> writeBody(exchange, status, contentType, body, extraHeaders));
    }

    /**
     * 分块发送的 SSE：第一帧写完**立刻 flush**，然后阻塞等测试放行（最多 5 秒），再写第二帧。
     * <p>这是「网关必须逐帧 flush」的唯一可证伪构造：一个「攒完再发」的实现在客户端侧**读不到第一帧**
     * （而客户端要读到第一帧才会放行上游）→ 死锁 → 用例在 5 秒读超时处失败。
     */
    public synchronized void enqueueHandshakeSse(String firstFrame, String secondFrame) {
        this.secondFrameRelease = new CountDownLatch(1);
        this.secondFrameWritten = false;
        queued.add(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);   // 0 => chunked（长度未知，才能逐帧发）
            OutputStream out = exchange.getResponseBody();
            out.write(firstFrame.getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                secondFrameRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            secondFrameWritten = true;
            out.write(secondFrame.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        });
    }

    /** 第二帧是否已经写出（用于断言「第一帧到达时上游还没写第二帧」）。 */
    public boolean secondFrameWritten() {
        return secondFrameWritten;
    }

    public void releaseSecondFrame() {
        secondFrameRelease.countDown();
    }

    public CapturedRequest lastRequest() {
        return lastRequest;
    }

    /**
     * 清空捕获。用例之间共享同一个假上游，而「请求根本没有到达上游」这一断言只有先把上一次的
     * 捕获清掉才成立（JUnit 不保证方法顺序）。
     */
    public void clearLastRequest() {
        lastRequest = null;
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

        Responder responder;
        synchronized (this) {
            responder = queued.poll();
        }
        if (responder == null) {
            writeBody(exchange, 200, "application/json; charset=utf-8", "{}", Map.of());
            return;
        }
        try {
            responder.write(exchange);
        } catch (IOException e) {
            // 网关/客户端断开时写响应会失败：这是断连用例的正常分支，不要把它扬出去。
        }
    }

    private static void writeBody(HttpExchange exchange, int status, String contentType, String body,
                                  Map<String, String> extraHeaders) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        extraHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(status, payload.length);
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
