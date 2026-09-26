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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p><b>「手上还有快照时绝不回源」是故障期的第一性规则</b>（2026 二次复审修复 1）：只要两级缓存
 * 或 {@link #lastGood} 还能服务，就**一次回源都不做**。旧实现把 {@code refreshBlocking()} 放在
 * 读取 {@link #lastGood} **之前**，于是「Redis 挂 + admin 挂」时每一个请求都要先付一次完整回源
 * （最长 5 s 的阻塞）才终于服务那份本来就在手上的快照 —— 比不做最近快照兜底时还差。
 *
 * <p><b>回源有冷却窗口（不是限速，是抑制）</b>：一次失败的尝试会占住单飞槽位直到它结束，
 * 所以尝试之间是**串行**的，但**不**受限速 —— 「5 秒超时」是单次尝试的**时长上限**，不是两次
 * 尝试之间的**间隔下限**。admin 快速失败（连接被拒、或返回空快照）时重试速率会逼近请求速率。
 * 因此每次失败的尝试都会记录一个「冷却到什么时候」（{@code aihub.config.refresh-cooldown}，
 * 默认 **5 s**），窗口内不再回源。
 *
 * <p><b>日志与指标在故障期必须是有界的</b>：回源失败的 WARN 只在**一次故障期的开头**打一条，
 * 之后沉默到「一次成功回源」或「超过 {@link #COOLDOWN_LOG_INTERVAL_MILLIS}」，同时
 * {@code aihub.config.snapshot.refresh.failures} 把每一次真实尝试都计数 —— 日志给人看，
 * 计数给告警看。旧实现是「并发重试 = 每请求两条 WARN」。
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
 * <b>名额必须在每一个终态（成功 / 空 / 异常 / 取消超时）上释放</b> —— 只在成功时释放会让
 * 「第一次回源失败」的那次空信号被永久重放，实例再也回不到 admin（详见 {@link #refresh()}）。
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

    /**
     * 长故障期里重复播报「仍在降级」的最小间隔。默认 60 s：一次故障期里日志条数有界
     * （约 1 条/分钟），但值班的人仍能看出「这个降级还在持续」。
     */
    private static final long COOLDOWN_LOG_INTERVAL_MILLIS = 60_000L;

    /** 抢单飞名额失败后的重试次数上界（每次重试前重新读一次槽位）。 */
    private static final int PUBLISH_ATTEMPTS = 4;

    /** 成功从 admin 回源并回填缓存的次数（「真快照」的获取速率，运维用它区分「一直命中缓存」与「一直在回源」）。 */
    public static final String REFRESHED_METRIC = "aihub.config.snapshot.refreshed";

    /**
     * 回源**尝试失败**的次数（含 admin 报错、空快照、以及 {@code refreshBlocking()} 超时）。
     * 与 {@link #REFRESHED_METRIC} 一起构成「故障持续了多久」的信号 —— WARN 会被限流，
     * 计数不会，告警必须架在计数上。
     */
    public static final String REFRESH_FAILURES_METRIC = "aihub.config.snapshot.refresh.failures";

    /** 当前生效快照 version 的指标名（`0` = 手上还没有任何控制面快照）。 */
    public static final String VERSION_METRIC = "aihub.config.snapshot.version";

    private final ConfigCache cache;
    private final AdminClient adminClient;
    private final UpstreamProperties upstream;
    /** 回源冷却窗口（{@code aihub.config.refresh-cooldown}）与两级缓存的 TTL 都在这里。 */
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
     *
     * <p>它同时是**故障期的短路开关**：非空就说明「手上还有快照」，于是冷却窗口内一次回源都不做
     * （{@link #inCooldown()}），请求不再为一份本已拿得到的快照付出回源代价。
     */
    private final AtomicReference<ConfigSnapshot> lastGood = new AtomicReference<>();
    private final Counter refreshedCounter;
    private final Counter refreshFailuresCounter;
    /**
     * 下一次允许回源的最早时刻（{@code System.currentTimeMillis()} 基准），`0` = 立即可回源。
     * 只在**失败的**尝试之后推进；一次成功回源或一次可用的缓存命中都会把它清回 `0`。
     */
    private final AtomicLong nextAttemptAt = new AtomicLong();
    /**
     * 「正处于故障期」。用 CAS 而不是 {@code synchronized}：这条路径在 event loop 上，
     * 它只决定**日志打不打**，不决定数据面行为，因此绝不能在这里引入锁。
     */
    private final AtomicBoolean degraded = new AtomicBoolean();
    /** 故障期内上一条 WARN 的时间戳（毫秒）；与 {@link #degraded} 一起把 WARN 限流成「一次故障期一条 + 每分钟一条」。 */
    private final AtomicLong lastDegradedLogAt = new AtomicLong();
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
        this.refreshFailuresCounter = registry.counter(REFRESH_FAILURES_METRIC);
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
            // 缓存能服务就说明系统是健康的：结束当前故障期（冷却窗口不受影响，见 clearDegraded()）。
            clearDegraded();
            return resolved;
        }
        // 三级全空。**先看是不是还在冷却窗口里**：窗口内连一次回源都不做。
        if (inCooldown()) {
            // 冷却期内唯一还能变的来源是 Redis（别的实例可能刚写进一个新版本）——
            // 只重读那一次，不再付回源的代价。
            ConfigSnapshot late = resolve();
            if (late != null) {
                clearDegraded();
                return late;
            }
            return serveRetainedOrLegacy();
        }
        // 这是唯一允许同步回源的地方（也是唯一可能阻塞的地方）。
        ConfigSnapshot loaded = refreshBlocking();
        if (loaded != null) {
            clearDegraded();
            // 冷启动成功：**就在这里**报出被服务的那份快照的版本。只在 resolve() 里报的话，
            // 第一次服务响应时 gauge 还是 0 —— 控制面明明已经给了 version，对外却显示「还没有」。
            visibleVersion.set(loaded.version());
            return loaded;
        }
        // 回源没拿到真快照。必须**再看一次两级缓存**：Redis 命中/回填走的是回源这条路径
        // （admin 返回空快照时回源不写任何缓存），而这一整个过程中别的实例可能刚把新版本写进 Redis。
        ConfigSnapshot afterRefresh = resolve();
        if (afterRefresh != null) {
            clearDegraded();
            return afterRefresh;
        }
        return serveRetainedOrLegacy();
    }

    /**
     * 两级缓存都过期/不可用、回源也没拿到真快照时的最后两级兜底（决策 6）。
     *
     * <p>顺序刻意固定为 {@code lastGood → legacyFallback}：手上那份**永不失效**的最近快照仍然比
     * 「只剩一条遗留渠道」好得多（决策 6 的「哪怕已过期」）；它不写任何缓存，因此不会复活陈旧
     * 共享条目。到这一层时**已经不再回源** —— 调用方（{@link #current()}）已经决定过这一次
     * 要不要回源了。
     */
    private ConfigSnapshot serveRetainedOrLegacy() {
        ConfigSnapshot retained = lastGood.get();
        if (retained != null) {
            // 这条 WARN 曾经是**每请求一条**（并发重试时代每个请求都会走到这里）。
            // 现在被限流成「一次故障期一条 + 每分钟一条」，见 #logDegradedOncePerEpisode。
            logDegradedOncePerEpisode("两级缓存均已过期/不可用且 admin 不可达，继续使用最近一次成功快照（version={}）",
                    retained.version());
            visibleVersion.set(retained.version());
            return retained;
        }
        visibleVersion.set(0L);
        return legacyFallback();
    }

    /**
     * 冷却窗口门禁：**窗口内绝不再回源**。
     *
     * <p>返回 {@code true} 表示「调用方直接把返回值服务出去，这一次不回源」。它**与手上有没有快照
     * 无关**（冷启动同样要抑制重试）；{@link #lastGood} 只决定后续服务的是那份快照还是遗留单渠道，
     * 以及要不要播报一条降级 WARN。
     */
    private boolean inCooldown() {
        long now = System.currentTimeMillis();
        long blockedUntil = nextAttemptAt.get();
        if (blockedUntil == 0L || now >= blockedUntil) {
            return false;
        }
        ConfigSnapshot retained = lastGood.get();
        if (retained != null) {
            logDegradedOncePerEpisode("配置回源处于冷却窗口（还有 {} ms），本次不回源，继续使用最近一次成功快照（version={}）",
                    blockedUntil - now, retained.version());
        }
        return true;
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
     *       所以「先写缓存、后清名额」的顺序仍然成立。第四次终态是**取消**（
     *       {@code refreshBlocking()} 超时），{@code doFinally} 同样会触发 —— 超时后名额不会泄漏。</li>
     * </ul>
     * 单飞性质不受影响：名额在终态释放前到达的并发调用者拿到的仍是同一个已 {@code cache()} 的
     * {@link Mono}，只订阅一次、只回源一次。
     *
     * <p><b>抢名额失败时重试 CAS，而不是把自己的 {@link Mono} 交出去</b>（2026 二次复审修复 3）：
     * 名额被释放后 {@code inFlight.get()} 可以读到 {@code null}，此时返回自己那个**从未发布**的
     * {@code fresh} 会让调用方订阅到一条独立的回源流，于是同一次 miss 产生**两个并发回源**；
     * 而 {@code load()} 的缓存写入又是无版本守卫的，晚到的旧响应就会覆盖掉新快照。现在
     * 要么拿到赢家的 {@link Mono}，要么以「唯一发布者」的身份重试 CAS —— 两者都只可能有一次回源。
     *
     * <p><b>冷却窗口</b>：窗口内直接返回空（调用方服务 {@link #lastGood}），这是「失败重试速率
     * 不得超过 1/冷却窗口」的那道闸。它是**抑制**而不是限速：没有它时，快速失败的 admin 会让
     * 重试速率逼近请求速率（5 s 超时管的是单次尝试的时长，不是两次尝试的间隔）。
     */
    public Mono<ConfigSnapshot> refresh() {
        Mono<ConfigSnapshot> existing = inFlight.get();
        if (existing != null) {
            return existing;
        }
        if (inCooldown()) {
            return Mono.empty();
        }
        markAttemptWindow();
        // 先把这个槽位要装的 Mono 造出来，再让 doFinally 关掉**同一个**引用（不能关一个 null）。
        AtomicReference<Mono<ConfigSnapshot>> published = new AtomicReference<>();
        Mono<ConfigSnapshot> fresh = Mono.defer(this::load)
                .doFinally(signal -> inFlight.compareAndSet(published.get(), null))
                .cache();
        published.set(fresh);
        for (int attempt = 0; attempt < PUBLISH_ATTEMPTS; attempt++) {
            if (inFlight.compareAndSet(null, fresh)) {
                return fresh;
            }
            // 输掉竞态：别人已经把名额占走了，等它的结果（不要自己再造一次回源）。
            Mono<ConfigSnapshot> winner = inFlight.get();
            if (winner != null) {
                return winner;
            }
            // 槽位在我们读取与 CAS 之间被赢家释放了：再试一次 CAS，而不是把自己的 Mono
            // （一条从未发布、无人共享的回源流）交出去 —— 那会让这次 miss 产生第二次回源。
        }
        // 极端争用下重试了 PUBLISH_ATTEMPTS 次都没抢到：返回赢家（或交由调用方降级）。
        Mono<ConfigSnapshot> lastWinner = inFlight.get();
        return lastWinner == null ? Mono.empty() : lastWinner;
    }

    /** 把冷却窗口推后到「现在 + {@code refreshCooldown}」。只在这里写 {@link #nextAttemptAt}。 */
    private void markAttemptWindow() {
        nextAttemptAt.set(System.currentTimeMillis() + Math.max(0L, properties.refreshCooldown().toMillis()));
    }

    /** 真正的回源 + 双回填。**只会被 singleflight 的赢家订阅一次**。 */
    private Mono<ConfigSnapshot> load() {
        return adminClient.configSnapshot()
                .map(maybe -> maybe.orElse(null))
                .map(this::usable)
                .flatMap(snapshot -> {
                    if (snapshot == null) {
                        // admin 明确回「没有快照」：与异常同属一次失败的尝试（计数 + 播报限流过的 WARN）。
                        // 不写任何缓存，也不广播「故障结束」—— 空表不代表控制面恢复了。
                        registerRefreshFailure(null);
                        return Mono.empty();
                    }
                    return Mono.fromRunnable(() -> {
                        cache.putLocal(snapshot);
                        cache.writeRedis(snapshot);
                    }).thenReturn(snapshot);
                })
                .doOnNext(snapshot -> {
                    refreshedCounter.increment();
                    rememberGood(snapshot);
                })
                // 超时必须发生在**流内部**（而不是只在 refreshBlocking 的 block(timeout) 上）。
                // block(Duration) 超时只取消它自己那个阻塞订阅者，**不会**把取消信号传回上游
                // （2026 二次复审修复 4 实测：doFinally 一次都没有触发，名额永久泄漏）。
                // 结果就是一个「永不回应」的 admin 会把这个实例**永久钉死**在一条挂起的回源上：
                // 之后每次读都订阅同一条永不终结的流，一路挂到 5 s 超时 —— 比缺少兜底更糟。
                // 用 timeout 让超时成为流内部的错误终止，doFinally 才能照常释放名额。
                .timeout(REFRESH_TIMEOUT)
                .onErrorResume(ex -> {
                    // admin 不可达不是错误路径，而是已设计的降级：调用方继续用手上的快照。
                    registerRefreshFailure(ex);
                    return Mono.empty();
                });
    }

    /**
     * 记一次**失败的回源尝试**：计数、按下限流规则决定要不要打 WARN。
     *
     * <p>冷却窗口不在这里推后 —— 它在尝试**开始**时就已经推后（{@link #markAttemptWindow()}），
     * 所以「两次尝试之间的间隔 ≥ {@code refreshCooldown}」与这一次尝试成功还是失败无关。
     *
     * @param ex 触发降级的异常；{@code null} 表示 admin 回了一份「空快照」（不是异常，但同样是一次失败的尝试）
     */
    private void registerRefreshFailure(Throwable ex) {
        refreshFailuresCounter.increment();
        logDegradedOncePerEpisode(
                "配置快照回源失败，继续使用已有快照（{} 内不再回源）: {}",
                properties.refreshCooldown(), ex == null ? "admin 返回空快照" : ex.toString());
    }

    /**
     * 「进入故障期」的那一条 WARN：一次故障期**只打一条**，之后要么沉默到下一次成功回源，
     * 要么每 {@link #COOLDOWN_LOG_INTERVAL_MILLIS} 补一条 —— 长故障期里日志条数因此有界
     * （旧实现是每请求两条：回源失败一条 + 服务最近快照一条）。
     *
     * <p>用 CAS 认领「谁负责打这条日志」而不是 {@code synchronized}：本方法在 event loop 上，
     * 日志的取舍绝不能变成锁。
     */
    private void logDegradedOncePerEpisode(String message, Object... arguments) {
        long now = System.currentTimeMillis();
        if (!degraded.compareAndSet(false, true)) {
            long last = lastDegradedLogAt.get();
            if (now - last < COOLDOWN_LOG_INTERVAL_MILLIS) {
                return; // 还在这条故障期的「安静期」内。
            }
            // 超过播报间隔：用 CAS 认领，避免并发请求同时补打。
            if (!lastDegradedLogAt.compareAndSet(last, now)) {
                return;
            }
        } else {
            lastDegradedLogAt.set(now);
        }
        log.warn(message, arguments);
    }

    /**
     * 一次可用的快照到手 = 故障期结束：清掉降级标记，让日志重新从「一次故障期一条」开始计数。
     *
     * <p><b>刻意不碰冷却窗口</b>：窗口在一次尝试**开始时**就推后了（{@link #markAttemptWindow()}），
     * 清掉它就等于让「成功回源 + 缓存随即过期」这一串立刻再各付一次回源 —— 那正是要修的行为。
     */
    private void clearDegraded() {
        degraded.set(false);
    }

    /**
     * 同步版回源（供 {@link #current()} 在请求路径上用）：拿不到就返回 null。
     *
     * <p>{@code block()} **不带超时**：超时由 {@link #load()} 里的 {@code .timeout(REFRESH_TIMEOUT)}
     * 负责，那样超时才会作为流内部的错误终止传播，让 {@code doFinally} 释放单飞名额。
     * 只在这里用 {@code block(Duration)} 会留下一个永久泄漏的名额（见 {@link #load()} 的注释）。
     */
    private ConfigSnapshot refreshBlocking() {
        try {
            return refresh().block();
        } catch (RuntimeException e) {
            // 超时 / 取消 / 其他终态异常：这也是一次失败的尝试 —— 它同样要计数、并打一条限流过的日志。
            // 不这么做的话，一个「永不回应」的 admin 会以每 5 秒一次的节奏被每个请求打一遍。
            registerRefreshFailure(e);
            return null;
        }
    }

    /**
     * 失效本地缓存，并**同时放行冷却窗口**：失效是「配置刚刚变了」的显式信号（未来的 Pub/Sub
     * 监听器会用它，见决策 16），此时不该让一次陈旧的失败尝试挡住立刻重新回源。
     */
    public void invalidate() {
        nextAttemptAt.set(0L);
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
     * {@link #REFRESH_FAILURES_METRIC} 的当前读数（**每一次真实失败的回源尝试**加一，被冷却窗口
     * 挡下的请求不算）。它是「WARN 被限流之后，故障持续了多久」的对外信号 —— 告警必须架在它上面。
     */
    double refreshFailureCount() {
        return refreshFailuresCounter.count();
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
