package com.aihub.dao.mapper;

import com.aihub.dao.entity.ChannelEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code channel} 表的 Mapper。不加自定义 SQL：M3 只需要全表读取（配置快照）。
 *
 * <p><b>不在这里过滤 {@code status}</b>：M3 的快照组装（Task 13 的 {@code ConfigSnapshotService}）
 * 必须显式带上 {@code status = 'ACTIVE'} 条件，理由是 Task 4 的报告已登记的事实 ——
 * {@code ChannelDescriptor} / {@code ModelRouteDescriptor} / {@code RatePolicy} 都**没有状态分量**，
 * 网关侧完全信任「快照里只有 ACTIVE 行」，因此漏掉那条过滤不会有任何网关用例变红，
 * 停用行会被照用。过滤点必须显式、且由容器的组装用例钉住（本 DAO 只负责读）。
 */
public interface ChannelMapper extends BaseMapper<ChannelEntity> {
}
