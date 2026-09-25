package com.aihub.admin.dao;

import com.aihub.admin.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesExactlyOneMigration() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = 1", Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void allTenTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = database() and table_name <> 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "tenant", "sys_user", "api_key", "channel", "model_route", "quota",
                "rate_limit_policy", "request_log", "kb_document", "billing_daily");
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
}
