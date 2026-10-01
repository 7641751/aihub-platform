package com.aihub.gateway.admin;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.QuotaDescriptor;
import com.aihub.common.config.RatePolicy;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.common.internal.InternalHmac;
import com.aihub.gateway.testsupport.FakeAdminServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * admin 的配置快照接口在**网关这一侧**的契约：签名请求 + 响应解析。
 *
 * <p>为什么必须有这个类：{@code AdminClient.Http.parseSnapshot} 此前**没有任何执行覆盖**
 * ——它按字面键名读 JSON（{@code data.channels[].apiKeyCipher} 等），一旦 admin 侧的序列化形态变了
 * （重命名 record 分量、换 naming strategy、包一层只有 admin 认识的 DTO），网关**不会报错**，
 * 只会静默拿到一份空快照并回落遗留单渠道。这里用「Jackson 序列化共享 record」这件与 admin 完全相同的
 * 机制造出响应体，再让它过一遍真实解析器，等价于一次线上往返。
 *
 * <p>无 Docker、无 Redis（{@link FakeAdminServer} 是 JDK 内置 HttpServer）。
 * 密文由运行期生成的合成主密钥产出：tracked 文件里没有明文密钥 / 密文 / 主密钥字面量。
 */
class AdminClientSnapshotContractTest {

    private static final String SECRET = "test-internal-secret";
    private static final String SYNTHETIC_PLAINTEXT = "sk-channel-plaintext-synthetic";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static FakeAdminServer admin;

    @BeforeAll
    static void startAdmin() {
        admin = FakeAdminServer.start();
    }

    @AfterAll
    static void stopAdmin() {
        admin.stop();
    }

    @Test
    void theSignedGetOnTheSnapshotPathReturnsTheParsedSnapshot() throws Exception {
        ConfigSnapshot source = sourceSnapshot();
        admin.enqueueJson(200, envelope(source));

        Optional<ConfigSnapshot> parsed = client(SECRET).configSnapshot().block();

        assertThat(parsed).as("200 + 完整信封必须被解析成快照").isPresent();
        assertThat(parsed.orElseThrow()).as("解析结果必须与 admin 组装的那份逐字段相等").isEqualTo(source);

        FakeAdminServer.CapturedRequest request = admin.lastRequest();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.rawPath())
                .as("打的必须是应用内路径（不含 context path / base-url 前缀）")
                .isEqualTo(AdminClient.CONFIG_SNAPSHOT_PATH);
        // admin 的 InternalAuthFilter 会用同一个 secret + 应用内路径 + GET 复算签名。
        assertThat(InternalHmac.verify(SECRET, request.headers().get("x-internal-timestamp"), "GET",
                AdminClient.CONFIG_SNAPSHOT_PATH, request.headers().get("x-internal-signature"))).isTrue();
    }

    /**
     * 「admin 发的 JSON → 网关解析器」的往返，密文包含在内。响应体由 Jackson 序列化共享 record 得到
     * ——与 admin 的 Spring MVC 用同一套机制，因此这条断言同时钉住了两侧共享的键名与结构。
     */
    @Test
    void theEnvelopeTheAdminAssemblesRoundTripsThroughTheParserWithTheCipherTextVerbatim() throws Exception {
        ConfigSnapshot source = sourceSnapshot();
        String cipher = source.channels().get(0).apiKeyCipher();

        Optional<ConfigSnapshot> parsed = AdminClient.Http.parseSnapshot(200, envelope(source));

        assertThat(parsed).isPresent();
        ConfigSnapshot snapshot = parsed.orElseThrow();
        assertThat(snapshot).isEqualTo(source);
        assertThat(snapshot.channels()).hasSize(1);

        ChannelDescriptor channel = snapshot.channels().get(0);
        assertThat(channel.apiKeyCipher())
                .as("密文必须逐字透传（网关要靠它解密上游密钥）").isEqualTo(cipher);
        assertThat(channel.apiKeyCipher()).doesNotContain(SYNTHETIC_PLAINTEXT);
        assertThat(new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey()))
                .decrypt(channel.apiKeyCipher()))
                .as("从 admin 发来的串必须能被网关侧实现解开").contains(SYNTHETIC_PLAINTEXT);
        assertThat(channel.keyVersion()).isEqualTo(1);
        assertThat(channel.timeoutMs()).isEqualTo(30_000);
        assertThat(channel.usable()).isTrue();

        ModelRouteDescriptor route = snapshot.routes().get(0);
        assertThat(route.modelName()).isEqualTo("demo-model");
        assertThat(route.channelId()).isEqualTo(channel.id());
        assertThat(route.weight()).isEqualTo(120);
        assertThat(route.priority()).isEqualTo(3);

        // 两个维度都要能取到：租户级（apiKeyId 是 JSON null）与 key 级（apiKeyId 是数字）。
        assertThat(snapshot.tenantPolicies(7L)).extracting(RatePolicy::qps).containsExactly(20);
        assertThat(snapshot.keyPolicies(7L, 42L)).extracting(RatePolicy::qps).containsExactly(5);
        assertThat(snapshot.defaultModel()).isEqualTo("demo-model");
    }

    /** fail-closed：非 2xx / 垃圾响应体 / 2xx 但没有 data 一律折算成「没有快照」，绝不抛。 */
    @Test
    void nonSuccessOrMalformedBodiesDegradeToNoSnapshot() {
        assertThat(AdminClient.Http.parseSnapshot(401,
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"invalid internal signature\",\"data\":null}")).isEmpty();
        assertThat(AdminClient.Http.parseSnapshot(500,
                "{\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\",\"data\":null}")).isEmpty();
        assertThat(AdminClient.Http.parseSnapshot(200, "not json at all")).isEmpty();
        assertThat(AdminClient.Http.parseSnapshot(200,
                "{\"code\":\"OK\",\"message\":\"success\",\"data\":null}")).isEmpty();
    }

    // --- 夹具 -----------------------------------------------------------

    /** 与 admin 的 {@code ApiResponse.ok(snapshot)} 同一形态的信封。 */
    private static String envelope(ConfigSnapshot snapshot) throws Exception {
        return "{\"code\":\"OK\",\"message\":\"success\",\"data\":"
                + MAPPER.writeValueAsString(snapshot) + "}";
    }

    private static ConfigSnapshot sourceSnapshot() {
        String cipher = new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey()))
                .encrypt(SYNTHETIC_PLAINTEXT);
        return new ConfigSnapshot(1_700_000_000_123L, 1_700_000_000_456L,
                List.of(new ChannelDescriptor(11L, "demo-primary", "http://127.0.0.1:11434", cipher, 1,
                        30_000, ChannelDescriptor.STATUS_ACTIVE, 100, 0)),
                List.of(new ModelRouteDescriptor("demo-model", 11L, 120, 3,
                        ModelRouteDescriptor.STATUS_ACTIVE)),
                List.of(new RatePolicy(7L, null, 20, 40), new RatePolicy(7L, 42L, 5, 10)),
                "demo-model",
                // ⚠️ **Task 13（2026-10-01 评审 Critical-1 的修复）**：夹具**必须**携带额度。
                // 用空 `quotas` 做夹具时，两侧都是空表 ⇒ `isEqualTo` **恒绿**，于是「网关解析器丢弃
                // `data.quotas`」这个缺口被**静默掩盖**（评审用 V-6 诊断变异实测证伪：夹具带上额度后，
                // 旧实现下 `quotas=[]` 而期望非空）。
                // 同一租户放**两个不同 period**：跨周期取错额度是这条链路最危险的失效模式。
                List.of(new QuotaDescriptor(7L, "202601", 1_000L, 4L),
                        new QuotaDescriptor(7L, "202602", 2_000L, 8L)));
    }

    private static AdminClient client(String secret) {
        return AdminClient.http(WebClient.builder().baseUrl(admin.baseUrl()).build(), secret);
    }

    private static String masterKey() {
        return "v1:" + b64Key(19);
    }

    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
