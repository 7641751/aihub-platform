package com.aihub.gateway.ratelimit;

import com.aihub.common.ratelimit.RateLimitScript;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 侧的调用约定与失败语义。**行为**（脚本真的在 Redis 里原子地补充/拒绝）由 admin 侧的
 * 真 Redis 集成测试负责 —— 网关测试不允许依赖 Docker。
 *
 * <p>本类的核心契约：Redis 出任何问题（连不上、超时、脚本被 FLUSHALL 掉）都返回 {@code null}
 * 表示「我这边不可用」，由 {@link RateLimiter} 决定降级到本机桶；**绝不抛异常到请求路径上**。
 *
 * <p><b>两侧相乘才等于「真容器上 {@code source() == REDIS}」</b>：本类钉住**调用约定**（脚本用的是
 * 共享常量、结果类型提示是 {@code List.class}、ARGV 向量形状、以及 3 个 {@code Long} 会被映射成
 * {@code Source.REDIS}）；admin 侧的 {@code RedisTokenBucketIntegrationTest} 钉住**真 Redis 返回值的
 * 形状**。少了后者的「结果类型提示」这一环，有人把提示改成别的类型时本类仍然全绿，而线上会静默地
 * 永久降级 —— 见 {@link #asksRedisToDecodeTheScriptReplyAsAList} 的注释。
 */
@SuppressWarnings("unchecked")
class RedisRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final RedisRateLimiter limiter = new RedisRateLimiter(redis);

    @Test
    void returnsNullWhenRedisThrows() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));

        assertThat(limiter.tryConsume("aihub:ratelimit:7:abc", 10, 20)).isNull();
    }

    @Test
    void returnsNullWhenTheScriptResultIsNotAThreeElementList() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L));
        assertThat(limiter.tryConsume("k", 10, 20)).isNull();

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        assertThat(limiter.tryConsume("k", 10, 20)).isNull();
    }

    @Test
    void passesThePinnedKeyAndArgumentsToTheScript() {
        AtomicReference<List<String>> keys = new AtomicReference<>();
        AtomicReference<Object[]> received = new AtomicReference<>();
        AtomicReference<Object[]> args = new AtomicReference<>();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            // Mockito 会把变参 `Object...` **摊开**成独立实参（下标 0 / 1 是 script 与 KEYS），
            // 所以 ARGV 是下标 2 起。这里从**真实收到的实参**切片，参数个数是被**测出来**的，
            // 不是按签名假设的（写死 `getArgument(2)` 会拿到字符串并抛 ClassCastException，
            // 被被测代码 catch 成「Redis 不可用」，于是这个用例会静默地什么也没验到）。
            received.set(invocation.getArguments());
            keys.set(invocation.getArgument(1));
            args.set(Arrays.copyOfRange(received.get(), 2, received.get().length));
            return List.of(1L, 19L, 0L);
        });

        long before = System.currentTimeMillis();
        limiter.tryConsume("aihub:ratelimit:7:abc", 10, 20);
        long after = System.currentTimeMillis();

        assertThat(keys.get()).containsExactly("aihub:ratelimit:7:abc");
        // 调用约定：1 个 key + 恰好 4 个字符串参数（script 与 keys 之外只剩 ARGV）。
        assertThat(received.get()).hasSize(6);
        // ARGV = {nowMillis, qps, burst, ttlMillis}
        assertThat(args.get()).hasSize(4);
        assertThat(Long.parseLong((String) args.get()[0]))
                .as("ARGV[1] 是调用方传入的 nowMillis（决策 9：绝不读 Redis 的 TIME）")
                .isBetween(before, after);
        assertThat(args.get()[1]).isEqualTo(String.valueOf(10));
        assertThat(args.get()[2]).isEqualTo(String.valueOf(20));
        assertThat(args.get()[3]).isEqualTo(String.valueOf(LuaTokenBucket.idleTtlMillis(10, 20)));
        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    /**
     * 结果类型提示是**跨模块契约的另一半**：admin 侧的真 Redis 集成测试
     * （{@code com.aihub.admin.ratelimit.RedisTokenBucketIntegrationTest}）按 {@code List.class}
     * （MULTI 解码）断言「真 Redis 的返回值是 size==3、元素为 {@code java.lang.Long} 的 List」，
     * 从而接力证明 {@code source() == REDIS}。**若这里换成别的类型，那边的证据就不再覆盖线上路径**，
     * 而本类其余用例（Mockito 只按 {@code any(RedisScript.class)} 打桩）不会变红 —— 于是线上会
     * 静默地永久降级。
     *
     * <p>实测（探针，真容器）：用 {@code Long.class} 提交**同一个脚本**，Redis 静默返回一个 {@code 0}
     * （既不是 allowed 也不是 remaining）；生产代码把那个 {@code Long} 赋给 {@code List<Long> result}
     * 时抛 {@code ClassCastException}，被 {@code catch (RuntimeException)} 吞掉 → {@code null} →
     * 永久降级，日志里只留一条 WARN。
     */
    @Test
    void asksRedisToDecodeTheScriptReplyAsAList() {
        AtomicReference<RedisScript<?>> captured = new AtomicReference<>();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return List.of(1L, 19L, 0L);
        });

        limiter.tryConsume("aihub:ratelimit:7:abc", 10, 20);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getScriptAsString())
                .as("跑的必须是共享模块里的那份脚本：admin 侧的真容器证据读的就是它")
                .isEqualTo(RateLimitScript.SCRIPT);
        assertThat(captured.get().getResultType())
                .as("MULTI 解码：admin 侧按 List<Long> 验证真返回值的形状")
                .isEqualTo(List.class);
    }

    @Test
    void mapsTheScriptResultToADecision() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 7L, 0L));

        RateLimitDecision decision = limiter.tryConsume("k", 10, 20);

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(7);
        assertThat(decision.limit()).isEqualTo(10);
        assertThat(decision.burst()).isEqualTo(20);
        assertThat(decision.source()).isEqualTo(RateLimitDecision.Source.REDIS);
        assertThat(decision.degraded()).isFalse();
    }

    @Test
    void deniedDecisionCarriesRetryAfter() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, 0L, 250L));

        RateLimitDecision decision = limiter.tryConsume("k", 10, 20);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterMs()).isEqualTo(250L);
    }

    /** 空/空白 key 属于编程错误：不要为此打一次 Redis，直接按「本机不可用」返回让上层降级。 */
    @Test
    void blankKeyIsRejectedLocallyWithoutCallingRedis() {
        assertThat(limiter.tryConsume("", 10, 20)).isNull();
        assertThat(limiter.tryConsume(null, 10, 20)).isNull();
    }

    /**
     * 降级信号必须是 `null` 而不是「一个拒绝判定」：把故障表达成拒绝就等于把 Redis 故障
     * 变成对客户端的限流拒绝 —— 那正是设计文档 §9 要避免的事。
     */
    @Test
    void unavailableRedisIsSignalledAsNullSoTheCallerCanDegrade() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));

        RateLimitDecision decision = limiter.tryConsume("k", 10, 20);

        assertThat(decision).as("故障绝不能伪装成一次「拒绝」").isNull();
    }
}
