package com.aihub.service.metering;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code request_log} 月分区的**纯规划逻辑**（不碰数据库，便于把边界条件测干净）。
 *
 * <p>命名规则与 V1 一致：分区 {@code pYYYYMM} 覆盖 {@code YYYY-MM} 这个自然月，
 * 其上界是**下个月 1 号**（如 {@code p202611 VALUES LESS THAN ('2026-12-01')}）。
 *
 * <p>只做**向前追加**：因为 MySQL 要求边界严格递增（实测 {@code ADD PARTITION} 在有 pmax 时直接
 * 报 1493），所以新分区只能从「最后一个有界分区的上界」开始逐月生成。中间空洞只报告（见
 * {@link Plan#gaps()}）——补它需要 REORGANIZE 一个**有数据**的分区，会搬数据、长时间持锁，
 * 必须由运维决定（计划「决策登记」第 15 条）。
 *
 * <p>{@code RANGE COLUMNS} 只声明**上界**，最低的那个分区下界无界，因此比它更早的时间戳会被
 * **吸收进最低分区**（不是报错）。这里要防的只有上界一侧：上界不够远，行就静默落进 {@code pmax}。
 */
public final class RequestLogPartitionPlanner {

    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    /** {@code upperBound == null} 表示 {@code pmax}（MAXVALUE）。 */
    public record Partition(String name, LocalDate upperBound) {
        public boolean isMaxValue() {
            return upperBound == null;
        }
    }

    public record Plan(List<Partition> toCreate, List<String> gaps, boolean pmaxPresent) {
    }

    private RequestLogPartitionPlanner() {
    }

    public static Plan plan(List<Partition> existing, LocalDate today, int monthsAhead) {
        List<Partition> bounded = existing.stream()
                .filter(partition -> !partition.isMaxValue())
                .sorted(Comparator.comparing(Partition::upperBound))
                .toList();
        boolean pmaxPresent = existing.stream().anyMatch(Partition::isMaxValue);

        List<String> gaps = new ArrayList<>();
        for (int i = 1; i < bounded.size(); i++) {
            LocalDate previous = bounded.get(i - 1).upperBound();
            LocalDate current = bounded.get(i).upperBound();
            for (LocalDate missing = previous.plusMonths(1); missing.isBefore(current); missing = missing.plusMonths(1)) {
                gaps.add(nameForBound(missing));
            }
        }

        // 覆盖必须**含**「今天 + monthsAhead」那个月的末端，即上界 today 月 + monthsAhead + 1
        // 本身（例如 today=2027-05、ahead=2 ⇒ 最后要建出覆盖 2027-07 的分区，其上界是 2027-08-01）。
        LocalDate coverageEnd = today.withDayOfMonth(1).plusMonths(monthsAhead + 1L);
        // next 是**下一个待建分区的上界**（不是「下一个月的上界」）：最后一个有界分区
        // p202611(<2026-12-01) 已覆盖到 2026-11，因此下一个待建的是覆盖 2026-12 的
        // p202612(<2027-01-01) —— 起点就是 lastBound.plusMonths(1)。写成 lastBound 会生成一个
        // 与 p202611 **同名同界**的重复分区（DDL 直接报错），并使规划不再幂等。
        LocalDate next = bounded.isEmpty()
                ? today.withDayOfMonth(1).plusMonths(1)
                : bounded.get(bounded.size() - 1).upperBound().plusMonths(1);
        List<Partition> toCreate = new ArrayList<>();
        while (!next.isAfter(coverageEnd)) {
            toCreate.add(new Partition(nameForBound(next), next));
            next = next.plusMonths(1);
        }
        return new Plan(List.copyOf(toCreate), List.copyOf(gaps), pmaxPresent);
    }

    /** 上界 → 分区名：上界 {@code 2026-12-01} 覆盖 2026-11，因此名字是 {@code p202611}。 */
    static String nameForBound(LocalDate bound) {
        return "p" + YearMonth.from(bound.minusMonths(1)).format(NAME_FORMAT);
    }

    /**
     * 解析 {@code information_schema.PARTITIONS.PARTITION_DESCRIPTION}。
     * RANGE COLUMNS 返回的是**带单引号**的日期字面量（实测 {@code '2026-10-01'}），
     * {@code pmax} 返回 {@code MAXVALUE}（→ {@code null}）。
     */
    public static LocalDate parseBound(String description) {
        if (description == null) {
            return null;
        }
        String cleaned = description.replace("'", "").trim();
        if (cleaned.isEmpty() || "MAXVALUE".equalsIgnoreCase(cleaned)) {
            return null;
        }
        return LocalDate.parse(cleaned);
    }
}
