package com.aihub.admin.time;

import com.aihub.admin.support.TestContainers;
import com.aihub.service.apikey.ApiKeyService;
import com.aihub.service.config.ConfigSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判别器：**故意把连接时区钉成一个固定的非 UTC 区**，跑同一条生产读写路径，证明时间基准与连接时区无关。
 *
 * <p>背景（2026-09-29 独立评审 I-1）：生产 URL 钉了 {@code serverTimezone=UTC}，而在那个方言下
 * 「把 {@code datetime} 读成 {@code LocalDateTime} + 显式 UTC 折」与「读成 {@code Timestamp} + {@code toInstant()}」
 * **逐位相等**（实测 {@code old − new = 0}）—— 只要连接时区是 UTC，修复前后就没有可观测差异。
 * 旧代码的错误只在一个条件下出现：**连接时区不是 UTC**。而
 * {@link com.aihub.admin.support.AbstractIntegrationTest} 现在跑生产方言（UTC），
 * 因此判别力必须由本类提供：它用 {@link TestContainers#nonUtcFlavouredJdbcUrl()}
 * （{@code connectionTimeZone=Asia/Shanghai}，固定区）起一个独立上下文，让
 * {@code ConfigSnapshotService.currentVersion()} 与 {@code ApiKeyService} 的实体↔列换算
 * **真的落在一个非 UTC 的连接时区上**。
 *
 * <p><b>为什么钉固定区而不是裸 URL（LOCAL）</b>：LOCAL 的行为取决于跑测试的 JVM 默认时区 ——
 * 在一台 UTC 的机器或 CI 镜像上，LOCAL 与 UTC 行为完全一致，本类会**悄悄失去判别力**
 * （这正是评审 I-1(b) 指出的盲区）。固定区让判别在任何 JVM 时区下都成立；
 * 本类的 {@link #theConnectionZoneIsDeliberatelyNonUtcSoTheDiscriminationDoesNotDependOnTheHostJvm()}
 * 同时把「历史方言 LOCAL 确实随 JVM 时区变化」这个事实量出来并留在证据里。
 *
 * <p>本类能判别什么：把 {@code ConfigSnapshotService.maxUpdatedAt} 换回 {@code Timestamp}、
 * 或把 {@code ApiKeyEntity.expireAt} 换回裸 {@code Instant}，两条判别用例都会红 —— **任何 JVM 时区下**。
 * 它不能判别什么：列的**基准**是否正确（会话时区不是 UTC 时这是另一件事），那一半由
 * {@code ConfigSnapshotServiceTest} 的**数据库自身时钟**断言承担。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TimeBasisIsConnectionFlavourIndependentTest {

    private static final String ACTIVE = "ACTIVE";
    private static final String CHANNEL_NAME = "flavour-independent-channel";
    private static final String TENANT_NAME = "flavour-independent-tenant";
    private static final String KEY_NAME = "flavour-independent-key";

    /** 该字面量的 UTC 墙上时间就是它自己：{@code 2026-01-01 12:00:00.123}。 */
    private static final LocalDateTime FIXED_WALL_TIME = LocalDateTime.parse("2026-01-01T12:00:00.123");

    /** 本类故意使用的非 UTC 连接时区。 */
    private static final ZoneId PINNED_CONNECTION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final long BASIS_TOLERANCE_MILLIS = 1L;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // 与 AbstractIntegrationTest 的唯一差别：连接时区是**故意的非 UTC 固定区**。
        registry.add("spring.datasource.url", TestContainers::nonUtcFlavouredJdbcUrl);
        TestContainers.registerInfrastructure(registry);
    }

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Autowired
    private ConfigSnapshotService service;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String mintedKeyId;

    @AfterEach
    void cleanUp() {
        if (mintedKeyId != null) {
            jdbcTemplate.update("delete from api_key where key_id = ?", mintedKeyId);
            mintedKeyId = null;
        }
        jdbcTemplate.update("delete from tenant where name = ?", TENANT_NAME);
        jdbcTemplate.update("delete from channel where name = ?", CHANNEL_NAME);
    }

    /**
     * 先钉住「本上下文真的跑在一个非 UTC 的连接时区上」，否则下面两条用例可能只是在一个 UTC 方言上
     * 重复了主套件的断言。
     *
     * <p>这里刻意分成两半：
     * <ol>
     *   <li>本上下文的方言（{@code connectionTimeZone=Asia/Shanghai}）—— 固定在 +08:00，
     *       **不随 JVM 默认时区变化**，因此这个判别在 UTC 的 CI 镜像上同样成立；</li>
     *   <li>历史方言（裸 URL ⇒ LOCAL）—— 它按 **JVM 默认时区**解释，把它量出来是为了留下
     *       「套件在评审之前跑的是哪个方言」的证据，同时说明它为什么不能当判别器。</li>
     * </ol>
     */
    @Test
    void theConnectionZoneIsDeliberatelyNonUtcSoTheDiscriminationDoesNotDependOnTheHostJvm() throws Exception {
        assertThat(datasourceUrl)
                .as("本上下文必须显式钉一个非 UTC 的连接时区（而不是靠 JVM 时区）")
                .containsIgnoringCase("connectionTimeZone=Asia/Shanghai")
                .doesNotContainIgnoringCase("serverTimezone=UTC");

        Instant viaTimestampCarrier = jdbcTemplate.queryForObject(
                "select cast('2026-01-01 12:00:00.123' as datetime(3))", Timestamp.class).toInstant();
        LocalDateTime viaLocalDateTimeCarrier = jdbcTemplate.queryForObject(
                "select cast('2026-01-01 12:00:00.123' as datetime(3))", LocalDateTime.class);

        assertThat(viaLocalDateTimeCarrier)
                .as("LocalDateTime 载体与时区无关（这是修复所依赖的驱动性质）：原样 %s", FIXED_WALL_TIME)
                .isEqualTo(FIXED_WALL_TIME);
        assertThat(viaTimestampCarrier)
                .as("连接时区被钉成 %s，因此 Timestamp 载体把这一格的墙上时间解释成 %s；"
                                + "UTC 折是 %s —— 两者相差固定 8 小时，与跑测试的 JVM 时区（%s）无关",
                        PINNED_CONNECTION_ZONE, FIXED_WALL_TIME.atZone(PINNED_CONNECTION_ZONE).toInstant(),
                        FIXED_WALL_TIME.toInstant(ZoneOffset.UTC), ZoneId.systemDefault())
                .isEqualTo(FIXED_WALL_TIME.atZone(PINNED_CONNECTION_ZONE).toInstant())
                .isNotEqualTo(FIXED_WALL_TIME.toInstant(ZoneOffset.UTC));

        // 历史方言：裸 URL ⇒ LOCAL ⇒ 按 JVM 默认时区解释。这里只作为**记录**（评审之前套件跑的就是它）：
        // JVM 自己是 UTC 时它与 UTC 折完全一致 —— 所以它不能用来判别修复。
        try (Connection rawLocal = DriverManager.getConnection(
                TestContainers.localFlavouredJdbcUrl(), TestContainers.MYSQL.getUsername(),
                TestContainers.MYSQL.getPassword());
             Statement statement = rawLocal.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select cast('2026-01-01 12:00:00.123' as datetime(3))")) {
            assertThat(rows.next()).isTrue();
            Instant viaLocalFlavour = rows.getTimestamp(1).toInstant();
            assertThat(viaLocalFlavour)
                    .as("裸 URL（LOCAL 方言）必须按 **JVM 默认时区**（%s）解释这一格 —— 这就是评审之前"
                                    + "测试套件实际在跑的方言，它的行为随机器变化（JVM=UTC 时与 UTC 折相同，"
                                    + "因此当不了判别器）", ZoneId.systemDefault())
                    .isEqualTo(FIXED_WALL_TIME.atZone(ZoneId.systemDefault()).toInstant());
        }
    }

    /**
     * Fix 1 的判别器（非 UTC 连接时区版）：水位来自裸 SQL 写入的 {@code channel.updated_at}，
     * {@code currentVersion()} 必须把它当 **UTC** 墙上时间读。
     *
     * <p>若实现改回 {@code Timestamp} 载体，驱动会按连接时区（本上下文 = +08:00）折这一格 ⇒ 差
     * {@code −8 h}，本条红 —— 且与 JVM 默认时区无关。
     */
    @Test
    void configVersionBasisIsUtcEvenOnANonUtcFlavouredConnection() {
        jdbcTemplate.update("UPDATE config_version SET version = 0 WHERE id = 1");
        assertThat(jdbcTemplate.queryForObject(
                "select version from config_version where id = 1", Long.class))
                .as("前置条件：水位必须是 0，否则测的是水位而不是 max(updated_at)")
                .isZero();

        jdbcTemplate.update("insert into channel (name, provider, base_url, api_key_cipher, status) "
                        + "values (?, ?, ?, ?, ?)",
                CHANNEL_NAME, "test", "https://" + CHANNEL_NAME + ".example.com",
                "v1:synthetic-not-a-real-cipher", ACTIVE);

        LocalDateTime stored = jdbcTemplate.queryForObject(
                "select updated_at from channel where name = ?", LocalDateTime.class, CHANNEL_NAME);
        assertThat(stored).as("这一行的 updated_at 必须真的落库（不是 null）").isNotNull();

        long expected = stored.toInstant(ZoneOffset.UTC).toEpochMilli();
        long skewedIfTheConnectionZoneIsApplied =
                stored.atZone(PINNED_CONNECTION_ZONE).toInstant().toEpochMilli();
        long actual = service.currentVersion();

        assertThat(Math.abs(actual - expected))
                .as("非 UTC 连接时区下 currentVersion() 仍必须把 updated_at 当 UTC 墙上时间解释："
                                + "库里存的是 %s，UTC 折 %d ms，实际 %d ms（差 %d ms）；"
                                + "连接时区 = %s，按它折会得到 %d ms（差 %d ms）—— 后者正是被修掉的读法",
                        stored, expected, actual, actual - expected, PINNED_CONNECTION_ZONE,
                        skewedIfTheConnectionZoneIsApplied, expected - skewedIfTheConnectionZoneIsApplied)
                .isLessThanOrEqualTo(BASIS_TOLERANCE_MILLIS);
    }

    /**
     * Fix 2 的判别器（非 UTC 连接时区版）：{@code api_key.expire_at} 的**原始列**必须与兄弟列同基准
     * （UTC 墙上时间）。
     *
     * <p>读的是列而不是实体：实体往返在两种基准下都自洽（写入与读回走同一次连接时区换算、互相抵消），
     * 因此只有裸列能判别。若实现改回裸 {@code Instant}，连接时区（+08:00）下驱动会把这一格写成
     * 本地墙上时间 ⇒ {@code +8 h}，本条红 —— 且与 JVM 默认时区无关。
     */
    @Test
    void apiKeyExpiryBasisIsUtcEvenOnANonUtcFlavouredConnection() {
        Instant expireAt = Instant.now().plus(Duration.ofDays(365));

        ApiKeyService.IssuedKey issued = apiKeyService.mint(TENANT_NAME, KEY_NAME, expireAt);
        mintedKeyId = issued.keyId();

        LocalDateTime stored = jdbcTemplate.queryForObject(
                "select expire_at from api_key where key_id = ?", LocalDateTime.class, issued.keyId());
        assertThat(stored).as("expire_at 必须真的落库（不是 null、也不是被截断成零值）").isNotNull();

        long storedAsUtcMillis = stored.toInstant(ZoneOffset.UTC).toEpochMilli();
        long expectedMillis = expireAt.toEpochMilli();
        long skewedIfTheConnectionZoneIsApplied =
                stored.atZone(PINNED_CONNECTION_ZONE).toInstant().toEpochMilli();

        assertThat(Math.abs(storedAsUtcMillis - expectedMillis))
                .as("非 UTC 连接时区下 api_key.expire_at 仍必须与 created_at/updated_at 同基准"
                                + "（UTC 墙上时间）：库里存的是 %s，UTC 折 %d ms，铸 key 给的瞬时 %d ms"
                                + "（差 %d ms）；按连接时区（%s）折才是 %d ms —— 后者正是被修掉的写法",
                        stored, storedAsUtcMillis, expectedMillis, storedAsUtcMillis - expectedMillis,
                        PINNED_CONNECTION_ZONE, skewedIfTheConnectionZoneIsApplied)
                .isLessThanOrEqualTo(BASIS_TOLERANCE_MILLIS);
    }
}
