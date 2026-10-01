package com.aihub.admin.config;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ConfigVersionMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.dao.mapper.QuotaMapper;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.aihub.service.config.ConfigChangePublisher;
import com.aihub.service.config.ConfigSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策 D5：快照 {@code version} 的**单调性**。必须真起容器 —— 这里要钉住的是「水位那一行
 * （{@code config_version.id = 1}）真的活在 MySQL 里」这件数据库层面的事实，换成内存替身就只是
 * 「测自己写的 Math.max」。
 *
 * <p><b>修的是 M3 登记的 A2 缺口</b>：M4 之前 {@code version = max(updated_at)}，删掉最新那一行
 * 会让版本**倒退**，而网关的本地/Redis 两侧比对与 {@code lastGood} 用的都是严格 {@code >} ——
 * 于是一份**更旧**的快照会被记住并继续服务，且没有任何收敛信号。水位（{@code config_version}）
 * 只抬不降，删除因此不再让版本回退。
 *
 * <p><b>抬水位的唯一入口是配置写入路径</b>（{@code ConfigChangePublisher.bumpAndPublish}，Task 2）：
 * {@code currentVersion()} 是**纯读**，因为 {@code snapshot()} 是 {@code @Transactional(readOnly = true)}，
 * 而 MySQL + Connector/J 的 {@code readOnlyPropagatesToServer} 默认是开的（会发
 * {@code SET SESSION TRANSACTION READ ONLY}），在只读事务里写库会直接 {@code ERROR 1792} ——
 * 正好打在 {@code GET /internal/config/snapshot} 这条里程碑依赖的路径上。
 *
 * <p><b>用例的判别力锚在「抬水位那一刻发布的 v2」上</b>（review 2026-09-27 Important 1）：
 * 先插行、再抬水位，于是 v2 = 水位 ≥ 那一行的 {@code updated_at}；删行之后仍断言
 * {@code currentVersion() >= v2}，而此刻能解释这个版本的只剩水位那一行（退化成纯
 * {@code max(updated_at)} 的实现在这里回到 0）。旧写法（抬水位在插入之前）把终局断言挂在
 * {@code withRow = max(行, 水位)} 上：行自己的 {@code updated_at} 挡在水位前面，水位的贡献
 * **从未被隔离**，而它的红只来自「删行后三张表恰好是空的」—— state-fragile（否则残留一行更新的
 * {@code updated_at} 就能让那个实现蒙混过关）。**不要把这个顺序改回去。**
 *
 * <p><b>本类只覆盖这一条路径</b>：两个用例都在删除**之前**显式走过 {@code bumpAndPublish}，钉住的是
 * 「水位已被抬起时 raw SQL 删除不回退」。水位**从未被抬起**的纯 SQL 路径（seeder 插行后直接 delete）
 * 仍可能回退一次 —— 那是 D5 登记在案的残余，本类**没有**去钉它。
 *
 * <p>本类不复位水位：**水位持久**正是本任务的意义所在，复位会把用例变成对执行顺序的断言。
 * 清空三张表（{@code @BeforeEach}）对「红」也是必要的 —— 删行之后必须真的没有别的行能解释版本 ——
 * 但它属于执行环境，不是被钉住的性质本身。
 */
class ConfigSnapshotVersionTest extends AbstractIntegrationTest {

    private static final String PROBE_MODEL = "m4-version-probe";

    @Autowired
    private ConfigSnapshotService snapshotService;

    @Autowired
    private ConfigChangePublisher publisher;

    @Autowired
    private ChannelMapper channelMapper;

    @Autowired
    private ModelRouteMapper routeMapper;

    @Autowired
    private RateLimitPolicyMapper policyMapper;

    @Autowired
    private QuotaMapper quotaMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigVersionMapper configVersionMapper;

    /** 容器是 JVM 级共享的：别的用例类可能留下配置行，这里只清行、**不动水位**。 */
    @BeforeEach
    void cleanConfigRows() {
        deleteAllRoutes();
        jdbcTemplate.update("delete from rate_limit_policy");
        jdbcTemplate.update("delete from channel");
    }

    @Test
    void deletingTheNewestRouteRowDoesNotMakeTheVersionGoBackwards() {
        // 顺序是刻意的：**先插行、后抬水位**。抬水位时 now 已在那一行的 updated_at 之后，
        // 因此 v2 是「最新那一行还在场时」发布的版本；反过来（抬水位在插入之前）下面这个
        // withProbeRow 会由行自己的 updated_at 撑着，水位的贡献根本没被隔离（review Important 1）。
        insertRoute(PROBE_MODEL, 1L);
        long withProbeRow = snapshotService.currentVersion();
        assertThat(withProbeRow)
                .as("探针行真的带上了 updated_at —— 「删掉最新那一行」这句话才有对象")
                .isPositive();

        long v2 = publisher.bumpAndPublish("route.create");              // 抬水位只能走**写入**路径（Task 2）

        // 本类**直接**读水位那一行：它是「撑住版本的是水位、不是某一行的 updated_at」的直接证据。
        Long watermark = configVersionMapper.current();
        assertThat(watermark)
                .as("抬水位必须真的写进 config_version 那一行（不是进程内的变量）")
                .isGreaterThanOrEqualTo(v2);

        deleteRoute(PROBE_MODEL);                                        // 删掉「最新」那一行

        assertThat(configVersionMapper.current())
                .as("删行不碰水位那一行：水位只抬不降")
                .isEqualTo(watermark);
        assertThat(snapshotService.currentVersion())
                .as("行已删掉：能撑住版本不低于已发布的 v2 的只剩水位那一行；"
                        + "纯 max(updated_at) 的实现（M4 之前）在这里回到 0，也就是版本倒退")
                .isGreaterThanOrEqualTo(v2);
    }

    @Test
    void theWatermarkSurvivesARecreatedServiceInstance() {
        long v = publisher.bumpAndPublish("channel.update");
        deleteAllRoutes();                                               // 把库里的配置清空

        // Task 13：ConfigSnapshotService 的构造器新增了 QuotaMapper（额度也入快照）。
        ConfigSnapshotService fresh = new ConfigSnapshotService(channelMapper, routeMapper, policyMapper,
                quotaMapper, jdbcTemplate, configVersionMapper, "default-model");
        assertThat(configVersionMapper.current())
                .as("水位那一行（config_version.id = 1）在库里，不在进程里")
                .isGreaterThanOrEqualTo(v);
        // 不要退回 `isGreaterThanOrEqualTo(v)`：清空三张表之后，版本只剩水位那一行可来源，等号才是
        // 诚实的断言 —— `>=` 是 state-fragile 的（库里残留一行更新的 updated_at，一个退化成纯
        // max(updated_at) 的实现就能蒙混过关；review 2026-09-27 Minor）。
        assertThat(fresh.currentVersion())
                .as("三张表已被清空，此刻版本的**唯一**来源就是水位那一行")
                .isEqualTo(v);
    }

    // --- SQL 助手 -------------------------------------------------------

    private void insertRoute(String modelName, Long channelId) {
        // model_route.channel_id 在 V1 里**没有**外键约束，因此这里可以只填一个合成 id。
        jdbcTemplate.update("insert into model_route (model_name, channel_id, weight, priority, status) "
                + "values (?, ?, ?, ?, ?)", modelName, channelId, 100, 0, "ACTIVE");
    }

    private void deleteRoute(String modelName) {
        jdbcTemplate.update("delete from model_route where model_name = ?", modelName);
    }

    private void deleteAllRoutes() {
        jdbcTemplate.update("delete from model_route");
    }
}
