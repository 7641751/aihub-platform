package com.aihub.admin.metering;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.service.metering.RequestLogPartitionMaintainer;
import com.aihub.service.metering.RequestLogPartitionPlanner.Partition;
import com.aihub.service.metering.RequestLogPartitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分区维护的真实 DDL 行为（真 MySQL 8.4）。条条都是本机实测确认过的现象：
 * ① 缺的未来月份可以被补齐；② 已经落进 pmax 的行会被 REORGANIZE 救回正确分区；
 * ③ 重复调用是幂等的（且第一次调用**确实动了手**）；④ 基准月由活状态推导，不随日历漂移；
 * ⑤ DDL 失败会带着原因抛出；⑥ cron 钉在 UTC。（⑤⑥ 是纯本地检查，不需要容器，但同属本类主题。）
 *
 * <p><b>为什么「救援 / 幂等」两个用例的基准月必须从活状态推导，而不能写字面量</b>：
 * 本类是 {@code @SpringBootTest}，维护器的 {@code ApplicationRunner} 会在**每次上下文启动**时
 * 对着共享容器跑一遍 {@code ensureCoverage(今天)}（UTC），覆盖永远被推到「今天 + 2 个月」。
 * 只要真实日期走到字面量那几个月，前置条件「这个月还没有专属分区 ⇒ 行落在 pmax」就不再成立：
 * 原先救援用例写死 2028-06，真实 UTC 日期一到 2028-04-01，启动补齐就会建出 p202806，
 * {@code partitionFor("2028-06-15 …")} 返回 p202806 而不是 pmax —— 用例自己把自己判红，
 * 与 JUnit 执行顺序无关，而且**不可恢复**（分区只会越建越多）。把字面量往后挪只是把炸弹推远。
 * 现在基准月 = 「当前最后一个有界分区的上界 + 6 个月」（上界必是某月 1 号，故结果也是），
 * 它永远落在**当前覆盖之外**，前置条件因此由活状态保证，而不是由「今天是几号」保证。
 *
 * <p>{@link #ensureCoverageAddsTheMissingFutureMonths} 仍用历史字面量（2027-03），这是安全的：
 * 分区只增不减，一旦覆盖过 p202703 就永远存在，该用例的断言不会随日期变化。
 *
 * <p>本类不注入时钟、不依赖方法执行顺序：两个会推进覆盖的用例各自在调用前读活状态。
 */
class RequestLogPartitionMaintainerTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    @Autowired
    private RequestLogPartitionMaintainer maintainer;

    @Autowired
    private RequestLogPartitionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<String> partitionNames() {
        return repository.partitions().stream().map(Partition::name).toList();
    }

    /** 当前最后一个有界分区的上界（= pmax 的下界）；表结构坏掉时让用例直接失败，而不是静默 null。 */
    private LocalDate lastCoveredBound() {
        return repository.partitions().stream()
                .filter(partition -> !partition.isMaxValue())
                .map(Partition::upperBound)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new AssertionError("request_log 没有任何有界分区：V1 的结构契约被破坏"));
    }

    /**
     * 用例基准月的**唯一**来源：当前覆盖之外 6 个月（必为某月 1 号）。
     * 落在覆盖之外 ⇒ 这个月没有专属分区 ⇒ 插进去的行会落进 pmax（前置条件成立）；
     * +6 是余量，保证「插入的那一行」与「本次要补的分区」不会互相顶掉。
     */
    private LocalDate benchmarkMonthBeyondCoverage() {
        return lastCoveredBound().plusMonths(6);
    }

    /** 覆盖某个自然月的分区名（{@code pYYYYMM}）：该分区的上界是这个月的下个月 1 号。 */
    private static String partitionNameFor(LocalDate month) {
        return "p" + YearMonth.from(month).format(PARTITION_SUFFIX);
    }

    /** {@code EXPLAIN} 的 partitions 列是判断「某日期落在哪个分区」的唯一可靠手段。 */
    private String partitionFor(String utcDateTime) {
        return jdbcTemplate.queryForMap(
                "explain select * from request_log where created_at = '" + utcDateTime + "'")
                .get("partitions").toString();
    }

    @Test
    void ensureCoverageAddsTheMissingFutureMonths() {
        maintainer.ensureCoverage(LocalDate.of(2027, 3, 1));

        assertThat(partitionNames()).contains("p202612", "p202701", "p202702", "p202703", "p202704", "p202705");
        assertThat(partitionFor("2027-03-15 10:00:00")).isEqualTo("p202703");
    }

    /**
     * **陷阱复现 + 存量救援**：先把一行插到当前覆盖之外的月份（它会静默落进 pmax，`EXPLAIN` 可见），
     * 再让维护器补齐，然后这一行必须出现在正确分区里、内容不变，而且**已经不在 pmax 里**。
     *
     * <p>基准月与其期望分区名来自**同一个值**（{@link #benchmarkMonthBeyondCoverage()}），
     * 输入与断言不可能漂移。
     */
    @Test
    void repairRescuesRowsThatAlreadyFellIntoPmax() {
        LocalDate rescueMonth = benchmarkMonthBeyondCoverage();
        String rescueDate = rescueMonth.withDayOfMonth(15) + " 10:00:00";
        String expectedPartition = partitionNameFor(rescueMonth);
        String requestId = "req-m2-pmax-rescue";

        // 基准月必须落在当前覆盖之外，前置条件才成立 —— 这条断言就是「不再依赖日历」的落点。
        assertThat(rescueMonth).isAfterOrEqualTo(lastCoveredBound());

        jdbcTemplate.update(
                "insert into request_log (request_id, tenant_id, model, prompt_tokens, completion_tokens,"
                        + " total_tokens, latency_ms, status, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, 7L, "deepseek-chat", 5, 6, 11, 100, "SUCCESS",
                Timestamp.valueOf(rescueDate));
        // 前置条件：这个月还没有专属分区，所以它落在 pmax —— 这就是「静默吞掉」的证据。
        assertThat(partitionFor(rescueDate)).isEqualTo("pmax");

        maintainer.ensureCoverage(rescueMonth);

        // 「救援」的证据只能是「这行真的换了分区」：EXPLAIN 说它在 pYYYYMM，而不是说 DDL 没抛异常。
        assertThat(partitionFor(rescueDate)).isEqualTo(expectedPartition);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from request_log where request_id = ? and total_tokens = 11",
                Integer.class, requestId)).isEqualTo(1);
        // 直接查 pmax 分区本身：行不只是「能被查到」，而是**已经不在 pmax 里**了。
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from request_log partition (pmax) where request_id = ?",
                Integer.class, requestId)).isZero();
    }

    /**
     * 幂等：第二次调用不再有任何变化。
     *
     * <p>**第一次调用必须真的动手**：基准月取自活状态（覆盖之外），所以第一次调用一定建出分区；
     * 若不断言这一点，一旦别的用例已经把覆盖推到基准月之后，两次调用就都是空操作，
     * 用例退化成恒真的空断言 —— 一个不幂等的实现也能过。这条断言让「第二次无变化」有意义。
     */
    @Test
    void ensureCoverageIsIdempotent() {
        LocalDate today = benchmarkMonthBeyondCoverage();
        List<String> before = partitionNames();

        maintainer.ensureCoverage(today);
        List<String> afterFirst = partitionNames();

        assertThat(afterFirst).hasSizeGreaterThan(before.size());
        assertThat(afterFirst).contains(partitionNameFor(today));

        maintainer.ensureCoverage(today);

        assertThat(partitionNames()).isEqualTo(afterFirst);
    }

    /**
     * Fix 1 的**可执行**回归证据：模拟「真实日期已经走到 2028-04-01 之后」的状态 ——
     * 那时启动补齐（today + 2 个月）会建出 p202806。
     *
     * <p>旧写法（写死 2028-06）的前置条件在这里必然不成立：`EXPLAIN` 说那行在 p202806，
     * 而旧断言要求 pmax —— 这就是它从 2028-04-01 起**永久变红**的原因，且与执行顺序无关。
     * 同一状态下派生基准月仍然落在覆盖之外（`EXPLAIN` = pmax），所以新写法不受影响。
     */
    @Test
    void derivedBenchmarkSurvivesTheOldLiteralMonthAlreadyBeingCovered() {
        // 把覆盖推到 2028-07-01（= 2028-04-01 那天的启动补齐会做的事），于是 p202806 已存在。
        maintainer.ensureCoverage(LocalDate.of(2028, 4, 1));
        assertThat(partitionNames()).contains("p202806");
        // 旧字面量用例的「前置条件」此刻已经是假的：这一行不再落在 pmax。
        assertThat(partitionFor("2028-06-15 10:00:00")).isEqualTo("p202806");

        LocalDate benchmark = benchmarkMonthBeyondCoverage();

        assertThat(benchmark).isAfter(LocalDate.of(2028, 7, 1));
        assertThat(benchmark).isAfterOrEqualTo(lastCoveredBound());
        // 派生基准月的行仍然（正确地）落在 pmax：前置条件与日历无关，只与活状态有关。
        assertThat(partitionFor(benchmark.withDayOfMonth(15) + " 10:00:00")).isEqualTo("pmax");
    }

    /**
     * Fix 4：定时触发时刻必须显式钉在 UTC —— 日期算术已经在 {@code ZoneOffset.UTC} 上，
     * cron 若不写 zone，「03:10」就取决于 JVM 默认时区，变成两个口径。纯反射断言，不碰数据库。
     */
    @Test
    void scheduledCoverageIsPinnedToUtc() throws Exception {
        Scheduled scheduled = RequestLogPartitionMaintainer.class
                .getMethod("scheduledEnsureCoverage")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.zone()).isEqualTo("UTC");
    }

    /**
     * Fix 3：DDL 失败必须**带着原因**抛出。控制流不变（warn → 重读 → 重新规划 → 仍缺才 throw），
     * 但抛出的 {@link IllegalStateException} 要能顺着 cause 链看到原始的 {@code DataAccessException}
     * （SQL state / error code / 原始语句都在它里面）。
     *
     * <p>用桩仓库，不碰数据库：第一次重组 pmax 就抛，重读拿到的是同一份「没补上」的分区列表，
     * 因此必须抛出且 cause 是那个失败。
     */
    @Test
    void failedDdlIsChainedIntoTheIllegalStateThatReportsTheStillMissingPartitions() {
        RequestLogPartitionRepository failing = new RequestLogPartitionRepository(null) {
            @Override
            public List<Partition> partitions() {
                return List.of(
                        new Partition("p202609", LocalDate.of(2026, 10, 1)),
                        new Partition("p202610", LocalDate.of(2026, 11, 1)),
                        new Partition("p202611", LocalDate.of(2026, 12, 1)),
                        new Partition("pmax", null));
            }

            @Override
            public void reorganizePmax(List<Partition> newPartitions) {
                throw new DataAccessResourceFailureException("simulated 1493 (HY000)",
                        new SQLException("VALUES LESS THAN value must be strictly increasing", "HY000", 1493));
            }

            @Override
            public long pmaxRowCount() {
                return 0L;
            }
        };

        assertThatThrownBy(() -> new RequestLogPartitionMaintainer(failing, 2)
                .ensureCoverage(LocalDate.of(2026, 12, 15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("p202612")
                .hasCauseInstanceOf(DataAccessResourceFailureException.class)
                .cause()
                .hasMessageContaining("1493")
                .hasRootCauseMessage("VALUES LESS THAN value must be strictly increasing");
    }
}
