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
import com.aihub.dao.mapper.TenantMapper;
import com.aihub.service.audit.AuditAction;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p><b>列表的租户维度来自令牌的 {@code tenantId}</b>（计划只定死了
 * {@code ApiKeyAdminService.list(long tenantId)}，没写这个 {@code tenantId} 从哪里来）：本类选择
 * **从 claims 取**，于是控制台主体只可能看到自己租户的 key，无法用查询参数跨租户读。代价是创建请求体里
 * 的 {@code tenantId} 与令牌里的 {@code tenantId} 必须一致才看得到 —— 用例因此让两者用同一个夹具租户。
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

    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SUBSCRIPTION_TIMEOUT = Duration.ofSeconds(5);
    private static final String SUBSCRIPTION_PROBE = "subscription-probe";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ApiKeyMapper apiKeyMapper;

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
     * 直接用 {@link ApiKeyMapper} 造一行 key（不走 HTTP）：用例 2/3/4 要观测的是**停用/启用/删除**
     * 这一半，而不是签发路径（那由用例 1 覆盖）。这样 RED 阶段的红点落在被测行为上，而不是落在
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
        long deadline = System.nanoTime() + MESSAGE_TIMEOUT.toNanos();
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
}
