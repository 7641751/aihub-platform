package com.aihub.gateway.quota;

import com.aihub.common.quota.QuotaDecision;
import com.aihub.gateway.admin.AdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * 配额兜底的**窄接口**：Redis 不可用时，把「预扣」回源到 admin 的
 * {@code POST /internal/quota/reserve}（HMAC 内部跳）。
 *
 * <p><b>为什么要这一层</b>：{@link QuotaFilter} 需要的是「给定租户 + 预估量，给我一个判定（或告诉我拿不到）」，
 * 它**不该**知道 {@link AdminClient} 的 HTTP 细节（路径、签名、超时、信封解析）—— 那些细节会随内部契约
 * 变化，而过滤器的降级规则（D7）不随它们变。因此这里只留一个方法：{@link #reserveFallback}。
 * 真实 HTTP 仍由 {@code AdminClient.Http} 执行，E2E 用 {@code FakeAdminServer} 证明「真 HTTP + 真 HMAC 签名」。
 *
 * <p><b>返回 {@code Optional.empty()} 的语义是「拿不到判定」</b>（未配兜底 / admin 不可达 / 超时 / 响应畸形），
 * 调用方据此走 D7 **放行**。**绝不允许**把它当成「拒绝」：兜底是一次可用性增强，不是新的可用性单点。
 *
 * <p><b>回源上限只来自服务端</b>：本接口只传「租户 + 预估要压多少」，额度上限由 admin 从 {@code quota}
 * 表读取。若让调用方声明上限，一把被攻破的网关就能给任何租户开出无限额度 —— 这个字段的缺席是**安全属性**。
 *
 * <p><b>阻塞调用提醒</b>：{@link #admin} 返回的实现在内部 {@code block}，因此**必须在
 * {@code boundedElastic} 上被调用**（{@link QuotaFilter} 已经这么做了）。
 */
@FunctionalInterface
public interface QuotaFallback {

    /**
     * 单次兜底调用的**有界等待**（毫秒）。
     *
     * <p>刻意**严格大于** {@code AdminClientConfig} 给内部跳的 {@code responseTimeout(3s)}：
     * 正常情况下是 WebClient 的 3 秒先生效（那是传输层超时，会把这次回源判成故障并 fail-open），
     * 本预算只是**第二道**兜底 —— 万一将来有人去掉 WebClient 的 responseTimeout，这里仍然有界，
     * 不会把 {@code boundedElastic} 的一个线程永久占住。两个值相等会让「谁先到期」由时序抖动决定，
     * 因此不取 3 秒。
     */
    long DEFAULT_TIMEOUT_MILLIS = 4_000L;

    /**
     * 试一次兜底预扣。
     *
     * @param tenantId        已通过鉴权的租户（匿名维度为 {@code 0}）
     * @param estimatedTokens 本次请求预估要压掉的 token（与正常路径同一口径）
     * @return 一个真实判定（含 {@code allowed=false} 的拒绝），或 {@code empty} = 「拿不到」（调用方放行）
     */
    Optional<QuotaDecision> reserveFallback(long tenantId, long estimatedTokens);

    /** 兜底未开启（{@code aihub.quota.fallback-enabled=false}）：永不回源，直接告诉调用方「拿不到」。 */
    static QuotaFallback disabled() {
        return (tenantId, estimatedTokens) -> Optional.empty();
    }

    /** 回源 admin 的实现（真实 HTTP + HMAC 签名由 {@link AdminClient#reserveQuota} 执行）。 */
    static QuotaFallback admin(AdminClient adminClient, long timeoutMillis) {
        return new AdminBacked(adminClient, timeoutMillis);
    }

    /** 真实现：把 {@code QuotaDecision} 的 Mono 有界地 block 出来，任何失败都折算成 {@code empty}。 */
    final class AdminBacked implements QuotaFallback {

        private static final Logger log = LoggerFactory.getLogger(AdminBacked.class);

        private final AdminClient adminClient;
        private final long timeoutMillis;

        AdminBacked(AdminClient adminClient, long timeoutMillis) {
            this.adminClient = adminClient;
            this.timeoutMillis = timeoutMillis;
        }

        @Override
        public Optional<QuotaDecision> reserveFallback(long tenantId, long estimatedTokens) {
            try {
                // 空 Mono（未配兜底 / 非 2xx / 响应畸形）⇒ blockOptional 返回 empty ⇒ 调用方放行。
                return adminClient.reserveQuota(tenantId, estimatedTokens)
                        .blockOptional(Duration.ofMillis(timeoutMillis));
            } catch (RuntimeException e) {
                // 传输故障 / 超时 / 签名失败：与「拿不到判定」同一条路（fail-open）。绝不升级成业务故障。
                log.warn("配额兜底回源失败（admin 不可达 / 超时），本次放行（D7）: {}", e.toString());
                return Optional.empty();
            }
        }
    }
}
