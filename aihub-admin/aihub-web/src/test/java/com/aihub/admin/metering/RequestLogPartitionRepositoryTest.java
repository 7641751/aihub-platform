package com.aihub.admin.metering;

import com.aihub.service.metering.RequestLogPartitionPlanner.Partition;
import com.aihub.service.metering.RequestLogPartitionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code reorganizePmax} 的本地护栏（纯单元，不碰数据库、不起容器）。
 *
 * <p>这是给一条**会重写线上表数据**的 DDL 做入参校验：空列表会拼出
 * {@code INTO (, PARTITION pmax …)} 这种非法语句，边界乱序 / 含 MAXVALUE 只会在语句发出去之后
 * 才被 MySQL 以 1493 拒绝。护栏必须在 {@code jdbcTemplate.execute} **之前**拦下来。
 *
 * <p>因此这里故意传 {@code null} 的 JdbcTemplate：只要护栏没有先抛
 * {@link IllegalArgumentException}，就会变成 {@link NullPointerException}（或真的去连库），
 * 用例即红 —— 这同时证明了「SQL 根本没有被发出」。
 */
class RequestLogPartitionRepositoryTest {

    private final RequestLogPartitionRepository repository = new RequestLogPartitionRepository(null);

    @Test
    void rejectsAnEmptyPartitionList() {
        assertThatThrownBy(() -> repository.reorganizePmax(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void rejectsANullPartitionList() {
        assertThatThrownBy(() -> repository.reorganizePmax(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void rejectsDuplicateOrDecreasingBounds() {
        assertThatThrownBy(() -> repository.reorganizePmax(List.of(
                new Partition("p202701", LocalDate.of(2027, 2, 1)),
                new Partition("p202702", LocalDate.of(2027, 2, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("严格递增");

        assertThatThrownBy(() -> repository.reorganizePmax(List.of(
                new Partition("p202701", LocalDate.of(2027, 2, 1)),
                new Partition("p202702", LocalDate.of(2027, 1, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("严格递增");
    }

    @Test
    void rejectsAMaxValueBoundInTheNewPartitionList() {
        // upperBound == null 是 pmax 自己：末尾的 pmax 由本方法固定追加，列表里再出现一个就是拼错的 DDL。
        assertThatThrownBy(() -> repository.reorganizePmax(List.of(new Partition("pmax", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAXVALUE");
    }

    @Test
    void rejectsAPartitionWithoutAName() {
        assertThatThrownBy(() -> repository.reorganizePmax(List.of(
                new Partition("  ", LocalDate.of(2027, 2, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名字");
    }
}
