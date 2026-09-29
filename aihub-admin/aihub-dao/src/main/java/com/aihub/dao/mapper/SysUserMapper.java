package com.aihub.dao.mapper;

import com.aihub.dao.entity.SysUserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code sys_user} 的查询入口（M4 的登录路径按 {@code username} 查一行）。
 *
 * <p>刻意只保留 {@code BaseMapper} 的形状（brief 的契约）：唯一键 {@code uk_sys_user_username}
 * 保证 {@code selectOne} 不会撞多行，因此不需要额外的注解 SQL。
 */
public interface SysUserMapper extends BaseMapper<SysUserEntity> {
}
