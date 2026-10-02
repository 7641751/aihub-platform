package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对应 Flyway V1 的 {@code kb_document}（M5 Task 1 只做实体，**不改表**）。
 *
 * <p><b>列与字段一一对应，以 V1 的 SQL 为准</b>（{@code V1__init_schema.sql:135-149}）：
 * {@code id / tenant_id / filename / size_bytes / sha256 / status / chunk_count / error_msg /
 * created_at / updated_at}。
 * ⚠️ <b>设计文档 §5.2 写的 {@code size} / {@code uploaded_at} 是错的</b>：实际列是
 * {@code size_bytes} / {@code created_at}。字段名到列名的下划线映射由 MyBatis-Plus 的默认策略
 * 完成（{@code sizeBytes -> size_bytes}、{@code createdAt -> created_at}），与既有
 * {@code RequestLogEntity} / {@code ChannelEntity} 的写法一致（无 Lombok、显式 getter/setter）。
 *
 * <p>{@code status} 的取值见 {@code com.aihub.common.kb.KbStatus}（裸字符串常量）。
 *
 * <p>{@code createdAt} / {@code updatedAt} 用 {@link LocalDateTime} 而不是 {@code Instant}：
 * {@code datetime(3)} 列在应用侧显式按 **UTC** 写入（CONVENTIONS §7），
 * {@code LocalDateTime} + 显式 UTC 赋值读写对称，与 JVM 默认时区、JDBC 驱动的时区推导都无关。
 */
@TableName("kb_document")
public class KbDocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String filename;
    private Long sizeBytes;
    private String sha256;
    private String status;
    private Integer chunkCount;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
