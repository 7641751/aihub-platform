package com.aihub.admin.config;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.common.internal.InternalHmac;
import com.aihub.service.channel.ChannelKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /internal/config/snapshot} 的**线上契约**：真容器 + 真 HMAC + 真 JSON。
 *
 * <p>未签名 / 错签名 / 用别的密钥签名一律 401（fail-closed），签名口径与
 * {@link com.aihub.admin.web.internal.InternalAuthFilter} 一致（应用内路径 + GET + 时间戳）。
 *
 * <p><b>为什么这里要逐字段核对 JSON 的键名</b>：网关侧的解码器
 * （{@code AdminClient.Http.parseSnapshot}）是按**字面键名**读 JSON 的
 * （{@code data.channels[].apiKeyCipher} 等）。两侧唯一的编译期纽带是共享 record，而
 * 「Spring 把 record 序列化成什么」是可以被悄悄改掉的（自定义 naming strategy、
 * 只有 admin 认识的 DTO、{@code @JsonIgnore}…），改错以后网关**不会报错**，只会静默拿到
 * 一份空快照并回落遗留单渠道。因此本类用网关解码器的**同一组字面路径**把响应体走一遍，
 * 并断言每个 JSON 对象的键集合**恰好**是网关要读的那些（多一个少一个都红）。
 *
 * <p>密文用真实的 {@link ChannelKeyService#encrypt} 产出，主密钥由字节算出（tracked 文件里
 * 没有任何明文密钥 / 密文 / 主密钥字面量）；INACTIVE 行则用来钉住 G5 在网络边界上的表现。
 */
class InternalConfigSnapshotIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET = "test-internal-secret-test-internal-secret";
    private static final String PATH = "/internal/config/snapshot";
    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";
    private static final String SYNTHETIC_PLAINTEXT = "sk-channel-plaintext-synthetic";

    /**
     * 额度用例独有的周期（Task 13）。用一个**不会与别的用例相撞**的 {@code YYYYMM}，
     * 使「本用例的额度行在线上」这条断言可以按 {@code (tenantId, period)} 定向查。
     */
    private static final String QUOTA_PERIOD = "202601";

    /** 与网关侧 {@code new ObjectMapper()} 同款：不共享 admin 的配置，独立地读一遍响应体。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChannelKeyService keys;

    @BeforeEach
    void cleanBefore() {
        keys = new ChannelKeyService("v1:" + b64Key(23));
        deleteEverything();
    }

    @AfterEach
    void cleanAfter() {
        deleteEverything();
    }

    @Test
    void signedRequestReturnsTheSnapshotInTheAdminEnvelope() {
        ResponseEntity<String> response = get(signed());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"code\":\"OK\"").contains("\"message\"")
                .contains("\"data\"");
    }

    @Test
    void unsignedRequestIsUnauthorized() {
        ResponseEntity<String> response = get(new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).as("未签名时也必须回 admin 信封").contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void wrongSignatureIsUnauthorized() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Timestamp", timestamp);
        headers.set("X-Internal-Signature", "deadbeef");

        assertThat(get(headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRequestSignedWithAForeignSecretIsUnauthorized() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Timestamp", timestamp);
        headers.set("X-Internal-Signature", InternalHmac.sign("另一个密钥", timestamp, "GET", PATH));

        assertThat(get(headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * 网关解码器的**线上往返**（本模块能做到的最强形式）：
     * 用 {@code AdminClient.Http.parseSnapshot} 读的那一组字面路径遍历真实响应体，
     * 断言值来自真实数据、密文逐字相等、且 INACTIVE 行根本没出现在线上。
     */
    @Test
    void theResponseBodyCarriesExactlyThePathsTheGatewayParserReads() throws Exception {
        String cipher = insertChannel("wire-active", ACTIVE);
        insertChannel("wire-inactive", INACTIVE);
        Long channelId = jdbcTemplate.queryForObject(
                "select id from channel where name = ?", Long.class, "wire-active");
        insertRoute("wire-active-model", channelId, 120, 3, ACTIVE);
        insertRoute("wire-inactive-model", channelId, 1, 9, INACTIVE);
        Long tenantId = insertTenant("wire-tenant");
        insertPolicy(tenantId, null, 20, 40, ACTIVE);
        insertPolicy(tenantId, null, 999, 999, INACTIVE);
        insertPolicy(tenantId, 42L, 5, 10, ACTIVE);
        // Task 13：额度行用**本用例独有的 (tenantId, period)**；`quota` 表是 JVM 级共享的，
        // 别的用例也在往里写 ⇒ 下面的断言**只按 (tenantId, period) 定向查，绝不数组计数**。
        insertQuota(tenantId, QUOTA_PERIOD, 1_000L, 7L);

        JsonNode envelope = MAPPER.readTree(get(signed()).getBody());
        JsonNode data = envelope.path("data");

        // ① 顶层信封 + data 的键集合（= ConfigSnapshot 的**七个**分量，网关逐个按名读）。
        //    **Task 13 新增 `quotas`**：额度是「控制面写、数据面读」的镜像，网关不连数据库，
        //    这是额度到达数据面的唯一通路（`ConfigSnapshotService` / `ConfigSnapshot#quota`）。
        assertThat(envelope.path("code").asText()).isEqualTo("OK");
        assertThat(names(data)).containsExactlyInAnyOrder(
                "version", "generatedAtEpochMilli", "channels", "routes", "ratePolicies", "defaultModel",
                "quotas");
        // 指代明确化（2026-09-29）：这里的"决策 5"是 **M3**（2026-09-23-m3-traffic-governance.md）的决策 5 ——
        // 「快照 version = max(三张配置表的 updated_at) 折算成 epoch 毫秒；没有任何配置行时为 0」。
        // 因为它，本断言才成立：插入了真实配置行 ⇒ updated_at 被推进 ⇒ version 必须为正，而 0 只表示"一条都没有"。
        // M4 的 D5 在这个时间戳之上又加了 config_version 水位（version = max(max(updated_at), config_version.version)），
        // 结论不变（仍然为正）。原注释只写"决策 5"，跨里程碑的编号无法区分是 M2/M3/M4 的哪一条。
        assertThat(data.path("version").asLong())
                .as("M3 决策 5（+ M4 D5 的水位）：真实数据写进去以后版本必须为正，0 只代表没有任何配置行")
                .isPositive();
        assertThat(data.path("generatedAtEpochMilli").asLong()).isPositive();

        // ② 渠道段：键名与 AdminClient.Http.parseSnapshot 里读的九个键逐一对应，密文逐字相等。
        JsonNode channels = data.path("channels");
        assertThat(channels).as("G5：INACTIVE 渠道行不得出现在线上").hasSize(1);
        JsonNode channel = channels.get(0);
        assertThat(names(channel)).containsExactlyInAnyOrder(
                "id", "name", "baseUrl", "apiKeyCipher", "keyVersion", "timeoutMs", "status", "weight", "priority");
        assertThat(channel.path("name").asText()).isEqualTo("wire-active");
        assertThat(channel.path("baseUrl").asText()).isEqualTo("https://wire-active.example.com");
        assertThat(channel.path("apiKeyCipher").asText())
                .as("密文必须原样透出（网关要靠它解密上游密钥）").isEqualTo(cipher);
        assertThat(channel.path("apiKeyCipher").asText()).doesNotContain(SYNTHETIC_PLAINTEXT);
        assertThat(new AesGcmChannelCipher(ChannelKeyRegistry.parse("v1:" + b64Key(23)))
                .decrypt(channel.path("apiKeyCipher").asText()))
                .as("线上透出的密文必须能被网关侧实现解开").contains(SYNTHETIC_PLAINTEXT);
        assertThat(channel.path("keyVersion").asInt()).isEqualTo(1);
        assertThat(channel.path("timeoutMs").asInt()).isEqualTo(60_000);
        assertThat(channel.path("status").asText()).isEqualTo(ACTIVE);
        assertThat(channel.path("id").asLong()).isEqualTo(channelId);

        // ③ 路由段：键名 + 路由级的 weight/priority（不是 channel 的默认值）。
        JsonNode routes = data.path("routes");
        assertThat(routes).as("G5：INACTIVE 路由行不得出现在线上").hasSize(1);
        JsonNode route = routes.get(0);
        assertThat(names(route)).containsExactlyInAnyOrder(
                "modelName", "channelId", "weight", "priority", "status");
        assertThat(route.path("modelName").asText()).isEqualTo("wire-active-model");
        assertThat(route.path("channelId").asLong()).isEqualTo(channelId);
        assertThat(route.path("weight").asInt()).isEqualTo(120);
        assertThat(route.path("priority").asInt()).isEqualTo(3);

        // ④ 限流段：两个维度都要在（key 级靠数值 apiKeyId 区分），INACTIVE 行一条都不许有。
        JsonNode policies = data.path("ratePolicies");
        assertThat(policies).as("G5：INACTIVE 策略行不得出现在线上（两个维度都算）").hasSize(2);
        assertThat(names(policies.get(0))).containsExactlyInAnyOrder("tenantId", "apiKeyId", "qps", "burst");
        assertThat(policies.get(0).path("apiKeyId").isNull())
                .as("租户级策略的 apiKeyId 必须是 JSON null（网关按 isNumber() 判维度）").isTrue();
        assertThat(policies.get(0).path("tenantId").asLong()).isEqualTo(tenantId);
        assertThat(policies.get(0).path("qps").asInt()).isEqualTo(20);
        assertThat(policies.get(1).path("apiKeyId").asLong()).isEqualTo(42L);
        assertThat(policies.get(1).path("qps").asInt()).isEqualTo(5);

        // ⑤ 额度段（Task 13）：键名与 `ConfigSnapshot#quota` / `QuotaDescriptor` 逐一对应，
        //    且**只搬额度、不搬已用量**（token_used/request_used 是数据面的账，不许进快照）。
        JsonNode quotas = data.path("quotas");
        JsonNode mine = null;
        for (JsonNode q : quotas) {
            if (q.path("tenantId").asLong() == tenantId && QUOTA_PERIOD.equals(q.path("period").asText())) {
                mine = q;
            }
        }
        assertThat(mine)
                .as("本用例的额度行必须出现在线上（按 (tenantId, period) 定向查 —— 该表是 JVM 级共享的，"
                        + "别的用例也在写，所以这里**不是**数组计数）")
                .isNotNull();
        assertThat(names(mine)).containsExactlyInAnyOrder("tenantId", "period", "tokenLimit", "requestLimit");
        assertThat(mine.path("tokenLimit").asLong()).isEqualTo(1_000L);
        assertThat(mine.path("requestLimit").asLong()).isEqualTo(7L);
    }

    private ResponseEntity<String> get(HttpHeaders headers) {
        return restTemplate.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** 签名口径：{@code GET} + **应用内路径** + 秒级时间戳（与 InternalAuthFilter 一致）。 */
    private static HttpHeaders signed() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Timestamp", timestamp);
        headers.set("X-Internal-Signature", InternalHmac.sign(SECRET, timestamp, "GET", PATH));
        return headers;
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // --- SQL 助手 -------------------------------------------------------

    private void deleteEverything() {
        jdbcTemplate.update("delete from model_route");
        jdbcTemplate.update("delete from rate_limit_policy");
        jdbcTemplate.update("delete from channel");
    }

    private Long insertTenant(String name) {
        jdbcTemplate.update("insert into tenant (name, status) values (?, ?)", name, ACTIVE);
        return jdbcTemplate.queryForObject("select id from tenant where name = ?", Long.class, name);
    }

    private String insertChannel(String name, String status) {
        String cipher = keys.encrypt(SYNTHETIC_PLAINTEXT);
        jdbcTemplate.update("insert into channel (name, provider, base_url, api_key_cipher, key_version, "
                        + "weight, priority, timeout_ms, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, "test", "https://" + name + ".example.com", cipher, keys.currentKeyVersion(),
                100, 0, 60_000, status);
        return cipher;
    }

    private void insertRoute(String modelName, Long channelId, int weight, int priority, String status) {
        jdbcTemplate.update("insert into model_route (model_name, channel_id, weight, priority, status) "
                + "values (?, ?, ?, ?, ?)", modelName, channelId, weight, priority, status);
    }

    private void insertPolicy(Long tenantId, Long apiKeyId, int qps, int burst, String status) {
        jdbcTemplate.update("insert into rate_limit_policy (tenant_id, api_key_id, qps, burst, status) "
                + "values (?, ?, ?, ?, ?)", tenantId, apiKeyId, qps, burst, status);
    }

    /**
     * 插入一条额度行（Task 13）。{@code quota} 表**没有** {@code status} 列：「没有行」本身就是「不限」（D15），
     * 所以这里也没有 status 参数；{@code uk_quota_tenant_period} 保证 {@code (tenant_id, period)} 唯一。
     */
    private void insertQuota(Long tenantId, String period, long tokenLimit, long requestLimit) {
        jdbcTemplate.update("insert into quota (tenant_id, period, token_limit, token_used, request_limit, "
                        + "request_used, version, created_at, updated_at) "
                        + "values (?, ?, ?, 0, ?, 0, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                tenantId, period, tokenLimit, requestLimit);
    }

    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
