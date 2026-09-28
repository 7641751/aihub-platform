package com.aihub.admin.config;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ConfigVersionMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.aihub.service.config.ConfigChangePublisher;
import com.aihub.service.config.ConfigSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策 D5：快照 {@code version} 的**单调性**。必须真起容器 —— 这里要钉住的是「水位真的活在
 * MySQL 里」这件数据库层面的事实，换成内存替身就只是「测自己写的 Math.max」。
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
 * <p><b>残余（D5 已登记）</b>：手工 SQL 的写入/删除不走控制台，就不会抬水位，因此一条 raw SQL 的删除
 * 仍可能让版本回退一次（本类的用例都显式走 {@code bumpAndPublish}，正是为了不落进这个边界）。
 *
 * <p>本类不复位水位：**水位持久**正是本任务的意义所在，复位会把这个用例变成对执行顺序的断言。
 * 只清配置行（三张表），保证「删掉最新那一行」这句话里剩下的是空集。
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
        long before = snapshotService.currentVersion();
        long rowVersion = publisher.bumpAndPublish("route.create");      // 抬水位只能走**写入**路径（Task 2）
        assertThat(rowVersion).isGreaterThan(before);

        insertRoute(PROBE_MODEL, 1L);
        long withRow = snapshotService.currentVersion();
        assertThat(withRow).isGreaterThanOrEqualTo(rowVersion);

        deleteRoute(PROBE_MODEL);                                        // 删掉「最新」那一行
        assertThat(snapshotService.currentVersion())
                .as("M4 之前：version = max(updated_at)，删掉最新行会让版本倒退，"
                        + "而网关两侧的比对都是严格 >，于是更旧的快照会被 lastGood 记住并继续服务")
                .isGreaterThanOrEqualTo(withRow);
    }

    @Test
    void theWatermarkSurvivesARecreatedServiceInstance() {
        long v = publisher.bumpAndPublish("channel.update");
        deleteAllRoutes();                                               // 把库里的配置清空

        ConfigSnapshotService fresh = new ConfigSnapshotService(channelMapper, routeMapper, policyMapper,
                jdbcTemplate, configVersionMapper, "default-model");
        assertThat(fresh.currentVersion()).as("水位在库里，不在进程里").isGreaterThanOrEqualTo(v);
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
