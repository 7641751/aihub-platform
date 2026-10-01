package com.aihub.gateway.config;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ConfigSnapshotCodec;
import com.aihub.common.config.QuotaDescriptor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 两级缓存的存储层（§6.3）：Caffeine（本机，30s TTL）+ Redis（跨实例共享）。**不含回源逻辑**
 * —— 回源、singleflight 与版本比对都在 {@link ConfigClient} 里，这样本类只关心「怎么存取」。
 *
 * <p>本地只有**一个** key（整个快照是一份文档），因此 Caffeine 的 {@code maximumSize} 只是形式上的
 * 兜底；真正的失效手段是 TTL 与两个失效入口（{@link #invalidateLocal()} /
 * {@link #invalidateAllCaches(long)}，后者的语义见那条 javadoc）。
 *
 * <p>Redis 侧用 {@link ConfigSnapshotCodec} 的分隔符载荷（决策 4），而不是 JSON：
 * 「缓存载荷」与「本地载荷」共用一份编解码只需维护一个转义器。**这不是跨服务契约**
 * （admin 不读它，admin 只发 JSON）。
 *
 * <p><b>两级写入都拒绝「版本倒退」</b>（2026 二次复审修复 3）：回源是并发的（单飞只保证
 * 「同一时刻一次」，不保证「先发先回」），一个晚到的旧响应本来可以把新快照覆盖掉 —— 本地与 Redis
 * 两侧都有这个方向，两侧都拒绝。差别在**等号**（三次复审修复 1）：
 * <ul>
 *   <li>本地层 {@link #putLocal}：同版本保留**已有**那份 —— key 还在，说明这份副本刚被服务出去过；</li>
 *   <li>Redis 层 {@link #writeRedis}：同版本**必须重写** —— 条目可能已经过期，而回源拿到的往往就是
 *       「控制面没变」的那一个版本。拦掉等号会让共享缓存**永远**填不回去：此后每个实例改以本地 TTL
 *       的节奏（≈2 次/分钟）去敲 admin，正是两级缓存要消除的那种控制面压力。</li>
 * </ul>
 * 这条守卫只影响「覆盖已有条目」，不影响「首次写入」，因此不改变正常回填语义。
 *
 * <p>所有 Redis 调用都吞异常：Redis 挂掉只是「二级缓存不可用」，一级与三级照常工作。
 */
public class ConfigCache {

    private static final Logger log = LoggerFactory.getLogger(ConfigCache.class);

    /** Redis 里快照的唯一键。 */
    public static final String REDIS_KEY = "aihub:config:snapshot";

    /** 「还没有观察到任何版本」的哨兵：任何真实 version（含 0）都比它大，所以首次写入永远放行。 */
    private static final long NO_VERSION = Long.MIN_VALUE;

    private final StringRedisTemplate redis;
    private final GatewayConfigProperties properties;
    private final Cache<String, ConfigSnapshot> local;

    private volatile boolean redisUsable = true;
    /**
     * 本实例在 Redis 上**观察到**的最高 version：一次成功的写入、或一次成功的读取
     * （{@link #readRedis()} 里的 {@code accumulateAndGet(..., Math::max)}）都会把它推到观察到的值。
     * 用它做「版本倒退」守卫，是为了避免每次回填都先读一次 Redis 去比版本（那样每次成功回源会多一次
     * RTT）。它的初值是「从未观察到」。
     *
     * <p>它**不是**「Redis 里现在那一份的版本」—— 条目随时可能过期，也可能已经被别的实例写得更新。
     * 因此它只能证明「某个版本曾经存在过」，这正是 {@link #writeRedis} 拒绝更旧版本所需要的全部信息。
     */
    private final AtomicLong observedRedisVersion = new AtomicLong(NO_VERSION);

    public ConfigCache(StringRedisTemplate redis, GatewayConfigProperties properties) {
        this(redis, properties, Ticker.systemTicker());
    }

    /**
     * 测试专用接缝：本地 TTL 的读数改由注入的 {@link Ticker} 提供。
     *
     * <p>它存在的理由是**一条真实的时间脆点**：`ConfigCacheTest` 里有一条用例必须观察到
     * 「一个刚写入、TTL 只有 80 ms 的本地条目」，而用真实时钟做这件事意味着任何 ≥80 ms 的停顿
     * （GC、CI 负载、整反应堆并发跑）都会把它翻成空 —— 那条用例实测在全量跑里红过一次
     * （FAIL/PASS/PASS）。推进假时钟能把它变成**确定**的，而不必放宽任何断言。
     *
     * <p>生产走 {@link Ticker#systemTicker()}（单调的纳秒读数），与改动前完全一致。
     */
    ConfigCache(StringRedisTemplate redis, GatewayConfigProperties properties, Ticker ticker) {
        this.redis = redis;
        this.properties = properties;
        this.local = Caffeine.newBuilder()
                .maximumSize(Math.max(1, properties.maxLocalSnapshotSources()))
                .expireAfterWrite(properties.localTtl())
                .ticker(ticker)
                .build();
    }

    public Optional<ConfigSnapshot> local() {
        return Optional.ofNullable(local.getIfPresent(REDIS_KEY));
    }

    /**
     * 该 {@code (tenantId, period)} 的额度（Task 13：网关从快照里读额度，不连数据库）。
     *
     * <p>额度是快照的一部分（{@link ConfigSnapshot#quota(long, String)}），本方法把它从**本地层里
     * 那份快照**取出来 —— 网关在每次请求上都会先经 {@code ConfigClient.current()}（它会把被服务的快照
     * 回填进本地层），因此到这里时本地层通常已经持有当前快照。
     *
     * <p><b>按「租户 + 周期」精确查找</b>（{@code period} 是 UTC 的 {@code YYYYMM}）：同一租户在不同
     * 周期各有额度行，跨周期取错会让错误的预算生效 —— 这正是本方法不能只按 {@code tenantId} 找的原因。
     *
     * <p>返回空表示**不限**（决策 D15：快照里没有该 {@code (tenant, period)} 的行 = 与 M3 一致）；
     * 返回的行里 {@code tokenLimit == 0} 同样表示**不限**。判定「是否受限」由调用方（配额过滤器）负责。
     */
    public Optional<QuotaDescriptor> quota(long tenantId, String period) {
        return local().flatMap(snapshot -> snapshot.quota(tenantId, period));
    }

    /**
     * 一级写入，**带版本守卫**：不比手上那份新就不覆盖。
     *
     * <p>{@code asMap().merge} 在 Caffeine 的 {@code ConcurrentMap} 视图上是**原子**的
     * （{@code compute} 语义），所以并发的两次回填不会交错出「旧盖新」的结果。
     */
    public void putLocal(ConfigSnapshot snapshot) {
        local.asMap().merge(REDIS_KEY, snapshot, ConfigCache::newer);
    }

    public void invalidateLocal() {
        local.invalidateAll();
    }

    /**
     * 失效的**完整**含义：本地、共享条目、写入水位三者一起动。
     *
     * <p>只清本地是 M3 登记的缺口：紧接着的 {@code resolve()} 会读到 Redis 里**同样陈旧**的
     * 共享条目并采用它，于是「配置变了」这个信号对多实例部署完全没有效果（实测 101 秒仍不可见）。
     *
     * <p><b>水位要抬到消息里的版本，而不是重置成 {@code NO_VERSION}</b>：重置会拆掉
     * 「挡住在飞的旧回填把刚删掉的陈旧条目写回去」的唯一护栏 —— 那等于让这次失效白做。
     *
     * @param version 失效消息里带的版本（权威的"控制面已经到过这里"的证据）
     */
    public void invalidateAllCaches(long version) {
        local.invalidateAll();
        // **先抬水位、再删条目**（顺序是承重的）：水位一旦抬到 version，任何比它旧的**在飞回填**
        // 都会被 writeRedis 挡住；反过来的顺序（先删、后抬）留出一个窗口，一条更旧的回源结果可以
        // 在两者之间把刚删掉的陈旧条目写回共享缓存 —— 那正是这条护栏要挡的东西。
        observedRedisVersion.accumulateAndGet(version, Math::max);
        try {
            redis.delete(REDIS_KEY);
        } catch (RuntimeException e) {
            // 删不掉只是「共享那一层没清干净」：本地已失效、水位已抬，回源仍会写回新版本。
            log.warn("删除共享配置快照失败（本地已失效，回源仍会写回新版本）: {}", e.toString());
        }
    }

    /**
     * 二级读取；Redis 不可用或载荷畸形都返回空（调用方回源）。
     *
     * <p><b>读到的版本同样抬升写入水位</b>（三次复审修复 1）：一次观察到的 v10 就是「v10 已知存在」
     * 的证据。少了这一步，「回源在途时读到更新的 v10、随后那条更旧的 v5 才回来」会把 v5 写进
     * **共享**条目、毒化所有其他实例（本地那一层由 {@link #putLocal} 的守卫正确挡住）。
     */
    public Optional<ConfigSnapshot> readRedis() {
        try {
            ConfigSnapshot decoded = ConfigSnapshotCodec.decode(redis.opsForValue().get(REDIS_KEY));
            redisUsable = true;
            if (decoded != null) {
                observedRedisVersion.accumulateAndGet(decoded.version(), Math::max);
            }
            return Optional.ofNullable(decoded);
        } catch (RuntimeException e) {
            if (redisUsable) {
                log.warn("读取配置快照缓存失败（降级为直接回源 admin）: {}", e.toString());
            }
            redisUsable = false;
            return Optional.empty();
        }
    }

    /**
     * 二级写入；失败只记日志（缓存是可丢的派生数据）。**带版本守卫**：version 必须**不旧于**本实例
     * 在 Redis 上观察到的那一份（{@link #observedRedisVersion}）。
     *
     * <p><b>相等必须放行</b>（三次复审修复 1）。条目过期之后回源拿到的往往还是同一个版本（控制面
     * 根本没变），若把等号也拦掉，本地层会正常回填而共享缓存永远填不回去 —— 此后每个实例都以本地
     * TTL 的节奏（≈2 次/分钟/实例）去敲 admin，而不是大约每 10 分钟一次；Redis 重启或一次瞬时写
     * 失败对那个版本留下同样的永久后果。**严格更旧**的版本仍然被拒绝 —— 那才是本守卫最初要挡的回归。
     *
     * <p>水位由「写入成功」与「{@link #readRedis()} 读到」共同推进，所以「回源在途时从 Redis 读到
     * 一个更新的版本，随后那条更旧的回源结果回来」这条**同进程**路径已经被拦住。它**不需要**
     * Lua/CAS（与本节早期版本里的披露相反）：观察与写入都在本进程里，水位就是那次观察的结论。
     *
     * <p>仍然拦不住的是**跨进程**的那一半：本实例既没读到过、也没写过别的实例刚写进去的 v7 时，
     * 它手上那条更旧的 v5 会把 v7 覆盖掉。要堵这一半，必须在 Redis 侧用 Lua/CAS 把「读-比-写」
     * 做成一个原子动作（M3 不具备条件；决策 16 之下 M4 之前也没有别的写入方）。
     */
    public void writeRedis(ConfigSnapshot snapshot) {
        if (snapshot.version() < observedRedisVersion.get()) {
            log.debug("配置快照缓存拒绝版本倒退的写入（已观察到 {}，本次 {}）", observedRedisVersion.get(),
                    snapshot.version());
            return;
        }
        try {
            redis.opsForValue().set(REDIS_KEY, ConfigSnapshotCodec.encode(snapshot), properties.snapshotTtl());
            observedRedisVersion.accumulateAndGet(snapshot.version(), Math::max);
            redisUsable = true;
        } catch (RuntimeException e) {
            // 写失败**不**推进水位：水位代表「Redis 上已知存在的最高版本」，一次失败的写入并没有让
            // 任何版本存在，推进它只会让后面那些（仍然有效的）更旧回填被永久挡住。
            log.warn("写入配置快照缓存失败，忽略: {}", e.toString());
            redisUsable = false;
        }
    }

    /** 最近一次 Redis 访问是否成功（指标与日志用）。 */
    public boolean redisAvailable() {
        return redisUsable;
    }

    /** 「谁更新」的合并函数：平局时保留**已有**那份（同 version 的两份快照里，先到的那份已经服务出去了）。 */
    private static ConfigSnapshot newer(ConfigSnapshot existing, ConfigSnapshot candidate) {
        return candidate.version() > existing.version() ? candidate : existing;
    }
}
