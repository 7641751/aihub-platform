package com.aihub.admin.console;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.config.ConfigInvalidateCodec;
import com.aihub.common.config.ConfigInvalidateMessage;
import com.aihub.common.config.ConfigInvalidateTopology;
import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.AuditLogMapper;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ConfigVersionMapper;
import com.aihub.dao.mapper.TenantMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.aihub.service.channel.ChannelAdminService;
import com.aihub.service.channel.ChannelKeyService;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 8 的**线上契约**：租户 / 渠道 CRUD + 加密写入 + 主密钥轮换 + 提交后广播失效。
 * 真容器（MySQL + Redis）、真 HTTP、真令牌、真 AES-GCM。
 *
 * <p><b>合成密钥</b>：控制台签名密钥与渠道主密钥都由 {@code @TestPropertySource} 注入
 * （{@code application.yml} 对两者都**刻意没有默认值**）。两者都是**合成**值，只活在这个测试上下文里，
 * 绝不是任何真实凭证。
 *
 * <p><b>为什么主密钥是双版本（v1 + v2）</b>：验收判据里有「轮换后 {@code key_version} **前进**」。
 * {@code channel.key_version} 必须与密文的自描述标签一致（{@code ChannelEntity} 的类注释），
 * 而 {@code ChannelKeyService.currentKeyVersion()} 是主密钥表里的最大版本号 —— 单版本时它是个常量，
 * 「前进」在同一个 Spring 上下文里**不可观测**。所以上下文里放 v1 与 v2 两把：
 * {@code currentKeyVersion() == 2}，而轮换用例先落一条**用 v1 加密的存量行**（模拟「主密钥升到 v2
 * 之前写入的渠道」），轮换之后它必须变成 v2 —— 这样「前进」才是可判别的。
 *
 * <p><b>回滚用例的纪律</b>（Task 7 的评审逐条换来的）：探针是**测试树里**的真实 Spring bean
 * （{@link RollbackProbeConfiguration}），它调**真实的** {@link ChannelAdminService#create} 之后在同一
 * 事务里抛异常；而该用例方法**自己不带 {@code @Transactional}** —— 否则测试自己的事务会成为边界，
 * 回滚就什么也证明不了。渠道数量用**增量**断言（先记基线），不用全表计数：
 * {@code channel} 表、{@code audit_log} 表与 Testcontainers 容器都是 JVM 级共享的。
 * 「业务写、审计、after-commit 钩子一起作废」这个不变量由三件事分别钉住：渠道数不变、
 * **按本次失败写独有的渠道名找不到审计行**、以及同一窗口内成功写的失效消息**必须**被观测到
 * （正向投递对照 —— 窗口太短时它先红，而不是让「没有消息」静默假绿）。
 *
 * <p><b>审计断言的作用域</b>：{@code audit_log} 是 JVM 级共享表，所以每条审计断言都必须按
 * **本用例的 {@code target_id}**（渠道 / 租户 id 都是自增主键，不会跨用例复用）或**本用例独有的
 * 渠道名**来查，绝不做全表计数；渠道写的 {@code tenant_id} 必须用 {@code isNull()} 查 ——
 * {@code eq(tenantId, null)} 恒不成立（CONVENTIONS §7），用它查会静默返回 0 行、把断言变成假绿。
 *
 * <p><b>订阅是本类自己搭的</b>{@code AbstractIntegrationTest} 不提供任何
 * {@code RedisMessageListenerContainer} 字段；形状照搬 {@code ConfigChangePublisherTest} ——
 * 真 Redis + 有界等待（订阅是异步建立的，不等就发会让用例偶发变红）。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=" + ChannelAdminIntegrationTest.CONSOLE_SECRET,
        "aihub.channel.master-key=" + ChannelAdminIntegrationTest.MASTER_KEY
})
@Import(ChannelAdminIntegrationTest.RollbackProbeConfiguration.class)
class ChannelAdminIntegrationTest extends AbstractIntegrationTest {

    /**
     * 合成控制台签名密钥（≥32 字符）。刻意**不是 {@code private}**：注解值里的常量表达式引用私有常量时
     * javac 会报访问控制错误（{@code ConsoleLoginIntegrationTest} 已实测过）。
     */
    static final String CONSOLE_SECRET = "channel-it-secret-0123456789abcdefghijklmn";

    /** 合成主密钥 v1（32 字节的 base64）。 */
    static final String V1_MASTER_KEY = "v1:ERITFBUWFxgZGhscHR4fICEiIyQlJicoKSorLC0uLzA=";

    /** 合成主密钥 v2（32 字节的 base64）：轮换的「当前版本」。 */
    static final String V2_MASTER_KEY = "v2:IiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/QEE=";

    static final String MASTER_KEY = V1_MASTER_KEY + "," + V2_MASTER_KEY;

    private static final String CHANNEL_PREFIX = "m4-ch-it-";
    private static final String TENANT_PREFIX = "m4-tn-it-";

    private static final String PLAINTEXT = "sk-console-plaintext-synthetic";

    /** PUT 换新密钥时写入的**新**明文：必须与 {@link #PLAINTEXT} 不同，否则「换成了新明文」不可判别。 */
    private static final String REPLACEMENT_PLAINTEXT = "sk-console-replaced-plaintext-synthetic";

    private static final String ACTIVE = "ACTIVE";

    private static final String CHANNEL_TARGET_TYPE = "CHANNEL";
    private static final String TENANT_TARGET_TYPE = "TENANT";

    private static final AuditService.Actor ACTOR = new AuditService.Actor("USER", "1");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SUBSCRIPTION_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 回滚用例的**观测窗口**：既用来等「没有消息」（缺席），也用来等正向对照的「有消息」（投递）。
     * 两个方向共用同一个长度，是为了让「窗口太短」在正向对照上变红，而不是让缺席断言静默假绿
     * （Task 8 的独立评审登记，N7）。
     */
    private static final Duration ROLLBACK_WINDOW = Duration.ofSeconds(2);

    private static final String SUBSCRIPTION_PROBE = "subscription-probe";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ChannelMapper channelMapper;

    @Autowired
    private TenantMapper tenantMapper;

    /** 真 Mapper（不是替身）：审计半边必须由**真的落库行**来判（Task 8 的独立评审，I1）。 */
    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private ConfigVersionMapper configVersionMapper;

    @Autowired
    private ChannelKeyService channelKeyService;

    @Autowired
    private ConsoleTokenService consoleTokenService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private RollbackProbe rollbackProbe;

    private RedisMessageListenerContainer listenerContainer;

    /** 唯一夹具名前后各清一次：容器是 JVM 级共享的，不依赖「表是干净的」。 */
    @BeforeEach
    @AfterEach
    void removeFixtures() {
        channelMapper.delete(new LambdaQueryWrapper<ChannelEntity>()
                .likeRight(ChannelEntity::getName, CHANNEL_PREFIX));
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

    // ---------------------------------------------------------------- 1) 加密写入

    @Test
    void creatingAChannelEncryptsTheKeyAndNeverReturnsTheCipher() throws Exception {
        String name = uniqueChannelName();

        ResponseEntity<String> res = post("/api/channels", Map.of(
                "name", name,
                "provider", "openai-compatible",
                "baseUrl", "http://127.0.0.1:1",
                "apiKey", PLAINTEXT));

        assertThat(res.getStatusCode()).as("POST /api/channels 必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);
        long id = body(res).path("data").path("id").asLong();
        assertThat(id).as("data.id 必须存在（响应体=%s）", res.getBody()).isPositive();

        ChannelEntity row = channelMapper.selectById(id);
        assertThat(row).as("渠道必须真的落库").isNotNull();
        String cipher = row.getApiKeyCipher();

        // ① 形状：自描述密文 v{n}:{base64}（不是「以 v 开头」这种弱断言）。
        assertThat(cipher).as("落库的必须是自描述密文 v{n}:{...}（实际=%s）", cipher).matches("^v\\d+:.+$");
        assertThat(cipher).as("落库的串里不得出现明文").doesNotContain(PLAINTEXT);
        // ② **往返**：只有把落库密文解回写入的明文，才证明「存的是密文」而不是「原样存明文」。
        //    （只断言 shape 会被「存 'v' + 明文」这类实现骗过去。）
        assertThat(channelKeyService.decrypt(cipher))
                .as("落库密文必须能用同一把主密钥解回写入的明文（实际密文=%s）", cipher)
                .contains(PLAINTEXT);
        assertThat(row.getKeyVersion()).as("key_version 必须与当前主密钥版本一致")
                .isEqualTo(channelKeyService.currentKeyVersion());

        // 响应体永不含明文，也永不含落库密文。
        assertThat(res.getBody()).as("创建响应不得回显明文或密文").doesNotContain(PLAINTEXT).doesNotContain(cipher);

        // 列表：既要**确实包含**这条渠道（否则「不含密文」是无意义的空断言），又不能带任何密钥材料。
        ResponseEntity<String> list = get("/api/channels");
        assertThat(list.getStatusCode()).as("GET /api/channels 必须 200（响应体=%s）", list.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode mine = findById(body(list).path("data"), id);
        assertThat(mine).as("列表里必须能找到刚创建的渠道 id=%s（否则下面的断言是空的）", id).isNotNull();
        assertThat(mine.path("keyVersion").asInt()).isEqualTo(channelKeyService.currentKeyVersion());
        assertThat(mine.path("hasKey").asBoolean()).as("ChannelView 只暴露 hasKey 这一位").isTrue();
        assertThat(mine.has("apiKeyCipher")).as("列表 DTO 不许有 apiKeyCipher 字段").isFalse();
        assertThat(mine.has("apiKey")).as("列表 DTO 不许有 apiKey 字段").isFalse();
        assertThat(mine.toString()).as("列表元素不得含明文或密文").doesNotContain(PLAINTEXT).doesNotContain(cipher);
    }

    // ---------------------------------------------------------------- 1b) 取单条

    @Test
    void gettingAChannelByIdReturnsTheViewWithoutKeyMaterialAnd404sForUnknownIds() throws Exception {
        long id = createChannel(uniqueChannelName());
        ChannelEntity row = channelMapper.selectById(id);

        ResponseEntity<String> res = get("/api/channels/" + id);
        assertThat(res.getStatusCode()).as("GET /api/channels/{id} 必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode data = body(res).path("data");
        assertThat(data.path("id").asLong()).as("取单条必须返回被请求的那一条").isEqualTo(id);
        assertThat(data.path("hasKey").asBoolean()).as("ChannelView 只暴露 hasKey 这一位").isTrue();
        assertThat(data.path("keyVersion").asInt()).isEqualTo(channelKeyService.currentKeyVersion());
        assertThat(data.has("apiKeyCipher")).as("GET 单条 DTO 不许有 apiKeyCipher 字段").isFalse();
        assertThat(data.has("apiKey")).as("GET 单条 DTO 不许有 apiKey 字段").isFalse();
        assertThat(res.getBody()).as("GET 单条不得回显明文或落库密文")
                .doesNotContain(PLAINTEXT).doesNotContain(row.getApiKeyCipher());

        // 未知 id 不许返回一个空壳 200：必须 404 NOT_FOUND（与 BizException 的全局处理一致）。
        ResponseEntity<String> missing = get("/api/channels/9000000000");
        assertThat(missing.getStatusCode()).as("未知 id 必须 404（响应体=%s）", missing.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(missing).path("code").asText()).isEqualTo("NOT_FOUND");
    }

    // ---------------------------------------------------------------- 2) 密钥轮换

    @Test
    void rotatingTheKeyReEncryptsToTheCurrentMasterKeyVersion() throws Exception {
        // 存量行：用**旧的** v1 主密钥加密（= 主密钥升到 v2 之前写入的渠道）。
        // 夹具由 {@link #insertLegacyV1Channel()} 单点定义（修复轮复评 M-4：此前本用例内联一份、
        // 助手又有一份，两份会各自漂移）。
        ChannelEntity legacy = insertLegacyV1Channel();

        String legacyCipher = legacy.getApiKeyCipher();
        assertThat(legacyCipher).as("存量行必须是 v1 密文，否则本用例没有可前进的起点").startsWith("v1:");
        assertThat(channelKeyService.currentKeyVersion())
                .as("上下文主密钥必须已经升到 v2，否则「key_version 前进」不可观测").isEqualTo(2);

        ResponseEntity<String> res = post("/api/channels/" + legacy.getId() + "/rotate-key", null);

        assertThat(res.getStatusCode()).as("POST /api/channels/{id}/rotate-key 必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);
        ChannelEntity rotated = channelMapper.selectById(legacy.getId());
        assertThat(rotated.getKeyVersion()).as("轮换后 key_version 必须前进到当前主密钥版本")
                .isEqualTo(2).isGreaterThan(1);
        assertThat(rotated.getApiKeyCipher())
                .as("必须用当前主密钥**重加密**：密文前缀跟着变，且不是原样搬运")
                .startsWith("v2:")
                .isNotEqualTo(legacyCipher);
        assertThat(channelKeyService.decrypt(rotated.getApiKeyCipher()))
                .as("轮换只换加密版本，不换明文").contains(PLAINTEXT);
        assertThat(res.getBody()).as("轮换响应不得回显任何密文/明文")
                .doesNotContain(rotated.getApiKeyCipher()).doesNotContain(legacyCipher).doesNotContain(PLAINTEXT);
    }

    // ---------------------------------------------------------------- 2b) PUT 换新密钥

    @Test
    void updatingWithANewApiKeyReEncryptsItAndAdvancesTheKeyVersion() throws Exception {
        ChannelEntity legacy = insertLegacyV1Channel();
        String legacyCipher = legacy.getApiKeyCipher();
        assertThat(legacyCipher).as("起点必须是 v1 密文，否则「前进」不可观测").startsWith("v1:");
        assertThat(channelKeyService.currentKeyVersion())
                .as("上下文主密钥必须已经升到 v2，否则「key_version 前进」不可观测").isEqualTo(2);

        ResponseEntity<String> res = put("/api/channels/" + legacy.getId(),
                Map.of("apiKey", REPLACEMENT_PLAINTEXT));

        assertThat(res.getStatusCode()).as("PUT 换新 apiKey 必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);
        ChannelEntity updated = channelMapper.selectById(legacy.getId());
        assertThat(updated).as("更新之后那一行必须还在").isNotNull();
        assertThat(updated.getKeyVersion()).as("换密钥后 key_version 必须前进到当前主密钥版本")
                .isEqualTo(channelKeyService.currentKeyVersion()).isEqualTo(2).isGreaterThan(1);
        String newCipher = updated.getApiKeyCipher();
        assertThat(newCipher).as("必须用当前主密钥重新加密（v2 前缀），不是原样搬运")
                .startsWith("v2:").isNotEqualTo(legacyCipher);
        String decrypted = channelKeyService.decrypt(newCipher)
                .orElseThrow(() -> new AssertionError("新密文必须能用主密钥解开（实际=" + newCipher + "）"));
        assertThat(decrypted).as("新密文必须解回**新的**明文（旧明文已经不在里面）")
                .contains(REPLACEMENT_PLAINTEXT).doesNotContain(PLAINTEXT);
        assertThat(res.getBody()).as("换密钥的响应不得回显任何明文或密文")
                .doesNotContain(REPLACEMENT_PLAINTEXT).doesNotContain(PLAINTEXT)
                .doesNotContain(newCipher).doesNotContain(legacyCipher);
    }

    // ---------------------------------------------------------------- 3) models_json 校验

    @Test
    void aMalformedModelsJsonIsRejectedWithInvalidParam() throws Exception {
        String name = uniqueChannelName();

        ResponseEntity<String> res = post("/api/channels", Map.of(
                "name", name,
                "provider", "openai-compatible",
                "baseUrl", "http://127.0.0.1:1",
                "apiKey", PLAINTEXT,
                "modelsJson", "not-json"));

        assertThat(res.getStatusCode()).as("非法 models_json 必须 400（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(res).path("code").asText()).isEqualTo("INVALID_PARAM");
        assertThat(channelMapper.selectCount(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getName, name)))
                .as("被拒的请求绝不许留下半条渠道行").isZero();

        // 正向对照：同一个入口在**合法** JSON 下必须 200 —— 否则「400」可能只是「一律拒绝」。
        ResponseEntity<String> accepted = post("/api/channels", Map.of(
                "name", uniqueChannelName(),
                "provider", "openai-compatible",
                "baseUrl", "http://127.0.0.1:1",
                "apiKey", PLAINTEXT,
                "modelsJson", "[\"m4-model-a\"]"));
        assertThat(accepted.getStatusCode()).as("合法 models_json 必须被接受（响应体=%s）", accepted.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- 4) 提交后广播（正向）

    @Test
    void everyWritePublishesAnInvalidationMessage() throws Exception {
        BlockingQueue<String> received = subscribeAndAwait();

        long id = createChannel(uniqueChannelName());
        ConfigInvalidateMessage created = awaitMessage(received, "channel.create");
        assertThat(created).as("渠道创建必须广播 channel.create").isNotNull();
        assertThat(created.version()).as("失效消息必须带一个真实的水位版本号").isPositive();

        // 审计半边（I1）：每次写都必须在**同一个事务**里留下一条 CHANNEL 目标、tenant_id 为 SQL NULL 的
        // 审计行（tenant_id 用 isNull 查，见类注释）。按 target_id 查 = 只看这条渠道自己的行。
        String cipherAfterCreate = channelMapper.selectById(id).getApiKeyCipher();
        AuditLogEntity createdAudit = onlyChannelAuditRow(AuditAction.CHANNEL_CREATE, id);
        assertActorIsTheConsoleAdmin(createdAudit);
        assertDetailCarriesNoKeyMaterial(createdAudit, PLAINTEXT, cipherAfterCreate);

        put("/api/channels/" + id, Map.of("weight", 7));
        assertThat(awaitMessage(received, "channel.update"))
                .as("渠道更新必须广播 channel.update").isNotNull();

        // 业务写真的落库了吗？（I3：此前「只广播不落库」也能全绿。）顺带看 CHANNEL_UPDATE 的审计行。
        ChannelEntity updatedRow = channelMapper.selectById(id);
        assertThat(updatedRow).as("更新之后那一行必须还在").isNotNull();
        assertThat(updatedRow.getWeight()).as("PUT weight 必须真的落库（只广播不落库是缺陷）").isEqualTo(7);
        AuditLogEntity updatedAudit = onlyChannelAuditRow(AuditAction.CHANNEL_UPDATE, id);
        assertActorIsTheConsoleAdmin(updatedAudit);
        assertDetailCarriesNoKeyMaterial(updatedAudit, PLAINTEXT, updatedRow.getApiKeyCipher());

        post("/api/channels/" + id + "/rotate-key", null);
        assertThat(awaitMessage(received, "channel.rotate-key"))
                .as("密钥轮换必须广播 channel.rotate-key").isNotNull();

        ChannelEntity rotatedRow = channelMapper.selectById(id);
        assertThat(rotatedRow).as("轮换之后那一行必须还在").isNotNull();
        AuditLogEntity rotatedAudit = onlyChannelAuditRow(AuditAction.CHANNEL_ROTATE_KEY, id);
        assertActorIsTheConsoleAdmin(rotatedAudit);
        assertDetailCarriesNoKeyMaterial(rotatedAudit, PLAINTEXT, cipherAfterCreate, rotatedRow.getApiKeyCipher());

        ResponseEntity<String> deleted = delete("/api/channels/" + id);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(awaitMessage(received, "channel.delete"))
                .as("渠道删除必须广播 channel.delete").isNotNull();

        // 业务写真的删掉了吗？（I3）审计行按同一个 id 查 —— 删除只删 channel 行，审计是另一张表。
        assertThat(channelMapper.selectById(id)).as("DELETE 必须真的把那一行删掉（只广播不落库是缺陷）").isNull();
        AuditLogEntity deletedAudit = onlyChannelAuditRow(AuditAction.CHANNEL_DELETE, id);
        assertActorIsTheConsoleAdmin(deletedAudit);
        assertDetailCarriesNoKeyMaterial(deletedAudit, PLAINTEXT, cipherAfterCreate, rotatedRow.getApiKeyCipher());
    }

    // ---------------------------------------------------------------- 5) 回滚：不广播、不抬水位

    @Test
    void rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark() throws Exception {
        // 正向对照（先做、且**在订阅之前**）：同一条 create 入口在不失败时确实会落库。
        // 没有它，下面的「渠道数不变」在「create 什么都不写」的实现下也会绿。
        long baseline = channelMapper.selectCount(null);
        String committedName = uniqueChannelName();
        createChannel(committedName);
        assertThat(channelMapper.selectCount(null))
                .as("正向对照：create 成功时渠道数必须 +1（否则回滚断言毫无判别力）")
                .isEqualTo(baseline + 1);
        // 审计查询的正向对照：同一个「按 detail 里的渠道名找 CHANNEL_CREATE 审计行」必须能找到刚提交的
        // 那一条 —— 否则下面「回滚的写没有审计行」在一个永远返回 0 行的查询下也会绿。
        assertThat(auditRowsForChannelName(committedName))
                .as("正向对照：提交成功的写必须留下审计行（否则「没有审计行」是空断言）")
                .isEqualTo(1L);

        BlockingQueue<String> received = subscribeAndAwait();

        Long watermarkBefore = configVersionMapper.current();
        Long channelsBefore = channelMapper.selectCount(null);
        String failedName = uniqueChannelName();
        ChannelAdminService.Write write = new ChannelAdminService.Write(failedName, "openai-compatible",
                "http://127.0.0.1:1", PLAINTEXT, null, null, null, null, null);

        assertThatThrownBy(() -> rollbackProbe.createChannelThenFail(write, ACTOR))
                .isInstanceOf(IllegalStateException.class);

        // 用 toMillis/MILLISECONDS 而不是 toSeconds/SECONDS（修复轮复评 M-1）：toSeconds 会截断，
        // 将来谁把 ROLLBACK_WINDOW 调成亚秒值，这里会变成 **0 长度** 的 poll —— 「没有消息」永远绿，
        // 而下面 :465 的正向投递对照仍按真实窗口等待，N7 那个「缺席窗口不可判别」的毛病就会悄悄回来。
        assertThat(received.poll(ROLLBACK_WINDOW.toMillis(), TimeUnit.MILLISECONDS))
                .as("回滚的写**绝不许**发布失效消息（发布必须在事务提交之后）").isNull();
        assertThat(configVersionMapper.current())
                .as("水位不许被抬高（发布路径里的 raiseTo 也不许跑）").isEqualTo(watermarkBefore);
        assertThat(channelMapper.selectCount(null))
                .as("渠道数必须保持不变（回滚了才没有新增）").isEqualTo(channelsBefore);
        assertThat(channelMapper.selectCount(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getName, failedName)))
                .as("那条渠道本身不许落库").isZero();
        // 审计必须与业务写一起作废（探针 javadoc 声称的「业务写、审计、after-commit 钩子必须一起作废」）：
        // 按**这次失败写独有的渠道名**查，绝不做全表计数（audit_log 是 JVM 级共享的）。
        assertThat(auditRowsForChannelName(failedName))
                .as("回滚的写绝不许留下审计行（审计若用 REQUIRES_NEW 独立事务，这里会留下一条）")
                .isZero();

        // 正向投递对照（N7）：在**同一个窗口长度**内，一次成功写的 channel.create 必须被观测到。
        // 没有它，上面的「一条消息都没有」在窗口短到测不出任何东西时也会绿。
        long controlId = createChannel(uniqueChannelName());
        assertThat(controlId).isPositive();
        assertThat(awaitMessageWithin(received, "channel.create", ROLLBACK_WINDOW))
                .as("正向投递对照：同一窗口内成功写的 channel.create 必须被观测到"
                        + "（等不到说明窗口太短，而不是「回滚没有发布」）")
                .isNotNull();
    }

    // ---------------------------------------------------------------- 6) 租户 CRUD

    @Test
    void tenantsAreCreatedListedAndUpdated() throws Exception {
        String name = uniqueTenantName();

        ResponseEntity<String> created = post("/api/tenants", Map.of("name", name, "status", ACTIVE));
        assertThat(created.getStatusCode()).as("POST /api/tenants 必须 200（响应体=%s）", created.getBody())
                .isEqualTo(HttpStatus.OK);
        long id = body(created).path("data").path("id").asLong();
        assertThat(id).isPositive();
        assertThat(body(created).path("data").path("status").asText()).isEqualTo(ACTIVE);

        // 审计半边（I1）：租户写的 tenant_id 必须是**这个租户的 id**（渠道写才是 SQL NULL）。
        AuditLogEntity createdAudit = onlyTenantAuditRow(AuditAction.TENANT_CREATE, id);
        assertActorIsTheConsoleAdmin(createdAudit);
        assertThat(createdAudit.getDetail()).as("租户审计 detail 必须存在（否则「不含密钥」是空断言）")
                .isNotNull();

        JsonNode list = body(get("/api/tenants")).path("data");
        assertThat(findById(list, id)).as("GET /api/tenants 必须包含刚建的租户 id=%s", id).isNotNull();

        ResponseEntity<String> updated = put("/api/tenants/" + id, Map.of("status", "DISABLED"));
        assertThat(updated.getStatusCode()).as("PUT /api/tenants/{id} 必须 200（响应体=%s）", updated.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(body(updated).path("data").path("status").asText()).isEqualTo("DISABLED");
        assertThat(tenantMapper.selectById(id).getStatus()).as("更新必须真的落库").isEqualTo("DISABLED");

        AuditLogEntity updatedAudit = onlyTenantAuditRow(AuditAction.TENANT_UPDATE, id);
        assertActorIsTheConsoleAdmin(updatedAudit);
        assertThat(updatedAudit.getDetail())
                .as("租户更新的审计 detail 必须存在并记下这次变更后的状态")
                .isNotNull().contains("DISABLED");
    }

    // ---------------------------------------------------------------- 7) 夹具自守

    /**
     * {@link #auditRowsForChannelName} 用 MyBatis-Plus 的 {@code like} 匹配 {@code detail}，而 LIKE 的
     * {@code %} / {@code _} / {@code \} 是**元字符**。夹具名今天只含 {@code m4-ch-it-} + 8 位 UUID 十六进制，
     * 所以是安全的 —— 但那是**假设**，不是断言。本用例把这条假设变成可执行事实：谁把前缀改成
     * {@code m4_ch_it_}（下划线变成单字符通配符），这里先红，而不是让审计查询悄悄匹配到别的行
     * （Task 8 修复轮复评 M-2）。
     */
    @Test
    void fixtureNamesContainNoLikeMetacharacters() {
        for (int i = 0; i < 8; i++) {
            assertThat(uniqueChannelName()).as("渠道夹具名不许含 LIKE 元字符，否则审计查询会误匹配")
                    .doesNotContain("%").doesNotContain("_").doesNotContain("\\");
            assertThat(uniqueTenantName()).as("租户夹具名不许含 LIKE 元字符")
                    .doesNotContain("%").doesNotContain("_").doesNotContain("\\");
        }
    }

    // ---------------------------------------------------------------- 夹具与助手

    private long createChannel(String name) throws Exception {
        ResponseEntity<String> res = post("/api/channels", Map.of(
                "name", name,
                "provider", "openai-compatible",
                "baseUrl", "http://127.0.0.1:1",
                "apiKey", PLAINTEXT));
        assertThat(res.getStatusCode()).as("创建渠道必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        return body(res).path("data").path("id").asLong();
    }

    private static String uniqueChannelName() {
        return CHANNEL_PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String uniqueTenantName() {
        return TENANT_PREFIX + UUID.randomUUID().toString().substring(0, 8);
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

    // ---------------------------------------------------------------- 审计断言与作用域

    /**
     * 按 {@code target_id} 取**本用例这条渠道**的审计行，并顺带钉住 {@code tenant_id} 的 SQL NULL 语义
     * （渠道不是租户级资源，见 {@code ChannelAdminService} 的类注释）：查询必须用 {@code isNull()} ——
     * {@code eq(tenantId, null)} 恒不成立（CONVENTIONS §7），用它查会静默返回 0 行、把断言变成假绿。
     */
    private AuditLogEntity onlyChannelAuditRow(String action, long channelId) {
        List<AuditLogEntity> rows = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, action)
                .eq(AuditLogEntity::getTargetType, CHANNEL_TARGET_TYPE)
                .eq(AuditLogEntity::getTargetId, String.valueOf(channelId))
                .isNull(AuditLogEntity::getTenantId));
        assertThat(rows).as("渠道写必须留下恰好一条 action=%s 且 tenant_id 为 SQL NULL 的审计行", action)
                .hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getAction()).as("审计行的 action").isEqualTo(action);
        assertThat(row.getTargetType()).as("审计行的 target_type 必须是 CHANNEL").isEqualTo(CHANNEL_TARGET_TYPE);
        assertThat(row.getTargetId()).as("审计行的 target_id 必须是被操作渠道的 id")
                .isEqualTo(String.valueOf(channelId));
        assertThat(row.getTenantId()).as("渠道写不是租户级资源：tenant_id 必须是 SQL NULL（不是 0，也不是操作者的租户）")
                .isNull();
        return row;
    }

    /** 租户写的审计行：{@code tenant_id} 必须**等于目标租户 id**（不是 NULL）。 */
    private AuditLogEntity onlyTenantAuditRow(String action, long tenantId) {
        List<AuditLogEntity> rows = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, action)
                .eq(AuditLogEntity::getTargetType, TENANT_TARGET_TYPE)
                .eq(AuditLogEntity::getTargetId, String.valueOf(tenantId))
                .eq(AuditLogEntity::getTenantId, tenantId));
        assertThat(rows).as("租户写必须留下恰好一条 action=%s 且 tenant_id=%s 的审计行", action, tenantId)
                .hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getAction()).as("审计行的 action").isEqualTo(action);
        assertThat(row.getTargetType()).as("审计行的 target_type 必须是 TENANT").isEqualTo(TENANT_TARGET_TYPE);
        assertThat(row.getTargetId()).as("审计行的 target_id 必须是被操作租户的 id")
                .isEqualTo(String.valueOf(tenantId));
        assertThat(row.getTenantId()).as("租户写的 tenant_id 必须等于被操作租户的 id（不是 NULL）")
                .isEqualTo(tenantId);
        return row;
    }

    private static void assertActorIsTheConsoleAdmin(AuditLogEntity row) {
        assertThat(row.getActorType()).as("审计行的 actor_type 必须来自请求里的控制台主体")
                .isEqualTo(ACTOR.type());
        assertThat(row.getActor()).as("审计行的 actor 必须是控制台用户的 id").isEqualTo(ACTOR.id());
    }

    /** detail 必须存在（否则「不含密钥」是空断言），且不得出现任何明文或密文。 */
    private static void assertDetailCarriesNoKeyMaterial(AuditLogEntity row, String... forbidden) {
        String detail = row.getDetail();
        assertThat(detail).as("审计 detail 必须存在（否则「不含密钥」是空断言）").isNotNull();
        for (String secret : forbidden) {
            assertThat(detail).as("审计 detail 绝不许含明文密钥或密文（detail=%s）", detail)
                    .doesNotContain(secret);
        }
    }

    /**
     * 按 detail 里的**渠道名**数 CHANNEL_CREATE 审计行。名字是每个用例独有的夹具值，因此这条查询只可能
     * 命中「本用例的那一行」，不是全表计数（{@code audit_log} 与容器都是 JVM 级共享的）。回滚用例用它
     * 同时做正向对照（成功写必须找得到）与反向断言（失败的写必须找不到）。
     */
    private Long auditRowsForChannelName(String name) {
        return auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, AuditAction.CHANNEL_CREATE)
                .eq(AuditLogEntity::getTargetType, CHANNEL_TARGET_TYPE)
                .like(AuditLogEntity::getDetail, name));
    }

    /**
     * 造一条「主密钥升到 v2 之前写入的」存量渠道：用 v1 主密钥加密、{@code key_version = 1}。
     * 轮换与「PUT 换新 apiKey」两条用例都需要这个前提（单版本主密钥时「前进」不可观测）。
     */
    private ChannelEntity insertLegacyV1Channel() {
        ChannelEntity legacy = new ChannelEntity();
        legacy.setName(uniqueChannelName());
        legacy.setProvider("openai-compatible");
        legacy.setBaseUrl("http://127.0.0.1:1");
        legacy.setApiKeyCipher(new ChannelKeyService(V1_MASTER_KEY).encrypt(PLAINTEXT));
        legacy.setKeyVersion(1);
        legacy.setWeight(100);
        legacy.setPriority(0);
        legacy.setTimeoutMs(60_000);
        legacy.setStatus(ACTIVE);
        channelMapper.insert(legacy);
        return legacy;
    }

    private String token() {
        long now = Instant.now().getEpochSecond();
        return consoleTokenService.issue(new ConsoleClaims(1L, 1L, ConsoleClaims.ROLE_ADMIN, now, now + 3_600L));
    }

    private ResponseEntity<String> post(String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, requestEntity(body), String.class);
    }

    private ResponseEntity<String> put(String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.PUT, requestEntity(body), String.class);
    }

    private ResponseEntity<String> delete(String path) {
        return restTemplate.exchange(path, HttpMethod.DELETE, requestEntity(null), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, requestEntity(null), String.class);
    }

    private HttpEntity<Object> requestEntity(Object body) {
        return body == null
                ? new HttpEntity<>(bearerHeaders())
                : new HttpEntity<>(body, bearerHeaders());
    }

    private HttpHeaders bearerHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token());
        return headers;
    }

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
     * 残留都没有（发布连接与订阅连接各一条，Redis 保序），于是随后收到的真消息一定是紧接着投递的那条。
     * 哨兵不含分隔符，{@link ConfigInvalidateCodec#decode} 对它返回 {@code null}，不会被误当成有效失效。
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

    /**
     * 回滚用例的探针：**测试树里的真实 Spring bean**（由 {@link TestConfiguration} 注册），
     * 因此 {@code @Transactional} 的代理语义成立。它在同一个事务里调**真实**的
     * {@link ChannelAdminService#create} 之后抛异常 —— 业务写、审计、以及 after-commit 钩子必须一起作废。
     * 这三件事都被用例本身钉住，而不是只写在注释里：渠道行不落库（按失败写独有的名字查计数为 0）、
     * 渠道数不变、同一名字下**找不到 CHANNEL_CREATE 审计行**、窗口内没有任何失效消息
     * （而成功写的消息在同一窗口长度内**必须**被观测到，作为投递对照）。
     */
    @TestConfiguration
    static class RollbackProbeConfiguration {

        @Bean
        RollbackProbe rollbackProbe(ChannelAdminService channelAdminService) {
            return new RollbackProbe(channelAdminService);
        }
    }

    /** 探针本体。刻意只有一条路径：写渠道 → 抛异常。 */
    static class RollbackProbe {

        private final ChannelAdminService channelAdminService;

        RollbackProbe(ChannelAdminService channelAdminService) {
            this.channelAdminService = channelAdminService;
        }

        @Transactional
        public void createChannelThenFail(ChannelAdminService.Write write, AuditService.Actor actor) {
            channelAdminService.create(write, actor);
            throw new IllegalStateException("模拟业务失败：渠道/审计必须回滚，失效消息也不许发出去");
        }
    }
}
