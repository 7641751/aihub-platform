package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 对应 Flyway V2 的 {@code audit_log} 表：M4 控制面写操作的审计承载物（决策 D1/D8）。
 *
 * <p>审计行由服务层在**业务写事务内**显式写入（{@code AuditService.record(...)}），
 * 因此这里只承载非敏感字段的变更摘要：{@code detail} 绝不放 API Key 明文、渠道明文密钥、
 * {@code api_key_cipher} 密文、主密钥或控制台口令/令牌。
 */
@TableName("audit_log")
public class AuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String actorType;   // "USER" | "SYSTEM"
    private String actor;       // sys_user.id 的字符串形式，或 "system"
    private String action;      // 见 AuditAction 常量（Task 7）
    private String targetType;  // "TENANT" | "API_KEY" | "CHANNEL" | "ROUTE" | "RATE_LIMIT" | "QUOTA"
    private String targetId;
    private String detail;      // 非敏感字段的变更摘要；**绝不放密钥/口令/密文**
    private String requestId;
    private Instant createdAt;

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

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
