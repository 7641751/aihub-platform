package com.aihub.dao.mapper;

import com.aihub.dao.entity.ConfigVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 由 {@code MybatisMapperConfig} 的 {@code @MapperScan("com.aihub.dao.mapper")} 扫描。
 *
 * <p>读写 {@code config_version} 的单行水位（{@code id = 1}，由 V2 预先插入）。
 * {@link #raiseTo(long)} 保证最低水位，{@link #advance(long)} 为每次业务写入分配递增版本。
 *
 * <p>{@link #raiseTo(long)} 的返回值<b>没有任何调用方依赖</b>：MySQL 的
 * {@code ON DUPLICATE KEY UPDATE} 在「更新成相同值」时返回 0，而 Connector/J 默认
 * {@code useAffectedRows=false}（即设了 {@code CLIENT_FOUND_ROWS}）时返回 1 —— 同一个实现
 * 可能给出 0、1 或 2。保留 {@code int} 只为将来做观测，任何地方都不许把它当判据。
 */
public interface ConfigVersionMapper extends BaseMapper<ConfigVersionEntity> {

    @Select("SELECT version FROM config_version WHERE id = 1")
    Long current();

    @Insert("INSERT INTO config_version (id, version) VALUES (1, #{version}) "
            + "ON DUPLICATE KEY UPDATE version = GREATEST(version, #{version})")
    int raiseTo(@Param("version") long version);

    /** 分配新版本。调用方在同一写事务内通过 {@link #current()} 读回分配值。 */
    @Insert("INSERT INTO config_version (id, version) VALUES (1, #{candidate}) "
            + "ON DUPLICATE KEY UPDATE version = GREATEST(version + 1, #{candidate})")
    int advance(@Param("candidate") long candidate);
}
