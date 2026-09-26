package com.aihub.admin.ratelimit;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.ratelimit.RateLimitScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * **Lua 令牌桶在真 Redis 上的证据**：容器（{@code redis:7-alpine}）由 {@link AbstractIntegrationTest}
 * 单例启动，被测的脚本与调用约定就是网关跑的那一份 —— {@link RateLimitScript#SCRIPT} 是共享模块里的
 * 同一个常量，网关的 {@code LuaTokenBucket.SCRIPT} 直接就是它。
 *
 * <h2>为什么放在 admin 侧</h2>
 * 网关的测试**明令禁止依赖 Docker**：{@code RedisRateLimiterTest} 的类注释把「脚本真的在 Redis 里
 * 原子地补充/拒绝」这条行为证据明确委托给了本类。本模块（`aihub-web`）是仓库里唯一带
 * Testcontainers 的模块。
 *
 * <h2>「判定来自 Redis」是怎么被钉住的</h2>
 * 模块图上有一条硬约束：`aihub-web` 的测试**看不到** {@code com.aihub.gateway.ratelimit} 包
 * （`aihub-web` 只依赖 common/dao/service/mq；根 pom 里网关也不是它的依赖），因此本类无法直接调用
 * {@code RedisRateLimiter.tryConsume}、也无法断言 {@code RateLimitDecision.source()} ——
 * 而派单又禁止为此添加依赖。于是这里改用**前提与结论的分解**，两侧相乘才等于
 * 「真容器上 {@code source() == REDIS}」：
 * <ul>
 *   <li>（网关侧，无 Docker）{@code RedisRateLimiterTest} 钉住两件事：脚本对象用的是共享常量与
 *       {@code List.class} 结果类型、ARGV 向量是 {@code {now, qps, burst, ttlMillis}}；
 *       并且**恰好 3 个 {@code Long}** 的返回值会被判成 {@code Source.REDIS}；</li>
 *   <li>（本类，真 Redis）{@link #redisReplyMatchesTheReturnContractThatYieldsSourceRedis} 钉住：
 *       同一个脚本、同一个 {@code List.class}、同一形状的 ARGV 在真容器上执行时，返回值非 null、
 *       恰好 3 个元素、元素的**运行时类型是 {@code java.lang.Long}** —— 于是生产代码的准入闸门
 *       （{@code result == null || result.size() != 3}）与 {@code long allowed = result.get(0)}
 *       的拆箱都不可能失败。</li>
 * </ul>
 * 这道分解要防的正是「返回类型不匹配**不会响亮地失败**」：元素若在运行时是 {@code Integer}，
 * {@code long allowed = result.get(0)} 会抛 {@code ClassCastException}，被生产代码 catch 成
 * 「Redis 不可用」→ 从此永远走本机桶，只留下一条 WARN。
 * {@link #theContractAssertionRejectsRealRedisRepliesOfTheWrongShape} 用真 Redis 返回的两种错形状
 * 反证上面那道断言不是恒真的装饰。
 *
 * <h2>原子性 / 键布局 / 服务端补充</h2>
 * <ul>
 *   <li>{@link #concurrentRequestsNeverExceedBurst}：20 线程 × 20 次打同一个 key，{@code burst=25}、
 *       {@code qps=0}，且 400 次调用共用**同一个调用方时间戳**（`elapsed` 恒为 0，与墙钟和线程交错
 *       都无关）⇒ 恰好放行 25 次；服务端 {@code t} 也被恰好扣到 0（两本独立的账互相交叉验证）。
 *       把「补充 + 判定 + 扣减」拆成多次客户端往返（HGET → 判定 → HSET）会显著超发。</li>
 *   <li>{@link #bucketKeyIsAHashWithTheDocumentedFieldsAndTtl}：键在真 Redis 里是 **Hash**、字段恰好是
 *       契约里的 {@code t}/{@code k}、并且每次调用都重新 {@code PEXPIRE}。</li>
 *   <li>{@link #refillIsComputedServerSideFromTheCallerSuppliedClock}：只把**时钟参数**往前推，
 *       耗尽（服务器端 {@code t=0}）的桶就重新放行，且补充结果由脚本落盘 —— 补充是 Redis 算的，
 *       不是 Java 纯算术镜像算的。</li>
 * </ul>
 *
 * <p>所有用例都不 sleep：时间永远是参数（计划决策 9）。TTL 类断言除外 —— {@code PEXPIRE} 由 Redis
 * 自己的墙钟驱动，那是被测行为本身。
 */
class RedisTokenBucketIntegrationTest extends AbstractIntegrationTest {

    /**
     * 固定时钟。所有调用都用这一个值 ⇒ 脚本里的 {@code elapsed = now - lastRefill} 恒为 0，
     * 于是「qps=0 时恰好放行 burst 次」这类断言与墙钟、与线程交错都无关（可复现）。
     */
    private static final long FIXED_NOW = 1_700_000_000_000L;

    private static final String BUCKET = RateLimitScript.KEY_PREFIX + "test:integration";

    private static final String OTHER_BUCKET = RateLimitScript.KEY_PREFIX + "test:other";

    private static final String WARMUP_BUCKET = RateLimitScript.KEY_PREFIX + "test:warmup";

    /** 与 {@code RedisRateLimiter} 逐字相同的调用约定：同一个脚本常量、同一个 {@code List.class}。 */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(RateLimitScript.SCRIPT, List.class);

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void resetBuckets() {
        redis.delete(List.of(BUCKET, OTHER_BUCKET, WARMUP_BUCKET));
        // 预热脚本：首次 EVALSHA 需要回退到 EVAL 把脚本装进 Redis。把它挪出并发用例，
        // 免得 20 个线程一起踩首次装载（那不是原子性用例要证的东西）。
        eval(WARMUP_BUCKET, FIXED_NOW, 0, 1);
        redis.delete(List.of(WARMUP_BUCKET));
    }

    /** ARGV 与 {@code RedisRateLimiter} 完全相同：{@code {nowMillis, qps, burst, ttlMillis}}。 */
    @SuppressWarnings("unchecked")
    private List<Long> eval(String key, long nowMillis, int qps, int burst) {
        return redis.execute(SCRIPT, List.of(key),
                String.valueOf(nowMillis),
                String.valueOf(qps),
                String.valueOf(burst),
                String.valueOf(RateLimitScript.idleTtlMillis(qps, burst)));
    }

    // ------------------------------------------------------------------ 返回值契约

    /**
     * **证明判定来自 Redis 的那个用例。** 见类注释：本类看不到 {@code RateLimitDecision}，
     * 因此这里断言的是 {@code RedisRateLimiter} 那条唯一产出 {@code Source.REDIS} 的分支的
     * **全部前提**，加上只有 Redis 路径才可能留下的服务端痕迹。
     */
    @Test
    void redisReplyMatchesTheReturnContractThatYieldsSourceRedis() {
        List<Long> reply = eval(BUCKET, FIXED_NOW, 10, 20);

        // ① 提交给真 Redis 的就是网关跑的那份脚本（共享常量，没有第二份手抄的文本）。
        assertThat(SCRIPT.getScriptAsString()).isEqualTo(RateLimitScript.SCRIPT);

        // ② 真返回值必须满足生产代码的准入前提（null / 元素个数 / 元素运行时类型）。
        assertReplyIsTheRedisRateLimiterContract(reply);

        // ③ 逐字照抄 RedisRateLimiter 的拆箱：元素若不是 Long，这一行就是 ClassCastException。
        long allowed = reply.get(0);
        int remaining = (int) Math.max(0L, reply.get(1));
        long retryAfter = Math.max(0L, reply.get(2));

        assertThat(allowed).as("allowed 只能是 0/1；生产代码的两个分支都标 Source.REDIS").isIn(0L, 1L);
        assertThat(remaining).isEqualTo(19);
        assertThat(retryAfter).isZero();

        // ④ 这次判定必须在服务端留下痕迹：本机降级桶（local 路径）从不写 Redis。
        assertThat(redis.hasKey(BUCKET)).as("判定确实发生在 Redis 上，而不是本机降级桶").isTrue();
        assertThat(tokens()).as("服务端计数器 = burst*1000 - 1000").isEqualTo("19000");
        assertThat(lastRefill()).isEqualTo(String.valueOf(FIXED_NOW));
    }

    /**
     * 反证：上面那道契约断言**会失败**。两种形状都来自真 Redis（不是构造出来的 Java 对象），
     * 正好对应生产代码的两处准入 —— 元素拆箱与 {@code size() != 3}。
     */
    @Test
    void theContractAssertionRejectsRealRedisRepliesOfTheWrongShape() {
        @SuppressWarnings("rawtypes")
        RedisScript<List> threeStrings = RedisScript.of("return {'a','b','c'}", List.class);
        List<?> strings = redis.execute(threeStrings, List.of(BUCKET));

        assertThat(strings).as("真 Redis 确实会返回 size==3 但元素类型不对的表").hasSize(3);
        assertThat(strings.get(0)).as("StringRedisTemplate 把 Lua 字符串解成 String").isInstanceOf(String.class);
        assertThatThrownBy(() -> assertReplyIsTheRedisRateLimiterContract(strings))
                .as("元素类型不对必须被拒：生产中它会被 catch 成永久降级")
                .isInstanceOf(AssertionError.class);

        @SuppressWarnings("rawtypes")
        RedisScript<List> oneElement = RedisScript.of("return {1}", List.class);
        List<?> tooFew = redis.execute(oneElement, List.of(BUCKET));

        assertThat(tooFew).as("元素类型对但个数不对").hasSize(1);
        assertThat(tooFew.get(0)).isInstanceOf(Long.class);
        assertThatThrownBy(() -> assertReplyIsTheRedisRateLimiterContract(tooFew))
                .as("个数不对必须被拒：RedisRateLimiter 的 result.size() != 3 闸门")
                .isInstanceOf(AssertionError.class);
    }

    /** 生产代码认定的「脚本返回值可用」：非 null、恰好 3 个元素、元素能按 `Long` 拆箱。 */
    private static void assertReplyIsTheRedisRateLimiterContract(List<?> reply) {
        assertThat(reply).as("null 会被生产代码当成「Redis 不可用」而降级").isNotNull();
        assertThat(reply).as("恰好 3 个：{allowed, remaining, retryAfter}").hasSize(3);
        for (int i = 0; i < reply.size(); i++) {
            Object element = reply.get(i);
            assertThat(element).as("元素 [%s] 的运行时类型（拆箱成 long 的前提）", i)
                    .isInstanceOf(Long.class);
        }
    }

    // ------------------------------------------------------------------ 突发 / 补充 / 封顶

    @Test
    void freshBucketAllowsBurstThenDenies() {
        for (int i = 0; i < 5; i++) {
            List<Long> reply = eval(BUCKET, FIXED_NOW, 0, 5);
            assertThat(reply.get(0)).as("第 %s 次突发：qps=0 也必须允许 burst 次", i + 1).isEqualTo(1L);
            assertThat(reply.get(1)).as("第 %s 次突发后的剩余", i + 1).isEqualTo(4L - i);
        }

        List<Long> denied = eval(BUCKET, FIXED_NOW, 0, 5);

        assertThat(denied.get(0)).isZero();
        assertThat(denied.get(1)).isZero();
        assertThat(denied.get(2)).as("qps=0 时退避有明确上界（与 TokenBucket 的纯算术一致）")
                .isEqualTo(3_600_000L);
    }

    /** 时间戳是参数 ⇒ 用一个「未来」的时刻就能立刻观察到补充，**不需要 sleep**。 */
    @Test
    void tokensRefillAccordingToQps() {
        for (int i = 0; i < 3; i++) {
            assertThat(eval(BUCKET, FIXED_NOW, 10, 3).get(0)).isEqualTo(1L);
        }

        List<Long> denied = eval(BUCKET, FIXED_NOW, 10, 3);
        assertThat(denied.get(0)).isZero();
        assertThat(denied.get(2)).as("退避 = ceil((1000 - 0) / 10)").isEqualTo(100L);

        // 向前 200ms：qps=10 → 补 2 个令牌。
        assertThat(eval(BUCKET, FIXED_NOW + 200L, 10, 3).get(0)).isEqualTo(1L);
        assertThat(eval(BUCKET, FIXED_NOW + 200L, 10, 3).get(0)).isEqualTo(1L);
        assertThat(eval(BUCKET, FIXED_NOW + 200L, 10, 3).get(0)).isZero();
    }

    @Test
    void remainingIsReportedAndCappedAtBurst() {
        List<Long> first = eval(BUCKET, FIXED_NOW, 100, 10);
        assertThat(first.get(0)).isEqualTo(1L);
        assertThat(first.get(1)).isEqualTo(9L);

        List<Long> afterHugeGap = eval(BUCKET, FIXED_NOW + 3_600_000L, 100, 10);
        assertThat(afterHugeGap.get(1)).as("过了一小时也只补到 burst").isEqualTo(9L);
    }

    /**
     * **服务端补充**：桶在 Redis 里被耗尽（服务端 {@code t == 0}），只把**调用方时钟**往前推，
     * 重新放行，并且补充的结果由脚本落盘 —— 补充是 Redis 算的，不是 Java 纯算术镜像算的。
     */
    @Test
    void refillIsComputedServerSideFromTheCallerSuppliedClock() {
        for (int i = 0; i < 2; i++) {
            assertThat(eval(BUCKET, FIXED_NOW, 10, 2).get(0)).isEqualTo(1L);
        }
        assertThat(eval(BUCKET, FIXED_NOW, 10, 2).get(0)).isZero();

        assertThat(tokens()).as("耗尽这件事发生在 Redis 里").isEqualTo("0");
        assertThat(lastRefill()).isEqualTo(String.valueOf(FIXED_NOW));

        long later = FIXED_NOW + 500L;
        List<Long> refilled = eval(BUCKET, later, 10, 2);

        assertThat(refilled.get(0)).as("只把时钟推进 500ms，桶就重新放行").isEqualTo(1L);
        assertThat(refilled.get(1)).as("500ms × qps 10 = 5000 毫令牌 → 封顶到 2 个 → 扣 1 个后剩 1 个")
                .isEqualTo(1L);
        assertThat(tokens()).as("补充后的令牌数由脚本写回 Redis").isEqualTo("1000");
        assertThat(lastRefill()).as("基准时刻前移到调用方传进来的 now").isEqualTo(String.valueOf(later));
    }

    // ------------------------------------------------------------------ 并发原子性

    /**
     * **原子性的可证伪证据**：20 个线程各尝试 20 次（共 400 次），桶容量 25、qps=0 →
     * 恰好 25 次被放行。所有调用共用 {@link #FIXED_NOW}（`elapsed` 恒为 0）⇒ 与墙钟、与线程交错无关。
     * 分步实现（HGET 之后 HSET，两次客户端往返）会显著超过 25；丢掉扣减则 400 次全放行。
     */
    @Test
    void concurrentRequestsNeverExceedBurst() throws Exception {
        int threads = 20;
        int attempts = 20;
        int burst = 25;
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < attempts; j++) {
                        if (eval(BUCKET, FIXED_NOW, 0, burst).get(0) == 1L) {
                            allowed.incrementAndGet();
                        }
                        else {
                            denied.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            // 任何一个线程里的异常（含 Redis 异常）都在这里炸出来，绝不被线程池静默吞掉。
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get())
                .as("Lua 的『补充 + 判定 + 扣减』必须是一次原子往返：多放行一次就是超发")
                .isEqualTo(burst);
        assertThat(denied.get()).isEqualTo(threads * attempts - burst);

        // 服务端计数器是第二本独立的账：qps=0 且 400 次调用共用同一个 now ⇒ t 只可能来自
        // 「恰好 25 次、每次扣 1000」。客户端计数与服务端状态必须同时成立。
        assertThat(tokens()).as("服务端也被恰好扣到 0").isEqualTo("0");
        assertThat(lastRefill()).isEqualTo(String.valueOf(FIXED_NOW));
        assertThat(eval(BUCKET, FIXED_NOW, 0, burst).get(0)).as("桶已经空了").isZero();
    }

    // ------------------------------------------------------------------ 键布局 / TTL

    @Test
    void bucketKeyCarriesAnIdleTtl() {
        eval(BUCKET, FIXED_NOW, 10, 20);

        long idleTtl = RateLimitScript.idleTtlMillis(10, 20);
        Long ttl = redis.getExpire(BUCKET, TimeUnit.MILLISECONDS);

        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(idleTtl);
        assertThat(ttl).isGreaterThan(idleTtl - Duration.ofSeconds(20).toMillis());
    }

    /** 契约里的键布局在真 Redis 上逐项兑现（类型、字段名与取值、以及每次调用都 PEXPIRE）。 */
    @Test
    void bucketKeyIsAHashWithTheDocumentedFieldsAndTtl() {
        eval(BUCKET, FIXED_NOW, 10, 20);

        assertThat(redis.type(BUCKET)).as("决策 8：桶是 Hash，不是 String").isEqualTo(DataType.HASH);

        Map<Object, Object> fields = redis.opsForHash().entries(BUCKET);
        assertThat(fields).as("字段只有契约里的两个：t = 令牌毫数、k = 上次补充的毫秒时间戳")
                .containsOnlyKeys(RateLimitScript.FIELD_TOKENS, RateLimitScript.FIELD_LAST_REFILL);
        assertThat(fields.get(RateLimitScript.FIELD_TOKENS)).isEqualTo("19000");
        assertThat(fields.get(RateLimitScript.FIELD_LAST_REFILL)).isEqualTo(String.valueOf(FIXED_NOW));

        long idleTtl = RateLimitScript.idleTtlMillis(10, 20);
        assertThat(redis.expire(BUCKET, Duration.ofSeconds(5))).isTrue();
        assertThat(redis.getExpire(BUCKET, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(5_000L);

        eval(BUCKET, FIXED_NOW, 10, 20);

        assertThat(redis.getExpire(BUCKET, TimeUnit.MILLISECONDS))
                .as("每次调用都重新 PEXPIRE（否则活跃桶会在空闲 TTL 后被回收）")
                .isGreaterThan(5_000L).isLessThanOrEqualTo(idleTtl);
    }

    // ------------------------------------------------------------------ 隔离 / 幂等

    @Test
    void differentKeysAreIsolated() {
        eval(BUCKET, FIXED_NOW, 0, 1);

        assertThat(eval(BUCKET, FIXED_NOW, 0, 1).get(0)).isZero();
        assertThat(eval(OTHER_BUCKET, FIXED_NOW, 0, 1).get(0)).as("另一个 key 不受影响").isEqualTo(1L);
    }

    @Test
    void scriptIsIdempotentForTheSameTimestamp() {
        assertThat(eval(BUCKET, FIXED_NOW, 10, 3).get(0)).isEqualTo(1L);
        assertThat(eval(BUCKET, FIXED_NOW, 10, 3).get(0)).isEqualTo(1L);
        assertThat(eval(BUCKET, FIXED_NOW, 10, 3).get(0)).isEqualTo(1L);

        // 同一毫秒内第四次：没有补充，也没有重复补充。
        assertThat(eval(BUCKET, FIXED_NOW, 10, 3).get(0)).isZero();
    }

    // ------------------------------------------------------------------ 读服务端状态的小工具

    private String tokens() {
        return redis.<String, String>opsForHash().get(BUCKET, RateLimitScript.FIELD_TOKENS);
    }

    private String lastRefill() {
        return redis.<String, String>opsForHash().get(BUCKET, RateLimitScript.FIELD_LAST_REFILL);
    }
}
