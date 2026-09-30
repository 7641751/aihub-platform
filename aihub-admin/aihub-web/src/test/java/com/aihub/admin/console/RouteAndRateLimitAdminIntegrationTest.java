package com.aihub.admin.console;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.config.ConfigInvalidateCodec;
import com.aihub.common.config.ConfigInvalidateMessage;
import com.aihub.common.config.ConfigInvalidateTopology;
import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.ModelRouteEntity;
import com.aihub.dao.entity.RateLimitPolicyEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.AuditLogMapper;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.aihub.dao.mapper.TenantMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
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
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10 的**线上契约**：模型路由 + 限流策略 CRUD。真容器（MySQL + Redis）、真 HTTP、真控制台令牌、
 * 真 Redis Pub/Sub。
 *
 * <p><b>本类只依赖 HTTP + 既有 mapper/entity</b>（不引用 {@code ModelRouteAdminService} /
 * {@code RateLimitPolicyAdminService} 这两个本任务才落地的类型）：因此它在服务层/控制器存在之前就能
 * **编译并跑红**。
 *
 * <p><b>RED 的实测形态（诚实登记，2026-09-30，不修饰）</b>：本类的**自然** RED 是
 * {@code Tests run: 13, Failures: 11, Errors: 0, Skipped: 0}，而那 **11 条红全部**落在
 * {@code 404 NOT_FOUND / No static resource api/routes | api/rate-limits}（端点尚未映射）——
 * **没有一条**落在被测断言上（与 Task 9 同源）。因此四条判据的**判别力完全由变异体提供**（每条行为各配
 * 一条变异体），**不是**由这套 RED 提供。产物：{@code .m4t10-logs/R01-red.log}（RED 原始日志）、
 * {@code .m4t10-logs/MUTATIONS.txt} 与 {@code .m4t10-logs/M*-red.log}（初版 11 条变异）；
 * 覆盖缺口修复轮的补充变异见 {@code .m4t10fix-logs/}。
 *
 * <p><b>租户语义（{@code docs/CONVENTIONS.md} §10）</b>：
 * <ul>
 *   <li><b>R1</b>：{@code model_route} 是全局资源（表里没有 {@code tenant_id}）——写读都是平台级，
 *       任何 {@code ADMIN} 看全部行；审计的 {@code tenant_id} 记 SQL NULL。</li>
 *   <li><b>R2</b>：{@code rate_limit_policy} 是租户维度资源——写是平台级（请求体带 {@code tenantId}），
 *       但审计的 {@code tenant_id} 必须记**该策略的**租户 id（不是操作者的租户、不是 NULL）。</li>
 *   <li><b>R3.2</b>：{@code GET /api/rate-limits} 缺省只回**令牌租户**的行（least privilege）。</li>
 * </ul>
 *
 * <p><b>{@code api_key_id IS NULL} = 租户级维度</b>：查它必须 {@code isNull()} ——
 * {@code eq(column, null)} 恒不成立、会静默返回 0 行（CONVENTIONS §7）。本类的
 * {@link #activeRowsFor(long, Long)} 与 {@link #onlyRouteAuditRow(String, long)} 把这条用到查询上，
 * 而它也正是被测的服务层必须做对的地方（否则「两个维度共存」会退化成「全租户只有一条 ACTIVE」）。
 *
 * <p><b>夹具纪律</b>：{@code tenant} / {@code channel} / {@code model_route} / {@code rate_limit_policy}
 * / {@code audit_log} 与 Testcontainers 容器都是 JVM 级共享的（{@link AbstractIntegrationTest}
 * 没有全局清理），所以夹具名每条用例唯一、前后各清一次；审计断言一律按**本次用例的数值主键**
 * （{@code target_id}）查，绝不做全表计数。夹具前缀里**不含** LIKE 元字符（{@code _} / {@code %} /
 * {@code \}），这条假设由 {@link #fixtureNamesContainNoLikeMetacharacters()} 钉住。
 *
 * <p><b>为什么 {@code @TestPropertySource} 只有一个属性</b>：本类只需要一把合规的控制台签名密钥
 * （{@code application.yml} 对 {@code aihub.console.secret} **刻意没有默认值**，见 D16）。属性集与
 * {@code ConsoleLoginIntegrationTest} **逐字相同**、且本类**不加** {@code @Import} /
 * {@code @TestConfiguration}，因此 Spring 复用那个已经存在的上下文，而不是再 fork 一个
 * （{@code docs/CONVENTIONS.md} §8 item 5/6 的 7 上下文预算）。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=" + RouteAndRateLimitAdminIntegrationTest.SECRET
})
class RouteAndRateLimitAdminIntegrationTest extends AbstractIntegrationTest {

    /**
     * 合成控制台签名密钥（≥32 字符），与 {@code ConsoleLoginIntegrationTest.SECRET} / {@code
     * ApiKeyAdminIntegrationTest.SECRET} **逐字相同** —— 值相同才会命中同一个 Spring 上下文缓存键
     * （本类的目的就是复用那个上下文）。刻意**不是 {@code private}**：注解值里的常量表达式引用私有
     * 常量时 javac 会报访问控制错误。
     */
    static final String SECRET = "console-it-secret-0123456789abcdefghijklmn";

    private static final String TENANT_PREFIX = "m4-rl-tn-";
    private static final String CHANNEL_PREFIX = "m4-rt-ch-";
    private static final String MODEL_PREFIX = "m4-rt-model-";

    private static final String ROUTE_TARGET_TYPE = "ROUTE";
    private static final String RATE_LIMIT_TARGET_TYPE = "RATE_LIMIT";

    private static final String ACTOR_TYPE = "USER";
    private static final long USER_ID = 1L;

    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";

    private static final String ROUTES = "/api/routes";
    private static final String RATE_LIMITS = "/api/rate-limits";

    /** 一个与 {@code api_key} 表无外键的合成 key 级维度（{@code rate_limit_policy.api_key_id} 只存 id）。 */
    private static final long KEY_LEVEL_API_KEY_ID = 4_242_424_242L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 收齐「有界窗口」用的**静默窗口**：拿到第一条真消息后，继续 poll 直到连续这么多时间没有新消息，
     * 就把窗口判定为「已经没有更多消息了」。它只用于把窗口**收完整**，不用于「等它发生」——
     * 「等它发生」由 {@link #MESSAGE_TIMEOUT} 负责。
     */
    private static final Duration QUIET_WINDOW = Duration.ofSeconds(2);

    private static final Duration SUBSCRIPTION_TIMEOUT = Duration.ofSeconds(5);
    private static final String SUBSCRIPTION_PROBE = "subscription-probe";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ModelRouteMapper modelRouteMapper;

    @Autowired
    private RateLimitPolicyMapper rateLimitPolicyMapper;

    @Autowired
    private ChannelMapper channelMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private ConsoleTokenService consoleTokenService;

    private RedisMessageListenerContainer listenerContainer;

    /** 唯一夹具名前后各清一次：容器是 JVM 级共享的，不依赖「表是干净的」。 */
    @BeforeEach
    @AfterEach
    void removeFixtures() {
        List<Long> tenantIds = tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
                        .likeRight(TenantEntity::getName, TENANT_PREFIX)).stream()
                .map(TenantEntity::getId).toList();
        if (!tenantIds.isEmpty()) {
            rateLimitPolicyMapper.delete(new LambdaQueryWrapper<RateLimitPolicyEntity>()
                    .in(RateLimitPolicyEntity::getTenantId, tenantIds));
        }
        tenantMapper.delete(new LambdaQueryWrapper<TenantEntity>()
                .likeRight(TenantEntity::getName, TENANT_PREFIX));
        modelRouteMapper.delete(new LambdaQueryWrapper<ModelRouteEntity>()
                .likeRight(ModelRouteEntity::getModelName, MODEL_PREFIX));
        channelMapper.delete(new LambdaQueryWrapper<ChannelEntity>()
                .likeRight(ChannelEntity::getName, CHANNEL_PREFIX));
    }

    @AfterEach
    void stopListenerContainer() throws Exception {
        if (listenerContainer != null) {
            listenerContainer.destroy();
            listenerContainer = null;
        }
    }

    // ---------------------------------------------------------------- 1) 重复路由 ⇒ 400 INVALID_PARAM

    /**
     * 唯一键 {@code uk_model_route(model_name, channel_id)} 被违反 ⇒ **400 {@code INVALID_PARAM}**
     * （{@code ErrorCode} 里**没有** 409）。核心判据是「必须把底层唯一键冲突翻译成 BizException」，
     * 否则会落到 {@code GlobalExceptionHandler} 的兜底分支变成 **500**。判别力由变异体
     * {@code M4}（去掉翻译、直接 insert）提供：去掉之后第二条的 status 是 500 ≠ 400，本用例精确变红。
     */
    @Test
    void creatingARouteTwiceForTheSameModelAndChannelIs400InvalidParam() throws Exception {
        long channelId = createChannel();
        String modelName = uniqueModelName();

        ResponseEntity<String> first = post(ROUTES, routeBody(modelName, channelId, null, null, null), null);
        assertThat(first.getStatusCode()).as("首次创建路由必须 200（响应体=%s）", first.getBody())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = post(ROUTES, routeBody(modelName, channelId, null, null, null), null);
        assertThat(second.getStatusCode())
                .as("同一 (model_name, channel_id) 重复创建必须 400，不是 409、更不是 500（响应体=%s）", second.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode envelope = body(second);
        assertThat(envelope.path("code").asText()).as("重复路由必须回 INVALID_PARAM").isEqualTo("INVALID_PARAM");
        String message = envelope.path("message").asText();
        assertThat(message).as("重复路由的 message 必须可读（非空）").isNotBlank();
        assertThat(message).as("message 不许泄漏 SQL/约束名（实际=%s）", message)
                .doesNotContainIgnoringCase("sql")
                .doesNotContainIgnoringCase("insert")
                .doesNotContainIgnoringCase("jdbc")
                .doesNotContain("uk_model_route");

        // 被拒的第二次绝不许留下第二行：同一 (model_name, channel_id) 只能有一行。
        assertThat(modelRouteMapper.selectCount(new LambdaQueryWrapper<ModelRouteEntity>()
                .eq(ModelRouteEntity::getModelName, modelName)
                .eq(ModelRouteEntity::getChannelId, channelId)))
                .as("重复创建被拒后，同一 (model_name, channel_id) 必须恰好只有一行")
                .isEqualTo(1L);
    }

    // ---------------------------------------------------------------- 2) 同维度只留一条 ACTIVE

    /**
     * {@code upsert} 写入新策略前必须把**同一维度**的旧 ACTIVE 行置 {@code INACTIVE}（M3 决策 17：
     * 「同维度取最后一条 = 取 id 最大那条」需要历史行仍在，但只允许一条候选）。判别力由变异体
     * {@code M1}（只插新行、不停用旧行）提供：变异后第一条仍是 ACTIVE，{@code :164} 精确变红。
     */
    @Test
    void updatingATenantPolicyDeactivatesThePreviousActiveRowForThatDimension() throws Exception {
        long tenantId = createTenant();

        long first = postTenantPolicy(tenantId, 10, 20);
        long second = postTenantPolicy(tenantId, 5, 10);

        assertThat(rateLimitPolicyMapper.selectById(first).getStatus())
                .as("upsert 必须把同维度旧 ACTIVE 行置 INACTIVE（否则同维度存在两条 ACTIVE）")
                .isEqualTo(INACTIVE);
        assertThat(rateLimitPolicyMapper.selectById(second).getStatus()).isEqualTo(ACTIVE);
        assertThat(activeRowsFor(tenantId, null))
                .as("租户级维度（api_key_id IS NULL）任何时刻只能有一条 ACTIVE 策略")
                .hasSize(1);
    }

    // ---------------------------------------------------------------- 2b) 跨租户隔离：A 的 upsert 绝不停用 B 的

    /**
     * {@code docs/CONVENTIONS.md} §10 的 **R2 租户隔离**：{@code deactivateActiveRows} 必须**只**停用
     * **同一租户、同一维度**的旧 ACTIVE 行 —— 租户维度对 {@code rate_limit_policy} 是**硬隔离**。
     * 租户 A 与 B **各**有一条**租户级** ACTIVE 策略；对 **A** 做一次 upsert ⇒ **B 的那条必须仍是
     * ACTIVE**（且 B 的租户级维度仍恰好一条 ACTIVE）。
     *
     * <p>判别力由变异体 {@code F3}（从 {@code deactivateActiveRows} 删掉
     * {@code .eq(RateLimitPolicyEntity::getTenantId, tenantId)}）提供：变异后 A 的 upsert 会停用
     * **所有租户**的租户级 ACTIVE 行，B 的那条被误停 ⇒ 本用例的「B 仍是 ACTIVE」精确变红。
     */
    @Test
    void upsertingOneTenantsPolicyDoesNotDeactivateAnotherTenantsActiveRow() throws Exception {
        long tenantA = createTenant();
        long tenantB = createTenant();
        long aOld = postTenantPolicy(tenantA, 10, 20);
        long bPolicy = postTenantPolicy(tenantB, 30, 40);

        // 对 A 做 upsert：只许动 A 自己的租户级旧行。
        long aNew = postTenantPolicy(tenantA, 5, 6);

        assertThat(rateLimitPolicyMapper.selectById(aOld).getStatus())
                .as("A 自己的同维度旧 ACTIVE 行必须被置 INACTIVE").isEqualTo(INACTIVE);
        assertThat(rateLimitPolicyMapper.selectById(aNew).getStatus()).isEqualTo(ACTIVE);
        assertThat(activeRowsFor(tenantA, null)).as("A 的租户级维度仍恰好一条 ACTIVE").hasSize(1);

        assertThat(rateLimitPolicyMapper.selectById(bPolicy).getStatus())
                .as("跨租户隔离（§10 R2）：对 A 的 upsert 绝不许停用 B 的租户级 ACTIVE 行")
                .isEqualTo(ACTIVE);
        assertThat(activeRowsFor(tenantB, null)).as("B 的租户级维度仍恰好一条 ACTIVE").hasSize(1);
    }

    // ---------------------------------------------------------------- 3) 两个维度共存

    /**
     * 维度 = {@code (tenant_id, api_key_id)}，其中 {@code api_key_id IS NULL} 是租户级、非空是 key 级：
     * ①两条维度可以**同时 ACTIVE**；②再 upsert 租户级只会停用**租户级**那条，key 级不受影响。
     * 判别力由变异体 {@code M2}（停用时不区分维度）提供：变异后 key 级那条也被置 INACTIVE，
     * 「key 级仍是 ACTIVE」精确变红。
     */
    @Test
    void aKeyLevelPolicyAndATenantLevelPolicyCoexist() throws Exception {
        long tenantId = createTenant();

        long tenantLevel = postTenantPolicy(tenantId, 10, 20);
        long keyLevel = postKeyPolicy(tenantId, KEY_LEVEL_API_KEY_ID, 7, 9);

        assertThat(rateLimitPolicyMapper.selectById(tenantLevel).getStatus())
                .as("租户级策略第一次写入后必须 ACTIVE").isEqualTo(ACTIVE);
        assertThat(rateLimitPolicyMapper.selectById(keyLevel).getStatus())
                .as("key 级策略第一次写入后必须 ACTIVE").isEqualTo(ACTIVE);

        // 再 upsert 一次租户级：只许停用租户级那条。
        long tenantLevelAgain = postTenantPolicy(tenantId, 3, 4);

        assertThat(rateLimitPolicyMapper.selectById(tenantLevel).getStatus())
                .as("同一维度（租户级）的旧行必须被置 INACTIVE").isEqualTo(INACTIVE);
        assertThat(rateLimitPolicyMapper.selectById(tenantLevelAgain).getStatus()).isEqualTo(ACTIVE);
        assertThat(rateLimitPolicyMapper.selectById(keyLevel).getStatus())
                .as("停用必须区分维度：另一维度（key 级）那条绝不许被误停（isNull vs eq）")
                .isEqualTo(ACTIVE);
        assertThat(activeRowsFor(tenantId, null))
                .as("租户级维度仍恰好一条 ACTIVE").hasSize(1);
        assertThat(activeRowsFor(tenantId, KEY_LEVEL_API_KEY_ID))
                .as("key 级维度仍恰好一条 ACTIVE").hasSize(1);
    }

    // ---------------------------------------------------------------- 4) 写操作都广播失效（窗口内集合恰好一条）

    /**
     * 一次写 = **恰好**一次广播，且 reason 与 version 必须正确。断言不是「收到一条匹配的就过」，而是
     * 「把**整个有界窗口**的消息**收齐**，其 reason **集合恰好等于** {@code {期望 reason}}」——
     * 这样才满足计划 {@code :1611} 的「断言不出现**别的** reason」（只断言「恰好一条」是 reason 受限的，
     * 多发一条别的 reason 不会被发现）。
     * <ul>
     *   <li>建一条路由 → 窗口内集合恰好 {@code {route.create}}，且 {@code version > 0}；</li>
     *   <li>upsert#1（无旧行可停用）→ 窗口内集合恰好 {@code {rate_limit.create}}；</li>
     *   <li>upsert#2（**先停用旧行、再插新行**，两行两次落库）→ 仍恰好 {@code {rate_limit.create}}
     *       （一次写 = 一次广播）；</li>
     *   <li>订阅后先用哨兵 {@code awaitSubscription(...)} 确认订阅建立，否则第一条真消息会被静默丢掉。</li>
     * </ul>
     * 判别力由变异体 {@code F4}（在 {@code upsert} 的 insert 前多发一条**不同 reason**
     * {@code rate_limit.deactivate}）提供：窗口内集合变成两条 ⇒ {@code :352} 一类的「集合恰好等于」断言
     * 精确变红。（初版这里只做 reason 受限的「恰好一条」，见 {@code .m4t10review-logs} 的存活变异 {@code N3b}。）
     */
    @Test
    void bothWritesPublishExactlyOneInvalidationMessage() throws Exception {
        BlockingQueue<String> received = subscribeAndAwait();

        long channelId = createChannel();
        long routeId = postRoute(uniqueModelName(), channelId);
        assertThat(routeId).isPositive();

        List<ConfigInvalidateMessage> routeWindow = drainBoundedWindow(received);
        assertThat(routeWindow).extracting(ConfigInvalidateMessage::reason)
                .as("建路由必须广播 reason=route.create，且窗口内不许出现别的 reason")
                .containsExactlyInAnyOrder("route.create");
        assertThat(routeWindow).allSatisfy(m -> assertThat(m.version())
                .as("失效消息必须带一个真实的水位版本号（reason=%s）", m.reason()).isPositive());

        long tenantId = createTenant();
        postTenantPolicy(tenantId, 10, 20);
        List<ConfigInvalidateMessage> createOnce = drainBoundedWindow(received);
        assertThat(createOnce).extracting(ConfigInvalidateMessage::reason)
                .as("upsert#1 只落一行（无旧行可停用）= 恰好一条 rate_limit.create")
                .containsExactlyInAnyOrder("rate_limit.create");
        assertThat(createOnce).allSatisfy(m -> assertThat(m.version())
                .as("失效消息必须带真实水位版本号（reason=%s）", m.reason()).isPositive());

        // upsert#2：先停用旧 ACTIVE 行、再插新行 —— 两行两次落库，仍只许一条广播。
        postTenantPolicy(tenantId, 5, 10);
        List<ConfigInvalidateMessage> createTwice = drainBoundedWindow(received);
        assertThat(createTwice).extracting(ConfigInvalidateMessage::reason)
                .as("一次写 = 一次广播：upsert 的两行落库只许发一条 rate_limit.create（多发别的 reason 也红）")
                .containsExactlyInAnyOrder("rate_limit.create");
        assertThat(createTwice).allSatisfy(m -> assertThat(m.version())
                .as("失效消息必须带真实水位版本号（reason=%s）", m.reason()).isPositive());
    }

    // ---------------------------------------------------------------- 4b) route.update / route.delete 也各广播一条

    /**
     * 判据④「写操作都广播」对 {@code route.update} 与 {@code route.delete} 同样成立：各**恰好一条**、
     * reason 正确、{@code version > 0}。
     *
     * <p>判别力由变异体 {@code F2a}（删 {@code update} 里 {@code publishAfterCommit("route.update")}）
     * 与 {@code F2b}（删 {@code delete} 里 {@code publishAfterCommit("route.delete")}）提供：删掉对应那条，
     * 本用例里对应窗口的第一个 poll 就变红（{@code .m4t10review-logs} 的存活变异 {@code N8} 即删
     * {@code route.update} 而全类曾 14/0 全绿）。
     */
    @Test
    void routeUpdateAndDeleteEachPublishExactlyOneInvalidationMessage() throws Exception {
        long channelId = createChannel();
        // 两条路由在**订阅之前**建好：setup 的 route.create 广播没有监听者，不会污染后面的有界窗口。
        long toUpdate = postRoute(uniqueModelName(), channelId);
        long toDelete = postRoute(uniqueModelName(), channelId);

        BlockingQueue<String> received = subscribeAndAwait();

        // ---- route.update ----
        ResponseEntity<String> updated = put(ROUTES + "/" + toUpdate, Map.of("weight", 7), CLIENT_TENANT);
        assertThat(updated.getStatusCode()).as("PUT 路由必须 200（响应体=%s）", updated.getBody())
                .isEqualTo(HttpStatus.OK);
        List<ConfigInvalidateMessage> updateWindow = drainBoundedWindow(received);
        assertThat(updateWindow).extracting(ConfigInvalidateMessage::reason)
                .as("PUT 路由 = 窗口内恰好一条 route.update")
                .containsExactlyInAnyOrder("route.update");
        assertThat(updateWindow).allSatisfy(m -> assertThat(m.version())
                .as("失效消息必须带真实水位版本号（reason=%s）", m.reason()).isPositive());

        // ---- route.delete ----
        ResponseEntity<String> deleted = delete(ROUTES + "/" + toDelete, null);
        assertThat(deleted.getStatusCode()).as("DELETE 路由必须 200（响应体=%s）", deleted.getBody())
                .isEqualTo(HttpStatus.OK);
        List<ConfigInvalidateMessage> deleteWindow = drainBoundedWindow(received);
        assertThat(deleteWindow).extracting(ConfigInvalidateMessage::reason)
                .as("DELETE 路由 = 窗口内恰好一条 route.delete")
                .containsExactlyInAnyOrder("route.delete");
        assertThat(deleteWindow).allSatisfy(m -> assertThat(m.version())
                .as("失效消息必须带真实水位版本号（reason=%s）", m.reason()).isPositive());
    }

    // ---------------------------------------------------------------- 4c) rate_limit.update / rate_limit.deactivate 也各广播一条

    /**
     * 判据④ 对 {@code rate_limit.update} 与 {@code rate_limit.deactivate} 同样成立。
     *
     * <p>判别力由变异体 {@code F2c}（删 {@code update} 里 {@code publishAfterCommit("rate_limit.update")}）
     * 与 {@code F2d}（删 {@code deactivate} 里 {@code publishAfterCommit("rate_limit.deactivate")}）提供。
     * 两条策略刻意**不同维度**（租户级 + key 级），以便两条在订阅后都仍是 ACTIVE（PUT 要求目标仍 ACTIVE）。
     */
    @Test
    void rateLimitUpdateAndDeactivateEachPublishExactlyOneInvalidationMessage() throws Exception {
        long tenantId = createTenant();
        // upsert#1 建租户级、upsert#2 建 key 级（不同维度，不会互相停用）；都在订阅之前。
        long toUpdate = postTenantPolicy(tenantId, 10, 20);
        long toDeactivate = postKeyPolicy(tenantId, KEY_LEVEL_API_KEY_ID, 30, 40);

        BlockingQueue<String> received = subscribeAndAwait();

        // ---- rate_limit.update ----
        ResponseEntity<String> updated = put(RATE_LIMITS + "/" + toUpdate, Map.of("qps", 55, "burst", 66), tenantId);
        assertThat(updated.getStatusCode()).as("PUT 策略必须 200（响应体=%s）", updated.getBody())
                .isEqualTo(HttpStatus.OK);
        List<ConfigInvalidateMessage> updateWindow = drainBoundedWindow(received);
        assertThat(updateWindow).extracting(ConfigInvalidateMessage::reason)
                .as("PUT 策略 = 窗口内恰好一条 rate_limit.update")
                .containsExactlyInAnyOrder("rate_limit.update");
        assertThat(updateWindow).allSatisfy(m -> assertThat(m.version())
                .as("失效消息必须带真实水位版本号（reason=%s）", m.reason()).isPositive());

        // ---- rate_limit.deactivate ----
        ResponseEntity<String> deactivated = delete(RATE_LIMITS + "/" + toDeactivate, tenantId);
        assertThat(deactivated.getStatusCode()).as("DELETE 策略必须 200（响应体=%s）", deactivated.getBody())
                .isEqualTo(HttpStatus.OK);
        List<ConfigInvalidateMessage> deactivateWindow = drainBoundedWindow(received);
        assertThat(deactivateWindow).extracting(ConfigInvalidateMessage::reason)
                .as("DELETE 策略 = 窗口内恰好一条 rate_limit.deactivate")
                .containsExactlyInAnyOrder("rate_limit.deactivate");
        assertThat(deactivateWindow).allSatisfy(m -> assertThat(m.version())
                .as("失效消息必须带真实水位版本号（reason=%s）", m.reason()).isPositive());
    }

    // ---------------------------------------------------------------- 5) R1 全局 / R3.2 令牌租户

    /**
     * R1：{@code GET /api/routes} 是**全局**列表（任何 {@code ADMIN} 看全部）。
     * R3.2：{@code GET /api/rate-limits} 缺省只回**令牌租户**的行（least privilege）。
     * 判别力由变异体 {@code M11}（列表忽略令牌租户、回全部行）提供：变异后另一个租户的策略也会出现，
     * {@code :340} 精确变红。
     */
    @Test
    void routeListIsGlobalButRateLimitListDefaultsToTheTokenTenant() throws Exception {
        long tokenTenant = createTenant();
        long otherTenant = createTenant();

        long channelId = createChannel();
        long routeId = postRoute(uniqueModelName(), channelId);

        // 写是平台级（R2）：用 tokenTenant 的令牌写 otherTenant 的策略。
        long otherPolicy = postPolicy(policyBody(otherTenant, null, 1, 1), tokenTenant);
        long myPolicy = postPolicy(policyBody(tokenTenant, null, 2, 2), tokenTenant);

        // R1：路由是全局资源 —— tokenTenant 的令牌也看得见刚建的路由。
        JsonNode routes = body(get(ROUTES, tokenTenant)).path("data");
        assertThat(findById(routes, routeId))
                .as("R1：model_route 是全局资源，任何 ADMIN 必须能在列表里看到它").isNotNull();

        // R3.2：限流策略列表缺省 = 令牌里的 tenantId。
        JsonNode list = body(get(RATE_LIMITS, tokenTenant)).path("data");
        assertThat(findById(list, myPolicy)).as("令牌租户必须看得见自己的策略").isNotNull();
        assertThat(findById(list, otherPolicy))
                .as("R3.2：缺省列表绝不许出现**另一个租户**的策略（least privilege）").isNull();
        assertThat(containsTenant(list, otherTenant))
                .as("R3.2：缺省列表里的每一项 tenantId 都必须等于令牌租户，实际=%s", list).isFalse();
    }

    // ---------------------------------------------------------------- 6) 取值校验与悬挂路由

    /** 路由引用的渠道必须存在（{@code model_route.channel_id} 没有外键）⇒ 不存在 → 404 {@code NOT_FOUND}。 */
    @Test
    void postingARouteWithAnUnknownChannelIs404NotFound() throws Exception {
        ResponseEntity<String> res = post(ROUTES, routeBody(uniqueModelName(), 9_000_000_000L, null, null, null), null);

        assertThat(res.getStatusCode()).as("悬挂路由必须 404（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(res).path("code").asText()).isEqualTo("NOT_FOUND");
    }

    /** {@code status} 只接受 {@code ACTIVE}/{@code INACTIVE}，其它一律 400 {@code INVALID_PARAM}。 */
    @Test
    void postingARouteWithAnIllegalStatusIs400InvalidParam() throws Exception {
        long channelId = createChannel();

        ResponseEntity<String> res = post(ROUTES, routeBody(uniqueModelName(), channelId, null, null, "BOGUS"), null);

        assertThat(res.getStatusCode()).as("非法 status 必须 400（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(res).path("code").asText()).isEqualTo("INVALID_PARAM");

        // 正向对照：合法 status 必须被接受（否则「400」可能只是「一律拒绝」）。
        ResponseEntity<String> ok = post(ROUTES, routeBody(uniqueModelName(), channelId, null, null, ACTIVE), null);
        assertThat(ok.getStatusCode()).as("合法 status=ACTIVE 必须被接受（响应体=%s）", ok.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    /** {@code weight}/{@code priority}/{@code qps}/{@code burst} 必须 ≥ 0，负数一律 400。 */
    @Test
    void negativeNumbersAre400InvalidParam() throws Exception {
        long channelId = createChannel();

        ResponseEntity<String> negativeWeight = post(ROUTES,
                routeBody(uniqueModelName(), channelId, -1, null, null), null);
        assertThat(negativeWeight.getStatusCode()).as("负 weight 必须 400（响应体=%s）", negativeWeight.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(negativeWeight).path("code").asText()).isEqualTo("INVALID_PARAM");

        ResponseEntity<String> negativePriority = post(ROUTES,
                routeBody(uniqueModelName(), channelId, null, -5, null), null);
        assertThat(negativePriority.getStatusCode()).as("负 priority 必须 400（响应体=%s）", negativePriority.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        long tenantId = createTenant();
        ResponseEntity<String> negativeQps = post(RATE_LIMITS,
                policyBody(tenantId, null, -3, 1), tenantId);
        assertThat(negativeQps.getStatusCode()).as("负 qps 必须 400（响应体=%s）", negativeQps.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> negativeBurst = post(RATE_LIMITS,
                policyBody(tenantId, null, 1, -2), tenantId);
        assertThat(negativeBurst.getStatusCode()).as("负 burst 必须 400（响应体=%s）", negativeBurst.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // 正向对照：合法数值必须被接受。
        ResponseEntity<String> ok = post(ROUTES, routeBody(uniqueModelName(), channelId, 0, 0, null), null);
        assertThat(ok.getStatusCode()).as("合法 weight/priority=0 必须被接受（响应体=%s）", ok.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- 7) DELETE 的两种语义

    /** {@code DELETE /api/routes/{id}} = **真删行**（与 Task 9 的 api-keys 一致）。 */
    @Test
    void deletingARouteReallyDeletesTheRow() throws Exception {
        long channelId = createChannel();
        long routeId = postRoute(uniqueModelName(), channelId);

        ResponseEntity<String> res = delete(ROUTES + "/" + routeId, null);
        assertThat(res.getStatusCode()).as("删除路由必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(modelRouteMapper.selectById(routeId))
                .as("DELETE 路由必须真的把那一行删掉（不是软停用）").isNull();
    }

    /**
     * {@code DELETE /api/rate-limits/{id}} = **置 {@code INACTIVE}（软停用、不删行）**
     * ——依据是 {@code AuditAction} 里只有 {@code RATE_LIMIT_DEACTIVATE}，且「同维度取最后一条」
     * 需要历史行仍在。判别力由变异体 {@code M9}（改成真删行）提供：变异后 {@code selectById} 为 null，
     * 「行仍在」断言精确变红。
     */
    @Test
    void deactivatingARateLimitPolicySoftSetsInactiveAndKeepsTheRow() throws Exception {
        long tenantId = createTenant();
        long policyId = postTenantPolicy(tenantId, 11, 22);

        ResponseEntity<String> res = delete(RATE_LIMITS + "/" + policyId, tenantId);
        assertThat(res.getStatusCode()).as("停用策略必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);

        RateLimitPolicyEntity row = rateLimitPolicyMapper.selectById(policyId);
        assertThat(row).as("软停用**不删行**：历史行必须仍在（M3「同维度取最后一条」需要它）").isNotNull();
        assertThat(row.getStatus()).as("DELETE 策略 = 置 INACTIVE").isEqualTo(INACTIVE);
        assertThat(row.getQps()).as("软停用不许改动数值列").isEqualTo(11);
    }

    // ---------------------------------------------------------------- 8) PUT 策略：只改 qps/burst、非 ACTIVE 404

    /**
     * {@code PUT /api/rate-limits/{id}} 只改 {@code qps}/{@code burst}；且该行必须仍是 {@code ACTIVE}，
     * 否则 404。判别力由变异体 {@code M10}（去掉 ACTIVE 检查）提供：变异后停用行也能被改，第二次 PUT
     * 返回 200 ≠ 404，本用例精确变红。
     */
    @Test
    void updatingAPolicyOnlyChangesQpsAndBurstAnd404sWhenNotActive() throws Exception {
        long tenantId = createTenant();
        long policyId = postTenantPolicy(tenantId, 10, 20);

        ResponseEntity<String> res = put(RATE_LIMITS + "/" + policyId, Map.of("qps", 33, "burst", 44), tenantId);
        assertThat(res.getStatusCode()).as("更新策略必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);
        RateLimitPolicyEntity updated = rateLimitPolicyMapper.selectById(policyId);
        assertThat(updated.getQps()).as("PUT 必须真的把 qps 落库").isEqualTo(33);
        assertThat(updated.getBurst()).as("PUT 必须真的把 burst 落库").isEqualTo(44);
        assertThat(updated.getStatus()).as("PUT 不改状态").isEqualTo(ACTIVE);
        assertThat(updated.getTenantId()).as("PUT 不改租户维度").isEqualTo(tenantId);
        assertThat(updated.getApiKeyId()).as("PUT 不改维度：仍是租户级").isNull();

        // 停用后不许再被更新：必须 404。
        assertThat(delete(RATE_LIMITS + "/" + policyId, tenantId).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> afterDeactivate = put(RATE_LIMITS + "/" + policyId, Map.of("qps", 99), tenantId);
        assertThat(afterDeactivate.getStatusCode())
                .as("非 ACTIVE 的策略不许被更新，必须 404（响应体=%s）", afterDeactivate.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- 9) 审计的租户语义

    /**
     * 审计 {@code tenant_id} 的语义（{@code CONVENTIONS} §10）：
     * 路由写记 SQL {@code NULL}（R1，全局资源）；策略写记**该策略的** {@code tenant_id}（R2）。
     * 路由审计查询必须用 {@code isNull()}（{@code eq(tenantId,null)} 恒不成立、会静默 0 行）。
     */
    @Test
    void writesRecordAuditRowsWithTheRightTenantSemantics() throws Exception {
        long channelId = createChannel();
        long routeId = postRoute(uniqueModelName(), channelId);

        AuditLogEntity routeAudit = onlyRouteAuditRow(AuditAction.ROUTE_CREATE, routeId);
        assertActorIsTheConsoleAdmin(routeAudit);
        assertThat(routeAudit.getTenantId())
                .as("R1：路由是全局资源，审计 tenant_id 必须是 SQL NULL（不是 0、也不是操作者的租户）")
                .isNull();

        long tenantId = createTenant();
        long policyId = postTenantPolicy(tenantId, 6, 7);
        AuditLogEntity policyAudit = onlyPolicyAuditRow(AuditAction.RATE_LIMIT_CREATE, policyId, tenantId);
        assertActorIsTheConsoleAdmin(policyAudit);
        assertThat(policyAudit.getTenantId())
                .as("R2：策略的审计 tenant_id 必须记**该策略的**租户 id（不是 NULL、不是操作者的租户）")
                .isEqualTo(tenantId);
    }

    // ---------------------------------------------------------------- 9b) PUT 路由：应用取值 + 未知 id 404

    /**
     * {@code PUT /api/routes/{id}} 必须真的把 {@code weight}/{@code priority}/{@code status} 落库，
     * 且未知 id 返回 404。判别力由变异体 {@code M12}（update 里不应用 weight、始终取默认值）提供：
     * 变异后 {@code weight} 仍是 100，`isEqualTo(7)` 精确变红。
     *
     * <p><b>登记（覆盖缺口用例）</b>：本条在实现落地之后才补上（Task 9 修复轮同款先例）—— 计划 Step 1
     * 只草拟了 4 条用例，路由 PUT 不在其中；因此它**没有经历自己的 RED**，可证伪性由变异体 {@code M12}
     * 提供，而不是由「先写会红的测试」提供。
     */
    @Test
    void updatingARouteAppliesWeightPriorityAndStatusAnd404sForUnknownId() throws Exception {
        long channelId = createChannel();
        long routeId = postRoute(uniqueModelName(), channelId);

        ResponseEntity<String> res = put(ROUTES + "/" + routeId,
                Map.of("weight", 7, "priority", 5, "status", INACTIVE), CLIENT_TENANT);
        assertThat(res.getStatusCode()).as("更新路由必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);

        ModelRouteEntity updated = modelRouteMapper.selectById(routeId);
        assertThat(updated.getWeight()).as("PUT 必须真的把 weight 落库").isEqualTo(7);
        assertThat(updated.getPriority()).as("PUT 必须真的把 priority 落库").isEqualTo(5);
        assertThat(updated.getStatus()).as("PUT 必须真的把 status 落库").isEqualTo(INACTIVE);

        ResponseEntity<String> missing = put(ROUTES + "/9000000000", Map.of("weight", 1), CLIENT_TENANT);
        assertThat(missing.getStatusCode()).as("未知 id 的 PUT 必须 404（响应体=%s）", missing.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- 9c) PUT 路由撞唯一键：翻译 + 不半改

    /**
     * {@code PUT /api/routes/{id}} 也可能撞 {@code uk_model_route(model_name, channel_id)}（因为 PUT 允许
     * 改 {@code modelName}/{@code channelId}，是已接受的偏差）⇒ **必须**把 {@link DuplicateKeyException}
     * 翻译成 **400 {@code INVALID_PARAM}**（未翻译会落到兜底分支变 **500**）；message 可读且**不含
     * SQL / 约束名**；事务回滚，那条路由**没被半改**、A 也不受影响。
     *
     * <p>判别力由变异体 {@code F1}（把 {@code updateRoute(entity)} 换成直接 {@code updateById}，去掉翻译）
     * 提供：去掉之后 PUT 返回 500 ≠ 400，本用例精确变红（{@code .m4t10review-logs} 的存活变异 {@code N5}
     * 即此，而当时全类 14/0 全绿 —— 本用例正是补上这一覆盖缺口）。
     */
    @Test
    void updatingARouteOntoAnotherRoutesModelAndChannelIs400InvalidParamWithoutHalfWriting() throws Exception {
        long channelA = createChannel();
        long channelB = createChannel();
        String modelA = uniqueModelName();
        String modelB = uniqueModelName();
        long routeA = postRoute(modelA, channelA);
        long routeB = postRoute(modelB, channelB);

        // 把 B 改到 A 的 (model_name, channel_id)：制造 uk_model_route 唯一键冲突。
        Map<String, Object> collision = new HashMap<>();
        collision.put("modelName", modelA);
        collision.put("channelId", channelA);
        ResponseEntity<String> res = put(ROUTES + "/" + routeB, collision, CLIENT_TENANT);

        assertThat(res.getStatusCode())
                .as("PUT 撞唯一键必须 400，不是 409、更不是 500（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode envelope = body(res);
        assertThat(envelope.path("code").asText()).as("PUT 撞唯一键必须回 INVALID_PARAM").isEqualTo("INVALID_PARAM");
        String message = envelope.path("message").asText();
        assertThat(message).as("PUT 撞唯一键的 message 必须可读（非空）").isNotBlank();
        assertThat(message).as("message 不许泄漏 SQL / 约束名（实际=%s）", message)
                .doesNotContainIgnoringCase("sql")
                .doesNotContainIgnoringCase("duplicate")
                .doesNotContainIgnoringCase("jdbc")
                .doesNotContain("uk_model_route");

        // 事务必须回滚：B 仍是旧值（没被半改），A 也不受影响。
        ModelRouteEntity stillB = modelRouteMapper.selectById(routeB);
        assertThat(stillB).as("被拒的 PUT 绝不许把目标行删掉").isNotNull();
        assertThat(stillB.getModelName()).as("PUT 被拒后 B 必须保持旧 modelName").isEqualTo(modelB);
        assertThat(stillB.getChannelId()).as("PUT 被拒后 B 必须保持旧 channelId").isEqualTo(channelB);
        assertThat(modelRouteMapper.selectById(routeA).getModelName()).as("A 不受影响").isEqualTo(modelA);
    }

    // ---------------------------------------------------------------- 10) 夹具自守

    /**
     * 夹具名前缀里的 {@code _} 对 LIKE 是**单字符通配符**，{@code %} 与 {@code \} 同理。本类的清理与
     * 审计查询都建立在「前缀只含字面量」这条**假设**上，所以这里把它变成可执行事实。
     */
    @Test
    void fixtureNamesContainNoLikeMetacharacters() {
        for (int i = 0; i < 8; i++) {
            assertThat(uniqueModelName()).as("夹具 model 名不许含 LIKE 元字符")
                    .doesNotContain("%").doesNotContain("_").doesNotContain("\\");
            assertThat(TENANT_PREFIX).as("夹具租户名前缀不许含 LIKE 元字符")
                    .doesNotContain("%").doesNotContain("_").doesNotContain("\\");
            assertThat(CHANNEL_PREFIX).as("夹具渠道名前缀不许含 LIKE 元字符")
                    .doesNotContain("%").doesNotContain("_").doesNotContain("\\");
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

    private long createChannel() {
        ChannelEntity channel = new ChannelEntity();
        channel.setName(CHANNEL_PREFIX + UUID.randomUUID().toString().substring(0, 8));
        channel.setProvider("openai-compatible");
        channel.setBaseUrl("http://127.0.0.1:1");
        channel.setApiKeyCipher("v1:fixture-not-used-by-this-suite");
        channel.setKeyVersion(1);
        channel.setWeight(100);
        channel.setPriority(0);
        channel.setTimeoutMs(60_000);
        channel.setStatus(ACTIVE);
        channelMapper.insert(channel);
        assertThat(channel.getId()).as("MyBatis-Plus 必须把自增主键回填进实体").isNotNull();
        return channel.getId();
    }

    private static String uniqueModelName() {
        return MODEL_PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> routeBody(String modelName, Long channelId, Integer weight,
                                                  Integer priority, String status) {
        Map<String, Object> body = new HashMap<>();
        body.put("modelName", modelName);
        body.put("channelId", channelId);
        if (weight != null) {
            body.put("weight", weight);
        }
        if (priority != null) {
            body.put("priority", priority);
        }
        if (status != null) {
            body.put("status", status);
        }
        return body;
    }

    private static Map<String, Object> policyBody(long tenantId, Long apiKeyId, Integer qps, Integer burst) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId);
        if (apiKeyId != null) {
            body.put("apiKeyId", apiKeyId);
        }
        if (qps != null) {
            body.put("qps", qps);
        }
        if (burst != null) {
            body.put("burst", burst);
        }
        return body;
    }

    /** 走 HTTP 建一条路由，断言 200 并回数值主键。 */
    private long postRoute(String modelName, long channelId) throws Exception {
        ResponseEntity<String> res = post(ROUTES, routeBody(modelName, channelId, null, null, null), null);
        assertThat(res.getStatusCode()).as("建路由必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(res).path("data").path("id").asLong();
    }

    private long postTenantPolicy(long tenantId, int qps, int burst) throws Exception {
        return postPolicy(policyBody(tenantId, null, qps, burst), tenantId);
    }

    private long postKeyPolicy(long tenantId, long apiKeyId, int qps, int burst) throws Exception {
        return postPolicy(policyBody(tenantId, apiKeyId, qps, burst), tenantId);
    }

    /** 走 HTTP upsert 一条策略（令牌租户由调用方指定），断言 200 并回数值主键。 */
    private long postPolicy(Map<String, Object> body, long tokenTenantId) throws Exception {
        ResponseEntity<String> res = post(RATE_LIMITS, body, tokenTenantId);
        assertThat(res.getStatusCode()).as("upsert 策略必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(res).path("data").path("id").asLong();
    }

    /**
     * 「某维度内的 ACTIVE 行」：{@code apiKeyId == null} 表示**租户级**维度 —— 查它必须用
     * {@code isNull()}（{@code eq(column, null)} 恒不成立、会静默返回 0 行，CONVENTIONS §7）。
     */
    private List<RateLimitPolicyEntity> activeRowsFor(long tenantId, Long apiKeyId) {
        LambdaQueryWrapper<RateLimitPolicyEntity> wrapper = new LambdaQueryWrapper<RateLimitPolicyEntity>()
                .eq(RateLimitPolicyEntity::getTenantId, tenantId)
                .eq(RateLimitPolicyEntity::getStatus, ACTIVE);
        if (apiKeyId == null) {
            wrapper.isNull(RateLimitPolicyEntity::getApiKeyId);
        } else {
            wrapper.eq(RateLimitPolicyEntity::getApiKeyId, apiKeyId);
        }
        return rateLimitPolicyMapper.selectList(wrapper);
    }

    private static JsonNode findById(JsonNode array, long id) {
        if (array == null || !array.isArray()) {
            return null;
        }
        for (JsonNode node : array) {
            if (node.path("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    private static boolean containsTenant(JsonNode array, long tenantId) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode node : array) {
            if (node.path("tenantId").asLong() == tenantId) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode body(ResponseEntity<String> res) throws Exception {
        return MAPPER.readTree(res.getBody());
    }

    // ---------------------------------------------------------------- 审计断言（按本用例的 target_id 查）

    private AuditLogEntity onlyRouteAuditRow(String action, long routeId) {
        List<AuditLogEntity> rows = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, action)
                .eq(AuditLogEntity::getTargetType, ROUTE_TARGET_TYPE)
                .eq(AuditLogEntity::getTargetId, String.valueOf(routeId))
                .isNull(AuditLogEntity::getTenantId));
        assertThat(rows).as("路由写必须留下恰好一条 action=%s 且 tenant_id 为 SQL NULL 的审计行", action)
                .hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getTargetType()).as("审计行的 target_type 必须是 ROUTE").isEqualTo(ROUTE_TARGET_TYPE);
        assertThat(row.getTargetId()).as("审计行的 target_id 必须是被操作路由的数值主键")
                .isEqualTo(String.valueOf(routeId));
        return row;
    }

    private AuditLogEntity onlyPolicyAuditRow(String action, long policyId, long tenantId) {
        List<AuditLogEntity> rows = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, action)
                .eq(AuditLogEntity::getTargetType, RATE_LIMIT_TARGET_TYPE)
                .eq(AuditLogEntity::getTargetId, String.valueOf(policyId))
                .eq(AuditLogEntity::getTenantId, tenantId));
        assertThat(rows).as("策略写必须留下恰好一条 action=%s 且 tenant_id=%s 的审计行", action, tenantId)
                .hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getTargetType()).as("审计行的 target_type 必须是 RATE_LIMIT")
                .isEqualTo(RATE_LIMIT_TARGET_TYPE);
        assertThat(row.getTargetId()).as("审计行的 target_id 必须是被操作策略的数值主键")
                .isEqualTo(String.valueOf(policyId));
        assertThat(row.getTenantId()).as("策略写的 tenant_id 必须是该策略的租户 id").isEqualTo(tenantId);
        return row;
    }

    private static void assertActorIsTheConsoleAdmin(AuditLogEntity row) {
        assertThat(row.getActorType()).as("审计行的 actor_type 必须来自请求里的控制台主体").isEqualTo(ACTOR_TYPE);
        assertThat(row.getActor()).as("审计行的 actor 必须是控制台用户的 id").isEqualTo(String.valueOf(USER_ID));
    }

    // ---------------------------------------------------------------- 令牌与 HTTP 助手

    private String token(long tenantId) {
        long now = Instant.now().getEpochSecond();
        return consoleTokenService.issue(
                new ConsoleClaims(USER_ID, tenantId, ConsoleClaims.ROLE_ADMIN, now, now + 3_600L));
    }

    /** 客户端用的控制台令牌租户（路由是全局资源，令牌租户不影响它；此处用 1 即可）。 */
    private static final long CLIENT_TENANT = 1L;

    private ResponseEntity<String> post(String path, Object body, Long tokenTenantId) {
        long tenantId = tokenTenantId == null ? CLIENT_TENANT : tokenTenantId;
        return restTemplate.exchange(path, HttpMethod.POST, requestEntity(body, tenantId), String.class);
    }

    private ResponseEntity<String> put(String path, Object body, long tokenTenantId) {
        return restTemplate.exchange(path, HttpMethod.PUT, requestEntity(body, tokenTenantId), String.class);
    }

    private ResponseEntity<String> get(String path, long tokenTenantId) {
        return restTemplate.exchange(path, HttpMethod.GET, requestEntity(null, tokenTenantId), String.class);
    }

    private ResponseEntity<String> delete(String path, Long tokenTenantId) {
        long tenantId = tokenTenantId == null ? CLIENT_TENANT : tokenTenantId;
        return restTemplate.exchange(path, HttpMethod.DELETE, requestEntity(null, tenantId), String.class);
    }

    private HttpEntity<Object> requestEntity(Object body, long tokenTenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(tokenTenantId));
        return body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
    }

    // ---------------------------------------------------------------- 失效广播订阅

    private RedisMessageListenerContainer container() {
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(redisConnectionFactory);
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        return listenerContainer;
    }

    /** 装一条真 Redis 订阅，并在返回之前**确认订阅已经建立**（否则第一条真消息会被静默丢掉）。 */
    private BlockingQueue<String> subscribeAndAwait() throws InterruptedException {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        container().addMessageListener((message, channel) -> received.add(new String(message.getBody())),
                new ChannelTopic(ConfigInvalidateTopology.CHANNEL));
        awaitSubscription(received);
        return received;
    }

    private void awaitSubscription(BlockingQueue<String> received) throws InterruptedException {
        long deadline = System.nanoTime() + SUBSCRIPTION_TIMEOUT.toNanos();
        long sequence = 0L;
        while (System.nanoTime() < deadline) {
            String probe = SUBSCRIPTION_PROBE + "-" + sequence++;
            redisTemplate.convertAndSend(ConfigInvalidateTopology.CHANNEL, probe);
            if (probe.equals(received.poll(200, TimeUnit.MILLISECONDS))) {
                return;
            }
        }
        throw new IllegalStateException(
                "Redis 订阅在 " + SUBSCRIPTION_TIMEOUT + " 内没有建立，用例无法判定消息是否发出");
    }

    /**
     * 在一个**有界窗口**内收齐频道上的**全部**失效消息（不是「等到一条匹配的就返回」）。
     *
     * <p>语义：先至多等 {@link #MESSAGE_TIMEOUT} 拿到**第一条**（拿不到**直接变红** —— 空集会让
     * 「集合恰好等于 {X}」在所有实现下都假绿）；拿到第一条之后继续 poll，直到出现 {@link #QUIET_WINDOW}
     * 长度的静默为止。返回的是「这一小段窗口里的消息**集合**」，断言可以要求它**恰好等于**期望的 reason
     * 集合 —— 从而抓到「多发了一条**别的** reason」这类漏洞（计划 {@code :1611} 明文要求，
     * 评审的存活变异 {@code N3b} 即此）。
     */
    private static List<ConfigInvalidateMessage> drainBoundedWindow(BlockingQueue<String> received)
            throws InterruptedException {
        List<ConfigInvalidateMessage> messages = new ArrayList<>();
        String first = received.poll(MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(first).as("有界窗口内必须至少收到一条失效广播（空窗口无法判定「恰好一条」）").isNotNull();
        messages.add(decode(first));
        while (true) {
            String payload = received.poll(QUIET_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
            if (payload == null) {
                return messages;
            }
            messages.add(decode(payload));
        }
    }

    /** 解码一条失效载荷；解不开就带着原文变红（而不是静默丢弃 —— 丢弃会让「集合恰好等于」失真）。 */
    private static ConfigInvalidateMessage decode(String payload) {
        ConfigInvalidateMessage message = ConfigInvalidateCodec.decode(payload);
        assertThat(message).as("失效广播的载荷必须能被 ConfigInvalidateCodec 解码（实际=%s）", payload).isNotNull();
        return message;
    }
}
