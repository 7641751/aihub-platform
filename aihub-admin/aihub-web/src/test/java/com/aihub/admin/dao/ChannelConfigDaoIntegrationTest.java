package com.aihub.admin.dao;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.ModelRouteEntity;
import com.aihub.dao.entity.RateLimitPolicyEntity;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.aihub.service.channel.ChannelKeyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 12 的验收标准 1：**三个实体与 V1 的列一一对应，三张表都能被 MyBatis-Plus 读写**。
 *
 * <p>纯映射层是「编译通过 + 有人肉核对了列名」最容易蒙混过去的那种代码：字段名写错（
 * {@code baseUrl} ↔ {@code base_url}）、少一个字段、{@code @TableName} 指错表，编译器一句话都不会说。
 * 因此这里用**真实 MySQL**（容器）把整条路径跑一遍：JDBC 裸 INSERT → mapper 读回 → 逐字段断言。
 *
 * <p>另外两件事也在这里钉住（都是 Task 13 组装快照的直接依赖）：
 * <ul>
 *   <li><b>读取顺序</b>：{@code rate_limit_policy} 上没有唯一键（决策 17），「同维度取最后一条」
 *       靠的就是 {@code id} 升序 —— 因此断言 mapper 交回来的顺序与插入顺序一致；</li>
 *   <li><b>状态不被 DAO 过滤</b>：同一张表里塞一条 {@code INACTIVE}，它必须**出现**在这次读取里 ——
 *       ACTIVE 过滤是 Task 13 组装快照的责任（网关侧的 DTO 没有状态分量，见 Task 4 报告 §3），
 *       如果它悄悄挪进 DAO（或被误认为已在 DAO），停用行会被照用而没有任何用例变红。</li>
 * </ul>
 *
 * <p>测试数据一律用一眼可辨的合成值；渠道密钥走真实的 {@link ChannelKeyService} 加密，
 * 并断言落库的串里**没有明文**（明文渠道密钥从不入库）。
 */
class ChannelConfigDaoIntegrationTest extends AbstractIntegrationTest {

    private static final String PLAINTEXT = "sk-channel-plaintext-synthetic";

    private static final String CHANNEL_INSERT =
            "insert into channel (name, provider, base_url, api_key_cipher, key_version, models_json,"
                    + " weight, priority, timeout_ms, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String POLICY_INSERT =
            "insert into rate_limit_policy (tenant_id, api_key_id, qps, burst, status) values (?, ?, ?, ?, ?)";

    @Autowired
    private ChannelMapper channelMapper;

    @Autowired
    private ModelRouteMapper modelRouteMapper;

    @Autowired
    private RateLimitPolicyMapper rateLimitPolicyMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        // 本类只删除自己插入的行（按前缀匹配），不动其它用例/演示数据留下的内容。
        jdbcTemplate.update("delete from model_route where model_name like 'dao-it-%'");
        jdbcTemplate.update("delete from rate_limit_policy where tenant_id = 987654");
        jdbcTemplate.update("delete from channel where name like 'dao-it-%'");
    }

    @Test
    void aChannelRowRoundTripsThroughMybatisPlusWithItsCipherAndEveryColumn() {
        String masterKey = "v1:" + b64Key(2);
        ChannelKeyService keys = new ChannelKeyService(masterKey);
        String cipher = keys.encrypt(PLAINTEXT);

        jdbcTemplate.update(CHANNEL_INSERT, "dao-it-primary", "openai", "https://upstream.example.com/v1",
                cipher, keys.currentKeyVersion(), "[\"gpt-4o-mini\"]", 120, 7, 30_000, "ACTIVE");

        ChannelEntity loaded = channelMapper.selectOne(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getName, "dao-it-primary"));

        assertThat(loaded).as("MyBatis-Plus 必须能把这一行映射成实体（列名 ↔ 字段名对不上时这里就是 null）")
                .isNotNull();
        assertThat(loaded.getId()).as("自增主键必须回填").isNotNull();
        assertThat(loaded.getProvider()).isEqualTo("openai");
        assertThat(loaded.getBaseUrl()).isEqualTo("https://upstream.example.com/v1");
        assertThat(loaded.getKeyVersion()).isEqualTo(1);
        assertThat(loaded.getModelsJson()).as("models_json 是 JSON 列，M3 用 String 原样映射")
                .isEqualTo("[\"gpt-4o-mini\"]");
        assertThat(loaded.getWeight()).isEqualTo(120);
        assertThat(loaded.getPriority()).isEqualTo(7);
        assertThat(loaded.getTimeoutMs()).isEqualTo(30_000);
        assertThat(loaded.getStatus()).isEqualTo("ACTIVE");

        // 密文列是「admin 加密 → 网关解密」这条链路的落库形态：读回来必须仍是那条自描述密文。
        assertThat(loaded.getApiKeyCipher())
                .as("api_key_cipher 必须原样读回（不是被截断/被改写的值），且明文渠道密钥绝不入库")
                .isEqualTo(cipher)
                .doesNotContain(PLAINTEXT);
        assertThat(new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey))
                .decrypt(loaded.getApiKeyCipher()))
                .as("落库的密文必须能被解码方（同一个 AesGcmChannelCipher）解开")
                .contains(PLAINTEXT);
    }

    @Test
    void routeWeightAndPriorityComeFromTheRouteTableNotTheChannelDefaults() {
        jdbcTemplate.update(CHANNEL_INSERT, "dao-it-route-target", "openai", "https://route.example.com",
                "v1:QUJD", 1, null, 100, 0, 60_000, "ACTIVE");
        Long channelId = jdbcTemplate.queryForObject(
                "select id from channel where name = ?", Long.class, "dao-it-route-target");

        // 路由行走 MyBatis-Plus 的**写**路径（channel 那行故意走裸 JDBC INSERT，让两个方向都被覆盖）。
        ModelRouteEntity route = new ModelRouteEntity();
        route.setModelName("dao-it-model");
        route.setChannelId(channelId);
        route.setWeight(5);
        route.setPriority(3);
        route.setStatus("ACTIVE");
        assertThat(modelRouteMapper.insert(route)).isEqualTo(1);

        ModelRouteEntity loaded = modelRouteMapper.selectOne(new LambdaQueryWrapper<ModelRouteEntity>()
                .eq(ModelRouteEntity::getModelName, "dao-it-model"));

        assertThat(loaded).isNotNull();
        assertThat(loaded.getChannelId()).isEqualTo(channelId);
        // 权重与优先级取 model_route 的值（5 / 3），不是 channel 上的默认值（100 / 0）：
        // 同一个渠道可以给不同模型不同的权重，这是 ModelRouteDescriptor 的既有契约。
        assertThat(loaded.getWeight()).isEqualTo(5);
        assertThat(loaded.getPriority()).isEqualTo(3);
        assertThat(loaded.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void policiesRoundTripInIdOrderWithBothDimensionsAndInactiveRowsLeftToTheAssembler() {
        jdbcTemplate.update(POLICY_INSERT, 987654L, null, 20, 40, "ACTIVE");
        jdbcTemplate.update(POLICY_INSERT, 987654L, null, 15, 30, "INACTIVE");
        jdbcTemplate.update(POLICY_INSERT, 987654L, 4321L, 5, 10, "ACTIVE");

        // 组装快照时读的是 selectList(无条件)：InnoDB 的全表扫描走聚簇索引 = id 升序，
        // 「同维度取最后一条」正是建立在这个顺序上（决策 17）。这里把 orderByAsc 显式写出来，
        // 只为让本用例的意图不依赖「读者知道 InnoDB 的扫描顺序」——被钉住的是 mapper 交回来的
        // 行内容与相对顺序，不是某个 ORDER BY 子句。
        List<RateLimitPolicyEntity> loaded = rateLimitPolicyMapper.selectList(
                new LambdaQueryWrapper<RateLimitPolicyEntity>()
                        .eq(RateLimitPolicyEntity::getTenantId, 987654L)
                        .orderByAsc(RateLimitPolicyEntity::getId));

        assertThat(loaded).extracting(RateLimitPolicyEntity::getId).isSorted();
        assertThat(loaded).hasSize(3);

        List<RateLimitPolicyEntity> tenantLevel = loaded.stream().filter(p -> p.getApiKeyId() == null).toList();
        List<RateLimitPolicyEntity> keyLevel = loaded.stream().filter(p -> p.getApiKeyId() != null).toList();

        assertThat(tenantLevel).as("api_key_id 为 NULL 的租户级行必须能读回（NULL ↔ null 的映射要对）")
                .hasSize(2).allSatisfy(policy -> {
                    assertThat(policy.getApiKeyId()).isNull();
                    assertThat(policy.getQps()).isNotNull();
                    assertThat(policy.getBurst()).isNotNull();
                    assertThat(policy.getStatus()).isNotNull();
                });
        assertThat(tenantLevel).extracting(RateLimitPolicyEntity::getQps).as("读取顺序 = 插入顺序 = id 升序（决策 17）")
                .containsExactly(20, 15);
        assertThat(tenantLevel.get(tenantLevel.size() - 1).getQps())
                .as("「同维度取最后一条」落在后插入的那一行上")
                .isEqualTo(15);
        assertThat(keyLevel).singleElement().satisfies(policy -> {
            assertThat(policy.getApiKeyId()).isEqualTo(4321L);
            assertThat(policy.getQps()).isEqualTo(5);
            assertThat(policy.getBurst()).isEqualTo(10);
        });
        assertThat(loaded).extracting(RateLimitPolicyEntity::getStatus)
                .as("DAO 不做 ACTIVE 过滤：INACTIVE 行也必须被读出来（过滤是 Task 13 组装快照的责任）")
                .contains("INACTIVE");
    }

    @Test
    void aFullTableScanReturnsEveryRowSoTheSnapshotAssemblerCanSeeInactiveOnes() {
        jdbcTemplate.update(POLICY_INSERT, 987654L, null, 1, 2, "INACTIVE");

        List<RateLimitPolicyEntity> all = rateLimitPolicyMapper.selectList(null);

        assertThat(all).as("selectList(null) 是无条件全表读取（快照要自己按 status 过滤）")
                .anySatisfy(policy -> {
                    assertThat(policy.getTenantId()).isEqualTo(987654L);
                    assertThat(policy.getStatus()).isEqualTo("INACTIVE");
                });
    }

    /**
     * 写入方向也要走一遍 MyBatis-Plus（而不是只信「读回来对」）：{@code insert} 只发送**非 null** 字段，
     * 因此字段与列的对应关系在写路径上是另一条独立的风险（漏映射一个字段 = 那一列永远拿库里的默认值）。
     * 这里插一条完整的渠道行，再断言**每一列**都落了库（key 级策略键 {@code api_key_id} 可空也一并覆盖）。
     */
    @Test
    void aChannelInsertedThroughTheMapperLandsEveryColumnInTheDatabase() {
        ChannelEntity entity = new ChannelEntity();
        entity.setName("dao-it-write");
        entity.setProvider("anthropic");
        entity.setBaseUrl("https://write.example.com/v1");
        entity.setApiKeyCipher("v1:QUJD");
        entity.setKeyVersion(2);
        entity.setModelsJson("[\"claude-3-haiku\"]");
        entity.setWeight(55);
        entity.setPriority(4);
        entity.setTimeoutMs(12_000);
        entity.setStatus("DISABLED");

        assertThat(channelMapper.insert(entity)).as("insert 必须影响一行").isEqualTo(1);
        assertThat(entity.getId()).as("自增主键必须回填到实体上（否则调用方拿不到刚写的 id）").isNotNull();

        ChannelEntity loaded = channelMapper.selectById(entity.getId());
        assertThat(loaded.getProvider()).isEqualTo("anthropic");
        assertThat(loaded.getBaseUrl()).isEqualTo("https://write.example.com/v1");
        assertThat(loaded.getApiKeyCipher()).isEqualTo("v1:QUJD");
        assertThat(loaded.getKeyVersion()).isEqualTo(2);
        assertThat(loaded.getModelsJson()).isEqualTo("[\"claude-3-haiku\"]");
        assertThat(loaded.getWeight()).isEqualTo(55);
        assertThat(loaded.getPriority()).isEqualTo(4);
        assertThat(loaded.getTimeoutMs()).isEqualTo(12_000);
        assertThat(loaded.getStatus()).as("DISABLED 必须原样落库（不是被默认值 ACTIVE 覆盖）").isEqualTo("DISABLED");

        // 策略表的写入方向：api_key_id 为 null（租户级）与 not-null（key 级）都能落库。
        RateLimitPolicyEntity tenantLevel = new RateLimitPolicyEntity();
        tenantLevel.setTenantId(987654L);
        tenantLevel.setApiKeyId(null);
        tenantLevel.setQps(33);
        tenantLevel.setBurst(66);
        tenantLevel.setStatus("ACTIVE");
        assertThat(rateLimitPolicyMapper.insert(tenantLevel)).isEqualTo(1);
        assertThat(rateLimitPolicyMapper.selectById(tenantLevel.getId()).getApiKeyId())
                .as("租户级策略的 api_key_id 必须落成 NULL").isNull();
        assertThat(rateLimitPolicyMapper.selectById(tenantLevel.getId()).getQps()).isEqualTo(33);
    }

    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 17 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
