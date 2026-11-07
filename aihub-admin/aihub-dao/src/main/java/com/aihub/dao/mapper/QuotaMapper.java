package com.aihub.dao.mapper;

import com.aihub.dao.entity.QuotaEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * {@code quota} 表的 Mapper。由 {@code MybatisMapperConfig} 的
 * {@code @MapperScan("com.aihub.dao.mapper")} 扫描。
 *
 * <p><b>为什么乐观锁要手写、不用 {@code @Version}</b>：MyBatis-Plus 的 {@code @Version} 需要注册
 * {@code MybatisPlusInterceptor} + {@code OptimisticLockerInnerInterceptor} 才生效 —— 而本仓库
 * <b>刻意不注册</b> {@code MybatisPlusInterceptor}（Task 11 定死：分页走显式 {@code LIMIT/OFFSET}）。
 * 因此这里用一条显式的 compare-and-swap：只有 {@code version} 仍等于调用方读到的那个值时才会更新，
 * 否则受影响行数为 0。
 */
public interface QuotaMapper extends BaseMapper<QuotaEntity> {

    /**
     * 更新周期额度（手写乐观锁）：{@code token_limit} / {@code request_limit} 一起写，
     * {@code version} 自增 1，<b>且仅当</b> {@code version} 仍等于调用方读到的值。
     *
     * <p>{@code token_used} / {@code request_used} 刻意<b>不在这里写</b> —— 而且<b>本仓库没有任何生产代码写它们</b>
     * （2026-10-11 实测：全仓库唯一的 {@code UPDATE quota} 就是本方法；每日对账按 D12 只读不改账）。
     * 这两列是<b>"预留但未接线"</b>的，生产环境恒为 0；真实已用量在<b>数据面的 Redis 桶</b>里，
     * 只读出口见 {@code GET /api/quotas/usage}（{@code QuotaUsageService}，口径见 CONVENTIONS §6.7）。
     *
     * <p>调用方必须检查返回值：<b>0 ⇒ 并发冲突</b>（另一个请求在「读 version」与「写」之间提交了），
     * 此时<b>不许静默覆盖</b>，要抛异常让调用方重试。
     *
     * @return 受影响行数：1 = 更新成功；0 = {@code version} 已过期（冲突）
     */
    @Update("UPDATE quota SET token_limit = #{tokenLimit}, request_limit = #{requestLimit}, version = version + 1 "
            + "WHERE tenant_id = #{tenantId} AND period = #{period} AND version = #{version}")
    int compareAndSwapLimits(@Param("tenantId") long tenantId,
                             @Param("period") String period,
                             @Param("tokenLimit") long tokenLimit,
                             @Param("requestLimit") long requestLimit,
                             @Param("version") long version);

    /**
     * <b>原子「插或忽略」</b>：{@code (tenant_id, period)} 不存在时插入一行零额度行；已存在时<b>什么都不改</b>
     * （{@code id = id} 是空操作），且<b>不抛唯一键异常</b>。
     *
     * <p><b>为什么需要它（并发首建的幂等）</b>：{@code getOrCreate} 是「读或建」。两个并发调用同时发现
     * 「没有行」时，朴素写法会让后到的那次 {@code INSERT} 撞 {@code uk_quota_tenant_period} 并抛
     * {@code DuplicateKeyException}（经 HTTP 折成 <b>500</b>，而它本该是一个成功的读）。
     * 用本语句后并发首建<b>不经过异常路径</b>：失败方只是插了个空操作，随后重读即可拿到已提交的那一行。
     *
     * <p><b>为什么不「catch DuplicateKeyException 后再重读」</b>（2026-10-01 实测的反例）：
     * 那样写会让「失败的 INSERT」与「随后的加锁读」在并发下互相等锁，8 线程的并发首建用例稳定报
     * {@code MySQLTransactionRollbackException: Deadlock found when trying to get lock}；
     * 而且即便不死锁，REPEATABLE READ 下本事务的一致性读视图在<b>第一次</b> {@code SELECT}（返回 null）
     * 时就已固定，普通重读仍然看不见并发提交的那一行。
     *
     * <p>{@code created_at} / {@code updated_at} 由调用方按 CONVENTIONS §7 显式传 UTC 墙上时间
     * （不用库默认值）。
     */
    @Insert("INSERT INTO quota (tenant_id, period, token_limit, token_used, request_limit, request_used, "
            + "version, created_at, updated_at) "
            + "VALUES (#{tenantId}, #{period}, 0, 0, 0, 0, 0, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertZeroRowIfAbsent(@Param("tenantId") long tenantId,
                              @Param("period") String period,
                              @Param("now") LocalDateTime now);
}
