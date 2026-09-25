package com.aihub.service.metering;

import com.aihub.service.metering.RequestLogPartitionPlanner.Partition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
     */
    public void reorganizePmax(List<Partition> newPartitions) {
        String additions = newPartitions.stream()
                .map(partition -> "PARTITION " + partition.name()
                        + " VALUES LESS THAN ('" + partition.upperBound() + "')")
                .collect(Collectors.joining(", "));
        jdbcTemplate.execute("ALTER TABLE request_log REORGANIZE PARTITION pmax INTO ("
                + additions + ", PARTITION pmax VALUES LESS THAN (MAXVALUE))");
    }

    /** pmax 里当前压着多少行（正常情况下应该是 0；不为 0 说明曾经漏过分区）。 */
    public long pmaxRowCount() {
        Long count = jdbcTemplate.queryForObject("select count(*) from request_log partition (pmax)", Long.class);
        return count == null ? 0L : count;
    }
}
