package com.aihub.service.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * embeddings 上游客户端（M5 决策 D6）：一个**配置化**的 OpenAI 兼容 {@code POST /v1/embeddings}。
 *
 * <p><b>为什么不接真实计费 API</b>：真 embeddings 需要密钥、网络与计费 ⇒ 验收**不可重复**；
 * 而假上游（{@code FakeEmbeddingUpstream}，宿主进程内的 JDK {@code HttpServer}）能把
 * "第 N 批才失败""挂住不答"这些**失败路径**变成可编排的输入 —— 这正是 Task 6 的官方验收需要的。
 * 生产上把 {@code AIHUB_KB_EMBEDDING_BASE_URL} 指向真的 OpenAI 兼容服务即可，代码一行不改。
 *
 * <p><b>为什么必须有显式超时</b>：没有超时的 HTTP 调用会把消费线程**无限期挂住**，
 * 于是消息既不 ack 也不进 DLQ、状态永远停在 {@code EMBEDDING} —— 那是比"失败"更糟的形态
 * （没有任何观测面，也没有任何出口）。超时值来自 {@code aihub.kb.embedding.timeout-seconds}
 * （默认 {@value #DEFAULT_TIMEOUT_SECONDS} 秒），并且**判据文字与配置项必须是同一个数**：
 * {@code KbEmbeddingClientTest} 会断言"配置读到的值 == 文档写的值"。
 *
 * <p><b>失败一律抛，绝不吞</b>：把"上游不可用"降级成"返回空向量"会让整篇文档被写成空向量
 * 并最终 {@code READY} —— 检索侧永远查不到，却显示成功。宁可抛出去走重试/DLQ（D7）。
 */
@Service
public class KbEmbeddingClient {

    /** 与 {@code application.yml} 的默认值、以及用例里的判据文字必须是**同一个数**。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final HttpClient httpClient;

    public KbEmbeddingClient(@Value("${aihub.kb.embedding.base-url:}") String baseUrl,
                             @Value("${aihub.kb.embedding.model:kb-embedding}") String model,
                             @Value("${aihub.kb.embedding.timeout-seconds:" + DEFAULT_TIMEOUT_SECONDS + "}")
                             int timeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        // 与 KbVectorStoreClient 同因：明文 http:// 下 JDK HttpClient 默认走 HTTP/2 的 h2c 升级前奏，
        // 而多数自建/容器化的 OpenAI 兼容上游是 HTTP/1.1-only（h11/uvicorn、nginx 等）
        // ⇒ 钉死 HTTP/1.1，避免"请求发出去了、对方说没收到体"这类隐晦失败。
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    /** 配置读到的超时（用例用它把"阈值与判据文字一致"变成可证伪的断言）。 */
    public Duration timeout() {
        return timeout;
    }

    /**
     * 把一批文本嵌成向量，顺序与入参**一一对应**。
     *
     * @throws IllegalStateException 上游未配置 / 非 2xx / 返回条数对不上 / 超时或连接失败
     */
    public List<float[]> embed(List<String> texts) {
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException(
                    "embeddings 上游未配置：aihub.kb.embedding.base-url / AIHUB_KB_EMBEDDING_BASE_URL");
        }
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", model);
        ArrayNode input = request.putArray("input");
        texts.forEach(input::add);

        HttpResponse<String> response;
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/embeddings"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8))
                    .build();
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 超时在这里（HttpTimeoutException 是 IOException 的子类）—— 必须抛，绝不返回空向量。
            throw new IllegalStateException("embeddings 上游调用失败（超时上限 " + timeout.toSeconds() + " 秒）："
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embeddings 调用被中断", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("embeddings 上游返回 " + response.statusCode()
                    + "：" + abbreviate(response.body()));
        }
        return parseVectors(response.body(), texts.size());
    }

    private static List<float[]> parseVectors(String body, int expected) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("embeddings 响应不是合法 JSON：" + abbreviate(body), e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new IllegalStateException("embeddings 返回条数与入参不一致：期望 " + expected
                    + " 实际 " + (data.isArray() ? data.size() : "非数组"));
        }
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        // 上游**不保证**顺序，按 index 归位（OpenAI 兼容体里 index 就是入参下标）。
        items.sort(Comparator.comparingInt(item -> item.path("index").asInt()));
        List<float[]> vectors = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new IllegalStateException("embeddings 返回了空向量 / 非数组");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "<null>";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
