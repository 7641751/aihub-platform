package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对应 Flyway V1 的 {@code rate_limit_policy} 表。
 * <p>{@code apiKeyId} 可空：为空表示**租户级**策略（该租户所有 key 的兜底）；非空表示 **key 级**策略
 * （只作用于该 {@code api_key.id}）。**两个维度在 M3 都生效**（决策 7，2026-09-26 依控制器 pre-flight
 * 评审修订）：网关侧先找 key 级、再回落租户级。字段与 V1 的列一一对应，两侧不需要各自定义 DTO。
 * （M3 组装进快照但不生效，等 M4 把数值主键接进请求上下文）。
 *
 * <p><b>V1 刻意没有唯一键</b>：{@code rate_limit_policy} 上只有 {@code KEY idx_rate_limit_tenant}
 * （决策 17）。因此同一个 {@code (tenant_id, api_key_id)} 可以存在多行，**同维度内取「最后一条」**
 * 的判定依据是读取顺序 —— 即 {@code id} 升序（见
 * {@code RateLimitPolicyMapper#selectList} 的 javadoc）。admin 组装快照时必须保持这个顺序，
 * 否则 {@code ConfigSnapshot.tenantPolicies / keyPolicies} 的「取最后一条 = 取 id 最大那条」不再成立。
 */
@TableName("rate_limit_policy")
public class RateLimitPolicyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long apiKeyId;
    private Integer qps;
    private Integer burst;
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

    public Long getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(Long apiKeyId) {
        this.apiKeyId = apiKeyId;
    }

    public Integer getQps() {
        return qps;
    }

    public void setQps(Integer qps) {
        this.qps = qps;
    }

    public Integer getBurst() {
        return burst;
    }

    public void setBurst(Integer burst) {
        this.burst = burst;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
