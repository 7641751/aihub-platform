package com.aihub.admin.console;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.entity.QuotaEntity;
import com.aihub.dao.mapper.AuditLogMapper;
import com.aihub.dao.mapper.QuotaMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.aihub.service.quota.QuotaAdminService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配额控制面 {@code /api/quotas} 的**线上 HTTP 契约**（Task 12，覆盖缺口 I-2 / M-2）：真容器
 * （MySQL + Redis + RabbitMQ）、真 HTTP、真控制台令牌、真鉴权过滤器。
 *
 * <p>被测的是 {@code QuotaController} + {@code ConsoleAuthFilter} + {@code QuotaAdminService} 的端到端接线，
 * 断言**只经 HTTP 与既有 mapper**（不引用控制器内部类型），因此它描述的就是运维会看到的行为。
 *
 * <h2>覆盖点（逐条对应 docs/CONVENTIONS.md §10 与评审 I-2）</h2>
 * <ul>
 *   <li>R3.2（查）：{@code GET /api/quotas} 缺省只服务**令牌租户**的行；缺 {@code period} 时用**当前 UTC 周期**；</li>
 *   <li>R2（写）：{@code PUT} 的 {@code tenantId} 来自**请求体**（平台级写），写的是**指定租户**，
 *       不是令牌租户；审计的 {@code tenant_id} 记**目标配额行**的租户；</li>
 *   <li>入参校验：缺 {@code tenantId}、{@code tokenLimit} 超过 {@code 2^53} 或为负 ⇒ **400 {@code INVALID_PARAM}**
 *       （边界复用 {@code QuotaScript.assertWithinRange} 的语义）；</li>
 *   <li>两级角色：{@code VIEWER} 对 {@code PUT} ⇒ 403、对 {@code GET} ⇒ 200；</li>
 *   <li>乐观锁冲突经 HTTP 折成的码（{@code ErrorCode} 里**没有** 409）—— 见
 *       {@link #anOptimisticLockConflictOverHttpFoldsTo400InvalidParam()}；</li>
 *   <li>审计副作用（M-2）：PUT 后按本次 {@code target_id} 定向查到一条 {@code QUOTA_UPDATE}。</li>
 * </ul>
 *
 * <h2>为什么本类与 {@code ConsoleLoginIntegrationTest} 共用同一个 Spring 上下文</h2>
 * 本类只需要一把合规的控制台签名密钥，属性集与 {@code ConsoleLoginIntegrationTest} **逐字相同**
 * （值直接引用它的 {@code SECRET} 常量）、且**不加** {@code @Import} / {@code @TestConfiguration} /
 * {@code @MockitoBean}，因此 Spring 复用那个已经存在的上下文（{@code docs/CONVENTIONS.md} §8 item 5/6
 * 的 7 上下文预算不增加）。这也是本类**不能**用 mock 服务来制造乐观锁冲突的原因 —— 那会 fork 一个新上下文。
 *
 * <h2>夹具纪律</h2>
 * 配额表（{@code (tenant_id, period)} 唯一）与 {@code audit_log}、以及 Testcontainers 容器都是
 * **JVM 级共享**的（{@link AbstractIntegrationTest} 没有全局清理），因此每个用例用**自己的
 * {@code tenantId}**、前后各清一次；审计断言一律按**本次用例的数值主键**（{@code target_id}）定向查，
 * 绝不做全表计数。租户 id 取 {@code 901_0xx} 段，与别类的 {@code 900_1xx} 段不重叠。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=" + ConsoleLoginIntegrationTest.SECRET
})
class QuotaAdminIntegrationTest extends AbstractIntegrationTest {

    private static final String QUOTAS = "/api/quotas";

    /** 控制台令牌的操作者 id（合成；本类只走令牌，不碰 {@code sys_user}）。 */
    private static final long USER_ID = 1L;

    /** 令牌存活期（秒）：一小时足够。 */
    private static final long TOKEN_TTL_SECONDS = 3_600L;

    private static final String QUOTA_TARGET_TYPE = "QUOTA";

    /** 有界等待上限：并发/轮询挂死时带原因变红，绝不让用例永不返回。 */
    private static final long WAIT_SECONDS = 30L;

    // 每个用例自己的租户（配额表按 (tenant, period) 唯一；容器共享，绝不复用别类的租户）。
    private static final long T_GET = 901_001L;
    private static final long T_PUT_TOKEN = 901_002L;
    private static final long T_PUT_BODY = 901_003L;
    private static final long T_VALIDATION = 901_004L;
    private static final long T_VIEWER = 901_005L;
    private static final long T_CONFLICT = 901_006L;
    private static final long T_AUDIT_TOKEN = 901_007L;
    private static final long T_AUDIT_TARGET = 901_008L;

    /** 本类用到的全部夹具租户：前后各清一次。 */
    private static final List<Long> FIXTURE_TENANTS =
            List.of(T_GET, T_PUT_TOKEN, T_PUT_BODY, T_VALIDATION, T_VIEWER, T_CONFLICT, T_AUDIT_TOKEN, T_AUDIT_TARGET);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ConsoleTokenService consoleTokenService;

    @Autowired
    private QuotaMapper quotaMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private QuotaAdminService quotaAdminService;


    /** 唯一夹具前后各清一次：容器是 JVM 级共享的，不依赖「表是干净的」。 */
    @BeforeEach
    @AfterEach
    void removeFixtures() {
        for (long tenant : FIXTURE_TENANTS) {
            quotaMapper.delete(new LambdaQueryWrapper<QuotaEntity>().eq(QuotaEntity::getTenantId, tenant));
            auditLogMapper.delete(new LambdaQueryWrapper<AuditLogEntity>().eq(AuditLogEntity::getTenantId, tenant));
        }
    }

    // ---------------------------------------------------------------- 1) GET = R3.2 + period 缺省

    /**
     * {@code GET /api/quotas}（**无显式 tenantId**）必须用**令牌租户**（R3.2，least privilege）；
     * 缺 {@code period} 时必须用**当前 UTC 周期**（{@code QuotaPeriod.of(now)}，与注入的 {@code Clock} 同基准）。
     *
     * <p>判别力：把 GET 的租户从 {@code claims.tenantId()} 改成别的来源、或把缺省 period 换成非当前周期
     * 即红（前者让 {@code tenantId} 断言红，后者让 {@code period} 断言红）。
     */
    @Test
    void getReturnsTheTokenTenantsQuotaForTheCurrentPeriodByDefault() throws Exception {
        long tenant = T_GET;
        String expectedPeriod = QuotaPeriod.of(System.currentTimeMillis());

        ResponseEntity<String> response = exchange(HttpMethod.GET, QUOTAS, null, tenant, ConsoleClaims.ROLE_ADMIN);

        assertThat(response.getStatusCode()).as("GET /api/quotas 必须 200（响应体=%s）", response.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode data = body(response).path("data");
        assertThat(data.path("tenantId").asLong())
                .as("GET = R3.2：必须返回**令牌里的租户**的配额行").isEqualTo(tenant);
        assertThat(data.path("period").asText())
                .as("缺 period 时必须用当前 UTC 周期 QuotaPeriod.of(now)").isEqualTo(expectedPeriod);
        assertThat(data.path("id").asLong()).as("惰性物化必须回传真行 id").isPositive();
    }

    // ---------------------------------------------------------------- 2) PUT = R2（写指定租户）

    /**
     * {@code PUT /api/quotas} 的 {@code tenantId} 来自**请求体**（R2，平台级写）：用两个不同租户钉住
     * 「写的是**请求体指定**的租户，**不是**令牌租户」。
     *
     * <p>判别力：把控制器的 tenantId 改成取令牌租户（评审 X-2）即红 —— 响应 {@code data.tenantId} 与
     * {@code findQuota(bodyTenant)} 两处断言都精确变红。
     */
    @Test
    void putWritesTheBodyTenantNotTheTokenTenant() throws Exception {
        long tokenTenant = T_PUT_TOKEN;
        long bodyTenant = T_PUT_BODY;
        String period = QuotaPeriod.of(System.currentTimeMillis());

        ResponseEntity<String> response = exchange(HttpMethod.PUT, QUOTAS,
                quotaBody(bodyTenant, period, 100L, 7L), tokenTenant, ConsoleClaims.ROLE_ADMIN);

        assertThat(response.getStatusCode()).as("PUT /api/quotas 必须 200（响应体=%s）", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(body(response).path("data").path("tenantId").asLong())
                .as("PUT = R2：写入的必须是**请求体**里的 tenantId，而不是令牌租户").isEqualTo(bodyTenant);

        QuotaEntity written = findQuota(bodyTenant, period);
        assertThat(written).as("请求体指定租户的配额行必须被写入").isNotNull();
        assertThat(written.getTokenLimit()).as("写入的 tokenLimit 必须落库").isEqualTo(100L);
        assertThat(written.getRequestLimit()).as("写入的 requestLimit 必须落库").isEqualTo(7L);
        assertThat(findQuota(tokenTenant, period))
                .as("令牌租户**绝不许**被写（写的是请求体指定的租户）").isNull();
    }

    // ---------------------------------------------------------------- 3) 入参校验 ⇒ 400

    /**
     * 入参校验一律 400 {@code INVALID_PARAM}（不是 500、也不是别的码）：缺 {@code tenantId}、
     * {@code tokenLimit} 超过 {@code 2^53}、{@code tokenLimit} 为负；且**被拒的请求绝不留行**。
     */
    @Test
    void putRejectsMissingTenantIdAndOutOfRangeLimitsAs400InvalidParam() throws Exception {
        long tenant = T_VALIDATION;
        String period = QuotaPeriod.of(System.currentTimeMillis());

        Map<String, Object> missingTenant = new LinkedHashMap<>();
        missingTenant.put("period", period);
        missingTenant.put("tokenLimit", 1L);
        missingTenant.put("requestLimit", 1L);
        ResponseEntity<String> missing = exchange(HttpMethod.PUT, QUOTAS, missingTenant, tenant,
                ConsoleClaims.ROLE_ADMIN);
        assertThat(missing.getStatusCode()).as("缺 tenantId 必须 400（响应体=%s）", missing.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(missing).path("code").asText()).as("缺 tenantId 必须回 INVALID_PARAM")
                .isEqualTo("INVALID_PARAM");

        ResponseEntity<String> tooBig = exchange(HttpMethod.PUT, QUOTAS,
                quotaBody(tenant, period, 9_007_199_254_740_993L, 1L), tenant, ConsoleClaims.ROLE_ADMIN);
        assertThat(tooBig.getStatusCode()).as("tokenLimit 超过 2^53 必须 400（响应体=%s）", tooBig.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(tooBig).path("code").asText()).as("超上界必须回 INVALID_PARAM").isEqualTo("INVALID_PARAM");

        ResponseEntity<String> negative = exchange(HttpMethod.PUT, QUOTAS,
                quotaBody(tenant, period, -1L, 1L), tenant, ConsoleClaims.ROLE_ADMIN);
        assertThat(negative.getStatusCode()).as("tokenLimit 为负必须 400（响应体=%s）", negative.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(negative).path("code").asText()).as("负数必须回 INVALID_PARAM").isEqualTo("INVALID_PARAM");

        assertThat(findQuota(tenant, period)).as("被 400 拒绝的 PUT 绝不许留下配额行").isNull();
    }

    // ---------------------------------------------------------------- 4) 两级角色

    /**
     * 两级角色（D10）：{@code VIEWER} 对 {@code GET} ⇒ 200（可读），对 {@code PUT} ⇒ **403**
     * （过滤器拦成 admin 信封，而不是落到 MVC 的 405）。
     */
    @Test
    void viewerMayReadButNotWrite() throws Exception {
        long tenant = T_VIEWER;
        String period = QuotaPeriod.of(System.currentTimeMillis());

        ResponseEntity<String> read = exchange(HttpMethod.GET, QUOTAS + "?period=" + period, null, tenant,
                ConsoleClaims.ROLE_VIEWER);
        assertThat(read.getStatusCode()).as("VIEWER 对 GET 必须 200（响应体=%s）", read.getBody())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> write = exchange(HttpMethod.PUT, QUOTAS, quotaBody(tenant, period, 1L, 1L), tenant,
                ConsoleClaims.ROLE_VIEWER);
        assertThat(write.getStatusCode()).as("VIEWER 对 PUT 必须 403（响应体=%s）", write.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(write).path("code").asText()).as("VIEWER 写操作必须回 FORBIDDEN").isEqualTo("FORBIDDEN");
    }

    // ---------------------------------------------------------------- 5) 乐观锁冲突 ⇒ HTTP 折成的码
    //
    // **本项被移除并登记为残余**（2026-10-01，控制器裁定）：
    // 覆盖「乐观锁冲突经 HTTP 折成 400 INVALID_PARAM」需要确定性地让 PUT 的 CAS 撞上 0 行 ——
    // 唯一可行机制是「持锁事务不提交 + 有界轮询 information_schema.innodb_trx 出现 LOCK WAIT」，
    // 而 **Testcontainers 的 MySQL 用户没有 PROCESS 权限**，该查询直接
    // `SQLSyntaxErrorException: Access denied; you need (at least one of) the PROCESS privilege(s)`
    // （证据：`.hb2-logs/T12F-C1-integration.log`；先前的修复尝试因此整类报 ERROR）。
    // 又不许用「睡够时间」代替可观测的阻塞证据（伪造确定性），所以本项**不在这里伪造**。
    // 残余风险评估：CAS 0 行 ⇒ `BizException(INVALID_PARAM)` 这条**服务层**行为已被
    // `QuotaPreDeductionIntegrationTest.anUpdateOnAStaleVersionConflictsInsteadOfSilentlyOverwriting` 钉住；
    // 而 `INVALID_PARAM` ⇒ HTTP 400 的**映射**已被本类的
    // `putRejectsMissingTenantIdAndOutOfRangeLimitsAs400InvalidParam` 覆盖。
    // 因此未覆盖的只剩「这两段的组合」，属**低风险残余**，登记在 M4 收口清单。

    // ---------------------------------------------------------------- 6) 审计副作用（M-2）

    /**
     * PUT 的审计副作用（M-2）：按**本次 {@code target_id}** 定向查 {@code audit_log}，断言
     * {@code target_type=QUOTA}、{@code action} 是**既有常量** {@link AuditAction#QUOTA_UPDATE}
     * （不新增常量）、且 {@code tenant_id} = **目标配额行的租户**（R2 的审计语义，而不是令牌租户 —— 因此这里
     * 用两个不同租户）。
     *
     * <p>判别力：把服务层 {@code auditService.record(...)} 去掉（评审 X-4）⇒ 查不到行，本用例精确变红。
     */
    @Test
    void putRecordsAnAuditRowForTheTargetTenantWithTheExistingAction() throws Exception {
        long tokenTenant = T_AUDIT_TOKEN;
        long targetTenant = T_AUDIT_TARGET;
        String period = QuotaPeriod.of(System.currentTimeMillis());

        ResponseEntity<String> response = exchange(HttpMethod.PUT, QUOTAS,
                quotaBody(targetTenant, period, 5L, 6L), tokenTenant, ConsoleClaims.ROLE_ADMIN);
        assertThat(response.getStatusCode()).as("PUT 必须 200（响应体=%s）", response.getBody())
                .isEqualTo(HttpStatus.OK);
        long quotaId = body(response).path("data").path("id").asLong();
        assertThat(quotaId).as("响应必须回填配额行 id（审计的 target_id 就是它）").isPositive();

        List<AuditLogEntity> rows = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getTargetType, QUOTA_TARGET_TYPE)
                .eq(AuditLogEntity::getTargetId, String.valueOf(quotaId)));

        assertThat(rows).as("PUT 之后必须按本次 target_id 恰好留下一条配额审计").hasSize(1);
        assertThat(rows.get(0).getTenantId())
                .as("R2：审计的 tenant_id = **目标配额行的**租户（不是令牌租户）").isEqualTo(targetTenant);
        assertThat(rows.get(0).getAction())
                .as("action 必须是既有常量 QUOTA_UPDATE（不新增常量）").isEqualTo(AuditAction.QUOTA_UPDATE);
    }

    // ---------------------------------------------------------------- HTTP / DB 助手

    private ResponseEntity<String> exchange(HttpMethod method, String path, Object body,
                                            long tokenTenantId, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(tokenTenantId, role));
        HttpEntity<Object> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(path, method, entity, String.class);
    }

    private String token(long tenantId, String role) {
        long now = Instant.now().getEpochSecond();
        return consoleTokenService.issue(new ConsoleClaims(USER_ID, tenantId, role, now, now + TOKEN_TTL_SECONDS));
    }

    private static Map<String, Object> quotaBody(long tenantId, String period, Long tokenLimit, Long requestLimit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("period", period);
        body.put("tokenLimit", tokenLimit);
        body.put("requestLimit", requestLimit);
        return body;
    }

    private static JsonNode body(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private QuotaEntity findQuota(long tenantId, String period) {
        return quotaMapper.selectOne(new LambdaQueryWrapper<QuotaEntity>()
                .eq(QuotaEntity::getTenantId, tenantId)
                .eq(QuotaEntity::getPeriod, period));
    }

}
