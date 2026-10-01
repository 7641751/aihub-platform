package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对应 Flyway V1 的 {@code quota} 表（{@code V1__init_schema.sql:77-90}）：
 * 租户的**周期预算**，唯一键 {@code uk_quota_tenant_period (tenant_id, period)}。
 *
 * <p><b>字段与 V1 的 10 列逐字对应</b>：{@code id / tenant_id / period / token_limit / token_used /
 * request_limit / request_used / version / created_at / updated_at}。其中
 * {@code tokenUsed} / {@code requestUsed} 由**数据面**（Redis 预扣 + 每日对账写回）维护，控制面
 * **不写**它们，但实体要能读出来（对账/巡检要看）。{@code version} 是控制面**手写乐观锁**的计数列
 * （本仓库不注册 {@code MybatisPlusInterceptor}，{@code @Version} 不生效 —— 见
 * {@code QuotaAdminService.update}）。
 *
 * <p>{@code period} 是 UTC 的 {@code YYYYMM}（决策 D13），用 {@link String} 映射 {@code VARCHAR(8)}。
 *
 * <p>{@code createdAt} / {@code updatedAt} 用 {@link LocalDateTime} 映射 {@code DATETIME(3)}，
 * 并由本表在**应用侧显式按 UTC 写入**（与 {@code ChannelEntity} 刻意不映射时间列的做法**不同** ——
 * 这里必须映射，因为控制面写路径要把 {@code created_at} 钉在代码里，见 CONVENTIONS §7）：
 * 不依赖库的 {@code DEFAULT CURRENT_TIMESTAMP(3)}（那写的是数据库会话时区），也不用 {@code Instant}
 * （那会被驱动按 JDBC 连接时区折算）。写入点见 {@code QuotaAdminService.getOrCreate}。
 */
@TableName("quota")
public class QuotaEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** UTC 的 {@code YYYYMM}（决策 D13）。 */
    private String period;
    /** 周期 token 预算；{@code 0} 表示**不限**（决策 D15）。控制面写。 */
    private Long tokenLimit;
    /** 周期内已用 token 估算累计；由数据面（Redis 预扣 + 对账）维护，控制面**不写**，只读。 */
    private Long tokenUsed;
    /** 周期请求数上限；{@code 0} 表示**不限**（决策 D15）。控制面写。 */
    private Long requestLimit;
    /** 周期内已用请求数；由数据面维护，控制面**不写**，只读。 */
    private Long requestUsed;
    /** 控制面手写乐观锁的计数列（每次配置更新 +1）。 */
    private Long version;
    /** UTC 墙上时间（应用侧显式写入，不依赖库默认值）。 */
    private LocalDateTime createdAt;
    /** UTC 墙上时间（应用侧显式写入）。 */
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

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public Long getTokenLimit() {
        return tokenLimit;
    }

    public void setTokenLimit(Long tokenLimit) {
        this.tokenLimit = tokenLimit;
    }

    public Long getTokenUsed() {
        return tokenUsed;
    }

    public void setTokenUsed(Long tokenUsed) {
        this.tokenUsed = tokenUsed;
    }

    public Long getRequestLimit() {
        return requestLimit;
    }

    public void setRequestLimit(Long requestLimit) {
        this.requestLimit = requestLimit;
    }

    public Long getRequestUsed() {
        return requestUsed;
    }

    public void setRequestUsed(Long requestUsed) {
        this.requestUsed = requestUsed;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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
