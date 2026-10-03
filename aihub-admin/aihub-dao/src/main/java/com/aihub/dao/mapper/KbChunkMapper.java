package com.aihub.dao.mapper;

import com.aihub.dao.entity.KbChunkEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

/**
 * 由 {@code MybatisMapperConfig} 的 {@code @MapperScan("com.aihub.dao.mapper")} 扫描。
 *
 * <p>读写的 {@code kb_chunk} 是文档入库流水线的逐段进度坐标（V3，决策 D1/D4）：
 * {@code (doc_id, seq)} 唯一 ⇒ 重放即覆盖；{@code doc_id} 有索引 ⇒ 按 doc 精确清理/读取走前缀。
 * 读取（按 doc 取/计数）与清理（delete by {@code doc_id}）走 {@link BaseMapper} 的既有方法；
 * 但**写入必须是 upsert**（见下），光靠 {@code insert} 做不到"重放不产生重复段"。
 */
public interface KbChunkMapper extends BaseMapper<KbChunkEntity> {

    /**
     * 幂等写入一段：命中 {@code uk_kb_chunk_doc_seq(doc_id, seq)} 即**覆盖**，不抛唯一键异常。
     *
     * <p>"重放同一条 parse 消息不产生重复段"（Task 4 的验收之一）**必须**靠它 ——
     * 唯一键 {@code uk_kb_chunk_doc_seq} 已存在于 V3，所以这条路成立。
     *
     * <p><b>为什么用 {@code VALUES()} 而不是行别名 {@code AS new}</b>：MySQL 8.4 上行别名
     * （{@code INSERT … AS new … ON DUPLICATE KEY UPDATE col = new.col}）会报 <b>1064 语法错误</b>
     * （M4 Task 15 已实测，见 CONVENTIONS §8）⇒ 必须用经典 {@code VALUES(col)} 形式。
     *
     * <p><b>刻意不更新 {@code embedded_at}</b>：只有 {@code PENDING|PARSING} 的行才会走到这里
     * （Task 4 的条件迁移），此时尚未嵌入；而 {@code READY}/{@code EMBEDDING} 的重放会被状态守卫挡住，
     * 所以这里保留 {@code embedded_at} 不会造成"旧向量冒充新文本"。
     *
     * @return 受影响行数；**调用方不得依赖它**（{@code ON DUPLICATE KEY UPDATE} 的返回语义随驱动
     *         {@code useAffectedRows} 变化）
     */
    @Insert("INSERT INTO kb_chunk (doc_id, seq, text, vector_id, created_at) "
            + "VALUES (#{docId}, #{seq}, #{text}, #{vectorId}, #{createdAt}) "
            + "ON DUPLICATE KEY UPDATE text = VALUES(text), vector_id = VALUES(vector_id), "
            + "created_at = VALUES(created_at)")
    int upsert(KbChunkEntity chunk);
}
