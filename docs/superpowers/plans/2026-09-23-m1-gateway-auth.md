# aihub-platform M1（网关直通）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让网关从「无鉴权的 SSE 演示」变成「带 API Key 鉴权的 OpenAI 兼容直通代理」：用任意 OpenAI 兼容 SDK 改 `base_url` + API Key 就能调通，上游的状态码与响应体原样透传。

**Architecture:** 网关中继从 `Flux<ServerSentEvent>` 改为**字节透传代理**（`exchangeToMono` + `ServerHttpResponse.writeAndFlushWith` 复制 `DataBuffer`），因此流式/非流式/上游错误三种情况用同一段代码覆盖，客户端 `Accept` 不再需要归一化。鉴权做成一个 `WebFilter`，密钥解析走 Caffeine → Redis → admin 内部接口三级回源（延续设计文档「决策 A：网关不直连 MySQL」）。密钥的**铸造**是一条本地 CLI 触发路径（不暴露 HTTP），**校验**才走内部接口，内部接口用共享密钥 HMAC 守卫。

**Tech Stack:** Java 21（编译目标）、Spring Boot 3.5.16、WebFlux、MyBatis-Plus 3.5.17、MySQL 8.4、Flyway、Redis 7、Caffeine、JUnit 5 + Testcontainers、Maven 3.9.12。

## Global Constraints

- 项目根目录：`D:\PycharmProjects\aihub-platform`；分支从 `master`（M0 已合并、已打 `m0` 标签）切出，命名为 `m1-gateway-auth`。
- 编译目标固定 Java 21：`<maven.compiler.release>21</maven.compiler.release>`；本机运行 JVM 为 JDK 25.0.2。不要改。
- Spring Boot 固定 `3.5.16`；MyBatis-Plus 固定 `3.5.17`。除本计划明确列出的三个新依赖外，**不要新增依赖，不要写 `<version>`**（版本由 Spring Boot BOM 管理）。
- 包名前缀：`com.aihub.common` / `com.aihub.dao` / `com.aihub.service` / `com.aihub.mq` / `com.aihub.admin` / `com.aihub.gateway`。
- 端口：admin `8081`，gateway `8080`，MySQL 宿主机 `3307`，Redis `6379`，RabbitMQ `5672` / 管理台 `15672`。**M1 不改端口。**
- **两套响应契约，不要混用**：admin 的 HTTP 接口统一 `{"code","message","data"}`（含 `/internal/**`）；网关的 **`/v1/**` 使用 OpenAI 兼容错误体** `{"error":{"message","type","param","code"}}`，不使用 admin 信封。这与 `docs/CONVENTIONS.md` 的 `/v1/**` 用标准 HTTP 状态码的约定一致。
- **API Key 格式固定**：客户端携带 `Authorization: Bearer <key_id>.<secret>`；库中 `key_hash` = `SHA-256(secret)` 的小写十六进制（64 字符）。`key_id` 形如 `ak_` + 16 位随机字符（总长 19，≤ 列宽 32）。
- `/internal/**` 必须经共享密钥 HMAC 守卫（`X-Internal-Timestamp` + `X-Internal-Signature`），且**永远不暴露到公网**；密钥只从环境变量注入。
- `aihub-common` 必须保持**零依赖**。
- `aihub-gateway` **不得依赖 `aihub-admin` 的任何模块**，只共享 `aihub-common`。
- 任何密钥、口令、secret 都不得写进仓库；一律走环境变量或 `.env`（已 gitignore）。
- 每个 Task 完成后立即 commit，conventional commits。每个里程碑打 tag（本里程碑为 `m1`）。
- 命令一律在项目根目录执行；不使用 Maven wrapper，用本机 `mvn`。
- **测试纪律**：网关测试**不得依赖 Docker**（用 JDK 内置 `com.sun.net.httpserver.HttpServer` 做假上游）；admin 集成测试沿用 `AbstractIntegrationTest`（Testcontainers 单例容器）。

### 本机环境前提（写给实施者）

- Docker 只能走 TCP：所有 `docker` CLI 命令必须带 `-H tcp://127.0.0.1:2375`（默认命名管道被沙箱拒绝）。
- Testcontainers 已通过用户级 `~/.testcontainers.properties`（`docker.host=tcp://127.0.0.1:2375`）指向 TCP。**不要动这个文件，也不要给它写 BOM** —— 之前用带 BOM 的写入把它写坏过，导致全部容器测试变红。
- `.mvn/maven.config` 是**未跟踪、已 gitignore** 的本机适配文件（in-tree Maven 仓库 + 测试 JVM 参数 + `-Dsurefire.failIfNoSpecifiedTests=false`）。不要修改、不要 `git add`。
- `Set-Content -Encoding UTF8` 在这个 shell 上**会写 BOM**；需要无 BOM 文件时用 `[System.IO.File]::WriteAllText(path, text, (New-Object System.Text.UTF8Encoding($false)))`。
- 磁盘上已有 `mysql:8.4`、`redis:7-alpine`、`rabbitmq:3.13-management-alpine` 镜像。

---

## File Structure

M1 结束后新增/修改的文件：

| 文件 | 职责 |
|---|---|
| `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java` | **改**：字节透传代理（状态/头/体透传），同时覆盖流式与非流式 |
| `aihub-gateway/src/main/java/com/aihub/gateway/relay/SseAcceptNormalizingFilter.java` | **删除**：字节透传后不再需要 Accept 归一化 |
| `aihub-gateway/src/main/java/com/aihub/gateway/relay/ModelsController.java` | 新增：`GET /v1/models` |
| `aihub-gateway/src/main/java/com/aihub/gateway/error/GatewayErrors.java` | 新增：`/v1/**` 的 OpenAI 兼容错误体构造与写出 |
| `aihub-gateway/src/main/java/com/aihub/gateway/auth/ApiKeyAuthFilter.java` | 新增：鉴权 WebFilter |
| `aihub-gateway/src/main/java/com/aihub/gateway/auth/ApiKeyResolver.java` | 新增：Caffeine → Redis → admin 三级回源 |
| `aihub-gateway/src/main/java/com/aihub/gateway/auth/AuthProperties.java` | 新增：`aihub.auth.*` 与 `aihub.admin.*` 配置 |
| `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java` | 新增：调 admin 内部接口的 WebClient 封装（含 HMAC 头） |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyHasher.java` | 新增：密钥生成与 SHA-256（**admin 与 gateway 共用同一份实现**） |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyView.java` | 新增：密钥视图 record + `usable()` 规则（两服务唯一真相） |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyCacheCodec.java` | 新增：Redis 缓存载荷的紧凑编解码（纯 JDK，不引入 Jackson） |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/internal/InternalHmac.java` | 新增：内部调用 HMAC（**两服务共用同一份实现**） |
| `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamProperties.java` | **改**：新增 `apiKey`、`defaultModel` |
| `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamClientConfig.java` | **改**：注入上游 `Authorization` 头 |
| `aihub-gateway/src/test/java/com/aihub/gateway/testsupport/FakeUpstream.java` | 新增：可复用假上游（JSON / SSE / 任意状态码 / 请求捕获） |
| `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/{ApiKeyEntity,TenantEntity}.java` | 新增：MyBatis-Plus 实体 |
| `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/{ApiKeyMapper,TenantMapper}.java` | 新增：Mapper |
| `aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyService.java` | 新增：铸造与解析用例（含 Redis 缓存写入） |
| `aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyMintRunner.java` | 新增：本地 CLI 触发路径（默认关闭） |
| `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/internal/InternalAuthFilter.java` | 新增：`/internal/**` HMAC 守卫 |
| `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/internal/InternalKeyController.java` | 新增：`POST /internal/api-keys/resolve` |
| `aihub-admin/aihub-web/src/main/java/com/aihub/admin/AihubAdminApplication.java` | **改**：加 `@MapperScan` |
| `aihub-admin/aihub-web/src/main/resources/application.yml` | **改**：内部密钥、密钥缓存 TTL、铸造开关 |
| `docker-compose.yml` | **改**：gateway 服务新增环境变量 |
| `README.md` / `docs/CONVENTIONS.md` | **改**：M1 进度、API Key 用法、`/v1/**` 错误契约、内部 HMAC 约定 |

---

## Task 1: 中继改为字节透传代理（上游状态/头/体透传，顺带支持非流式）

**Files:**
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java`（整体替换）
- Delete: `aihub-gateway/src/main/java/com/aihub/gateway/relay/SseAcceptNormalizingFilter.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/error/GatewayErrors.java`
- Modify: `aihub-gateway/src/test/java/com/aihub/gateway/relay/ChatRelayControllerTest.java`
- Create: `aihub-gateway/src/test/java/com/aihub/gateway/testsupport/FakeUpstream.java`

**Interfaces:**
- Consumes: M0 的 `upstreamWebClient` bean（`UpstreamClientConfig`），`UpstreamProperties.baseUrl()`。
- Produces:
  - `POST /v1/chat/completions` 返回 `Mono<Void>`，自行设置状态码与 `Content-Type`；**上游状态码、上游响应体字节、上游 `Content-Type` 原样透传**。
  - `FakeUpstream`：`FakeUpstream.start()` → `FakeUpstream`；`baseUrl()`；`enqueueSse(String frames)`；`enqueueJson(int status, String body)`；`lastRequest()` → `record CapturedRequest(String method, String path, Map<String,String> headers, String body)`；`stop()`。后续 Task 5 复用。
  - `GatewayErrors.write(ServerHttpResponse, HttpStatus, String type, String code, String message)` → `Mono<Void>`。

- [ ] **Step 1: 写 FakeUpstream 测试夹具**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/testsupport/FakeUpstream.java`：

```java
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
```

- [ ] **Step 2: 把控制器改成字节透传代理**

用下面内容**整体替换** `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java`：

```java
package com.aihub.gateway.relay;

import com.aihub.gateway.error.GatewayErrors;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;

/**
 * 字节透传代理：把请求体原样交给上游，再把上游的**状态码、响应体字节与 Content-Type** 原样交回客户端。
 * <p>因此流式（SSE）与非流式（JSON）用同一段代码覆盖，客户端 {@code Accept} 不参与决策
 * —— 由请求体里的 {@code stream} 字段决定上游返回什么，我们只负责透传。
 * <p>上游的错误响应（401/429/502…）同样原样透传，不再被折叠成通用 500。
 * <p>鉴权、限流、配额、多渠道路由与计量分别在 M1（{@code ApiKeyAuthFilter}）、M2、M3 加在它前面。
 */
@RestController
public class ChatRelayController {

    public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** 需要从上游回传给客户端的具体头；其余（如 Content-Length、Transfer-Encoding）由本服务自行决定。 */
    private static final Set<String> RELAYED_HEADERS = Set.of("x-request-id");

    private final WebClient upstreamWebClient;

    public ChatRelayController(WebClient upstreamWebClient) {
        this.upstreamWebClient = upstreamWebClient;
    }

    @PostMapping(path = CHAT_COMPLETIONS_PATH)
    public Mono<Void> chatCompletions(@RequestBody String body, ServerHttpResponse response) {
        return upstreamWebClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(upstream -> relay(upstream, response))
                .onErrorResume(WebClientRequestException.class, ex -> GatewayErrors.write(response,
                        HttpStatus.BAD_GATEWAY, "api_error", "upstream_unreachable",
                        "上游服务不可达: " + ex.getMessage()));
    }

    private Mono<Void> relay(ClientResponse upstream, ServerHttpResponse response) {
        response.setStatusCode(upstream.statusCode());
        response.getHeaders().setContentType(
                upstream.headers().contentType().orElse(MediaType.APPLICATION_JSON));

        HttpHeaders upstreamHeaders = upstream.headers().asHttpHeaders();
        upstreamHeaders.forEach((name, values) -> {
            if (RELAYED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                response.getHeaders().put(name, values);
            }
        });

        Flux<DataBuffer> body = upstream.bodyToFlux(DataBuffer.class);
        // writeAndFlushWith 逐块 flush：SSE 因此是真流式，而不是攒完再发。
        return response.writeAndFlushWith(body.map(Mono::just));
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/error/GatewayErrors.java`：

```java
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
```

- [ ] **Step 3: 删除 Accept 归一化过滤器**

```powershell
git rm aihub-gateway/src/main/java/com/aihub/gateway/relay/SseAcceptNormalizingFilter.java
```

它存在的原因是「返回类型 `Flux<ServerSentEvent>` 只能产出 `text/event-stream`，与客户端的 `Accept` 冲突」。改成字节透传后我们自行设置 `Content-Type`，冲突消失，因此该过滤器及其 q 值/畸形头/路径精确匹配等遗留问题一并消失。

- [ ] **Step 4: 重写控制器测试**

用下面内容**整体替换** `aihub-gateway/src/test/java/com/aihub/gateway/relay/ChatRelayControllerTest.java`（把原先内联的假上游换成 `FakeUpstream`，并补上游错误透传与非流式用例）：

```java
package com.aihub.gateway.relay;

import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aihub.auth.enabled=false")
class ChatRelayControllerTest {

    private static FakeUpstream upstream;

    @LocalServerPort
    private int gatewayPort;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @DynamicPropertySource
    static void upstreamBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> upstream.baseUrl());
    }

    @Test
    void relaysSseFramesWhenClientAcceptsJson() throws Exception {
        upstream.enqueueSse(FakeUpstream.sseFrames());

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":true}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).contains("text/event-stream"));
        assertThat(response.body()).contains("你").contains("好").contains("[DONE]");
    }

    @Test
    void relaysNonStreamingJsonBody() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).contains("application/json"));
        assertThat(response.body()).contains("\"object\":\"chat.completion\"").contains("你好");
    }

    @Test
    void propagatesUpstreamErrorStatusAndBody() throws Exception {
        upstream.enqueueJson(429, "{\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit_error\"}}");

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                "application/json", "application/json");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("rate limited");
    }

    @Test
    void forwardsRequestBodyVerbatim() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        post("/v1/chat/completions", "{\"stream\":false,\"messages\":[]}", "application/json", null);

        assertThat(upstream.lastRequest().body()).isEqualTo("{\"stream\":false,\"messages\":[]}");
        assertThat(upstream.lastRequest().path()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void malformedAcceptHeaderDoesNotBreakTheRelay() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("/v1/chat/completions", "{\"stream\":false}",
                null, "this-is-not/a-media-type;;;q=x");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> post(String path, String body, String contentType, String accept)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + path))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (accept != null) {
            builder.header("Accept", accept);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
```

- [ ] **Step 5: 运行测试**

```powershell
mvn -B -pl aihub-gateway -am test -Dtest=ChatRelayControllerTest
```

预期：`Tests run: 5, Failures: 0, Errors: 0`。若 `propagatesUpstreamErrorStatusAndBody` 仍得 500，说明 `exchangeToMono` 没被用到（还在用 `retrieve()`）。

- [ ] **Step 6: 跑全量测试**

```powershell
mvn -B clean test
```

预期 `BUILD SUCCESS`。M0 结束时有 18 项；本任务把网关的 4 项替换成 5 项，因此**预期 19 项**。请**自己数一遍 `@Test` 并报告实测数字**，不要照抄这个数字。

- [ ] **Step 7: 提交**

```powershell
git add -A
git commit -m "refactor: relay upstream bytes verbatim so status and body pass through"
```

---

## Task 2: 共享密钥工具（aihub-common）+ 网关依赖与配置

**Files:**
- Modify: `aihub-gateway/pom.xml`
- Modify: `aihub-gateway/src/main/resources/application.yml`
- Create: `aihub-gateway/src/test/resources/application.yml`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamProperties.java`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamClientConfig.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/auth/AuthProperties.java`
- Modify: `docker-compose.yml` 与 `.env.example`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyHasher.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyView.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyCacheCodec.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/internal/InternalHmac.java`

**Interfaces:**
- Consumes: M0 的 `upstreamWebClient`。
- Produces:
  - `AuthProperties`（`@ConfigurationProperties("aihub.auth")`）：`boolean enabled`、`Duration keyCacheTtl`、`Duration localCacheTtl`。
  - `aihub.admin.base-url`、`aihub.admin.internal-secret` 两个配置项（Task 3/4 用）。
  - `UpstreamProperties` 变为 `record UpstreamProperties(String baseUrl, String apiKey, String defaultModel)`。
  - `upstreamWebClient` 在上游 `apiKey` 非空时默认注入 `Authorization: Bearer <apiKey>`。

- [ ] **Step 1: 加依赖**

在 `aihub-gateway/pom.xml` 的 `spring-boot-starter-webflux` 之后插入：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>
```

不要写 `<version>`（BOM 管理）。

- [ ] **Step 2: 改配置属性与客户端**

用下面内容**整体替换** `UpstreamProperties.java`：

```java
package com.aihub.gateway.upstream;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 上游模型服务配置。M1 仍是单渠道（渠道管理是 M4 的事）；
 * M3 引入多渠道后，这里会扩展为「渠道快照」的一部分。
 *
 * @param apiKey       上游自己的密钥；空串表示上游无需鉴权（例如本地 Ollama）
 * @param defaultModel 单渠道场景下 {@code GET /v1/models} 回报的模型名
 */
@Validated
@ConfigurationProperties(prefix = "aihub.upstream")
public record UpstreamProperties(@NotBlank String baseUrl, String apiKey, String defaultModel) {
}
```

用下面内容**整体替换** `UpstreamClientConfig.java`：

```java
package com.aihub.gateway.upstream;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(UpstreamProperties.class)
public class UpstreamClientConfig {

    @Bean
    public WebClient upstreamWebClient(UpstreamProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(120));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        if (StringUtils.hasText(properties.apiKey())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }
        return builder.build();
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/auth/AuthProperties.java`：

```java
package com.aihub.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 鉴权相关配置。
 *
 * @param enabled       是否启用 API Key 鉴权。测试与本地裸跑可置 false；生产必须为 true
 * @param localCacheTtl Caffeine 本地缓存 TTL（二级缓存的第一级）
 * @param keyCacheTtl   Redis 共享缓存 TTL（第二级）
 * @param adminBaseUrl  admin 内部接口地址（第三级回源）
 * @param internalSecret 内部调用共享密钥；为空时内部回源不可用
 */
@ConfigurationProperties(prefix = "aihub.auth")
public record AuthProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("30s") Duration localCacheTtl,
                             @DefaultValue("5m") Duration keyCacheTtl,
                             @DefaultValue("http://127.0.0.1:8081") String adminBaseUrl) {
}
```

内部共享密钥不放在这里：它与 admin 侧同名，统一走 `aihub.internal.secret`（见 Step 7 与 Task 4 的 `AdminClientConfig`），避免两个应用出现两套属性路径。

- [ ] **Step 3: 改 application.yml**

用下面内容**整体替换** `aihub-gateway/src/main/resources/application.yml`（注意 Redis 配置合并进已有的 `spring:` 块，不要出现两个同名顶层键）：

```yaml
server:
  port: 8080

spring:
  application:
    name: aihub-gateway
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:127.0.0.1}
      port: ${SPRING_DATA_REDIS_PORT:6379}
      timeout: 2s

management:
  endpoints:
    web:
      base-path: /
      exposure:
        include: health,info
      path-mapping:
        health: healthz
  endpoint:
    health:
      show-details: always

aihub:
  upstream:
    base-url: ${AIHUB_UPSTREAM_BASE_URL:http://127.0.0.1:11434}
    api-key: ${AIHUB_UPSTREAM_API_KEY:}
    default-model: ${AIHUB_UPSTREAM_DEFAULT_MODEL:default}
  auth:
    enabled: ${AIHUB_AUTH_ENABLED:true}
    local-cache-ttl: 30s
    key-cache-ttl: 5m
    admin-base-url: ${AIHUB_ADMIN_BASE_URL:http://127.0.0.1:8081}
  internal:
    secret: ${AIHUB_INTERNAL_SECRET:}
```

再创建 `aihub-gateway/src/test/resources/application.yml`，让单元测试不需要真 Redis —— 关掉 Redis 健康指示器，`/healthz` 因此仍为 `UP`：

```yaml
management:
  health:
    redis:
      enabled: false
```

- [ ] **Step 4: compose 里给 gateway 补环境变量**

在 `docker-compose.yml` 的 `gateway:` 服务 `environment:` 下追加：

```yaml
      SPRING_DATA_REDIS_HOST: redis
      AIHUB_ADMIN_BASE_URL: http://admin:8081
      AIHUB_INTERNAL_SECRET: ${AIHUB_INTERNAL_SECRET:?set AIHUB_INTERNAL_SECRET in .env}
      AIHUB_UPSTREAM_API_KEY: ${AIHUB_UPSTREAM_API_KEY:-}
```

并在 `.env.example` 里补上：

```ini
# 网关 → admin 内部调用共享密钥（≥ 32 字符随机串）
AIHUB_INTERNAL_SECRET=change-me-internal-secret-change-me
# 上游模型服务自己的密钥；本地 Ollama 留空
AIHUB_UPSTREAM_API_KEY=
```

> ⚠️ **不要改 redis 服务的宿主端口映射。** 它当前是 `6380:6379`，是为了避开本机原生 Windows Redis 占用的 6379 而故意改的（提交 `chore: publish redis on host 6380 ...`）。容器内端口仍是 6379，网关在 compose 网络里走 `SPRING_DATA_REDIS_HOST: redis`，**不受宿主映射影响**。宿主侧客户端（例如 IDEA 的 Redis 数据源）要用 6380。

- [ ] **Step 5: 验证上下文仍能启动**

```powershell
mvn -B -pl aihub-gateway -am test
```

预期：网关的 6 项测试（`AihubGatewayApplicationTests` 1 + `ChatRelayControllerTest` 5）通过，`BUILD SUCCESS`。Step 3 建的测试 `application.yml` 已关掉 Redis 健康指示器，所以这些测试**不需要真 Redis**。若仍有组件因连不上 Redis 而把 `/healthz` 拉成 `DOWN`，在报告里说明具体是哪个指示器，不要把断言删掉了事。

- [ ] **Step 6: 把共享密钥工具放进 `aihub-common`**

把哈希算法、密钥视图与内部 HMAC 放在 `aihub-common`，**admin 与 gateway 共用同一份实现**。这不是偷懒：如果两边各写一份，`CONVENTIONS.md` 就只能写「改一处必须同时改另一处」，而那正是评审会拦下的重复逻辑。这些类只用 JDK（`MessageDigest`、`SecureRandom`、`javax.crypto`、`HexFormat`），因此 `aihub-common` 仍是零依赖。

创建 `ApiKeyHasher.java`：

```java
package com.aihub.common.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** API Key 的生成与哈希。全项目只有这一份实现，admin 铸造与 gateway 校验都用它。 */
public final class ApiKeyHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private ApiKeyHasher() {
    }

    /** 库中存储的 key_hash：secret 的 SHA-256 小写十六进制（64 字符，正好匹配 CHAR(64)）。 */
    public static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 未提供 SHA-256", e);
        }
    }

    /** 32 字节随机 secret，URL 安全 Base64（无填充）。 */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 形如 {@code ak_xxxxxxxxxxxxxxxx}，总长 19，安全落在 key_id VARCHAR(32) 内。 */
    public static String newKeyId() {
        StringBuilder sb = new StringBuilder("ak_");
        for (int i = 0; i < 16; i++) {
            sb.append(KEY_ID_ALPHABET.charAt(RANDOM.nextInt(KEY_ID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
```

创建 `ApiKeyView.java`：

```java
package com.aihub.common.apikey;

import java.time.Instant;

/**
 * 密钥视图：MySQL 真相源、Redis 缓存载荷、admin 内部接口响应、gateway 解析结果**共用同一个类型**，
 * 因此「什么样的密钥算可用」只有一处定义。
 */
public record ApiKeyView(String keyId, long tenantId, String tenantName, String status, Instant expireAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public boolean usable() {
        return STATUS_ACTIVE.equals(status) && (expireAt == null || expireAt.isAfter(Instant.now()));
    }
}
```

创建 `ApiKeyCacheCodec.java`：

```java
package com.aihub.common.apikey;

import java.time.Instant;

/**
 * Redis 缓存载荷的紧凑编解码：{@code keyId|tenantId|tenantName|status|expireAtEpochSecond}。
 * 用自定义格式而不是 JSON，是因为 {@code aihub-common} 必须保持零依赖（不能引 Jackson），
 * 而这里的字段固定且都由本类写入。
 */
public final class ApiKeyCacheCodec {

    private static final String DELIMITER = "|";
    private static final int FIELD_COUNT = 5;

    private ApiKeyCacheCodec() {
    }

    public static String encode(ApiKeyView view) {
        return escape(view.keyId()) + DELIMITER
                + view.tenantId() + DELIMITER
                + escape(view.tenantName()) + DELIMITER
                + escape(view.status()) + DELIMITER
                + (view.expireAt() == null ? "" : String.valueOf(view.expireAt().getEpochSecond()));
    }

    /** 格式非法时返回 {@code null}，调用方应视作缓存未命中并回源，而不是抛错。 */
    public static ApiKeyView decode(String payload) {
        if (payload == null) {
            return null;
        }
        String[] parts = payload.split("(?<!\\\\)\\" + DELIMITER, -1);
        if (parts.length != FIELD_COUNT) {
            return null;
        }
        try {
            String expireAt = unescape(parts[4]);
            return new ApiKeyView(unescape(parts[0]), Long.parseLong(parts[1]), unescape(parts[2]),
                    unescape(parts[3]), expireAt.isEmpty() ? null : Instant.ofEpochSecond(Long.parseLong(expireAt)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace(DELIMITER, "\\" + DELIMITER);
    }

    private static String unescape(String value) {
        return value.replace("\\" + DELIMITER, DELIMITER).replace("\\\\", "\\");
    }
}
```

创建 `InternalHmac.java`：

```java
package com.aihub.common.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 内部调用的最小守卫：共享密钥 HMAC。admin 与 gateway 共用这一份实现。
 * 签名内容为 {@code timestamp + "\n" + METHOD + "\n" + path}，配合 ±300 秒时间窗防重放。
 * body 不参与签名 —— M1 的内部接口只传一个哈希，body 签名留待需要时再加。
 */
public final class InternalHmac {

    private static final String ALGORITHM = "HmacSHA256";

    private InternalHmac() {
    }

    public static String sign(String secret, String timestamp, String method, String path) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String payload = timestamp + "\n" + method.toUpperCase() + "\n" + path;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算内部签名", e);
        }
    }

    /** 常量时间比较，避免按字节提前返回泄露信息。 */
    public static boolean verify(String secret, String timestamp, String method, String path, String signature) {
        if (signature == null || timestamp == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(secret, timestamp, method, path).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 7: 提交**

```powershell
git add -A
git commit -m "feat: share api-key tooling in common and configure the gateway"
```

---

## Task 3: admin 侧密钥铸造与内部解析接口

**Files:**
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ApiKeyEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/TenantEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/ApiKeyMapper.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/TenantMapper.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyService.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyMintRunner.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/internal/InternalAuthFilter.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/internal/InternalKeyController.java`
- Modify: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/AihubAdminApplication.java`
- Modify: `aihub-admin/aihub-web/src/main/resources/application.yml`
- Modify: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/support/AbstractIntegrationTest.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/apikey/ApiKeyMintAndResolveTest.java`

**Interfaces:**
- Consumes: M0 的 `aihub-dao`（MyBatis-Plus + MySQL）、admin 的 Redis 连接、`ApiResponse`，以及 **Task 2 放进 `aihub-common` 的** `ApiKeyHasher` / `ApiKeyView` / `ApiKeyCacheCodec` / `InternalHmac`。
- Produces:
  - `ApiKeyService.mint(String tenantName, String keyName, Instant expireAt)` → `ApiKeyService.IssuedKey(String token, String keyId)`，`token = keyId + "." + secret`。
  - `ApiKeyService.resolve(String keyHash)` → `Optional<com.aihub.common.apikey.ApiKeyView>`（Redis 优先，未命中回源 MySQL 并回填）。
  - `POST /internal/api-keys/resolve`：请求 `{"keyHash":"..."}`，命中返回 admin 信封 `{"code":"OK","message":"success","data":{keyId,tenantId,tenantName,status,expireAt}}`，未命中 404 + `NOT_FOUND`。
  - 内部 HMAC 头：`X-Internal-Timestamp`（epoch 秒）与 `X-Internal-Signature`，算法见 `com.aihub.common.internal.InternalHmac`；时间窗 ±300 秒。

- [ ] **Step 1: 写失败测试**

创建 `aihub-admin/aihub-web/src/test/java/com/aihub/admin/apikey/ApiKeyMintAndResolveTest.java`：

```java
package com.aihub.admin.apikey;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.internal.InternalHmac;
import com.aihub.service.apikey.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyMintAndResolveTest extends AbstractIntegrationTest {

    private static final String TEST_SECRET = "test-internal-secret-test-internal-secret";
    private static final String RESOLVE_PATH = "/internal/api-keys/resolve";

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void mintedKeyIsResolvableBySecretHashAndNeverStoresPlaintext() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-mint", "key-1", null);
        String secret = secretOf(issued);

        Optional<ApiKeyView> resolved = apiKeyService.resolve(ApiKeyHasher.hash(secret));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().keyId()).isEqualTo(issued.keyId());
        assertThat(resolved.get().tenantName()).isEqualTo("t-mint");
        assertThat(resolved.get().usable()).isTrue();
        assertThat(apiKeyService.resolve(ApiKeyHasher.hash("wrong-secret"))).isEmpty();
    }

    @Test
    void expiredKeyResolvesButIsNotUsable() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-exp", "key-exp", Instant.now().minusSeconds(60));

        ApiKeyView view = apiKeyService.resolve(ApiKeyHasher.hash(secretOf(issued))).orElseThrow();

        assertThat(view.usable()).isFalse();
    }

    @Test
    void internalResolveEndpointRequiresValidSignature() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-http", "key-http", null);
        String body = "{\"keyHash\":\"" + ApiKeyHasher.hash(secretOf(issued)) + "\"}";

        ResponseEntity<String> unsigned = restTemplate.exchange(RESOLVE_PATH, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(unsigned.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<String> signed = restTemplate.exchange(RESOLVE_PATH, HttpMethod.POST,
                new HttpEntity<>(body, signedHeaders("POST", RESOLVE_PATH)), String.class);
        assertThat(signed.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(signed.getBody()).contains("\"code\":\"OK\"").contains(issued.keyId());
    }

    @Test
    void staleTimestampIsRejected() {
        String body = "{\"keyHash\":\"" + ApiKeyHasher.hash("whatever") + "\"}";
        String stale = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());
        HttpHeaders headers = jsonHeaders();
        headers.add("X-Internal-Timestamp", stale);
        headers.add("X-Internal-Signature", InternalHmac.sign(TEST_SECRET, stale, "POST", RESOLVE_PATH));

        ResponseEntity<String> response = restTemplate.exchange(RESOLVE_PATH, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    private String secretOf(ApiKeyService.IssuedKey issued) {
        return issued.token().substring(issued.token().indexOf('.') + 1);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders signedHeaders(String method, String path) {
        HttpHeaders headers = jsonHeaders();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        headers.add("X-Internal-Timestamp", timestamp);
        // body 不参与签名 —— M1 的最小内部守卫只防未授权调用，body 签名留待需要时再加
        headers.add("X-Internal-Signature", InternalHmac.sign(TEST_SECRET, timestamp, method, path));
        return headers;
    }
}
```

在 `AbstractIntegrationTest` 的 `@DynamicPropertySource` 中补一行（其余保持不变）：

```java
        registry.add("aihub.internal.secret", () -> "test-internal-secret-test-internal-secret");
```

- [ ] **Step 2: 运行测试，确认失败**

```powershell
mvn -B -pl aihub-admin/aihub-web -am test -Dtest=ApiKeyMintAndResolveTest
```

预期：编译失败（`ApiKeyService` 尚不存在，`aihub-dao` 里还没有 `ApiKeyEntity` / `TenantEntity` 与对应 Mapper）。

- [ ] **Step 3: 写实体与 Mapper**

创建 `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ApiKeyEntity.java`：

```java
package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** 对应 Flyway V1 的 {@code api_key} 表。明文 secret 从不入库，只存 SHA-256。 */
@TableName("api_key")
public class ApiKeyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String keyId;
    private Long tenantId;
    private String keyHash;
    private String name;
    private String status;
    private Instant expireAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = keyHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Instant expireAt) {
        this.expireAt = expireAt;
    }
}
```

创建 `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/TenantEntity.java`（结构同上，`@TableName("tenant")`，字段 `id` / `name` / `status`）。

创建两个 Mapper：

```java
package com.aihub.dao.mapper;

import com.aihub.dao.entity.ApiKeyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface ApiKeyMapper extends BaseMapper<ApiKeyEntity> {
}
```

```java
package com.aihub.dao.mapper;

import com.aihub.dao.entity.TenantEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface TenantMapper extends BaseMapper<TenantEntity> {
}
```

修改 `AihubAdminApplication.java`，在类上加：

```java
import org.mybatis.spring.annotation.MapperScan;
...
@MapperScan("com.aihub.dao.mapper")
```

- [ ] **Step 4: 写哈希工具、服务与铸造 Runner**

`ApiKeyHasher` 已在 Task 2 Step 6 放进 `aihub-common` 的 `com.aihub.common.apikey` 包（两个服务共用一份），本 Task **不要**重复创建它。

创建 `ApiKeyService.java`：

```java
package com.aihub.service.apikey;

import com.aihub.common.apikey.ApiKeyCacheCodec;
import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.dao.entity.ApiKeyEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.ApiKeyMapper;
import com.aihub.dao.mapper.TenantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * API Key 的铸造与解析。
 * <p>铸造：生成 key_id 与 secret，只把 {@code SHA-256(secret)} 入库，明文只在返回值里出现一次。
 * <p>解析：Redis 缓存优先，未命中回源 MySQL 并回填 —— 缓存是可丢的派生数据，MySQL 才是真相源。
 * <p>缓存载荷用 {@link ApiKeyCacheCodec} 而不是 JSON：gateway 要读同一份数据，
 * 而 {@code aihub-common} 必须保持零依赖。
 * <p>密钥视图与「是否可用」的规则都来自 {@link ApiKeyView}，两个服务共用同一个定义。
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final String CACHE_PREFIX = "aihub:apikey:";

    private final ApiKeyMapper apiKeyMapper;
    private final TenantMapper tenantMapper;
    private final StringRedisTemplate redis;
    private final Duration cacheTtl;

    public ApiKeyService(ApiKeyMapper apiKeyMapper, TenantMapper tenantMapper,
                         StringRedisTemplate redis,
                         @Value("${aihub.apikey.cache-ttl:5m}") Duration cacheTtl) {
        this.apiKeyMapper = apiKeyMapper;
        this.tenantMapper = tenantMapper;
        this.redis = redis;
        this.cacheTtl = cacheTtl;
    }

    public record IssuedKey(String token, String keyId) {
    }

    @Transactional
    public IssuedKey mint(String tenantName, String keyName, Instant expireAt) {
        TenantEntity tenant = findOrCreateTenant(tenantName);

        String keyId = ApiKeyHasher.newKeyId();
        String secret = ApiKeyHasher.newSecret();
        String keyHash = ApiKeyHasher.hash(secret);

        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId(keyId);
        entity.setTenantId(tenant.getId());
        entity.setKeyHash(keyHash);
        entity.setName(keyName);
        entity.setStatus(ApiKeyView.STATUS_ACTIVE);
        entity.setExpireAt(expireAt);
        apiKeyMapper.insert(entity);

        cache(keyHash, new ApiKeyView(keyId, tenant.getId(), tenantName, ApiKeyView.STATUS_ACTIVE, expireAt));
        log.info("已铸造 API Key keyId={} tenant={} name={}", keyId, tenantName, keyName);
        return new IssuedKey(keyId + "." + secret, keyId);
    }

    public Optional<ApiKeyView> resolve(String keyHash) {
        ApiKeyView cached = readCache(keyHash);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<ApiKeyView> fromDb = loadFromDb(keyHash);
        fromDb.ifPresent(view -> cache(keyHash, view));
        return fromDb;
    }

    private Optional<ApiKeyView> loadFromDb(String keyHash) {
        ApiKeyEntity entity = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKeyEntity>()
                .eq(ApiKeyEntity::getKeyHash, keyHash));
        if (entity == null) {
            return Optional.empty();
        }
        TenantEntity tenant = tenantMapper.selectById(entity.getTenantId());
        String tenantName = tenant == null ? "" : tenant.getName();
        return Optional.of(new ApiKeyView(entity.getKeyId(), entity.getTenantId(), tenantName,
                entity.getStatus(), entity.getExpireAt()));
    }

    private TenantEntity findOrCreateTenant(String tenantName) {
        TenantEntity existing = tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getName, tenantName));
        if (existing != null) {
            return existing;
        }
        TenantEntity created = new TenantEntity();
        created.setName(tenantName);
        created.setStatus(ApiKeyView.STATUS_ACTIVE);
        tenantMapper.insert(created);
        return created;
    }

    /** 缓存读写都吞掉异常：Redis 不可用时降级回 MySQL，而不是让鉴权失败。 */
    private void cache(String keyHash, ApiKeyView view) {
        try {
            redis.opsForValue().set(CACHE_PREFIX + keyHash, ApiKeyCacheCodec.encode(view), cacheTtl);
        } catch (RuntimeException e) {
            log.warn("写入密钥缓存失败，忽略: {}", e.toString());
        }
    }

    private ApiKeyView readCache(String keyHash) {
        try {
            return ApiKeyCacheCodec.decode(redis.opsForValue().get(CACHE_PREFIX + keyHash));
        } catch (RuntimeException e) {
            log.warn("读取密钥缓存失败，回源 MySQL: {}", e.toString());
            return null;
        }
    }
}
```

创建 `ApiKeyMintRunner.java`（**本地触发路径，默认关闭**）：

```java
package com.aihub.service.apikey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 「最小内部签发」的触发路径：不是 HTTP 接口，因此不会在公网上出现造密钥的入口。
 * 用法（默认关闭）：
 * <pre>
 * java -jar aihub-web.jar --aihub.mint-key.enabled=true \
 *      --aihub.mint-key.tenant-name=demo --aihub.mint-key.name=my-first-key
 * </pre>
 * 明文 token 只打印一次，之后无法再取回（库里只有哈希）。
 * 控制台的完整签发/列表/吊销接口属于 M4。
 */
@Component
public class ApiKeyMintRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyMintRunner.class);

    private final ApiKeyService apiKeyService;
    private final boolean enabled;
    private final String tenantName;
    private final String keyName;
    private final long validDays;

    public ApiKeyMintRunner(ApiKeyService apiKeyService,
                            @Value("${aihub.mint-key.enabled:false}") boolean enabled,
                            @Value("${aihub.mint-key.tenant-name:demo}") String tenantName,
                            @Value("${aihub.mint-key.name:default}") String keyName,
                            @Value("${aihub.mint-key.valid-days:365}") long validDays) {
        this.apiKeyService = apiKeyService;
        this.enabled = enabled;
        this.tenantName = tenantName;
        this.keyName = keyName;
        this.validDays = validDays;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Instant expireAt = validDays <= 0 ? null : Instant.now().plus(validDays, ChronoUnit.DAYS);
        ApiKeyService.IssuedKey issued = apiKeyService.mint(tenantName, keyName, expireAt);
        log.warn("""

                ==================== API KEY ISSUED (仅显示一次) ====================
                token    : {}
                keyId    : {}
                tenant   : {}
                有效期至 : {}
                ====================================================================
                """, issued.token(), issued.keyId(), tenantName, expireAt);
    }
}
```

`InternalHmac` 同样已在 Task 2 Step 6 放进 `aihub-common` 的 `com.aihub.common.internal` 包（gateway 与 admin 共用一份），本 Task **不要**重复创建它。`InternalAuthFilter` 直接用 `com.aihub.common.internal.InternalHmac.verify(...)`。

- [ ] **Step 5: 写内部守卫与解析接口**

创建 `InternalAuthFilter.java`（Spring MVC 的 `OncePerRequestFilter`）：

```java
package com.aihub.admin.web.internal;

import com.aihub.common.internal.InternalHmac;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/** 守卫 /internal/**：缺签名或不合法一律 401。该前缀永远不应该暴露到公网。 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final long MAX_SKEW_SECONDS = 300;

    private final String secret;

    public InternalAuthFilter(@Value("${aihub.internal.secret:}") String secret) {
        this.secret = secret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String timestamp = request.getHeader("X-Internal-Timestamp");
        String signature = request.getHeader("X-Internal-Signature");
        if (secret == null || secret.isBlank() || !fresh(timestamp)
                || !InternalHmac.verify(secret, timestamp, request.getMethod(), request.getRequestURI(), signature)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"invalid internal signature\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean fresh(String timestamp) {
        try {
            long skew = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
            return skew <= MAX_SKEW_SECONDS;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
```

创建 `InternalKeyController.java`：

```java
package com.aihub.admin.web.internal;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.service.apikey.ApiKeyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 网关回源用的内部接口。调用方必须带合法内部签名（见 InternalAuthFilter）。 */
@RestController
@RequestMapping("/internal/api-keys")
public class InternalKeyController {

    private final ApiKeyService apiKeyService;

    public InternalKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    public record ResolveRequest(String keyHash) {
    }

    /** 直接返回共享的 {@link ApiKeyView}，避免再定义一层只有 admin 才认识的 DTO。 */
    @PostMapping("/resolve")
    public ResponseEntity<ApiResponse<ApiKeyView>> resolve(@RequestBody ResolveRequest request) {
        if (request == null || request.keyHash() == null || request.keyHash().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail(ErrorCode.INVALID_PARAM, "keyHash 不能为空"));
        }
        return apiKeyService.resolve(request.keyHash())
                .map(view -> ResponseEntity.ok(ApiResponse.ok(view)))
                .orElseGet(() -> ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
                        .body(ApiResponse.fail(ErrorCode.NOT_FOUND, "key not found")));
    }
}
```

在 admin 的 `application.yml` 的 `spring:` 同级追加：

```yaml
aihub:
  internal:
    secret: ${AIHUB_INTERNAL_SECRET:}
  apikey:
    cache-ttl: 5m
  mint-key:
    enabled: ${AIHUB_MINT_KEY_ENABLED:false}
```

- [ ] **Step 6: 运行测试**

```powershell
mvn -B -pl aihub-admin/aihub-web -am test -Dtest=ApiKeyMintAndResolveTest
```

预期：`Tests run: 3, Failures: 0, Errors: 0`。若签名校验得 401，先确认 `InternalAuthFilter` 用的 `request.getRequestURI()` 与测试签名里的 path 完全一致（含前导 `/`、不含 query）。

- [ ] **Step 7: 跑全量测试并提交**

```powershell
mvn -B clean test
git add -A
git commit -m "feat: mint api keys in admin and expose an internal resolve endpoint"
```

---

## Task 4: 网关鉴权过滤器（Caffeine → Redis → admin 回源）

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClientConfig.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/auth/ApiKeyResolver.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/auth/ApiKeyAuthFilter.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/auth/ApiKeyAuthFilterTest.java`

**Interfaces:**
- Consumes: Task 2 的 `AuthProperties`、Task 3 的 `POST /internal/api-keys/resolve` 与 `InternalHmac.sign(...)` 约定、Task 1 的 `GatewayErrors`。
- Produces:
  - `ApiKeyView`（来自 `aihub-common`）：`keyId, tenantId, tenantName, status, expireAt` 五个分量 + `usable()` 方法。
  - `AdminClient.resolve(String keyHash)` → `Mono<Optional<ApiKeyView>>`（含内部签名头）。
  - `ApiKeyAuthFilter`：`/v1/**` 之外直接放行；缺/畸形/无效/过期/停用密钥 → 401 + OpenAI 错误体（`code=invalid_api_key`，`type=invalid_request_error`）；通过时把 `ApiKeyView` 写入 exchange 属性 `ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW`。
  - 三级缓存：Caffeine（`localCacheTtl`）→ Redis（`aihub:apikey:{hash}`，`keyCacheTtl`）→ admin。

- [ ] **Step 1: 写失败测试**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/auth/ApiKeyAuthFilterTest.java`（本测试**不依赖 Docker**：Redis 与 admin 都用假实现，`aihub.auth.enabled=true`，Redis 指向一个不存在的端口使缓存永远 miss，从而强制走 admin。为避免把测试搞成集成测试，用 `@SpringBootTest` + 一个 `@TestConfiguration` 提供假的 `AdminClient`）：

```java
package com.aihub.gateway.auth;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyAuthFilterTest {

    private static FakeUpstream upstream;

    @LocalServerPort
    private int gatewayPort;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> upstream.baseUrl());
        registry.add("aihub.auth.enabled", () -> "true");
        registry.add("aihub.internal.secret", () -> "test-internal-secret");
        // 指向不存在的 Redis 端口：缓存必然 miss，强制走 AdminClient（本测试用假实现）
        registry.add("spring.data.redis.port", () -> "1");
    }

    @TestConfiguration
    static class FakeAdmin {
        @Bean
        @Primary
        AdminClient adminClient() {
            return keyHash -> {
                if (keyHash.equals(TestKeys.VALID_HASH)) {
                    return Mono.just(Optional.of(new ApiKeyView("ak_valid", 7L, "t", "ACTIVE", null)));
                }
                if (keyHash.equals(TestKeys.EXPIRED_HASH)) {
                    return Mono.just(Optional.of(new ApiKeyView("ak_exp", 7L, "t", "ACTIVE",
                            Instant.now().minusSeconds(60))));
                }
                return Mono.just(Optional.empty());
            };
        }
    }

    static final class TestKeys {
        static final String VALID_SECRET = "valid-secret";
        static final String EXPIRED_SECRET = "expired-secret";
        static final String VALID_HASH = sha256Hex(VALID_SECRET);
        static final String EXPIRED_HASH = sha256Hex(EXPIRED_SECRET);
    }

    @Test
    void missingApiKeyIsRejectedWithOpenAiErrorBody() throws Exception {
        HttpResponse<String> response = post(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"invalid_api_key\"");
        assertThat(response.body()).contains("\"type\":\"invalid_request_error\"");
    }

    @Test
    void unknownApiKeyIsRejected() throws Exception {
        assertThat(post("Bearer ak_x.unknown").statusCode()).isEqualTo(401);
    }

    @Test
    void expiredApiKeyIsRejected() throws Exception {
        assertThat(post("Bearer ak_exp." + TestKeys.EXPIRED_SECRET).statusCode()).isEqualTo(401);
    }

    @Test
    void validApiKeyReachesUpstream() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("Bearer ak_valid." + TestKeys.VALID_SECRET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chat.completion");
    }

    @Test
    void healthEndpointIsNotGuarded() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/healthz"))
                .GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> post(String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":false}"));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```powershell
mvn -B -pl aihub-gateway -am test -Dtest=ApiKeyAuthFilterTest
```

预期：编译失败（`AdminClient` / `ApiKeyView` / `ApiKeyAuthFilter` 不存在）。

- [ ] **Step 3: 写 admin 客户端与解析器**

`ApiKeyView` 同样来自 `aihub-common`（Task 2 Step 6），本 Task 不要重复定义。

创建 `AdminClient.java`：

```java
package com.aihub.gateway.admin;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.internal.InternalHmac;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;

/**
 * 调 admin 内部接口。网关不直连 MySQL（设计文档决策 A），密钥的真相源在 admin。
 * <p>签名算法必须与 admin 的 InternalHmac 一致；两处都改了才算改对。
 */
public class AdminClient {

    private static final Logger log = LoggerFactory.getLogger(AdminClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOLVE_PATH = "/internal/api-keys/resolve";

    private final WebClient webClient;
    private final String internalSecret;

    public AdminClient(WebClient webClient, String internalSecret) {
        this.webClient = webClient;
        this.internalSecret = internalSecret;
    }

    public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = InternalHmac.sign(internalSecret, timestamp, "POST", RESOLVE_PATH);

        return webClient.post()
                .uri(RESOLVE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Signature", signature)
                .bodyValue("{\"keyHash\":\"" + keyHash + "\"}")
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> parse(response.statusCode().value(), body)))
                .onErrorResume(ex -> {
                    log.warn("admin 内部接口调用失败: {}", ex.toString());
                    return Mono.just(Optional.empty());
                });
    }

    private Optional<ApiKeyView> parse(int status, String body) {
        if (status < 200 || status >= 300) {
            return Optional.empty();
        }
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            if (data.isMissingNode() || data.isNull()) {
                return Optional.empty();
            }
            JsonNode expireAt = data.path("expireAt");
            return Optional.of(new ApiKeyView(
                    data.path("keyId").asText(),
                    data.path("tenantId").asLong(),
                    data.path("tenantName").asText(),
                    data.path("status").asText(),
                    expireAt.isNull() || expireAt.isMissingNode() ? null : Instant.parse(expireAt.asText())));
        } catch (Exception e) {
            log.warn("解析 admin 响应失败: {}", e.toString());
            return Optional.empty();
        }
    }
}
```

创建 `ApiKeyResolver.java`：

```java
package com.aihub.gateway.auth;

import com.aihub.common.apikey.ApiKeyCacheCodec;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.admin.AdminClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 三级解析：Caffeine（本地，最快）→ Redis（跨实例共享）→ admin（真相源）。
 * <p>任何一级不可用都必须降级到下一级：缓存是可丢的派生数据，绝不能因为缓存故障而拒绝请求。
 * <p>Redis 载荷用 {@link ApiKeyCacheCodec} 编解码，与 admin 写入的格式必须一致 —— 两边都用
 * aihub-common 里的同一个 codec，因此不存在"两套格式要对齐"的问题。
 */
@Component
public class ApiKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyResolver.class);
    private static final String CACHE_PREFIX = "aihub:apikey:";

    private final Cache<String, ApiKeyView> local;
    private final StringRedisTemplate redis;
    private final AdminClient adminClient;
    private final Duration keyCacheTtl;

    public ApiKeyResolver(AuthProperties properties, AdminClient adminClient, StringRedisTemplate redis) {
        this.local = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(properties.localCacheTtl())
                .build();
        this.redis = redis;
        this.adminClient = adminClient;
        this.keyCacheTtl = properties.keyCacheTtl();
    }

    public Mono<ApiKeyView> resolve(String keyHash) {
        ApiKeyView cached = local.getIfPresent(keyHash);
        if (cached != null) {
            return Mono.just(cached);
        }
        ApiKeyView fromRedis = readRedis(keyHash);
        if (fromRedis != null) {
            local.put(keyHash, fromRedis);
            return Mono.just(fromRedis);
        }
        return adminClient.resolve(keyHash).map(maybeView -> {
            ApiKeyView view = maybeView.orElse(MISS);
            local.put(keyHash, view);
            if (view != MISS) {
                writeRedis(keyHash, view);
            }
            return view;
        });
    }

    /** 负缓存：同一个不存在的 key 不必每次都打 admin。usable() 为 false，调用方据此 401。 */
    private static final ApiKeyView MISS = new ApiKeyView("", 0L, "", "MISSING", null);
    private static final String MISS_PAYLOAD = ApiKeyCacheCodec.encode(MISS);

    private ApiKeyView readRedis(String keyHash) {
        try {
            String payload = redis.opsForValue().get(CACHE_PREFIX + keyHash);
            if (payload == null) {
                return null;
            }
            return MISS_PAYLOAD.equals(payload) ? MISS : ApiKeyCacheCodec.decode(payload);
        } catch (RuntimeException e) {
            log.warn("Redis 读取失败，降级回源 admin: {}", e.toString());
            return null;
        }
    }

    private void writeRedis(String keyHash, ApiKeyView view) {
        try {
            redis.opsForValue().set(CACHE_PREFIX + keyHash, ApiKeyCacheCodec.encode(view), keyCacheTtl);
        } catch (RuntimeException e) {
            log.warn("Redis 写入失败，忽略: {}", e.toString());
        }
    }
}
```

- [ ] **Step 4: 写鉴权过滤器**

创建 `ApiKeyAuthFilter.java`：

```java
package com.aihub.gateway.auth;

import com.aihub.gateway.error.GatewayErrors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * API Key 鉴权。只守 {@code /v1/**}；{@code /healthz} 与未来其它运维端点不设防（健康检查由网关/容器发起，
 * 不会带密钥）。
 * <p>通过后把解析结果写进 exchange 属性，供 M2 的计量与 M3 的配额复用。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ApiKeyAuthFilter implements WebFilter {

    public static final String ATTRIBUTE_KEY_VIEW = "aihub.apiKeyView";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthProperties properties;
    private final ApiKeyResolver resolver;

    public ApiKeyAuthFilter(AuthProperties properties, ApiKeyResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!properties.enabled() || !path.startsWith("/v1/")) {
            return chain.filter(exchange);
        }

        String secret = extractSecret(exchange.getRequest().getHeaders().getFirst("Authorization"));
        if (secret == null) {
            return unauthorized(exchange, "缺少 API Key：请在 Authorization 头里带 Bearer <key_id>.<secret>");
        }

        return resolver.resolve(sha256Hex(secret))
                .flatMap(view -> {
                    if (view == null || !view.usable()) {
                        return unauthorized(exchange, "API Key 无效、已过期或已停用");
                    }
                    exchange.getAttributes().put(ATTRIBUTE_KEY_VIEW, view);
                    return chain.filter(exchange);
                });
    }

    /** 客户端携带 {@code <key_id>.<secret>}；只有 secret 部分参与哈希。 */
    private String extractSecret(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        return token.substring(dot + 1);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        return GatewayErrors.write(exchange.getResponse(), HttpStatus.UNAUTHORIZED,
                "invalid_request_error", "invalid_api_key", message);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("JVM 未提供 SHA-256", e);
        }
    }
}
```

新建 `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClientConfig.java`，把 `AdminClient` 注册成 bean（`WebClient` 用 `aihub.auth.admin-base-url` 构造，超时 2 秒）：

```java
package com.aihub.gateway.admin;

import com.aihub.gateway.auth.AuthProperties;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AdminClientConfig {

    @Bean
    public AdminClient adminClient(AuthProperties properties,
                                   @Value("${aihub.internal.secret:}") String internalSecret) {
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.adminBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
                        .responseTimeout(Duration.ofSeconds(3))))
                .build();
        return new AdminClient(webClient, internalSecret);
    }
}
```

同时把 `AuthProperties` 从 `UpstreamClientConfig` 上解绑（若 Task 2 里把 `@EnableConfigurationProperties(AuthProperties.class)` 写在了别处，保留一处即可，不要重复注册导致两个 bean）。

- [ ] **Step 5: 运行测试**

```powershell
mvn -B -pl aihub-gateway -am test -Dtest=ApiKeyAuthFilterTest
```

预期：`Tests run: 5, Failures: 0, Errors: 0`。

- [ ] **Step 6: 跑全量测试并提交**

```powershell
mvn -B clean test
git add -A
git commit -m "feat: authenticate gateway requests with api keys"
```

---

## Task 5: `GET /v1/models` 与上游契约测试

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/relay/ModelsController.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/relay/ModelsControllerTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/relay/ChatRelayContractTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/relay/UnreachableUpstreamTest.java`

**Interfaces:**
- Consumes: `UpstreamProperties.defaultModel()`（Task 2）、`FakeUpstream`（Task 1）、`aihub.auth.enabled=false`（测试）。
- Produces: `GET /v1/models` → `{"object":"list","data":[{"id":"<defaultModel>","object":"model","owned_by":"aihub"}]}`。

- [ ] **Step 1: 写失败测试**

创建 `ModelsControllerTest.java`：

```java
package com.aihub.gateway.relay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=false", "aihub.upstream.default-model=m1-test-model"})
class ModelsControllerTest {

    @LocalServerPort
    private int gatewayPort;

    @Test
    void listsTheConfiguredModel() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + gatewayPort + "/v1/models")).GET().build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"object\":\"list\"").contains("m1-test-model");
    }
}
```

创建 `ChatRelayContractTest.java`：

```java
package com.aihub.gateway.relay;

import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aihub.auth.enabled=false")
class ChatRelayContractTest {

    private static FakeUpstream upstream;

    @LocalServerPort
    private int gatewayPort;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @DynamicPropertySource
    static void upstreamBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> upstream.baseUrl());
    }

    @Test
    void propagatesUpstream401StatusAndBody() throws Exception {
        upstream.enqueueJson(401,
                "{\"error\":{\"message\":\"bad upstream key\",\"type\":\"invalid_request_error\"}}");

        HttpResponse<String> response = post();

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("bad upstream key");
    }

    @Test
    void propagatesUpstream502StatusAndBody() throws Exception {
        upstream.enqueueJson(502, "{\"error\":{\"message\":\"upstream boom\",\"type\":\"api_error\"}}");

        HttpResponse<String> response = post();

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("upstream boom");
    }

    @Test
    void sseFramesArriveInOrder() throws Exception {
        upstream.enqueueSse(FakeUpstream.sseFrames());

        HttpResponse<String> response = post();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().indexOf("你")).isLessThan(response.body().indexOf("好"));
        assertThat(response.body()).contains("[DONE]");
    }

    private HttpResponse<String> post() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":false}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
```

创建 `UnreachableUpstreamTest.java`（上游不可达无法逐用例切换 base-url，因此单独一个类，用自己的 `@DynamicPropertySource` 指向一个已关闭端口）：

```java
package com.aihub.gateway.relay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=false", "aihub.upstream.base-url=http://127.0.0.1:1"})
class UnreachableUpstreamTest {

    @LocalServerPort
    private int gatewayPort;

    @Test
    void upstreamConnectionFailureBecomes502WithOpenAiErrorBody() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":false}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("\"code\":\"upstream_unreachable\"");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```powershell
mvn -B -pl aihub-gateway -am test -Dtest='ModelsControllerTest,ChatRelayContractTest'
```

预期：编译失败（`ModelsController` 不存在）。

- [ ] **Step 3: 实现 `/v1/models`**

```java
package com.aihub.gateway.relay;

import com.aihub.gateway.upstream.UpstreamProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 单渠道场景下只回报一个模型；多渠道与渠道级模型列表属于 M3。 */
@RestController
public class ModelsController {

    private final UpstreamProperties properties;

    public ModelsController(UpstreamProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/v1/models")
    public Mono<Map<String, Object>> listModels() {
        Map<String, Object> model = Map.of(
                "id", properties.defaultModel(),
                "object", "model",
                "owned_by", "aihub");
        return Mono.just(Map.of(
                "object", "list",
                "data", List.of(model)));
    }
}
```

- [ ] **Step 4: 运行测试**

```powershell
mvn -B -pl aihub-gateway -am test
```

预期：网关全部测试通过。报告**实测**总数。

- [ ] **Step 5: 提交**

```powershell
git add -A
git commit -m "feat: expose the configured model list on the gateway"
```

---

## Task 6: 文档收口、手工真上游验收与里程碑标签

**Files:**
- Modify: `README.md`
- Modify: `docs/CONVENTIONS.md`
- Modify: `.env.example`（若 Task 2 已改则确认）

**Interfaces:**
- Consumes: Task 1–5 的全部产出。
- Produces: 更新后的 M1 进度与用法文档；`m1` 标签（**由控制器在手工验收后打，不由实施者打**）。

- [ ] **Step 1: 更新 `docs/CONVENTIONS.md`**

补三节：
1. **数据面错误契约**：`/v1/**` 用 OpenAI 兼容错误体 `{"error":{"message","type","param","code"}}`，与 admin 的 `{code,message,data}` 信封严格区分；网关自身产生的错误码至少包括 `invalid_api_key`（401）、`upstream_unreachable`（502）、`internal_error`（500）。
2. **内部接口约定**：`/internal/**` 必须带 `X-Internal-Timestamp` + `X-Internal-Signature`（算法见 `InternalHmac`），永不暴露公网；签名算法在 admin 与 gateway 各有一份实现，**改一处必须同时改另一处**。
3. **API Key 约定**：客户端 bearer 为 `<key_id>.<secret>`；库中只存 `SHA-256(secret)`；明文只在铸造时打印一次；铸造走 `ApiKeyMintRunner`（`--aihub.mint-key.enabled=true`），控制台接口属于 M4。

- [ ] **Step 2: 更新 `README.md`**

- 把 M1 一行改为已完成，并写清它到底做了什么（鉴权 + 单渠道 + 非流式 + 上游状态/体透传）。
- 新增「调用方式」小节：给出 `curl` 与 OpenAI SDK（Python/Node 任一）改 `base_url=http://localhost:8080/v1` + `api_key=<minted token>` 的示例。
- 新增「签发 API Key」小节：给出 `--aihub.mint-key.enabled=true --aihub.mint-key.tenant-name=demo --aihub.mint-key.name=my-key` 的完整命令与"明文只显示一次"的说明。
- 更新「M0 已知边界」：**删掉已被 M1 解决的三条**（relay 只有 SSE、无鉴权、上游错误未透传），保留仍然成立的（`/healthz` 明细暴露、`request_log` 分区维护），并补 M1 仍然没做的（限流/配额/计量/多渠道路由/M4 控制台）。
- 把端口说明改准：**Redis 宿主端口是 6380**（容器内仍 6379），原因是本机原生 Redis 占用 6379；`docs/CONVENTIONS.md` 与 README 里凡写 `Redis 6379` 的地方都要区分"宿主 / 容器内"。
- 更新测试数量为**你实测的数字**。

- [ ] **Step 3: 全量测试**

```powershell
mvn -B clean test
```

预期 `BUILD SUCCESS`，`Failures: 0, Errors: 0`。报告实测数字。

- [ ] **Step 4: 提交**

```powershell
git add -A
git commit -m "docs: document m1 authentication and the data-plane error contract"
```

- [ ] **Step 5: 手工端到端验收（真上游）**

由控制器执行（需要真实上游与真跑的进程，实施者若无法完成就报告 BLOCKED 并交给控制器）：

1. 起 Redis 与 MySQL（compose：`docker compose up -d mysql redis`；本机原生 Redis 与本项目要求版本不兼容时，先停掉原生服务）。
2. 起 admin：`java -jar aihub-admin/aihub-web/target/aihub-web-0.0.1-SNAPSHOT.jar --aihub.mint-key.enabled=true --aihub.mint-key.tenant-name=demo --aihub.mint-key.name=m1-demo --aihub.internal.secret=<32+ 随机串>`，从日志里抄下 token。
3. 起 gateway：`AIHUB_AUTH_ENABLED=true AIHUB_INTERNAL_SECRET=<同一个串> AIHUB_UPSTREAM_BASE_URL=<真上游> AIHUB_UPSTREAM_API_KEY=<上游 key> java -jar aihub-gateway/target/aihub-gateway-0.0.1-SNAPSHOT.jar`。
4. 用 OpenAI 兼容客户端（或等价 curl）打 `http://localhost:8080/v1`：
   - `GET /v1/models` 带 `Authorization: Bearer <token>` → 200 且列出模型；
   - `POST /v1/chat/completions`（`stream:false`）→ 200 + 真实回答；
   - 去掉 Authorization → 401 且错误体是 OpenAI 形状；
   - 故意发一个上游会拒绝的请求 → 上游状态码被透传（不是 500）。
5. 记录实际输出。真上游首选本机 Ollama（`http://127.0.0.1:11434`，`AIHUB_UPSTREAM_API_KEY` 留空）；若用云厂商 key，**不要**把 key 写进任何文件。

- [ ] **Step 6: 打标签（仅控制器）**

手工验收通过后由控制器执行：

```powershell
git tag -a m1 -m "M1 网关直通完成：API Key 鉴权（Caffeine→Redis→admin 三级回源）+ 单渠道 + 非流式 + 上游状态/响应体透传；用 OpenAI 兼容客户端改 base_url 实测调通。"
```

---

## 附：M1 不做的事（写进文档，避免范围蔓延）

- 限流（Redis + Lua 令牌桶）、配额预扣、计量与对账 —— M2/M3
- 多渠道、权重路由、熔断与故障转移 —— M3
- 租户/渠道/配额管理、审计、账单、管理台 —— M4
- 文档上传与向量化流水线，以及 `POST /v1/embeddings` —— M5（设计文档 §7.1 列了它，但 M1 只做 chat 直通）
- 压测与故障注入报告 —— M6
- 请求日志落库（`request_log`）—— M2
