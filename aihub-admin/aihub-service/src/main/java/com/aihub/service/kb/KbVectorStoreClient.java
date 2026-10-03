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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chroma（向量库）的 REST 客户端（M5 决策 D3：**真 Chroma**，不许用假 HTTP 上游替代）。
 *
 * <p><b>契约按 2026-10-02/03 的两次实测钉死</b>（`docs/superpowers/plans/2026-10-02-m5-kb-pipeline.md`
 * 「Chroma 契约」表 + `.m5t5-logs/step0-04-contract-probe.txt` 的原始输出），**不是照文档猜的**：
 * <ul>
 *   <li>建/取集合：{@code POST /api/v1/collections} 体 {@code {"name":…,"get_or_create":true}} ⇒ 返回 {@code id}(UUID)；
 *       <b>除"按集合名"的路由外，所有集合级路由都吃这个 {@code collection_id}，不吃名字</b>；</li>
 *   <li>写：{@code POST /api/v1/collections/{id}/upsert} ⇒ **重放同一批 ids 时 count 不变**（幂等 ⇒ D15 的前提成立）；</li>
 *   <li>取：{@code POST /api/v1/collections/{id}/get} 体 {@code {"where":{"doc_id":N},"include":["metadatas"]}}
 *       ⇒ 返回的 {@code ids} 与写入时**逐字一致**（即 {@code "{docId}:{seq}"}）；</li>
 *   <li>删：{@code POST /api/v1/collections/{id}/delete} 体 {@code {"where":{"doc_id":N}}} ⇒ **只删该 doc**（实测 3→1）；</li>
 *   <li>⚠️ <b>计数：{@code count} 是 {@code GET}</b> —— 实测用 {@code POST} 调它得到 <b>405</b>，别照直觉写。
 *       而且它**不支持 where 过滤** ⇒ 本类的 {@link #countByDocId(long)} 是"取回该 doc 的 ids 再数长度"。</li>
 * </ul>
 *
 * <p><b>metadata 与 id 的形状是跨仓库契约</b>（计划附录 A）：`vector_id = "{docId}:{seq}"`；
 * metadata **必填** `doc_id`(long)/`tenant_id`(long)/`seq`(int)；**文本不进 Chroma**（只回查 MySQL）。
 * 检索侧（另一个仓库的 Python）靠 `where={"tenant_id": N}` 隔离租户 ⇒ **改这几个字段名就是破坏性变更**。
 */
@Service
public class KbVectorStoreClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String collectionName;
    private final Duration timeout;
    private final HttpClient httpClient;

    /** 集合 id 懒解析一次（UUID，进程生命周期内不变）。 */
    private volatile String collectionId;

    public KbVectorStoreClient(@Value("${aihub.kb.chroma.base-url:}") String baseUrl,
                               @Value("${aihub.kb.chroma.collection:kb_chunks}") String collectionName,
                               @Value("${aihub.kb.chroma.timeout-seconds:30}") int timeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.collectionName = collectionName;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        // ⚠️ 必须钉 HTTP/1.1：JDK 的 HttpClient 对 **明文 http://** 默认协商 HTTP/2，会先发一个
        // "h2c" 升级前奏（`Upgrade: h2c`）。Chroma 的 uvicorn/h11 **解析不了那个前奏**，于是 FastAPI
        // 认为请求体不存在 ⇒ 建集合收到 **422 `{"loc":["body"],"msg":"Field required","input":null}`**
        // （2026-10-03 实测：同一个 URL、同一个 JSON 体，用 curl（HTTP/1.1）得到 200，用本客户端得到 422）。
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    /** 一条待写入的记录（id 即 {@code vector_id}）。 */
    public record VectorRecord(String id, float[] vector, Map<String, Object> metadata) {
    }

    /** 写/覆盖一批记录（同 id 重放 ⇒ 覆盖，不新增）。 */
    public void upsert(List<VectorRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        ArrayNode ids = MAPPER.createArrayNode();
        ArrayNode embeddings = MAPPER.createArrayNode();
        ArrayNode metadatas = MAPPER.createArrayNode();
        for (VectorRecord record : records) {
            ids.add(record.id());
            ArrayNode vector = embeddings.addArray();
            for (float value : record.vector()) {
                vector.add(value);
            }
            ObjectNode metadata = metadatas.addObject();
            record.metadata().forEach((key, value) -> {
                if (value instanceof Long longValue) {
                    metadata.put(key, longValue);
                } else if (value instanceof Integer intValue) {
                    metadata.put(key, intValue);
                } else {
                    metadata.put(key, String.valueOf(value));
                }
            });
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.set("ids", ids);
        body.set("embeddings", embeddings);
        body.set("metadatas", metadatas);
        post("/api/v1/collections/" + collectionId() + "/upsert", body);
    }

    /** 该 doc 在向量库里的记录 id（即 {@code "{docId}:{seq}"}），顺序不保证。 */
    public List<String> idsForDoc(long docId) {
        JsonNode root = post("/api/v1/collections/" + collectionId() + "/get", getBody(docId));
        List<String> ids = new ArrayList<>();
        root.path("ids").forEach(node -> ids.add(node.asText()));
        return ids;
    }

    /** 该 doc 的 metadata 列表（与 {@link #idsForDoc(long)} 同序）。 */
    public List<Map<String, Object>> metadatasForDoc(long docId) {
        JsonNode root = post("/api/v1/collections/" + collectionId() + "/get", getBody(docId));
        List<Map<String, Object>> metadatas = new ArrayList<>();
        for (JsonNode node : root.path("metadatas")) {
            metadatas.add(MAPPER.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {
            }));
        }
        return metadatas;
    }

    /** 该 doc 的记录数（Chroma 的 {@code count} 不吃 where ⇒ 取回 ids 再数）。 */
    public int countByDocId(long docId) {
        return idsForDoc(docId).size();
    }

    /** 该 doc 的 metadata 里出现过的 `tenant_id` 集合（检索侧的隔离维度）。 */
    public Set<Long> tenantIdsForDoc(long docId) {
        Set<Long> tenants = new LinkedHashSet<>();
        metadatasForDoc(docId).forEach(metadata -> {
            Object value = metadata.get("tenant_id");
            if (value instanceof Number number) {
                tenants.add(number.longValue());
            }
        });
        return tenants;
    }

    /** 按 metadata 的 `doc_id` 全删该 doc（D8 的清理顺序里**第一步**）。 */
    public void deleteByDocId(long docId) {
        ObjectNode where = MAPPER.createObjectNode();
        where.put("doc_id", docId);
        ObjectNode body = MAPPER.createObjectNode();
        body.set("where", where);
        post("/api/v1/collections/" + collectionId() + "/delete", body);
    }

    private static ObjectNode getBody(long docId) {
        ObjectNode where = MAPPER.createObjectNode();
        where.put("doc_id", docId);
        ObjectNode body = MAPPER.createObjectNode();
        body.set("where", where);
        body.putArray("include").add("metadatas");
        return body;
    }

    /** 懒解析集合 id：`POST /api/v1/collections {name, get_or_create:true}`。 */
    private String collectionId() {
        String cached = collectionId;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (collectionId == null) {
                ObjectNode body = MAPPER.createObjectNode();
                body.put("name", collectionName);
                body.put("get_or_create", true);
                JsonNode response = post("/api/v1/collections", body);
                String id = response.path("id").asText(null);
                if (id == null || id.isEmpty()) {
                    throw new IllegalStateException("Chroma 未返回集合 id：" + response);
                }
                collectionId = id;
            }
            return collectionId;
        }
    }

    private JsonNode post(String path, ObjectNode body) {
        return send(HttpRequest.newBuilder().uri(URI.create(requireBaseUrl() + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Chroma 调用失败（超时上限 " + timeout.toSeconds() + " 秒）："
                    + request.method() + " " + request.uri() + " ⇒ "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Chroma 调用被中断", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Chroma 返回 " + response.statusCode() + "：" + request.uri()
                    + " ⇒ " + abbreviate(response.body()));
        }
        String body = response.body();
        if (body == null || body.isBlank() || "null".equals(body.trim())) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("Chroma 响应不是合法 JSON：" + abbreviate(body), e);
        }
    }

    private String requireBaseUrl() {
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException(
                    "Chroma 未配置：aihub.kb.chroma.base-url / AIHUB_KB_CHROMA_BASE_URL");
        }
        return baseUrl;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "<null>";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
