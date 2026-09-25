package com.aihub.service.metering;

import com.aihub.service.metering.RequestLogPartitionPlanner.Partition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code request_log} 分区元数据的读取与 DDL 执行。
 *
 * <p>DDL 只有一种写法：{@code REORGANIZE PARTITION pmax INTO (…新分区…, pmax)}。
 * 实测 {@code ADD PARTITION} 在 pmax 存在时必然报
 * {@code ERROR 1493 (HY000): VALUES LESS THAN value must be strictly increasing for each partition}
 * —— 因为 pmax 的 {@code MAXVALUE} 已经是最后一个边界，任何追加都排在它后面。
 * 而 REORGANIZE 还会把已经落进 pmax 的行按新边界重新分配（存量救援）。
 */
@Repository
public class RequestLogPartitionRepository {

    /** 语句尾部固定带回的新 pmax：它是「新分区 + MAXVALUE」这套区间的闭口。 */
    private static final String PMAX_TAIL = ", PARTITION pmax VALUES LESS THAN (MAXVALUE))";

    private final JdbcTemplate jdbcTemplate;

    public RequestLogPartitionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按物理顺序读取分区：{@code PARTITION_DESCRIPTION} 对 RANGE COLUMNS 是**带单引号**的日期
     * 字面量（实测 {@code '2026-10-01'}），pmax 是 {@code MAXVALUE}。
     */
    public List<Partition> partitions() {
        return jdbcTemplate.query(
                "select partition_name, partition_description from information_schema.partitions "
                        + "where table_schema = database() and table_name = 'request_log' "
                        + "and partition_name is not null order by partition_ordinal_position",
                (rs, rowNum) -> new Partition(rs.getString(1),
                        RequestLogPartitionPlanner.parseBound(rs.getString(2))));
    }

    /**
     * 把 {@code pmax} 劈成「新分区 + 新的 pmax」。
     *
     * <p>REORGANIZE 的新分区表必须**恰好**覆盖 pmax 原来的区间，因此末尾必须原样带回
     * {@code pmax VALUES LESS THAN (MAXVALUE)}；新分区从 pmax 的下界（即最后一个有界分区的上界）
     * 开始逐月递增，正好满足 MySQL「边界严格递增」的要求。
     *
     * <p>这是一条会**重写表数据**的语句，所以入参先过 {@link #requireReorganizable(List)}：
     * 空列表会拼出非法 DDL（{@code INTO (, PARTITION pmax …)}），边界乱序或含 {@code MAXVALUE}
     * 则只会在语句已经发到线上表之后才被服务端以 1493 拒绝。合法入参生成的 SQL 与护栏引入前**逐字节相同**。
     *
     * @throws IllegalArgumentException 列表为空/null、分区缺名字、含 {@code MAXVALUE}、上界非严格递增
     */
    public void reorganizePmax(List<Partition> newPartitions) {
        requireReorganizable(newPartitions);
        String additions = newPartitions.stream()
                .map(partition -> "PARTITION " + partition.name()
                        + " VALUES LESS THAN ('" + partition.upperBound() + "')")
                .collect(Collectors.joining(", "));
        jdbcTemplate.execute("ALTER TABLE request_log REORGANIZE PARTITION pmax INTO ("
                + additions + PMAX_TAIL);
    }

    /**
     * 本地护栏（在 {@code execute} **之前**跑，不碰数据库）：把「参数错了」变成调用点上的
     * {@link IllegalArgumentException}，而不是线上表上的一条半成品 DDL。
     *
     * <p>只做便宜的检查：空列表、缺名字、含 {@code MAXVALUE}、上界非严格递增。语句尾部永远是
     * {@code pmax VALUES LESS THAN (MAXVALUE)}，因此「上界严格递增 + 末尾接 MAXVALUE」已经保证
     * 新分区要么正好从 pmax 的下界开始、要么落在 pmax 内部（后者会被 MySQL 1493 拒绝——那是服务端
     * 才知道的信息，本地不查 {@code information_schema}，避免多一次往返与 TOCTOU）。
     */
    private static void requireReorganizable(List<Partition> newPartitions) {
        if (newPartitions == null || newPartitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "REORGANIZE PARTITION pmax 的新分区列表不能为空：会生成非法 DDL（INTO (, PARTITION pmax ...))");
        }
        LocalDate previous = null;
        for (Partition partition : newPartitions) {
            if (partition == null || partition.name() == null || partition.name().isBlank()) {
                throw new IllegalArgumentException("REORGANIZE PARTITION pmax 的新分区必须带名字: " + newPartitions);
            }
            if (partition.isMaxValue()) {
                throw new IllegalArgumentException("新分区 " + partition.name()
                        + " 的上界是 MAXVALUE：列表里不能出现 pmax 本身（结尾的 pmax 由本方法固定追加）");
            }
            LocalDate bound = partition.upperBound();
            if (previous != null && !bound.isAfter(previous)) {
                throw new IllegalArgumentException("REORGANIZE PARTITION pmax 的新分区上界必须严格递增，实际 "
                        + previous + " -> " + bound + "（分区 " + partition.name() + "）");
            }
            previous = bound;
        }
    }

    /** pmax 里当前压着多少行（正常情况下应该是 0；不为 0 说明曾经漏过分区）。 */
    public long pmaxRowCount() {
        Long count = jdbcTemplate.queryForObject("select count(*) from request_log partition (pmax)", Long.class);
        return count == null ? 0L : count;
    }
}
