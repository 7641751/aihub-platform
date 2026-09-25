package com.aihub.admin.metering;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.service.metering.RequestLogPartitionMaintainer;
import com.aihub.service.metering.RequestLogPartitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分区维护的真实 DDL 行为（真 MySQL 8.4）。三条都是本机实测确认过的现象：
 * ① 缺的未来月份可以被补齐；② 已经落进 pmax 的行会被 REORGANIZE 救回正确分区；
 * ③ 重复调用是幂等的。
 *
 * <p>时间基准写死在用例里（不依赖「今天是几号」），因此不会随日期漂移变红。
 *
 * <p><b>为什么救援用例挑 2028-06</b>：本类三个用例共享同一个 MySQL 容器，分区是**累积**的。
 * {@code ensureCoverageIsIdempotent} 会把覆盖推到 2028-03（today=2028-01 + 2 个月），
 * {@code ensureCoverageAddsTheMissingFutureMonths} 推到 2027-06；若救援用例挑 2027-09 之类的
 * 月份，它的前置条件（「这行此刻落在 pmax」）就会**依赖 JUnit 的方法执行顺序**，顺序一变就红。
 * 2028-06 落在另外两个用例的覆盖之外，因此前置条件与顺序无关。
 */
class RequestLogPartitionMaintainerTest extends AbstractIntegrationTest {

    @Autowired
    private RequestLogPartitionMaintainer maintainer;

    @Autowired
    private RequestLogPartitionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<String> partitionNames() {
        return repository.partitions().stream().map(p -> p.name()).toList();
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
     * **陷阱复现 + 存量救援**：先把一行插到还没建分区的月份（它会静默落进 pmax，`EXPLAIN` 可见），
     * 再让维护器补齐，然后这一行必须出现在正确分区里且内容不变。
     */
    @Test
    void repairRescuesRowsThatAlreadyFellIntoPmax() {
        String requestId = "req-m2-pmax-rescue";
        jdbcTemplate.update(
                "insert into request_log (request_id, tenant_id, model, prompt_tokens, completion_tokens,"
                        + " total_tokens, latency_ms, status, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, 7L, "deepseek-chat", 5, 6, 11, 100, "SUCCESS",
                Timestamp.valueOf("2028-06-15 10:00:00"));
        // 前置条件：这个月还没有专属分区，所以它落在 pmax —— 这就是「静默吞掉」的证据。
        assertThat(partitionFor("2028-06-15 10:00:00")).isEqualTo("pmax");

        maintainer.ensureCoverage(LocalDate.of(2028, 6, 1));

        // 「救援」的证据只能是「这行真的换了分区」：EXPLAIN 说它在 p202806，而不是说 DDL 没抛异常。
        assertThat(partitionFor("2028-06-15 10:00:00")).isEqualTo("p202806");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from request_log where request_id = ? and total_tokens = 11",
                Integer.class, requestId)).isEqualTo(1);
        // 直接查 pmax 分区本身：行不只是「能被查到」，而是**已经不在 pmax 里**了。
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from request_log partition (pmax) where request_id = ?",
                Integer.class, requestId)).isZero();
    }

    @Test
    void ensureCoverageIsIdempotent() {
        maintainer.ensureCoverage(LocalDate.of(2028, 1, 1));
        List<String> afterFirst = partitionNames();

        maintainer.ensureCoverage(LocalDate.of(2028, 1, 1));

        assertThat(partitionNames()).isEqualTo(afterFirst);
    }
}
