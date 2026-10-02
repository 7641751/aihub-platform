package com.aihub.dao.mapper;

import com.aihub.dao.entity.KbChunkEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 由 {@code MybatisMapperConfig} 的 {@code @MapperScan("com.aihub.dao.mapper")} 扫描。
 *
 * <p>读写的 {@code kb_chunk} 是文档入库流水线的逐段进度坐标（V3，决策 D1/D4）：
 * {@code (doc_id, seq)} 唯一 ⇒ 重放即覆盖；{@code doc_id} 有索引 ⇒ 按 doc 精确清理/读取走前缀。
 * 进度写入（insert / update {@code embedded_at}）与清理（delete by {@code doc_id}）都由
 * 流水线通过 {@link BaseMapper} 的既有方法完成，故此处不再另加注解方法。
 */
public interface KbChunkMapper extends BaseMapper<KbChunkEntity> {
}
