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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * 握手响应「等测试放行」的预算。**必须严格大于客户端的读取预算**（{@code SseStreamingTest} 现为 5 秒）：
     * 它只是兜底（客户端侧机制真的坏了时才轮到它），绝不与客户端读取竞争。两个预算相等时「谁先到期」
     * 由时序抖动决定，会把一个**正确**的中继判红。该不变式不是靠这段注释维持的：
     * {@code SseStreamingTest#upstreamHoldBudgetMustExceedClientReadBudget()} 会断言这里的值严格大于
     * 客户端预算，任何调小它的改动都会立刻变红。任务背景见
     * {@code docs/superpowers/plans/2026-09-23-m2-metering.md} 的 Task 6。
     */
    public static final long SECOND_FRAME_HOLD_BUDGET_SECONDS = 30;

    /**
     * 最近一次握手的「第二帧是否已经写出」。每次 {@link #enqueueHandshakeSse} 都换成新实例，
     * 且 handler 闭包的是**它自己那一次**的实例（见该方法注释），所以重叠握手不会互相踩。
     */
    private volatile AtomicBoolean secondFrameWritten = new AtomicBoolean(false);

    /** 还在扣留第二帧的握手；{@link #releaseSecondFrame()} 会把它们全部放行。 */
    private final List<CountDownLatch> pendingReleases = new CopyOnWriteArrayList<>();

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
     * 只发一个错误状态码 + JSON 错误体（用于构造上游 5xx 与 429）。
     * 与 {@link #enqueueJson(int, String)} 的实现相同，只是命名让故障注入用例读起来更清楚。
     */
    public synchronized void enqueueError(int status, String body) {
        enqueueJson(status, body);
    }

    /**
     * 连上但**永远不回响应头**：用来构造「上游超时」。
     * <p>响应头不回 = 网关的 {@code responseTimeout} 会触发（非流式客户端按 {@code channel.timeoutMs}），
     * 因此这个夹具的等待预算必须**大于**被测客户端的超时，否则用例会先被自己的兜底放行。
     *
     * @param holdMillis 最多扣留多少毫秒（到时写出 200 空 JSON 并关闭，避免测试进程挂住）
     */
    public synchronized void enqueueStall(long holdMillis) {
        queued.add(exchange -> {
            try {
                Thread.sleep(holdMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeBody(exchange, 200, "application/json; charset=utf-8", "{}", Map.of());
        });
    }

    /**
     * 分块发送的 SSE：第一帧写完**立刻 flush**，然后阻塞等测试放行（最多
     * {@value #SECOND_FRAME_HOLD_BUDGET_SECONDS} 秒），再写第二帧。
     *
     * <p>这是「网关必须逐帧 flush」的唯一可证伪构造。一个「攒完再发」的实现的实测失败机制是：
     * 它在收到第一帧时既不提交响应头也不回吐字节 → 客户端的 {@code send()} 一直在等响应头；
     * 而「客户端读到第一帧」正是测试放行上游的前提，于是**没人放行**，上游的等待预算自然到期、
     * 写出第二帧并关闭 body → 网关这才把整段字节一次性回吐 → 客户端读到第一帧，但此刻
     * {@code secondFrameWritten()} 已经是 true，用例在该断言上变红（失败方法耗时 ≈ 本预算的秒数）。
     *
     * <p>客户端的读取预算是**兜底路径**：只有当某个实现先把响应头提交出去、却把第一帧扣住时才会
     * 走到它，所以它绝不是攒批实现的主失败点。上游的等待预算因此被刻意设为**远大于**客户端读取
     * 预算（30 s vs 5 s）：这样「已读到第一帧、还没做断言」这段留给正确实现的余量从 5 s 变成 30 s，
     * 一次调度延迟不再能把一个**正确**的中继判红。两个预算相等时「谁先到期」由时序抖动决定 ——
     * 那正是本次评审要求修掉的假红窗口。
     *
     * <p>每次调用都新建独立的 latch 与标志，handler 闭包的是这两个**局部量**：两次重叠的握手各自等
     * 自己的 latch、各自写自己的标志，不会共用「最近一次」的状态。{@link #releaseSecondFrame()} /
     * {@link #secondFrameWritten()} 这两个既有签名只能指向最近一次握手（放行侧则会放行所有未放行的）。
     */
    public synchronized void enqueueHandshakeSse(String firstFrame, String secondFrame) {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean written = new AtomicBoolean(false);
        pendingReleases.add(release);
        this.secondFrameWritten = written;
        queued.add(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);   // 0 => chunked（长度未知，才能逐帧发）
            OutputStream out = exchange.getResponseBody();
            try {
                out.write(firstFrame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    release.await(SECOND_FRAME_HOLD_BUDGET_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                out.write(secondFrame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                // 只有字节真的写出去（并 flush）之后才置位：这个标志断言的是「已写出」，
                // 不是「已停止扣留」——放在 write 之前会让标量名与观测事实不符。
                written.set(true);
            } finally {
                pendingReleases.remove(release);
                out.close();
            }
        });
    }

    /** 第二帧是否已经写出（用于断言「第一帧到达时上游还没写第二帧」）。 */
    public boolean secondFrameWritten() {
        return secondFrameWritten.get();
    }

    /** 放行所有仍在扣留第二帧的握手响应（常规用法下只有一个在飞）。 */
    public void releaseSecondFrame() {
        for (CountDownLatch pending : pendingReleases) {
            pending.countDown();
        }
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
