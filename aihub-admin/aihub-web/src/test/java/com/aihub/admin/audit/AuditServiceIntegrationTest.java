package com.aihub.admin.audit;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.mapper.AuditLogMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 7 的验收：{@code audit_log} 的写入**发生在调用方的事务里**（决策 D8），
 * 且审计内容不含敏感字段、{@code created_at} 按 UTC 墙钟落库。
 *
 * <p>本轮的用例分为四组：
 * <ol>
 *   <li><b>同事务</b>（{@link #recordWritesAnAuditRowInTheSameTransactionAsTheBusinessWrite}）：
 *       业务回滚时审计行必须一起消失。做法是让**本类自己的**事务探针
 *       （{@link TransactionalProbe}，一个真实的 Spring bean，因此 {@code @Transactional} 的代理语义成立）
 *       先写一条审计、再抛异常；回滚之后该探针的审计行必须**没有留下**。
 *       ⚠️ 这条用例方法**本身不能**标 {@code @Transactional}：那样测试自己的事务会成为边界，
 *       探针的回滚在同一事务里被测试吞掉，断言就恒绿、什么也证明不了。
 *       <br>⚠️ 它还带一个**正向对照**：同一个探针在不抛异常时必须真的落下**恰好一行** ——
 *       否则「record 什么都不写」的实现会让回滚断言恒绿（<i>独立评审 I3</i>）。</li>
 *   <li><b>审计失败 = 业务失败</b>：{@code record} 不允许吞异常。用一个 Jackson 序列化不了的值
 *       把受检 {@code JsonProcessingException} 逼出来，断言它变成 {@code IllegalStateException}。</li>
 *   <li><b>无租户事件落 NULL</b>：{@code tenantId} 传 {@code null} 时读回来必须仍是 NULL。</li>
 *   <li><b>{@code created_at} 的基准（评审 I2）</b>：服务层显式按 UTC 墙钟写入（可注入时钟）。
 *       判据是「回读值按 UTC 换算后与 JVM 的 {@code Instant.now()} 同窗口」+「注入固定时钟时落库值
 *       恰好等于那个瞬时」+「用 {@code LocalDateTime}（UTC）绑定的范围查询能找到这一行」。</li>
 *   <li><b>写入边界的脱敏</b>：安全网覆盖 值形态 / 键名 / 嵌套 / 数组 / POJO / 键文本，
 *       并且**只替换不丢字段**、深度上限只削内容不削字段。</li>
 * </ol>
 *
 * <p><b>用例数为什么从 7 涨到 21</b>：<i>独立评审</i>（{@code .superpowers/sdd/m4-task-7-review.md}）
 * 实测发现旧用例在三处「删掉功能也照样绿」：回滚用例没有正向对照；三条脱敏用例只断言敏感值
 * **不出现**、从不断言 {@code [REDACTED]} 出现或「被脱敏的键仍然存在」（于是「整条删掉」的实现
 * 全部通过）；四个值形态正则里有三个被键名路径短路、bcrypt 根本没有用例，且 POJO / 数组 /
 * map 的键这三条泄漏路径完全没有用例。本文件的每一条新用例都对应一个**可被杀死的变异体**
 * （见 {@code .superpowers/sdd/m4-task-7-fix-report.md}），没有一条是凑数。
 *
 * <p><b>本类的每一条断言都只落在自己写的那一行上</b>（按 {@code target_id} 定位，见
 * {@link #rowsFor(String)}）：{@code audit_log} 与 Testcontainers 容器都是 JVM 级共享的
 * （Task 1 的迁移用例、Task 8/9/10 的写路径都会往里插行），而 JUnit 的方法执行顺序是确定但
 * 不可依赖的 —— 第一版用「本类 action 的全表计数」做断言，在同一个类里就已经因为方法顺序而红过
 * （这正是 N12 说的那类隐患）。按 target_id 隔离之后，每条用例的读数与顺序无关。
 *
 * <p><b>⚠️ 本类会多起一个 Spring 上下文</b>（实测：全量运行里 {@code HikariPool-2} 与第二个 Tomcat
 * 就是它，约 5 s）：事务探针必须是一个真实的 Spring bean，所以它挂在
 * {@link TransactionalProbeConfiguration} 上并由 {@code @Import} 引入，于是上下文缓存的 key
 * 与共享上下文不同。本仓库已有同样的先例（{@code ConsoleLoginIntegrationTest} /
 * {@code ConsoleMisconfiguredSecretIntegrationTest} 因 {@code @TestPropertySource} 各自起上下文），
 * 这里登记为**已知代价**而不是隐患：反向字母序运行
 * （{@code -Dsurefire.runOrder=reversealphabetical}）与全量运行都是绿的，说明它不引入顺序依赖。
 */
@Import(AuditServiceIntegrationTest.TransactionalProbeConfiguration.class)
class AuditServiceIntegrationTest extends AbstractIntegrationTest {

    /** 本类专属的 action：与其它测试类/演示数据插入的行区分开。 */
    private static final String PROBE_ACTION = "TASK7_PROBE";

    /** 一眼可辨的合成值（不是真密钥）：四种「值形态」各一个。 */
    private static final String SYNTHETIC_PLAINTEXT_KEY = "sk-console-plaintext-synthetic";
    private static final String SYNTHETIC_CIPHER = "v1:cipher-synthetic";
    private static final String SYNTHETIC_JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln";
    private static final String SYNTHETIC_BCRYPT =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /**
     * 只用作**键名**的合成密钥：它不含任何敏感键名片段（apikey/secret/cipher/…），
     * 所以「键被替换、值原样保留」这件事只能由**键的文本**这一个规则解释。
     */
    private static final String SYNTHETIC_SECRET_KEY = "sk-console-rotation-key";

    /** 探针的两条路径各自固定的 target_id（正向对照 / 回滚），互不干扰。 */
    private static final String PROBE_COMMIT_TARGET_ID = "task7-probe-commit";
    private static final String PROBE_ROLLBACK_TARGET_ID = "task7-probe-rollback";

    /**
     * {@code created_at} 判据的容差（毫秒）。
     *
     * <p>为什么是 5 秒：这条路径只有「服务层写 + 一次 insert + 一次 select」的开销，实测远小于 1 秒；
     * 5 秒足够吸收一次 GC/容器调度抖动，而**它比 8 小时的时区偏差小 5760 倍**
     * （28 800 000 ms vs 5 000 ms）—— 所以「回读早 8 小时」或「写入用 JVM 本地墙钟」这两种实现
     * 无论朝哪个方向偏都必然红。旧用例用的「库钟对库钟 + 1 小时容差」恰好把这两种错误都放过去了
     * （独立评审 I2 的实测：8.0 h）。
     */
    private static final long CREATED_AT_TOLERANCE_MILLIS = 5_000L;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionalProbe transactionalProbe;

    // ------------------------------------------------------------------ (1) 同事务

    @Test
    void recordWritesAnAuditRowInTheSameTransactionAsTheBusinessWrite() {
        // 正向对照（评审 I3）：**同一条探针写路径**在不抛异常时必须真的落下恰好一行。
        // 没有它，这条用例在「record 什么都不写」的实现下也会绿（before=0、after=0）——
        // 它断言的就不是「回滚」，而是「什么都没发生」。
        transactionalProbe.writeAuditThenCommit();
        assertThat(rowsFor(PROBE_COMMIT_TARGET_ID))
                .as("正向对照：探针的写路径在不抛异常时确实插入一行（否则下面的回滚断言毫无判别力）")
                .hasSize(1);

        // ⚠️ 断言**增量**而不是「等于 0」：audit_log 与 testcontainers 容器都是 JVM 级共享的（N12）。
        long before = rowsFor(PROBE_ROLLBACK_TARGET_ID).size();

        assertThatThrownBy(() -> transactionalProbe.writeAuditThenFail())
                .isInstanceOf(IllegalStateException.class);

        assertThat(rowsFor(PROBE_ROLLBACK_TARGET_ID))
                .as("业务回滚，审计必须一起回滚（审计写在调用方的事务里，不是 REQUIRES_NEW）")
                .hasSize((int) before);
    }

    // ------------------------------------------------------------------ (2) 序列化失败

    @Test
    void recordPropagatesASerializationFailureAsIllegalStateExceptionInsteadOfSwallowingIt() {
        // 审计是合规要求，不是尽力而为：写不出 detail 时必须让业务一起失败（D8），
        // 且受检的 JsonProcessingException 不许从 record 的签名里漏出去（brief 与 I12 的要求）。
        // 这里用**不可序列化**的 detail 值（裸 Object：脱敏时把它转成 JSON 树会失败，
        // 异常是 JsonProcessingException 的子类）把这条路径逼出来。
        Object unserializable = new Object();
        String targetId = "task7-serialize-failure";

        assertThatThrownBy(() -> auditService.record(1L, new AuditService.Actor("SYSTEM", "system"),
                AuditAction.CHANNEL_CREATE, "CHANNEL", targetId, Map.of("broken", unserializable)))
                .as("detail 序列化失败必须变成 IllegalStateException（不能静默吞掉，也不能漏出受检异常）")
                .isInstanceOf(IllegalStateException.class);

        assertThat(rowsFor(targetId))
                .as("插入失败就不该留下半条审计行（序列化发生在 insert 之前）")
                .isEmpty();
    }

    // ------------------------------------------------------------------ (3) 无租户事件

    @Test
    void tenantLessEventsLandAsSqlNullNotAZeroSentinel() {
        // E.3-3 的裁定：LOGIN_FAILURE（用户名不存在）这类事件真的没有租户上下文，tenantId 传 null。
        // tenant_id 是 BIGINT NULL，写 null 就得是 SQL NULL —— 0 与真实 id 空间无法区分（id 从 1 开始）。
        // 这里同时用**实体回读**与**裸 JDBC 回读**两条独立路径断言：前者证明映射层没把 null 变成 0，
        // 后者证明**库里**那一格真的是 NULL（实体字段若被赋了 0，光看实体是看不出来的）。
        //
        // 这里就用 LOGIN_FAILURE 这个真实的无租户动作（它的**生产方**还不存在，见附录 E.3 第 11 条，
        // 所以本用例钉的是「tenantId 传 null 时落库是 NULL」这条**本服务**的契约）。
        String targetId = "task7-tenantless";
        auditService.record(null, new AuditService.Actor("SYSTEM", "system"),
                AuditAction.LOGIN_FAILURE, "TENANT", targetId, Map.of("reason", "unknown-username"));

        AuditLogEntity row = single(rowsFor(targetId));
        assertThat(row.getAction()).as("action 必须原样落库（常量值就是库里的值）")
                .isEqualTo(AuditAction.LOGIN_FAILURE);
        assertThat(row.getTenantId()).as("无租户事件的 tenant_id 必须是 NULL").isNull();

        // 裸 JDBC 回读：只取那一格（列类型是 BIGINT，用 Long 映射；为 NULL 时返回 null）。
        // ⚠️ 用 getObject 而不是 queryForObject(..., Long.class)：后者对 NULL 列会抛
        // EmptyResultDataAccessException，那样「是 NULL」与「这一行不存在」就分不开了 ——
        // 而这两种情况的含义完全不同。
        Object rawTenantId = jdbcTemplate.queryForObject(
                "select tenant_id from audit_log where id = ?", Object.class, row.getId());
        assertThat(rawTenantId).as("库里那一格必须是 SQL NULL，不是 0 哨兵").isNull();
    }

    // ------------------------------------------------------------------ (4) created_at 的基准

    @Test
    void createdAtIsStoredOnTheUtcWallClockBasisSoJavaReadsBackTheRightInstant() {
        // 判据（评审 I2 要求把旧用例「库钟对库钟 + 1 小时容差」换成 JVM 时钟对照）：
        // 把回读的 LocalDateTime 按 UTC 换算成瞬时，必须落在写入前后的 5 秒窗口内。
        // 容差与「为什么它看得见 8 小时偏差」见 CREATED_AT_TOLERANCE_MILLIS 的注释。
        String targetId = "task7-created-at";
        Instant before = Instant.now();

        auditService.record(1L, new AuditService.Actor("USER", "1"),
                PROBE_ACTION, "TENANT", targetId, Map.of("name", "probe"));

        LocalDateTime stored = single(rowsFor(targetId)).getCreatedAt();
        assertThat(stored)
                .as("created_at 必须被写入（不是 null，也不是零值）")
                .isNotNull();

        Instant readBack = stored.toInstant(ZoneOffset.UTC);
        Instant after = Instant.now();
        assertThat(readBack)
                .as("回读值按 UTC 换算后必须落在写入窗口内（stored=%s，容差 %d ms）",
                        stored, CREATED_AT_TOLERANCE_MILLIS)
                .isAfterOrEqualTo(before.minusMillis(CREATED_AT_TOLERANCE_MILLIS))
                .isBeforeOrEqualTo(after.plusMillis(CREATED_AT_TOLERANCE_MILLIS));
    }

    @Test
    void createdAtComesFromTheInjectedClockNotFromTheDatabaseDefault() {
        // 固定时钟：写进去的必须是**这个**瞬时。库默认值（CURRENT_TIMESTAMP(3) = 现在）
        // 与 JVM 本地墙钟（= UTC+8）都不可能恰好等于它，所以这一条同时钉住
        // 「显式设置」与「按 UTC 换算」两件事（二者缺一即红）。
        Instant pinned = Instant.parse("2026-09-29T11:40:19.718Z");
        AuditService pinnedClockService = new AuditService(auditLogMapper, Clock.fixed(pinned, ZoneOffset.UTC));
        String targetId = "task7-pinned-clock";

        pinnedClockService.record(1L, new AuditService.Actor("USER", "1"),
                PROBE_ACTION, "TENANT", targetId, Map.of("name", "probe"));

        assertThat(single(rowsFor(targetId)).getCreatedAt())
                .as("created_at 必须等于注入时钟的瞬时（按 UTC 换算），而不是库的 now() 或 JVM 本地墙钟")
                .isEqualTo(LocalDateTime.ofInstant(pinned, ZoneOffset.UTC));
    }

    @Test
    void aRangeQueryBoundedWithUtcLocalDateTimesFindsTheRow() {
        // Task 11 的 /api/audit?from=&to= 形状：边界用 **LocalDateTime（UTC 墙钟）** 绑定。
        // 这条是「写入基准错」的判别器：若 record 写的不是 UTC 墙钟（例如 LocalDateTime.now()，
        // 本机就是 UTC+8），这个 ±10 分钟的 UTC 窗口里一行都找不到（实测 0 行）。
        String targetId = "task7-range-query";
        Instant writeMoment = Instant.now();

        auditService.record(1L, new AuditService.Actor("USER", "1"),
                PROBE_ACTION, "TENANT", targetId, Map.of("name", "probe"));

        LocalDateTime from = LocalDateTime.ofInstant(writeMoment.minusSeconds(600), ZoneOffset.UTC);
        LocalDateTime to = LocalDateTime.ofInstant(writeMoment.plusSeconds(600), ZoneOffset.UTC);
        List<AuditLogEntity> found = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getTargetId, targetId)
                .ge(AuditLogEntity::getCreatedAt, from)
                .le(AuditLogEntity::getCreatedAt, to));

        assertThat(found)
                .as("用 LocalDateTime(UTC) 绑定的 ±10 分钟窗口必须找到刚写的那一行（from=%s to=%s）", from, to)
                .hasSize(1);
    }

    // ------------------------------------------------------------------ (5) 脱敏

    @Test
    void theAuditDetailNeverContainsSecrets() {
        // 用一眼可辨的合成值，直接调 AuditService（不经任何业务服务）：
        String targetId = "task7-secrets";
        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "CHANNEL", targetId,
                Map.of("name", "probe", "weight", 100,
                        "apiKeyCipher", SYNTHETIC_CIPHER,
                        "apiKey", SYNTHETIC_PLAINTEXT_KEY));

        String all = detailsOf(rowsFor(targetId));

        assertThat(all)
                .as("审计只记非敏感字段：明文/密文/口令/令牌一律不许出现")
                .doesNotContain(SYNTHETIC_PLAINTEXT_KEY)
                .doesNotContain(SYNTHETIC_CIPHER)
                .doesNotContain("v1:");
        // 替换而不是丢字段（评审 I3）：被脱敏的键必须**仍然在**，只有值是 [REDACTED]。
        // 一个「把敏感项整条删掉、或替换成 null/空串」的实现会在这两条上红 ——
        // 旧用例只断言「不出现」，那种实现照样全绿。
        assertThat(all)
                .as("被脱敏的字段必须仍然存在，值是 [REDACTED]（替换，不是丢字段）")
                .contains("\"apiKey\":\"[REDACTED]\"")
                .contains("\"apiKeyCipher\":\"[REDACTED]\"");
        // 反面也要钉住：脱敏不能把整条 detail 抹掉（否则「非敏感字段照记」这条就没了）。
        assertThat(all)
                .as("脱敏只针对敏感字段：渠道名/权重这些非敏感字段必须原样记下来")
                .contains("\"name\":\"probe\"")
                .contains("\"weight\":100");
    }

    @Test
    void aSecretIsAlsoRedactedWhenItsKeyNameLooksInnocuous() {
        // 键名不可信：{"reason": "sk-..."} 看起来完全无害，但值就是一把明文 Key。
        // 只按名字脱敏的实现（或干脆不脱敏的实现）会在这条上红。
        String targetId = "task7-secret-by-value";
        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "CHANNEL", targetId,
                Map.of("reason", SYNTHETIC_PLAINTEXT_KEY, "status", "ACTIVE"));

        assertThat(detailsOf(rowsFor(targetId)))
                .as("值本身是密钥形态时也必须脱敏（不能只看键名），且字段仍在")
                .doesNotContain(SYNTHETIC_PLAINTEXT_KEY)
                .contains("\"reason\":\"[REDACTED]\"")
                .contains("\"status\":\"ACTIVE\"");
    }

    @Test
    void aSecretNestedInsideAMapOrListIsRedactedToo() {
        // 脱敏必须递归：真实的 detail 会长成 {"before": {"apiKey": "..."}, "changed": [...]}。
        // 只脱顶层键的实现会在这条上红。
        String targetId = "task7-secret-nested";
        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "CHANNEL", targetId,
                Map.of("before", Map.of("apiKey", SYNTHETIC_PLAINTEXT_KEY, "weight", 100),
                        "auditTrail", List.of(Map.of("token", SYNTHETIC_JWT), "plain-note"),
                        "name", "probe"));

        assertThat(detailsOf(rowsFor(targetId)))
                .as("嵌套 map/list 里的敏感值也必须被抹掉，且被脱敏的键仍在")
                .doesNotContain(SYNTHETIC_PLAINTEXT_KEY)
                .doesNotContain(SYNTHETIC_JWT)
                .contains("\"apiKey\":\"[REDACTED]\"")
                .contains("\"token\":\"[REDACTED]\"")
                .contains("\"plain-note\"")
                .contains("\"name\":\"probe\"");
    }

    @Test
    void aPojosFieldsAreRedactedEvenThoughTheTopLevelValueIsNotAMap() {
        // 评审 C1 的回归护栏：旧实现把非 String/Map/List 的值**原样**交给 Jackson，
        // 于是 POJO 的字段逐个落库（实测 {"channel":{"apiKeyCipher":"v1:…","apiKey":"sk-…"}}）。
        // Task 8/9/10 手上就是 ChannelEntity（有 apiKeyCipher）这类对象，所以这条不是假想。
        String targetId = "task7-secret-pojo";
        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "CHANNEL", targetId,
                Map.of("channel", new ChannelRecordProbe(
                                "c1", SYNTHETIC_CIPHER, SYNTHETIC_PLAINTEXT_KEY, SYNTHETIC_JWT),
                        "name", "probe"));

        String all = detailsOf(rowsFor(targetId));
        assertThat(all)
                .as("POJO 的每个字段都要被走一遍：按键名命中的与按值形态命中的都不许落库")
                .doesNotContain(SYNTHETIC_CIPHER)
                .doesNotContain(SYNTHETIC_PLAINTEXT_KEY)
                .doesNotContain(SYNTHETIC_JWT);
        assertThat(all)
                .as("被脱敏的 POJO 字段仍然存在（替换，不是丢字段），非敏感字段原样保留")
                .contains("\"apiKeyCipher\":\"[REDACTED]\"")
                .contains("\"apiKey\":\"[REDACTED]\"")
                .contains("\"note\":\"[REDACTED]\"")
                .contains("\"name\":\"c1\"")
                .contains("\"name\":\"probe\"");
    }

    @Test
    void anArrayOfStringsIsWalkedSoSecretsInsideItAreRedacted() {
        // 数组是旧实现的第二条泄漏路径（实测 {"keys":["sk-…","plain"]} 原样落库）。
        String targetId = "task7-secret-string-array";
        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "CHANNEL", targetId,
                Map.of("keys", new String[] {SYNTHETIC_PLAINTEXT_KEY, "plain"}));

        assertThat(detailsOf(rowsFor(targetId)))
                .as("数组要逐个走：密钥替换、非密钥原样保留，数组形状本身也要保住")
                .doesNotContain(SYNTHETIC_PLAINTEXT_KEY)
                .contains("\"keys\":[\"[REDACTED]\",\"plain\"]");
    }

    @Test
    void anArrayOfPojosIsWalkedSoSecretsInsideItAreRedacted() {
        // 第三条泄漏路径：数组的**元素是 POJO**（两条规则叠在一起时最容易漏）。
        String targetId = "task7-secret-pojo-array";
        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "CHANNEL", targetId,
                Map.of("channels", new ChannelPojoProbe[] {
                        new ChannelPojoProbe("c1", SYNTHETIC_CIPHER),
                        new ChannelPojoProbe("c2", "v1:second-cipher")}));

        assertThat(detailsOf(rowsFor(targetId)))
                .as("POJO 数组的每个元素都要走：每个密文字段都得被替换，元素本身不许消失")
                .doesNotContain(SYNTHETIC_CIPHER)
                .doesNotContain("v1:second-cipher")
                .contains("\"name\":\"c1\"")
                .contains("\"name\":\"c2\"")
                .contains("\"apiKeyCipher\":\"[REDACTED]\"");
    }

    @Test
    void aValueShapedSecretUsedAsAMapKeyIsReplacedNotStored() {
        // 评审 I1：键的文本也是调用方交来的**内容**。旧实现只脱值不脱键，
        // 实测 {"sk-…":"enabled"} 与嵌套的 {"rotate":{"sk-…":1}} 原样落库。
        String targetId = "task7-secret-key";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(SYNTHETIC_PLAINTEXT_KEY, "enabled");
        detail.put("rotate", Map.of(SYNTHETIC_SECRET_KEY, 1));

        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "API_KEY", targetId, detail);

        assertThat(detailsOf(rowsFor(targetId)))
                .as("密钥形态的键不许落库；值照记（「这里有这个字段」这条事实要留下）")
                .doesNotContain(SYNTHETIC_PLAINTEXT_KEY)
                .doesNotContain(SYNTHETIC_SECRET_KEY)
                .contains("\"[REDACTED_KEY]\":\"enabled\"")
                .contains("\"rotate\":{\"[REDACTED_KEY]\":1}");
    }

    @Test
    void twoDistinctSecretKeysDoNotCollapseIntoOneField() {
        // 「这次改了哪几把 key」这类摘要天然会拿 key 当键名，两把 key 都会命中同一个占位符 ——
        // 直接用 put 会让后者覆盖前者，把两个字段变成一个（= 丢字段）。
        String targetId = "task7-secret-two-keys";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sk-probe-key-aaaa", "ENABLED");
        detail.put("sk-probe-key-bbbb", "DISABLED");

        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "API_KEY", targetId, detail);

        assertThat(detailsOf(rowsFor(targetId)))
                .as("两把密钥当键名时占位键不能互相覆盖（覆盖就少了一条事实）")
                .doesNotContain("sk-probe-key-aaaa")
                .doesNotContain("sk-probe-key-bbbb")
                .contains("\"[REDACTED_KEY]\":\"ENABLED\"")
                .contains("\"[REDACTED_KEY]#2\":\"DISABLED\"");
    }

    // ---- 四个值形态各一条：键名都是无害的，所以只有「值的形态」这一条规则能守住它们 ----

    @Test
    void aCipherShapedValueIsRedactedUnderAnInnocuousKeyName() {
        // note 不含任何敏感键名片段：去掉 ^v\d+: 这个模式，本条即红。
        assertValueShapeIsRedacted("note", SYNTHETIC_CIPHER, "task7-shape-cipher");
    }

    @Test
    void aPlaintextKeyShapedValueIsRedactedUnderAnInnocuousKeyName() {
        // blob 不含任何敏感键名片段：去掉 ^sk-…$ 这个模式，本条即红。
        assertValueShapeIsRedacted("blob", SYNTHETIC_PLAINTEXT_KEY, "task7-shape-plaintext");
    }

    @Test
    void aJwtShapedValueIsRedactedUnderAnInnocuousKeyName() {
        // jwt 不含任何敏感键名片段（旧用例把 JWT 放在 token 键下，走的是键名路径，
        // 所以 JWT 正则其实从来没被考过）：去掉 JWT 模式，本条即红。
        assertValueShapeIsRedacted("jwt", SYNTHETIC_JWT, "task7-shape-jwt");
    }

    @Test
    void aBcryptShapedValueIsRedactedUnderAnInnocuousKeyName() {
        // pin 不含任何敏感键名片段，且此前**没有任何用例**碰到 bcrypt 模式：去掉它，本条即红。
        assertValueShapeIsRedacted("pin", SYNTHETIC_BCRYPT, "task7-shape-bcrypt");
    }

    @Test
    void overRedactingNameMatchedKeysIsTheAcceptedCostNotAnAccident() {
        // 类注释把「按键名包含匹配」的**过度脱敏**登记为有意接受的代价，这里把它钉住：
        // tokenCount 不是敏感字段，但键名包含 "token" → 值被替换。
        // 把「包含」改成「相等」的实现会在这条上红（它会留下 tokenCount:17）——
        // 也就是说它是一个**被用例钉住的决定**，不是文档里的一句免责。
        String targetId = "task7-over-redaction";
        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "TENANT", targetId,
                Map.of("tokenCount", 17, "keyId", "k-1", "name", "probe"));

        String all = detailsOf(rowsFor(targetId));
        assertThat(all)
                .as("已知代价：键名包含匹配会把 tokenCount 一起脱敏")
                .contains("\"tokenCount\":\"[REDACTED]\"");
        assertThat(all)
                .as("键名里没有敏感片段的字段必须原样保留（脱敏不是把 detail 清空）")
                .contains("\"keyId\":\"k-1\"")
                .contains("\"name\":\"probe\"");
    }

    // ---- 深度上限：只削内容，不削字段 ----

    @Test
    void aSubtreeDeeperThanTheCapIsReplacedRatherThanDropped() {
        // 深度上限（MAX_DEPTH = 8）是「宁可少记内容，不可让 StackOverflowError 把响亮失败变成
        // 线程挂掉」的实现。第 9 层的**内容**被替换成 [REDACTED]，而键与层级结构全都还在 ——
        // 所以这与「替换而不是丢字段」并不矛盾（旧注释里那两句相反的话在这里被对齐）。
        // 期望串把上限本身也钉住了：{@code {"level":} 之后恰好 8 层 {@code {"n":}}。
        String targetId = "task7-depth-cap";
        Map<String, Object> node = Map.of("n", "bottom");
        for (int i = 0; i < 20; i++) {
            node = Map.of("n", node);
        }

        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "TENANT", targetId,
                Map.of("level", node));

        String expected = "{\"level\":" + "{\"n\":".repeat(8) + "\"[REDACTED]\"" + "}".repeat(8) + "}";
        assertThat(detailsOf(rowsFor(targetId)))
                .as("深度上限是 8：第 9 层的内容被替换，键与层级仍在，底层标记不许出现")
                .isEqualTo(expected)
                .doesNotContain("bottom");
    }

    @Test
    void aSelfReferencingDetailDoesNotBlowTheStack() {
        // detail 是调用方构造的 map，理论上可能自引用。撞上 StackOverflowError 会让
        // 「审计失败要响亮」变成「整个线程挂掉」—— 深度上限正是为这件事存在的。
        String targetId = "task7-cyclic";
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("me", cyclic);

        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "TENANT", targetId,
                Map.of("cyclic", cyclic));

        String expected = "{\"cyclic\":" + "{\"me\":".repeat(8) + "\"[REDACTED]\"" + "}".repeat(8) + "}";
        assertThat(detailsOf(rowsFor(targetId)))
                .as("自引用的 detail 必须在上限处被截断，而不是把线程打挂")
                .isEqualTo(expected);
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 「无害键名 + 一种密钥形态」的公共断言：只有**值形态**这一条规则能把值守住。
     *
     * @param innocuousKey 不含任何敏感键名片段的键（注意：它必须真的无害，否则这条用例就退化成
     *                     在考键名路径 —— 旧用例的 {@code apiKeyCipher}/{@code token} 就是这么被短路的）
     */
    private void assertValueShapeIsRedacted(String innocuousKey, String secret, String targetId) {
        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "CHANNEL", targetId,
                Map.of(innocuousKey, secret, "name", "probe"));

        assertThat(detailsOf(rowsFor(targetId)))
                .as("键名 %s 是无害的：这条只可能由值形态规则守住（去掉对应模式即红）", innocuousKey)
                .doesNotContain(secret)
                .contains("\"" + innocuousKey + "\":\"[REDACTED]\"");
    }

    /**
     * 本类专属 action 的行，按 {@code target_id} 在 Java 侧筛出来。
     *
     * <p>⚠️ 这里**刻意不把 {@code tenant_id}（甚至是值为 {@code null} 的 {@code target_id}）放进 WHERE**：
     * 实测 MyBatis-Plus 3.5.17 的 {@code eq} 在值为 {@code null} 时会生成 {@code tenant_id = ?} 并绑
     * {@code null}，而 SQL 里 {@code x = NULL} 恒为 UNKNOWN —— 于是「无租户」的那些行**查不出来**
     * （本条用例第一版就因此红过：插入成功了，回读却是 0 行）。要按 null 查必须显式写 {@code isNull()}。
     * 这里选择在 Java 侧过滤：行数极少，且不给「null 语义」留任何踩空的机会。
     * （{@code target_id} 每条用例各不相同，所以只按 action 取回来再筛就够了。）
     */
    private List<AuditLogEntity> rowsFor(String targetId) {
        return auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogEntity>()
                        .in(AuditLogEntity::getAction, PROBE_ACTION, AuditAction.LOGIN_FAILURE)
                        .orderByAsc(AuditLogEntity::getId)).stream()
                .filter(row -> targetId.equals(row.getTargetId()))
                .toList();
    }

    private static AuditLogEntity single(List<AuditLogEntity> rows) {
        assertThat(rows).as("该用例写的那一行必须恰好能被读回来").hasSize(1);
        return rows.get(0);
    }

    private static String detailsOf(List<AuditLogEntity> rows) {
        return rows.stream()
                .map(row -> row.getDetail() == null ? "<null-detail>" : row.getDetail())
                .collect(Collectors.joining("|"));
    }

    /**
     * 模拟 Task 8 手上的 {@code ChannelEntity}：一个**普通 POJO**（字段经 getter 暴露）。
     * 它是「非 String/Map/List 的值」那条泄漏路径的最小复现。
     */
    static final class ChannelPojoProbe {

        private final String name;
        private final String apiKeyCipher;

        ChannelPojoProbe(String name, String apiKeyCipher) {
            this.name = name;
            this.apiKeyCipher = apiKeyCipher;
        }

        public String getName() {
            return name;
        }

        public String getApiKeyCipher() {
            return apiKeyCipher;
        }
    }

    /**
     * record 形态的 DTO：{@code apiKeyCipher}/{@code apiKey} 按键名命中，
     * {@code note} 键名无害、值是密钥形态（考值形态）。
     */
    record ChannelRecordProbe(String name, String apiKeyCipher, String apiKey, String note) {
    }

    /**
     * Task 7 自己的事务探针。**必须是真实的 Spring bean**（由 {@link TestConfiguration} 注册），
     * 否则 {@code @Transactional} 不会被代理，也就没有事务边界可言。
     *
     * <p>{@code writeAuditThenFail} 在**同一个事务**里先经 {@link AuditService#record} 写审计、再抛异常：
     * 若 {@code record} 正确地加入调用方事务，这条审计行必须随事务回滚消失；
     * 若实现改成了 {@code REQUIRES_NEW}，它就活下来 —— 那正是本类要打红的变异体。
     *
     * <p>{@code writeAuditThenCommit} 是**正向对照**：同一条写路径、不抛异常，必须留下恰好一行。
     * 没有它，回滚用例在「record 什么都不写」的实现下恒绿（独立评审 I3）。
     */
    @TestConfiguration
    static class TransactionalProbeConfiguration {

        @Bean
        TransactionalProbe transactionalProbe(AuditService auditService) {
            return new TransactionalProbe(auditService);
        }
    }

    /** 探针本体。刻意只有两个写方法：它们模拟「业务写 + 审计写同事务」的最小形态。 */
    static class TransactionalProbe {

        private final AuditService auditService;

        TransactionalProbe(AuditService auditService) {
            this.auditService = auditService;
        }

        /** 正向对照：不抛异常 → 事务提交 → 审计行必须留下。 */
        @Transactional
        public void writeAuditThenCommit() {
            auditService.record(1L, new AuditService.Actor("USER", "1"),
                    PROBE_ACTION, "TENANT", PROBE_COMMIT_TARGET_ID, Map.of("probe", "commit"));
        }

        /** 业务失败：抛异常 → 事务回滚 → 审计行必须一起消失。 */
        @Transactional
        public void writeAuditThenFail() {
            auditService.record(1L, new AuditService.Actor("USER", "1"),
                    PROBE_ACTION, "TENANT", PROBE_ROLLBACK_TARGET_ID, Map.of("probe", "rollback"));
            throw new IllegalStateException("模拟业务失败：审计行必须随事务一起回滚");
        }
    }
}
