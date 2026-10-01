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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    /**
     * {@code RequestLogQueryService.RequestLogView} 的**期望 JSON 投影**（字段名集合，逐字）。
     *
     * <p>它是「响应里没有密钥材料字段」这条性质的**可证伪**形式：返回记录的字段名集合必须与它逐字相等；
     * 给视图多加一个字段（例如 {@code keyHash} / {@code apiKeyCipher}），返回 JSON 就多一个名字，断言即红。
     * 之所以不用 {@code doesNotContain("...")}：那类「某个字符串不可能出现」的断言**结构上不可证伪**
     * （理由见 {@link #logsResponseCarriesNoKeyMaterial()} 的 javadoc，评审 I-3）。
     */
    private static final Set<String> REQUEST_LOG_VIEW_FIELDS = Set.of(
            "id", "requestId", "tenantId", "apiKeyId", "channelId", "model", "promptTokens",
            "completionTokens", "totalTokens", "latencyMs", "ttftMs", "status", "errorCode", "createdAt");

    /** {@code AuditQueryService.AuditLogView} 的**期望 JSON 投影**（字段名集合，逐字）。理由同上。 */
    private static final Set<String> AUDIT_LOG_VIEW_FIELDS = Set.of(
            "id", "tenantId", "actorType", "actor", "action", "targetType", "targetId", "detail",
            "requestId", "createdAt");

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

    /**
     * 日志视图的投影必须**恰好**是这些非敏感字段 —— 既不含密钥材料字段，也不许**多出**任何字段。
     *
     * <p><b>为什么是「JSON 字段集等价断言」而不是 {@code doesNotContain(keyHash)}</b>（评审 I-3）：
     * {@code request_log} 表根本没有 {@code key_hash} / {@code api_key_cipher} 列，{@code RequestLogView}
     * 也没有这两个字段，且那些哨兵串**从未被写进任何表** —— 所以任何生产变异都不可能让一条「响应里不
     * 出现 X」的断言变红（**结构上不可证伪**）。真正要防的是「有人给视图加了字段」（例如给
     * {@code RequestLogView} 补一个 {@code keyHash}/{@code apiKeyCipher}）。把每条返回记录的 **JSON
     * 字段名集合**与**期望投影**（{@link #REQUEST_LOG_VIEW_FIELDS}）逐字比对，才让「加字段」可证伪 ——
     * 加一个字段，JSON 就多一个名字，本断言立即变红。
     */
    @Test
    void logsResponseCarriesNoKeyMaterial() throws Exception {
        long tenantId = createTenant();
        long apiKeyId = insertApiKey(tenantId, uniqueKeyName(), randomKeyHash()).getId();
        String cipher = CIPHER_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        long channelId = insertChannel(cipher).getId();
        long rowId = insertRequestLog(tenantId, uniqueRequestId(), apiKeyId, channelId, MID).getId();

        ResponseEntity<String> res = get("/api/logs?tenantId=" + tenantId + "&" + WINDOW + "&page=0&size=50");
        assertThat(res.getStatusCode()).as("查询必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode records = body(res).path("data").path("records");
        // 正向对照：投影必须是**活的** —— 本次夹具那一行必须真的被查回来，否则字段集断言是在空对象上比对。
        assertThat(records.size()).as("响应必须包含本次夹具那一行（否则下面的字段集断言是空断言）").isEqualTo(1);

        JsonNode row = records.get(0);
        assertThat(fieldNames(row)).as("RequestLogView 的 JSON 字段集必须逐字等于期望投影（多一个字段，例如"
                        + "有人给视图补 keyHash/apiKeyCipher，即失败；响应体=%s）", res.getBody())
                .containsExactlyInAnyOrderElementsOf(REQUEST_LOG_VIEW_FIELDS);
        // 夹具值真的通了：证明这些字段不是「恰好全是 null」的摆设。
        assertThat(row.path("id").asLong()).isEqualTo(rowId);
        assertThat(row.path("apiKeyId").asLong()).isEqualTo(apiKeyId);
        assertThat(row.path("channelId").asLong()).isEqualTo(channelId);

        // 记录性说明（**故意不写成断言**）：request_log 表没有 key_hash / api_key_cipher 列，视图也没有这
        // 两个字段，keyHash / 渠道密文 / 明文哨兵在结构上**不可能**出现在响应里。对「不可能出现的字符串」
        // 做 doesNotContain 是**无牙断言**（无论生产怎么改都不会红，评审 I-3），其可证伪形式就是上面的字段集
        // 等价断言 —— 因此原 doesNotContain(keyHash).doesNotContain(cipher).doesNotContain(PLAINTEXT)
        // 已被删除，而不是留着充数。
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

    /**
     * 审计视图的投影必须**恰好**是这些非敏感字段 —— 与 {@link #logsResponseCarriesNoKeyMaterial()} 同款
     * 「JSON 字段集等价断言」（为什么不用 {@code doesNotContain(...)}：见该方法的 javadoc，评审 I-3）。
     *
     * <p><b>正向对照</b>：给审计行的 {@code detail} 放一条**非敏感**哨兵，并断言响应**确实回显**它 ——
     * 证明投影是**活的**、{@code detail} 真的通了，而不是「恰好什么都没读出来」。
     */
    @Test
    void auditResponseCarriesNoKeyMaterial() throws Exception {
        long tenantId = createTenant();
        insertApiKey(tenantId, uniqueKeyName(), randomKeyHash());
        String detailSentinel = "audit-detail-" + UUID.randomUUID().toString().substring(0, 8);
        insertAuditLog(tenantId, MID, "{\"note\":\"" + detailSentinel + "\"}");

        ResponseEntity<String> res = get("/api/audit?tenantId=" + tenantId + "&" + WINDOW + "&page=0&size=50");
        assertThat(res.getStatusCode()).as("查询必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode records = body(res).path("data").path("records");
        assertThat(records.size()).as("响应必须包含本次夹具那一行（否则字段集断言与回显对照都是空断言）")
                .isEqualTo(1);

        JsonNode row = records.get(0);
        assertThat(fieldNames(row)).as("AuditLogView 的 JSON 字段集必须逐字等于期望投影（多一个字段，例如"
                        + "有人给视图补 keyHash/apiKeyCipher，即失败；响应体=%s）", res.getBody())
                .containsExactlyInAnyOrderElementsOf(AUDIT_LOG_VIEW_FIELDS);
        // 正向对照：detail 必须**原样回显**那条非敏感哨兵（证明投影是活的、detail 真的通了）。
        assertThat(row.path("detail").asText()).as("审计 detail 必须原样回显哨兵（投影是活的；响应体=%s）",
                res.getBody()).contains(detailSentinel);
        assertThat(row.path("targetId").asText()).startsWith(AUDIT_TARGET_PREFIX);

        // 记录性说明（**故意不写成断言**）：audit_log 表没有 key_hash 列、AuditLogView 也没有该字段，那些
        // 哨兵串从未入库 ⇒ 对「不可能出现的字符串」做 doesNotContain 不可证伪（评审 I-3）。可证伪的形式即
        // 上面的字段集等价断言 —— 原 doesNotContain(keyHash).doesNotContain(PLAINTEXT) 因此被删除。
    }

    // ---------------------------------------------------------------- 8) /api/billing/daily 强制 tenantId + 范围

    /**
     * R3.1 + 控制器裁定 2：{@code /api/billing/daily} 必须带 {@code tenantId}（缺省 400），并按
     * {@code stat_date} 范围返回该租户的行。「缺 tenantId ⇒ 400」的判别力由变异体 {@code M3}（缺省
     * tenantId 回落默认租户）提供；{@code stat_date} 窗口过滤的判别力由本用例的夹具形状提供（见下）。
     *
     * <p><b>{@code stat_date} 窗口过滤必须可判别（评审 I-1 / M-h）</b>：此前只插**1 行且在窗口内**，
     * 于是删掉整段 {@code .ge/.le(statDate)} 后仍全绿（有无窗口都返回那一行）—— 零判别力。现在同一租户
     * 插**3 行**：{@code 2026-09-15} 在窗口内（窗口 ±10 分钟折成同一 UTC 自然日 09-15），{@code 2026-08-01}
     * 早于、{@code 2026-12-31} 晚于窗口。删掉窗口过滤（或只删 {@code .ge}／只删 {@code .le} 其中一半）
     * 都会让窗外行漏进响应，本用例精确变红。
     */
    @Test
    void billingDailyRequiresATenantAndReturnsFixtureRows() throws Exception {
        long tenantId = createTenant();
        // 一行在窗口内、两行在窗口外（一早一晚，好让 .ge 与 .le 各自都有判别力）。
        insertBillingDaily(tenantId, LocalDate.of(2026, 9, 15));
        insertBillingDaily(tenantId, LocalDate.of(2026, 8, 1));
        insertBillingDaily(tenantId, LocalDate.of(2026, 12, 31));

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
        // 判别力：删掉 stat_date 窗口过滤会返回全部 3 行；这里必须**恰好**只有窗口内的那 1 行。
        List<String> statDates = new ArrayList<>();
        data.forEach(node -> statDates.add(node.path("statDate").asText()));
        assertThat(data.size()).as("必须恰好返回窗口内的 1 条日账单行（删掉 stat_date 窗口过滤会返回 3 行；"
                + "响应体=%s）", ok.getBody()).isEqualTo(1);
        assertThat(statDates).as("返回的 stat_date 必须**恰好**只有窗口内那一天 —— 窗外的 2026-08-01 / "
                + "2026-12-31 一个都不许出现（响应体=%s）", ok.getBody()).containsExactly("2026-09-15");
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
        return insertAuditLog(tenantId, createdAt, "{\"status\":\"ACTIVE\"}");
    }

    /**
     * 可变 {@code detail} 的版本：给「投影是活的」这条**正向对照**用 —— 放一条非敏感哨兵进 {@code detail}
     * 并断言响应确实回显它（证明 {@code AuditLogView.detail} 真的通了，而不是「恰好什么都没读出来」）。
     */
    private AuditLogEntity insertAuditLog(long tenantId, LocalDateTime createdAt, String detail) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setTenantId(tenantId);
        entity.setActorType(USER);
        entity.setActor(ACTOR_ID);
        entity.setAction("TENANT_UPDATE");
        entity.setTargetType(TENANT_TARGET_TYPE);
        entity.setTargetId(AUDIT_TARGET_PREFIX + UUID.randomUUID().toString().substring(0, 8));
        entity.setDetail(detail);
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

    /** 取一个 JSON 对象的字段名（用于「投影字段集逐字等价」断言，让「给视图加字段」可证伪）。 */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
