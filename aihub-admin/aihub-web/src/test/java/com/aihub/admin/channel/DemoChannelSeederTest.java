package com.aihub.admin.channel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.ModelRouteEntity;
import com.aihub.dao.entity.RateLimitPolicyEntity;
import com.aihub.dao.mapper.ApiKeyMapper;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.aihub.dao.mapper.TenantMapper;
import com.aihub.service.channel.ChannelKeyService;
import com.aihub.service.channel.DemoChannelSeeder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 开发演示数据 seeder。**真容器**：它是一条写路径，要钉住的是「写了哪些行、密文是谁产出的、
 * 再跑一次会不会写第二遍」这些落库事实。
 *
 * <p>三条控制器裁决在这里落地：
 * <ol>
 *   <li><b>默认关闭</b>：默认配置下上下文里**没有** seeder bean（写路径不该在启动时自动改数据）；</li>
 *   <li><b>主密钥为空时必须响亮失败**且不写任何行**</b>：不能把一条解不开的密文写进
 *       {@code channel.api_key_cipher}（admin 侧「加密失败即拒绝写」的契约）；</li>
 *   <li><b>渠道密钥来自配置/环境</b>：明文值只从构造参数（= 配置属性 / 环境变量）来，落库前一律
 *       过 {@code ChannelKeyService.encrypt}；测试里的值由字节算出 / 一眼可辨的合成串，
 *       tracked 文件里没有真密钥。</li>
 * </ol>
 */
class DemoChannelSeederTest extends AbstractIntegrationTest {

    private static final String TENANT = "demo-seed-it";
    private static final String MODEL = "demo-seed-it-model";

    /** 合成明文（不是任何真实密钥）：只用来验证「配置里的值 → 密文 → 能解回它」。 */
    private static final String PRIMARY_PLAINTEXT = "sk-demo-seed-primary-plaintext-synthetic";
    private static final String STANDBY_PLAINTEXT = "sk-demo-seed-standby-plaintext-synthetic";

    private static final List<String> DEMO_CHANNEL_NAMES = List.of("demo-primary", "demo-standby");

    @Autowired
    private ChannelMapper channelMapper;

    @Autowired
    private ModelRouteMapper modelRouteMapper;

    @Autowired
    private RateLimitPolicyMapper rateLimitPolicyMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private ApiKeyMapper apiKeyMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @BeforeEach
    void cleanBefore() {
        cleanDemoRows();
    }

    @AfterEach
    void cleanAfter() {
        cleanDemoRows();
    }

    private void cleanDemoRows() {
        jdbcTemplate.update("delete from model_route where model_name = ?", MODEL);
        jdbcTemplate.update("delete from rate_limit_policy where tenant_id in "
                + "(select id from tenant where name = ?)", TENANT);
        jdbcTemplate.update("delete from channel where name in (?, ?)",
                DEMO_CHANNEL_NAMES.get(0), DEMO_CHANNEL_NAMES.get(1));
        jdbcTemplate.update("delete from api_key where tenant_id in "
                + "(select id from tenant where name = ?)", TENANT);
        jdbcTemplate.update("delete from tenant where name = ?", TENANT);
    }

    /** 裁决 ①：默认关闭。{@code aihub.demo-seed.enabled} 的出厂值是 false，因此连 bean 都不该存在。 */
    @Test
    void theSeederIsOffByDefault() {
        assertThat(applicationContext.getBeanNamesForType(DemoChannelSeeder.class))
                .as("默认配置下不得注册 DemoChannelSeeder bean（它是写路径，不该在启动时自动改数据）")
                .isEmpty();
        assertThat(environment.getProperty("aihub.demo-seed.enabled", Boolean.class, Boolean.FALSE))
                .as("开关的出厂值必须是 false（缺省也必须是 false）")
                .isFalse();
    }

    /**
     * 裁决 ②：主密钥为空 → 响亮失败 + 一行都不写。
     *
     * <p>消息必须同时点出**本 seeder 的开关**与主密钥属性：只说「主密钥缺失」会让运维以为
     * 是别处的配置问题（这条同时是「seeder 自己的守卫在起作用、而不是恰好由 ChannelKeyService 兜住」
     * 的判别点）。
     */
    @Test
    void itRefusesToWriteAnythingWhenTheMasterKeyIsBlank() {
        DemoChannelSeeder seeder = seeder(new ChannelKeyService(""));
        long channelsBefore = channelMapper.selectCount(null);
        long routesBefore = modelRouteMapper.selectCount(null);
        long policiesBefore = rateLimitPolicyMapper.selectCount(null);

        assertThatThrownBy(() -> seeder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AIHUB_CHANNEL_MASTER_KEY")
                .hasMessageContaining("aihub.demo-seed.enabled");

        assertThat(channelMapper.selectCount(null)).as("不允许写进任何渠道行").isEqualTo(channelsBefore);
        assertThat(modelRouteMapper.selectCount(null)).as("不允许写进任何路由行").isEqualTo(routesBefore);
        assertThat(rateLimitPolicyMapper.selectCount(null))
                .as("不允许写进任何限流策略行（半截数据比没有数据更糟）").isEqualTo(policiesBefore);
    }

    /**
     * 裁决 ③：演示渠道的明文密钥**只来自配置**（{@code aihub.demo-seed.primary-api-key} 等）。
     * 本类不提供任何密钥默认值 —— 缺了就响亮失败，而不是把「空明文」或某个写死的合成串加密后落库。
     */
    @Test
    void itRefusesToWriteWhenTheDemoApiKeysAreNotConfigured() {
        long channelsBefore = channelMapper.selectCount(null);

        assertThatThrownBy(() -> seeder(configuredKeys(), "", "  ").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AIHUB_DEMO_SEED_PRIMARY_API_KEY")
                .hasMessageContaining("AIHUB_DEMO_SEED_STANDBY_API_KEY");

        assertThat(channelMapper.selectCount(null)).as("缺配置时一行都不许写").isEqualTo(channelsBefore);
    }

    /**
     * 裁决 ③ + 验收标准 5：打开且配了主密钥时写入 2 渠道 + 2 路由 + 1 租户级 + 1 key 级策略，
     * 且再跑一次不产生重复行（幂等）。key 级策略作用于该租户的**第一把** API Key。
     */
    @Test
    void itWritesTheDocumentedDemoTopologyFromConfigurationAndIsIdempotent() {
        Long tenantId = insertTenant();
        Long firstApiKeyId = insertApiKey(tenantId, "demo-seed-it-key-1", 1);
        Long secondApiKeyId = insertApiKey(tenantId, "demo-seed-it-key-2", 2);
        assertThat(firstApiKeyId).isLessThan(secondApiKeyId);
        ChannelKeyService keys = configuredKeys();

        DemoChannelSeeder seeder = seeder(keys);
        seeder.run(null);
        seeder.run(null);

        List<ChannelEntity> channels = demoChannels();
        assertThat(channels).extracting(ChannelEntity::getName)
                .as("幂等：两次运行仍然只有两条渠道").containsExactly("demo-primary", "demo-standby");
        ChannelEntity primary = channels.get(0);
        ChannelEntity standby = channels.get(1);

        assertThat(primary.getWeight()).isEqualTo(100);
        assertThat(standby.getWeight()).isEqualTo(1);
        assertThat(primary.getStatus()).isEqualTo("ACTIVE");
        assertThat(primary.getTimeoutMs()).isEqualTo(30_000);
        assertThat(primary.getKeyVersion()).isEqualTo(keys.currentKeyVersion());
        assertThat(primary.getBaseUrl()).isEqualTo("http://127.0.0.1:11434");

        // 密文必须是 ChannelKeyService.encrypt 基于**配置里的明文**产出的：能解回、且串里没有明文。
        AesGcmChannelCipher gatewaySide = new AesGcmChannelCipher(
                ChannelKeyRegistry.parse(masterKey()));
        assertThat(primary.getApiKeyCipher()).startsWith("v1:").doesNotContain(PRIMARY_PLAINTEXT);
        assertThat(gatewaySide.decrypt(primary.getApiKeyCipher())).contains(PRIMARY_PLAINTEXT);
        assertThat(gatewaySide.decrypt(standby.getApiKeyCipher())).contains(STANDBY_PLAINTEXT);

        List<ModelRouteEntity> routes = modelRouteMapper.selectList(
                new LambdaQueryWrapper<ModelRouteEntity>().eq(ModelRouteEntity::getModelName, MODEL)
                        .orderByAsc(ModelRouteEntity::getId));
        assertThat(routes).as("幂等：同一模型的两条候选路由").hasSize(2);
        assertThat(routes).extracting(ModelRouteEntity::getChannelId)
                .containsExactly(primary.getId(), standby.getId());
        assertThat(routes).extracting(ModelRouteEntity::getWeight).containsExactly(100, 1);
        assertThat(routes).allSatisfy(route -> assertThat(route.getStatus()).isEqualTo("ACTIVE"));

        List<RateLimitPolicyEntity> policies = rateLimitPolicyMapper.selectList(
                new LambdaQueryWrapper<RateLimitPolicyEntity>()
                        .eq(RateLimitPolicyEntity::getTenantId, tenantId)
                        .orderByAsc(RateLimitPolicyEntity::getId));
        assertThat(policies).as("幂等：一条租户级 + 一条 key 级").hasSize(2);
        RateLimitPolicyEntity tenantLevel = policies.get(0);
        RateLimitPolicyEntity keyLevel = policies.get(1);
        assertThat(tenantLevel.getApiKeyId()).isNull();
        assertThat(tenantLevel.getQps()).isEqualTo(20);
        assertThat(tenantLevel.getBurst()).isEqualTo(40);
        assertThat(keyLevel.getApiKeyId())
                .as("key 级策略作用于该租户的**第一把** API Key（api_key.id 最小的那条）")
                .isEqualTo(firstApiKeyId);
        assertThat(keyLevel.getQps()).as("key 级比租户级更严格，端到端演示才看得出两维限流")
                .isEqualTo(5);
        assertThat(keyLevel.getBurst()).isEqualTo(10);
    }

    /**
     * 演示数据不全（租户还没有 API Key）时**只 WARN 并跳过** key 级策略：先铸一把 key 再重启即可。
     * 这里同时钉住「跳过」不是「中断」—— 渠道与租户级策略照样写入。
     */
    @Test
    void itWarnsAndSkipsTheKeyLevelPolicyWhenTheTenantHasNoApiKeyYet() {
        Long tenantId = insertTenant();

        List<ILoggingEvent> warnings = captureSeederWarnings(() -> seeder(configuredKeys()).run(null));

        List<RateLimitPolicyEntity> policies = rateLimitPolicyMapper.selectList(
                new LambdaQueryWrapper<RateLimitPolicyEntity>()
                        .eq(RateLimitPolicyEntity::getTenantId, tenantId));
        assertThat(policies).as("没有 API Key 时只写租户级策略").hasSize(1);
        assertThat(policies.get(0).getApiKeyId()).isNull();
        assertThat(policies.get(0).getQps()).isEqualTo(20);
        assertThat(demoChannels()).as("跳过 key 级策略不该让整个 seeder 中断").hasSize(2);
        assertThat(warnings).extracting(ILoggingEvent::getLevel).contains(Level.WARN);
        assertThat(warnings).extracting(ILoggingEvent::getFormattedMessage)
                .as("WARN 必须指出「这个租户还没有 API Key」而不是静默跳过")
                .anySatisfy(message -> assertThat(message).contains(TENANT).contains("还没有 API Key"));
    }

    // --- 夹具 -----------------------------------------------------------

    /** 构造 seeder：参数与生产构造器一致，只是明文与租户名换成测试值。 */
    private DemoChannelSeeder seeder(ChannelKeyService keys) {
        return seeder(keys, PRIMARY_PLAINTEXT, STANDBY_PLAINTEXT);
    }

    private DemoChannelSeeder seeder(ChannelKeyService keys, String primaryKey, String standbyKey) {
        return new DemoChannelSeeder(channelMapper, modelRouteMapper, rateLimitPolicyMapper,
                tenantMapper, apiKeyMapper, keys, MODEL, TENANT,
                "http://127.0.0.1:11434", "http://127.0.0.1:11434",
                primaryKey, standbyKey, 20, 40, 5, 10);
    }

    private ChannelKeyService configuredKeys() {
        return new ChannelKeyService(masterKey());
    }

    private List<ChannelEntity> demoChannels() {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .in(ChannelEntity::getName, DEMO_CHANNEL_NAMES)
                .orderByAsc(ChannelEntity::getName));
    }

    private Long insertTenant() {
        jdbcTemplate.update("insert into tenant (name, status) values (?, ?)", TENANT, "ACTIVE");
        return jdbcTemplate.queryForObject("select id from tenant where name = ?", Long.class, TENANT);
    }

    /** 只写哈希列（明文 token 从不入库）：这里放的是从种子算出的合成串。 */
    private Long insertApiKey(Long tenantId, String keyId, int seed) {
        jdbcTemplate.update("insert into api_key (key_id, tenant_id, key_hash, name, status) values (?, ?, ?, ?, ?)",
                keyId, tenantId, syntheticHash(seed), keyId, "ACTIVE");
        return jdbcTemplate.queryForObject("select id from api_key where key_id = ?", Long.class, keyId);
    }

    private static String syntheticHash(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 37 + i);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    private static List<ILoggingEvent> captureSeederWarnings(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DemoChannelSeeder.class);
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static String masterKey() {
        return "v1:" + b64Key(13);
    }

    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
