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
 * <p><b>「哪怕已过期」由 {@link #lastGood} 兑现</b>：两级缓存都会过期（本地 30 s、Redis 10 m），
 * 只有它们兜底的实现会在「Redis 挂 + admin 挂」时退化成遗留单渠道并丢掉多渠道路由。
 * 因此本类额外保留一份**永不失效**的最近一次成功快照，仅在编排层（本类）持有，
 * 不写 Redis、不写本地缓存 —— 它绝不能以「真快照」的身份复活一条陈旧的共享条目。
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
 * <b>名额必须在每一个终态（成功 / 空 / 异常）上释放</b> —— 只在成功时释放会让「第一次回源失败」
 * 的那次空信号被永久重放，实例再也回不到 admin（详见 {@link #refresh()}）。
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

    /** 当前生效快照 version 的指标名（`0` = 手上还没有任何控制面快照）。 */
    public static final String VERSION_METRIC = "aihub.config.snapshot.version";

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
    /**
     * **永不失效**的最近一次成功快照（决策 6 的「哪怕已过期」）。
     *
     * <p>两级缓存都有 TTL，TTL 是「多久之后重新确认」，不是「多久之后放弃服务」。仅靠两级缓存时，
     * 「本地已过期 + Redis 不可用 + admin 不可达」这一组合会让网关掉回单条遗留渠道、丢掉多渠道路由
     * —— 与决策 6 的字面要求矛盾。因此这里留一份只在本进程内存里的副本：不写 Redis（否则它会以
     * 「真快照」的身份复活一条陈旧共享条目，覆盖掉 admin 恢复后别的实例写进去的新版本），
     * 也不回写本地缓存（那是 TTL 的职责）。
     */
    private final AtomicReference<ConfigSnapshot> lastGood = new AtomicReference<>();
    private final Counter refreshedCounter;
    /** 当前快照 version 的**可观测镜像**（{@link Gauge} 需要 Number，不能直接量一个 record）。 */
    private final AtomicLong visibleVersion = new AtomicLong();

    /**
     * 不带注册表的便捷重载：计数进 Micrometer 的**全局复合注册表**
     * （Spring Boot 默认把各注册表挂在它上面，没有注册表时是安全空操作）。
     *
     * <p><b>它只是「随手 new 一个」时的便利</b>，不是给测试或生产装配用的：全局静态注册表在测试之间
     * 会互相污染，所以 {@code ConfigConfig} 与单元测试一律走下面那个**显式注册表**的构造器
     * （与 {@code MeteringConfig} 的同一套纪律）。需要隔离性时请显式传注册表。
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
        Gauge.builder(VERSION_METRIC, visibleVersion, AtomicLong::get)
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
            // 冷启动成功：**就在这里**报出被服务的那份快照的版本。只在 resolve() 里报的话，
            // 第一次服务响应时 gauge 还是 0 —— 控制面明明已经给了 version，对外却显示「还没有」。
            visibleVersion.set(loaded.version());
            return loaded;
        }
        // 回源没拿到真快照。必须**再看一次两级缓存**：Redis 命中/回填走的是回源这条路径
        // （admin 返回空快照时回源不写任何缓存），而这一整个过程中别的实例可能刚把新版本写进 Redis。
        ConfigSnapshot afterRefresh = resolve();
        if (afterRefresh != null) {
            return afterRefresh;
        }
        // 两级缓存都过期/不可用且 admin 不可达：手上那份**永不失效**的最近快照仍然比「只剩一条
        // 遗留渠道」好得多（决策 6 的「哪怕已过期」）。它不写任何缓存，因此不会复活陈旧共享条目。
        ConfigSnapshot retained = lastGood.get();
        if (retained != null) {
            log.warn("两级缓存均已过期/不可用且 admin 不可达，继续使用最近一次成功快照（version={}）",
                    retained.version());
            visibleVersion.set(retained.version());
            return retained;
        }
        visibleVersion.set(0L);
        return legacyFallback();
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
     * 两级缓存的一致读（§6.3 的版本比对），顺带维护 {@link #lastGood}。
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
        if (resolved != null) {
            rememberGood(resolved);
        }
        visibleVersion.set(resolved == null ? 0L : resolved.version());
        return resolved;
    }

    /**
     * 记下「最近一次成功拿到的快照」。**绝不让版本倒退**：Redis 里可能残留一条比手上更旧的条目
     * （例如本地那份刚被别的实例回填过），把旧的盖上去会让永不失效的那一层反而变差。
     */
    private void rememberGood(ConfigSnapshot snapshot) {
        lastGood.accumulateAndGet(snapshot,
                (current, candidate) -> current == null || candidate.version() > current.version()
                        ? candidate
                        : current);
    }

    /**
     * 主动回源（singleflight）并回填两级缓存。返回空表示「这次没拿到真快照」。
     *
     * <p><b>回源操作本身是惰性的</b>（{@code Mono.defer}）：拿到「正在飞」名额之前**不构造**
     * 真正的 admin 调用。这不是风格问题 —— 若在这里直接构造 {@code adminClient.configSnapshot()}，
     * 每一个抢名额失败的调用者都会**先**向 admin 发一次请求再把它丢掉，singleflight 就只剩名字。
     * 这条是被 {@code concurrentMissesCollapseIntoASingleAdminCall} 抓出来的真实缺陷。
     *
     * <p><b>名额在 {@code doFinally} 里释放，且比较的是「真正发布出去的那一个引用」。</b>
     * 两处都必须是现在这个样子：
     * <ul>
     *   <li>比较的对象只能是发布出去的那个 {@link Mono}。写成 {@code compareAndSet(existing, null)}
     *       时，走到发布分支就必然有 {@code existing == null}，于是 CAS 永远失败、名额永不释放；
     *       而那两个提前返回（{@code existing != null}）又保证没有别的路径去清它。</li>
     *   <li>{@code doFinally} 而不是 {@code doOnSuccess}：空完成（admin 明确回「没有快照」）与异常
     *       （admin 不可达）都必须释放名额。{@code doOnSuccess} 在空完成时不触发 —— 那正好是
     *       「第一次回源失败后永久卡死」的那条路径。而 {@code doFinally} 晚于
     *       {@code Mono.fromRunnable} 里的缓存写入（缓存写在终态信号**之前**执行），
     *       所以「先写缓存、后清名额」的顺序仍然成立。</li>
     * </ul>
     * 单飞性质不受影响：名额在终态释放前到达的并发调用者拿到的仍是同一个已 {@code cache()} 的
     * {@link Mono}，只订阅一次、只回源一次。
     */
    public Mono<ConfigSnapshot> refresh() {
        Mono<ConfigSnapshot> existing = inFlight.get();
        if (existing != null) {
            return existing;
        }
        // 先把这个槽位要装的 Mono 造出来，再让 doFinally 关掉**同一个**引用（不能关一个 null）。
        AtomicReference<Mono<ConfigSnapshot>> published = new AtomicReference<>();
        Mono<ConfigSnapshot> fresh = Mono.defer(this::load)
                .doFinally(signal -> inFlight.compareAndSet(published.get(), null))
                .cache();
        published.set(fresh);
        if (inFlight.compareAndSet(null, fresh)) {
            return fresh;
        }
        // 输掉竞态：别人已经把名额占走了，等它的结果（不要自己再造一次回源）。
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
                .doOnNext(this::rememberGood)
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
     * 当前生效快照的 version 的**可观测副本**（{@code aihub.config.snapshot.version} 那条 gauge 的值）。
     * {@code 0} 表示手上还没有任何控制面快照（即正在服务遗留单渠道）。
     */
    public long visibleVersion() {
        return visibleVersion.get();
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
