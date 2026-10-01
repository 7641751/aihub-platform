package com.aihub.dao.mapper;

import com.aihub.dao.entity.QuotaEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code quota} 表的 Mapper。由 {@code MybatisMapperConfig} 的
 * {@code @MapperScan("com.aihub.dao.mapper")} 扫描。
 *
 * <p><b>为什么乐观锁要手写、不用 {@code @Version}</b>：MyBatis-Plus 的 {@code @Version} 需要注册
 * {@code MybatisPlusInterceptor} + {@code OptimisticLockerInnerInterceptor} 才生效 —— 而本仓库
 * **刻意不注册** {@code MybatisPlusInterceptor}（Task 11 定死：分页走显式 {@code LIMIT/OFFSET}）。
 * 因此这里用一条显式的 compare-and-swap：只有 {@code version} 仍等于调用方读到的那个值时才会更新，
 * 否则受影响行数为 0。
 */
public interface QuotaMapper extends BaseMapper<QuotaEntity> {

    /**
     * 更新周期额度（手写乐观锁）：{@code token_limit} / {@code request_limit} 一起写，
     * {@code version} 自增 1，**且仅当** {@code version} 仍等于调用方读到的值。
     *
     * <p>{@code token_used} / {@code request_used} 刻意**不在这里写**：它们是数据面（Redis 预扣 +
     * 每日对账写回）的账，控制面改额度不该碰已用量。
     *
     * <p>调用方必须检查返回值：**0 ⇒ 并发冲突**（另一个请求在「读 version」与「写」之间提交了），
     * 此时**不许静默覆盖**，要抛异常让调用方重试。
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
}
