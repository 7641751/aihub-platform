package com.aihub.dao.mapper;

import com.aihub.dao.entity.KbDocumentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** 由 {@code MybatisMapperConfig} 的 {@code @MapperScan("com.aihub.dao.mapper")} 扫描。 */
public interface KbDocumentMapper extends BaseMapper<KbDocumentEntity> {

    /**
     * 幂等「插或忽略」：命中 {@code uk_kb_document_tenant_sha} 时什么都不改，也不抛唯一键异常。
     *
     * <p>实现方式照 M4 Task 12 的定稿（决策 D5）：用 {@code INSERT ... ON DUPLICATE KEY UPDATE id = id}
     * 把「重复上传」这条**正常用户动作**变成无副作用的重放 —— **不抛异常、不 catch
     * {@code DuplicateKeyException}}**。M4 已用一次死锁实测证明：{@code catch DuplicateKeyException}
     * + {@code SELECT ... FOR UPDATE} 重读会死锁；且即便不死锁，REPEATABLE READ 下同一事务的普通重读
     * **看不见**并发提交的行。调用方随后用 {@code selectOne}（按 tenant_id + sha256）读回已存在那一行。
     *
     * <p>新插入时 {@code chunk_count} 恒为 0（进度由 {@code kb_chunk} 承载，见 V3）。
     *
     * @return 受影响行数；**调用方不得依赖它**做判据（{@code ON DUPLICATE KEY UPDATE} 在
     *         「命中并更新成同值」与「真插入」下返回值语义随驱动 {@code useAffectedRows} 变化）。
     */
    @Insert("INSERT INTO kb_document (tenant_id, filename, size_bytes, sha256, status, chunk_count) "
            + "VALUES (#{tenantId}, #{filename}, #{sizeBytes}, #{sha256}, #{status}, 0) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIfAbsent(@Param("tenantId") long tenantId, @Param("filename") String filename,
                       @Param("sizeBytes") long sizeBytes, @Param("sha256") String sha256,
                       @Param("status") String status);
}
