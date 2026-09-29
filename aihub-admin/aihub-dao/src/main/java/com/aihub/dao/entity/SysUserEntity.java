package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对应 Flyway V1 的 {@code sys_user} 表（M4 首次使用）：控制台账号。
 *
 * <p>字段与列同名；{@code created_at}/{@code updated_at} 刻意不映射（与既有 {@code ApiKeyEntity}
 * 同一手法）：它们只由数据库默认值维护，读路径不需要它们。
 *
 * <p><b>{@code passwordHash} 只存 bcrypt 哈希</b>（{@code VARCHAR(72)} 恰好容纳 bcrypt 的 60 字符，
 * 见决策 D2）。明文口令既不进这个类以外的任何地方，也不进日志/审计/响应体。
 *
 * <p>{@code role} 是 {@code /api/**} 授权的**唯一来源**（D10）：只有 {@code ADMIN}/{@code VIEWER}
 * 被认，其余一律 fail-closed（由 {@code ConsoleAuthFilter} 判，不在这里判 —— 本类是纯数据）。
 */
@TableName("sys_user")
public class SysUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String username;
    private String passwordHash;
    private String role;
    private String status;

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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
