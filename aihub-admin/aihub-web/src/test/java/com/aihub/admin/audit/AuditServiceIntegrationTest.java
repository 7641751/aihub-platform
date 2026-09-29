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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Task 7 的验收：{@code audit_log} 的写入**发生在调用方的事务里**（决策 D8），且审计内容不含敏感字段。
 *
 * <p>七条用例分别钉住七件事：
 * <ol>
 *   <li><b>同事务</b>：业务回滚时审计行必须一起消失。做法是让**本类自己的**事务探针
 *       （{@link TransactionalProbe}，一个真实的 Spring bean，因此 {@code @Transactional} 的代理语义成立）
 *       先写一条审计、再抛异常；回滚之后该探针的审计行必须**没有留下**。
 *       ⚠️ 这条用例方法**本身不能**标 {@code @Transactional}：那样测试自己的事务会成为边界，
 *       探针的回滚在同一事务里被测试吞掉，断言就恒绿、什么也证明不了。</li>
 *   <li><b>审计失败 = 业务失败</b>：{@code record} 不允许吞异常。这里用「detail 里放一个
 *       Jackson 序列化不了的值」把 {@code MAPPER.writeValueAsString} 的受检
 *       {@code JsonProcessingException} 逼出来，断言它变成 {@code IllegalStateException}
 *       （受检异常不得从签名里漏出去）。</li>
 *   <li><b>无租户事件落 NULL</b>：{@code tenantId} 传 {@code null} 时读回来必须仍是 NULL，
 *       不是 {@code 0} 哨兵（{@code 0} 与真实 id 空间无法区分，id 从 1 开始）。</li>
 *   <li><b>{@code created_at} 真的被填了</b>：服务层不设它，靠的是 V2 的
 *       {@code DEFAULT CURRENT_TIMESTAMP(3)} —— 断言而不是假设。</li>
 *   <li><b>detail 不含敏感字段</b>：明文 Key / 密文 / {@code v1:} 前缀一个都不许出现，
 *       同时非敏感字段必须**原样保留**（脱敏不能变成清空）。这一条有三个方向：
 *       按敏感键名（{@code apiKey}/{@code apiKeyCipher}）、按值的形态（键名无害但值是明文 Key）、
 *       以及嵌套结构（map/list 里也要递归脱敏）。</li>
 * </ol>
 *
 * <p><b>为什么用例数是 7 而不是 brief 里写的 2</b>：brief 的「Tests run: 2」只数了它自己给出的两个片段。
 * 裁定 1 追加「null 落 NULL」、裁定 6 要求「断言 created_at 被填充」；实现契约里
 * 「受检 {@code JsonProcessingException} 必须转成 {@code IllegalStateException} 且不留半条行」
 * 是一条独立风险（brief 只在正文用一句话要求，没有任何用例钉住它）；最后，brief 自己的
 * no-secrets 用例要求「审计表绝不记录敏感值」，而它给出的实现样例是**原样序列化** ——
 * 要让它绿就必须由服务端脱敏，而脱敏有三个独立可失败的维度（键名 / 值形态 / 嵌套），
 * 各钉一条。每多出来的一条都对应一个**独立可失败**的变异体（见
 * {@code .superpowers/sdd/m4-task-7-report.md} 的变异证据），不是凑数。
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
 * （{@code -Dsurefire.runOrder=reversealphabetical}，本类排在 {@code com.aihub.admin.metering.*} 之前）
 * 与全量运行都是绿的，说明它不引入顺序依赖。
 */
@Import(AuditServiceIntegrationTest.TransactionalProbeConfiguration.class)
class AuditServiceIntegrationTest extends AbstractIntegrationTest {

    /** 本类专属的 action：与其它测试类/演示数据插入的行区分开。 */
    private static final String PROBE_ACTION = "TASK7_PROBE";

    /** 一眼可辨的合成值（不是真密钥）：明文 Key 与自描述密文各一个。 */
    private static final String SYNTHETIC_PLAINTEXT_KEY = "sk-console-plaintext-synthetic";
    private static final String SYNTHETIC_CIPHER = "v1:cipher-synthetic";

    /** 探针写的那一行用它定位（探针的 target_id 固定为这个值）。 */
    private static final String PROBE_TARGET_ID = "task7-probe-target";

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionalProbe transactionalProbe;

    @Test
    void recordWritesAnAuditRowInTheSameTransactionAsTheBusinessWrite() {
        // 用**本任务自己的**事务探针（不借 Task 8 的 ChannelAdminService —— 那会让 Task 7 依赖 Task 8，
        // 而 Task 7 排在前面）：它先写一行 audit_log，再抛异常；断言业务回滚时审计行也一起消失。
        // ⚠️ 断言**增量**而不是「等于 0」：audit_log 与 testcontainers 容器都是 JVM 级共享的（N12）。
        long before = rowsFor(PROBE_TARGET_ID).size();

        assertThatThrownBy(() -> transactionalProbe.writeAuditThenFail())
                .isInstanceOf(IllegalStateException.class);

        assertThat(rowsFor(PROBE_TARGET_ID))
                .as("业务回滚，审计必须一起回滚（审计写在调用方的事务里，不是 REQUIRES_NEW）")
                .hasSize((int) before);
    }

    @Test
    void recordPropagatesASerializationFailureAsIllegalStateExceptionInsteadOfSwallowingIt() {
        // 审计是合规要求，不是尽力而为：写不出 detail 时必须让业务一起失败（D8），
        // 且受检的 JsonProcessingException 不许从 record 的签名里漏出去（brief 与 I12 的要求）。
        // 这里用**不可序列化**的 detail 值（Jackson 对裸 Object 会抛 InvalidDefinitionException，
        // 它是 JsonProcessingException 的子类）把这条路径逼出来。
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

    @Test
    void createdAtIsPopulatedByTheDatabaseDefaultEvenThoughTheServiceNeverSetsIt() {
        // V2 里 created_at 是 DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)，而 record 不设它
        // （MyBatis-Plus 的默认字段策略会把 null 字段从 INSERT 里省略，于是库默认值生效）。
        // 这条**必须断言而不是假设**：NOT NULL 列一旦真的收到 NULL 就是硬失败，
        // 而一个静默为零的时间戳比失败更糟（审计的行序会全部失去意义）。
        String targetId = "task7-created-at";

        auditService.record(1L, new AuditService.Actor("USER", "1"),
                PROBE_ACTION, "TENANT", targetId, Map.of("name", "probe"));

        Instant createdAt = single(rowsFor(targetId)).getCreatedAt();
        assertThat(createdAt)
                .as("created_at 必须由库默认值填充（不是 null，也不是零值）")
                .isNotNull()
                .isNotEqualTo(Instant.EPOCH);

        Long rawMillis = jdbcTemplate.queryForObject(
                "select unix_timestamp(created_at) * 1000 from audit_log where target_id = ?", Long.class, targetId);
        assertThat(rawMillis).as("库里那一格必须是真实的时间戳（NULL 或 0 都不行）").isNotNull().isPositive();

        // ⚠️ 这里**不断言** createdAt 与测试 JVM 的 Instant.now() 落在同一个 5 分钟窗口内：
        // 实测本机（宿主 Asia/Shanghai、容器进程继承宿主时区）读回来的 Instant 恰好比 JVM 的 UTC
        // 早 8 小时 —— 那是「DATETIME 无时区 + 连接会话时区与驱动 serverTimezone 不一致」造成的
        // **读取侧**偏移，与本任务写入的 created_at 是否被填充无关（实测见报告 §偏差）。
        // 用 JVM 时钟做判据会把一个跨环境的时区约定问题伪装成 Task 7 的 bug，所以判据用
        // 「库自己的时钟」：这一行的 created_at 必须与数据库当前时间一致（同一支时钟，容差 1 小时）。
        Long dbNowMillis = jdbcTemplate.queryForObject("select unix_timestamp() * 1000", Long.class);
        assertThat(rawMillis)
                .as("created_at 必须来自库自己的时钟（用库时钟而不是 JVM 时钟，避免时区约定造成假红）")
                .isCloseTo(dbNowMillis, within(3_600_000L));
    }

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
        // 反面也要钉住：脱敏不能把整条 detail 抹掉（否则「非敏感字段照记」这条就没了，
        // 而一个恒为 [REDACTED] 的 detail 同样通不过上面三条断言 —— 那种「实现」在这里必须红）。
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
                .as("值本身是密钥形态时也必须脱敏（不能只看键名）")
                .doesNotContain(SYNTHETIC_PLAINTEXT_KEY)
                .contains("\"status\":\"ACTIVE\"");
    }

    @Test
    void aSecretNestedInsideAMapOrListIsRedactedToo() {
        // 脱敏必须递归：真实的 detail 会长成 {"before": {"apiKey": "..."}, "changed": [...]}。
        // 只脱顶层键的实现会在这条上红。
        String targetId = "task7-secret-nested";
        auditService.record(1L, new AuditService.Actor("USER", "1"), PROBE_ACTION, "CHANNEL", targetId,
                Map.of("before", Map.of("apiKey", SYNTHETIC_PLAINTEXT_KEY, "weight", 100),
                        "auditTrail", List.of(Map.of("token", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln"),
                                "plain-note"),
                        "name", "probe"));

        String all = detailsOf(rowsFor(targetId));
        assertThat(all)
                .as("嵌套 map/list 里的敏感值也必须被抹掉")
                .doesNotContain(SYNTHETIC_PLAINTEXT_KEY)
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .contains("\"name\":\"probe\"");
    }

    // ---------------------------------------------------------------- 辅助

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
     * Task 7 自己的事务探针。**必须是真实的 Spring bean**（由 {@link TestConfiguration} 注册），
     * 否则 {@code @Transactional} 不会被代理，也就没有事务边界可言。
     *
     * <p>{@code writeAuditThenFail} 在**同一个事务**里先经 {@link AuditService#record} 写审计、再抛异常：
     * 若 {@code record} 正确地加入调用方事务，这条审计行必须随事务回滚消失；
     * 若实现改成了 {@code REQUIRES_NEW}，它就活下来 —— 那正是本类要打红的变异体。
     */
    @TestConfiguration
    static class TransactionalProbeConfiguration {

        @Bean
        TransactionalProbe transactionalProbe(AuditService auditService) {
            return new TransactionalProbe(auditService);
        }
    }

    /** 探针本体。刻意只有这一个写方法：它模拟「业务写 + 审计写同事务」的最小形态。 */
    static class TransactionalProbe {

        private final AuditService auditService;

        TransactionalProbe(AuditService auditService) {
            this.auditService = auditService;
        }

        @Transactional
        public void writeAuditThenFail() {
            auditService.record(1L, new AuditService.Actor("USER", "1"),
                    PROBE_ACTION, "TENANT", PROBE_TARGET_ID, Map.of("probe", "rollback"));
            throw new IllegalStateException("模拟业务失败：审计行必须随事务一起回滚");
        }
    }
}
