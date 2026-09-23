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
