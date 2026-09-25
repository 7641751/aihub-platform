package com.aihub.gateway.auth;

import com.aihub.common.apikey.ApiKeyCacheCodec;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.admin.AdminClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Optional;

/**
 * 三级解析：Caffeine（本地，最快）→ Redis（跨实例共享）→ admin（真相源）。
 * <p>任何一级不可用都必须降级到下一级：缓存是可丢的派生数据，绝不能因为缓存故障而拒绝请求。
 * <p>Redis 载荷用 {@link ApiKeyCacheCodec} 编解码，key 前缀用它的公开常量
 * {@link ApiKeyCacheCodec#CACHE_KEY_PREFIX} —— 与 admin 写入时用的是**同一个常量**，
 * 因此不存在「两边字面量写歪了、缓存永远 miss 却无人报错」的隐患。
 * <p><b>线程模型</b>：{@code StringRedisTemplate} 是**阻塞**驱动（Lettuce 同步 API + 2s
 * {@code spring.data.redis.timeout}），而本类的调用方是 {@code WebFilter}，即 Netty event loop。
 * 直接在 event loop 上调用它，意味着 Redis 不可达时每个 event loop 线程都被按住 2 秒 ——
 * 共享同一个 loop 的所有请求（{@code /healthz} 也在内）一起卡住，而且这**不是**异常，下面的
 * {@code catch (RuntimeException)} 降级路径救不了一个正在阻塞的线程。因此两次 Redis I/O 都
 * 显式调度到 {@link Schedulers#boundedElastic()}（见 {@link #readRedis} 与 {@link #writeRedis}）：
 * 事件循环只做「拿本地缓存 / 组合 Mono」这类 CPU 工作，网络阻塞发生在弹性线程池上。
 */
@Component
public class ApiKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyResolver.class);

    /**
     * Redis 阻塞 I/O 的执行器。用 {@link Schedulers#boundedElastic()} 而不是
     * {@code single()}：阻塞任务天然可能堆积，单线程会把堆积变成队列延迟。
     */
    private static final Scheduler REDIS_SCHEDULER = Schedulers.boundedElastic();

    /**
     * 负缓存：同一个不存在的 key 不必每次都打 admin。usable() 为 false，调用方据此 401。
     * <p><b>只存在于本地 Caffeine，不写 Redis。</b>admin 侧只把**命中**的 view 写进共享缓存，
     * 从不写「不存在」这种载荷；解析器这边也只对非 MISS 结果调 {@code writeRedis}。既然两端
     * 都不写，Redis 里就不存在 MISS 载荷，读取侧也就没有可判的分支 —— 不要在这里凭空发明一个
     * 跨服务契约。
     * <p>与过滤器在空 {@code Mono} 上的兜底共用同一个实例（{@link ApiKeyView#UNUSABLE}），
     * 不再各自 {@code new} 一份同形哨兵。
     */
    private static final ApiKeyView MISS = ApiKeyView.UNUSABLE;

    private final Cache<String, ApiKeyView> local;
    private final StringRedisTemplate redis;
    private final AdminClient adminClient;
    private final Duration keyCacheTtl;

    public ApiKeyResolver(AuthProperties properties, AdminClient adminClient, StringRedisTemplate redis) {
        this.local = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(properties.localCacheTtl())
                .build();
        this.redis = redis;
        this.adminClient = adminClient;
        this.keyCacheTtl = properties.keyCacheTtl();
    }

    /**
     * 解析结果保证非 null：不存在/不可用的 key 会得到 {@link #MISS}（{@code usable() == false}），
     * 而不是空 Mono —— 调用方只需判断一次，不必再处理「没有值」的分支。
     */
    public Mono<ApiKeyView> resolve(String keyHash) {
        ApiKeyView cached = local.getIfPresent(keyHash);
        if (cached != null) {
            return Mono.just(cached);
        }
        // 第一级未命中才碰 Redis。readRedis 整体跑在 boundedElastic 上（不可用/超时都不占 event loop），
        // 读失败在内部折算成 null，于是 onErrorResume 之外**没有**异常逃逸路径，三级顺序不变。
        return readRedis(keyHash)
                .onErrorResume(ex -> {
                    // fromCallable 已经吞掉 RuntimeException；这里只兜底 Throwable（如 OOM、Error）。
                    // 即便是这类错误也必须降级 —— 缓存故障绝不能让客户端拿到 500。
                    log.warn("Redis 读取异常，降级回源 admin: {}", ex.toString());
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.defer(() -> resolveFromAdmin(keyHash)))
                .map(view -> {
                    // 命中与「确实不存在」（MISS）都写本地负缓存，行为与改前一致。
                    local.put(keyHash, view);
                    return view;
                });
    }

    /**
     * 第三级：admin 回源。异常与「不存在」都折算成 {@link #MISS}（usable=false → 调用方 401），
     * 真正的命中才回填 Redis（与 admin 侧只写命中载荷的约定一致）。
     * <p>Mono.defer 同时保证 adminClient.resolve 的**同步**抛错（例如空 internal secret 时
     * InternalHmac.sign 抛 IllegalStateException）也落进 onErrorResume。
     */
    private Mono<ApiKeyView> resolveFromAdmin(String keyHash) {
        return Mono.defer(() -> adminClient.resolve(keyHash))
                .onErrorResume(ex -> {
                    // 这一条兜的是「AdminClient 实现把异常抛出来了」（真实实现的传输/5xx 已在
                    // AdminClient.Http 里分别打了 ERROR）。它同样**不是**「key 不存在」，
                    // 所以按 ERROR 记录，与「key 不存在」的 debug 日志区分开。
                    log.error("admin 回源抛出异常（非「key 不存在」），按「key 不存在」处理（fail-closed）: {}",
                            ex.toString());
                    return Mono.just(Optional.empty());
                })
                .map(maybeView -> {
                    ApiKeyView view = maybeView.orElse(MISS);
                    if (view != MISS) {
                        writeRedis(keyHash, view);
                    }
                    return view;
                });
    }

    /**
     * 第二级：Redis 读取。**阻塞调用，整体切到 {@link #REDIS_SCHEDULER}**，event loop 上不留任何
     * 网络 I/O；失败（不可达/超时/载荷畸形）一律返回空 Mono，由调用方落到 admin。
     */
    private Mono<ApiKeyView> readRedis(String keyHash) {
        return Mono.fromCallable(() -> {
            try {
                String payload = redis.opsForValue().get(ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash);
                return payload == null ? null : ApiKeyCacheCodec.decode(payload);
            } catch (RuntimeException e) {
                log.warn("Redis 读取失败，降级回源 admin: {}", e.toString());
                return null;
            }
        }).subscribeOn(REDIS_SCHEDULER);
    }

    /**
     * 回填 Redis。**不参与响应链路**：整段（编码 + 阻塞 set）切到 {@link #REDIS_SCHEDULER} 上异步执行，
     * event loop 只做一次 {@code subscribe()} 就不再过问。写失败只记日志，绝不影响本次请求的结果。
     */
    private void writeRedis(String keyHash, ApiKeyView view) {
        Mono.fromRunnable(() -> {
            try {
                redis.opsForValue().set(ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash,
                        ApiKeyCacheCodec.encode(view), keyCacheTtl);
            } catch (RuntimeException e) {
                log.warn("Redis 写入失败，忽略: {}", e.toString());
            }
        }).subscribeOn(REDIS_SCHEDULER).subscribe();
    }
}
