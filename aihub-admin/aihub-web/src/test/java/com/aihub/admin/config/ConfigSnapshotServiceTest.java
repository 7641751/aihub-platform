package com.aihub.admin.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.RatePolicy;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.service.channel.ChannelKeyService;
import com.aihub.service.config.ConfigSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照组装（{@code GET /internal/config/snapshot} 的数据面）。**必须真起容器**：这里要钉住的是
 * 「{@code max(updated_at)} 真的随一次写入推进」「{@code status = 'ACTIVE'} 真的把停用行挡在快照外」
 * 「同维度多条 ACTIVE 策略里 {@code id} 最大的那条真的赢」这三件 SQL 层面的事实 —— 换成内存替身
 * 就会变成「测自己写的排序」，一条都证明不了。
 *
 * <p><b>三条控制器裁决在这里落地</b>（它们都压过计划正文的措辞）：
 * <ul>
 *   <li><b>G5</b>：INACTIVE 的 {@code channel} / {@code model_route} / {@code rate_limit_policy} 行
 *       **不得进入快照**。计划正文写的是「全部行都组装（含 DISABLED），由 gateway 侧过滤」——
 *       那条路走不通：{@code RatePolicy} 根本没有状态分量，而 {@code ChannelDescriptor.usable()}
 *       也只在网关侧生效，因此组装层漏掉过滤在网关侧**不可观测**。三个网关 DTO 里只有
 *       {@code RatePolicy} 没有任何状态痕迹，所以过滤点只能是组装层，且每条都由
 *       {@code containsExactly} 钉死（不是「ACTIVE 的在」而是「INACTIVE 的不在」）。</li>
 *   <li><b>决策 17</b>：「同维度取最后一条」= 「取 {@code id} 最大的那条」，这里用真实 MySQL 断言，
 *       不把 {@code RateLimitPolicyMapper} javadoc 里的 InnoDB 扫描顺序假设默默继承下来。</li>
 *   <li><b>决策 D5</b>：{@code version} = {@code max(三张表 updated_at 的最大值, config_version 水位)}，
 *       改一行必须**严格变大**；水位被显式归零（见 {@link #resetVersionWatermark()}）且控制面为空时是
 *       {@code 0}。抬水位的入口是配置**写**路径（{@code ConfigChangePublisher}），本类只用 {@code updated_at}
 *       那一半，因此这些断言仍然钉住「三张表都参与 max」。</li>
 * </ul>
 *
 * <p>用例数据一律是一眼可辨的合成值；密文走真实的 {@link ChannelKeyService#encrypt}，主密钥在
 * 测试里由字节算出来（tracked 文件里没有任何明文密钥 / 密文 / 主密钥字面量）。
 */
class ConfigSnapshotServiceTest extends AbstractIntegrationTest {

    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";

    /** 合成明文（不是任何真实密钥）：只用来证明「快照里拿到的密文能解回它、且密文里没有它」。 */
    private static final String SYNTHETIC_PLAINTEXT = "sk-channel-plaintext-synthetic";

    @Autowired
    private ConfigSnapshotService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 只用于造密文：与 admin 侧生产组件用的是同一个 {@code ChannelKeyService}。 */
    private ChannelKeyService keys;

    @BeforeEach
    void cleanBefore() {
        keys = new ChannelKeyService("v1:" + b64Key(11));
        deleteEverything();
    }

    @BeforeEach
    void resetVersionWatermark() {
        // 水位是**持久**的（这正是 Task 3 的意义），而容器是 JVM 级共享的：
        // 不归零，「空库 version=0」这类断言就变成了对**用例执行顺序**的断言。
        jdbcTemplate.update("UPDATE config_version SET version = 0 WHERE id = 1");
    }

    @AfterEach
    void cleanAfter() {
        deleteEverything();
    }

    @Test
    void emptyDatabaseYieldsAnEmptySnapshotWithTheResetVersion() {
        ConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.channels()).isEmpty();
        assertThat(snapshot.routes()).isEmpty();
        assertThat(snapshot.ratePolicies()).isEmpty();
        assertThat(snapshot.version())
                .as("空控制面 + 被显式归零的水位（决策 D5：水位是持久的，这个 0 来自 @BeforeEach 的归零，"
                        + "不是「版本恒为 0」）")
                .isZero();
        assertThat(snapshot.generatedAtEpochMilli()).isPositive();
    }

    /**
     * 决策 D5：{@code version} 必须是**严格递增的标量**。三张表都要参与 {@code max}，
     * 而且「改一行」也要推进（V1 三张表的 {@code updated_at} 都是 {@code ON UPDATE CURRENT_TIMESTAMP(3)}）。
     *
     * <p>毫秒分辨率下同一毫秒内的两次写入可能拿到相同的 {@code updated_at} —— 那个缺口是**登记在案**的
     * （不修），因此这里每次写入之间睡 10ms，让断言钉住的是「真的推进」而不是「时钟够快」。
     */
    @Test
    void versionStrictlyIncreasesEveryTimeAnyOfTheThreeTablesChanges() throws Exception {
        long empty = service.snapshot().version();
        // 语义是 max(空控制面 0, 被 @BeforeEach 归零的水位 0) = 0 —— 不是「version 恒为 0」。
        assertThat(empty).isZero();

        String cipher = insertChannel("snap-v", ACTIVE);
        assertThat(cipher).startsWith("v1:");
        long afterChannelInsert = service.snapshot().version();
        assertThat(afterChannelInsert).as("channel 表的一次写入必须推进版本").isGreaterThan(empty);

        Thread.sleep(10L);
        insertRoute("snap-v-model", channelIdOf("snap-v"), 50, 1, ACTIVE);
        long afterRouteInsert = service.snapshot().version();
        assertThat(afterRouteInsert).as("model_route 表也要参与 max(updated_at)").isGreaterThan(afterChannelInsert);

        Long tenantId = insertTenant("snap-v-tenant");
        Thread.sleep(10L);
        insertPolicy(tenantId, null, 20, 40, ACTIVE);
        long afterPolicyInsert = service.snapshot().version();
        assertThat(afterPolicyInsert).as("rate_limit_policy 表也要参与 max(updated_at)").isGreaterThan(afterRouteInsert);

        Thread.sleep(10L);
        jdbcTemplate.update("update channel set weight = weight + 1 where name = ?", "snap-v");
        assertThat(service.snapshot().version())
                .as("改一行（不是新增）同样必须让版本严格变大，否则网关会拿着陈旧快照继续服务")
                .isGreaterThan(afterPolicyInsert);
    }

    /**
     * G5 第一条：INACTIVE 的渠道行**不得**进入快照。断言用 {@code containsExactly}：
     * 只断言「ACTIVE 那条在」的实现（把 INACTIVE 也带上）会漏过这个缺口。
     */
    @Test
    void inactiveChannelRowsNeverEnterTheSnapshot() {
        String activeCipher = insertChannel("snap-ch-active", ACTIVE);
        insertChannel("snap-ch-inactive", INACTIVE);

        List<ChannelDescriptor> channels = service.snapshot().channels();

        assertThat(channels).extracting(ChannelDescriptor::name).containsExactly("snap-ch-active");
        assertThat(channels.get(0).apiKeyCipher()).as("密文原样透出（快照里永远只有密文）").isEqualTo(activeCipher);
    }

    /** G5 第二条：INACTIVE 的路由行**不得**进入快照（路由级的 weight/priority 要一并核对）。 */
    @Test
    void inactiveRouteRowsNeverEnterTheSnapshot() {
        Long channelId = insertChannelReturningId("snap-rt-ch", ACTIVE);
        insertRoute("snap-rt-active", channelId, 120, 4, ACTIVE);
        insertRoute("snap-rt-inactive", channelId, 1, 9, INACTIVE);

        List<ModelRouteDescriptor> routes = service.snapshot().routes();

        assertThat(routes).extracting(ModelRouteDescriptor::modelName).containsExactly("snap-rt-active");
        assertThat(routes.get(0).weight()).as("权重取 model_route 的值").isEqualTo(120);
        assertThat(routes.get(0).priority()).isEqualTo(4);
        assertThat(routes.get(0).usable()).isTrue();
    }

    /**
     * G5 第三条：INACTIVE 的限流策略行**不得**进入快照 —— 而且两个维度都要覆盖：
     * 停用一条租户级行不该回落成「没有策略」，停用一条 key 级行同样不该生效。
     */
    @Test
    void inactiveRateLimitPolicyRowsNeverEnterTheSnapshotInEitherDimension() {
        Long tenantId = insertTenant("snap-g5-tenant");
        insertPolicy(tenantId, null, 20, 40, ACTIVE);
        insertPolicy(tenantId, null, 999, 999, INACTIVE);
        insertPolicy(tenantId, 42L, 5, 10, ACTIVE);
        insertPolicy(tenantId, 42L, 777, 777, INACTIVE);

        ConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.ratePolicies()).as("只允许两条 ACTIVE 行进快照").hasSize(2);
        assertThat(snapshot.tenantPolicies(tenantId)).extracting(RatePolicy::qps).containsExactly(20);
        assertThat(snapshot.keyPolicies(tenantId, 42L)).extracting(RatePolicy::qps).containsExactly(5);
        assertThat(snapshot.ratePolicies()).extracting(RatePolicy::qps)
                .as("被停用的那一行（qps=999 / 777）一条都不许出现").doesNotContain(999, 777);
    }

    /** 快照里的字段与 V1 的列一一对应：密文逐字相等、能解回明文、且快照里没有明文。 */
    @Test
    void channelFieldsAreExposedVerbatimAndTheSnapshotCarriesOnlyCipherText() {
        String cipher = insertChannel("snap-verbatim", ACTIVE);

        ChannelDescriptor channel = service.snapshot().channels().get(0);

        assertThat(channel.name()).isEqualTo("snap-verbatim");
        assertThat(channel.baseUrl()).isEqualTo("https://snap-verbatim.example.com");
        assertThat(channel.apiKeyCipher()).isEqualTo(cipher);
        assertThat(channel.keyVersion()).isEqualTo(keys.currentKeyVersion());
        assertThat(channel.timeoutMs()).isEqualTo(60_000);
        assertThat(channel.status()).isEqualTo(ACTIVE);
        assertThat(channel.usable()).isTrue();

        assertThat(channel.apiKeyCipher()).as("快照里绝不能出现明文渠道密钥").doesNotContain(SYNTHETIC_PLAINTEXT);
        assertThat(new AesGcmChannelCipher(ChannelKeyRegistry.parse("v1:" + b64Key(11)))
                .decrypt(channel.apiKeyCipher()))
                .as("透出的密文必须能被网关侧的实现解开（加密实现只有一份）")
                .contains(SYNTHETIC_PLAINTEXT);
    }

    /**
     * 决策 17 + G5：同租户有多条 ACTIVE 租户级策略时，**id 最大的那条**赢。
     *
     * <p>数据刻意让「后插入的那条」的 qps **更小**（20 &lt; 60），并在中间夹一条 INACTIVE 的极大值：
     * 于是「取 qps 最大的」「取第一条」「取 INACTIVE 那条」三种错误实现都会红，
     * 只有「ACTIVE 里 id 最大的」才对。
     */
    @Test
    void theActiveTenantLevelPolicyWithTheMaximumIdWins() {
        Long tenantId = insertTenant("snap-max-id-tenant");
        insertPolicy(tenantId, null, 60, 80, ACTIVE);
        insertPolicy(tenantId, null, 999, 999, INACTIVE);
        insertPolicy(tenantId, null, 20, 40, ACTIVE);

        List<RatePolicy> policies = service.snapshot().tenantPolicies(tenantId);

        assertThat(policies).as("两条 ACTIVE 都要在（组装的顺序就是 id 升序）").hasSize(2);
        assertThat(policies.get(policies.size() - 1).qps())
                .as("「取最后一条」= 「取 id 最大的那条」，不是「取 qps 最大的那条」")
                .isEqualTo(20);
        assertThat(policies.get(policies.size() - 1).burst()).isEqualTo(40);
        assertThat(policies.get(0).qps()).isEqualTo(60);
    }

    /** 决策 17 的观测面：同维度多条 ACTIVE 时打一次 WARN（脏数据要被看见），但结果仍然是 id 最大的那条。 */
    @Test
    void severalActiveTenantLevelPoliciesEmitExactlyOneWarningAndTheMaximumIdStillWins() {
        Long tenantId = insertTenant("snap-warn-tenant");
        insertPolicy(tenantId, null, 60, 80, ACTIVE);
        insertPolicy(tenantId, null, 20, 40, ACTIVE);

        List<ILoggingEvent> events = captureServiceWarnings(() -> {
            List<RatePolicy> policies = service.snapshot().tenantPolicies(tenantId);
            assertThat(policies.get(policies.size() - 1).qps()).isEqualTo(20);
        });

        assertThat(events).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).contains("多条租户级").contains(String.valueOf(tenantId)));
    }

    /**
     * 决策 7（修订）+ 决策 17：两个维度**都**要能从快照里取到，而且跨维度的优先级不是靠 id 顺序
     * 表达的 —— 组装顺序把租户级排在前面（快照契约），但 key 级那条仍然只被它自己的 (租户, key) 取到，
     * 因此网关的「key 级优先」不会被组装顺序吃掉。
     */
    @Test
    void bothPolicyDimensionsAreQueryableAndTheKeyLevelRowIsNotShadowedByTheTenantLevelOne() {
        Long tenantId = insertTenant("snap-two-dim-tenant");
        // key 级的 id 更小：如果实现按「全局最后一条」或「id 最大」来选，就会选错维度。
        insertPolicy(tenantId, 42L, 5, 10, ACTIVE);
        insertPolicy(tenantId, null, 20, 40, ACTIVE);

        ConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.ratePolicies()).extracting(RatePolicy::apiKeyId)
                .as("组装契约：同一租户的租户级行排在 key 级行之前（决策 17 的排序）")
                .containsExactly(null, 42L);
        assertThat(snapshot.tenantPolicies(tenantId)).singleElement().satisfies(policy -> {
            assertThat(policy.qps()).isEqualTo(20);
            assertThat(policy.tenantLevel()).isTrue();
        });
        assertThat(snapshot.keyPolicies(tenantId, 42L)).singleElement().satisfies(policy -> {
            assertThat(policy.qps()).as("更严格的 key 级策略（覆盖租户级）必须能取到").isEqualTo(5);
            assertThat(policy.burst()).isEqualTo(10);
            assertThat(policy.tenantLevel()).isFalse();
        });
        assertThat(snapshot.keyPolicies(tenantId, 43L)).as("别的 key 取不到").isEmpty();
    }

    // --- SQL 助手 -------------------------------------------------------

    private void deleteEverything() {
        jdbcTemplate.update("delete from model_route");
        jdbcTemplate.update("delete from rate_limit_policy");
        jdbcTemplate.update("delete from channel");
    }

    private Long insertTenant(String name) {
        jdbcTemplate.update("insert into tenant (name, status) values (?, ?)", name, ACTIVE);
        return jdbcTemplate.queryForObject("select id from tenant where name = ?", Long.class, name);
    }

    private Long insertChannelReturningId(String name, String status) {
        insertChannel(name, status);
        return channelIdOf(name);
    }

    /**
     * 插入一条渠道行，密文由 {@link ChannelKeyService#encrypt} 真实产出（主密钥由字节算出，
     * tracked 文件里没有明文 / 密文 / 主密钥字面量）。
     */
    private String insertChannel(String name, String status) {
        String cipher = keys.encrypt(SYNTHETIC_PLAINTEXT);
        jdbcTemplate.update("insert into channel (name, provider, base_url, api_key_cipher, key_version, "
                        + "weight, priority, timeout_ms, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, "test", "https://" + name + ".example.com", cipher, keys.currentKeyVersion(),
                100, 0, 60_000, status);
        return cipher;
    }

    private void insertRoute(String modelName, Long channelId, int weight, int priority, String status) {
        jdbcTemplate.update("insert into model_route (model_name, channel_id, weight, priority, status) "
                + "values (?, ?, ?, ?, ?)", modelName, channelId, weight, priority, status);
    }

    private void insertPolicy(Long tenantId, Long apiKeyId, int qps, int burst, String status) {
        jdbcTemplate.update("insert into rate_limit_policy (tenant_id, api_key_id, qps, burst, status) "
                + "values (?, ?, ?, ?, ?)", tenantId, apiKeyId, qps, burst, status);
    }

    private Long channelIdOf(String name) {
        return jdbcTemplate.queryForObject("select id from channel where name = ?", Long.class, name);
    }

    private static List<ILoggingEvent> captureServiceWarnings(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ConfigSnapshotService.class);
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 32 字节主密钥，由种子算出：tracked 文件里不出现任何密钥字面量。 */
    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
