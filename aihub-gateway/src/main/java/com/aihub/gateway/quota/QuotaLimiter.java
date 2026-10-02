package com.aihub.gateway.quota;

import com.aihub.common.config.QuotaDescriptor;
import com.aihub.common.quota.QuotaDecision;

/**
 * 配额判定的**唯一入口**，也是「Redis 挂了怎么办」这条降级规则的唯一落点（D7）。
 *
 * <p>与 {@code RateLimiter} 的对照是本项目最值得讲的一处取舍：
 * <ul>
 *   <li><b>限流</b>：保护**上游**。Redis 挂了 → 本机令牌桶，**仍然拒绝**（宁可错杀）；</li>
 *   <li><b>配额</b>：**记账**。Redis 挂了 → **放行 + 告警**（宁可少记，也不因为记账组件坏了而拒绝
 *       付费客户）。丢失的预扣由每日对账兜底（按 {@code request_log} 重算 {@code billing_daily}）。</li>
 * </ul>
 * 因此本接口的实现**绝不允许**用「拒绝」来表达自身故障。
 *
 * <p><b>签名相对计划的偏差（已登记）</b>：计划把 {@code RedisQuotaLimiter.reserve} 写成
 * {@code reserve(long tenantId, long estimatedTokens)}，但预扣脚本
 * （{@link com.aihub.common.quota.QuotaScript#args}）需要 {@code period}（决定键与 TTL）以及
 * {@code tokenLimit}/{@code requestLimit}（来自快照的额度行）。因此这里用
 * {@link QuotaDescriptor} 把 {@code (tenantId, period, tokenLimit, requestLimit)} 一起传下去 ——
 * 它正好是 {@link QuotaResolver} 的产物。
 */
public interface QuotaLimiter {

    /**
     * 请求前预扣（一次往返、服务器端原子，见 {@link com.aihub.common.quota.QuotaScript}）。
     *
     * @param limits          本周期生效的额度行（含 {@code period} 与两个限额），来自配置快照
     * @param estimatedTokens 预估要压掉的 token（{@link QuotaEstimator#estimate}）
     * @return {@code null} = **Redis 这一级不可用**（连接失败 / 超时）：调用方按 D7 **放行**并计数。
     *         非空即一次真实判定（含 {@code allowed=false} 的拒绝）。
     *         <p>形状异常（脚本返回非 3 元素 / 元素不可解析）由
     *         {@link com.aihub.common.quota.QuotaScript#parse} 抛 {@code IllegalStateException} ——
     *         那是**缺陷不是降级**，必须让调用方单独计数（{@code aihub.quota.script_error}），
     *         绝不能被合并进「Redis 不可用」。
     */
    QuotaDecision reserve(QuotaDescriptor limits, long estimatedTokens);

    /**
     * 拿到真实用量后的实际校正：把「估算 − 真实」的差额补回桶（正 = 补扣、负 = 退回）。
     *
     * <p><b>非幂等</b>：每个请求只允许调用一次。重复调用会**双重扣账**（这正是
     * {@link QuotaReservationRegistry} 消费即移除的原因）。
     *
     * <p>校正发生在**响应回写之后**，因此它的失败绝不允许影响客户端：调用方捕获异常、只记日志
     * 与计数（02:00 的对账是兜底）。
     */
    void adjust(long tenantId, String period, long estimatedTokens, long actualTokens);
}
