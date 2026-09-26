package com.aihub.admin.apikey;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.dao.entity.ApiKeyEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.ApiKeyMapper;
import com.aihub.dao.mapper.TenantMapper;
import com.aihub.service.apikey.ApiKeyService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D1 的**控制面这一半**：Redis 不可用时，{@code ApiKeyService.resolve}（即
 * {@code POST /internal/api-keys/resolve} 的全部业务逻辑）必须在网关给内部跳的 3 秒预算
 * （{@code AdminClientConfig.responseTimeout(3s)}）**之内**回答。
 *
 * <p><b>为什么这是一个真实缺陷的判据，而不是一条形式化的时延断言</b>：compose 全栈验收实测到的是
 * 「网关的 WARN→ERROR 恰好 3.01 秒」—— 网关在 3 秒预算到点时掐掉连接，把 admin 那条**健康**的回答
 * 当成传输故障，于是 valid key 变 401。判据因此必须落在 admin 这一侧的真实耗时上：只要 resolve 的
 * 最坏耗时超过 3 秒，网关上就一定复现 D1。
 *
 * <p><b>Redis 是怎么"不可用"的</b>：本类起一个只 {@code accept()}、永不回包的
 * {@link ServerSocket}（黑障）。这比"指向一个没人监听的端口"更接近实测现场 —— 后者会立刻
 * {@code ECONNREFUSED}（毫秒级），而 compose 里 {@code stop redis} 之后到旧容器 IP 的包是被**丢弃**
 * 的：TCP 连得上、命令永远等不到回应，于是每个 Redis 命令都耗掉整个
 * {@code spring.data.redis.timeout}。实测日志里那句
 * {@code QueryTimeoutException: Redis command timed out} 正是这个形状。
 *
 * <p><b>超时值从生产配置读</b>：本类不在测试里另写一个数字，而是读 admin 的
 * {@code application.yml}（同一个 {@code spring.data.redis.timeout}）。否则把这个值改回 2s
 * 不会让本类变红，本类也就不再是"admin 的回答必须落在网关内部跳预算内"的判据。
 *
 * <p><b>每个用例都用全新的 {@code ApiKeyService} 与全新的连接工厂</b>：修复会给解析路径加
 * "粘性降级"（一次失败之后短期不再碰 Redis），共享实例会让第二个用例白蹭第一个用例的降级状态、
 * 测出来的耗时失去意义。
 *
 * <p><b>实测数字（同一台机器、同一套夹具）</b>：
 * <table>
 *   <tr><th>场景</th><th>修前</th><th>修后</th></tr>
 *   <tr><td>命中路径（读缓存 + 回写缓存）</td><td>4404 ms</td><td>811 ms</td></tr>
 *   <tr><td>不存在的 key（只读缓存）</td><td>2420 ms</td><td>804 ms</td></tr>
 *   <tr><td>一次故障之后的第二个 resolve</td><td>4203 ms</td><td>1 ms</td></tr>
 * </table>
 * 「命中 4404 − 不存在 2420 ≈ 2 秒」正好是**回写缓存**那一跳的代价，两条一起证明读写**各自**
 * 都在拖慢 resolve。修后残余的约 0.8 秒是 Redis 客户端那一次（唯一的）连接/命令超时，
 * 之后粘性降级生效，第二个请求 1 ms。
 */
class ApiKeyResolveRedisOutageTest {

    /** 网关给内部跳的预算，与 {@code AdminClientConfig} 的 {@code responseTimeout} 同一个数。 */
    private static final long GATEWAY_INTERNAL_HOP_BUDGET_MILLIS = 3_000L;

    /**
     * 断言阈值刻意**小于**预算的一半：判据是"admin 的回答落在预算内并留出余量"，
     * 而不是"刚好没超 3 秒"。修前实测约 4.0s（命中）/ 2.0s（未命中），修后约 0.5s。
     */
    private static final long RESOLVE_BUDGET_MILLIS = 1_500L;

    private static ServerSocket blackhole;
    private static final List<Socket> heldConnections = new ArrayList<>();

    @BeforeAll
    static void startBlackholeRedis() throws IOException {
        blackhole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!blackhole.isClosed()) {
                try {
                    Socket accepted = blackhole.accept();
                    synchronized (heldConnections) {
                        heldConnections.add(accepted);
                    }
                } catch (IOException closed) {
                    return;
                }
            }
        }, "fake-redis-blackhole");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterAll
    static void stopBlackholeRedis() throws IOException {
        blackhole.close();
        synchronized (heldConnections) {
            for (Socket socket : heldConnections) {
                socket.close();
            }
        }
    }

    /**
     * 命中路径：Redis 不可用时回源 MySQL 拿到视图，并且**回答必须落在网关的 3 秒内部跳预算内**。
     *
     * <p>修前这里约 4.0 秒 —— "读缓存"与"回写缓存"各等一次 2 秒的 Redis 命令超时。
     */
    @Test
    void validKeyResolveFitsInsideTheGatewayInternalHopBudgetWhenRedisIsDown() {
        ApiKeyService service = serviceWithBlackholedRedis(apiKeyEntity("a".repeat(64)));

        long startedAt = System.nanoTime();
        Optional<ApiKeyView> resolved = service.resolve("a".repeat(64));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(resolved).as("Redis 只是缓存：它挂了也必须能从 MySQL（真相源）解析出来").isPresent();
        assertThat(resolved.orElseThrow().keyId()).isEqualTo("ak_outage");
        assertThat(elapsedMillis)
                .as("Redis 不可用时 resolve 耗时 %s ms；必须先于网关的内部跳预算（%s ms）回答，"
                        + "否则网关会把这条**健康**的回答当成传输故障、把 valid key 变成 401",
                        elapsedMillis, GATEWAY_INTERNAL_HOP_BUDGET_MILLIS)
                .isLessThan(RESOLVE_BUDGET_MILLIS);
    }

    /**
     * 未命中路径（MySQL 里也没有这把 key）：同样必须在预算内回答。
     *
     * <p>它的耗时与上面那条的差就是"回写缓存"那一跳的代价：修前实测这条约 2.0 秒、上面那条约 4.0 秒。
     * 两条一起证明**读缓存与回写缓存各自都在拖慢 resolve**，而不是只有其中一跳。
     */
    @Test
    void unknownKeyResolveAlsoFitsInsideTheGatewayInternalHopBudgetWhenRedisIsDown() {
        ApiKeyService service = serviceWithBlackholedRedis(null);

        long startedAt = System.nanoTime();
        Optional<ApiKeyView> resolved = service.resolve("b".repeat(64));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(resolved).as("不存在的 key 仍然是「不存在」，不是「解析不了」").isEmpty();
        assertThat(elapsedMillis)
                .as("Redis 不可用 + key 不存在时 resolve 耗时 %s ms，同样必须落在内部跳预算（%s ms）内",
                        elapsedMillis, GATEWAY_INTERNAL_HOP_BUDGET_MILLIS)
                .isLessThan(RESOLVE_BUDGET_MILLIS);
    }

    /**
     * 一次故障之后的**后续**请求不能再为 Redis 付钱：这正是"几百毫秒会摊到每一个冷缓存请求上"
     * 那句话的反面。修前每一个请求都是 4 秒（每次都要撞两次超时）；修后除第一个请求外都直接走 MySQL。
     */
    @Test
    void afterOneFailureFurtherResolvesDoNotPayForRedisAgain() {
        ApiKeyService service = serviceWithBlackholedRedis(apiKeyEntity("c".repeat(64)));

        service.resolve("c".repeat(64));

        long startedAt = System.nanoTime();
        service.resolve("d".repeat(64));
        long secondMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(secondMillis)
                .as("故障之后的第二个 resolve 耗时 %s ms：它不该再等一次 Redis 超时", secondMillis)
                .isLessThan(200L);
    }

    /**
     * 真实的 Lettuce 客户端 + 真实的 {@code StringRedisTemplate}，指向黑障端口；
     * {@code api_key_mapper} / {@code tenant_mapper} 为替身（本类要测的是**缓存这一跳**的代价，
     * MySQL 那一跳在集成测试里已有覆盖）。命令超时取生产配置里的值。
     *
     * @param found MySQL 的替身答案：{@code null} = 这个 key 在真相源里也不存在
     */
    private static ApiKeyService serviceWithBlackholedRedis(ApiKeyEntity found) {
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(configuredRedisCommandTimeout())
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", blackhole.getLocalPort()), clientConfiguration);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        ApiKeyMapper apiKeyMapper = mock(ApiKeyMapper.class);
        TenantMapper tenantMapper = mock(TenantMapper.class);
        when(tenantMapper.selectById(7L)).thenReturn(tenant());
        when(apiKeyMapper.selectOne(any(Wrapper.class))).thenReturn(found);

        return new ApiKeyService(apiKeyMapper, tenantMapper, redis, Duration.ofMinutes(5));
    }

    private static ApiKeyEntity apiKeyEntity(String keyHash) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(42L);
        entity.setKeyId("ak_outage");
        entity.setTenantId(7L);
        entity.setKeyHash(keyHash);
        entity.setStatus(ApiKeyView.STATUS_ACTIVE);
        return entity;
    }

    private static TenantEntity tenant() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(7L);
        tenant.setName("outage-tenant");
        tenant.setStatus(ApiKeyView.STATUS_ACTIVE);
        return tenant;
    }

    /**
     * admin 生产配置里的 {@code spring.data.redis.timeout}。见类注释：从生产配置读，避免测试与
     * 生产各写一个数字、改坏了也没人发现。
     */
    private static Duration configuredRedisCommandTimeout() {
        try {
            PropertySource<?> source = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml")).get(0);
            Object value = source.getProperty("spring.data.redis.timeout");
            assertThat(value)
                    .as("admin 的 application.yml 必须显式声明 spring.data.redis.timeout")
                    .isNotNull();
            return DurationStyle.detectAndParse(String.valueOf(value));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 admin 的 application.yml", e);
        }
    }
}
