package com.aihub.admin.console;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.dao.entity.ApiKeyEntity;
import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.RequestLogEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.ApiKeyMapper;
import com.aihub.dao.mapper.AuditLogMapper;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.RequestLogMapper;
import com.aihub.dao.mapper.TenantMapper;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 11 的**线上契约**：请求日志 / 审计 / 每日账单三条**运营查询**端点。真容器（MySQL + Redis +
 * RabbitMQ，见 {@link AbstractIntegrationTest}）、真 HTTP、真控制台令牌。
 *
 * <p><b>本类只依赖 HTTP + 既有 mapper/entity + {@code JdbcTemplate}</b>：它**不引用**
 * {@code RequestLogQueryService} / {@code AuditQueryService} / {@code BillingDailyMapper} 等本任务才
 * 落地的类型，因此能在服务层/控制器存在之前**编译并跑红**（计划 Task 11 的 Step 1 纪律）。
 * {@code billing_daily} 夹具用裸 SQL 插（{@link JdbcTemplate}）正是为了这一点。
 *
 * <p><b>RED 的实测形态（诚实登记，不修饰）</b>：实现落地之前，本类的自然 RED **全部**落在
 * {@code 404 NOT_FOUND / No static resource api/logs | api/audit | api/billing/daily}（端点尚未映射）——
 * **没有一条**落在被测断言上（与 Task 9 / Task 10 同源）。因此每条判据的**判别力完全由变异体提供**
 * （见交付报告的变异实验），**不是**由这套自然 RED 提供。产物：{@code .m4t11-logs/R01-red.log}。
 *
 * <p><b>租户语义（{@code docs/CONVENTIONS.md} §10 R3.1）</b>：这三条都是**运营查询**，调用方**必须**
 * 显式给 {@code tenantId}（缺省 400），理由是**防无界扫描**（{@code request_log} / {@code audit_log}
 * 的索引都以 {@code tenant_id} 打头），**不是**授权；令牌里的 {@code tenantId}（R4）在这里不参与判定。
 * 本类的令牌租户固定为 1，查询租户是每次新建的夹具租户 —— 两者刻意不同，以证明查询用的是**显式参数**。
 *
 * <p><b>可选维度过滤的语义</b>：{@code apiKeyId} / {@code channelId} **缺省 = 不过滤**（返回该租户该
 * 时间窗内的全部行），由**条件式构造**实现 —— 绝不把 {@code null} 交给 {@code eq(...)}（那会生成
 * {@code col = NULL}、恒为 UNKNOWN、**静默返回 0 行**，见 CONVENTIONS §7）。夹具行**带**非空
 * {@code api_key_id} / {@code channel_id}，所以「缺省不过滤」这条语义在本类里是可判别的。
 *
 * <p><b>时间绑定的纪律（CONVENTIONS §7）</b>：{@code from}/{@code to} 是带 {@code Z} 的绝对时刻，
 * 窗口夹具也按 **UTC 墙上时间**写进 {@code datetime(3)}。窗口只有 ±10 分钟，因此任何「按非 UTC 基准」
 * 的绑定（{@code Timestamp} 在非 UTC 连接上、或 {@code LocalDateTime} 用 JVM 默认时区折算）都会把窗口
 * 整体推走、一行都命中不到 —— 这正是变异体 ④ 要打的断言。
 *
 * <p><b>为什么 {@code @TestPropertySource} 只有一个属性</b>：本类只需要一把合规的控制台签名密钥
 * （{@code application.yml} 对 {@code aihub.console.secret} **刻意没有默认值**，见 D16）。属性集与
 * {@code ConsoleLoginIntegrationTest} / {@code ApiKeyAdminIntegrationTest} /
 * {@code RouteAndRateLimitAdminIntegrationTest} **逐字相同**、且本类**不加** {@code @Import} /
 * {@code @TestConfiguration}，因此 Spring **复用**已经存在的那个上下文，套件的 Spring 上下文总数
 * 仍是 7（{@code docs/CONVENTIONS.md} §8 item 5/6 的预算）。
 *
 * <p><b>夹具纪律</b>：容器与各表都是 JVM 级共享的（基类没有全局清理），所以夹具名每条用例唯一、
 * 前后各清一次；断言一律按**本次用例的租户 id** 或**唯一夹具串**查，绝不做全表计数；夹具前缀里
 * **不含** LIKE 元字符（由 {@link #fixtureNamesContainNoLikeMetacharacters()} 钉住）。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=" + ProbeAndQueryIntegrationTest.SECRET
})
class ProbeAndQueryIntegrationTest extends AbstractIntegrationTest {

    /**
     * 合成控制台签名密钥（≥32 字符），与 {@code ConsoleLoginIntegrationTest.SECRET} **逐字相同** ——
     * 值相同才会命中同一个 Spring 上下文缓存键（本类的目的就是复用那个上下文，不新增上下文）。
     * 刻意**不是** {@code private}：注解值里的常量表达式引用私有常量时 javac 会报访问控制错误。
     */
    static final String SECRET = "console-it-secret-0123456789abcdefghijklmn";

    private static final String TENANT_PREFIX = "m4-q-tn-";
    private static final String CHANNEL_PREFIX = "m4-q-ch-";
    private static final String KEY_PREFIX = "m4-q-key-";
    private static final String REQ_PREFIX = "m4-q-req-";
    private static final String AUDIT_TARGET_PREFIX = "m4-q-aud-";

    /** 一个**只可能**是本类夹具的值：任何响应里出现它就说明视图把密钥材料带出来了。 */
    private static final String PLAINTEXT_SENTINEL = "sk-query-plaintext-synthetic";

    private static final String MODEL = "m4-q-model";
    private static final String ACTIVE = "ACTIVE";

    private static final String USER = "USER";
    private static final String ACTOR_ID = "1";
    private static final String TENANT_TARGET_TYPE = "TENANT";

    /** 令牌租户：与查询租户**刻意不同**，用来证明查询用的是显式 {@code tenantId} 参数（R3.1/R4）。 */
    private static final long TOKEN_TENANT = 1L;

    /**
     * 夹具窗口的**中轴**（UTC 墙上时间）。窗口只有 ±10 分钟 ⇒ 任何「按非 UTC 基准」的时间绑定都会
     * 一行都命中不到（见类注释的「时间绑定的纪律」）。
     */
    private static final LocalDateTime MID = LocalDateTime.of(2026, 9, 15, 12, 0, 0);
    private static final String FROM = toInstantString(MID.minusMinutes(10));
    private static final String TO = toInstantString(MID.plusMinutes(10));
    private static final String WINDOW = "from=" + FROM + "&to=" + TO;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private ChannelMapper channelMapper;

    @Autowired
    private ApiKeyMapper apiKeyMapper;

    @Autowired
    private RequestLogMapper requestLogMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConsoleTokenService consoleTokenService;

    /** 唯一夹具前后各清一次：容器是 JVM 级共享的，不依赖「表是干净的」。 */
    @BeforeEach
    @AfterEach
    void removeFixtures() {
        requestLogMapper.delete(new LambdaQueryWrapper<RequestLogEntity>()
                .likeRight(RequestLogEntity::getRequestId, REQ_PREFIX));
        auditLogMapper.delete(new LambdaQueryWrapper<AuditLogEntity>()
                .likeRight(AuditLogEntity::getTargetId, AUDIT_TARGET_PREFIX));
        List<Long> tenantIds = tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
                        .likeRight(TenantEntity::getName, TENANT_PREFIX)).stream()
                .map(TenantEntity::getId).toList();
        if (!tenantIds.isEmpty()) {
            String placeholders = tenantIds.stream().map(id -> "?").collect(Collectors.joining(","));
            jdbcTemplate.update("DELETE FROM billing_daily WHERE tenant_id IN (" + placeholders + ")",
                    tenantIds.toArray());
        }
        apiKeyMapper.delete(new LambdaQueryWrapper<ApiKeyEntity>().likeRight(ApiKeyEntity::getName, KEY_PREFIX));
        tenantMapper.delete(new LambdaQueryWrapper<TenantEntity>().likeRight(TenantEntity::getName, TENANT_PREFIX));
        channelMapper.delete(new LambdaQueryWrapper<ChannelEntity>().likeRight(ChannelEntity::getName, CHANNEL_PREFIX));
    }

    // ---------------------------------------------------------------- 1) /api/logs 强制 tenantId + 时间范围

    /**
     * R3.1：{@code /api/logs} 是**运营查询** —— 缺 {@code tenantId} 或时间范围一律 **400**（防无界扫描）；
     * 三者齐备才 200。判别力由变异体 {@code M3}（缺省 tenantId 时回落到一个默认租户）提供：
     * 变异后第一条断言的 status 变成 200 ≠ 400，本用例精确变红。
     */
    @Test
    void logsQueryRequiresATenantAndATimeRange() throws Exception {
        long tenantId = createTenant();

        // 带时间范围、**不带** tenantId：这条 400 的**唯一**可能原因就是缺 tenantId（否则「400」可能只是
        // 「缺时间范围」——判别力会落在错误的原因上，变异体 M3 就是靠这条被抓住的）。
        ResponseEntity<String> missingTenant = get("/api/logs?" + WINDOW);
        assertThat(missingTenant.getStatusCode()).as("缺 tenantId 必须 400（响应体=%s）", missingTenant.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(missingTenant).path("code").asText()).isEqualTo("INVALID_PARAM");

        // 完全不带参数（连时间范围也没有）：同样 400（无界扫描的入口）。
        assertThat(get("/api/logs?page=0&size=10").getStatusCode())
                .as("无 tenantId 且无时间范围必须 400").isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> missingRange = get("/api/logs?tenantId=" + tenantId + "&page=0&size=10");
        assertThat(missingRange.getStatusCode()).as("缺时间范围必须 400（响应体=%s）", missingRange.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // 正向对照：三者齐备必须 200（否则「400」可能只是「一律拒绝」）。
        ResponseEntity<String> ok = get("/api/logs?tenantId=" + tenantId + "&" + WINDOW);
        assertThat(ok.getStatusCode()).as("tenantId + 时间范围齐备必须 200（响应体=%s）", ok.getBody())
                .isEqualTo(HttpStatus.OK);
        // 缺省分页值也必须钉死（size 缺省 20、page 缺省 0），否则「默认」只是文档承诺。
        assertThat(body(ok).path("data").path("page").asInt()).isEqualTo(0);
        assertThat(body(ok).path("data").path("size").asInt()).isEqualTo(20);
    }

    // ---------------------------------------------------------------- 2) /api/logs 分页有界 + created_at DESC

    /**
     * 分页**真的有 {@code LIMIT}**、顺序是 {@code created_at DESC}、上界被钉死。
     *
     * <p>关键断言是「3 行夹具、{@code size=2} ⇒ 恰好 2 条且 {@code total==3}」：判别力由变异体
     * {@code M1}（**删掉 {@code MybatisPlusInterceptor} 的 {@code @Bean}**）提供 —— 没有分页拦截器时
     * MyBatis-Plus 的 {@code selectPage} **不加 LIMIT**、把全部匹配行塞进 {@code records}（且不算
     * {@code total}），本用例的 {@code records.size()==2} 精确变红。这正是「无界扫描」的入口。
     *
     * <p>{@code apiKeyId}/{@code channelId} **刻意都不传**：夹具行带非空 {@code api_key_id} /
     * {@code channel_id}，所以本用例的「3 条」同时证明「缺省 = 不过滤」这条语义（判别力由变异体
     * {@code M7}：把可选维度写成无条件的 {@code eq(col, null)} ⇒ 静默 0 行提供）。
     */
    @Test
    void logsPagingIsBoundedAndOrderedByCreatedAtDescending() throws Exception {
        long tenantId = createTenant();
        long apiKeyId = insertApiKey(tenantId, uniqueKeyName(), randomKeyHash()).getId();
        long channelId = insertChannel(CIPHER_PREFIX + UUID.randomUUID().toString().substring(0, 8)).getId();

        long oldest = insertRequestLog(tenantId, uniqueRequestId(), apiKeyId, channelId, MID.minusMinutes(1)).getId();
        long middle = insertRequestLog(tenantId, uniqueRequestId(), apiKeyId, channelId, MID).getId();
        long newest = insertRequestLog(tenantId, uniqueRequestId(), apiKeyId, channelId, MID.plusMinutes(1)).getId();

        JsonNode page = body(get("/api/logs?tenantId=" + tenantId + "&" + WINDOW + "&page=0&size=2")).path("data");
        assertThat(page.path("total").asLong()).as("total 必须等于匹配行数（无拦截器时它不被计算）").isEqualTo(3);
        assertThat(page.path("size").asInt()).as("回显的页大小必须等于请求的 size").isEqualTo(2);
        JsonNode records = page.path("records");
        assertThat(records.size()).as("分页必须真的加了 LIMIT：3 行夹具、size=2 ⇒ 恰好 2 条（无拦截器时是 3 条）")
                .isEqualTo(2);
        assertThat(records.get(0).path("id").asLong())
                .as("排序必须是 created_at DESC：第一条是最新的").isEqualTo(newest);
        assertThat(records.get(1).path("id").asLong()).isEqualTo(middle);
        assertThat(records.get(0).path("id").asLong()).isGreaterThan(records.get(1).path("id").asLong())
                .isGreaterThan(oldest);

        // size=201 必须被**钳到 200**（断言实际页大小，而不是只断言 200 状态码）。
        JsonNode clamped = body(get("/api/logs?tenantId=" + tenantId + "&" + WINDOW + "&page=0&size=201")).path("data");
        assertThat(clamped.path("size").asInt()).as("size>200 必须钳到 200，不许原样回显 201").isEqualTo(200);
        assertThat(clamped.path("records").size()).as("钳到 200 后 3 行夹具必须全部返回").isEqualTo(3);

        // 非法 page/size 一律 400。
        assertThat(get("/api/logs?tenantId=" + tenantId + "&" + WINDOW + "&size=0").getStatusCode())
                .as("size<1 必须 400").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/logs?tenantId=" + tenantId + "&" + WINDOW + "&page=-1").getStatusCode())
                .as("page<0 必须 400").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- 3) /api/logs 时间范围校验

    /**
     * {@code from}/{@code to} 必须能解析成带 {@code Z} 的 {@code Instant}，且 {@code from <= to}。
     * 判别力由变异体 {@code M4a}（缺省/空白时间不报错、回落到 {@code Instant.EPOCH}）与 {@code M4b}
     * （不校验 {@code from <= to}）提供：两者分别把对应断言从 400 打成 200，精确变红。
     */
    @Test
    void logsQueryRejectsAMalformedOrInvertedTimeRange() throws Exception {
        long tenantId = createTenant();

        ResponseEntity<String> malformed = get("/api/logs?tenantId=" + tenantId + "&from=not-an-instant&to=" + TO);
        assertThat(malformed.getStatusCode()).as("无法解析的 from 必须 400（响应体=%s）", malformed.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(malformed).path("code").asText()).isEqualTo("INVALID_PARAM");

        ResponseEntity<String> inverted = get("/api/logs?tenantId=" + tenantId + "&from=" + TO + "&to=" + FROM);
        assertThat(inverted.getStatusCode()).as("from>to 必须 400（响应体=%s）", inverted.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- 4) /api/logs 视图不含密钥材料

    /** 视图里**不含**本次夹具的明文、{@code key_hash} 或渠道密文；且响应**非空**（否则「不含」是空断言）。 */
    @Test
    void logsResponseCarriesNoKeyMaterial() throws Exception {
        long tenantId = createTenant();
        String keyHash = randomKeyHash();
        long apiKeyId = insertApiKey(tenantId, uniqueKeyName(), keyHash).getId();
        String cipher = CIPHER_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        long channelId = insertChannel(cipher).getId();
        insertRequestLog(tenantId, uniqueRequestId(), apiKeyId, channelId, MID);

        ResponseEntity<String> res = get("/api/logs?tenantId=" + tenantId + "&" + WINDOW + "&page=0&size=50");
        assertThat(res.getStatusCode()).as("查询必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(body(res).path("data").path("records").size())
                .as("响应里必须能找到本次夹具那一行（否则下面的「不含」是无意义的空断言）").isEqualTo(1);
        assertThat(res.getBody()).as("日志视图绝不许出现 key_hash、渠道密文或明文")
                .doesNotContain(keyHash).doesNotContain(cipher).doesNotContain(PLAINTEXT_SENTINEL);
    }

    // ---------------------------------------------------------------- 5) /api/audit 强制 tenantId + 时间范围

    /**
     * R3.1：{@code /api/audit} 同样必须显式 {@code tenantId} + 时间范围；缺一即 400，齐备则 200 并
     * 返回本次夹具的审计行。判别力由变异体（同 {@code /api/logs} 的 {@code M3}：把缺省 tenantId 回落到
     * 默认租户）提供。
     */
    @Test
    void auditQueryRequiresATenantAndATimeRange() throws Exception {
        long tenantId = createTenant();
        insertAuditLog(tenantId, MID.minusMinutes(1));
        insertAuditLog(tenantId, MID.plusMinutes(1));

        // 带时间范围、**不带** tenantId：400 的唯一原因就是缺 tenantId（同 /api/logs 的判别力要求）。
        ResponseEntity<String> missingTenant = get("/api/audit?" + WINDOW);
        assertThat(missingTenant.getStatusCode()).as("缺 tenantId 必须 400（响应体=%s）", missingTenant.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(missingTenant).path("code").asText()).isEqualTo("INVALID_PARAM");

        ResponseEntity<String> missingRange = get("/api/audit?tenantId=" + tenantId + "&page=0&size=10");
        assertThat(missingRange.getStatusCode()).as("缺时间范围必须 400（响应体=%s）", missingRange.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> ok = get("/api/audit?tenantId=" + tenantId + "&" + WINDOW + "&page=0&size=10");
        assertThat(ok.getStatusCode()).as("三者齐备必须 200（响应体=%s）", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(body(ok).path("data").path("total").asLong())
                .as("必须查到本次夹具的 2 条审计行（按租户 + 窗口过滤）").isEqualTo(2);
    }

    // ---------------------------------------------------------------- 6) /api/audit 分页有界 + DESC

    /** 审计查询同样分页有界、{@code created_at DESC}。分页拦截器的判别力同 {@code /api/logs} 的 {@code M1}。 */
    @Test
    void auditPagingIsBoundedAndOrderedByCreatedAtDescending() throws Exception {
        long tenantId = createTenant();
        long oldest = insertAuditLog(tenantId, MID.minusMinutes(1)).getId();
        long middle = insertAuditLog(tenantId, MID).getId();
        long newest = insertAuditLog(tenantId, MID.plusMinutes(1)).getId();

        JsonNode page = body(get("/api/audit?tenantId=" + tenantId + "&" + WINDOW + "&page=0&size=1")).path("data");
        assertThat(page.path("total").asLong()).isEqualTo(3);
        assertThat(page.path("size").asInt()).isEqualTo(1);
        JsonNode records = page.path("records");
        assertThat(records.size()).as("分页必须真的加了 LIMIT：3 行、size=1 ⇒ 恰好 1 条").isEqualTo(1);
        assertThat(records.get(0).path("id").asLong()).as("排序必须是 created_at DESC").isEqualTo(newest);
        assertThat(newest).isGreaterThan(middle).isGreaterThan(oldest);
    }

    // ---------------------------------------------------------------- 7) /api/audit 视图不含密钥材料

    /** 审计视图不含本次夹具的明文、{@code key_hash} 或渠道密文；响应非空。 */
    @Test
    void auditResponseCarriesNoKeyMaterial() throws Exception {
        long tenantId = createTenant();
        String keyHash = randomKeyHash();
        insertApiKey(tenantId, uniqueKeyName(), keyHash);
        insertAuditLog(tenantId, MID);

        ResponseEntity<String> res = get("/api/audit?tenantId=" + tenantId + "&" + WINDOW + "&page=0&size=50");
        assertThat(res.getStatusCode()).as("查询必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(body(res).path("data").path("records").size())
                .as("响应里必须能找到本次夹具那一行（否则下面的「不含」是无意义的空断言）").isEqualTo(1);
        assertThat(res.getBody()).as("审计视图绝不许出现 key_hash 或明文")
                .doesNotContain(keyHash).doesNotContain(PLAINTEXT_SENTINEL);
    }

    // ---------------------------------------------------------------- 8) /api/billing/daily 强制 tenantId + 范围

    /**
     * R3.1 + 控制器裁定 2：{@code /api/billing/daily} 必须带 {@code tenantId}（缺省 400），并按
     * {@code stat_date} 范围返回该租户的行。判别力由变异体 {@code M3}（缺省 tenantId 回落默认租户）提供。
     */
    @Test
    void billingDailyRequiresATenantAndReturnsFixtureRows() throws Exception {
        long tenantId = createTenant();
        insertBillingDaily(tenantId, LocalDate.of(2026, 9, 15));

        ResponseEntity<String> missingTenant = get("/api/billing/daily?" + WINDOW);
        assertThat(missingTenant.getStatusCode()).as("缺 tenantId 必须 400（响应体=%s）", missingTenant.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(missingTenant).path("code").asText()).isEqualTo("INVALID_PARAM");

        ResponseEntity<String> missingRange = get("/api/billing/daily?tenantId=" + tenantId);
        assertThat(missingRange.getStatusCode()).as("缺时间范围必须 400（响应体=%s）", missingRange.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> ok = get("/api/billing/daily?tenantId=" + tenantId + "&" + WINDOW);
        assertThat(ok.getStatusCode()).as("三者齐备必须 200（响应体=%s）", ok.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = body(ok).path("data");
        assertThat(data.size()).as("必须返回本次夹具的 1 条日账单行").isEqualTo(1);
        assertThat(data.get(0).path("statDate").asText()).isEqualTo("2026-09-15");
        assertThat(data.get(0).path("requests").asLong()).isEqualTo(3L);
        assertThat(data.get(0).path("tokens").asLong()).isEqualTo(30L);

        // from>to ⇒ 400（与 /api/logs、/api/audit 语义统一）。
        ResponseEntity<String> inverted = get("/api/billing/daily?tenantId=" + tenantId + "&from=" + TO + "&to=" + FROM);
        assertThat(inverted.getStatusCode()).as("from>to 必须 400（响应体=%s）", inverted.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- 9) 夹具自守

    /**
     * 夹具名前缀里的 {@code _} 对 LIKE 是**单字符通配符**，{@code %} 与 {@code \} 同理。本类的清理与
     * 断言都建立在「前缀只含字面量」这条**假设**上，所以这里把它变成可执行事实。
     */
    @Test
    void fixtureNamesContainNoLikeMetacharacters() {
        for (int i = 0; i < 8; i++) {
            assertThat(uniqueRequestId()).as("请求日志夹具名不许含 LIKE 元字符")
                    .doesNotContain("%").doesNotContain("_").doesNotContain("\\");
            assertThat(uniqueKeyName()).as("key 夹具名不许含 LIKE 元字符")
                    .doesNotContain("%").doesNotContain("_").doesNotContain("\\");
            for (String prefix : List.of(TENANT_PREFIX, CHANNEL_PREFIX, KEY_PREFIX, REQ_PREFIX,
                    AUDIT_TARGET_PREFIX)) {
                assertThat(prefix).as("夹具前缀不许含 LIKE 元字符（实际=%s）", prefix)
                        .doesNotContain("%").doesNotContain("_").doesNotContain("\\");
            }
        }
    }

    // ---------------------------------------------------------------- 夹具与助手

    private long createTenant() {
        TenantEntity tenant = new TenantEntity();
        tenant.setName(TENANT_PREFIX + UUID.randomUUID().toString().substring(0, 8));
        tenant.setStatus(ACTIVE);
        tenantMapper.insert(tenant);
        assertThat(tenant.getId()).as("MyBatis-Plus 必须把自增主键回填进实体").isNotNull();
        return tenant.getId();
    }

    private ApiKeyEntity insertApiKey(long tenantId, String name, String keyHash) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId(ApiKeyHasher.newKeyId());
        entity.setTenantId(tenantId);
        entity.setKeyHash(keyHash);
        entity.setName(name);
        entity.setStatus(ACTIVE);
        apiKeyMapper.insert(entity);
        assertThat(entity.getId()).as("MyBatis-Plus 必须把自增主键回填进实体").isNotNull();
        return entity;
    }

    private ChannelEntity insertChannel(String cipher) {
        ChannelEntity channel = new ChannelEntity();
        channel.setName(CHANNEL_PREFIX + UUID.randomUUID().toString().substring(0, 8));
        channel.setProvider("openai-compatible");
        channel.setBaseUrl("http://127.0.0.1:1");
        channel.setApiKeyCipher(cipher);
        channel.setKeyVersion(1);
        channel.setWeight(100);
        channel.setPriority(0);
        channel.setTimeoutMs(60_000);
        channel.setStatus(ACTIVE);
        channelMapper.insert(channel);
        assertThat(channel.getId()).as("MyBatis-Plus 必须把自增主键回填进实体").isNotNull();
        return channel;
    }

    private RequestLogEntity insertRequestLog(long tenantId, String requestId, Long apiKeyId, Long channelId,
                                              LocalDateTime createdAt) {
        RequestLogEntity entity = new RequestLogEntity();
        entity.setRequestId(requestId);
        entity.setTenantId(tenantId);
        entity.setApiKeyId(apiKeyId);
        entity.setChannelId(channelId);
        entity.setModel(MODEL);
        entity.setPromptTokens(1);
        entity.setCompletionTokens(2);
        entity.setTotalTokens(3);
        entity.setLatencyMs(10);
        entity.setTtftMs(5);
        entity.setStatus("SUCCESS");
        entity.setCreatedAt(createdAt);
        requestLogMapper.insert(entity);
        assertThat(entity.getId()).as("MyBatis-Plus 必须把自增主键回填进实体").isNotNull();
        return entity;
    }

    private AuditLogEntity insertAuditLog(long tenantId, LocalDateTime createdAt) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setTenantId(tenantId);
        entity.setActorType(USER);
        entity.setActor(ACTOR_ID);
        entity.setAction("TENANT_UPDATE");
        entity.setTargetType(TENANT_TARGET_TYPE);
        entity.setTargetId(AUDIT_TARGET_PREFIX + UUID.randomUUID().toString().substring(0, 8));
        entity.setDetail("{\"status\":\"ACTIVE\"}");
        entity.setCreatedAt(createdAt);
        auditLogMapper.insert(entity);
        assertThat(entity.getId()).as("MyBatis-Plus 必须把自增主键回填进实体").isNotNull();
        return entity;
    }

    /** {@code billing_daily} 夹具走裸 SQL：本类要在 {@code BillingDailyMapper} 存在之前就能编译并跑红。 */
    private void insertBillingDaily(long tenantId, LocalDate statDate) {
        jdbcTemplate.update("INSERT INTO billing_daily (tenant_id, stat_date, requests, tokens, cost) "
                + "VALUES (?, ?, 3, 30, 0.000000)", tenantId, Date.valueOf(statDate));
    }

    private static String uniqueKeyName() {
        return KEY_PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String uniqueRequestId() {
        return REQ_PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 每次都用新 secret 现算，保证 {@code uk_api_key_key_hash} 不冲突；返回值就是可判别的哨兵串。 */
    private static String randomKeyHash() {
        return ApiKeyHasher.hash("m4-q-key-secret-" + UUID.randomUUID());
    }

    private static String toInstantString(LocalDateTime utcWallTime) {
        return utcWallTime.toInstant(ZoneOffset.UTC).toString();
    }

    /** 渠道密文哨兵前缀（它绝不会出现在任何响应里；形态与真实的 {@code v{n}:{base64}} 同类）。 */
    private static final String CIPHER_PREFIX = "v1:QUERYCIPHER";

    private String token() {
        long now = Instant.now().getEpochSecond();
        return consoleTokenService.issue(
                new ConsoleClaims(1L, TOKEN_TENANT, ConsoleClaims.ROLE_ADMIN, now, now + 3_600L));
    }

    private ResponseEntity<String> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token());
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static JsonNode body(ResponseEntity<String> res) throws Exception {
        return MAPPER.readTree(res.getBody());
    }
}
