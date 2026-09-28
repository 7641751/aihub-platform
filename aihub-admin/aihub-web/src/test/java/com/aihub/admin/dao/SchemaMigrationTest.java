package com.aihub.admin.dao;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.mapper.AuditLogMapper;
import com.aihub.dao.mapper.ConfigVersionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private ConfigVersionMapper configVersionMapper;

    @Test
    void flywayAppliesExactlyOneMigration() {
        // D1：M4 有意引入第二条迁移（审计表 / request_log 索引 / config_version）。
        // 断言语义从「恰好 1 条」升级为「恰好这 2 条」：护栏要保的是「没人能悄悄加迁移」，
        // 而不是「永远只有 1 条」—— 现在任何人再加迁移都必须**显式**改这里。
        List<Map<String, Object>> applied = jdbcTemplate.queryForList(
                "SELECT version, description FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");

        assertThat(applied).hasSize(2);
        assertThat(applied).extracting(r -> String.valueOf(r.get("version"))).containsExactly("1", "2");
        assertThat(applied).extracting(r -> String.valueOf(r.get("description")))
                .containsExactly("init schema", "m4 console");
    }

    @Test
    void allTwelveTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = database() and table_name <> 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "tenant", "sys_user", "api_key", "channel", "model_route", "quota",
                "rate_limit_policy", "request_log", "kb_document", "billing_daily",
                // V2 新增（决策 D1）：审计表与水位表。
                "audit_log", "config_version");
    }

    @Test
    void v2CreatesTheAuditTableAndTheTwoRequestLogIndexes() {
        // 审计表可写可读（顺带证明列名与实体映射一致）
        AuditLogEntity row = new AuditLogEntity();
        row.setActorType("SYSTEM"); row.setActor("system");
        row.setAction("MIGRATION_TEST"); row.setTargetType("CHANNEL");
        auditLogMapper.insert(row);
        assertThat(auditLogMapper.selectById(row.getId()).getAction()).isEqualTo("MIGRATION_TEST");

        // 两个索引真实存在（查 information_schema 而不是读 SQL 文件）
        assertThat(indexNamesOf("request_log"))
                .contains("idx_request_log_channel", "idx_request_log_api_key");
    }

    @Test
    void configVersionRowExistsAndTheUpsertOnlyEverRaisesTheValue() {
        // V2 已经插入了 (id=1, version=0)，所以下面两次走的都是 **UPDATE** 路径。
        // **不要断言 affected rows**：MySQL 的 ON DUPLICATE KEY UPDATE 在「更新成相同值」时返回 0，
        // 而 Connector/J 默认 useAffectedRows=false（即设了 CLIENT_FOUND_ROWS），此时返回 1；
        // 换句话说同一个实现可能给出 0、1 或 2，断言返回值就是在猜驱动。断言**值**的语义。
        // ⚠️ 水位行是**共享容器里的一行**，而 Testcontainers 是 JVM 级单例、`AbstractIntegrationTest`
        // 没有全局清理：Task 2 的 ConfigChangePublisherTest 与 Task 3 都会把它抬到 ~1.76e12。
        // 所以这里**先显式归零**再断言绝对值，否则这条用例在 Task 17 的全量 `mvn clean test` 里
        // 会因为"谁先跑"而红（N11：与 ConfigSnapshotServiceTest 同一类隐患，那里已经加了 @BeforeEach）。
        jdbcTemplate.update("UPDATE config_version SET version = 0 WHERE id = 1");
        assertThat(configVersionMapper.current()).isZero();
        configVersionMapper.raiseTo(1_700_000_000_000L);
        assertThat(configVersionMapper.current()).isEqualTo(1_700_000_000_000L);
        configVersionMapper.raiseTo(1_600_000_000_000L);                 // 更小的值：不改
        assertThat(configVersionMapper.current()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void requestLogIsPartitionedByCreatedAt() {
        Integer partitions = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.partitions "
                        + "where table_schema = database() and table_name = 'request_log' "
                        + "and partition_name is not null",
                Integer.class);

        assertThat(partitions).isGreaterThan(1);

        // 分区方案本身也要被钉住：只数分区数量的话，PARTITION BY HASH(id) 同样能通过。
        List<String> methods = jdbcTemplate.queryForList(
                "select distinct partition_method from information_schema.partitions "
                        + "where table_schema = database() and table_name = 'request_log' "
                        + "and partition_name is not null",
                String.class);

        assertThat(methods).containsExactly("RANGE COLUMNS");

        List<String> expressions = jdbcTemplate.queryForList(
                "select distinct partition_expression from information_schema.partitions "
                        + "where table_schema = database() and table_name = 'request_log' "
                        + "and partition_name is not null",
                String.class);

        assertThat(expressions).hasSize(1);
        assertThat(expressions.get(0)).containsIgnoringCase("created_at");

        List<String> names = jdbcTemplate.queryForList(
                "select partition_name from information_schema.partitions "
                        + "where table_schema = database() and table_name = 'request_log' "
                        + "and partition_name is not null "
                        + "order by partition_ordinal_position",
                String.class);

        // 不再断言「恰好这四个」：M2 的分区维护器会在启动/定时补建未来月份（实测会真的加），
        // 精确相等会在 12 月一到就变红。这里钉住的是**结构契约**：
        // V1 的三个历史分区是前缀、pmax 是最后一个、中间只允许 pYYYYMM。
        assertThat(names.subList(0, 3)).containsExactly("p202609", "p202610", "p202611");
        assertThat(names.get(names.size() - 1)).isEqualTo("pmax");
        assertThat(names.subList(3, names.size() - 1)).allSatisfy(name -> assertThat(name).matches("p\\d{6}"));
        // 必须覆盖「今天」：最后一个有界分区的上界要晚于今天，否则行会落进 pmax（M2 的陷阱）。
        String lastBounded = names.get(names.size() - 2);
        assertThat(lastBounded).matches("p\\d{6}");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.partitions "
                        + "where table_schema = database() and table_name = 'request_log' "
                        + "and partition_name <> 'pmax' and partition_name is not null "
                        + "and str_to_date(replace(partition_description, '''', ''), '%Y-%m-%d') > curdate()",
                Integer.class)).isGreaterThan(0);
    }

    @Test
    void requestLogRejectsDuplicateRequestIdWithinSameCreatedAt() {
        insertRequestLog("req-dup-1");

        assertThatThrownBy(() -> insertRequestLog("req-dup-1"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private void insertRequestLog(String requestId) {
        jdbcTemplate.update(
                "insert into request_log (request_id, tenant_id, status, created_at) values (?, ?, ?, ?)",
                requestId, 1L, "SUCCESS", Timestamp.from(Instant.parse("2026-09-23T10:00:00Z")));
    }

    /** 只属于本测试类的辅助方法：索引存在性必须查 information_schema，不能靠读 SQL 文件推断。 */
    private List<String> indexNamesOf(String table) {
        return jdbcTemplate.queryForList(
                "SELECT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class, table);
    }
}
