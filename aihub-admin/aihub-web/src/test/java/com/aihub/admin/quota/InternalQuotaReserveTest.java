package com.aihub.admin.quota;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.internal.InternalHmac;
import com.aihub.common.quota.QuotaKeys;
import com.aihub.common.quota.QuotaPeriod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /internal/quota/reserve}（配额兜底回源端点）的**真容器**证据（Task 14 裁定 3/7/8）：
 * 真 MySQL + 真 Redis + 真 HMAC 签名过滤器 + 真 HTTP。
 *
 * <p>为什么这些断言只能放在 admin 模块（CONVENTIONS §8 item 3）：网关的测试**永远不允许依赖 Docker**，
 * 因此「桶真的被扣 / 超预算真的不再增长」这件事必须由这里的**真 Redis** 用例负责；
 * 网关侧只证「Redis 不可用时确实回源了、以及回源结果怎么翻成 200/429」。
 *
 * <p>要钉的五件事：
 * <ol>
 *   <li><b>受既有内部鉴权保护</b>（裁定 7）：{@code InternalAuthFilter} 按前缀 {@code /internal/} 守卫
 *       （无白名单），新端点自动受保护 —— 无签名 / 错签名一律 401 + admin 信封，带正确签名才 200。
 *       签名路径是**应用内路径** {@code /internal/quota/reserve}，METHOD 是 {@code POST}；
 *       （本类刻意**不加** {@code @TestPropertySource}：与默认上下文共用，不新增 Tomcat）。</li>
 *   <li><b>共享同一个桶</b>（裁定 3）：写的键逐字等于 {@code aihub:quota:<tenantId>:<YYYYMM>}，
 *       字段是 {@code QuotaKeys.FIELD_TOKENS}/{@code FIELD_REQUESTS} —— 与网关正常路径同一布局。</li>
 *   <li><b>超预算 ⇒ 拒绝且桶值不再增长</b>（裁定 3）：{@code allowed=false} 时**不写桶**
 *       （否则「越拒越满」，一旦桶被顶到上限就再也放不进来）。</li>
 *   <li><b>上限只来自 MySQL</b>：请求体里没有（也不会被读入）额度字段 —— 调用方声明不了自己的额度。</li>
 *   <li><b>不限不碰 Redis</b>（D15）：没有配额行 = 不限 ⇒ 直接放行，连记账都不做。</li>
 * </ol>
 *
 * <p><b>夹具纪律</b>：{@code quota} 表（{@code (tenant_id, period)} 唯一）与 Testcontainers 容器都是
 * JVM 级共享的，因此每个用例用**自己的** {@code tenantId}（{@code 902_0xx} 段，与 900_1xx / 901_0xx 不重叠），
 * 前后各清一次，且**只清自己那几个租户**的配额行与桶键（绝不 {@code FLUSHALL} / 全前缀删）。
 */
class InternalQuotaReserveTest extends AbstractIntegrationTest {

    private static final String PATH = "/internal/quota/reserve";

    /** 每个用例自己的租户：配额行与桶键都按它们清理。 */
    private static final long[] FIXTURE_TENANTS = {902_001L, 902_002L, 902_003L};

    private static final long LIMITED_TENANT = 902_001L;
    private static final long UNLIMITED_TENANT = 902_002L;
    private static final long PAYLOAD_TENANT = 902_003L;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    /** 与 {@code TestContainers.registerInfrastructure} 注册的是同一把合成密钥（不是真实秘密）。 */
    @Value("${aihub.internal.secret}")
    private String internalSecret;

    @LocalServerPort
    private int port;

    @BeforeEach
    void cleanBefore() {
        cleanFixtures();
    }

    @AfterEach
    void cleanAfter() {
        cleanFixtures();
    }

    // ------------------------------------------------------------------ 鉴权（裁定 7）

    /** 无签名 ⇒ 401 + admin 信封（新端点自动继承 {@code /internal/**} 的守卫）。 */
    @Test
    void theReserveEndpointRequiresTheInternalSignature() {
        ResponseEntity<String> response = post(body(LIMITED_TENANT, 300L), jsonHeaders());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("UNAUTHORIZED");
    }

    /** 错密钥签出来的签名同样 401（守卫不是「有头就放行」）。 */
    @Test
    void aRequestSignedWithTheWrongSecretIsRejectedWith401() {
        HttpHeaders headers = jsonHeaders();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        headers.set("X-Internal-Timestamp", timestamp);
        headers.set("X-Internal-Signature", InternalHmac.sign("not-the-secret", timestamp, "POST", PATH));

        ResponseEntity<String> response = post(body(LIMITED_TENANT, 300L), headers);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("UNAUTHORIZED");
    }

    // ------------------------------------------------------------------ 共享桶（裁定 3）

    /** 带正确签名 ⇒ 200，且**桶真的被扣**；键布局与网关逐字同一份。 */
    @Test
    void signedRequestReservesAgainstTheControlPlaneLimitAndWritesTheSharedBucket() {
        String period = currentPeriod();
        insertQuota(LIMITED_TENANT, period, 1_000L, 0L);

        ResponseEntity<String> response = signedPost(body(LIMITED_TENANT, 300L));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .contains("\"code\":\"OK\"")
                .contains("\"allowed\":true")
                .contains("\"remainingTokens\":700");

        String key = QuotaKeys.bucketKey(LIMITED_TENANT, period);
        assertThat(key)
                .as("桶键必须与网关正常路径逐字同一布局（否则「同一个桶」不成立）")
                .isEqualTo("aihub:quota:" + LIMITED_TENANT + ":" + period)
                .matches("aihub:quota:902001:\\d{6}");
        assertThat(redis.opsForHash().get(key, QuotaKeys.FIELD_TOKENS)).isEqualTo("300");
        assertThat(redis.opsForHash().get(key, QuotaKeys.FIELD_REQUESTS)).isEqualTo("1");
    }

    /**
     * 超预算 ⇒ {@code allowed=false} 且**桶值不再增长**：被拒的预扣绝不写桶。
     * 判别力：把「判定拒绝」的实现改成「先写桶再判定」⇒ 第二条断言（键根本不存在）立刻红。
     */
    @Test
    void anOverBudgetRequestIsDeniedAndTheSharedBucketNeverGrows() {
        String period = currentPeriod();
        insertQuota(LIMITED_TENANT, period, 100L, 0L);
        String key = QuotaKeys.bucketKey(LIMITED_TENANT, period);

        ResponseEntity<String> denied = signedPost(body(LIMITED_TENANT, 300L));

        assertThat(denied.getStatusCode().value())
                .as("业务上的「拒绝」仍是 HTTP 200（网关据此翻成 429），不是 4xx/5xx")
                .isEqualTo(200);
        assertThat(denied.getBody()).contains("\"allowed\":false");
        assertThat(redis.opsForHash().get(key, QuotaKeys.FIELD_TOKENS))
                .as("被拒的预扣绝不能写桶：否则「拒绝」反而把已用量顶上去，越拒越满")
                .isNull();
        assertThat(redis.hasKey(key)).as("被拒时桶根本不该被创建").isFalse();

        // 反向对照：同一把键上，真正放行的那一笔才写进去 ⇒ 确实共享同一个桶（不是另一套键）。
        ResponseEntity<String> allowed = signedPost(body(LIMITED_TENANT, 50L));
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(allowed.getBody()).contains("\"allowed\":true");
        assertThat(redis.opsForHash().get(key, QuotaKeys.FIELD_TOKENS)).isEqualTo("50");
        assertThat(redis.opsForHash().get(key, QuotaKeys.FIELD_REQUESTS)).isEqualTo("1");
    }

    // ------------------------------------------------------------------ 上限只来自 MySQL

    /**
     * 请求体自称有 {@code 999999} 的额度 —— 它必须被忽略（上限只能由控制面决定）。
     * 哨兵：哪一天实现改去读请求体里的额度，本用例立刻红。
     */
    @Test
    void theServerSideLimitWinsOverAnythingThePayloadClaims() {
        String period = currentPeriod();
        insertQuota(PAYLOAD_TENANT, period, 100L, 0L);

        ResponseEntity<String> response = signedPost("{\"tenantId\":" + PAYLOAD_TENANT
                + ",\"estimatedTokens\":300,\"tokenLimit\":999999,\"requestLimit\":999999}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .as("上限只来自 MySQL 的 quota 行：请求体里的 999999 不生效 ⇒ 300 > 100 ⇒ 拒绝")
                .contains("\"allowed\":false");
    }

    // ------------------------------------------------------------------ D15：不限

    /** 没有配额行 = 不限（D15）：直接放行，且**不碰 Redis**（连记账都不做）。 */
    @Test
    void anUnlimitedTenantIsAllowedWithoutTouchingRedis() {
        String period = currentPeriod();

        ResponseEntity<String> response = signedPost(body(UNLIMITED_TENANT, 999_999L));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .contains("\"allowed\":true")
                .contains("\"remainingTokens\":-1")
                .contains("\"remainingRequests\":-1");
        assertThat(redis.hasKey(QuotaKeys.bucketKey(UNLIMITED_TENANT, period)))
                .as("不限的维度没有预扣可言，不该创建桶").isFalse();
    }

    // ------------------------------------------------------------------ 入参校验

    /** 负的预估量会把 Lua 里的已用量**减小**（等于送额度），必须 400。 */
    @Test
    void aNegativePayloadIsRejectedAsInvalidParam() {
        ResponseEntity<String> response = signedPost("{\"tenantId\":" + LIMITED_TENANT
                + ",\"estimatedTokens\":-1}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("INVALID_PARAM");
    }

    // ------------------------------------------------------------------ 脚手架

    /** 用**绝对 URL + 自带 port**：不依赖 {@link TestRestTemplate} 的 root uri，避免任何前缀歧义。 */
    private String url() {
        return "http://127.0.0.1:" + port + PATH;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** 按应用内路径 {@code POST /internal/quota/reserve} 签名（body 不参与签名，与既有内部接口同口径）。 */
    private HttpHeaders signedHeaders() {
        HttpHeaders headers = jsonHeaders();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        headers.set("X-Internal-Timestamp", timestamp);
        headers.set("X-Internal-Signature", InternalHmac.sign(internalSecret, timestamp, "POST", PATH));
        return headers;
    }

    private ResponseEntity<String> signedPost(String body) {
        return post(body, signedHeaders());
    }

    private ResponseEntity<String> post(String body, HttpHeaders headers) {
        return restTemplate.exchange(url(), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String body(long tenantId, long estimatedTokens) {
        return "{\"tenantId\":" + tenantId + ",\"estimatedTokens\":" + estimatedTokens + "}";
    }

    /** 用**绝对 URL + 自带 port**：与 {@link TestRestTemplate} 的默认 root uri 无关，避免任何前缀歧义。 */
    private void insertQuota(long tenantId, String period, long tokenLimit, long requestLimit) {
        jdbcTemplate.update("insert into quota (tenant_id, period, token_limit, token_used, request_limit, "
                + "request_used, version) values (?, ?, ?, 0, ?, 0, 0)", tenantId, period, tokenLimit, requestLimit);
    }

    /** 只清本类的租户（绝不 FLUSHALL / 删全前缀）：容器是 JVM 级共享的。 */
    private void cleanFixtures() {
        for (long tenant : FIXTURE_TENANTS) {
            jdbcTemplate.update("delete from quota where tenant_id = ?", tenant);
            Set<String> keys = redis.keys(QuotaKeys.KEY_PREFIX + tenant + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
    }

    private static String currentPeriod() {
        return QuotaPeriod.of(System.currentTimeMillis());
    }
}
