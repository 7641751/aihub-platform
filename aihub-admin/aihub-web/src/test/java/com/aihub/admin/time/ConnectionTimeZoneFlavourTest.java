package com.aihub.admin.time;

import com.aihub.admin.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住**集成套件自己**跑的连接时区方言。
 *
 * <p>为什么需要这条用例（2026-09-29 独立评审 I-1）：{@code connectionTimeZone} /
 * {@code serverTimezone} 这个参数决定驱动怎么把 {@code datetime(3)} 的墙上时间解释成瞬时。
 * 生产 URL 钉了 {@code serverTimezone=UTC}（{@code application.yml:8} / {@code docker-compose.yml:65}），
 * 而在这条用例出现之前，测试 URL 是 Testcontainers 返回的**裸 URL**（无该参数 ⇒ LOCAL ⇒ 按
 * **JVM 默认时区**解释）—— 两个方言**相反**，却没有任何地方写下来。结果是：那些「时区基准」用例
 * 的红/绿取决于跑测试的 JVM 时区，谁也说不清套件到底在断言哪个环境。
 *
 * <p>现在 {@link AbstractIntegrationTest} 把方言显式钉成生产方（UTC），本类负责让它**可观测**：
 * <ol>
 *   <li><b>字符串级</b>：{@code spring.datasource.url} 必须带 {@code serverTimezone=UTC}。
 *       这条在**任何** JVM 时区下都成立、也能抓到有人把参数删掉。</li>
 *   <li><b>行为级</b>：驱动真的按 UTC 解释 {@code datetime}（{@code Timestamp} 载体的读数 = 该墙上时间的
 *       UTC 折）。这条在非 UTC 的 JVM 上才与方言 LOCAL 可区分 —— JVM 自己就是 UTC 时两种方言行为完全一致，
 *       见 {@link #suiteRunsOnTheProductionUtcConnectionFlavourNotOnTheJvmZoneDefault()} 的注释。</li>
 *   <li><b>环境级</b>：数据库会话时钟就是 UTC（{@code now(3)} 与 {@code utc_timestamp(3)} 一致）——
 *       这是 V1 三张配置表的 {@code DEFAULT CURRENT_TIMESTAMP(3)} 写 UTC 墙上时间的**唯一**依据，
 *       而应用侧没有任何东西钉它（登记为独立评审 I-3 的残余风险）。</li>
 * </ol>
 */
class ConnectionTimeZoneFlavourTest extends AbstractIntegrationTest {

    /** 一个固定字面量的 UTC 墙上时间：{@code 2026-01-01 12:00:00.123}。 */
    private static final LocalDateTime FIXED_WALL_TIME = LocalDateTime.parse("2026-01-01T12:00:00.123");

    /** 两个时钟读数的间隔上界（毫秒）：同一台容器上的两次 SELECT，实测是毫秒级。 */
    private static final long CLOCK_TOLERANCE_MILLIS = 2_000L;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void suiteRunsOnTheProductionUtcConnectionFlavourNotOnTheJvmZoneDefault() {
        assertThat(datasourceUrl)
                .as("集成套件必须跑在**生产方言**上：URL 带 serverTimezone=UTC。"
                        + "这条断言是字符串级的，因此在任何 JVM 时区下都能抓到「有人把参数删回裸 URL」——"
                        + "而下面的行为探针做不到：JVM 默认时区本身就是 UTC 时，LOCAL 与 UTC 两种方言"
                        + "在行为上完全一致、不可区分（这正是评审 I-1(b) 指出的 CI 盲区）")
                .containsIgnoringCase("serverTimezone=UTC")
                .doesNotContainIgnoringCase("connectionTimeZone=LOCAL");

        Instant viaTimestampCarrier = jdbcTemplate.queryForObject(
                "select cast('2026-01-01 12:00:00.123' as datetime(3))", Timestamp.class).toInstant();
        LocalDateTime viaLocalDateTimeCarrier = jdbcTemplate.queryForObject(
                "select cast('2026-01-01 12:00:00.123' as datetime(3))", LocalDateTime.class);

        assertThat(viaTimestampCarrier)
                .as("驱动必须按 UTC 解释这一格的墙上时间（当前 JVM 默认时区 = %s）："
                                + "Timestamp 载体的读数是 %s，UTC 折是 %s",
                        ZoneId.systemDefault(), viaTimestampCarrier,
                        FIXED_WALL_TIME.toInstant(ZoneOffset.UTC))
                .isEqualTo(FIXED_WALL_TIME.toInstant(ZoneOffset.UTC));
        assertThat(viaLocalDateTimeCarrier)
                .as("LocalDateTime 载体与时区无关（这是修复所依赖的驱动性质）：原样 %s", FIXED_WALL_TIME)
                .isEqualTo(FIXED_WALL_TIME);
    }

    @Test
    void databaseSessionClockIsUtcSoTheConfigTablesCurrentTimestampDefaultsAreUtcWallTime() {
        LocalDateTime dbUtcNow = jdbcTemplate.queryForObject("select utc_timestamp(3)", LocalDateTime.class);
        LocalDateTime dbSessionNow = jdbcTemplate.queryForObject("select now(3)", LocalDateTime.class);
        Map<String, Object> zones = jdbcTemplate.queryForMap(
                "select @@session.time_zone as sessionZone, @@global.time_zone as globalZone, "
                        + "@@system_time_zone as systemZone");

        long sessionOffsetMillis = Duration.between(dbUtcNow, dbSessionNow).toMillis();

        assertThat(Math.abs(sessionOffsetMillis))
                .as("V1 的配置表用 DEFAULT CURRENT_TIMESTAMP(3) / ON UPDATE CURRENT_TIMESTAMP(3) 生成时间列，"
                                + "那写的是**数据库会话时区**的墙上时间；应用侧把它当 UTC 读"
                                + "（ConfigSnapshotService.maxUpdatedAt）。因此这里断言：会话时钟就是 UTC。"
                                + "UTC_TIMESTAMP(3)=%s、NOW(3)=%s、@@session.time_zone=%s、@@global.time_zone=%s、"
                                + "@@system_time_zone=%s ⇒ 偏移 %d ms。"
                                + "若这条变红，说明「列里是 UTC」这个前提不再成立，"
                                + "ConfigSnapshotServiceTest 的列基准断言会红在正确的地方",
                        dbUtcNow, dbSessionNow, zones.get("sessionZone"), zones.get("globalZone"),
                        zones.get("systemZone"), sessionOffsetMillis)
                .isLessThanOrEqualTo(CLOCK_TOLERANCE_MILLIS);
    }
}
