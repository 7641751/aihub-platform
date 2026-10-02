package com.aihub.gateway.quota;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.QuotaDescriptor;
import com.aihub.common.quota.QuotaDecision;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配额链**端到端**：真实 HTTP 穿过真实过滤器链（含 {@link QuotaFilter}），到假上游、再回到客户端。
 *
 * <p>为什么必须有这一条：{@link QuotaFilterTest} 自己 {@code new} 过滤器，因此它对「过滤器压根不在
 * 链上」这类故障完全免疫。本类不构造任何配额组件（{@code @Primary} 替身只覆盖判定结果），只发真实 HTTP、
 * 只读响应，因此它红就等于「线上配额这一跳不存在」。
 *
 * <p><b>Redis 指向死端口</b>（{@code spring.data.redis.port=1}，与 M1/M2/M3 既有做法一致）：本类证明的
 * 因此是**降级路径**上的端到端配额 —— 「Redis 挂了也照样放行」；「Redis 里桶最终等于真实用量」那条
 * 由 **admin 侧真 Redis** 集成测试负责（网关测试永远不允许依赖 Docker，CONVENTIONS §8 item 3）。
 *
 * <p><b>字节透传（D17 + M1 铁律）</b>：{@code QuotaFilter} 为了估算必须读请求体，而下游（路由、转发）
 * 也必须能读到**同一份字节**。{@link #cachedBodyStillReachesTheUpstreamByteForByte()} 用真实上游收到的
 * body 做整体相等断言 —— 一个「释放了 buffer 还传原 exchange」的实现会让上游收到空体，立刻变红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.quota.enabled=true", "aihub.auth.enabled=false"})
@Import({MeteringTestConfig.class, QuotaDegradeTest.FakeAdmin.class, QuotaDegradeTest.StubLimiterConfig.class})
class QuotaDegradeTest {

    private static FakeUpstream upstream;

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private StubLimiter limiter;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void defaultToWithinBudget() {
        // 每个用例自行决定判定结果；默认放行（本类大多数用例只关心字节透传与过滤器是否在链上）。
        limiter.decision = new QuotaDecision(true, 1L, -1L);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> upstream.baseUrl());
        registry.add("aihub.upstream.default-model", () -> "quota-model");
        registry.add("aihub.internal.secret", () -> "test-internal-secret");
        // 死端口 = Redis 不可用（配额降级路径正是本类要证的）。
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("spring.data.redis.timeout", () -> "500ms");
        // 一个成功的快照必须能覆盖本类的全部用例；且快照里的额度行要按**当前** period 命中。
        registry.add("aihub.config.local-ttl", () -> "60s");
    }

    /**
     * Redis 不可用（{@code QuotaLimiter} 返回 {@code null}）时请求**必须放行**：配额是记账，
     * 不能因为记账组件坏了就拒绝付费客户（D7）。放行的同时必须有 {@code aihub.quota.degraded} 计数。
     */
    @Test
    void redisDownAllowsTheRequestAndCountsADegrade() throws Exception {
        limiter.decision = null;                      // = 「Redis 这一级不可用」
        upstream.enqueueJson(200, FakeUpstream.completionJson());
        double degradedBefore = meterRegistry.counter(QuotaFilter.DEGRADED_METRIC).count();

        HttpResponse<String> response = post("{\"model\":\"quota-model\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chat.completion");
        assertThat(meterRegistry.counter(QuotaFilter.DEGRADED_METRIC).count() - degradedBefore)
                .as("Redis 不可用必须被计数（它是「配额保护失效」的对外信号）").isEqualTo(1.0);
        assertThat(meterRegistry.find(QuotaFilter.SCRIPT_ERROR_METRIC).counter())
                .as("降级不得被计成脚本错误").isNull();
    }

    /**
     * 配额这一跳打开时，上游收到的字节必须与客户端发出的**完全一致**（D17 + M1 铁律）。
     * <p>body 含多字节 UTF-8 与首尾空格：任何字节丢失、重排、截断都会让整体相等断言失败。
     */
    @Test
    void cachedBodyStillReachesTheUpstreamByteForByte() throws Exception {
        String sent = "{\"model\":\"quota-model\",\"messages\":[{\"role\":\"user\",\"content\":\"  你好世界  \"}],"
                + "\"max_tokens\":16,\"stream\":false}";
        upstream.clearLastRequest();
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post(sent);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(upstream.lastRequest()).as("请求必须真的到了上游").isNotNull();
        assertThat(upstream.lastRequest().body())
                .as("缓存请求体后上游必须收到**逐字节**原样的 body（释放了还传原 exchange 会让它是空的）")
                .isEqualTo(sent);
        assertThat(upstream.lastRequest().body().getBytes(StandardCharsets.UTF_8))
                .as("按字节比较同样相等（UTF-8 往返无损）")
                .isEqualTo(sent.getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 假 admin：一条遗留渠道（{@code ConfigClient.usable()} 要求快照里至少有一条渠道，否则整份快照被
     * 当成「空快照」丢弃）+ 一条租户 0（鉴权关闭时的匿名维度）本周期额度。
     */
    @TestConfiguration
    static class FakeAdmin {
        @Bean
        @Primary
        AdminClient adminClient() {
            return new AdminClient() {
                @Override
                public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
                    return Mono.just(Optional.empty());
                }

                @Override
                public Mono<Optional<ConfigSnapshot>> configSnapshot() {
                    return Mono.just(Optional.of(snapshot()));
                }

                private ConfigSnapshot snapshot() {
                    ChannelDescriptor legacy = new ChannelDescriptor(LegacyChannel.ID, "legacy-single-channel",
                            upstream.baseUrl(), null, 0, 60_000, ChannelDescriptor.STATUS_ACTIVE, 100, 0);
                    QuotaDescriptor quota = new QuotaDescriptor(0L, QuotaPeriod.of(System.currentTimeMillis()),
                            100L, 0L);
                    return new ConfigSnapshot(1L, 1L, List.of(legacy), List.of(), List.of(), "quota-model",
                            List.of(quota));
                }
            };
        }
    }

    /** {@code @Primary} 判定替身：本类不需要 Redis（网关测试不允许依赖 Docker）。 */
    @TestConfiguration
    static class StubLimiterConfig {
        @Bean
        @Primary
        StubLimiter stubLimiter() {
            return new StubLimiter();
        }
    }

    /** 可编排的判定替身：{@code null} = Redis 不可用。 */
    static final class StubLimiter implements QuotaLimiter {
        private volatile QuotaDecision decision = new QuotaDecision(true, 1L, -1L);

        @Override
        public QuotaDecision reserve(QuotaDescriptor limits, long estimatedTokens) {
            return decision;
        }

        @Override
        public void adjust(long tenantId, String period, long estimatedTokens, long actualTokens) {
            // 不碰 Redis。
        }
    }
}
