package com.aihub.gateway.ratelimit;

import com.aihub.common.config.ConfigSnapshot;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 桶 key 的**布局**契约，两级各一条（控制器评审 G4）。
 *
 * <p>门面 {@link RateLimiter} 按 Redis 布局拼桶 key
 * （{@code aihub:ratelimit:{tenantId}:{sha256}}，决策 8），而 {@link LocalRateLimiter} 自己再补一层
 * 前缀。两层前缀是**两个不同的东西**：门面交给本机桶的必须是**未加本机前缀的那一份**，
 * {@link LocalRateLimiter#tryConsume} 自己会补 {@link LocalRateLimiter#KEY_PREFIX}。
 *
 * <p>修复前的写法是 {@code local:ratelimit:} + {@code aihub:ratelimit:…}，即
 * {@code local:ratelimit:aihub:ratelimit:7:abc} —— 与 {@link LocalRateLimiter#KEY_PREFIX} 自己的
 * 「刻意与 Redis 布局不同」注释矛盾：同一串里出现两个前缀，日志与抓包里反而更难判断一个 key 在哪一级。
 * 桶 key 没有任何解析方（只用于查表），因此改布局是安全的。
 */
class BucketKeyLayoutTest {

    /**
     * G4 的正面钉子：**门面**交给本机桶的键必须是 Redis 布局那一份，而本机前缀只由本机桶自己补一次。
     * <p>修复前门面拼的是 {@code local:ratelimit:} + {@code aihub:ratelimit:…}，本机桶再补一次，
     * 于是桶键里出现两个前缀（本用例断言的就是那份完整键）。
     * <p>注意不能直接对 {@link LocalRateLimiter#tryConsume} 传一个已带本机前缀的键来断言 ——
     * 那一层**本来就该**再补一次（它不知道调用方是谁），那样断言的只是一句同义反复。
     * 契约在门面这一层，所以钉子也必须在门面这一层。
     */
    @SuppressWarnings("unchecked")
    @Test
    void theFacadeAddsNoPrefixOfItsOwnSoTheLocalKeyCarriesExactlyOne() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));
        LocalRateLimiter local = new LocalRateLimiter(10, () -> 1_000L);
        RateLimiter limiter = new RateLimiter(new RedisRateLimiter(redis), local,
                new RateLimitResolver(ConfigSnapshot::empty), 60_000L);

        RateLimitDecision decision = limiter.acquire(7L, 42L, "abc");

        assertThat(decision.allowed()).isTrue();
        // 本机桶里的完整键：前缀只有一层（local 那一层），后面紧跟 Redis 布局的键。
        assertThat(local.trackedKeys()).containsExactly(LocalRateLimiter.KEY_PREFIX + "aihub:ratelimit:7:abc");
        // 「双前缀」= 门面**又**拼了一次 local 前缀（修复前的 bug）。注意别把
        // LocalRateLimiter.KEY_PREFIX + LuaTokenBucket.KEY_PREFIX 当成「双前缀」——
        // 那一串恰好就是**正确**的单层形态（写反过一次，断言会与上面那条互相矛盾）。
        assertThat(local.trackedKeys())
                .as("门面不得自己再拼一次 local 前缀")
                .noneMatch(key -> key.startsWith(LocalRateLimiter.KEY_PREFIX + LocalRateLimiter.KEY_PREFIX));
    }

    /**
     * 前缀必须是**另一个**前缀：两个存储混用同一串时，日志与抓包里无法判断一个 key 在哪一级。
     * 这条不是风格问题 —— 它是 {@link LocalRateLimiter#KEY_PREFIX} 存在的全部理由。
     */
    @Test
    void theLocalPrefixIsDistinctFromTheRedisOne() {
        assertThat(LocalRateLimiter.KEY_PREFIX).isNotEqualTo(LuaTokenBucket.KEY_PREFIX);
        assertThat(LocalRateLimiter.KEY_PREFIX).doesNotStartWith(LuaTokenBucket.KEY_PREFIX);
    }

    /**
     * 门面 → 本机桶这条链的**真实**键：死端口的替身版（不需要 Lettuce）。
     * 断言的是「桶布局是 tenant + sha256(secret)」而不是「tenant + apiKeyId」（决策 7 与决策 8 是
     * 两个维度，混用会让 key 级策略把桶也切开）。
     */
    @SuppressWarnings("unchecked")
    @Test
    void theBucketKeyIsTheDocumentedTenantAndHashLayout() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));
        LocalRateLimiter local = new LocalRateLimiter(100, () -> 1_000L);
        RateLimiter limiter = new RateLimiter(new RedisRateLimiter(redis), local,
                new RateLimitResolver(ConfigSnapshot::empty), 60_000L);

        limiter.acquire(7L, 42L, "hash-of-secret");

        assertThat(local.trackedKeys())
                .containsExactly(LocalRateLimiter.KEY_PREFIX + "aihub:ratelimit:7:hash-of-secret");
    }

    /**
     * 端到端形状：真门面 + 真 Lettuce 客户端指向死端口 → 本机桶，且**同一个
     * {@code (tenantId, keyHash)} 只产生一个桶**。修复前后这一条都是 1（双前缀也还是一对一），
     * 所以它证明不了前缀本身；它证明的是「门面到本机桶这条链真的接通了、维度也没多算」。
     */
    @Test
    void theFacadeRoutesTheDegradedDecisionIntoASingleLocalBucketPerTenantAndHash() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder()
                        .clientOptions(ClientOptions.builder()
                                .socketOptions(SocketOptions.builder()
                                        .connectTimeout(Duration.ofMillis(200))
                                        .build())
                                .build())
                        .commandTimeout(Duration.ofMillis(300))
                        .build());
        factory.afterPropertiesSet();
        try {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            LocalRateLimiter local = new LocalRateLimiter(100, System::currentTimeMillis);
            RateLimiter limiter = new RateLimiter(new RedisRateLimiter(template), local,
                    new RateLimitResolver(ConfigSnapshot::empty), 60_000L);

            assertThat(limiter.acquire(7L, 42L, "abc").degraded()).isTrue();
            assertThat(limiter.acquire(7L, 42L, "abc").degraded()).isTrue();

            assertThat(local.trackedBuckets()).as("同一 (tenantId, hash) 必须共用一个本机桶").isEqualTo(1);
            assertThat(local.trackedKeys()).containsExactly(LocalRateLimiter.KEY_PREFIX + "aihub:ratelimit:7:abc");
        } finally {
            factory.destroy();
        }
    }
}
