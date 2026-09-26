package com.aihub.gateway.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置读取的**唯一入口**（§6.3 三级读取 + 两级缓存 + singleflight + 版本比对）。
 *
 * <p><b>降级顺序是刻意的（决策 6）</b>：Caffeine 命中就直接用 → 未命中读 Redis（命中既采用它、
 * 也顺手刷新本地副本及其 {@code version}）→ 都没有才回源 admin。**admin 不可达时继续服务手上
 * 已有的陈旧快照（哪怕已过期）**；只有「手上一份快照都没有」（冷启动 + admin 挂）才回落到
 * 遗留单渠道。理由是：30 秒前的路由表远比「没有路由表」安全，而回 503 会违反
 * 「数据面永不因控制面故障而整体不可用」。
 *
 * <p><b>{@link #current()} 永不返回 null、永不抛异常</b>，最差返回「遗留单渠道」合成的快照
 * （决策 6）。理由同 M1：「缓存/控制面故障绝不能让数据面整体不可用」。
 *
 * <p><b>版本比对（§6.3）</b>：本地命中时若 {@code local.version() < redis.version} 则丢弃本地、
 * 采用 Redis 并刷新本地。已知缺口（登记在计划里）：M4 之前没有任何写入方，且同毫秒更新的两行会
 * 产生相同 version —— 届时 version 的生成方式必须保证单调。
 *
 * <p><b>singleflight（§6.3）</b>：并发 miss 时只有一个线程真正回源，其余等待同一个 {@link Mono}
 * （用 {@link AtomicReference} 持有「正在飞的 Mono」并用 {@code Mono.cache()} 让多个订阅者共享）。
 *
 * <p><b>没有 Pub/Sub 失效监听器（决策 16）</b>：M4 之前没有发布方，写了就是一条永远不执行的
 * 死路径，只能靠「直接调监听器」自证。M3 的收敛手段是**版本比对 + TTL**
 * （{@link #invalidate()} 因此只是将来监听器的挂载点，当前无人调用）。
 *
 * <p><b>线程模型</b>：{@link #current()} 会被 event loop（过滤器/控制器）调用，而
 * {@code AdminClient} 是 WebClient（异步，不阻塞）；Redis 侧只有一次同步 get/set，因此这里
 * **不引入额外的调度器** —— 与 M1 的 {@code ApiKeyResolver} 不同，本类的 Redis 调用只发生在
 * 「本地缓存 miss」时（30 秒一次那一档），代价可接受。若将来把这里改回逐请求调用，
 * 必须像 {@code ApiKeyResolver} 那样切到 {@code boundedElastic}。
 */
public class ConfigClient {

    private static final Logger log = LoggerFactory.getLogger(ConfigClient.class);

    /** 回源等待上限：超过就当这次回源失败（宁可用陈旧快照，也不把 event loop 挂死）。 */
    private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(5);

    /** 成功从 admin 回源并回填缓存的次数（「真快照」的获取速率，运维用它区分「一直命中缓存」与「一直在回源」）。 */
    public static final String REFRESHED_METRIC = "aihub.config.snapshot.refreshed";

    private final ConfigCache cache;
    private final AdminClient adminClient;
    private final UpstreamProperties upstream;
    /**
     * 本类自身**不读**任何配置项（本地/Redis 的 TTL 由 {@link ConfigCache} 持有），但 brief 的构造器
     * 形状如此，且它是将来加「回源超时/退避」等开关的天然挂载点；保留字段以免每次都要改构造器签名。
     */
    @SuppressWarnings("unused")
    private final GatewayConfigProperties properties;
    private final AtomicReference<Mono<ConfigSnapshot>> inFlight = new AtomicReference<>();
    private final Counter refreshedCounter;
    /** 当前快照 version 的**可观测镜像**（{@link Gauge} 需要 Number，不能直接量一个 record）。 */
    private final AtomicLong visibleVersion = new AtomicLong();

    /**
     * 不装配注册表的重载：计数进 Micrometer 的**全局复合注册表**
     * （Spring Boot 默认把各注册表挂在它上面，没有注册表时是安全空操作）。
     */
    public ConfigClient(ConfigCache cache, AdminClient adminClient, UpstreamProperties upstream,
                        GatewayConfigProperties properties) {
        this(cache, adminClient, upstream, properties, Metrics.globalRegistry);
    }

    public ConfigClient(ConfigCache cache, AdminClient adminClient, UpstreamProperties upstream,
                        GatewayConfigProperties properties, MeterRegistry registry) {
        this.cache = cache;
        this.adminClient = adminClient;
        this.upstream = upstream;
        this.properties = properties;
        this.refreshedCounter = registry.counter(REFRESHED_METRIC);
        // 版本号是「两级缓存是否收敛」唯一的对外信号：本地命中而 Redis 已经前进（或反之）
        // 会直接反映在这条曲线上的阶梯。用 Gauge 而不是 Counter，因为它是状态不是事件。
        Gauge.builder("aihub.config.snapshot.version", visibleVersion, AtomicLong::get)
                .description("当前生效的配置快照 version（0 = 尚未拿到控制面快照）")
                .register(registry);
    }

    /** 当前生效的快照。三级顺序 + 版本比对 + 兜底，**永不 null / 永不抛**。 */
    public ConfigSnapshot current() {
        ConfigSnapshot resolved = resolve();
        if (resolved != null) {
            return resolved;
        }
        // 三级全空：这是唯一允许同步回源的地方（也是唯一可能阻塞的地方）。
        ConfigSnapshot loaded = refreshBlocking();
        if (loaded != null) {
            return loaded;
        }
        // 回源没拿到真快照。必须**再看一次两级缓存**：Redis 命中/回填走的是回源这条路径，
        // 而 admin 返回空快照时回源不写任何缓存 —— 两种情况的区分只能靠重读，读到了就用，
        // 一份都没有才回落遗留单渠道（决策 6）。
        ConfigSnapshot afterRefresh = resolve();
        return afterRefresh != null ? afterRefresh : legacyFallback();
    }

    /**
     * 过滤掉「没有内容」的快照：admin 在冷启动（还没组装过任何渠道/路由）时会这么回。
     * 它必须与 {@code Optional.empty()} 同序降级到遗留渠道，否则「控制面空表 + 渠道在
     * {@code aihub.upstream.*} 里」的部署会得到一份**空快照**，所有模型都查不到候选。
     * 有渠道但暂时没有路由的快照**不**算空 —— 那是一个合法的中间状态，不该被遗留渠道顶掉。
     */
    private ConfigSnapshot usable(ConfigSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return snapshot.channels().isEmpty() && snapshot.routes().isEmpty() ? null : snapshot;
    }

    /**
     * 两级缓存的一致读（§6.3 的版本比对）。
     *
     * <p>规则：本地命中且**不比 Redis 落后**就用本地；Redis 的 version 更大就丢弃本地、采用 Redis
     * 并顺手刷新本地副本。版本比对是 M3 唯一的跨实例收敛手段（决策 16 不写 Pub/Sub），
     * 因此它必须在本地命中这条路径上生效 —— 而不是「本地命中就无条件相信本地」。
     */
    private ConfigSnapshot resolve() {
        ConfigSnapshot local = cache.local().orElse(null);
        ConfigSnapshot fromRedis = cache.readRedis().orElse(null);
        ConfigSnapshot resolved;
        if (fromRedis != null && (local == null || fromRedis.version() > local.version())) {
            // Redis 是跨实例的收敛点：它更新就说明别的实例已经改过配置，本地副本必须让位。
            cache.putLocal(fromRedis);
            resolved = fromRedis;
        } else {
            // Redis 更旧/读不到：继续用手上的本地副本（决策 6 —— 陈旧快照好过没有快照）。
            resolved = local;
        }
        visibleVersion.set(resolved == null ? 0L : resolved.version());
        return resolved;
    }

    /**
     * 主动回源（singleflight）并回填两级缓存。返回空表示「这次没拿到真快照」。
     *
     * <p><b>回源操作本身是惰性的</b>（{@code Mono.defer}）：拿到「正在飞」名额之前**不构造**
     * 真正的 admin 调用。这不是风格问题 —— 若在这里直接构造 {@code adminClient.configSnapshot()}，
     * 每一个抢名额失败的调用者都会**先**向 admin 发一次请求再把它丢掉，singleflight 就只剩名字。
     * 这条是被 {@code concurrentMissesCollapseIntoASingleAdminCall} 抓出来的真实缺陷。
     */
    public Mono<ConfigSnapshot> refresh() {
        Mono<ConfigSnapshot> existing = inFlight.get();
        if (existing != null) {
            return existing;
        }
        Mono<ConfigSnapshot> fresh = Mono.defer(this::load)
                // **在缓存写完之后**才清掉「正在飞」的引用，顺序不能反：反过来的话，
                // 排队等待的后来者会落进「引用已清空 + 缓存还是空的」这条缝隙，于是再回源一次
                // —— singleflight 就在最需要它的那一刻（一整批并发 miss）失效。
                // 清空之后到达的调用者会直接命中刚写好的本地缓存，同样不会二次回源。
                .doOnSuccess(ignored -> inFlight.compareAndSet(existing, null))
                .cache();
        if (inFlight.compareAndSet(null, fresh)) {
            return fresh;
        }
        Mono<ConfigSnapshot> winner = inFlight.get();
        return winner == null ? fresh : winner;
    }

    /** 真正的回源 + 双回填。**只会被 singleflight 的赢家订阅一次**。 */
    private Mono<ConfigSnapshot> load() {
        return adminClient.configSnapshot()
                .map(maybe -> maybe.orElse(null))
                .map(this::usable)
                .flatMap(snapshot -> {
                    if (snapshot == null) {
                        return Mono.empty();
                    }
                    return Mono.fromRunnable(() -> {
                        cache.putLocal(snapshot);
                        cache.writeRedis(snapshot);
                        refreshedCounter.increment();
                    }).thenReturn(snapshot);
                })
                .onErrorResume(ex -> {
                    // admin 不可达不是错误路径，而是已设计的降级：调用方继续用手上的快照。
                    log.warn("配置快照回源失败，继续使用已有快照: {}", ex.toString());
                    return Mono.empty();
                });
    }

    /** 同步版回源（供 {@link #current()} 在请求路径上用）：拿不到就返回 null。 */
    private ConfigSnapshot refreshBlocking() {
        try {
            return refresh().block(REFRESH_TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("配置快照回源等待失败，继续使用已有快照: {}", e.toString());
            return null;
        }
    }

    /** 失效本地缓存（未来 Pub/Sub 监听器的唯一调用点；M3 没有发布方，见决策 16）。 */
    public void invalidate() {
        cache.invalidateLocal();
    }

    /**
     * 冷启动 + admin 不可达时的兜底：把 {@code aihub.upstream.*} 合成一条单渠道快照。
     * **不写任何缓存** —— 否则 admin 恢复后，这份兜底会以「真快照」的身份留在 Redis 里。
     */
    public ConfigSnapshot legacyFallback() {
        ChannelDescriptor legacy = legacyChannel();
        String model = upstream.defaultModel();
        List<ModelRouteDescriptor> routes = model == null || model.isBlank()
                ? List.of()
                : List.of(new ModelRouteDescriptor(model, legacy.id(), 100, 0, ModelRouteDescriptor.STATUS_ACTIVE));
        return new ConfigSnapshot(0L, 0L, List.of(legacy), routes, List.of(), model);
    }

    public ChannelDescriptor legacyChannel() {
        return LegacyChannel.of(upstream);
    }
}
