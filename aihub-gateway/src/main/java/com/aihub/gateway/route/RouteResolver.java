package com.aihub.gateway.route;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * {@code model → 有序候选渠道}。返回**列表**而不是单个渠道：故障转移的全部能力来自「还有下一个」。
 *
 * <p>选择规则（评审按此判断，实施者不要「优化」）：
 * <ol>
 *   <li>候选集 = {@code routes} 与 {@code channels} 联表（两者都必须 ACTIVE 且渠道可用）；</li>
 *   <li><b>priority 数字小的组优先</b>。主备就是 priority=0 / priority=1 两条路由，
 *       不需要额外的「主/备」字段；</li>
 *   <li>组内<b>按权重随机</b>排序（{@code model_route.weight}）。权重非正数按 1 处理 ——
 *       零权重不该变成「永不选中」的隐形禁用，那会让一条配错的渠道静默失联；</li>
 *   <li>组内被熔断的渠道**排到该组末尾**而不是被删除：删掉会让「唯一候选恰好被熔断」退化成
 *       无候选（404），而全熔断时「试一次」比「直接告诉客户端没这个模型」更诚实（决策 10）；</li>
 *   <li><b>整组熔断就换下一组</b>：所有组的「未熔断」渠道先按 priority 升序排完（整组熔断的组
 *       在这一层不贡献任何渠道，于是下一组自然顶上来），<b>已熔断</b>的渠道作为**最后手段**
 *       接在整张列表的末尾 —— 低优先级组**不被丢弃**，否则「换下一组」无从谈起；</li>
 *   <li><b>两层都按权重随机</b>：健康层与熔断层**各自**在组内做权重随机排序，所以最后手段层
 *       的首位也是按权重抽出来的，而不是「配置里第一行的渠道永远打头」（否则 weight=0 的渠道
 *       会永久压过 weight=1000 的渠道，决策 10 的「照常选」就只剩一半）；</li>
 *   <li>所有组都熔断 → 列表以最高优先级的那一组开头（放行 + WARN + 计数器，决策 10）。</li>
 * </ol>
 *
 * <p><b>为什么列表包含全部优先级组</b>：调用方（relay）是**顺序尝试**这张列表的，
 * 「当前渠道失败就试下一个候选」就是故障转移本身；只返回「候选组」会让主渠道一旦整组熔断
 * 就再也回不去。健康层内组间严格按 priority 升序，组内才是权重随机，因此「priority 压过
 * weight」始终成立；熔断层只影响「所有健康候选都失败之后」的行为。
 *
 * <p><b>随机源的线程安全要求（接线必读）</b>：本类是请求路径上的**单例**，<b>不是</b>线程安全的
 * —— {@link RandomGenerator} 的接口明确写着实现**不必**线程安全，而
 * {@code RandomGenerator.getDefault()} 在当前 JDK 上返回的正是这类实现。并发抽取会让「按权重分流」
 * 出现重复或偏斜，而那恰恰是本类存在的理由。因此接线时**必须**注入一个线程安全的发生器：
 * {@link java.util.concurrent.ThreadLocalRandom#current()}（推荐，无锁）、
 * {@code SplittableRandom} 的每线程实例，或对单实例做同步包装。
 * 测试用固定种子做确定性断言；生产**不要**把种子写死。
 */
public class RouteResolver {

    private static final Logger log = LoggerFactory.getLogger(RouteResolver.class);

    /**
     * 全候选熔断分支的计数器（决策 10 的「WARN + 计数器」）。API 只返回
     * {@code List<ChannelDescriptor>}，里面**没有任何熔断标记**，调用方无法自己认出这种情况，
     * 因此计数必须在这里做。命名沿用项目前缀 {@code aihub.}（见 {@code aihub.metering.*}）。
     */
    public static final String ALL_BROKEN_METRIC = "aihub.route.all-broken";

    private final Supplier<ConfigSnapshot> snapshots;
    private final ChannelCircuitBreaker breaker;
    private final RandomGenerator random;
    private final Counter allBrokenCounter;

    /**
     * 不装配注册表的重载：计数进 Micrometer 的**全局复合注册表**
     * （Spring Boot 默认把各注册表挂在它上面，没有注册表时是安全空操作）。
     */
    public RouteResolver(Supplier<ConfigSnapshot> snapshots, ChannelCircuitBreaker breaker, RandomGenerator random) {
        this(snapshots, breaker, random, Metrics.globalRegistry);
    }

    public RouteResolver(Supplier<ConfigSnapshot> snapshots, ChannelCircuitBreaker breaker, RandomGenerator random,
                         MeterRegistry registry) {
        this.snapshots = snapshots;
        this.breaker = breaker;
        this.random = random;
        this.allBrokenCounter = registry.counter(ALL_BROKEN_METRIC);
    }

    /** 有序候选（最该用的排最前）。没有候选时返回空列表（调用方决定 404 还是回落遗留渠道）。 */
    public List<ChannelDescriptor> candidates(String model) {
        ConfigSnapshot snapshot = snapshots.get();
        if (snapshot == null) {
            // 冷启动的 supplier 可能还没有快照（返回 null）。空快照正是为这种情况准备的降级
            // （决策 6），绝不能让请求路径 NPE —— 那会变成一个客户端看不懂的 500。
            snapshot = ConfigSnapshot.empty();
        }
        List<ModelRouteDescriptor> routes = snapshot.routesFor(model);
        if (routes.isEmpty()) {
            return List.of();
        }
        // priority → {渠道, route 权重}；TreeMap 保证组的顺序 = priority 升序（数字小的先）。
        Map<Integer, List<Entry>> grouped = new TreeMap<>();
        // 同一个渠道可能有多行 ACTIVE 路由（admin 侧没有唯一约束）；重复计入会让它在权重抽取里
        // **双计**。ConfigSnapshot.channelsSupporting 同样做去重，这里保持同一语义：保留首次出现。
        Set<Long> seenChannelIds = new HashSet<>();
        for (ModelRouteDescriptor route : routes) {
            if (!seenChannelIds.add(route.channelId())) {
                continue;
            }
            snapshot.channel(route.channelId())
                    .filter(ChannelDescriptor::usable)
                    .ifPresent(channel -> grouped
                            .computeIfAbsent(route.priority(), ignored -> new ArrayList<>())
                            .add(new Entry(channel, route.weight())));
        }
        if (grouped.isEmpty()) {
            return List.of();
        }
        // 两层结果：所有组的「未熔断」渠道先按 priority 升序排完，再排「已熔断」的渠道。
        // 熔断渠道是**最后手段**（last resort），不是被删除：删掉会让「唯一候选恰好被熔断」
        // 退化成无候选（404），而全熔断时「试一次」比「告诉客户端没这个模型」更诚实（决策 10）。
        // 两层都按权重随机（组内），因此最后手段层的首位同样是权重抽出来的，而不是配置顺序。
        List<ChannelDescriptor> healthyTier = new ArrayList<>();
        List<ChannelDescriptor> brokenTier = new ArrayList<>();
        for (List<Entry> group : grouped.values()) {
            List<Entry> healthy = new ArrayList<>();
            List<Entry> broken = new ArrayList<>();
            for (Entry entry : group) {
                if (breaker.isOpen(entry.channel().id())) {
                    broken.add(entry);
                } else {
                    healthy.add(entry);
                }
            }
            healthyTier.addAll(weightedOrder(healthy));
            brokenTier.addAll(weightedOrder(broken));
        }
        boolean allBroken = healthyTier.isEmpty();
        List<ChannelDescriptor> ordered = new ArrayList<>(healthyTier.size() + brokenTier.size());
        ordered.addAll(healthyTier);
        ordered.addAll(brokenTier);
        if (allBroken) {
            // 全熔断时仍然给出候选（列表以最高优先级组的权重随机结果开头），但必须留下
            // 可观测的痕迹（WARN + 计数器，决策 10）。
            log.warn("模型 {} 的所有候选渠道都在熔断中（{} 个候选），仍按优先级顺序放行（best-effort，决策 10）",
                    model, ordered.size());
            allBrokenCounter.increment();
        }
        return ordered;
    }

    /** 首选渠道；没有候选时抛 {@link RouteSelectionException}（控制器翻成 404 model_not_found）。 */
    public ChannelDescriptor primary(String model) {
        List<ChannelDescriptor> candidates = candidates(model);
        if (candidates.isEmpty()) {
            throw new RouteSelectionException(model);
        }
        return candidates.get(0);
    }

    /**
     * 权重随机的**可复现**实现：按 {@code max(weight,1)} 做累加游走抽取，选中的与末位交换后重复。
     * 复杂度 O(n²)，候选数是个位数，不需要更聪明。
     */
    private List<ChannelDescriptor> weightedOrder(List<Entry> entries) {
        List<Entry> pool = new ArrayList<>(entries);
        List<ChannelDescriptor> ordered = new ArrayList<>(pool.size());
        while (!pool.isEmpty()) {
            long total = 0;
            for (Entry entry : pool) {
                total += Math.max(entry.weight(), 1);
            }
            long target = random.nextLong(total);
            int picked = 0;
            long walk = 0;
            for (int i = 0; i < pool.size(); i++) {
                walk += Math.max(pool.get(i).weight(), 1);
                if (target < walk) {
                    picked = i;
                    break;
                }
            }
            ordered.add(pool.get(picked).channel());
            pool.set(picked, pool.get(pool.size() - 1));
            pool.remove(pool.size() - 1);
        }
        return ordered;
    }

    /** 把「渠道 + route 权重」绑在一起传递的内部小类型。 */
    private record Entry(ChannelDescriptor channel, int weight) {
    }
}
