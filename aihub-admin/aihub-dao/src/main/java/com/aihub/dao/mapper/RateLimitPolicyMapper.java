package com.aihub.dao.mapper;

import com.aihub.dao.entity.RateLimitPolicyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code rate_limit_policy} 表的 Mapper。不加自定义 SQL：M3 只需要全表读取（配置快照）。
 *
 * <p><b>读取顺序是契约的一部分（决策 17）</b>：V1 在这张表上**没有唯一键**（只有
 * {@code idx_rate_limit_tenant}），所以同一个 {@code (tenant_id, api_key_id)} 可以有多行，
 * 「同维度内取最后一条」= 「取 {@code id} 最大的那条」。{@code BaseMapper#selectList(null)}
 * 生成的是无 {@code ORDER BY} 的全表扫描（InnoDB 聚簇索引 = 主键顺序 → 即 {@code id} 升序），
 * 组装快照时必须**原样保持这个顺序**；不要为了「确定性」在组装层再排序（例如按 qps 排序），
 * 那会把「取最后一条」变成「取某个字段最大的一条」。
 *
 * <p>与另外两个 mapper 相同：**状态过滤不在 DAO**，由 Task 13 的组装显式带 {@code status = 'ACTIVE'}。
 */
public interface RateLimitPolicyMapper extends BaseMapper<RateLimitPolicyEntity> {
}
