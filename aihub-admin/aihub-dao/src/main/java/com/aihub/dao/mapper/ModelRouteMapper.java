package com.aihub.dao.mapper;

import com.aihub.dao.entity.ModelRouteEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code model_route} 表的 Mapper。不加自定义 SQL：M3 只需要全表读取（配置快照）。
 *
 * <p>与 {@link ChannelMapper} 同一条纪律：**状态过滤不在 DAO**，由 Task 13 的组装显式带
 * {@code status = 'ACTIVE'}（网关侧的 {@code ModelRouteDescriptor.usable()} 是第二道防线，
 * 但它读不到「这一行是否 ACTIVE」以外的信息 —— 行本身就不该进快照）。
 */
public interface ModelRouteMapper extends BaseMapper<ModelRouteEntity> {
}
