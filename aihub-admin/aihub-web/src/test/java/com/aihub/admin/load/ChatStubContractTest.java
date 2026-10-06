package com.aihub.admin.load;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1（M6 压测桩）的契约测试：把 {@code load/stub/ChatStub.java} 当作**单文件程序**起起来
 * （`java <file>`，与 compose 里用的启动方式**同一份命令**），断言它满足压测所需的最小契约。
 *
 * <p>刻意**不起 Spring**（不占上下文预算）：这是一个纯 HTTP 黑盒测试。
 *
 * <p>为什么这些断言是"契约"而不是"实现细节"：压测的每一类数据都依赖它们 ——
 * 非流式数据要合规的 `usage`（计量对账），流式 TTFT 要**逐块 SSE**，
 * "流中途断开"的故障注入要能在**没有 `[DONE]`** 的情况下收尾。
 */
class ChatStubContractTest {

    private static Process stub;
    private static String base;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void startStubOnAFreePort() throws Exception {
        Path source = locateStubSource();
        int port = freePort();
        ProcessBuilder pb = new ProcessBuilder("java", source.toString(), String.valueOf(port));
        Map<String, String> env = pb.environment();
        env.put("STUB_FIRST_TOKEN_DELAY_MS", "250");
        env.put("STUB_TOKEN_DELAY_MS", "1");
        env.put("STUB_TOKENS", "8");
        pb.redirectErrorStream(true);
        stub = pb.start();
        base = "http://127.0.0.1:" + port;
        awaitHealth();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.destroyForcibly();
        }
    }

    @Test
    void healthEndpointAnswersSoComposeCanProbeIt() throws Exception {
        HttpResponse<String> res = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/healthz"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("压测桩必须可被健康检查探到（compose 依赖它）").isEqualTo(200);
    }

    @Test
    void aNonStreamingCallReturnsTheOpenAiShapeWithUsage() throws Exception {
        HttpResponse<String> res = post("{\"model\":\"stub-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("content-type").orElse("")).contains("application/json");
        String body = res.body();
        assertThat(body).as("必须有 id / choices / usage（计量对账依赖 usage 的存在）")
                .contains("\"id\"").contains("\"choices\"").contains("\"usage\"");
        assertThat(body).as("非流式必须带正文").contains("\"content\"");
        assertThat(body).as("usage 必须像 OpenAI 那样带 prompt/completion/total")
                .contains("prompt_tokens").contains("completion_tokens").contains("total_tokens");
    }

    @Test
    void aStreamingCallEmitsSseChunksThenDone() throws Exception {
        HttpResponse<String> res = post("{\"model\":\"stub-chat\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("content-type").orElse(""))
                .as("流式必须声明 SSE（否则网关无法按流处理）").contains("text/event-stream");
        List<String> dataLines = dataLines(res.body());
        assertThat(dataLines.size()).as("逐块（不是一整块）+ 一个 [DONE]").isGreaterThanOrEqualTo(3);
        assertThat(dataLines.get(dataLines.size() - 1)).as("最后一块必须是 [DONE]").isEqualTo("[DONE]");
        assertThat(dataLines.subList(0, dataLines.size() - 1))
                .allSatisfy(line -> assertThat(line).as("每一块都必须是 JSON").startsWith("{"));
    }

    @Test
    void theConfiguredFirstTokenDelayIsHonoured() throws Exception {
        long started = System.nanoTime();
        post("{\"model\":\"stub-chat\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMs).as("首字延迟配了 250ms ⇒ 整条流不可能在 200ms 内跑完（TTFT 数据要可控）")
                .isGreaterThanOrEqualTo(200);
    }

    @Test
    void abortInjectionEndsTheStreamWithoutDone() throws Exception {
        Path source = locateStubSource();
        int port = freePort();
        ProcessBuilder pb = new ProcessBuilder("java", source.toString(), String.valueOf(port));
        pb.environment().put("STUB_ABORT_AFTER_TOKENS", "2");
        pb.environment().put("STUB_TOKENS", "8");
        pb.redirectErrorStream(true);
        Process aborting = pb.start();
        try {
            String abortBase = "http://127.0.0.1:" + port;
            awaitHealth(abortBase);
            HttpResponse<String> res = CLIENT.send(HttpRequest.newBuilder(URI.create(abortBase + "/v1/chat/completions"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"model\":\"stub-chat\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                                    StandardCharsets.UTF_8))
                            .build(), HttpResponse.BodyHandlers.ofString());
            List<String> dataLines = dataLines(res.body());
            assertThat(dataLines).as("注入中途断流时，必须收不到 [DONE]（这正是\"流中断\"的机器特征）")
                    .doesNotContain("[DONE]");
            assertThat(dataLines).as("断开前必须先发出若干块（否则没覆盖到\"中途\"）").isNotEmpty();
        } finally {
            aborting.destroyForcibly();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static HttpResponse<String> post(String json) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/v1/chat/completions"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> dataLines(String body) {
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("data:")) {
                lines.add(trimmed.substring("data:".length()).strip());
            }
        }
        return lines;
    }

    private static void awaitHealth() throws Exception {
        awaitHealth(base);
    }

    private static void awaitHealth(String target) throws Exception {
        Exception last = null;
        for (int i = 0; i < 60; i++) {
            try {
                HttpResponse<String> res = CLIENT.send(HttpRequest.newBuilder(URI.create(target + "/healthz"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("压测桩没能在 15 秒内起来（它是 `java <file> <port>` 单文件程序）", last);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 从当前模块目录往上找 {@code load/stub/ChatStub.java}（Maven 的 user.dir 是模块目录）。 */
    private static Path locateStubSource() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("load").resolve("stub").resolve("ChatStub.java");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 load/stub/ChatStub.java（T1 的压测桩还没写）");
    }
}
