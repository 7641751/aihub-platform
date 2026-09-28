package com.aihub.service.channel;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.dao.entity.ApiKeyEntity;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.ModelRouteEntity;
import com.aihub.dao.entity.RateLimitPolicyEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.ApiKeyMapper;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.aihub.dao.mapper.TenantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * **开发专用**的演示数据（两条同模型渠道 + 一条租户级限流策略 + 一条 key 级限流策略），用来做 M3 的验收
 * （多渠道 + 权重 + 故障转移 + 熔断 + **两维限流**）。
 *
 * <p><b>默认关闭</b>（{@code aihub.demo-seed.enabled=false}，见 {@link ConditionalOnProperty}）。
 * 理由：它是一个**写入路径**，不应该在生产启动时自动改数据；而且真渠道的密钥只能由运维提供，
 * 不该有默认值。
 *
 * <p><b>为什么不是 Flyway 迁移</b>（决策 12）：迁移脚本是**一次性、不可回滚、随代码分发的**
 * 数据变更，而演示渠道依赖环境（base-url、密钥）。更硬的约束是
 * {@code SchemaMigrationTest} 把迁移集合（version + description）**逐条显式钉住** ——
 * 加迁移就必须**有意**改那条断言，护栏因此是显式的，而不是被削弱。
 *
 * <p><b>幂等</b>：按名字 / 按 (模型, 渠道) / 按 (租户, 维度) 判断「已存在就跳过」，因此重复启动
 * 不会产生重复行。
 *
 * <p><b>明文密钥一律来自配置</b>（{@code aihub.demo-seed.primary-api-key} /
 * {@code standby-api-key} ← {@code AIHUB_DEMO_SEED_PRIMARY_API_KEY} / {@code ..._STANDBY_API_KEY}）：
 * 本类**没有任何密钥默认值**，缺了就在启动时**响亮失败**并给出配置指引 —— 落到
 * {@code channel.api_key_cipher} 的永远只有 {@link ChannelKeyService#encrypt} 产出的密文。
 * 主密钥（{@code aihub.channel.master-key}）为空时同样响亮失败，绝不写「解不开的密文」。
 *
 * <p><b>日志只打 id / 名字 / 数字</b>：明文与密文都不进日志。
 */
@Component
@ConditionalOnProperty(name = "aihub.demo-seed.enabled", havingValue = "true")
public class DemoChannelSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoChannelSeeder.class);

    private static final String PRIMARY_CHANNEL_NAME = "demo-primary";
    private static final String STANDBY_CHANNEL_NAME = "demo-standby";

    private final ChannelMapper channelMapper;
    private final ModelRouteMapper modelRouteMapper;
    private final RateLimitPolicyMapper rateLimitPolicyMapper;
    private final TenantMapper tenantMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ChannelKeyService keyService;

    private final String model;
    private final String tenantName;
    private final String primaryBaseUrl;
    private final String standbyBaseUrl;
    private final String primaryApiKey;
    private final String standbyApiKey;
    private final int qps;
    private final int burst;
    private final int keyQps;
    private final int keyBurst;

    public DemoChannelSeeder(ChannelMapper channelMapper, ModelRouteMapper modelRouteMapper,
                             RateLimitPolicyMapper rateLimitPolicyMapper, TenantMapper tenantMapper,
                             ApiKeyMapper apiKeyMapper, ChannelKeyService keyService,
                             @Value("${aihub.demo-seed.model:demo-model}") String model,
                             @Value("${aihub.demo-seed.tenant:demo}") String tenantName,
                             @Value("${aihub.demo-seed.primary-base-url:http://host.docker.internal:11434}")
                             String primaryBaseUrl,
                             @Value("${aihub.demo-seed.standby-base-url:http://host.docker.internal:11434}")
                             String standbyBaseUrl,
                             @Value("${aihub.demo-seed.primary-api-key:}") String primaryApiKey,
                             @Value("${aihub.demo-seed.standby-api-key:}") String standbyApiKey,
                             @Value("${aihub.demo-seed.qps:20}") int qps,
                             @Value("${aihub.demo-seed.burst:40}") int burst,
                             @Value("${aihub.demo-seed.key-qps:5}") int keyQps,
                             @Value("${aihub.demo-seed.key-burst:10}") int keyBurst) {
        this.channelMapper = channelMapper;
        this.modelRouteMapper = modelRouteMapper;
        this.rateLimitPolicyMapper = rateLimitPolicyMapper;
        this.tenantMapper = tenantMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.keyService = keyService;
        this.model = model;
        this.tenantName = tenantName;
        this.primaryBaseUrl = primaryBaseUrl;
        this.standbyBaseUrl = standbyBaseUrl;
        this.primaryApiKey = primaryApiKey;
        this.standbyApiKey = standbyApiKey;
        this.qps = qps;
        this.burst = burst;
        this.keyQps = keyQps;
        this.keyBurst = keyBurst;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!keyService.configured()) {
            // 主密钥没配就无法加密渠道密钥 → 直接失败并给出配置指引（而不是写一条不可解的密文）。
            throw new IllegalStateException(
                    "aihub.demo-seed.enabled=true 需要先配置渠道主密钥 aihub.channel.master-key"
                            + "（环境变量 AIHUB_CHANNEL_MASTER_KEY）；生成方法见 .env.example");
        }
        if (isBlank(primaryApiKey) || isBlank(standbyApiKey)) {
            // 演示渠道的明文密钥**只来自配置**：本类刻意不提供任何默认值，
            // 否则就等于把一个明文密钥字面量写进代码库（那是脚手架，不是演示数据）。
            throw new IllegalStateException(
                    "aihub.demo-seed.enabled=true 需要提供演示渠道的明文密钥："
                            + "aihub.demo-seed.primary-api-key / aihub.demo-seed.standby-api-key"
                            + "（环境变量 AIHUB_DEMO_SEED_PRIMARY_API_KEY / AIHUB_DEMO_SEED_STANDBY_API_KEY）");
        }
        Long primary = ensureChannel(PRIMARY_CHANNEL_NAME, primaryBaseUrl, primaryApiKey, 100);
        Long standby = ensureChannel(STANDBY_CHANNEL_NAME, standbyBaseUrl, standbyApiKey, 1);
        ensureRoute(model, primary, 100, 0);
        ensureRoute(model, standby, 1, 0);
        ensureTenantPolicy(tenantName, qps, burst);
        // 决策 7（修订）：**key 级策略也要能被端到端演示** —— 它比租户级更严格（5/10 vs 20/40），
        // 因此「换了 key 以后限流数字变了」这件事在演示里是可观察的，而不是只能靠单元测试相信。
        ensureKeyLevelPolicy(tenantName, keyQps, keyBurst);
        log.info("演示数据就绪：模型 {} 有两条候选渠道（primary weight=100 / standby weight=1），"
                        + "租户 {} 限流 {}qps burst={}，该租户的第一把 key 覆盖为 {}qps burst={}",
                model, tenantName, qps, burst, keyQps, keyBurst);
    }

    private Long ensureChannel(String name, String baseUrl, String plaintextKey, int weight) {
        ChannelEntity existing = channelMapper.selectOne(
                new LambdaQueryWrapper<ChannelEntity>().eq(ChannelEntity::getName, name));
        if (existing != null) {
            return existing.getId();
        }
        ChannelEntity entity = new ChannelEntity();
        entity.setName(name);
        entity.setProvider("openai-compatible");
        entity.setBaseUrl(baseUrl);
        entity.setApiKeyCipher(keyService.encrypt(plaintextKey));
        entity.setKeyVersion(keyService.currentKeyVersion());
        entity.setWeight(weight);
        entity.setPriority(0);
        entity.setTimeoutMs(30_000);
        entity.setStatus(ChannelDescriptor.STATUS_ACTIVE);
        channelMapper.insert(entity);
        // 只打 id / 名字 / 权重：明文与密文都不进日志。
        log.info("已写入演示渠道 {}（id={}，权重 {}）", name, entity.getId(), weight);
        return entity.getId();
    }

    private void ensureRoute(String modelName, Long channelId, int weight, int priority) {
        Long existing = modelRouteMapper.selectCount(new LambdaQueryWrapper<ModelRouteEntity>()
                .eq(ModelRouteEntity::getModelName, modelName)
                .eq(ModelRouteEntity::getChannelId, channelId));
        if (existing != null && existing > 0) {
            return;
        }
        ModelRouteEntity entity = new ModelRouteEntity();
        entity.setModelName(modelName);
        entity.setChannelId(channelId);
        entity.setWeight(weight);
        entity.setPriority(priority);
        entity.setStatus(ModelRouteDescriptor.STATUS_ACTIVE);
        modelRouteMapper.insert(entity);
    }

    private void ensureTenantPolicy(String name, int tenantQps, int tenantBurst) {
        TenantEntity tenant = tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getName, name));
        if (tenant == null) {
            log.warn("演示租户 {} 不存在（先铸一把 API Key 会自动创建它），跳过限流策略", name);
            return;
        }
        Long existing = rateLimitPolicyMapper.selectCount(new LambdaQueryWrapper<RateLimitPolicyEntity>()
                .eq(RateLimitPolicyEntity::getTenantId, tenant.getId())
                .isNull(RateLimitPolicyEntity::getApiKeyId));
        if (existing != null && existing > 0) {
            return;
        }
        RateLimitPolicyEntity entity = new RateLimitPolicyEntity();
        entity.setTenantId(tenant.getId());
        entity.setApiKeyId(null);
        entity.setQps(tenantQps);
        entity.setBurst(tenantBurst);
        entity.setStatus(ChannelDescriptor.STATUS_ACTIVE);
        rateLimitPolicyMapper.insert(entity);
        log.info("已写入演示限流策略：租户 {} {}qps burst={}", name, tenantQps, tenantBurst);
    }

    /**
     * **key 级**限流策略（决策 7 修订）：作用于该租户的**第一把** API Key（{@code api_key.id} 最小的那条，
     * 与 V1 的建表顺序一致，因此演示时「先铸的那把 key」就是被覆盖的那把）。
     *
     * <p>找不到租户或该租户还没有 API Key 时**只 WARN 并跳过**（与 {@link #ensureTenantPolicy} 同款）：
     * 演示数据不全不能让 admin 启动失败；先铸一把 key 再重启即可。
     */
    private void ensureKeyLevelPolicy(String name, int keyQps, int keyBurst) {
        TenantEntity tenant = tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getName, name));
        if (tenant == null) {
            log.warn("演示租户 {} 不存在（先铸一把 API Key 会自动创建它），跳过 key 级限流策略", name);
            return;
        }
        ApiKeyEntity apiKey = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKeyEntity>()
                .eq(ApiKeyEntity::getTenantId, tenant.getId())
                .orderByAsc(ApiKeyEntity::getId)
                .last("limit 1"));
        if (apiKey == null) {
            log.warn("演示租户 {} 还没有 API Key，跳过 key 级限流策略（铸一把 key 后重启即可写入）", name);
            return;
        }
        Long existing = rateLimitPolicyMapper.selectCount(new LambdaQueryWrapper<RateLimitPolicyEntity>()
                .eq(RateLimitPolicyEntity::getTenantId, tenant.getId())
                .eq(RateLimitPolicyEntity::getApiKeyId, apiKey.getId()));
        if (existing != null && existing > 0) {
            return;
        }
        RateLimitPolicyEntity entity = new RateLimitPolicyEntity();
        entity.setTenantId(tenant.getId());
        entity.setApiKeyId(apiKey.getId());
        entity.setQps(keyQps);
        entity.setBurst(keyBurst);
        entity.setStatus(ChannelDescriptor.STATUS_ACTIVE);
        rateLimitPolicyMapper.insert(entity);
        log.info("已写入演示 key 级限流策略：租户 {} 的 api_key_id={} 覆盖为 {}qps burst={}",
                name, apiKey.getId(), keyQps, keyBurst);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
