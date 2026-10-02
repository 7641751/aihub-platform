package com.aihub.admin.web.internal;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.common.quota.QuotaDecision;
import com.aihub.common.quota.QuotaKeys;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.common.quota.QuotaScript;
import com.aihub.dao.entity.QuotaEntity;
import com.aihub.dao.mapper.QuotaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 配额预扣的**兜底回源**端点（设计文档 §7.2 / Task 14 裁定 1）。
 *
 * <p>正常路径上配额判定在网关本地跑「Redis + Lua」，本端点只在「Redis 不可用 **且** 部署显式打开了兜底」
 * （{@code aihub.quota.fallback-enabled}，网关侧默认开启）时被调用。
 *
 * <p><b>它不是一条更弱的路径</b>：服务端跑的是**同一段** {@link QuotaScript#SCRIPT}（共享常量），
 * 读的是**同一张** {@code quota} 表，写的是**同一个桶键** {@link QuotaKeys#bucketKey(long, String)}
 * （周期取 {@link QuotaPeriod#of(long)}，与网关同口径的 UTC {@code YYYYMM}）。因此「Redis 故障期间
 * 客户端看到的配额规则变了」这件事不可能发生 —— 那是最难查的一类不一致（只在 Redis 故障期间出现）。
 *
 * <p><b>上限只来自 MySQL</b>：请求体里**没有** {@code tokenLimit} / {@code requestLimit} 字段，服务端读
 * {@code quota} 行。若让调用方声明上限，一把被攻破的网关就能给任何租户开出无限额度 —— 这个字段的缺席是
 * **安全属性**，不是省略。（残余风险：共享内部密钥被攻破时，调用方可以把用量记到**别的**租户头上；
 * 这属于「内部接口的信任边界」，与既有的 {@code /internal/api-keys/resolve} 同一个边界。）
 *
 * <p><b>HTTP 200 也可能是「拒绝」</b>：{@code data.allowed = false} 是**业务结果**（余额不足），不是 HTTP 错误。
 * 网关据此翻成 {@code 429 insufficient_quota}。把余额不足做成 4xx/5xx 会让「网关→admin」这条内部链的
 * 失败重试策略误判（内部调用失败是传输概念，余额不足是业务概念）。
 *
 * <p>鉴权：{@code InternalAuthFilter} 按**前缀** {@code /internal/} 守卫（无白名单），因此本端点自动受保护，
 * 不需要登记任何新路径。被签名的路径是**应用内路径** {@code /internal/quota/reserve}，METHOD 是 {@code POST}，
 * body 不参与签名（与既有内部接口同一口径）。
 */
@RestController
@RequestMapping("/internal/quota")
public class InternalQuotaController {

    private static final Logger log = LoggerFactory.getLogger(InternalQuotaController.class);

    /** {@code QuotaScript} 里「限额为 0」表达的是**该维度不限**（D15），回报的剩余量是 {@code -1}。 */
    private static final long UNLIMITED = -1L;

    /**
     * 与网关逐字一致：同一个脚本常量、同一个 {@code List.class} 结果类型。
     * admin 侧**不复写**任何判定逻辑，只做「读行 → 组装 ARGV → 翻译返回值」。
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(QuotaScript.SCRIPT, List.class);

    /** 租户级已用量读路径：与快照/展示用同一个表，控制面不写已用量。 */
    private final QuotaMapper quotaMapper;
    private final StringRedisTemplate redis;

    public InternalQuotaController(QuotaMapper quotaMapper, StringRedisTemplate redis) {
        this.quotaMapper = quotaMapper;
        this.redis = redis;
    }

    /**
     * 请求体。**刻意没有额度上限字段**（见类注释）。{@code tenantId} 是网关已鉴权的租户（匿名维度为 {@code 0}）。
     */
    public record ReserveRequest(long tenantId, long estimatedTokens) {
    }

    /**
     * 响应体。字段名与网关侧 {@code AdminClient.Http.parseReserveDecision} 读的字面键名**逐字对应**
     * （没有共享 DTO —— 那需要改 {@code aihub-common}，本任务不允许）。
     */
    public record ReserveResult(boolean allowed, long remainingTokens, long remainingRequests) {
    }

    /**
     * 预扣（一次原子 Lua 往返）。
     *
     * @throws BizException {@code INVALID_PARAM}（参数非法）/ {@code INTERNAL_ERROR}（**本端自己的** Redis 不可用
     *                      或脚本形状异常 —— 一律非 2xx，让网关按 D7 放行，绝不用「拒绝」表达自身故障）
     */
    @PostMapping("/reserve")
    public ApiResponse<ReserveResult> reserve(@RequestBody(required = false) ReserveRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "请求体必填：{\"tenantId\":<非负整数>,\"estimatedTokens\":<非负整数>}");
        }
        if (request.tenantId() < 0 || request.estimatedTokens() < 0) {
            // 负数没有语义：Lua 用 tonumber 解 ARGV，负的 est 会把已用量**减小**（等于送额度）。
            throw new BizException(ErrorCode.INVALID_PARAM, "tenantId 与 estimatedTokens 都不能为负");
        }

        // 周期与网关同口径：UTC 的 YYYYMM（QuotaPeriod.of）。兜底请求体不带 period —— 它由服务端按自己的
        // 时钟折算，避免调用方声明一个「更好」的周期（那会指向另一个桶 = 绕过配额）。
        String period = QuotaPeriod.of(System.currentTimeMillis());
        QuotaEntity row = find(request.tenantId(), period);
        if (row == null || isUnlimited(row)) {
            // 没有配额行 / 两个限额都是 0 = **不限**（D15）：直接放行，**不碰 Redis**（不限的维度连记账都不做）。
            return ApiResponse.ok(new ReserveResult(true, UNLIMITED, UNLIMITED));
        }

        long tokenLimit = Math.max(0L, nz(row.getTokenLimit()));
        long requestLimit = Math.max(0L, nz(row.getRequestLimit()));
        long ttlMillis = QuotaKeys.ttlMillis(period, System.currentTimeMillis());
        List<?> raw;
        try {
            raw = redis.execute(SCRIPT, QuotaScript.keys(request.tenantId(), period),
                    QuotaScript.args(request.estimatedTokens(), tokenLimit, requestLimit, ttlMillis)
                            .toArray(new Object[0]));
        } catch (RuntimeException e) {
            // 本端的 Redis 也不可用 ⇒ 返回「服务端不可用」（非 2xx），由网关按 D7 放行 + 计数。
            // **绝不**用 allowed=false 表达自身故障（那会让记账故障变成对客户端的 429）。
            log.warn("兜底预扣在 admin 侧也遇到 Redis 故障，返回「服务端不可用」由网关放行（D7）: {}",
                    e.toString());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "配额判定暂时不可用");
        }
        QuotaDecision decision;
        try {
            decision = QuotaScript.parse(raw);
        } catch (IllegalStateException e) {
            // 形状异常是**缺陷不是降级**：响亮失败（500 + ERROR 日志），绝不静默放行/拒绝。
            // 网关侧同一条分支会落 aihub.quota.script_error，因此这个缺陷在生产上可观测。
            log.error("兜底预扣脚本返回了非预期形状（缺陷，不是降级）: {}", e.toString());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "配额判定暂时不可用");
        }
        return ApiResponse.ok(new ReserveResult(decision.allowed(), decision.remainingTokens(),
                decision.remainingRequests()));
    }

    private QuotaEntity find(long tenantId, String period) {
        return quotaMapper.selectOne(new LambdaQueryWrapper<QuotaEntity>()
                .eq(QuotaEntity::getTenantId, tenantId)
                .eq(QuotaEntity::getPeriod, period));
    }

    /** 与 {@code QuotaResolver.limited} 同一条判据：任一维限额为正即受限（0 = 该维不限）。 */
    private static boolean isUnlimited(QuotaEntity row) {
        return nz(row.getTokenLimit()) <= 0L && nz(row.getRequestLimit()) <= 0L;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
