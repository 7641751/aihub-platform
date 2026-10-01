package com.aihub.admin.quota;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.common.quota.QuotaDecision;
import com.aihub.common.quota.QuotaKeys;
import com.aihub.common.quota.QuotaPeriod;
import com.aihub.common.quota.QuotaScript;
import com.aihub.dao.entity.QuotaEntity;
import com.aihub.dao.mapper.QuotaMapper;
import com.aihub.service.audit.AuditService;
import com.aihub.service.quota.QuotaAdminService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * **配额预扣在真 Redis 上的证据** + 配额控制面写路径的**乐观锁**证据（真 MySQL）。
 *
 * <p>容器（{@code redis:7-alpine} / MySQL）由 {@link AbstractIntegrationTest} 单例启动，被测的脚本与调用约定
 * 就是网关将来要跑的那一份 —— {@link QuotaScript#SCRIPT} 是共享模块里的同一个常量。
 *
 * <p><b>刻意不声明 {@code @TestPropertySource}、不 {@code @Import}</b>：本类只用 Redis 与 MySQL，不需要
 * 任何合成密钥，因此与全仓「继承 {@code AbstractIntegrationTest} 且无属性集」的类**共用同一个默认上下文**
 * —— 套件的 Spring 上下文数量**不增加**。
 *
 * <p><b>每个用例用自己的 {@code tenantId}</b>：配额桶按 {@code (tenant, period)} 建，共用租户会让用例
 * 互相污染（CONVENTIONS §8 的容器共享纪律）。
 *
 * <h2>每条行为配一个可证伪的最小变异</h2>
 * <ul>
 *   <li>{@link #concurrentPreDeductionsNeverOversellTheBudget()}：把 Lua 拆成「先 HMGET、再 HSET」两次客户端
 *       往返（非原子）⇒ 本用例红且 {@code allowed > 10}（超发）。这是本任务最强的判别力来源。</li>
 *   <li>{@link #aZeroLimitMeansUnlimitedAndDoesNotBlockAnything()}（D15）：把 Lua 的
 *       {@code if tokenLimit > 0 and (tokenUsed + est) > tokenLimit} 里的 {@code tokenLimit > 0} 去掉
 *       （把 {@code 0} 当成「额度为零」）⇒ 本用例红 —— 那正是「M4 上线瞬间全员 429」的入口。</li>
 *   <li>{@link #aReplyOfTheWrongShapeIsRejectedInsteadOfSilentlyAccepted()}：把 {@link QuotaScript#parse} 的
 *       3 元素校验删掉 ⇒ 越界访问抛 {@code IndexOutOfBoundsException} 而不是 {@code IllegalStateException}
 *       （它会被网关当成「Redis 不可用」而降级）⇒ 本用例红。</li>
 *   <li>{@link #anUpdateOnAStaleVersionConflictsInsteadOfSilentlyOverwriting()}：把手写乐观锁 UPDATE 的
 *       {@code AND version = ?} 去掉（静默覆盖）⇒ 本用例红（两个更新都成功）。</li>
 * </ul>
 *
 * <p><b>并发用例一律用有界等待</b>（CONVENTIONS §8）：裸 {@code Future.get()} 在脚本挂死时会让用例
 * **永不返回**（Maven 超时，比红更糟），因此这里 {@code get(30, SECONDS)}，等不到就带原因变红。
 */
class QuotaPreDeductionIntegrationTest extends AbstractIntegrationTest {

    /** 与网关调用约定逐字一致：同一个脚本常量、同一个 {@code List.class} 结果类型。 */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(QuotaScript.SCRIPT, List.class);

    /** 控制面写路径的操作者（操作用例里的一处合成 actor，不涉及任何真实账号）。 */
    private static final AuditService.Actor ACTOR = new AuditService.Actor("USER", "1");

    /** 有界等待上限：脚本/线程挂死时 30 秒内带原因变红，绝不让用例永不返回。 */
    private static final long WAIT_SECONDS = 30L;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private QuotaAdminService quotaAdminService;

    @Autowired
    private QuotaMapper quotaMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------------ 预扣：原子性 / D15 / 解析契约

    /**
     * **原子性的可证伪证据**：预算 1000、每次估 100、50 个任务并发（16 线程）⇒ 恰好放行 **10** 次。
     * 预扣是「读已用 → 判定 → 写回」的读改写，Lua 在 Redis 里单线程原子执行；拆成两次客户端往返
     * 就会让多个请求读到同一个已用量、各自判定成功，于是超发（{@code allowed > 10}）。
     * 服务端桶值（第二本独立的账）也必须恰好是 1000。
     */
    @Test
    void concurrentPreDeductionsNeverOversellTheBudget() throws Exception {
        long tenant = 900_101L;
        String period = QuotaPeriod.of(Instant.now().toEpochMilli());
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<QuotaDecision>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return reserve(tenant, period, 100L, 1_000L);
                }));
            }
            start.countDown();
            long allowed = 0;
            for (Future<QuotaDecision> future : futures) {
                if (await(future).allowed()) {
                    allowed++;
                }
            }
            assertThat(allowed).as("Lua 必须原子：预扣的总量不可能超过预算").isEqualTo(10L);
        } finally {
            pool.shutdownNow();
        }

        assertThat(redis.opsForHash().get(QuotaKeys.bucketKey(tenant, period), QuotaKeys.FIELD_TOKENS))
                .as("服务端计数器 = 恰好 10 次 × 100").isEqualTo("1000");
    }

    /** D15：{@code 0} 的限额表示**不限**（M4 上线前所有租户都没有配额行，把 {@code 0} 当零会让全员 429）。 */
    @Test
    void aZeroLimitMeansUnlimitedAndDoesNotBlockAnything() {
        long tenant = 900_102L;
        String period = QuotaPeriod.of(Instant.now().toEpochMilli());
        QuotaDecision decision = reserve(tenant, period, 999_999L, 0L);
        assertThat(decision.allowed()).as("0 的限额 = 不限，一笔都不该被拒").isTrue();
        assertThat(decision.remainingTokens()).as("-1 表示该维度不限").isEqualTo(-1L);
    }

    /** 正数限额逐维判定，并且回报剩余额度（剩下的账要能被网关用来做展示与校正）。 */
    @Test
    void aPositiveLimitIsEnforcedAndReportsTheRemainingBudget() {
        long tenant = 900_103L;
        String period = QuotaPeriod.of(Instant.now().toEpochMilli());
        assertThat(reserve(tenant, period, 250L, 300L).allowed()).isTrue();
        QuotaDecision denied = reserve(tenant, period, 250L, 300L);
        assertThat(denied.allowed()).as("已用 250，再要 250 ⇒ 250+250 > 300 ⇒ 拒").isFalse();
        assertThat(denied.remainingTokens()).isEqualTo(50L);
        assertThat(reserve(tenant, period, 50L, 300L).allowed()).as("恰好用满仍然允许").isTrue();
        assertThat(reserve(tenant, period, 1L, 300L).allowed()).as("再要 1 个就超了").isFalse();
    }

    /** 真 Redis 的返回值确实能被 {@link QuotaScript#parse} 折成契约里的三元素判定。 */
    @Test
    void theRealRedisReplyParsesIntoThePinnedDecision() {
        long tenant = 900_104L;
        String period = QuotaPeriod.of(Instant.now().toEpochMilli());
        List<?> raw = executeScript(tenant, period, 100L, 1_000L, 0L);
        assertThat(raw).as("恰好 3 个：{allowed, remainingTokens, remainingRequests}").hasSize(3);
        QuotaDecision decision = QuotaScript.parse(raw);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingTokens()).isEqualTo(900L);
        assertThat(decision.remainingRequests()).as("requestLimit=0 ⇒ 不限 ⇒ -1").isEqualTo(-1L);
    }

    /**
     * 返回形状不对必须**响亮失败**（{@code IllegalStateException}），不能被当成「Redis 不可用」而静默降级
     * —— 那是「脚本写错了永远藏在 Redis 挂了后面」的入口（E.4-(a)）。
     */
    @Test
    void aReplyOfTheWrongShapeIsRejectedInsteadOfSilentlyAccepted() {
        assertThatThrownBy(() -> QuotaScript.parse(List.of(1L, 2L)))
                .as("2 个元素 ⇒ 不是契约形状").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> QuotaScript.parse(List.of()))
                .as("0 个元素 ⇒ 不是契约形状").isInstanceOf(IllegalStateException.class);
        assertThat(QuotaScript.parse(List.of(1L, 2L, 3L)).allowed())
                .as("3 个元素的合法形状仍然解析成功").isTrue();
    }

    // ------------------------------------------------------------------ 控制面：乐观锁 / 上界

    /**
     * **手写乐观锁的可证伪证据**（本仓库不注册 {@code MybatisPlusInterceptor}，{@code @Version} 不生效）：
     * 两个「各自基于同一 {@code version}」的更新并发执行 ⇒ **恰好一个成功**，另一个抛 {@link BizException}
     * （**绝不静默覆盖**）。
     *
     * <p><b>为什么这样写才是确定性的</b>：每个线程在自己的事务里**先读一次**（固定 REPEATABLE READ 快照到
     * {@code version=0}），再用 {@link CyclicBarrier} 保证两个线程都读完才各自发 UPDATE。MySQL 的
     * {@code UPDATE ... WHERE version = 0} 是**加锁读**（看的是最新已提交版本，不是快照），因此后到的那个
     * 一定匹配不到 {@code version=0} ⇒ 受影响行数 0。两个线程谁先拿到行锁都一样：只有一个能把
     * {@code version} 从 0 推到 1。
     */
    @Test
    void anUpdateOnAStaleVersionConflictsInsteadOfSilentlyOverwriting() throws Exception {
        long tenant = 900_105L;
        String period = QuotaPeriod.of(Instant.now().toEpochMilli());
        quotaAdminService.getOrCreate(tenant, period); // 初始 version = 0

        CyclicBarrier bothReadTheSameVersion = new CyclicBarrier(2);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean first;
        boolean second;
        try {
            Future<Boolean> a = pool.submit(updateAttempt(tx, tenant, period, bothReadTheSameVersion, 11L, 22L));
            Future<Boolean> b = pool.submit(updateAttempt(tx, tenant, period, bothReadTheSameVersion, 33L, 44L));
            first = await(a);
            second = await(b);
        } finally {
            pool.shutdownNow();
        }

        assertThat((first ? 1 : 0) + (second ? 1 : 0))
                .as("两个基于同一 version 的更新只能有一个成功").isEqualTo(1);

        QuotaEntity row = findRow(tenant, period);
        assertThat(row.getVersion()).as("恰好推进一次版本").isEqualTo(1L);
        assertThat(List.of(List.of(11L, 22L), List.of(33L, 44L)))
                .as("失败方绝不能静默覆盖：落库的是某一个赢家的**完整**取值对")
                .contains(List.of(row.getTokenLimit(), row.getRequestLimit()));
    }

    /** 上界（F4）：{@code > 2^53} 与负数都必须被 {@code INVALID_PARAM} 拒绝，绝不落到 Lua 里。 */
    @Test
    void anUpdateBeyondTwoPow53OrNegativeIsRejectedAsInvalidParam() {
        long tenant = 900_106L;
        String period = QuotaPeriod.of(Instant.now().toEpochMilli());
        quotaAdminService.getOrCreate(tenant, period);

        assertThatThrownBy(() -> quotaAdminService.update(tenant, period, 9_007_199_254_740_993L, 0L, ACTOR))
                .as("超过 2^53 会被 Lua 的 double 丢精度")
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode()).isEqualTo(ErrorCode.INVALID_PARAM);
        assertThatThrownBy(() -> quotaAdminService.update(tenant, period, -1L, 0L, ACTOR))
                .as("负数限额没有语义（Lua 会把它当「不限」），必须显式拒绝")
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode()).isEqualTo(ErrorCode.INVALID_PARAM);
    }

    // ------------------------------------------------------------------ 内部

    /** 一个「在同一快照版本上做更新」的尝试：成功返回 {@code true}，乐观锁冲突返回 {@code false}。 */
    private Callable<Boolean> updateAttempt(TransactionTemplate tx, long tenant, String period,
                                            CyclicBarrier barrier, long tokenLimit, long requestLimit) {
        return () -> {
            try {
                tx.execute(status -> {
                    // 事务里的**第一次**读把 REPEATABLE READ 快照钉在 version=0（后续 service 内部再读也看它）。
                    findRow(tenant, period);
                    awaitBothReadTheSameVersion(barrier);
                    quotaAdminService.update(tenant, period, tokenLimit, requestLimit, ACTOR);
                    return null;
                });
                return Boolean.TRUE;
            } catch (BizException conflict) {
                return Boolean.FALSE;
            }
        };
    }

    private static void awaitBothReadTheSameVersion(CyclicBarrier barrier) {
        try {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发更新线程时被中断", e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("两个更新线程没有在 " + WAIT_SECONDS + " 秒内都读到同一版本", e);
        }
    }

    /** 有界等待：等不到、抛异常、被中断都带原因变红，**绝不**裸 {@code get()}。 */
    private static <T> T await(Future<T> future) {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("并发调用在 " + WAIT_SECONDS + " 秒内没有返回（脚本/事务挂死？）", e);
        } catch (ExecutionException e) {
            throw new AssertionError("并发调用抛出了异常: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待并发调用结果时被中断", e);
        }
    }

    private QuotaEntity findRow(long tenantId, String period) {
        return quotaMapper.selectOne(new LambdaQueryWrapper<QuotaEntity>()
                .eq(QuotaEntity::getTenantId, tenantId)
                .eq(QuotaEntity::getPeriod, period));
    }

    /**
     * 一次预扣调用（{@code requestLimit = 0} ⇒ 请求维不限）。周期由调用方传入，这样断言用的桶键与
     * 调用用的桶键逐字一致（避免跨月边界上的偶然不一致）。
     */
    private QuotaDecision reserve(long tenantId, String period, long estimatedTokens, long tokenLimit) {
        return QuotaScript.parse(executeScript(tenantId, period, estimatedTokens, tokenLimit, 0L));
    }

    private List<?> executeScript(long tenantId, String period, long estimatedTokens, long tokenLimit,
                                  long requestLimit) {
        long now = Instant.now().toEpochMilli();
        List<String> argv = QuotaScript.args(estimatedTokens, tokenLimit, requestLimit,
                QuotaKeys.ttlMillis(period, now));
        // ⚠️ 必须把 ARGV **展开**成 varargs：直接把 List 当单个参数传会让整张表变成一个 ARGV。
        return redis.execute(SCRIPT, QuotaScript.keys(tenantId, period), argv.toArray(new Object[0]));
    }
}
