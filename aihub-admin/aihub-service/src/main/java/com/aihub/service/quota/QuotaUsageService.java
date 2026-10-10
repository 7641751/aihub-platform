package com.aihub.service.quota;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.common.quota.QuotaKeys;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.dao.entity.QuotaEntity;
import com.aihub.dao.mapper.QuotaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 配额用量的**只读**出口（{@code GET /api/quotas/usage}）。
 *
 * <h2>为什么需要它</h2>
 * 配额的真实已用量**只在 Redis 桶**（{@code aihub:quota:{tenantId}:{YYYYMM}} 的
 * {@link QuotaKeys#FIELD_TOKENS}/{@link QuotaKeys#FIELD_REQUESTS}）里：那是
 * {@code QuotaScript} 预扣与 {@code QuotaCorrector} 校正共同维护的账，也是判定"会不会被拒"所读的那个数。
 * MySQL 的 {@code quota.token_used}/{@code request_used} 是**预留但未接线的列**（全仓库唯一的
 * {@code UPDATE quota} 不写它们；每日对账按 D12 也"从不写"）⇒ {@code GET /api/quotas} 返回的
 * {@code tokenUsed} 恒为 0，运维要看用量在此之前**没有任何出口**。
 *
 * <h2>口径（随数一起返回，不许客户端猜）</h2>
 * 桶里是**预扣估算**累计（{@code QuotaEstimator} 刻意偏保守、偏大），**不是**账单口径 —— 因此
 * {@link UsageView#source()} 恒为 {@link #SOURCE_REDIS_BUCKET}、{@link UsageView#estimate()} 恒为 true。
 * 选它的理由只有一个：**所见即所拦**（与判定同一个数）。真实落账口径请用
 * {@code GET /api/billing/daily}，两者的差额由每日对账（D12）负责报告。
 *
 * <h2>三条不变量</h2>
 * <ol>
 *   <li><b>只读</b>（D12 红线）：不写 {@code quota} 表（**也不惰性物化** —— 那是
 *       {@link QuotaAdminService#getOrCreate} 的行为，本类刻意**不用**它）、不写桶、不刷新桶的 TTL；</li>
 *   <li><b>不改判</b>：本类的 {@code remaining} 与 {@code QuotaScript} 的
 *       {@code math.max(0, limit - used)}、{@code -1 = 不限} 逐字同规则 —— 两套算法迟早会漂移，
 *       而"运维看到的剩余"与"判定用的剩余"不一致是最难查的一类不一致；</li>
 *   <li><b>读不到就报错，绝不用 0 冒充</b>：Redis 不可用 ⇒ {@code INTERNAL_ERROR}；桶值畸形 ⇒
 *       {@code INTERNAL_ERROR}（缺陷不是降级，与每日对账对同一列的处理同款）。方向与网关侧配额的
 *       fail-open（D7）**相反**，因为读接口没有"放行"这个概念。</li>
 * </ol>
 *
 * <p><b>桶缺失不是错误</b>：该周期还没发生任何预扣时两个字段都读回 {@code null} ⇒ 报
 * {@code used=0} + {@link UsageView#bucketMissing()} {@code = true}（显式标记，免得"没有记录"
 * 看起来像"用了 0"）。字段被写成**空串**算"存在但为 0"，不算缺失。
 *
 * <p><b>租户维度只由调用方（控制器）从令牌里取</b>（R3.2，least privilege）：本类不提供"指定别的租户"
 * 的入口，也不做跨租户列举。
 */
@Service
public class QuotaUsageService {

    /** 用量口径：数据面预扣的 Redis 桶（与配额判定同一个数）。 */
    public static final String SOURCE_REDIS_BUCKET = "redis-bucket";

    /** {@code QuotaScript} 的"该维不限"约定：remaining 返回 {@code -1}（D15）。 */
    private static final long UNLIMITED = -1L;

    private static final Logger log = LoggerFactory.getLogger(QuotaUsageService.class);

    private final QuotaMapper quotaMapper;
    private final StringRedisTemplate redis;

    public QuotaUsageService(QuotaMapper quotaMapper, StringRedisTemplate redis) {
        this.quotaMapper = quotaMapper;
        this.redis = redis;
    }

    /**
     * 用量视图。
     *
     * <p>{@code tokenRemaining}/{@code requestRemaining} 由**服务端**算好（{@code -1} = 该维不限，D15）：
     * "0 的限额表示不限"这条语义在项目里只允许有一处定义，不许推给客户端。
     *
     * @param unlimited      两个限额都 ≤ 0（= 无配额行 / 零额度行）⇒ 该租户本周期不受额度约束
     * @param source         口径来源，恒为 {@link #SOURCE_REDIS_BUCKET}
     * @param estimate       {@code true} = 桶里是预扣**估算**而非真实 usage（恒为 true）
     * @param bucketMissing  {@code true} = 该周期**还没有任何预扣记录**（桶的两个字段都不存在）；
     *                       此时 {@code used} 全是 0，但这与"确实用了 0"是两件事
     */
    public record UsageView(long tenantId, String period, long tokenLimit, long tokenUsed, long tokenRemaining,
                            long requestLimit, long requestUsed, long requestRemaining, boolean unlimited,
                            String source, boolean estimate, boolean bucketMissing) {
    }

    /**
     * 读某个 {@code (tenantId, period)} 的用量。
     *
     * @param tenantId 已鉴权的租户（控制器从令牌取，R3.2）
     * @param period   UTC 的 {@code YYYYMM}
     * @throws BizException {@code INVALID_PARAM}（周期非法）/ {@code INTERNAL_ERROR}（Redis 不可用、
     *                      桶值畸形）
     */
    public UsageView read(long tenantId, String period) {
        requirePeriod(period);

        QuotaEntity row = quotaMapper.selectOne(new LambdaQueryWrapper<QuotaEntity>()
                .eq(QuotaEntity::getTenantId, tenantId)
                .eq(QuotaEntity::getPeriod, period));
        long tokenLimit = row == null ? 0L : nz(row.getTokenLimit());
        long requestLimit = row == null ? 0L : nz(row.getRequestLimit());

        String key = QuotaKeys.bucketKey(tenantId, period);
        List<String> values = readBucket(key);
        String rawTokens = valueAt(values, 0);
        String rawRequests = valueAt(values, 1);
        long tokenUsed = parse(rawTokens, key, QuotaKeys.FIELD_TOKENS);
        long requestUsed = parse(rawRequests, key, QuotaKeys.FIELD_REQUESTS);
        // 只有"两个字段都不存在"才算桶缺失：被写成空串算"存在但为 0"。
        boolean bucketMissing = rawTokens == null && rawRequests == null;

        return new UsageView(tenantId, period, tokenLimit, tokenUsed, remaining(tokenLimit, tokenUsed),
                requestLimit, requestUsed, remaining(requestLimit, requestUsed),
                tokenLimit <= 0L && requestLimit <= 0L, SOURCE_REDIS_BUCKET, true, bucketMissing);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 一次 {@code HMGET} 把两个字段取回（不是两次 {@code HGET}）：与 {@code QuotaScript} 读桶的方式一致，
     * 也少一次往返。
     *
     * <p><b>失败 ⇒ {@code INTERNAL_ERROR}，绝不返回 0</b>：把"读不到"报成"用了 0"会让运维以为这个租户
     * 一分钱都没花 —— 那是最坏的一种"看起来成功"。
     */
    private List<String> readBucket(String key) {
        try {
            return redis.<String, String>opsForHash()
                    .multiGet(key, List.of(QuotaKeys.FIELD_TOKENS, QuotaKeys.FIELD_REQUESTS));
        } catch (RuntimeException e) {
            log.warn("读取配额用量桶失败（Redis 不可用）: key={} {}", key, e.toString());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "配额用量暂时不可读（Redis 不可用），请稍后重试");
        }
    }

    /** 桶值 → 已用量：{@code null}/空串算 0（桶没写过或写过空串），畸形值算**缺陷**（响亮失败）。 */
    private static long parse(String raw, String key, String field) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            // 与 QuotaReconciliationService 对同一列的处理同款：桶被写坏了是缺陷，静默当 0 会把它藏起来。
            log.error("配额桶的 {} 不是整数（缺陷，不是降级）: key={} value={}", field, key, raw);
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "配额桶的 " + field + " 不是整数：" + key + " = " + raw);
        }
    }

    /**
     * 剩余量：限额为正时 {@code max(0, limit - used)}，否则 {@code -1}（该维不限）。
     * **与 {@code QuotaScript} 逐字同规则**（含"超用夹到 0"这一条）。
     */
    private static long remaining(long limit, long used) {
        return limit > 0L ? Math.max(0L, limit - used) : UNLIMITED;
    }

    /** 与 {@code QuotaAdminService.requirePeriod} 同一条语法（唯一真相源是 {@link QuotaPeriod}）。 */
    private static void requirePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "period 不能为空");
        }
        try {
            QuotaPeriod.nextPeriodStartMillis(period);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, "period 必须是 UTC 的 YYYYMM：" + period);
        }
    }

    private static String valueAt(List<String> values, int index) {
        return values == null || values.size() <= index ? null : values.get(index);
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
