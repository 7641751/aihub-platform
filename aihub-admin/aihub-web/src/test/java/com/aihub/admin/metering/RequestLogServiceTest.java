package com.aihub.admin.metering;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.service.metering.RequestLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 落库与幂等。这里用的是真 MySQL（Testcontainers），因为要钉住的正是**唯一键**
 * {@code (request_id, created_at)} 与分区表的行为 —— 用内存库测等于没测。
 */
class RequestLogServiceTest extends AbstractIntegrationTest {

    private static final long CREATED_AT_MILLIS = 1_800_000_000_123L;   // 2027-01-15T08:00:00.123Z

    @Autowired
    private RequestLogService requestLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static MeteringEvent event(String requestId, long createdAtMillis) {
        return new MeteringEvent(requestId, 7L, null, null, "deepseek-chat",
                12, 34, 46, 1200, 250, MeteringEvent.STATUS_SUCCESS, null, createdAtMillis);
    }

    /** 同上，但显式给 {@code tenant_id}：决策 9 要钉住网关的 {@code 0} 哨兵原样落库。 */
    private static MeteringEvent event(String requestId, long tenantId, long createdAtMillis) {
        return new MeteringEvent(requestId, tenantId, null, null, "deepseek-chat",
                12, 34, 46, 1200, 250, MeteringEvent.STATUS_SUCCESS, null, createdAtMillis);
    }

    /**
     * 同上，但把 Task 11 起真的有值的两列（{@code api_key_id} / {@code channel_id}）显式填上。
     * 它们对应网关侧的「鉴权视图的数值主键」与「实际服务的那条渠道」。
     */
    private static MeteringEvent event(String requestId, Long apiKeyId, Long channelId, long createdAtMillis) {
        return new MeteringEvent(requestId, 7L, apiKeyId, channelId, "deepseek-chat",
                12, 34, 46, 1200, 250, MeteringEvent.STATUS_SUCCESS, null, createdAtMillis);
    }

    private Map<String, Object> row(String requestId) {
        return jdbcTemplate.queryForMap(
                "select tenant_id, api_key_id, channel_id, model, prompt_tokens, completion_tokens,"
                        + " total_tokens, latency_ms, ttft_ms, status, error_code, created_at"
                        + " from request_log where request_id = ?",
                requestId);
    }

    @Test
    void persistsEveryColumnWithUtcCreatedAt() {
        String requestId = "req-m2-persist";

        assertThat(requestLogService.persist(event(requestId, CREATED_AT_MILLIS))).isTrue();

        Map<String, Object> row = row(requestId);
        assertThat(row.get("tenant_id")).isEqualTo(7L);
        // 这个事件里这两个字段是 null（鉴权关闭 / 没有视图的哨兵形态），因此列必须落 NULL ——
        // 不许被数据库默认值或「省略 null 列」的策略悄悄填成别的东西。
        // containsKey 不能省：queryForMap 少了列时 get() 同样返回 null，只断言 isNull() 会被漏列骗过。
        assertThat(row).containsKey("api_key_id");
        assertThat(row).containsKey("channel_id");
        assertThat(row.get("api_key_id")).isNull();
        assertThat(row.get("channel_id")).isNull();
        assertThat(row.get("model")).isEqualTo("deepseek-chat");
        assertThat(row.get("prompt_tokens")).isEqualTo(12);
        assertThat(row.get("completion_tokens")).isEqualTo(34);
        assertThat(row.get("total_tokens")).isEqualTo(46);
        assertThat(row.get("latency_ms")).isEqualTo(1200);
        assertThat(row.get("ttft_ms")).isEqualTo(250);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("error_code")).isNull();
        // UTC 墙上时间：必须等于事件里的 epoch 毫秒按 UTC 换算出的值，断言里不出现 JVM 默认时区。
        //
        // 期望值刻意用 LocalDateTime 而不是 Timestamp：本项目的 mysql-connector-j 9.7.0 在
        // queryForMap 里对 DATETIME(3) 返回的是 LocalDateTime（实测，见 task-8-report.md），
        // 用 Timestamp 断言会在**类型**上失配（值其实是对的），从而让这条用例永远红。
        // 同样的值、同样的含义，只是用驱动真正返回的类型来比。
        LocalDateTime expected = LocalDateTime.ofInstant(Instant.ofEpochMilli(CREATED_AT_MILLIS), ZoneOffset.UTC);
        assertThat(expected).isEqualTo(LocalDateTime.parse("2027-01-15T08:00:00.123"));
        assertThat(row.get("created_at")).isEqualTo(expected);
    }

    /**
     * Task 11 把 M2 决策 8 的缺口（{@code api_key_id} / {@code channel_id} 恒为 NULL）闭合掉了：
     * 事件里带了值，落库这一侧必须**原样写进列**，而不是被插入策略（MyBatis-Plus 默认省略 null 字段）
     * 或某个「只 set 一次」的实体复用悄悄丢掉。
     *
     * <p>判别力：删掉 {@code RequestLogService} 里的 {@code entity.setApiKeyId(...)} /
     * {@code setChannelId(...)} 任一行，本用例立刻红 —— 而既有用例全部用的是 null 事件，
     * 一行都不会红（这正是本用例存在的理由：它把「列有值」这条链路钉在库里）。
     *
     * <p>用的两个值刻意不同且都不是 0：0 与 NULL 在失败输出里不好区分，
     * 而 {@code api_key_id} 没有任何哨兵语义（可空），因此不能被 {@code tenant_id} 的 0 哨兵惯例带偏。
     */
    @Test
    void persistsTheNumericApiKeyIdAndChannelIdFromTheEvent() {
        String requestId = "req-m3-api-key-and-channel";

        assertThat(requestLogService.persist(event(requestId, 42L, 13L, CREATED_AT_MILLIS))).isTrue();

        Map<String, Object> row = row(requestId);
        assertThat(row).containsKey("api_key_id");
        assertThat(row).containsKey("channel_id");
        assertThat(row.get("api_key_id")).isEqualTo(42L);
        assertThat(row.get("channel_id")).isEqualTo(13L);
    }

    /**
     * 决策 9：{@code tenant_id} 取**事件**里的值，网关在没有认证视图时发 {@code 0} 哨兵。
     * 真值 {@code 0} 必须原样落库 —— 不能被丢弃、也不能被写成 NULL。
     *
     * <p>判别力：把 {@code RequestLogService} 改成
     * {@code entity.setTenantId(event.tenantId() == 0L ? null : event.tenantId())}（或把 MyBatis-Plus
     * 的 null 字段插入策略换成「null 即省略列」）本用例立刻红：{@code tenant_id} 是
     * {@code BIGINT NOT NULL} 且无默认值，插入会直接失败。
     */
    @Test
    void tenantZeroSentinelFromEventIsPersistedAsZeroNotNull() {
        String requestId = "req-m2-tenant-zero";

        assertThat(requestLogService.persist(event(requestId, 0L, CREATED_AT_MILLIS))).isTrue();

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from request_log where request_id = ?", Integer.class, requestId);
        assertThat(rows).isEqualTo(1);

        Map<String, Object> row = row(requestId);
        assertThat(row.get("tenant_id")).isNotNull();
        assertThat(row.get("tenant_id")).isEqualTo(0L);
    }

    /**
     * **幂等**：同一事件（同 request_id + 同 created_at）落两次只能有一行，第二次返回 false。
     * 反证：把 {@code RequestLogService} 里的 createdAt 换成 {@code LocalDateTime.now(UTC)}
     * 再跑本用例 → 变成两行 / 第二次返回 true，用例红（实测过 MySQL 的行为）。
     */
    @Test
    void duplicateEventIsIgnoredInsteadOfInsertingASecondRow() {
        String requestId = "req-m2-idempotent";

        assertThat(requestLogService.persist(event(requestId, CREATED_AT_MILLIS))).isTrue();
        assertThat(requestLogService.persist(event(requestId, CREATED_AT_MILLIS))).isFalse();

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from request_log where request_id = ?", Integer.class, requestId);
        assertThat(rows).isEqualTo(1);
    }

    /**
     * 把「幂等键的另一半也必须稳定」写成文档：**同一 request_id 但 created_at 变了 → 第二行**。
     * 这正是「消费端不许用 now()」的原因（计划「决策登记」第 2 条），也是 M4 对账要小心的地方。
     */
    @Test
    void sameRequestIdWithDifferentCreatedAtIsNotTreatedAsDuplicate() {
        String requestId = "req-m2-different-created-at";

        assertThat(requestLogService.persist(event(requestId, CREATED_AT_MILLIS))).isTrue();
        assertThat(requestLogService.persist(event(requestId, CREATED_AT_MILLIS + 1))).isTrue();

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from request_log where request_id = ?", Integer.class, requestId);
        assertThat(rows).isEqualTo(2);
    }

    /**
     * 验收标准第 4 条：**只有**「重复消费」允许被吞掉。非唯一键的数据库异常（这里是
     * {@code status} 为 NULL 撞上 {@code NOT NULL} 约束）必须往外抛，让消息重试/进死信。
     *
     * <p>判别力：把 {@code RequestLogService} 的 {@code catch} 放宽成
     * {@code catch (DataAccessException e) { return false; }}
     * （或退化成 {@code catch (Exception e) }），本用例立刻变红 —— 那正是「静默丢数据」的实现。
     */
    @Test
    void nonDuplicateDatabaseErrorPropagatesInsteadOfBeingSwallowed() {
        MeteringEvent invalid = new MeteringEvent("req-m2-bad-row", 7L, null, null, "deepseek-chat",
                12, 34, 46, 1200, 250, null, null, CREATED_AT_MILLIS);

        assertThatThrownBy(() -> requestLogService.persist(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from request_log where request_id = ?", Integer.class, "req-m2-bad-row");
        assertThat(rows).isZero();
    }

    /** 未被使用的常量保留给「事件时间必须是 UTC」的断言可读性。 */
    @SuppressWarnings("unused")
    private static final Instant SANITY = Instant.ofEpochMilli(CREATED_AT_MILLIS);
}
