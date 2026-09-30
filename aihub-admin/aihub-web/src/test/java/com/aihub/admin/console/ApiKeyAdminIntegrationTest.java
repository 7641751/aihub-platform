package com.aihub.admin.console;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.apikey.ApiKeyCacheCodec;
import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ConfigInvalidateCodec;
import com.aihub.common.config.ConfigInvalidateMessage;
import com.aihub.common.config.ConfigInvalidateTopology;
import com.aihub.dao.entity.ApiKeyEntity;
import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.ApiKeyMapper;
import com.aihub.dao.mapper.AuditLogMapper;
import com.aihub.dao.mapper.ConfigVersionMapper;
import com.aihub.dao.mapper.TenantMapper;
import com.aihub.service.apikey.ApiKeyAdminService;
import com.aihub.service.apikey.ApiKeyService;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.aihub.service.config.ConfigChangePublisher;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 9 的**线上契约**：API Key 的签发 / 列表 / 停用 / 启用 / 删除，以及**吊销时的显式
 * {@code DEL}` 共享缓存。真容器（MySQL + Redis）、真 HTTP、真控制台令牌、真 Redis Pub/Sub。
 *
 * <p><b>明文只出现一次</b>：只有 {@code POST /api/api-keys} 的响应里带 {@code data.plaintextKey}；
 * 列表、审计、日志一处都不许再出现。本类的断言分两半 —— 「创建响应里有明文」与
 * 「此后任何响应都不含明文，也不含 {@code SHA-256(secret)}」。
 *
 * <p><b>D11 的核心判据是"立即消失"，不是"最终消失"</b>：网关侧本地 Caffeine 的 30 秒窗口没有便宜的
 * 失效通道（残余已登记），但**共享层必须当场被 {@code DEL}** —— 这条只能用「预置一条真的共享条目 →
 * 调停用 → 立刻断言 {@code hasKey} 为 false」来钉，任何「等一会儿再说」的写法都会把 TTL 兜底
 * 误当成显式删除。
 *
 * <p><b>为什么 {@code @TestPropertySource} 只有一个属性</b>：本类只需要一把合规的控制台签名密钥
 * （{@code application.yml} 对 {@code aihub.console.secret} **刻意没有默认值**，见 D16）。属性集与
 * {@code ConsoleLoginIntegrationTest} **逐字相同**、且本类**不加** {@code @Import} /
 * {@code @TestConfiguration}，因此 Spring 有机会**复用**那个已经存在的上下文，而不是再 fork 一个
 * （{@code docs/CONVENTIONS.md} §8 item 5/6 登记过每多一个上下文的真实代价）。
 *
 * <p><b>覆盖缺口的修复轮（Task 9 独立评审 I-1 / I-2 / I-3）</b>：本类在原有 5 条用例之外补上了三块
 * **此前零覆盖**的行为 —— ① {@code evictSharedCache} 在 Redis 抛异常时**吞掉异常、业务写仍提交**
 * （{@link #aFailingSharedCacheEvictionIsSwallowedAndTheWriteStillCommits()}）；② API Key 的写
 * **在事务提交之后才发布失效**（{@link #aRolledBackApiKeyWritePublishesNothingAndDoesNotRaiseTheWatermark()}）；
 * ③ {@code create} 的三条分支（未知租户 404 / 空白 name 400 / 不设过期 = 永不过期）。
 * <b>这三块都是"覆盖缺口"而不是"代码缺陷"：被测行为在 {@code 80fb2e9} 已正确存在，因此新用例在
 * 未变异的代码上**立即为绿**。</b>它们的可证伪性由修复轮的变异体提供（删掉 {@code try/catch}、
 * {@code publishAfterCommit} → {@code bumpAndPublish}、去掉租户检查、去掉 {@code requireText}、
 * 把 {@code expireAt} 改成永远非 null），逐条对应的红见修复轮证据目录 {@code .m4t9fix-logs/}。
 *
 * <p><b>列表的租户维度来自令牌的 {@code tenantId}</b>（计划只定死了
 * {@code ApiKeyAdminService.list(long tenantId)}，没写这个 {@code tenantId} 从哪里来）：本类选择
 * **从 claims 取**，于是控制台主体只可能看到自己租户的 key，无法用查询参数跨租户读。代价是创建请求体里
 * 的 {@code tenantId} 与令牌里的 {@code tenantId} 必须一致才看得到 —— 用例因此让两者用同一个夹具租户
 * （未知租户用例是唯一例外：它故意让两者不同，好让"请求体的 {@code tenantId} 不存在"这件事落在
 * 服务层的租户查找上）。
 *
 * <p><b>夹具的纪律</b>：{@code api_key} / {@code tenant} / {@code audit_log} 与 Testcontainers 容器
 * 都是 JVM 级共享的（{@link AbstractIntegrationTest} 没有全局清理），所以夹具名每条用例唯一、前后各清
 * 一次，审计断言一律按**本次用例的数值主键**（{@code target_id}）查，绝不做全表计数。
 * 夹具前缀里**不含** LIKE 元字符（{@code _} / {@code %} / {@code \}），这条假设由
 * {@link #fixtureNamesContainNoLikeMetacharacters()} 钉住而不是留给读者判断。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=" + ApiKeyAdminIntegrationTest.SECRET
})
class ApiKeyAdminIntegrationTest extends AbstractIntegrationTest {

    /**
     * 合成控制台签名密钥（≥32 字符），与 {@code ConsoleLoginIntegrationTest.SECRET} **逐字相同** ——
     * 值相同才会命中同一个 Spring 上下文缓存键（本类的目的就是复用那个上下文）。刻意**不是
     * {@code private}**：注解值里的常量表达式引用私有常量时 javac 会报访问控制错误。
     */
    static final String SECRET = "console-it-secret-0123456789abcdefghijklmn";

    private static final String TENANT_PREFIX = "m4-key-tn-it-";
    private static final String KEY_PREFIX = "m4-key-it-";

    private static final String TARGET_TYPE = "API_KEY";
    private static final String ACTOR_TYPE = "USER";
    private static final long USER_ID = 1L;

    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";

    private static final String API_KEYS = "/api/api-keys";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与服务层写入这条审计行时的操作者一致（HTTP 路径从 claims 取，这里直接构造同一个值）。 */
    private static final AuditService.Actor CONSOLE_ACTOR = new AuditService.Actor(ACTOR_TYPE, String.valueOf(USER_ID));

    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SUBSCRIPTION_TIMEOUT = Duration.ofSeconds(5);
    private static final String SUBSCRIPTION_PROBE = "subscription-probe";

    /**
     * 回滚用例的**观测窗口**：既用来等「没有消息」（缺席），也用来等正向对照的「有消息」（投递）。
     * 两个方向共用同一个长度，是为了让「窗口太短」在正向对照上变红，而不是让缺席断言静默假绿
     * （照抄 {@code ChannelAdminIntegrationTest} 的 {@code ROLLBACK_WINDOW}）。
     */
    private static final Duration ROLLBACK_WINDOW = Duration.ofSeconds(2);

    /**
     * 黑障 Redis 的命令超时。**与 {@code ApiKeyResolveRedisOutageTest} 不同**：那条用例测的是**时延**，
     * 必须读生产配置里的 {@code spring.data.redis.timeout}；本类只测「Redis 抛异常之后控制面写是否仍然
     * 提交」，超时值**不影响任何断言**，因此固定一个小值让失败尽快发生，不把它变成第二个需要维护的数字。
     */
    private static final Duration BLACKHOLE_COMMAND_TIMEOUT = Duration.ofMillis(200);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ApiKeyMapper apiKeyMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private ConfigVersionMapper configVersionMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private ConsoleTokenService consoleTokenService;

    @Autowired
    private ApiKeyAdminService apiKeyAdminService;

    /** I-1 的替身服务需要它（同一个 bean 传给手工构造的实例，保证签发生成逻辑仍只有一份实现）。 */
    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ConfigChangePublisher configChangePublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private RedisMessageListenerContainer listenerContainer;

    /** 唯一夹具名前后各清一次：容器是 JVM 级共享的，不依赖「表是干净的」。 */
    @BeforeEach
    @AfterEach
    void removeFixtures() {
        for (ApiKeyEntity row : apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKeyEntity>()
                .likeRight(ApiKeyEntity::getName, KEY_PREFIX))) {
            redisTemplate.delete(ApiKeyCacheCodec.CACHE_KEY_PREFIX + row.getKeyHash());
        }
        apiKeyMapper.delete(new LambdaQueryWrapper<ApiKeyEntity>()
                .likeRight(ApiKeyEntity::getName, KEY_PREFIX));
        tenantMapper.delete(new LambdaQueryWrapper<TenantEntity>()
                .likeRight(TenantEntity::getName, TENANT_PREFIX));
    }

    @AfterEach
    void stopListenerContainer() throws Exception {
        if (listenerContainer != null) {
            listenerContainer.destroy();
            listenerContainer = null;
        }
    }

    // ---------------------------------------------------------------- 1) 明文只出现一次

    @Test
    void creationReturnsThePlaintextExactlyOnceAndNeverAgain() throws Exception {
        long tenantId = createTenant();
        String name = uniqueKeyName();

        ResponseEntity<String> created = post(API_KEYS,
                Map.of("tenantId", tenantId, "name", name, "validDays", 30), tenantId);

        assertThat(created.getStatusCode()).as("POST /api/api-keys 必须 200（响应体=%s）", created.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode data = body(created).path("data");
        String plaintext = data.path("plaintextKey").asText();
        assertThat(plaintext).as("data.plaintextKey 必须存在（响应体=%s）", created.getBody())
                .matches("ak_[a-z0-9]{16}\\.[A-Za-z0-9_-]{43}");
        long id = data.path("id").asLong();
        assertThat(id).as("data.id 必须存在（响应体=%s）", created.getBody()).isPositive();

        // 创建响应里除明文之外不得出现它的哈希（那是库里的值，不是给客户端的）。
        String keyHash = ApiKeyHasher.hash(plaintext.substring(plaintext.indexOf('.') + 1));
        assertThat(created.getBody()).as("创建响应不得回显 key_hash").doesNotContain(keyHash);
        // 落库的必须是哈希，不是明文。
        assertThat(apiKeyMapper.selectById(id).getKeyHash()).isEqualTo(keyHash);

        ResponseEntity<String> list = get(API_KEYS, tenantId);
        assertThat(list.getStatusCode()).as("GET /api/api-keys 必须 200（响应体=%s）", list.getBody())
                .isEqualTo(HttpStatus.OK);

        // 非空断言：列表里必须能找到刚创建的那一条，否则下面的「不含明文」是无意义的空断言。
        JsonNode mine = findById(body(list).path("data"), id);
        assertThat(mine).as("列表里必须包含刚创建的 key id=%s（否则「不含明文」是空断言）", id).isNotNull();
        assertThat(mine.has("plaintextKey")).as("列表 DTO 不许有 plaintextKey 字段").isFalse();
        assertThat(mine.has("keyHash")).as("列表 DTO 不许有 keyHash 字段").isFalse();
        assertThat(list.getBody()).as("列表不得含明文或它的哈希")
                .doesNotContain(plaintext).doesNotContain(keyHash);
    }

    // ---------------------------------------------------------------- 2) 停用必须显式 DEL 共享缓存

    @Test
    void disablingAKeyDeletesItsSharedCacheEntryImmediately() throws Exception {
        long tenantId = createTenant();
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        ApiKeyEntity key = insertKey(tenantId, uniqueKeyName());
        String cacheKey = ApiKeyCacheCodec.CACHE_KEY_PREFIX + key.getKeyHash();

        // 预置网关会读到的那一条**真实**共享条目：用真的 ApiKeyCacheCodec.encode，而不是手写的字符串。
        ApiKeyView view = new ApiKeyView(key.getKeyId(), tenantId, tenant.getName(),
                ApiKeyView.STATUS_ACTIVE, null, key.getId());
        redisTemplate.opsForValue().set(cacheKey, ApiKeyCacheCodec.encode(view));
        assertThat(redisTemplate.hasKey(cacheKey)).as("正向对照：预置的共享条目必须真的存在").isTrue();

        ResponseEntity<String> res = post(API_KEYS + "/" + key.getId() + "/disable", null, tenantId);

        assertThat(res.getStatusCode()).as("停用必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(apiKeyMapper.selectById(key.getId()).getStatus()).as("停用必须真的落库").isEqualTo(DISABLED);
        assertThat(redisTemplate.hasKey(cacheKey))
                .as("D11：吊销必须显式 DEL 共享条目（M4 之前只有 TTL，等 TTL 就是「最终」而不是「立即」）")
                .isFalse();
    }

    // ---------------------------------------------------------------- 3) 三个写：发布 + 审计 + 落库

    @Test
    void everyWritePublishesAnInvalidationMessageAndRecordsTheActor() throws Exception {
        BlockingQueue<String> received = subscribeAndAwait();
        long tenantId = createTenant();
        ApiKeyEntity key = insertKey(tenantId, uniqueKeyName());
        long id = key.getId();

        assertThat(post(API_KEYS + "/" + id + "/disable", null, tenantId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(awaitMessage(received, "apikey.disable")).as("停用必须广播 apikey.disable").isNotNull();
        assertThat(apiKeyMapper.selectById(id).getStatus()).as("停用必须真的落库").isEqualTo(DISABLED);
        assertActorIsTheConsoleAdmin(onlyApiKeyAuditRow(AuditAction.API_KEY_DISABLE, id, tenantId));

        assertThat(post(API_KEYS + "/" + id + "/enable", null, tenantId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(awaitMessage(received, "apikey.enable")).as("启用必须广播 apikey.enable").isNotNull();
        assertThat(apiKeyMapper.selectById(id).getStatus()).as("启用必须真的落库").isEqualTo(ACTIVE);
        assertActorIsTheConsoleAdmin(onlyApiKeyAuditRow(AuditAction.API_KEY_ENABLE, id, tenantId));

        assertThat(delete(API_KEYS + "/" + id, tenantId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(awaitMessage(received, "apikey.delete")).as("删除必须广播 apikey.delete").isNotNull();
        assertThat(apiKeyMapper.selectById(id)).as("DELETE 必须真的把那一行删掉").isNull();
        assertActorIsTheConsoleAdmin(onlyApiKeyAuditRow(AuditAction.API_KEY_DELETE, id, tenantId));
    }

    // ---------------------------------------------------------------- 4) 未知 id 404 + 审计记到 actor

    @Test
    void disablingAnUnknownKeyIs404AndTheAuditRowRecordsTheActor() throws Exception {
        long tenantId = createTenant();
        ApiKeyEntity key = insertKey(tenantId, uniqueKeyName());

        assertThat(post(API_KEYS + "/" + key.getId() + "/disable", null, tenantId).getStatusCode())
                .as("停用已存在的 key 必须 200").isEqualTo(HttpStatus.OK);

        AuditLogEntity row = onlyApiKeyAuditRow(AuditAction.API_KEY_DISABLE, key.getId(), tenantId);
        assertActorIsTheConsoleAdmin(row);
        assertThat(row.getDetail()).as("审计 detail 必须存在（否则「不含哈希」是空断言）").isNotNull();
        assertThat(row.getDetail()).as("审计 detail 绝不许出现 key_hash（它是可离线爆破的凭证材料）")
                .doesNotContain(key.getKeyHash());

        ResponseEntity<String> missing = post(API_KEYS + "/9000000000/disable", null, tenantId);
        assertThat(missing.getStatusCode()).as("未知 id 必须 404（响应体=%s）", missing.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(missing).path("code").asText()).isEqualTo("NOT_FOUND");
    }

    // ------------------------------------------------- 5) I-1：DEL 失败必须吞，业务写仍然提交

    /**
     * 独立评审 I-1（裁定 5）：{@code evictSharedCache} 的「Redis 抛异常 ⇒ 吞掉 + WARN + 写仍提交」
     * 分支此前**没有任何用例**（删掉 {@code try/catch} 聚焦仍 5/0 全绿）。本用例把它变成被钉住的行为：
     *
     * <ol>
     *   <li>让 {@code redis.delete(...)} **真的抛**（真 Lettuce 客户端 + 黑障端口，手法照抄
     *       {@code ApiKeyResolveRedisOutageTest}），然后断言 {@code disable} **不抛异常**；</li>
     *   <li>断言**业务写仍然提交**（那一行的状态真的变成 {@code DISABLED}）；</li>
     *   <li>断言**审计行仍然写了**（只按本次用例的 {@code target_id} 查）。</li>
     * </ol>
     *
     * <p>三条缺一不可：只断言「不抛异常」会被「抛了但测试没看见」的实现骗过；只断言「落库」会漏掉
     * 「异常被吞但写没提交」（那正是裁定 5 禁止的形态）。判别力由变异体 {@code F-1}（删掉
     * {@code try/catch}）提供 —— 删掉之后第一条断言精确变红。
     *
     * <p><b>为什么手工构造服务实例</b>：共享上下文里的 {@code StringRedisTemplate} 是**活**的 Redis，
     * 没法让它抛。这里用一个指向黑障端口的真客户端喂给同一个 {@link ApiKeyAdminService} 实现，其余依赖
     * （真 Mapper / 真 {@code AuditService} / 真 {@code ApiKeyService} / 真 {@code ConfigChangePublisher}）
     * 全部来自上下文 —— 于是「业务写提交」与「审计落库」仍然是对**真库**的断言。
     */
    @Test
    void aFailingSharedCacheEvictionIsSwallowedAndTheWriteStillCommits() throws Exception {
        long tenantId = createTenant();
        ApiKeyEntity key = insertKey(tenantId, uniqueKeyName());
        long id = key.getId();

        ApiKeyAdminService serviceWithDeadRedis = adminServiceWithBlackholedRedis();

        assertThatCode(() -> serviceWithDeadRedis.disable(id, CONSOLE_ACTOR))
                .as("Redis 不可用时 delete 抛出的异常必须被吞掉：控制面的写绝不因缓存清理失败而失败（裁定 5）")
                .doesNotThrowAnyException();

        assertThat(apiKeyMapper.selectById(id).getStatus())
                .as("吞异常的语义是「继续提交」，不是「一起回滚」：业务写必须真的落库")
                .isEqualTo(DISABLED);

        AuditLogEntity row = onlyApiKeyAuditRow(AuditAction.API_KEY_DISABLE, id, tenantId);
        assertActorIsTheConsoleAdmin(row);
    }

    // ------------------------------------------------- 6) I-2：回滚的写不发布、不抬水位

    /**
     * 独立评审 I-2：{@code publishAfterCommit(reason)} 的**回滚语义**此前无用例 —— 把它换成**直接**
     * {@code bumpAndPublish(reason)} 聚焦仍 5/0 全绿（现有断言「收到某个 reason 的消息」对两种实现都成立）。
     *
     * <p>本用例照抄 {@code ChannelAdminIntegrationTest#rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark}
     * 的形状：在**真被 Spring 代理的 bean** 上开一个事务、调**真实的** {@code ApiKeyAdminService.disable(...)}
     * 之后抛异常 ⇒ 事务回滚，然后断言：
     * <ul>
     *   <li>{@code audit_log} 没有该写留下的行（按本次用例的 {@code target_id} 定向查，绝不全表计数）；</li>
     *   <li>一条 {@code apikey.*} 消息都没收到（**有界等待**，不是固定 {@code sleep}）；</li>
     *   <li>业务写的状态仍是 {@code ACTIVE}（写真的回滚了）；水位也没被抬高；</li>
     *   <li>**正向对照**：同一套订阅在同一窗口长度内，一次成功写的 {@code apikey.disable} **必须**被观测到
     *       —— 否则「没收到」只是「窗口短到测不出任何东西」的空断言。</li>
     * </ul>
     *
     * <p><b>为什么用 {@code TransactionTemplate} 而不是新增一个探针 bean</b>：{@code ApiKeyAdminService}
     * 已经是这个上下文里**真被代理的 bean**（它的 {@code disable} 带 {@code @Transactional}），
     * {@code TransactionTemplate} 显式开一个事务、代理调用在其中加入（{@code REQUIRED}）—— 与
     * {@code ChannelAdminIntegrationTest} 的探针 bean **语义等价**（真代理 bean + 业务写后抛异常 + 用例自身
     * 非 {@code @Transactional}）。之所以不照抄「{@code @TestConfiguration} + {@code @Import}」：那会给
     * 套件**多 fork 一个完整的 Spring 上下文**（{@code docs/CONVENTIONS.md} §8 item 5/6 的 7 上下文预算），
     * 而本类之所以与 {@code ConsoleLoginIntegrationTest} 共用一个上下文、正是因为它**不加** {@code @Import}。
     * 判别力由变异体 {@code F-2}（{@code publishAfterCommit} → {@code bumpAndPublish}）提供 —— 换成直接发布后，
     * 消息会在**事务里**就发出去，第一条「没有消息」断言精确变红。
     */
    @Test
    void aRolledBackApiKeyWritePublishesNothingAndDoesNotRaiseTheWatermark() throws Exception {
        long tenantId = createTenant();
        ApiKeyEntity key = insertKey(tenantId, uniqueKeyName());
        long id = key.getId();

        BlockingQueue<String> received = subscribeAndAwait();

        Long watermarkBefore = configVersionMapper.current();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // 探针：在真实代理 bean 上跑真实的 disable(...) 之后抛异常 ⇒ 业务写、审计、after-commit 钩子一起作废。
        assertThatThrownBy(() -> transaction.execute(status -> {
            apiKeyAdminService.disable(id, CONSOLE_ACTOR);
            throw new IllegalStateException("模拟业务失败：API Key 的写/审计/after-commit 钩子必须一起作废");
        })).isInstanceOf(IllegalStateException.class);

        // 缺席断言：整个窗口内一条消息都不许收到（发布必须在事务提交之后）。
        assertThat(received.poll(ROLLBACK_WINDOW.toMillis(), TimeUnit.MILLISECONDS))
                .as("回滚的 API Key 写**绝不许**发布失效消息（发布必须在事务提交之后）").isNull();
        assertThat(configVersionMapper.current())
                .as("水位不许被抬高（发布路径里的 raiseTo 也不许跑）").isEqualTo(watermarkBefore);
        assertThat(apiKeyMapper.selectById(id).getStatus())
                .as("回滚后状态必须仍是 ACTIVE（业务写真的回滚了）").isEqualTo(ACTIVE);
        assertThat(apiKeyAuditRowCount(AuditAction.API_KEY_DISABLE, id, tenantId))
                .as("回滚的写绝不许留下审计行（审计若用 REQUIRES_NEW 独立事务，这里会留下一条）").isZero();

        // 正向投递对照：同一窗口长度内，一次**成功**写的 apikey.disable 必须被观测到。
        ApiKeyEntity control = insertKey(tenantId, uniqueKeyName());
        assertThat(post(API_KEYS + "/" + control.getId() + "/disable", null, tenantId).getStatusCode())
                .as("正向对照：成功停用必须 200").isEqualTo(HttpStatus.OK);
        assertThat(awaitMessageWithin(received, "apikey.disable", ROLLBACK_WINDOW))
                .as("正向投递对照：同一窗口内成功写的 apikey.disable 必须被观测到"
                        + "（等不到说明窗口太短，而不是「回滚没有发布」）")
                .isNotNull();
    }

    // ------------------------------------------------- 7) I-3a：未知租户 ⇒ 404 NOT_FOUND

    /**
     * 独立评审 I-3①（裁定 2 明文要求）：{@code create} 按请求体里的 {@code tenantId} 查 {@code TenantMapper}，
     * **查不到必须 404 {@code NOT_FOUND}** —— 此前零覆盖。判别力由变异体 {@code F-3a}（去掉租户存在性检查）
     * 提供：去掉之后 {@code tenant.getId()} 会 NPE ⇒ 500 ≠ 404，本用例精确变红。
     *
     * <p>令牌的 {@code tenantId} 故意用一个**存在**的夹具租户，而请求体里的 {@code tenantId} 指向一个
     * **不存在**的租户 —— 这样红点落在服务层的租户查找上，而不是落在"令牌租户不存在"上。
     */
    @Test
    void creatingAKeyForAnUnknownTenantIs404NotFound() throws Exception {
        long tokenTenantId = createTenant();

        ResponseEntity<String> res = post(API_KEYS,
                Map.of("tenantId", 9_000_000_000L, "name", uniqueKeyName(), "validDays", 30), tokenTenantId);

        assertThat(res.getStatusCode()).as("未知 tenantId 必须 404（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(res).path("code").asText()).as("未知租户必须回 NOT_FOUND 错误码").isEqualTo("NOT_FOUND");
    }

    // ------------------------------------------------- 8) I-3b：空白 name ⇒ 400 INVALID_PARAM

    /**
     * 独立评审 I-3②（{@code requireText} 的契约）：空白 {@code name} 必须 400 {@code INVALID_PARAM} ——
     * 此前零覆盖。判别力由变异体 {@code F-3b}（去掉 {@code requireText}）提供：去掉之后空白 name 会被
     * 原样签发 ⇒ 200 ≠ 400，本用例精确变红。
     *
     * <p>带一个**正向对照**：同一个入口在合法 {@code name} 下必须 200 —— 否则「400」可能只是「这个租户
     * 一律被拒」，而不是「name 空白被拒」。
     */
    @Test
    void creatingAKeyWithABlankNameIs400InvalidParam() throws Exception {
        long tenantId = createTenant();

        ResponseEntity<String> blank = post(API_KEYS,
                Map.of("tenantId", tenantId, "name", "   ", "validDays", 30), tenantId);
        assertThat(blank.getStatusCode()).as("空白 name 必须 400（响应体=%s）", blank.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(blank).path("code").asText()).as("空白 name 必须回 INVALID_PARAM").isEqualTo("INVALID_PARAM");

        // 正向对照：同一租户、同一入口，合法 name 必须被接受。
        ResponseEntity<String> accepted = post(API_KEYS,
                Map.of("tenantId", tenantId, "name", uniqueKeyName(), "validDays", 30), tenantId);
        assertThat(accepted.getStatusCode()).as("合法 name 必须被接受（响应体=%s）", accepted.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------- 9) I-3c：不设过期 ⇒ 永不过期（落库 NULL + 读回 null）

    /**
     * 独立评审 I-3③：{@code validDays} 为 {@code null} 或 ≤ 0 ⇒ **永不过期** ——
     * 这是 {@code ApiKeyMintRunner} 的既有语义，也是 {@code ApiKeyAdminService.expireAt(Integer)} 的契约。
     *
     * <p>断言**同时**钉住两件事，缺一就测不出 {@code Instant} / {@code LocalDateTime} 基准退化
     * （{@code docs/CONVENTIONS.md} §7）：
     * <ol>
     *   <li>{@code api_key.expire_at} **落库为 NULL**（列里没有凭空造出一个"默认"时间）；</li>
     *   <li>列表读回 {@code expireAt} 为 **JSON null**（读侧折算不会把一个 NULL 变成某个瞬时）。</li>
     * </ol>
     * 判别力由变异体 {@code F-3c}（把 {@code expireAt} 改成永远非 null）提供：改完之后第一条断言精确变红。
     *
     * <p>两个输入都要：省略 {@code validDays}（= {@code null}）与 {@code validDays=0}（= ≤ 0），
     * 它们覆盖 {@code if (validDays == null || validDays <= 0)} 的**两个**短路分支。
     */
    @Test
    void aKeyWithoutValidDaysNeverExpires() throws Exception {
        long tenantId = createTenant();

        // ① 省略 validDays（= null）⇒ 永不过期
        long omittedId = createKeyWithoutExpiry(singletonCreateBody(tenantId, uniqueKeyName()));
        assertThat(apiKeyMapper.selectById(omittedId).getExpireAt())
                .as("validDays=null 表示永不过期：api_key.expire_at 必须落库为 NULL（不是某个「默认」时间）")
                .isNull();
        assertListExpireAtIsNull(tenantId, omittedId);

        // ② validDays=0（= ≤ 0）⇒ 同样永不过期
        Map<String, Object> zeroDays = singletonCreateBody(tenantId, uniqueKeyName());
        zeroDays.put("validDays", 0);
        long zeroId = createKeyWithoutExpiry(zeroDays);
        assertThat(apiKeyMapper.selectById(zeroId).getExpireAt())
                .as("validDays<=0 表示永不过期：api_key.expire_at 必须落库为 NULL").isNull();
        assertListExpireAtIsNull(tenantId, zeroId);
    }

    // ---------------------------------------------------------------- 夹具自守

    /**
     * 夹具名前缀里的 {@code _} 对 LIKE 是**单字符通配符**，{@code %} 与 {@code \} 同理。本类的清理与
     * 断言都建立在「前缀只含字面量」这条**假设**上，所以这里把它变成可执行事实：谁把前缀改成
     * {@code m4_key_it_}，这里先红，而不是让 {@code likeRight} 悄悄匹配到别的行（Task 8 的 M-2 教训）。
     */
    @Test
    void fixtureNamesContainNoLikeMetacharacters() {
        for (int i = 0; i < 8; i++) {
            assertThat(uniqueKeyName()).as("夹具 key 名不许含 LIKE 元字符")
                    .doesNotContain("%").doesNotContain("_").doesNotContain("\\");
            assertThat(TENANT_PREFIX).as("夹具租户名前缀不许含 LIKE 元字符")
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

    /**
     * 直接用 {@link ApiKeyMapper} 造一行 key（不走 HTTP）：用例 2/3/4/5/6 要观测的是**停用/启用/删除**
     * 这一半，而不是签发路径（那由用例 1 与 7/8/9 覆盖）。这样 RED 阶段的红点落在被测行为上，而不是落在
     * 「签发接口还没实现」上。
     */
    private ApiKeyEntity insertKey(long tenantId, String name) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId(ApiKeyHasher.newKeyId());
        entity.setTenantId(tenantId);
        entity.setKeyHash(ApiKeyHasher.hash(ApiKeyHasher.newSecret()));
        entity.setName(name);
        entity.setStatus(ACTIVE);
        entity.setExpireAt(null);
        apiKeyMapper.insert(entity);
        assertThat(entity.getId()).as("MyBatis-Plus 必须把自增主键回填进实体").isNotNull();
        return entity;
    }

    /** I-3c 的创建请求体：只带 {@code tenantId} 与 {@code name}（**刻意不带** {@code validDays}）。 */
    private static HashMap<String, Object> singletonCreateBody(long tenantId, String name) {
        HashMap<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId);
        body.put("name", name);
        return body;
    }

    /** 走 HTTP 创建一把不设过期的 key，返回它的数值主键。 */
    private long createKeyWithoutExpiry(Map<String, Object> createBody) throws Exception {
        ResponseEntity<String> created = post(API_KEYS, createBody, ((Number) createBody.get("tenantId")).longValue());
        assertThat(created.getStatusCode()).as("不设过期（validDays 缺失/≤0）必须 200（响应体=%s）", created.getBody())
                .isEqualTo(HttpStatus.OK);
        long id = body(created).path("data").path("id").asLong();
        assertThat(id).as("data.id 必须存在（响应体=%s）", created.getBody()).isPositive();
        return id;
    }

    /** 列表里这条 key 的 {@code expireAt} 必须是 JSON null（读侧折算不许把 NULL 变成某个瞬时）。 */
    private void assertListExpireAtIsNull(long tenantId, long id) throws Exception {
        ResponseEntity<String> list = get(API_KEYS, tenantId);
        assertThat(list.getStatusCode()).as("GET /api/api-keys 必须 200（响应体=%s）", list.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode mine = findById(body(list).path("data"), id);
        assertThat(mine).as("列表里必须包含刚建的 key id=%s（否则「expireAt 为 null」是空断言）", id).isNotNull();
        assertThat(mine.path("expireAt").isNull())
                .as("永不过期的 key 在列表里 expireAt 必须是 JSON null（实际=%s）", mine.path("expireAt")).isTrue();
    }

    private static String uniqueKeyName() {
        return KEY_PREFIX + UUID.randomUUID().toString().substring(0, 8);
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

    private static JsonNode body(ResponseEntity<String> res) throws Exception {
        return MAPPER.readTree(res.getBody());
    }

    // ---------------------------------------------------------------- 审计断言（按本用例的 target_id 查）

    /**
     * 按 {@code target_id}（数值主键，自增、不跨用例复用）取**本用例这条 key** 的审计行，并顺带钉住
     * {@code tenant_id} 的语义：API Key 是租户级资源，写的是**该 key 的租户 id**（不是 SQL NULL、
     * 也不是操作者的租户）—— 裁定 4 明文要求。
     */
    private AuditLogEntity onlyApiKeyAuditRow(String action, long apiKeyId, long tenantId) {
        List<AuditLogEntity> rows = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, action)
                .eq(AuditLogEntity::getTargetType, TARGET_TYPE)
                .eq(AuditLogEntity::getTargetId, String.valueOf(apiKeyId))
                .eq(AuditLogEntity::getTenantId, tenantId));
        assertThat(rows).as("API Key 写必须留下恰好一条 action=%s 且 tenant_id=%s 的审计行", action, tenantId)
                .hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getTargetType()).as("审计行的 target_type 必须是 API_KEY").isEqualTo(TARGET_TYPE);
        assertThat(row.getTargetId()).as("审计行的 target_id 必须是被操作 key 的数值主键")
                .isEqualTo(String.valueOf(apiKeyId));
        assertThat(row.getTenantId()).as("API Key 写的 tenant_id 必须是该 key 的租户 id（不是 NULL、不是 0）")
                .isEqualTo(tenantId);
        return row;
    }

    /** 回滚用例的**缺席**断言用：按 {@code target_id} 数本用例这条 key 的审计行（绝不全表计数）。 */
    private Long apiKeyAuditRowCount(String action, long apiKeyId, long tenantId) {
        return auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, action)
                .eq(AuditLogEntity::getTargetType, TARGET_TYPE)
                .eq(AuditLogEntity::getTargetId, String.valueOf(apiKeyId))
                .eq(AuditLogEntity::getTenantId, tenantId));
    }

    private static void assertActorIsTheConsoleAdmin(AuditLogEntity row) {
        assertThat(row.getActorType()).as("审计行的 actor_type 必须来自请求里的控制台主体").isEqualTo(ACTOR_TYPE);
        assertThat(row.getActor()).as("审计行的 actor 必须是控制台用户的 id").isEqualTo(String.valueOf(USER_ID));
    }

    // ---------------------------------------------------------------- 令牌与 HTTP 助手

    /**
     * 令牌的 {@code tenantId} 就是本用例的夹具租户：列表按 claims 的租户过滤，创建请求体里的
     * {@code tenantId} 也用同一个值，两者一致才看得见自己刚建的那条（见类注释）。
     */
    private String token(long tenantId) {
        long now = Instant.now().getEpochSecond();
        return consoleTokenService.issue(
                new ConsoleClaims(USER_ID, tenantId, ConsoleClaims.ROLE_ADMIN, now, now + 3_600L));
    }

    private ResponseEntity<String> post(String path, Object body, long tenantId) {
        return restTemplate.exchange(path, HttpMethod.POST, requestEntity(body, tenantId), String.class);
    }

    private ResponseEntity<String> get(String path, long tenantId) {
        return restTemplate.exchange(path, HttpMethod.GET, requestEntity(null, tenantId), String.class);
    }

    private ResponseEntity<String> delete(String path, long tenantId) {
        return restTemplate.exchange(path, HttpMethod.DELETE, requestEntity(null, tenantId), String.class);
    }

    private HttpEntity<Object> requestEntity(Object body, long tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(tenantId));
        return body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
    }

    // ---------------------------------------------------------------- 失效广播订阅（照抄 ChannelAdminIntegrationTest）

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

    /**
     * 反复发一条**带唯一后缀的哨兵**，直到 {@code poll} 拿到的第一条就是刚刚那条 —— 说明队列里连更早的
     * 残留都没有，于是随后收到的真消息一定是紧接着投递的那条。哨兵不含分隔符，
     * {@link ConfigInvalidateCodec#decode} 对它返回 {@code null}，不会被误当成有效失效。
     */
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

    /** 有界等待某个 reason 的失效消息；等不到返回 {@code null}（由调用方断言）。 */
    private static ConfigInvalidateMessage awaitMessage(BlockingQueue<String> received, String reason)
            throws InterruptedException {
        return awaitMessageWithin(received, reason, MESSAGE_TIMEOUT);
    }

    /**
     * {@link #awaitMessage} 的可变窗口版本：窗口长度由调用方给。回滚用例的正向投递对照必须用**与缺席
     * 断言相同的窗口长度**，否则「窗口太短」不会被任何断言发现（Task 8 的独立评审登记，N7）。
     */
    private static ConfigInvalidateMessage awaitMessageWithin(BlockingQueue<String> received, String reason,
                                                              Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String payload = received.poll(200, TimeUnit.MILLISECONDS);
            if (payload == null) {
                continue;
            }
            ConfigInvalidateMessage message = ConfigInvalidateCodec.decode(payload);
            if (message != null && reason.equals(message.reason())) {
                return message;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 黑障 Redis（I-1）

    private static ServerSocket blackholeRedis;
    private static final List<Socket> blackholedSockets = new ArrayList<>();
    private static final List<LettuceConnectionFactory> blackholedFactories = new ArrayList<>();

    /**
     * 一个只 {@code accept()}、**永不回包**的 {@link ServerSocket}（黑障）：真 Lettuce 客户端连得上、
     * 但命令永远等不到回应，于是每个命令都耗掉命令超时后抛 {@code RuntimeException}。
     * 这比「指向没人监听的端口」更接近「Redis 挂了」的现场，也与
     * {@code ApiKeyResolveRedisOutageTest} 的手法一致。
     */
    @BeforeAll
    static void startBlackholeRedis() throws IOException {
        blackholeRedis = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!blackholeRedis.isClosed()) {
                try {
                    Socket accepted = blackholeRedis.accept();
                    synchronized (blackholedSockets) {
                        blackholedSockets.add(accepted);
                    }
                } catch (IOException closed) {
                    return;
                }
            }
        }, "apikey-admin-blackhole-redis");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterAll
    static void stopBlackholeRedis() throws IOException {
        for (LettuceConnectionFactory factory : blackholedFactories) {
            factory.destroy();
        }
        if (blackholeRedis != null) {
            blackholeRedis.close();
        }
        synchronized (blackholedSockets) {
            for (Socket socket : blackholedSockets) {
                socket.close();
            }
        }
    }

    /**
     * 用**真** Lettuce 客户端（指向黑障端口）构造一个 {@link ApiKeyAdminService}，其余依赖全部来自共享
     * 上下文（真 Mapper / 真 {@code AuditService} / 真 {@code ApiKeyService} / 真 {@code ConfigChangePublisher}）。
     * 于是 {@code redis.delete(...)} 会真的抛，而「业务写提交」「审计落库」仍是对真库的断言。
     */
    private ApiKeyAdminService adminServiceWithBlackholedRedis() {
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(BLACKHOLE_COMMAND_TIMEOUT)
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", blackholeRedis.getLocalPort()), clientConfiguration);
        factory.afterPropertiesSet();
        blackholedFactories.add(factory);
        StringRedisTemplate unreachable = new StringRedisTemplate(factory);
        unreachable.afterPropertiesSet();
        return new ApiKeyAdminService(apiKeyMapper, tenantMapper, apiKeyService, auditService,
                unreachable, configChangePublisher);
    }
}
