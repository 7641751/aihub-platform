package com.aihub.dao.mapper;

import com.aihub.dao.entity.BillingDailyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 由 {@code MybatisMapperConfig} 的 {@code @MapperScan("com.aihub.dao.mapper")} 扫描。 */
public interface BillingDailyMapper extends BaseMapper<BillingDailyEntity> {

    /**
     * 按 {@code request_log} 重算给定自然日的每日账单行（Task 15 的对账任务）。
     *
     * <p><b>幂等</b>：唯一键 {@code uk_billing_daily(tenant_id, stat_date)} 是幂等的锚点 ——
     * {@code ON DUPLICATE KEY UPDATE} 写入的是**绝对值**（不是增量），因此同一窗口连跑两次，
     * 行数与 {@code requests}/{@code tokens} 都不变。改写成本方法的 {@code requests = requests + VALUES(requests)}
     * 这类**累加**形态即破坏幂等（会被 {@code QuotaReconciliationTest#runningTwiceIsIdempotentBecauseOfTheUniqueKey} 打红）。
     *
     * <p><b>为什么 {@code from}/{@code to} 是 {@link LocalDateTime} 而不是 {@code Instant}</b>：
     * {@code request_log.created_at} 是**无时区**的 {@code DATETIME(3)}，存的是 UTC 墙上时间。
     * 绑 {@code Instant} 会由 JDBC 驱动按**连接时区**折算窗口 —— 在非 UTC 连接上把窗口整体推走
     * （Task 11 已为此付过代价）；而本项目的测试套件跑 UTC 方言，这种错在测试里**静默通过**。
     * 因此调用方必须显式 {@code LocalDateTime.ofInstant(instant, ZoneOffset.UTC)} 折算（见
     * {@code QuotaReconciliationService#reconcile}）。
     *
     * <p><b>{@code cost} 固定写 0</b>：本里程碑没有单价表（计划「不做的事」）。
     *
     * <p><b>MySQL 8.4 的 {@code VALUES()} vs 行别名</b>：{@code VALUES()} 在 8.0.20+ 被标记为 deprecated
     * （有 deprecation warning，功能正常）；推荐的替代「行别名」对 {@code INSERT ... SELECT} 有语法限制。
     * 本方法采用在真库（MySQL 8.4 容器）上**实测可用**的形式 —— 采用哪一种见交付报告。
     *
     * <p>注意：{@code error_code = usage_missing / client_disconnected} 的行是**已知近似值**（CONVENTIONS §6.5），
     * 重算**不区分**它们 —— 因此对账的偏差计数器天然包含这部分近似数据（README 已知边界）。
     *
     * @param from 窗口下界（含，UTC 墙上时间）
     * @param to   窗口上界（**不含**，UTC 墙上时间）；通常为 {@code statDate + 1 天} 的 00:00
     * @return 受影响行数（{@code ON DUPLICATE KEY UPDATE} 下同一实现可能返回 0/1/2，那是驱动语义，不要据此判断业务事实）
     */
    @Insert("""
            INSERT INTO billing_daily (tenant_id, stat_date, requests, tokens, cost)
            SELECT tenant_id, DATE(created_at), COUNT(*), SUM(total_tokens), 0
            FROM request_log
            WHERE created_at >= #{from} AND created_at < #{to}
            GROUP BY tenant_id, DATE(created_at)
            ON DUPLICATE KEY UPDATE requests = VALUES(requests), tokens = VALUES(tokens), cost = VALUES(cost)
            """)
    int recomputeDaily(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 给定自然日的**当日**请求数合计（跨全部租户）。定向按 {@code stat_date} 查，不是全表计数。 */
    @Select("SELECT COALESCE(SUM(requests), 0) FROM billing_daily WHERE stat_date = #{statDate}")
    long sumRequestsOn(@Param("statDate") LocalDate statDate);

    /** 给定自然日的**当日** token 合计（跨全部租户）。 */
    @Select("SELECT COALESCE(SUM(tokens), 0) FROM billing_daily WHERE stat_date = #{statDate}")
    long sumTokensOn(@Param("statDate") LocalDate statDate);

    /**
     * 单个租户在 {@code [from, to]}（含两端）内的 token 合计 —— 对账「周期至今」口径的取数点。
     * 只按本租户 + 日期区间定向查。
     */
    @Select("SELECT COALESCE(SUM(tokens), 0) FROM billing_daily "
            + "WHERE tenant_id = #{tenantId} AND stat_date >= #{from} AND stat_date <= #{to}")
    long sumTokensForTenantBetween(@Param("tenantId") long tenantId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);
}
