package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 对应 Flyway V1 的 {@code billing_daily} 表：每日对账任务（Task 15）按 {@code request_log} 重算出来的
 * 日粒度账单行，键 {@code uk_billing_daily(tenant_id, stat_date)}。
 *
 * <p><b>本任务（Task 11）只读不写</b>：{@code /api/billing/daily} 是只读的运营查询。写入方是
 * 每日 02:00 的对账任务（本里程碑尚未落地）—— 所以今天该表在真实环境里通常是空的；查询端点照样
 * 必须存在（它是 §12 的 M4 交付物之一，且计费单价本身登记为「只写 0」，见计划 Global Constraints）。
 *
 * <p><b>为什么 {@code statDate} 用 {@link LocalDate}</b>：{@code stat_date} 是 SQL {@code DATE}
 * （**不是** {@code DATETIME(3)}）—— 它是自然日、不是瞬时，因此既不需要、也不应该做 UTC 折算
 * （CONVENTIONS §7 说的是 {@code datetime(3)} 那些列）。{@code cost} 用 {@link BigDecimal}
 * 映射 {@code DECIMAL(18,6)}：绝不能用 {@code double}（金额的二进制浮点误差会在对账里静默累积）。
 *
 * <p>刻意**不**映射 {@code created_at} / {@code updated_at}（与 {@code ChannelEntity} 同款：
 * 那两列的库默认值已经是对的，多映射只会让「INSERT 时到底写了什么」变难推理）。
 */
@TableName("billing_daily")
public class BillingDailyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private LocalDate statDate;
    private Long requests;
    private Long tokens;
    private BigDecimal cost;

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

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public Long getRequests() {
        return requests;
    }

    public void setRequests(Long requests) {
        this.requests = requests;
    }

    public Long getTokens() {
        return tokens;
    }

    public void setTokens(Long tokens) {
        this.tokens = tokens;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public void setCost(BigDecimal cost) {
        this.cost = cost;
    }
}
