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
 * <p>两级存储的键前缀**刻意不同**，因为一个 key 的字符串本身就应当说明它在哪一级：
 * <ul>
 *   <li>Redis 桶：{@code aihub:ratelimit:{tenantId}:{sha256}}（决策 8），前缀由 {@link RateLimiter}
 *       在 Redis 那一侧加上；</li>
 *   <li>本机桶：{@code local:ratelimit:{tenantId}:{sha256}}，前缀由 {@link LocalRateLimiter#tryConsume}
 *       在它自己那一侧加上。</li>
 * </ul>
 *
 * <p>G4 的判据是**本机键上恰好有一个 {@code local:ratelimit:} 前缀**，而不是「两个前缀叠在同一串里」
 * （{@code local:ratelimit:aihub:ratelimit:…}）。因此 {@link RateLimiter#acquire} 交给本机桶的是
 * **身份**（{@code {tenantId}:{keyHash}}），Redis 布局那一份键是 Redis 路径自己的事 ——
 * 前缀属于各自的存储，谁都不复用对方那一个。桶 key 没有任何解析方（只用于查表），因此改布局是安全的。
 */
class BucketKeyLayoutTest {

    /**
     * G4 的正面钉子：**门面**交给本机桶的是身份（{@code tenantId:hash}），本机前缀由本机桶自己补一次，
     * 于是本机键里恰好只有一个 {@code local:ratelimit:} 前缀 —— {@code aihub:ratelimit:}（Redis 布局的
     * 身份）**不出现**在它里面。
     * <p>注意不能直接对 {@link LocalRateLimiter#tryConsume} 传一个已带本机前缀的键来断言 ——
     * 那一层**本来就该**再补一次（它不知道调用方是谁），那样断言的只是一句同义反复。
     * 契约在门面这一层，所以钉子也必须在门面这一层。
     */
    @SuppressWarnings("unchecked")
    @Test
    void theLocalKeyCarriesExactlyOneLocalPrefixAndNoRedisLayoutPrefix() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));
        LocalRateLimiter local = new LocalRateLimiter(10, () -> 1_000L);
        RateLimiter limiter = new RateLimiter(new RedisRateLimiter(redis), local,
                new RateLimitResolver(ConfigSnapshot::empty), 60_000L);

        RateLimitDecision decision = limiter.acquire(7L, 42L, "abc");

        assertThat(decision.allowed()).isTrue();
        // 本机桶里的完整键：local:ratelimit:{tenant}:{hash} —— 前缀只有一层。
        assertThat(local.trackedKeys()).containsExactly(LocalRateLimiter.KEY_PREFIX + "7:abc");
        assertThat(local.trackedKeys())
                .as("Redis 布局的前缀属于 Redis 那一路，不得出现在本机键里")
                .noneMatch(key -> key.contains(LuaTokenBucket.KEY_PREFIX));
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
                .containsExactly(LocalRateLimiter.KEY_PREFIX + "7:hash-of-secret");
    }

    /**
     * 端到端形状：真门面 + 真 Lettuce 客户端指向死端口 → 本机桶，且**同一个
     * {@code (tenantId, keyHash)} 只产生一个桶**。它证明的是「门面到本机桶这条链真的接通了、
     * 维度也没多算」，同时把本机键的字面量钉在真实 Lettuce 路径上。
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
            assertThat(local.trackedKeys()).containsExactly(LocalRateLimiter.KEY_PREFIX + "7:abc");
        } finally {
            factory.destroy();
        }
    }
}
