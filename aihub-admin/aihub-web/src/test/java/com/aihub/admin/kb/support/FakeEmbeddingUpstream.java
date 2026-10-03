package com.aihub.admin.kb.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * **假的 embeddings 上游**（M5 决策 D6）：一个跑在**宿主进程内**的 JDK {@link HttpServer}，
 * 提供 OpenAI 兼容的 {@code POST /v1/embeddings}。既有先例是
 * {@code ChannelAdminIntegrationTest} 里那个假的 {@code /models} 上游（同样的
 * {@code HttpServer.create(loopback, 0)} 形状）。
 *
 * <p><b>为什么是"宿主机上的 JVM 级单例"而不是每个测试类一个</b>：它的 URL 必须由
 * {@code TestContainers.registerInfrastructure} 注册 —— 那是**唯一**的共享注册点，
 * 在某个测试类上单独注册 URL 会改变 Spring 上下文缓存键、**fork 出第 8 个上下文**
 * （M5 的硬约束是全量 {@code Tomcat started on port} 保持 7）。而每一个上下文里都跑着
 * embed 消费者（它们争抢同一个共享队列）⇒ 每个上下文都必须拿到**可用**的上游 URL。
 *
 * <p><b>行为可编排、且必须能复位</b>：它是 JVM 级单例，用例之间会互相污染 ⇒ 每个用例
 * 在 {@code @BeforeEach} 里调 {@link #reset()}。可编排的三件事：
 * <ul>
 *   <li>{@link #respondWithDeterministicVectors(int)} —— 正常应答；向量**可推导**：
 *       第 {@code i} 条输入的第 {@code j} 维 = {@code (i + 1) * (j + 1) / 100.0}
 *       （所以测试能独立算出期望值，而不是"看起来像个数组"）；</li>
 *   <li>{@link #neverRespond()} —— 收到请求后**挂住不答**（客户端必须靠**自己的超时**逃出来，
 *       这就是"超时有界"的判别形式）；</li>
 *   <li>{@link #failOnCall(int)} —— 第 {@code n} 次调用回 500（Task 6 用来注入"第 N 批才失败"）。</li>
 * </ul>
 *
 * <p>请求体只读 {@code input} 数组的**长度**（要生成几条向量），不关心模型名 ——
 * 上游是假的，模型名只用来证明"配置化"这件事（真值由运维给）。
 */
public final class FakeEmbeddingUpstream {

    /** 与附录 A 一致：假上游**固定 8 维**（真维度由上游/配置决定）。 */
    public static final int DEFAULT_DIMENSION = 8;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Object LOCK = new Object();

    private static HttpServer server;

    private static ExecutorService executor;

    /** 当前维度。 */
    private static volatile int dimension = DEFAULT_DIMENSION;

    /** 是否挂住不答（客户端只能靠自己的超时逃出来）。 */
    private static volatile boolean neverRespond;

    /** 第 n 次调用回 500（0 = 不注入失败）。 */
    private static volatile int failOnCall;

    /** 已收到的调用次数（用例可断言"确实打到了上游"）。 */
    private static final AtomicInteger CALLS = new AtomicInteger();

    private FakeEmbeddingUpstream() {
    }

    /** 上游的 base url（**懒启动**：第一次被问到才起，端口由内核分配）。 */
    public static String baseUrl() {
        ensureStarted();
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** 用例之间必须复位（JVM 级单例）。 */
    public static void reset() {
        dimension = DEFAULT_DIMENSION;
        neverRespond = false;
        failOnCall = 0;
        CALLS.set(0);
    }

    public static void respondWithDeterministicVectors(int dim) {
        dimension = dim;
        neverRespond = false;
        failOnCall = 0;
    }

    public static void neverRespond() {
        neverRespond = true;
    }

    public static void failOnCall(int n) {
        failOnCall = n;
        neverRespond = false;
    }

    public static int calls() {
        return CALLS.get();
    }

    private static void ensureStarted() {
        synchronized (LOCK) {
            if (server != null) {
                return;
            }
            try {
                HttpServer created = HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                // 必须给线程池：neverRespond 会占住线程，单线程实现会把后续用例一起堵死。
                executor = Executors.newCachedThreadPool();
                created.setExecutor(executor);
                created.createContext("/v1/embeddings", FakeEmbeddingUpstream::handleEmbeddings);
                created.start();
                server = created;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    created.stop(0);
                    executor.shutdownNow();
                }));
            } catch (IOException e) {
                throw new IllegalStateException("假 embeddings 上游起不来（绑定环回地址失败）", e);
            }
        }
    }

    private static void handleEmbeddings(HttpExchange exchange) throws IOException {
        int call = CALLS.incrementAndGet();
        try {
            byte[] requestBody = readAll(exchange.getRequestBody());
            if (neverRespond) {
                // 挂住：不写响应、也不关流。客户端只能靠自己的请求超时逃出来。
                sleepQuietly(60_000L);
                exchange.close();
                return;
            }
            if (failOnCall > 0 && call == failOnCall) {
                write(exchange, 500, "{\"error\":\"injected failure on call " + call + "\"}");
                return;
            }
            JsonNode request = MAPPER.readTree(requestBody.length == 0 ? "{}" : new String(requestBody, StandardCharsets.UTF_8));
            int count = request.path("input").isArray() ? request.path("input").size() : 1;
            write(exchange, 200, responseBody(count, dimension).toString());
        } catch (RuntimeException e) {
            write(exchange, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        } finally {
            exchange.close();
        }
    }

    /** 可推导的向量：第 i 条第 j 维 = (i+1)*(j+1)/100.0。 */
    private static ObjectNode responseBody(int count, int dim) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("object", "list");
        root.put("model", "fake-embedding");
        ArrayNode data = root.putArray("data");
        for (int i = 0; i < count; i++) {
            ObjectNode item = data.addObject();
            item.put("object", "embedding");
            item.put("index", i);
            ArrayNode vector = item.putArray("embedding");
            for (int j = 0; j < dim; j++) {
                vector.add((i + 1) * (j + 1) / 100.0);
            }
        }
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", count);
        usage.put("total_tokens", count);
        return root;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private static void write(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // 用例已经结束了（客户端断开）：假上游不需要为这件事失败。
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
