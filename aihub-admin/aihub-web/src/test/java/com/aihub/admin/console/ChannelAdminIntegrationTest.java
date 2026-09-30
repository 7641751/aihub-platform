package com.aihub.admin.console;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.config.ConfigInvalidateCodec;
import com.aihub.common.config.ConfigInvalidateMessage;
import com.aihub.common.config.ConfigInvalidateTopology;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ConfigVersionMapper;
import com.aihub.dao.mapper.TenantMapper;
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
 * <p><b>回滚用例的两条纪律</b>（Task 7 的评审逐条换来的）：探针是**测试树里**的真实 Spring bean
 * （{@link RollbackProbeConfiguration}），它调**真实的** {@link ChannelAdminService#create} 之后在同一
 * 事务里抛异常；而该用例方法**自己不带 {@code @Transactional}** —— 否则测试自己的事务会成为边界，
 * 回滚就什么也证明不了。渠道数量用**增量**断言（先记基线），不用全表计数：
 * {@code channel} 表与 Testcontainers 容器都是 JVM 级共享的。
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
    private static final String ACTIVE = "ACTIVE";

    private static final AuditService.Actor ACTOR = new AuditService.Actor("USER", "1");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SUBSCRIPTION_TIMEOUT = Duration.ofSeconds(5);
    private static final String SUBSCRIPTION_PROBE = "subscription-probe";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ChannelMapper channelMapper;

    @Autowired
    private TenantMapper tenantMapper;

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

    // ---------------------------------------------------------------- 2) 密钥轮换

    @Test
    void rotatingTheKeyReEncryptsToTheCurrentMasterKeyVersion() throws Exception {
        // 存量行：用**旧的** v1 主密钥加密（= 主密钥升到 v2 之前写入的渠道）。
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

        put("/api/channels/" + id, Map.of("weight", 7));
        assertThat(awaitMessage(received, "channel.update"))
                .as("渠道更新必须广播 channel.update").isNotNull();

        post("/api/channels/" + id + "/rotate-key", null);
        assertThat(awaitMessage(received, "channel.rotate-key"))
                .as("密钥轮换必须广播 channel.rotate-key").isNotNull();

        ResponseEntity<String> deleted = delete("/api/channels/" + id);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(awaitMessage(received, "channel.delete"))
                .as("渠道删除必须广播 channel.delete").isNotNull();
    }

    // ---------------------------------------------------------------- 5) 回滚：不广播、不抬水位

    @Test
    void rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark() throws Exception {
        // 正向对照（先做、且**在订阅之前**）：同一条 create 入口在不失败时确实会落库。
        // 没有它，下面的「渠道数不变」在「create 什么都不写」的实现下也会绿。
        long baseline = channelMapper.selectCount(null);
        createChannel(uniqueChannelName());
        assertThat(channelMapper.selectCount(null))
                .as("正向对照：create 成功时渠道数必须 +1（否则回滚断言毫无判别力）")
                .isEqualTo(baseline + 1);

        BlockingQueue<String> received = subscribeAndAwait();

        Long watermarkBefore = configVersionMapper.current();
        Long channelsBefore = channelMapper.selectCount(null);
        String failedName = uniqueChannelName();
        ChannelAdminService.Write write = new ChannelAdminService.Write(failedName, "openai-compatible",
                "http://127.0.0.1:1", PLAINTEXT, null, null, null, null, null);

        assertThatThrownBy(() -> rollbackProbe.createChannelThenFail(write, ACTOR))
                .isInstanceOf(IllegalStateException.class);

        assertThat(received.poll(2, TimeUnit.SECONDS))
                .as("回滚的写**绝不许**发布失效消息（发布必须在事务提交之后）").isNull();
        assertThat(configVersionMapper.current())
                .as("水位不许被抬高（发布路径里的 raiseTo 也不许跑）").isEqualTo(watermarkBefore);
        assertThat(channelMapper.selectCount(null))
                .as("渠道数必须保持不变（回滚了才没有新增）").isEqualTo(channelsBefore);
        assertThat(channelMapper.selectCount(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getName, failedName)))
                .as("那条渠道本身不许落库").isZero();
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

        JsonNode list = body(get("/api/tenants")).path("data");
        assertThat(findById(list, id)).as("GET /api/tenants 必须包含刚建的租户 id=%s", id).isNotNull();

        ResponseEntity<String> updated = put("/api/tenants/" + id, Map.of("status", "DISABLED"));
        assertThat(updated.getStatusCode()).as("PUT /api/tenants/{id} 必须 200（响应体=%s）", updated.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(body(updated).path("data").path("status").asText()).isEqualTo("DISABLED");
        assertThat(tenantMapper.selectById(id).getStatus()).as("更新必须真的落库").isEqualTo("DISABLED");
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

    /**
     * 回滚用例的探针：**测试树里的真实 Spring bean**（由 {@link TestConfiguration} 注册），
     * 因此 {@code @Transactional} 的代理语义成立。它在同一个事务里调**真实**的
     * {@link ChannelAdminService#create} 之后抛异常 —— 业务写、审计、以及 after-commit 钩子必须一起作废。
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
