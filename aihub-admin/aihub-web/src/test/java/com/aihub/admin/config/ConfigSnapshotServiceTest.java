package com.aihub.admin.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.RatePolicy;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.service.channel.ChannelKeyService;
import com.aihub.service.config.ConfigSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照组装（{@code GET /internal/config/snapshot} 的数据面）。**必须真起容器**：这里要钉住的是
 * 「{@code max(updated_at)} 真的随一次写入推进」「{@code status = 'ACTIVE'} 真的把停用行挡在快照外」
 * 「同维度多条 ACTIVE 策略里 {@code id} 最大的那条真的赢」这三件 SQL 层面的事实 —— 换成内存替身
 * 就会变成「测自己写的排序」，一条都证明不了。
 *
 * <p><b>三条控制器裁决在这里落地</b>（它们都压过计划正文的措辞）：
 * <ul>
 *   <li><b>G5</b>：INACTIVE 的 {@code channel} / {@code model_route} / {@code rate_limit_policy} 行
 *       **不得进入快照**。计划正文写的是「全部行都组装（含 DISABLED），由 gateway 侧过滤」——
 *       那条路走不通：{@code RatePolicy} 根本没有状态分量，而 {@code ChannelDescriptor.usable()}
 *       也只在网关侧生效，因此组装层漏掉过滤在网关侧**不可观测**。三个网关 DTO 里只有
 *       {@code RatePolicy} 没有任何状态痕迹，所以过滤点只能是组装层，且每条都由
 *       {@code containsExactly} 钉死（不是「ACTIVE 的在」而是「INACTIVE 的不在」）。</li>
 *   <li><b>决策 17</b>：「同维度取最后一条」= 「取 {@code id} 最大的那条」，这里用真实 MySQL 断言，
 *       不把 {@code RateLimitPolicyMapper} javadoc 里的 InnoDB 扫描顺序假设默默继承下来。</li>
 *   <li><b>决策 D5</b>：{@code version} = {@code max(三张表 updated_at 的最大值, config_version 水位)}，
 *       改一行必须**严格变大**；水位被显式归零（见 {@link #resetVersionWatermark()}）且控制面为空时是
 *       {@code 0}。抬水位的入口是配置**写**路径（{@code ConfigChangePublisher}），本类只用 {@code updated_at}
 *       那一半，因此这些断言仍然钉住「三张表都参与 max」。</li>
 * </ul>
 *
 * <p>用例数据一律是一眼可辨的合成值；密文走真实的 {@link ChannelKeyService#encrypt}，主密钥在
 * 测试里由字节算出来（tracked 文件里没有任何明文密钥 / 密文 / 主密钥字面量）。
 */
class ConfigSnapshotServiceTest extends AbstractIntegrationTest {

    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";

    /**
     * {@code version} 基准的容差（毫秒）。这一格是 {@code datetime(3)}，写入的是整数毫秒，
     * 因此「精确相等」本就成立；留 1 ms 只为把「截断」与「基准错」分开 —— 8 小时的缺陷
     * （28800000 ms）比它大七个数量级，绝不会被它兜住。
     */
    private static final long VERSION_BASIS_TOLERANCE_MILLIS = 1L;

    /**
     * 「列里的墙上时间 vs 数据库自己的 UTC 时钟」的容差（毫秒）—— 独立评审 I-3 要求的**独立**基准断言用。
     *
     * <p>两次 SELECT 之间的真实间隔是毫秒级（同一台容器、同一条连接池）；这里给 2 秒是给慢机器的余量。
     * 它仍然比**任何**非零真实时区偏移小三个数量级以上（2026-09-30 在本机 JDK 25.0.2 上枚举全部
     * tzdata zone、跨三个瞬时实测：最小的**非零**偏移是 **60 分钟 = 3600000 ms**，2 秒是它的
     * **1/1800**），所以「列按会话时区写」这种基准错一定会红，而不是被容差吃掉。
     */
    private static final long COLUMN_CLOCK_TOLERANCE_MILLIS = 2_000L;

    /** 合成明文（不是任何真实密钥）：只用来证明「快照里拿到的密文能解回它、且密文里没有它」。 */
    private static final String SYNTHETIC_PLAINTEXT = "sk-channel-plaintext-synthetic";

    @Autowired
    private ConfigSnapshotService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 只用于造密文：与 admin 侧生产组件用的是同一个 {@code ChannelKeyService}。 */
    private ChannelKeyService keys;

    @BeforeEach
    void cleanBefore() {
        keys = new ChannelKeyService("v1:" + b64Key(11));
        deleteEverything();
    }

    @BeforeEach
    void resetVersionWatermark() {
        // 水位是**持久**的（这正是 Task 3 的意义），而容器是 JVM 级共享的：
        // 不归零，「空库 version=0」这类断言就变成了对**用例执行顺序**的断言。
        jdbcTemplate.update("UPDATE config_version SET version = 0 WHERE id = 1");
    }

    @AfterEach
    void cleanAfter() {
        deleteEverything();
    }

    @Test
    void emptyDatabaseYieldsAnEmptySnapshotWithTheResetVersion() {
        ConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.channels()).isEmpty();
        assertThat(snapshot.routes()).isEmpty();
        assertThat(snapshot.ratePolicies()).isEmpty();
        assertThat(snapshot.version())
                .as("空控制面 + 被显式归零的水位（决策 D5：水位是持久的，这个 0 来自 @BeforeEach 的归零，"
                        + "不是「版本恒为 0」）")
                .isZero();
        assertThat(snapshot.generatedAtEpochMilli()).isPositive();
    }

    /**
     * 决策 D5：{@code version} 必须是**严格递增的标量**。三张表都要参与 {@code max}，
     * 而且「改一行」也要推进（V1 三张表的 {@code updated_at} 都是 {@code ON UPDATE CURRENT_TIMESTAMP(3)}）。
     *
     * <p>毫秒分辨率下同一毫秒内的两次写入可能拿到相同的 {@code updated_at} —— 那个缺口是**登记在案**的
     * （不修），因此这里每次写入之间睡 10ms，让断言钉住的是「真的推进」而不是「时钟够快」。
     */
    @Test
    void versionStrictlyIncreasesEveryTimeAnyOfTheThreeTablesChanges() throws Exception {
        long empty = service.snapshot().version();
        // 语义是 max(空控制面 0, 被 @BeforeEach 归零的水位 0) = 0 —— 不是「version 恒为 0」。
        assertThat(empty).isZero();

        String cipher = insertChannel("snap-v", ACTIVE);
        assertThat(cipher).startsWith("v1:");
        long afterChannelInsert = service.snapshot().version();
        assertThat(afterChannelInsert).as("channel 表的一次写入必须推进版本").isGreaterThan(empty);

        Thread.sleep(10L);
        insertRoute("snap-v-model", channelIdOf("snap-v"), 50, 1, ACTIVE);
        long afterRouteInsert = service.snapshot().version();
        assertThat(afterRouteInsert).as("model_route 表也要参与 max(updated_at)").isGreaterThan(afterChannelInsert);

        Long tenantId = insertTenant("snap-v-tenant");
        Thread.sleep(10L);
        insertPolicy(tenantId, null, 20, 40, ACTIVE);
        long afterPolicyInsert = service.snapshot().version();
        assertThat(afterPolicyInsert).as("rate_limit_policy 表也要参与 max(updated_at)").isGreaterThan(afterRouteInsert);

        Thread.sleep(10L);
        jdbcTemplate.update("update channel set weight = weight + 1 where name = ?", "snap-v");
        assertThat(service.snapshot().version())
                .as("改一行（不是新增）同样必须让版本严格变大，否则网关会拿着陈旧快照继续服务")
                .isGreaterThan(afterPolicyInsert);
    }

    /**
     * 决策 D5 的时间**基准**：{@code max(updated_at)} 只来自**裸 SQL / seeder** 这条路径
     * （控制台写入会走 {@code ConfigChangePublisher} 抬水位，把这一半盖住），因此这里把水位显式
     * 归零（见 {@link #resetVersionWatermark()}）、只靠一行 raw SQL 解释版本。
     *
     * <p><b>本用例是判别器</b>：{@code datetime(3)} 列里存的是 **UTC 墙上时间**，所以回读必须走
     * 「不做任何时区换算」的载体 —— 先用 {@link LocalDateTime} 读出那一格（驱动对它会原样搬运），
     * 再显式折 {@code toInstant(ZoneOffset.UTC)}。曾经的实现读 {@code java.sql.Timestamp} 再
     * {@code toInstant()}，驱动会按**连接时区**解释这串墙上时间（连接时区解析成 LOCAL 时就是
     * JVM 默认时区，本机 Asia/Shanghai），于是版本整整早 8 小时（28800000 ms）——
     * 对「几秒内收敛」的验收判据来说就是永远不收敛。
     *
     * <p><b>判别力的边界（独立评审 I-1）</b>：旧读法只在**连接时区不是 UTC** 时才有偏差。
     * 生产 URL 钉了 {@code serverTimezone=UTC}，在那里旧读法与新读法**逐位相等**（实测 old − new = 0），
     * 所以本用例现在跑的生产方言下**不能**判别两个实现 —— 判别那份工作由
     * {@code TimeBasisIsConnectionFlavourIndependentTest}（故意钉非 UTC 连接时区的独立上下文）承担。
     * 本用例在这里承担的是另一半：钉住**生产读法**是「列 + 显式 UTC 折」，并用下面那条
     * **数据库自身时钟**断言钉住**列的基准**（见下）。
     *
     * <p>容差 {@value #VERSION_BASIS_TOLERANCE_MILLIS} ms：这一行**不加**容差就是精确相等，
     * 保留 1 毫秒只是给 {@code datetime(3)} 的毫秒截断留位（这里实际上是整数毫秒，取 0 也会绿）。
     * 8 小时的偏差比它大 7 个数量级，因此「红」不会靠容差。
     *
     * <p><b>独立评审 I-3 的补强 —— 列基准必须用数据库自己的时钟来钉</b>：上面的断言把**同一列**按
     * **同一个 UTC 折常量**折了两次，因此它只能证明「生产的读法 = UTC 折」，证明不了
     * 「列里存的确实是 UTC」：V1 的配置表用 {@code DEFAULT CURRENT_TIMESTAMP(3)} /
     * {@code ON UPDATE CURRENT_TIMESTAMP(3)} 生成这一列，那写的是**数据库会话时区**的墙上时间，
     * 而应用侧没有任何东西钉会话时区。若某台 MySQL 的会话时区不是 UTC，这一列会存本地墙上时间，
     * 两边一起被折错、上面的断言照样绿，而 {@code currentVersion()} 会整体偏一个时区。
     * 因此下面额外比**两个不同的时钟**：列里的值 vs 数据库自己的 {@code UTC_TIMESTAMP(3)}；
     * 并在会话时区与 UTC 不同时要求这一格**不**落在 {@code NOW(3)}（会话时钟）附近。
     */
    @Test
    void versionUsesTheUtcWallClockBasisOfUpdatedAtNotTheJvmDefaultZone() {
        // 水位的归零在 @BeforeEach（resetVersionWatermark）：此刻 config_version.version = 0，
        // 因此 currentVersion() 只可能等于这一行的 updated_at。
        assertThat(jdbcTemplate.queryForObject(
                "select version from config_version where id = 1", Long.class))
                .as("前置条件：水位必须是 0，否则下面测的是水位而不是 max(updated_at)")
                .isZero();

        insertChannel("snap-utc-basis", ACTIVE);

        // 关键：用 LocalDateTime 读回那一格 —— 这个载体不做时区换算，拿到的就是库里的墙上时间。
        LocalDateTime stored = jdbcTemplate.queryForObject(
                "select updated_at from channel where name = ?", LocalDateTime.class, "snap-utc-basis");
        assertThat(stored).as("这一行的 updated_at 必须真的落库（不是 null）").isNotNull();
        long expected = stored.toInstant(ZoneOffset.UTC).toEpochMilli();

        long actual = service.currentVersion();

        assertThat(Math.abs(actual - expected))
                .as("version 必须把 updated_at 当作 **UTC** 墙上时间来解释：库里存的是 %s，期望 %d ms，"
                                + "实际 %d ms（差 %d ms，容差 %d ms）。旧读法（java.sql.Timestamp）把这一格"
                                + "按**连接时区**解释成瞬时；本套件跑的是生产方言 serverTimezone=UTC"
                                + "（该连接的偏移为 0），因此两种读法在这里逐位相等 —— 判别旧读法的活在故意钉"
                                + "非 UTC 连接时区的 TimeBasisIsConnectionFlavourIndependentTest",
                        stored, expected, actual, actual - expected, VERSION_BASIS_TOLERANCE_MILLIS)
                .isLessThanOrEqualTo(VERSION_BASIS_TOLERANCE_MILLIS);

        // ——— 独立评审 I-3：列的**真实基准**，用数据库自己的时钟独立断言 ———
        // 上面那条断言折的是「同一列 + 同一个 UTC 常量」，因此对「列本身不是 UTC」是盲的
        // （会话时区写库时，`expected` 与 `actual` 一起偏，仍然相等 ⇒ 绿）。这里换两个**不同的时钟**：
        // 列里的墙上时间必须落在数据库自己的 UTC_TIMESTAMP(3) 附近。会话时区不是 UTC 时，
        // CURRENT_TIMESTAMP(3) 写的是会话墙上时间，这条会红 —— 而上面那条仍然是绿的。
        LocalDateTime dbUtcNow = jdbcTemplate.queryForObject("select utc_timestamp(3)", LocalDateTime.class);
        LocalDateTime dbSessionNow = jdbcTemplate.queryForObject("select now(3)", LocalDateTime.class);
        long sessionOffsetMillis = Duration.between(dbUtcNow, dbSessionNow).toMillis();

        assertThat(Math.abs(Duration.between(dbUtcNow, stored).toMillis()))
                .as("channel.updated_at 必须落在**数据库自己的 UTC 时钟**附近（±%d ms）："
                                + "UTC_TIMESTAMP(3) = %s，列里是 %s（两者相差 %d ms），"
                                + "会话时钟 NOW(3) = %s（会话相对 UTC 的偏移 %d ms）。"
                                + "这一条能抓到上面那条抓不到的东西：列的**基准** —— "
                                + "若会话时区不是 UTC，CURRENT_TIMESTAMP(3) 会写本地墙上时间，这里就红了",
                        COLUMN_CLOCK_TOLERANCE_MILLIS, dbUtcNow, stored,
                        Duration.between(dbUtcNow, stored).toMillis(), dbSessionNow, sessionOffsetMillis)
                .isLessThanOrEqualTo(COLUMN_CLOCK_TOLERANCE_MILLIS);

        if (Math.abs(sessionOffsetMillis) > COLUMN_CLOCK_TOLERANCE_MILLIS) {
            assertThat(Math.abs(Duration.between(dbSessionNow, stored).toMillis()))
                    .as("会话时区相对 UTC 偏了 %d ms（NOW(3) = %s vs UTC_TIMESTAMP(3) = %s），"
                                    + "因此这一格**不得**落在会话时钟附近 —— 落在上面就说明它是按会话时区写的",
                            sessionOffsetMillis, dbSessionNow, dbUtcNow)
                    .isGreaterThan(COLUMN_CLOCK_TOLERANCE_MILLIS);
        }
    }

    /**
     * G5 第一条：INACTIVE 的渠道行**不得**进入快照。断言用 {@code containsExactly}：
     * 只断言「ACTIVE 那条在」的实现（把 INACTIVE 也带上）会漏过这个缺口。
     */
    @Test
    void inactiveChannelRowsNeverEnterTheSnapshot() {
        String activeCipher = insertChannel("snap-ch-active", ACTIVE);
        insertChannel("snap-ch-inactive", INACTIVE);

        List<ChannelDescriptor> channels = service.snapshot().channels();

        assertThat(channels).extracting(ChannelDescriptor::name).containsExactly("snap-ch-active");
        assertThat(channels.get(0).apiKeyCipher()).as("密文原样透出（快照里永远只有密文）").isEqualTo(activeCipher);
    }

    /** G5 第二条：INACTIVE 的路由行**不得**进入快照（路由级的 weight/priority 要一并核对）。 */
    @Test
    void inactiveRouteRowsNeverEnterTheSnapshot() {
        Long channelId = insertChannelReturningId("snap-rt-ch", ACTIVE);
        insertRoute("snap-rt-active", channelId, 120, 4, ACTIVE);
        insertRoute("snap-rt-inactive", channelId, 1, 9, INACTIVE);

        List<ModelRouteDescriptor> routes = service.snapshot().routes();

        assertThat(routes).extracting(ModelRouteDescriptor::modelName).containsExactly("snap-rt-active");
        assertThat(routes.get(0).weight()).as("权重取 model_route 的值").isEqualTo(120);
        assertThat(routes.get(0).priority()).isEqualTo(4);
        assertThat(routes.get(0).usable()).isTrue();
    }

    /**
     * G5 第三条：INACTIVE 的限流策略行**不得**进入快照 —— 而且两个维度都要覆盖：
     * 停用一条租户级行不该回落成「没有策略」，停用一条 key 级行同样不该生效。
     */
    @Test
    void inactiveRateLimitPolicyRowsNeverEnterTheSnapshotInEitherDimension() {
        Long tenantId = insertTenant("snap-g5-tenant");
        insertPolicy(tenantId, null, 20, 40, ACTIVE);
        insertPolicy(tenantId, null, 999, 999, INACTIVE);
        insertPolicy(tenantId, 42L, 5, 10, ACTIVE);
        insertPolicy(tenantId, 42L, 777, 777, INACTIVE);

        ConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.ratePolicies()).as("只允许两条 ACTIVE 行进快照").hasSize(2);
        assertThat(snapshot.tenantPolicies(tenantId)).extracting(RatePolicy::qps).containsExactly(20);
        assertThat(snapshot.keyPolicies(tenantId, 42L)).extracting(RatePolicy::qps).containsExactly(5);
        assertThat(snapshot.ratePolicies()).extracting(RatePolicy::qps)
                .as("被停用的那一行（qps=999 / 777）一条都不许出现").doesNotContain(999, 777);
    }

    /** 快照里的字段与 V1 的列一一对应：密文逐字相等、能解回明文、且快照里没有明文。 */
    @Test
    void channelFieldsAreExposedVerbatimAndTheSnapshotCarriesOnlyCipherText() {
        String cipher = insertChannel("snap-verbatim", ACTIVE);

        ChannelDescriptor channel = service.snapshot().channels().get(0);

        assertThat(channel.name()).isEqualTo("snap-verbatim");
        assertThat(channel.baseUrl()).isEqualTo("https://snap-verbatim.example.com");
        assertThat(channel.apiKeyCipher()).isEqualTo(cipher);
        assertThat(channel.keyVersion()).isEqualTo(keys.currentKeyVersion());
        assertThat(channel.timeoutMs()).isEqualTo(60_000);
        assertThat(channel.status()).isEqualTo(ACTIVE);
        assertThat(channel.usable()).isTrue();

        assertThat(channel.apiKeyCipher()).as("快照里绝不能出现明文渠道密钥").doesNotContain(SYNTHETIC_PLAINTEXT);
        assertThat(new AesGcmChannelCipher(ChannelKeyRegistry.parse("v1:" + b64Key(11)))
                .decrypt(channel.apiKeyCipher()))
                .as("透出的密文必须能被网关侧的实现解开（加密实现只有一份）")
                .contains(SYNTHETIC_PLAINTEXT);
    }

    /**
     * 决策 17 + G5：同租户有多条 ACTIVE 租户级策略时，**id 最大的那条**赢。
     *
     * <p>数据刻意让「后插入的那条」的 qps **更小**（20 &lt; 60），并在中间夹一条 INACTIVE 的极大值：
     * 于是「取 qps 最大的」「取第一条」「取 INACTIVE 那条」三种错误实现都会红，
     * 只有「ACTIVE 里 id 最大的」才对。
     */
    @Test
    void theActiveTenantLevelPolicyWithTheMaximumIdWins() {
        Long tenantId = insertTenant("snap-max-id-tenant");
        insertPolicy(tenantId, null, 60, 80, ACTIVE);
        insertPolicy(tenantId, null, 999, 999, INACTIVE);
        insertPolicy(tenantId, null, 20, 40, ACTIVE);

        List<RatePolicy> policies = service.snapshot().tenantPolicies(tenantId);

        assertThat(policies).as("两条 ACTIVE 都要在（组装的顺序就是 id 升序）").hasSize(2);
        assertThat(policies.get(policies.size() - 1).qps())
                .as("「取最后一条」= 「取 id 最大的那条」，不是「取 qps 最大的那条」")
                .isEqualTo(20);
        assertThat(policies.get(policies.size() - 1).burst()).isEqualTo(40);
        assertThat(policies.get(0).qps()).isEqualTo(60);
    }

    /** 决策 17 的观测面：同维度多条 ACTIVE 时打一次 WARN（脏数据要被看见），但结果仍然是 id 最大的那条。 */
    @Test
    void severalActiveTenantLevelPoliciesEmitExactlyOneWarningAndTheMaximumIdStillWins() {
        Long tenantId = insertTenant("snap-warn-tenant");
        insertPolicy(tenantId, null, 60, 80, ACTIVE);
        insertPolicy(tenantId, null, 20, 40, ACTIVE);

        List<ILoggingEvent> events = captureServiceWarnings(() -> {
            List<RatePolicy> policies = service.snapshot().tenantPolicies(tenantId);
            assertThat(policies.get(policies.size() - 1).qps()).isEqualTo(20);
        });

        assertThat(events).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).contains("多条租户级").contains(String.valueOf(tenantId)));
    }

    /**
     * 决策 7（修订）+ 决策 17：两个维度**都**要能从快照里取到，而且跨维度的优先级不是靠 id 顺序
     * 表达的 —— 组装顺序把租户级排在前面（快照契约），但 key 级那条仍然只被它自己的 (租户, key) 取到，
     * 因此网关的「key 级优先」不会被组装顺序吃掉。
     */
    @Test
    void bothPolicyDimensionsAreQueryableAndTheKeyLevelRowIsNotShadowedByTheTenantLevelOne() {
        Long tenantId = insertTenant("snap-two-dim-tenant");
        // key 级的 id 更小：如果实现按「全局最后一条」或「id 最大」来选，就会选错维度。
        insertPolicy(tenantId, 42L, 5, 10, ACTIVE);
        insertPolicy(tenantId, null, 20, 40, ACTIVE);

        ConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.ratePolicies()).extracting(RatePolicy::apiKeyId)
                .as("组装契约：同一租户的租户级行排在 key 级行之前（决策 17 的排序）")
                .containsExactly(null, 42L);
        assertThat(snapshot.tenantPolicies(tenantId)).singleElement().satisfies(policy -> {
            assertThat(policy.qps()).isEqualTo(20);
            assertThat(policy.tenantLevel()).isTrue();
        });
        assertThat(snapshot.keyPolicies(tenantId, 42L)).singleElement().satisfies(policy -> {
            assertThat(policy.qps()).as("更严格的 key 级策略（覆盖租户级）必须能取到").isEqualTo(5);
            assertThat(policy.burst()).isEqualTo(10);
            assertThat(policy.tenantLevel()).isFalse();
        });
        assertThat(snapshot.keyPolicies(tenantId, 43L)).as("别的 key 取不到").isEmpty();
    }

    // --- SQL 助手 -------------------------------------------------------

    private void deleteEverything() {
        jdbcTemplate.update("delete from model_route");
        jdbcTemplate.update("delete from rate_limit_policy");
        jdbcTemplate.update("delete from channel");
    }

    private Long insertTenant(String name) {
        jdbcTemplate.update("insert into tenant (name, status) values (?, ?)", name, ACTIVE);
        return jdbcTemplate.queryForObject("select id from tenant where name = ?", Long.class, name);
    }

    private Long insertChannelReturningId(String name, String status) {
        insertChannel(name, status);
        return channelIdOf(name);
    }

    /**
     * 插入一条渠道行，密文由 {@link ChannelKeyService#encrypt} 真实产出（主密钥由字节算出，
     * tracked 文件里没有明文 / 密文 / 主密钥字面量）。
     */
    private String insertChannel(String name, String status) {
        String cipher = keys.encrypt(SYNTHETIC_PLAINTEXT);
        jdbcTemplate.update("insert into channel (name, provider, base_url, api_key_cipher, key_version, "
                        + "weight, priority, timeout_ms, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, "test", "https://" + name + ".example.com", cipher, keys.currentKeyVersion(),
                100, 0, 60_000, status);
        return cipher;
    }

    private void insertRoute(String modelName, Long channelId, int weight, int priority, String status) {
        jdbcTemplate.update("insert into model_route (model_name, channel_id, weight, priority, status) "
                + "values (?, ?, ?, ?, ?)", modelName, channelId, weight, priority, status);
    }

    private void insertPolicy(Long tenantId, Long apiKeyId, int qps, int burst, String status) {
        jdbcTemplate.update("insert into rate_limit_policy (tenant_id, api_key_id, qps, burst, status) "
                + "values (?, ?, ?, ?, ?)", tenantId, apiKeyId, qps, burst, status);
    }

    private Long channelIdOf(String name) {
        return jdbcTemplate.queryForObject("select id from channel where name = ?", Long.class, name);
    }

    private static List<ILoggingEvent> captureServiceWarnings(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ConfigSnapshotService.class);
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 32 字节主密钥，由种子算出：tracked 文件里不出现任何密钥字面量。 */
    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
