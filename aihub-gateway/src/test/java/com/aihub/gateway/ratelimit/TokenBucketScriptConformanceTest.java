package com.aihub.gateway.ratelimit;

import com.aihub.common.ratelimit.RateLimitScript;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **「Lua 脚本」与「Java 纯算术」不许漂移**的守恒测试 —— 这是一份跨实现的契约测试，不是任何一侧的
 * 实现测试。
 *
 * <p>本任务的规则被实现了两遍：{@link RateLimitScript#SCRIPT}（Redis 里跑的那份）与
 * {@link TokenBucket}（纯算术，也是本机降级桶 {@link LocalRateLimiter} 的内核）。两者没有共享代码 ——
 * 一份是 Lua 文本，一份是 Java —— 所以「顺手中的某一侧」是这类代码最常见、也最难在评审里看出来的
 * 故障：两侧各自都自洽，只有把「Redis 正常」与「Redis 降级」并排看才会发现客户端在故障切换的瞬间
 * 看到两种限流行为。
 *
 * <h2>这份测试怎么钉</h2>
 * <ol>
 *   <li><b>向量表只写一遍</b>（{@link #ARITHMETIC_VECTORS}）：每行是
 *       {@code (tokensMilli, lastRefill, now, qps, burst, allowed, remaining, retryAfterMs)}，
 *       后三个字段是**手算的字面量**（本文件里读得出来怎么算的），不从被测代码里推。
 *       它们驱动 {@link TokenBucket} 逐行断言。</li>
 *   <li><b>向量隐含的公式常量再拿去核对脚本**自身**</b>：脚本里的数字字面量集合必须恰好是
 *       {@code {0, 1, 1000, 3600000}} —— 多一个、少一个、把 {@code 1000} 挪成别的值都会变红；
 *       并且 {@code 1000} 必须**恰好等于** {@link TokenBucket#MILLI}、
 *       {@code 3600000} 必须**恰好等于** Java 侧 qps=0 时返回的退避上界。</li>
 *   <li><b>公式形状逐字断言</b>：新桶满桶、补充与封顶 {@code math.min(capacity, tokens + elapsed * qps)}、
 *       扣减 {@code tokens - 1000}、剩余取整 {@code math.floor(tokens / 1000)}、
 *       退避 {@code math.ceil(missing / qps)} 与其下限、{@code qps < 0}/{@code burst < 1} 的钳位。</li>
 * </ol>
 *
 * <h2>这份测试**不能**证明什么（已知缺口，不是遗漏）</h2>
 * 它证明的是「两份实现的常量与算式一致」，**不是**「脚本在真 Redis 上跑出来的结果与纯算术相同」。
 * 本仓库里没有 Lua 解释器（{@code lua}/{@code luajit} 均不存在），gateway 的测试又明令禁止依赖
 * Docker / 活 Redis，因此「把脚本真的丢给 Redis 跑一遍」只能落在 admin 侧的真 Redis 集成测试
 * （{@code RedisTokenBucketIntegrationTest}，计划里的 Task 14）。两者都必要：少了 Task 14，
 * 脚本里 {@code HSET}/{@code PEXPIRE} 的原子性与真实返回值没人验；少了本类，常量漂移要等到有人
 * 真跑起 Redis 才会被发现。
 */
class TokenBucketScriptConformanceTest {

    /**
     * 手算的向量表。列的含义见类注释；{@code retryAfterMs} 在放行时必须为 0。
     *
     * <p>选这些行是为了覆盖公式的每一个分支与每一处取整：
     * <ul>
     *   <li>行 1：新桶（{@code state == null}）在 epoch 量级时刻第一次请求 → 满桶并放行；</li>
     *   <li>行 2：{@code tokensMilli == 1000} 恰好一个令牌 → 放行后确实归零（不是 -1）；</li>
     *   <li>行 3：{@code tokensMilli == 999} 差一个毫令牌 → 拒绝，退避 ceil(1/1)=1ms；</li>
     *   <li>行 4：qps=0 → 拒绝且退避取 3 600 000ms 的上界，并覆盖「脚本不得除以 0」；</li>
     *   <li>行 5：补充 100ms × qps 10 = 恰好 1000 毫令牌 —— 落在整数令牌上的补充；</li>
     *   <li>行 6：补充 150ms × qps 10 = 1500，扣 1000 后余 500 毫令牌 → remaining=0（不是 1）；</li>
     *   <li>行 7：半令牌时被拒 → ceil(500/10)=50ms（毫令牌与整令牌的取整边界）；</li>
     *   <li>行 8：封顶 —— 1 小时 × qps 10 远超 burst，必须封到 capacity；</li>
     *   <li>行 9：{@code burst == 1} 时的剩余上限钳位（2000-1000=1000 → remaining=1）；</li>
     *   <li>行 10/11：burst=0 视作 1、qps 为负视作 0（配置事故的钳位）；</li>
     *   <li>行 12：时钟回拨 → 不补充、不报错，只按现有令牌判定。</li>
     * </ul>
     */
    private static final List<Vector> ARITHMETIC_VECTORS = List.of(
            new Vector("新桶满桶后放行", null, 0L, 1_700_000_000_000L, 10, 20, true, 19, 0L),
            new Vector("恰好一个令牌", 1_000L, 1_700_000_000_000L, 1_700_000_000_000L, 10, 20, true, 0, 0L),
            new Vector("差一个毫令牌", 999L, 1_700_000_000_000L, 1_700_000_000_000L, 1, 20, false, 0, 1L),
            new Vector("qps=0 永不补充", 0L, 1_700_000_000_000L, 1_700_000_000_000L, 0, 20, false, 0, 3_600_000L),
            new Vector("补充恰好落在整数令牌上", 0L, 1_700_000_000_000L, 1_700_000_000_100L, 10, 20, true, 0, 0L),
            new Vector("补充多出半个令牌只算整数个", 0L, 1_700_000_000_000L, 1_700_000_000_150L, 10, 20, true, 0, 0L),
            new Vector("半个令牌的退避时间", 500L, 1_700_000_000_000L, 1_700_000_000_000L, 10, 20, false, 0, 50L),
            new Vector("补充封顶到 burst", 5_000L, 1_700_000_000_000L, 1_700_000_003_600L, 10, 20, true, 19, 0L),
            new Vector("容量为 1 时的剩余上限", 2_000L, 1_700_000_000_000L, 1_700_000_000_000L, 10, 1, true, 1, 0L),
            new Vector("burst=0 视作 1", 100_000L, 1_700_000_000_000L, 1_700_000_000_000L, 10, 0, true, 1, 0L),
            new Vector("qps 为负视作 0", 0L, 1_700_000_000_000L, 1_700_000_000_000L, -5, 2, false, 0, 3_600_000L),
            new Vector("时钟回拨不补充令牌", 3_000L, 1_700_000_000_000L, 1_699_999_999_000L, 10, 20, true, 2, 0L));

    @Test
    void theSameVectorTableDrivesThePureArithmetic() {
        for (Vector vector : ARITHMETIC_VECTORS) {
            TokenBucket.State state = vector.tokensMilli() == null
                    ? null
                    : new TokenBucket.State(vector.tokensMilli(), vector.lastRefillMillis());
            RateLimitDecision decision = TokenBucket.tryConsume(state, vector.nowMillis(), vector.qps(), vector.burst());

            String what = vector.what() + " → " + vector;
            assertThat(decision.allowed()).as("%s：放行与否", what).isEqualTo(vector.allowed());
            assertThat(decision.remaining()).as("%s：剩余请求数", what).isEqualTo(vector.remaining());
            assertThat(decision.retryAfterMs()).as("%s：退避毫秒", what).isEqualTo(vector.retryAfterMs());
            assertThat(decision.limit()).as("%s：limit 必须是钳位后的 qps", what)
                    .isEqualTo(Math.max(vector.qps(), 0));
            assertThat(decision.burst()).as("%s：burst 必须是钳位后的容量", what)
                    .isEqualTo(Math.max(vector.burst(), 1));
        }
    }

    /**
     * 脚本里的数字字面量**集合**必须恰好是这四个，且它们就是向量表与 Java 侧共用的那四个数字。
     * 多一个常量（例如有人写死一个 500）、少一个、或把 {@code 1000} 挪成别的值，都会先在这里变红。
     */
    @Test
    void theScriptsNumericLiteralsAreExactlyThePinnedOnes() {
        assertThat(numericLiterals()).as("脚本里出现过的数字字面量集合")
                .containsExactlyInAnyOrder("0", "1", "1000", "3600000");

        // 毫令牌单位是两侧共用的**同一个数值**，不是「两边各自写了一个 1000」。
        assertThat(numericLiterals()).as("脚本的毫令牌单位必须等于 Java 的 MILLI")
                .contains(String.valueOf(TokenBucket.MILLI));
        assertThat(TokenBucket.MILLI).isEqualTo(1_000L);
        assertThat(TokenBucket.tryConsume(new TokenBucket.State(0L, 0L), 0L, 0, 1).retryAfterMs())
                .as("脚本里的 3600000 与 Java 侧 qps=0 的退避上界必须是同一个数字")
                .isEqualTo(3_600_000L);
        // 脚本里不许出现这两个数字：它们会说明有人换了单位（毫秒/微秒）或换了上限。
        assertThat(numericLiterals()).doesNotContain("500", "3600", "1000000", "60000");
    }

    /** 向量表依赖的每一处算式都必须在脚本里逐字存在（改掉任何一处都会让上表的期望值失去依据）。 */
    @Test
    void theScriptsArithmeticIsPinnedClauseByClause() {
        String script = RateLimitScript.SCRIPT;

        // 新桶直接满桶：与 Java 侧 state == null 的分支一一对应，不是 or '0' + 靠 elapsed 补。
        assertThat(script).contains("local tokens = redis.call('HGET', KEYS[1], 't')");
        assertThat(script).contains("local lastRefill = redis.call('HGET', KEYS[1], 'k')");
        assertThat(script).contains("if tokens and lastRefill then");
        assertThat(script).contains("tokens = capacity");
        assertThat(script).contains("lastRefill = now");
        assertThat(script).as("不得再用 or '0' 把新桶伪装成 0 令牌").doesNotContain("or '0'");

        assertThat(script).contains("if burst < 1 then burst = 1 end");
        assertThat(script).contains("if qps < 0 then qps = 0 end");
        assertThat(script).contains("local capacity = burst * 1000");
        assertThat(script).contains("if elapsed > 0 and qps > 0 then");
        assertThat(script).contains("tokens = math.min(capacity, tokens + elapsed * qps)");
        assertThat(script).contains("if elapsed < 0 then");
        assertThat(script).contains("if tokens >= 1000 then");
        assertThat(script).contains("tokens = tokens - 1000");
        assertThat(script).contains("remaining = math.floor(tokens / 1000)");
        assertThat(script).contains("if remaining > burst then remaining = burst end");
        assertThat(script).contains("local missing = 1000 - tokens");
        assertThat(script).contains("retryAfter = math.ceil(missing / qps)");
        assertThat(script).as("qps=0 与 qps>0 的两条退避分支").contains("retryAfter = 3600000");
        assertThat(script).as("退避不得为 0：与 Java 侧的 Math.max(1L, …) 对齐")
                .contains("if retryAfter < 1 then retryAfter = 1 end");
        assertThat(script).contains("redis.call('HSET', KEYS[1], 't', tokens, 'k', now)");
        assertThat(script).contains("redis.call('PEXPIRE', KEYS[1], ARGV[4])");
        assertThat(script).contains("return {allowed, remaining, retryAfter}");
    }

    /**
     * 向量表第 1 行依赖的规则：新桶（{@code null}，即 Redis 里没这个 key）必须以**满桶**起步，
     * 与「一个显式满桶状态」得到完全相同的判定。这条规则在 {@code qps = 0} 下尤其要紧 ——
     * 那时「从 0 号毫秒补充」永远补不出令牌，会把「允许 burst 次突发」退化成「一次都不放行」。
     */
    @Test
    void aNeverSeenBucketStartsFull() {
        for (int qps : new int[] {0, 1, 10, 1_000_000}) {
            for (int burst : new int[] {1, 5, 20, 10_000}) {
                RateLimitDecision fromNewBucket = TokenBucket.tryConsume(null, 1_000L, qps, burst);
                RateLimitDecision fromFullBucket = TokenBucket.tryConsume(
                        new TokenBucket.State((long) burst * TokenBucket.MILLI, 1_000L), 1_000L, qps, burst);

                assertThat(fromNewBucket.allowed()).as("qps=%s burst=%s：新桶应当放行", qps, burst).isTrue();
                assertThat(fromNewBucket.remaining()).as("qps=%s burst=%s：新桶应当是满的", qps, burst)
                        .isEqualTo(burst - 1);
                assertThat(fromNewBucket).as("qps=%s burst=%s：新桶等价于显式满桶", qps, burst)
                        .isEqualTo(fromFullBucket);

                // 落盘的状态也必须与判定一致：放行一次后是 burst-1 个令牌，而不是从空桶扣出来的负数。
                assertThat(TokenBucket.nextState(null, 1_000L, qps, burst, fromNewBucket).tokensMilli())
                        .as("qps=%s burst=%s：新桶放行一次后的落盘令牌数", qps, burst)
                        .isEqualTo((long) (burst - 1) * TokenBucket.MILLI);
            }
        }
    }

    /**
     * qps=0 时 {@code null}（新桶）与 {@code (0, 0)}（真实存在的空状态）必须**不同** ——
     * 把这两者混为一谈正是「新桶靠 elapsed 补满」那个错误的前提。
     */
    @Test
    void newBucketAndExplicitEmptyBucketAreNotTheSameState() {
        assertThat(TokenBucket.tryConsume(null, 1_000L, 0, 5).allowed())
                .as("新桶：qps=0 也要允许 burst 次突发").isTrue();
        assertThat(TokenBucket.tryConsume(new TokenBucket.State(0L, 1_000L), 1_000L, 0, 5).allowed())
                .as("已存在的空桶：qps=0 不再补充，只能拒绝").isFalse();
    }

    /**
     * 脚本里作为**算术常量**出现的数字字面量必须恰好是这四个。
     * {@code KEYS[1]} / {@code ARGV[2]} 里的下标不是算术常量（它们由调用方按契约填），因此排除。
     */
    private static List<String> numericLiterals() {
        List<String> literals = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?<![A-Za-z\\[\\]])\\d+").matcher(RateLimitScript.SCRIPT);
        while (matcher.find()) {
            literals.add(matcher.group());
        }
        return literals.stream().distinct().collect(Collectors.toList());
    }

    /** 一行向量：输入（{@code tokensMilli == null} 表示新桶）+ 策略 + 手算的三元结果。 */
    private record Vector(String what, Long tokensMilli, long lastRefillMillis, long nowMillis, int qps, int burst,
                          boolean allowed, int remaining, long retryAfterMs) {

        @Override
        public String toString() {
            return "tokens=" + (tokensMilli == null ? "新桶" : tokensMilli) + " lastRefill=" + lastRefillMillis
                    + " now=" + nowMillis + " qps=" + qps + " burst=" + burst;
        }
    }
}
