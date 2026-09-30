package com.aihub.service.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.RatePolicy;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.ModelRouteEntity;
import com.aihub.dao.entity.RateLimitPolicyEntity;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ConfigVersionMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 组装 {@code GET /internal/config/snapshot} 的响应：一次调用把网关需要的**全部**配置给它
 * （渠道 + 密文 + 路由 + 限流策略 + 版本号）。设计文档 §7.2 的明文接口。
 *
 * <p><b>version 是单调的</b>（决策 D5）：{@code max(三张表的 max(updated_at), config_version 的水位)}。
 * V1 的三张表都有 {@code ON UPDATE CURRENT_TIMESTAMP(3)}，因此任何一次配置写入都会推进版本；
 * 而水位的存在修掉了 M3 登记的 A2 缺口 —— 只靠 {@code max(updated_at)} 时，**删掉最新那一行会让版本
 * 倒退**，而网关的本地/Redis 两侧比对与 {@code lastGood} 用的都是严格 {@code >}，于是一份更旧的快照
 * 会被记住并继续服务、且没有收敛信号。
 *
 * <p><b>水位只在配置写入路径上抬升</b>（{@code ConfigChangePublisher.bumpAndPublish}，Task 2/8/9/10），
 * 读路径**绝不写库**：{@link #snapshot()} 是 {@code @Transactional(readOnly = true)}，而 MySQL +
 * Connector/J 的 {@code readOnlyPropagatesToServer} 默认是**开**的（会发
 * {@code SET SESSION TRANSACTION READ ONLY}），在只读事务里写会直接 {@code ERROR 1792} ——
 * 正好打在 {@code GET /internal/config/snapshot} 这条里程碑依赖的路径上。
 *
 * <p><b>残余（D5 已登记，不修）</b>：手工 SQL / seeder 的写入会推进 {@code updated_at}（这一半仍在），
 * 但**手工 SQL 的删除不走控制台、就不会抬水位**，因此一条 raw SQL 的删除仍可能让版本回退一次。
 * M4 的验收（Task 17 第 3 步）因此必须走控制台/API 改配置；这也意味着 {@code config_version} 的水位
 * 在纯 SQL 运维路径上是空的。另一条**登记在案**的既有边界：同一毫秒内的两次写入可能得到相同的
 * {@code updated_at}。
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
    private final ConfigVersionMapper configVersionMapper;
    private final String defaultModel;

    public ConfigSnapshotService(ChannelMapper channelMapper, ModelRouteMapper modelRouteMapper,
                                 RateLimitPolicyMapper rateLimitPolicyMapper, JdbcTemplate jdbcTemplate,
                                 ConfigVersionMapper configVersionMapper,
                                 @Value("${aihub.upstream.default-model:}") String defaultModel) {
        this.channelMapper = channelMapper;
        this.modelRouteMapper = modelRouteMapper;
        this.rateLimitPolicyMapper = rateLimitPolicyMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.configVersionMapper = configVersionMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional(readOnly = true)
    public ConfigSnapshot snapshot() {
        return new ConfigSnapshot(currentVersion(), System.currentTimeMillis(),
                channels(), routes(), ratePolicies(),
                defaultModel == null || defaultModel.isBlank() ? null : defaultModel);
    }

    /**
     * 快照版本 = max(三张配置表的 max(updated_at), config_version 的水位)。
     *
     * <p><b>纯读：本方法绝不写库。</b>它由 {@link #snapshot()} 调用，而后者是
     * {@code @Transactional(readOnly = true)}；MySQL + Connector/J 的 {@code readOnlyPropagatesToServer}
     * 默认是开的（会发 {@code SET SESSION TRANSACTION READ ONLY}），在只读事务里写会直接
     * {@code ERROR 1792} —— 正好打在 {@code GET /internal/config/snapshot} 这条里程碑依赖的路径上。
     * 因此水位**只在配置写入路径**上抬升（{@code ConfigChangePublisher.bumpAndPublish}）。
     *
     * <p>为什么需要水位：{@code max(updated_at)} 会**倒退**（删掉最新那一行），而网关两侧的比对是
     * 严格 {@code >} —— 一旦倒退，更旧的快照会被 lastGood 记住并继续服务（M3 登记、M4 修）。
     */
    public long currentVersion() {
        long dbMax = maxUpdatedAtAcrossConfigTables();     // 既有实现保持不变（JdbcTemplate 查三张表）
        Long stored = configVersionMapper.current();
        return Math.max(dbMax, stored == null ? 0L : stored);
    }

    /**
     * 三张配置表的 {@code updated_at} 最大值（epoch 毫秒）；空库为 {@code 0}。
     *
     * <p>这一半仍然必要：**手工 SQL / seeder 写入不经过控制台，不会抬水位**，只能靠
     * {@code ON UPDATE CURRENT_TIMESTAMP(3)} 推进版本（D5）。
     */
    private long maxUpdatedAtAcrossConfigTables() {
        long max = 0L;
        for (String table : List.of("channel", "model_route", "rate_limit_policy")) {
            Long candidate = maxUpdatedAt(table);
            if (candidate != null && candidate > max) {
                max = candidate;
            }
        }
        return max;
    }

    /**
     * 一张配置表的 {@code updated_at} 最大值（epoch 毫秒）；空表为 {@code null}。
     *
     * <p><b>为什么用 {@link LocalDateTime} 读、而不是 {@code java.sql.Timestamp} / {@code Instant}</b>：
     * V1 的约定是「时间统一 {@code datetime(3)}，按 **UTC** 存储」（DDL 第 2 行），而
     * {@code datetime} 这一列类型**不带时区** —— 库里那串数字就是 UTC 墙上时间本身。
     * {@link LocalDateTime} 正是「不做任何时区换算」的载体，驱动对它原样搬运；而
     * {@code java.sql.Timestamp}（以及任何走 {@code Instant} 字段的映射）会把这串墙上时间按
     * **JDBC 连接时区**（解析成 LOCAL 时就是 **JVM 默认时区**）解释成瞬时 —— 本机是 Asia/Shanghai，
     * 于是那条连接上的版本整整早 8 小时
     * （实测：库里 {@code 2026-09-29T14:04:36.652} 被读成 {@code 1790661876652}，真值
     * {@code 1790690676652}，差 {@code -28800000} ms）。
     *
     * <p>这个 8 小时不是「略有偏差」：{@code max(updated_at)} 被这次读折成一个**比真实时刻更小的**
     * 毫秒数，于是以它为来源的版本（水位）**低于**配置真正被写入的时刻，而网关的
     * 版本比对是严格 {@code >} —— 控制面改了配置、数据面却认为收到的快照「不比手上的新」，
     * 于是 Task 3 的验收判据（配置改动在数秒内生效）静默不成立。今天这条路径大部分被
     * {@code ConfigChangePublisher}（用 {@code System.currentTimeMillis()} 抬水位）盖住，
     * 剩下的正是**裸 SQL / seeder** 这条只有 {@code max(updated_at)} 可用的路径。
     *
     * <p><b>触发条件与更正（2026-09-29 独立评审 I-1）</b>：上面的 8 小时偏差只在
     * **JDBC 连接时区不是 UTC** 时出现 —— URL 不带 {@code serverTimezone} / {@code connectionTimeZone}
     * 时驱动按 **JVM 默认时区**解释（本仓库的**测试** URL 当时正是这种方言），本机 Asia/Shanghai
     * 于是差 8 小时。**发布的两个 URL 都钉了 {@code serverTimezone=UTC}**
     * （{@code application.yml:8}、{@code docker-compose.yml:65}），评审在那条连接上实测旧读法与新读法
     * **逐位相等**（{@code old − new = 0}），{@code -Duser.timezone=UTC} 下旧实现同样 {@code delta = 0}
     * ⇒ **旧代码在生产上没有可观测差异**。本次改动的价值是**不再依赖那个连接参数、也不依赖
     * JVM 默认时区**。纪律：连接时区仍要**显式钉死**（发布 URL 已经这么做），但新代码不许依赖它。
     *
     * <p>{@code toInstant(ZoneOffset.UTC)} 把「无时区的墙上时间」显式声明成 UTC 瞬时 ——
     * 基准写在代码里，不依赖任何 JVM 默认设置。
     */
    private Long maxUpdatedAt(String table) {
        // 表名来自本类的常量列表，不来自任何外部输入（没有注入面）。
        LocalDateTime max = jdbcTemplate.queryForObject(
                "select max(updated_at) from " + table, LocalDateTime.class);
        return max == null ? null : max.toInstant(ZoneOffset.UTC).toEpochMilli();
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
