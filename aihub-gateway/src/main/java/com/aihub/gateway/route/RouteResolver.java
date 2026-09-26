package com.aihub.gateway.route;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *   <li>所有组都熔断 → 列表以最高优先级的那一组开头（放行 + WARN，决策 10）。</li>
 * </ol>
 *
 * <p><b>为什么列表包含全部优先级组</b>：调用方（relay）是**顺序尝试**这张列表的，
 * 「当前渠道失败就试下一个候选」就是故障转移本身；只返回「候选组」会让主渠道一旦整组熔断
 * 就再也回不去。健康层内组间严格按 priority 升序，组内才是权重随机，因此「priority 压过
 * weight」始终成立；熔断层只影响「所有健康候选都失败之后」的行为。
 *
 * <p>随机源由构造器注入（{@link RandomGenerator}）：测试用固定种子做确定性断言，
 * 生产用 {@code RandomGenerator.getDefault()}。这样「按权重分流」是真的被测过，而不是靠统计运气。
 */
public class RouteResolver {

    private static final Logger log = LoggerFactory.getLogger(RouteResolver.class);

    private final Supplier<ConfigSnapshot> snapshots;
    private final ChannelCircuitBreaker breaker;
    private final RandomGenerator random;

    public RouteResolver(Supplier<ConfigSnapshot> snapshots, ChannelCircuitBreaker breaker, RandomGenerator random) {
        this.snapshots = snapshots;
        this.breaker = breaker;
        this.random = random;
    }

    /** 有序候选（最该用的排最前）。没有候选时返回空列表（调用方决定 404 还是回落遗留渠道）。 */
    public List<ChannelDescriptor> candidates(String model) {
        ConfigSnapshot snapshot = snapshots.get();
        List<ModelRouteDescriptor> routes = snapshot.routesFor(model);
        if (routes.isEmpty()) {
            return List.of();
        }
        // priority → {渠道, route 权重}；TreeMap 保证组的顺序 = priority 升序（数字小的先）。
        Map<Integer, List<Entry>> grouped = new TreeMap<>();
        for (ModelRouteDescriptor route : routes) {
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
        List<ChannelDescriptor> healthyTier = new ArrayList<>();
        List<ChannelDescriptor> brokenTier = new ArrayList<>();
        for (List<Entry> group : grouped.values()) {
            List<Entry> healthy = new ArrayList<>();
            List<ChannelDescriptor> broken = new ArrayList<>();
            for (Entry entry : group) {
                if (breaker.isOpen(entry.channel().id())) {
                    broken.add(entry.channel());
                } else {
                    healthy.add(entry);
                }
            }
            // 组内按权重随机；熔断的排在该组末尾。
            healthyTier.addAll(weightedOrder(healthy));
            brokenTier.addAll(broken);
        }
        boolean allBroken = healthyTier.isEmpty();
        List<ChannelDescriptor> ordered = new ArrayList<>(healthyTier.size() + brokenTier.size());
        ordered.addAll(healthyTier);
        ordered.addAll(brokenTier);
        if (allBroken) {
            // 全熔断时仍然给出候选（列表以最高优先级组开头），但必须留下可观测的痕迹
            // （WARN + 调用方的计数器，决策 10）。
            log.warn("模型 {} 的所有候选渠道都在熔断中（{} 个候选），仍按优先级顺序放行（best-effort，决策 10）",
                    model, ordered.size());
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
