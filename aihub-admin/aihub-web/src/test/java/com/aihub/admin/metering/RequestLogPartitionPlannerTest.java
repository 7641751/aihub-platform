package com.aihub.admin.metering;

import com.aihub.service.metering.RequestLogPartitionPlanner;
import com.aihub.service.metering.RequestLogPartitionPlanner.Partition;
import com.aihub.service.metering.RequestLogPartitionPlanner.Plan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分区规划的纯函数测试（不碰数据库）。这些断言全部来自本机 MySQL 8.4 的实测：
 * V1 的分区是 p202609(&lt;10-01) / p202610(&lt;11-01) / p202611(&lt;12-01) / pmax，
 * 而「今天 + N 个月」必须每月都有**专属**分区，否则行会静默落进 pmax。
 */
class RequestLogPartitionPlannerTest {

    private static final List<Partition> V1_PARTITIONS = List.of(
            new Partition("p202609", LocalDate.of(2026, 10, 1)),
            new Partition("p202610", LocalDate.of(2026, 11, 1)),
            new Partition("p202611", LocalDate.of(2026, 12, 1)),
            new Partition("pmax", null));

    @Test
    void v1PartitionsAlreadyCoverToday() {
        Plan plan = RequestLogPartitionPlanner.plan(V1_PARTITIONS, LocalDate.of(2026, 9, 23), 2);

        assertThat(plan.pmaxPresent()).isTrue();
        assertThat(plan.toCreate()).isEmpty();
        assertThat(plan.gaps()).isEmpty();
    }

    @Test
    void addsTheMissingFutureMonths() {
        Plan plan = RequestLogPartitionPlanner.plan(V1_PARTITIONS, LocalDate.of(2026, 12, 15), 2);

        assertThat(plan.toCreate()).extracting(Partition::name)
                .containsExactly("p202612", "p202701", "p202702");
        assertThat(plan.toCreate()).extracting(Partition::upperBound).containsExactly(
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1), LocalDate.of(2027, 3, 1));
    }

    /** 中间空洞只报告、不补（补它要 REORGANIZE 一个**有数据**的分区）—— 决策 15。 */
    @Test
    void detectsAMiddleGap() {
        List<Partition> withGap = List.of(
                new Partition("p202609", LocalDate.of(2026, 10, 1)),
                new Partition("p202611", LocalDate.of(2026, 12, 1)),
                new Partition("pmax", null));

        Plan plan = RequestLogPartitionPlanner.plan(withGap, LocalDate.of(2026, 9, 23), 0);

        assertThat(plan.gaps()).containsExactly("p202610");
    }

    @Test
    void reportsMissingPmax() {
        Plan plan = RequestLogPartitionPlanner.plan(
                List.of(new Partition("p202609", LocalDate.of(2026, 10, 1))),
                LocalDate.of(2026, 9, 23), 2);

        assertThat(plan.pmaxPresent()).isFalse();
    }

    /** 应用停了几个月：从最后一个**有界**分区的上界开始逐月补到「今天 + N 个月」。 */
    @Test
    void coversFromAStaleMaxBoundWhenTheAppWasDownForMonths() {
        Plan plan = RequestLogPartitionPlanner.plan(V1_PARTITIONS, LocalDate.of(2027, 5, 15), 2);

        assertThat(plan.toCreate()).hasSize(8);
        assertThat(plan.toCreate().get(0).name()).isEqualTo("p202612");
        assertThat(plan.toCreate().get(7).name()).isEqualTo("p202707");
        assertThat(plan.toCreate().get(7).upperBound()).isEqualTo(LocalDate.of(2027, 8, 1));
    }

    @Test
    void isIdempotentOnTheResultOfAPlan() {
        Plan first = RequestLogPartitionPlanner.plan(V1_PARTITIONS, LocalDate.of(2026, 12, 15), 2);
        List<Partition> afterApply = new java.util.ArrayList<>(V1_PARTITIONS);
        afterApply.addAll(first.toCreate());

        Plan second = RequestLogPartitionPlanner.plan(afterApply, LocalDate.of(2026, 12, 15), 2);

        assertThat(second.toCreate()).isEmpty();
    }

    /** RANGE COLUMNS 的边界是**带单引号**的字符串，pmax 是 MAXVALUE（实测格式）。 */
    @Test
    void parsesQuotedRangeColumnBoundsAndMaxValue() {
        assertThat(RequestLogPartitionPlanner.parseBound("'2026-10-01'")).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(RequestLogPartitionPlanner.parseBound("MAXVALUE")).isNull();
        assertThat(RequestLogPartitionPlanner.parseBound(null)).isNull();
    }
}
