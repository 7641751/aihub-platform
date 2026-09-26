package com.aihub.service.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.RatePolicy;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.ModelRouteEntity;
import com.aihub.dao.entity.RateLimitPolicyEntity;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 组装 {@code GET /internal/config/snapshot} 的响应：一次调用把网关需要的**全部**配置给它
 * （渠道 + 密文 + 路由 + 限流策略 + 版本号）。设计文档 §7.2 的明文接口。
 *
 * <p><b>version 是单调时间戳</b>（决策 5）：三张表的 {@code updated_at} 取最大。V1 的三张表都有
 * {@code ON UPDATE CURRENT_TIMESTAMP(3)}，因此任何一次配置写入都会推进它。空库返回 {@code 0}。
 * 已知缺口（**登记在案，不修**）：同一毫秒内的两次写入可能得到相同的 {@code updated_at}。
 *
 * <p><b>限流策略的排序是契约的一部分</b>（决策 17）：同一租户的租户级策略必须按 {@code id} 升序、
 * 且排在 key 级策略之前 —— 这样 gateway 侧「在每一维内部取最后一条」（租户级取最后一条租户级行、
 * key 级取该 {@code apiKeyId} 的最后一行）就等价于「取该维 {@code id} 最大的那条」。
 * **两维都参与判定**（决策 7，2026-09-26 依控制器 pre-flight 评审修订），因此本查询把
 * {@code api_key_id} 非空的行也一样组装进快照。
 *
 * <p><b>只有 {@code ACTIVE} 行会进快照（G5，控制器裁决，2026-09-26）</b>：{@code channel} /
 * {@code model_route} / {@code rate_limit_policy} 三张表的 {@code INACTIVE}（以及任何非
 * {@code ACTIVE}）行在组装层就被挡掉。计划正文原本写的是「全部行都组装（含 DISABLED），由 gateway 侧
 * 过滤」—— 那条路不成立：{@code RatePolicy} 根本没有状态分量，网关侧无从过滤；而当网关拿到一条
 * 非 ACTIVE 的渠道/路由行时也只是静静地不用它（{@code usable()} 返回 false），也就是「停用」与
 * 「不存在」在网关侧无法区分、更不会有任何用例变红。DAO 刻意不过滤（{@code ChannelMapper} 的
 * javadoc 写明理由），**这里就是唯一的过滤点**，三条各由一个「同表里塞一条 INACTIVE」的容器用例钉住。
 *
 * <p><b>version 不受状态影响</b>：{@code max(updated_at)} 覆盖三张表的**全部**行，因此「把一条渠道
 * 停用」同样会推进版本 —— 这正是网关及时丢掉那条渠道所需要的信号。
 *
 * <p>**本查询不返回任何明文密钥**，只有密文。
 */
@Service
public class ConfigSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ConfigSnapshotService.class);

    /**
     * {@code rate_limit_policy.status} 的「生效」取值。V1 的 DDL 默认就是 {@code 'ACTIVE'}，
     * 而共享的 {@code RatePolicy} **刻意没有状态分量**（网关只消费生效的那些行），
     * 因此这个字面量没有可以复用的共享常量，只能在本组装点显式写出。
     */
    private static final String POLICY_STATUS_ACTIVE = "ACTIVE";

    private final ChannelMapper channelMapper;
    private final ModelRouteMapper modelRouteMapper;
    private final RateLimitPolicyMapper rateLimitPolicyMapper;
    private final JdbcTemplate jdbcTemplate;
    private final String defaultModel;

    public ConfigSnapshotService(ChannelMapper channelMapper, ModelRouteMapper modelRouteMapper,
                                 RateLimitPolicyMapper rateLimitPolicyMapper, JdbcTemplate jdbcTemplate,
                                 @Value("${aihub.upstream.default-model:}") String defaultModel) {
        this.channelMapper = channelMapper;
        this.modelRouteMapper = modelRouteMapper;
        this.rateLimitPolicyMapper = rateLimitPolicyMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.defaultModel = defaultModel;
    }

    @Transactional(readOnly = true)
    public ConfigSnapshot snapshot() {
        return new ConfigSnapshot(currentVersion(), System.currentTimeMillis(),
                channels(), routes(), ratePolicies(),
                defaultModel == null || defaultModel.isBlank() ? null : defaultModel);
    }

    /** 三张表的 {@code updated_at} 最大值（epoch 毫秒）；空库为 0。 */
    public long currentVersion() {
        long max = 0L;
        for (String table : List.of("channel", "model_route", "rate_limit_policy")) {
            Long candidate = maxUpdatedAt(table);
            if (candidate != null && candidate > max) {
                max = candidate;
            }
        }
        return max;
    }

    private Long maxUpdatedAt(String table) {
        // 表名来自本类的常量列表，不来自任何外部输入（没有注入面）。
        Timestamp max = jdbcTemplate.queryForObject("select max(updated_at) from " + table, Timestamp.class);
        return max == null ? null : max.toInstant().toEpochMilli();
    }

    private List<ChannelDescriptor> channels() {
        List<ChannelDescriptor> channels = new ArrayList<>();
        for (ChannelEntity entity : channelMapper.selectList(
                new QueryWrapper<ChannelEntity>().eq("status", ChannelDescriptor.STATUS_ACTIVE))) {
            channels.add(new ChannelDescriptor(
                    entity.getId() == null ? 0L : entity.getId(),
                    entity.getName(),
                    entity.getBaseUrl(),
                    entity.getApiKeyCipher(),
                    entity.getKeyVersion() == null ? 0 : entity.getKeyVersion(),
                    entity.getTimeoutMs() == null ? 0 : entity.getTimeoutMs(),
                    entity.getStatus(),
                    entity.getWeight() == null ? 0 : entity.getWeight(),
                    entity.getPriority() == null ? 0 : entity.getPriority()));
        }
        return channels;
    }

    private List<ModelRouteDescriptor> routes() {
        List<ModelRouteDescriptor> routes = new ArrayList<>();
        for (ModelRouteEntity entity : modelRouteMapper.selectList(
                new QueryWrapper<ModelRouteEntity>().eq("status", ModelRouteDescriptor.STATUS_ACTIVE))) {
            routes.add(new ModelRouteDescriptor(
                    entity.getModelName(),
                    entity.getChannelId() == null ? 0L : entity.getChannelId(),
                    entity.getWeight() == null ? 0 : entity.getWeight(),
                    entity.getPriority() == null ? 0 : entity.getPriority(),
                    entity.getStatus()));
        }
        return routes;
    }

    /**
     * 排序规则见类注释（决策 17）：{@code tenant_id ASC, api_key_id IS NULL DESC, id ASC}。
     * MySQL 里 {@code api_key_id IS NULL} 为真时是 1，因此 {@code DESC} 把租户级排在前面。
     *
     * <p>{@code status = 'ACTIVE'} 也在这里（G5）：{@code RatePolicy} 没有状态分量，所以「停用一条策略」
     * 的唯一表达就是「它不出现在快照里」——「取最后一条」因此落在**最后一个 ACTIVE 行**上。
     */
    private List<RatePolicy> ratePolicies() {
        QueryWrapper<RateLimitPolicyEntity> query = new QueryWrapper<>();
        query.eq("status", POLICY_STATUS_ACTIVE)
                .orderByAsc("tenant_id").orderByDesc("api_key_id is null").orderByAsc("id");
        List<RatePolicy> policies = new ArrayList<>();
        Set<String> seenDimensions = new HashSet<>();
        Set<String> warnedDimensions = new HashSet<>();
        for (RateLimitPolicyEntity entity : rateLimitPolicyMapper.selectList(query)) {
            policies.add(new RatePolicy(entity.getTenantId(), entity.getApiKeyId(),
                    entity.getQps() == null ? 0 : entity.getQps(),
                    entity.getBurst() == null ? 0 : entity.getBurst()));
            String dimension = entity.getApiKeyId() == null
                    ? "租户级"
                    : " key 级（api_key_id=" + entity.getApiKeyId() + "）";
            String dimensionKey = entity.getTenantId() + ":" + entity.getApiKeyId();
            if (!seenDimensions.add(dimensionKey) && warnedDimensions.add(dimensionKey)) {
                // V1 没有唯一约束（决策 17）：同维度多条 ACTIVE 策略时取 id 最大的那条，
                // 但必须让人知道数据是脏的（每个维度各判各的、各告警一次）。
                log.warn("租户 {} 有多条{} ACTIVE 限流策略，表上没有唯一约束，将按 id 最大的那条生效；"
                        + "请停用多余的行（M4 的控制台会强制单条生效）", entity.getTenantId(), dimension);
            }
        }
        return policies;
    }
}
