package com.aihub.admin.console;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.quota.QuotaKeys;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.dao.entity.QuotaEntity;
import com.aihub.dao.mapper.QuotaMapper;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只读用量出口 {@code GET /api/quotas/usage} 的**线上 HTTP 契约**：真容器（MySQL + Redis + RabbitMQ）、
 * 真 HTTP、真控制台令牌、真鉴权过滤器。
 *
 * <h2>这个端点存在的原因</h2>
 * 配额的真实已用量**只在 Redis 桶**（{@code aihub:quota:{tenantId}:{YYYYMM}} 的 {@code tok}/{@code req}）里 ——
 * MySQL 的 {@code quota.token_used}/{@code request_used} 是**死列**（全仓库唯一的
 * {@code UPDATE quota} 不写它们，每日对账按 D12 也"从不写"），因此
 * {@code GET /api/quotas} 返回的 {@code tokenUsed} **恒为 0**。运维要看"这个周期用了多少、还剩多少"，
 * 在此之前**没有任何出口**。
 *
 * <h2>口径（刻意写进响应）</h2>
 * 桶里是**预扣估算**累计（{@code QuotaEstimator} 刻意偏保守），不是账单口径；所以响应带
 * {@code source="redis-bucket"} 与 {@code estimate=true}，防止被当成财务数字。选它的理由是
 * **所见即所拦**：这正是配额判定（{@code QuotaScript}）读的那个数。
 *
 * <h2>三条不变量（各自的判据见下）</h2>
 * <ol>
 *   <li><b>只读</b>（D12 红线）：不碰 {@code quota.token_used}、不碰桶、**也不惰性物化**
 *       （与 {@code GET /api/quotas} 相反 —— 那个 GET 会插一行零额度行）；</li>
 *   <li><b>R3.2</b>：租户只取令牌里的 {@code tenantId}，**不接受显式覆盖**（显式 {@code ?tenantId=}
 *       被忽略 —— 用两个桶值不同的租户让这条可证伪）；</li>
 *   <li><b>桶缺失 ≠ 错误</b>：报 {@code used=0} + {@code bucketMissing=true}；
 *       Redis 不可用才报错（那条在零上下文的 {@code QuotaUsageServiceTest} 里钉）。</li>
 * </ol>
 *
 * <h2>为什么本类与 {@code ConsoleLoginIntegrationTest} 共用同一个 Spring 上下文</h2>
 * 属性集与 {@code ConsoleLoginIntegrationTest} / {@code QuotaAdminIntegrationTest} **逐字相同**
 * （引用同一个 {@code SECRET} 常量）、且**不加** {@code @Import} / {@code @TestConfiguration} /
 * {@code @MockitoBean} ⇒ 复用既有上下文，{@code docs/CONVENTIONS.md} §8 的 7 上下文预算**不增加**
 * （全量套件的 {@code Tomcat started on port} 必须仍是 7）。
 *
 * <h2>夹具纪律</h2>
 * {@code quota} 表（{@code (tenant_id, period)} 唯一）与 Redis 容器都是 **JVM 级共享**的，
 * 因此每个用例用**自己的** {@code tenantId}（{@code 903_0xx} 段，与 {@code 901_0xx} /
 * {@code 902_0xx} 不重叠），前后各清一次，且**只清自己那几个租户**的配额行与桶键
 * （绝不 {@code FLUSHALL} / 删全前缀）。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=" + ConsoleLoginIntegrationTest.SECRET
})
class QuotaUsageEndpointIntegrationTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/quotas/usage";

    /** 控制台令牌的操作者 id（合成；本类只走令牌，不碰 {@code sys_user}）。 */
    private static final long USER_ID = 1L;

    private static final long TOKEN_TTL_SECONDS = 3_600L;

    // 每个用例自己的租户（容器共享，绝不复用别类的租户）。
    private static final long T_MAIN = 903_001L;
    private static final long T_OTHER = 903_002L;
    private static final long T_NO_BUCKET = 903_003L;
    private static final long T_UNLIMITED = 903_004L;
    private static final long T_VIEWER = 903_005L;
    private static final long T_READ_ONLY = 903_006L;

    private static final List<Long> FIXTURE_TENANTS =
            List.of(T_MAIN, T_OTHER, T_NO_BUCKET, T_UNLIMITED, T_VIEWER, T_READ_ONLY);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ConsoleTokenService consoleTokenService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private QuotaMapper quotaMapper;

    @LocalServerPort
    private int port;

    @BeforeEach
    @AfterEach
    void removeFixtures() {
        for (long tenant : FIXTURE_TENANTS) {
            quotaMapper.delete(new LambdaQueryWrapper<QuotaEntity>().eq(QuotaEntity::getTenantId, tenant));
            Set<String> keys = redis.keys(QuotaKeys.KEY_PREFIX + tenant + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
    }

    // ---------------------------------------------------------------- 1) 正常路径：限额 + 桶 + remaining

    /**
     * 本类的主判据：响应必须同时给出**限额**（来自 MySQL 行）、**已用量**（来自 Redis 桶）、
     * **剩余量**（服务端按 Lua 同款规则算）与**口径标记**。
     *
     * <p><b>判别力</b>：把桶读成 MySQL 的 {@code token_used}（恒 0）⇒ {@code tokenUsed} 断言红；
     * 把 {@code remaining} 改成"两个维度同一套算法"⇒ {@code requestRemaining=-1} 断言红（该维 0 = 不限）；
     * 去掉 {@code source}/{@code estimate} ⇒ 对应断言红（口径必须随数一起走）。
     */
    @Test
    void usageReportsTheSharedBucketAlongsideTheLimits() throws Exception {
        String period = currentPeriod();
        insertQuota(T_MAIN, period, 1_000L, 5L);
        putBucket(T_MAIN, period, 300L, 2L);

        ResponseEntity<String> response = exchange(PATH, T_MAIN, ConsoleClaims.ROLE_ADMIN);

        assertThat(response.getStatusCode()).as("响应体=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = body(response).path("data");
        assertThat(data.path("tenantId").asLong()).isEqualTo(T_MAIN);
        assertThat(data.path("period").asText()).isEqualTo(period);
        assertThat(data.path("tokenLimit").asLong()).as("限额来自 MySQL 的 quota 行").isEqualTo(1_000L);
        assertThat(data.path("tokenUsed").asLong()).as("已用量来自 Redis 桶的 tok").isEqualTo(300L);
        assertThat(data.path("tokenRemaining").asLong()).as("remaining = max(0, limit - used)").isEqualTo(700L);
        assertThat(data.path("requestLimit").asLong()).isEqualTo(5L);
        assertThat(data.path("requestUsed").asLong()).isEqualTo(2L);
        assertThat(data.path("requestRemaining").asLong()).isEqualTo(3L);
        assertThat(data.path("unlimited").asBoolean()).as("两个维度都有额度 ⇒ 不是不限").isFalse();
        assertThat(data.path("source").asText()).as("口径必须随数一起走，防止被当成账单").isEqualTo("redis-bucket");
        assertThat(data.path("estimate").asBoolean()).as("桶里是预扣估算，不是真实 usage").isTrue();
        assertThat(data.path("bucketMissing").asBoolean()).isFalse();
    }

    /** 某一维限额为 0 = 该维**不限**（D15）：它的 remaining 必须是 {@code -1}（与 Lua 的返回约定一致）。 */
    @Test
    void aZeroLimitDimensionReportsMinusOneRemaining() throws Exception {
        String period = currentPeriod();
        insertQuota(T_MAIN, period, 1_000L, 0L);
        putBucket(T_MAIN, period, 100L, 7L);

        JsonNode data = body(exchange(PATH, T_MAIN, ConsoleClaims.ROLE_ADMIN)).path("data");

        assertThat(data.path("requestRemaining").asLong()).as("requestLimit=0 ⇒ 该维不限 ⇒ -1").isEqualTo(-1L);
        assertThat(data.path("tokenRemaining").asLong()).isEqualTo(900L);
        assertThat(data.path("unlimited").asBoolean()).as("只有一维不限，整体仍不是 unlimited").isFalse();
        assertThat(data.path("requestUsed").asLong()).as("不限的维度照样有已用量（桶里记着）").isEqualTo(7L);
    }

    // ---------------------------------------------------------------- 2) R3.2：不接受显式覆盖

    /**
     * 显式 {@code ?tenantId=} 必须被**忽略**：读的仍是**令牌租户**的桶（R3.2，least privilege）。
     *
     * <p>可证伪性来自两个租户的桶值**不同**（令牌租户 300、另一个 999）：哪一天有人给这个端点加了
     * 请求参数覆盖，断言立刻红 —— 它不是"Spring 忽略未知参数"的同义反复。
     */
    @Test
    void anExplicitTenantIdIsIgnoredAndTheTokenTenantWins() throws Exception {
        String period = currentPeriod();
        insertQuota(T_MAIN, period, 1_000L, 0L);
        insertQuota(T_OTHER, period, 1_000L, 0L);
        putBucket(T_MAIN, period, 300L, 1L);
        putBucket(T_OTHER, period, 999L, 42L);

        JsonNode data = body(exchange(PATH + "?tenantId=" + T_OTHER, T_MAIN, ConsoleClaims.ROLE_ADMIN)).path("data");

        assertThat(data.path("tenantId").asLong()).as("R3.2：租户只取令牌").isEqualTo(T_MAIN);
        assertThat(data.path("tokenUsed").asLong()).as("读的是令牌租户的桶，不是 ?tenantId= 那个").isEqualTo(300L);
    }

    // ---------------------------------------------------------------- 3) 桶缺失 ≠ 错误

    /** 该周期还没发生任何预扣 ⇒ {@code used=0} + {@code bucketMissing=true}（**不是** 404/500）。 */
    @Test
    void anAbsentBucketIsReportedAsZeroAndFlagged() throws Exception {
        String period = currentPeriod();
        insertQuota(T_NO_BUCKET, period, 1_000L, 0L);

        ResponseEntity<String> response = exchange(PATH, T_NO_BUCKET, ConsoleClaims.ROLE_ADMIN);

        assertThat(response.getStatusCode()).as("桶不存在不是错误：响应体=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode data = body(response).path("data");
        assertThat(data.path("tokenUsed").asLong()).isZero();
        assertThat(data.path("tokenRemaining").asLong()).isEqualTo(1_000L);
        assertThat(data.path("bucketMissing").asBoolean())
                .as("必须显式标记：别让“没有记录”看起来像“用了 0”").isTrue();
    }

    // ---------------------------------------------------------------- 4) D15：不限 + 只读红线

    /**
     * 没有配额行 = 不限（D15）：两个 remaining 都是 {@code -1}，且**不创建桶、也不惰性物化配额行**。
     *
     * <p>最后那条断言是本端点与 {@code GET /api/quotas} 的分水岭：后者会
     * {@code insertZeroRowIfAbsent}（读路径上的写），本端点**一个字节都不许写**。
     */
    @Test
    void anUnlimitedTenantGetsMinusOneAndNothingIsWritten() throws Exception {
        String period = currentPeriod();

        JsonNode data = body(exchange(PATH, T_UNLIMITED, ConsoleClaims.ROLE_ADMIN)).path("data");

        assertThat(data.path("unlimited").asBoolean()).isTrue();
        assertThat(data.path("tokenRemaining").asLong()).isEqualTo(-1L);
        assertThat(data.path("requestRemaining").asLong()).isEqualTo(-1L);
        assertThat(data.path("bucketMissing").asBoolean()).isTrue();
        assertThat(redis.hasKey(QuotaKeys.bucketKey(T_UNLIMITED, period)))
                .as("读用量绝不该创建桶").isFalse();
        assertThat(countQuotaRows(T_UNLIMITED))
                .as("本端点不许惰性物化配额行（那是 GET /api/quotas 的行为，不是这里的）").isZero();
    }

    /**
     * D12 红线的正面钉：调用前后 {@code quota} 行的 {@code token_used}/{@code version} 与桶的值**逐位不变**。
     *
     * <p>判别力：把已用量"顺手写回 MySQL"（那正是 D12 明确否掉的修正）⇒ {@code token_used} 断言红。
     */
    @Test
    void theUsageEndpointNeverTouchesTheAccounts() throws Exception {
        String period = currentPeriod();
        insertQuota(T_READ_ONLY, period, 1_000L, 0L);
        putBucket(T_READ_ONLY, period, 7L, 3L);
        long versionBefore = quotaVersion(T_READ_ONLY, period);
        String bucketKey = QuotaKeys.bucketKey(T_READ_ONLY, period);
        Long ttlBefore = redis.getExpire(bucketKey);

        ResponseEntity<String> response = exchange(PATH, T_READ_ONLY, ConsoleClaims.ROLE_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quotaTokenUsed(T_READ_ONLY, period)).as("已用量列一个字节都不许被这个接口改").isZero();
        assertThat(quotaVersion(T_READ_ONLY, period)).as("读路径不该推进乐观锁版本").isEqualTo(versionBefore);
        assertThat(redis.opsForHash().get(bucketKey, QuotaKeys.FIELD_TOKENS))
                .as("桶值也必须原样").isEqualTo("7");
        Long ttlAfter = redis.getExpire(bucketKey);
        assertThat(ttlAfter).as("桶仍然活着（夹具设了一小时的 TTL）").isPositive();
        assertThat(ttlAfter)
                .as("读用量绝不许刷新桶的 TTL：否则早已过期的周期桶永远不会被回收")
                .isLessThanOrEqualTo(ttlBefore);
    }

    // ---------------------------------------------------------------- 5) 鉴权与入参

    /** VIEWER 只读：用量出口是读接口 ⇒ 200（与 {@code PUT /api/quotas} 的 403 对照）。 */
    @Test
    void aViewerMayReadTheUsage() throws Exception {
        String period = currentPeriod();
        insertQuota(T_VIEWER, period, 500L, 0L);
        putBucket(T_VIEWER, period, 20L, 1L);

        ResponseEntity<String> response = exchange(PATH, T_VIEWER, ConsoleClaims.ROLE_VIEWER);

        assertThat(response.getStatusCode()).as("响应体=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(body(response).path("data").path("tokenUsed").asLong()).isEqualTo(20L);
    }

    /** 缺令牌 ⇒ 401 + admin 信封（继承 {@code ConsoleAuthFilter} 的前缀守卫，无需新登记任何路径）。 */
    @Test
    void aMissingTokenIsRejectedWith401() throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(url(PATH), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("UNAUTHORIZED");
    }

    /** {@code period} 不合法 ⇒ 400 {@code INVALID_PARAM}（与 {@code GET /api/quotas} 同一条校验）。 */
    @Test
    void anInvalidPeriodIsRejectedAs400() throws Exception {
        ResponseEntity<String> response = exchange(PATH + "?period=2026-10", T_MAIN, ConsoleClaims.ROLE_ADMIN);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("INVALID_PARAM");
    }

    // ---------------------------------------------------------------- 脚手架

    private ResponseEntity<String> exchange(String path, long tenantId, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(tenantId, role));
        return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String token(long tenantId, String role) {
        long now = Instant.now().getEpochSecond();
        return consoleTokenService.issue(new ConsoleClaims(USER_ID, tenantId, role, now, now + TOKEN_TTL_SECONDS));
    }

    /** 用**绝对 URL + 自带 port**：不依赖 {@link TestRestTemplate} 的 root uri，避免任何前缀歧义。 */
    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static JsonNode body(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private static String currentPeriod() {
        return QuotaPeriod.of(System.currentTimeMillis());
    }

    /** 走裸 SQL 插入：本类只关心 HTTP 契约，不经过控制面写路径（那会引入 {@code version}/审计的噪声）。 */
    private void insertQuota(long tenantId, String period, long tokenLimit, long requestLimit) {
        jdbcTemplate.update("insert into quota (tenant_id, period, token_limit, token_used, request_limit, "
                + "request_used, version) values (?, ?, ?, 0, ?, 0, 0)", tenantId, period, tokenLimit, requestLimit);
    }

    /**
     * 直接写**网关那个键**（{@code QuotaKeys} 是共享契约，这里绝不出现字面量），并且**照生产的样子给它
     * 一个 TTL**：真实路径上 {@code QuotaScript} 的 {@code PEXPIRE} 会设它，而"读用量**不刷新** TTL"
     * 是一条真判据 —— 夹具不设 TTL 时 {@code getExpire} 恒为 {@code -1}，那条断言就变成了同义反复。
     */
    private void putBucket(long tenantId, String period, long tokens, long requests) {
        String key = QuotaKeys.bucketKey(tenantId, period);
        redis.opsForHash().put(key, QuotaKeys.FIELD_TOKENS, String.valueOf(tokens));
        redis.opsForHash().put(key, QuotaKeys.FIELD_REQUESTS, String.valueOf(requests));
        redis.expire(key, Duration.ofHours(1));
    }

    private long countQuotaRows(long tenantId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from quota where tenant_id = ?", Long.class, tenantId);
        return count == null ? 0L : count;
    }

    private long quotaTokenUsed(long tenantId, String period) {
        Long used = jdbcTemplate.queryForObject(
                "select token_used from quota where tenant_id = ? and period = ?", Long.class, tenantId, period);
        return used == null ? -1L : used;
    }

    private long quotaVersion(long tenantId, String period) {
        Long version = jdbcTemplate.queryForObject(
                "select version from quota where tenant_id = ? and period = ?", Long.class, tenantId, period);
        return version == null ? -1L : version;
    }
}
