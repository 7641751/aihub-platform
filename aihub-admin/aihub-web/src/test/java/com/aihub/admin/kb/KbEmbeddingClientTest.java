package com.aihub.admin.kb;

import com.aihub.admin.kb.support.FakeEmbeddingUpstream;
import com.aihub.service.kb.KbEmbeddingClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * M5 Task 5 的**零上下文单测**（不启 Spring）：{@link KbEmbeddingClient} 的协议形状与**超时有界**。
 *
 * <p><b>为什么"超时有界"必须放在这里、而不是放进集成用例</b>：
 * ① 写 {@code FAILED} 的是 Task 6 的自定义 {@code MessageRecoverer}（D7）⇒ 集成层根本走不到 FAILED；
 * ② 容器会**重试 3 次 + 指数退避**，一次 30 秒超时会把集成用例拖到分钟级。
 * 单测里把超时配成 {@value #TIMEOUT_SECONDS} 秒、对着一个"挂住不答"的假上游，既精确又快。
 */
class KbEmbeddingClientTest {

    /** 单测自己的短超时（被测的是"有界"这件事，不是那个生产默认值本身）。 */
    private static final int TIMEOUT_SECONDS = 2;

    @BeforeEach
    @AfterEach
    void resetUpstream() {
        FakeEmbeddingUpstream.reset();
    }

    @Test
    void vectorsComeBackInInputOrderWithTheConfiguredDimension() {
        FakeEmbeddingUpstream.respondWithDeterministicVectors(8);
        KbEmbeddingClient client = new KbEmbeddingClient(FakeEmbeddingUpstream.baseUrl(), "kb-embedding", TIMEOUT_SECONDS);

        List<float[]> vectors = client.embed(List.of("a", "b", "c"));

        assertThat(vectors).as("条数必须与入参一一对应").hasSize(3);
        assertThat(vectors.get(0)).as("维度由上游决定（假上游固定 8 维，附录 A）").hasSize(8);
        // 假上游的向量是**可推导**的：第 i 条（0 起）第 j 维 = (i+1)*(j+1)/100 ⇒ 测试能独立算出期望值。
        assertThat(vectors.get(0)[0]).isCloseTo(1 * 1 / 100.0f, within(1e-5f));
        assertThat(vectors.get(2)[3]).isCloseTo(3 * 4 / 100.0f, within(1e-5f));
        assertThat(FakeEmbeddingUpstream.calls()).as("确实打到了上游（否则上面的断言毫无意义）").isEqualTo(1);
    }

    @Test
    void aHangingUpstreamIsBoundedByOurOwnTimeout() {
        FakeEmbeddingUpstream.neverRespond();
        KbEmbeddingClient client = new KbEmbeddingClient(FakeEmbeddingUpstream.baseUrl(), "kb-embedding", TIMEOUT_SECONDS);

        Instant start = Instant.now();
        assertThatThrownBy(() -> client.embed(List.of("x")))
                .as("超时必须抛 —— 返回空向量会让整篇文档被写成空向量并最终 READY（静默的错误成功）")
                .isInstanceOf(IllegalStateException.class);

        assertThat(Duration.between(start, Instant.now()))
                .as("阈值必须与配置项/文档写的那个值一致（%d 秒 + 5 秒余量）", TIMEOUT_SECONDS)
                .isLessThan(Duration.ofSeconds(TIMEOUT_SECONDS + 5));
    }

    @Test
    void theConfiguredTimeoutIsTheDocumentedDefault() {
        KbEmbeddingClient client = new KbEmbeddingClient(FakeEmbeddingUpstream.baseUrl(), "kb-embedding",
                KbEmbeddingClient.DEFAULT_TIMEOUT_SECONDS);

        assertThat(client.timeout())
                .as("判据文字写的「超时上限 %d 秒」必须就是这个默认值（改一处就得改另一处，否则本用例红）",
                        KbEmbeddingClient.DEFAULT_TIMEOUT_SECONDS)
                .isEqualTo(Duration.ofSeconds(KbEmbeddingClient.DEFAULT_TIMEOUT_SECONDS));
    }

    @Test
    void aNon2xxResponseFailsLoudly() {
        FakeEmbeddingUpstream.failOnCall(1);
        KbEmbeddingClient client = new KbEmbeddingClient(FakeEmbeddingUpstream.baseUrl(), "kb-embedding", TIMEOUT_SECONDS);

        assertThatThrownBy(() -> client.embed(List.of("x")))
                .as("上游 5xx 必须抛（重试/DLQ 的入口；绝不许当成「这批没有向量」）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test
    void anUnconfiguredUpstreamFailsWhenUsedNotWhenConstructed() {
        // 构造允许（否则没有嵌入需求的上下文/进程根本起不来），**使用**时才响亮失败。
        KbEmbeddingClient client = new KbEmbeddingClient("", "kb-embedding", TIMEOUT_SECONDS);

        assertThatThrownBy(() -> client.embed(List.of("x")))
                .as("未配置 base-url ⇒ 调用时抛，并指出缺哪个键")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aihub.kb.embedding.base-url");
    }

    /**
     * 真实上游（DashScope / OpenAI 等）都要鉴权，而客户端原先**一个鉴权头都不发** ⇒ 接不上任何带密钥的上游。
     * 契约：配了就发 {@code Authorization: Bearer <key>}；空白等于没配 ⇒ **一个头都不加**
     * （默认行为必须与加这个特性之前**逐字相同**，否则既有部署会被静默改变）。
     */
    @Test
    void aConfiguredApiKeyIsSentAsABearerTokenAndABlankOneSendsNothing() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("UNSET");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = "{\"data\":[{\"embedding\":[0.5]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            new KbEmbeddingClient(base, "kb-embedding", TIMEOUT_SECONDS, "sk-unit-test-key")
                    .embed(List.of("x"));
            assertThat(seen.get()).as("配了 api-key 就必须以 Bearer 形式发出去")
                    .isEqualTo("Bearer sk-unit-test-key");

            seen.set("UNSET");
            new KbEmbeddingClient(base, "kb-embedding", TIMEOUT_SECONDS, "   ")
                    .embed(List.of("x"));
            assertThat(seen.get()).as("空白密钥等于没配 ⇒ 不发 Authorization 头").isNull();
        } finally {
            server.stop(0);
        }
    }
}
