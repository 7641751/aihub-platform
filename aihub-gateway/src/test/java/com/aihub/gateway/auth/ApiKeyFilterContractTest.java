package com.aihub.gateway.auth;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 过滤器级契约测试：不起 Spring 上下文、不碰 socket、不需要 Docker/Redis（只把
 * {@code StringRedisTemplate} 这一个阻塞边界换成桩），直接驱动 {@code filter(exchange, chain)}。
 *
 * <p>之所以另开一个类而不是塞进 {@code ApiKeyAuthFilterTest}：本类要求 {@link ApiKeyResolver}
 * 可被替身挡住（例如返回空 Mono），并且需要在**进程内**观测 exchange 属性；而
 * {@code ApiKeyAuthFilterTest} 的 {@code @Primary} 假 adminClient 是类级的、socket 客户端也看不见
 * exchange 属性。两者关注点不同，合在一起只会互相绑手绑脚。
 *
 * <p>这里钉住四件事，改前都只是「今天恰好对」：空 Mono 兜底 401（Finding B）、成功时的
 * {@link ApiKeyAuthFilter#ATTRIBUTE_KEY_VIEW}（Finding E）、过滤器顺序（Finding D）、
 * 以及 Redis 阻塞 I/O 落在线程池而非 event loop 上（Finding A）。
 *
 * <p>Finding A 的观测对象有一次是**发后不管**的异步调用，所以那两条断言必须先等观测点发生
 * （{@link #awaitObserved}），否则断言本身就是在赌调度顺序。
 */
class ApiKeyFilterContractTest {

    private static final AuthProperties PROPERTIES =
            new AuthProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(5), "http://127.0.0.1:1");

    private static final ApiKeyView VALID_VIEW =
            new ApiKeyView("ak_valid", 7L, "acme", ApiKeyView.STATUS_ACTIVE, null);

    private static final String VALID_SECRET = "valid-secret";

    /**
     * 契约：{@code ApiKeyResolver} 永不返回空 Mono。这里**故意**打破契约（替身返回
     * {@code Mono.empty()}）：请求本来会通过过滤器，必须仍然拿到 401 + OpenAI 错误体 ——
     * 既不是 200（放行），也不是「没有状态码就挂住」。
     *
     * <p>把 {@code ApiKeyAuthFilter} 里的 {@code .switchIfEmpty(Mono.just(UNRESOLVED))} 删掉，
     * 本用例会以「响应状态码为 null / 链从未被调用」变红，而不是超时挂死。
     */
    @Test
    void emptyResolverResultIsStillRejectedAsInvalidApiKey() {
        ApiKeyResolver emptyResolver = mock(ApiKeyResolver.class);
        when(emptyResolver.resolve(anyString())).thenReturn(Mono.empty());
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(PROPERTIES, emptyResolver);
        MockServerWebExchange exchange = exchange("Bearer ak_valid." + VALID_SECRET);
        AtomicReference<ServerWebExchange> reachedDownstream = new AtomicReference<>();

        filter.filter(exchange, downstream(reachedDownstream)).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bodyOf(exchange)).contains("\"code\":\"invalid_api_key\"");
        assertThat(reachedDownstream.get()).as("空 Mono 绝不能被放行到下游链").isNull();
    }

    /**
     * 契约：成功鉴权必须把解析结果写进 {@code ATTRIBUTE_KEY_VIEW}（M2 计量 / M3 配额要读它）。
     * 这是**真观测属性**：断言的是下游链拿到的那个 exchange 里的值，不是重述过滤器代码。
     * 删掉 {@code getAttributes().put(...)}，本用例立刻变红。
     */
    @Test
    void successfulAuthenticationPublishesTheResolvedViewAsExchangeAttribute() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(PROPERTIES,
                resolverWith(Optional.of(VALID_VIEW), new AtomicReference<>(), new AtomicReference<>()));
        MockServerWebExchange exchange = exchange("Bearer ak_valid." + VALID_SECRET);
        AtomicReference<ServerWebExchange> reachedDownstream = new AtomicReference<>();

        filter.filter(exchange, downstream(reachedDownstream)).block(Duration.ofSeconds(5));

        assertThat(reachedDownstream.get()).as("可用密钥必须放行到下游链").isNotNull();
        // 显式取成 ApiKeyView：getAttribute 是泛型方法，交给 assertThat 自行推断会退化成 Object。
        ApiKeyView published = reachedDownstream.get().getAttribute(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW);
        assertThat(published).isEqualTo(VALID_VIEW);
        assertThat((ApiKeyView) exchange.getAttribute(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW))
                .as("handler 与过滤器共用同一个 exchange").isEqualTo(VALID_VIEW);
    }

    /**
     * 契约：过滤器顺序是承重的（必须早于 handler mapping 里的路由解析）。
     * 读的是**运行时**类上的 {@code @Order}，不是源码里那行字面量：注解被摘掉、或数值被改成
     * 「排在 RoutingFunction 之后」都会变红；同时断言它确实实现 {@link WebFilter}（Spring 只按这个
     * 接口收集过滤器，不实现它就等于没接线）。
     */
    @Test
    void filterOrderIsPinnedAheadOfRouting() {
        Order order = ApiKeyAuthFilter.class.getAnnotation(Order.class);

        assertThat(WebFilter.class).isAssignableFrom(ApiKeyAuthFilter.class);
        assertThat(order).as("@Order 是「过滤器先于路由」的关键，不能被摘掉").isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
    }

    /**
     * Finding A 的行为证据，顺带钉住三级顺序：Redis 不可用时
     * (a) 阻塞读发生在 {@code boundedElastic} 线程上 —— 不是 event loop（即本用例的调用线程）；
     * (b) 请求照旧降级到 admin 并成功放行；
     * (c) 命中 admin 后的回填写同样不在调用线程上。
     *
     * <p>把 {@code readRedis} 的 {@code subscribeOn(REDIS_SCHEDULER)} 拿掉，(a) 立刻变红：阻塞调用
     * 就又回到 event loop 上了。
     *
     * <p><b>两个观测点都必须先等它真的发生</b>（见 {@link #awaitObserved}）：Redis 回填是
     * <b>发后不管</b>的（{@code writeRedis} 里 {@code subscribeOn(...).subscribe()}），
     * 它跑完的时刻与 {@code block()} 返回的时刻没有先后关系。早先这里直接读
     * {@code writeThread.get()}，等于让断言和一次异步调度赛跑 —— 实测在热态重复调用下
     * 约 2/3 的轮次会读到 {@code null}（详情见 {@code .superpowers/sdd/task-11-report.md}）。
     * 等待是有界的（5 秒），「回填从未发生」仍然会红，只是不再靠运气。
     */
    @Test
    void redisOutageStillResolvesThroughAdminWithBothRedisCallsOffTheCallersThread() throws InterruptedException {
        AtomicReference<String> readThread = new AtomicReference<>();
        AtomicReference<String> writeThread = new AtomicReference<>();
        ApiKeyResolver resolver = resolverWith(Optional.of(VALID_VIEW), readThread, writeThread);
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(PROPERTIES, resolver);
        MockServerWebExchange exchange = exchange("Bearer ak_valid." + VALID_SECRET);
        AtomicReference<ServerWebExchange> reachedDownstream = new AtomicReference<>();
        String callerThread = Thread.currentThread().getName();

        filter.filter(exchange, downstream(reachedDownstream)).block(Duration.ofSeconds(5));

        assertThat(reachedDownstream.get()).as("Redis 故障必须降级到 admin 并放行").isNotNull();
        assertThat((ApiKeyView) exchange.getAttribute(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW)).isEqualTo(VALID_VIEW);
        assertThat(awaitObserved(readThread, "阻塞 Redis 读"))
                .as("阻塞 Redis 读必须在弹性线程池上，不能是 event loop（%s）", callerThread)
                .startsWith("boundedElastic-").isNotEqualTo(callerThread);
        assertThat(awaitObserved(writeThread, "命中 admin 后的 Redis 回填"))
                .as("阻塞 Redis 写也同样不能在 event loop 上")
                .startsWith("boundedElastic-").isNotEqualTo(callerThread);
    }

    // --- 测试脚手架 -------------------------------------------------------

    /**
     * 等一个「已经发生的观测点」：被观测的调用是异步的，只能等到它把线程名写进来为止。
     * 有界 5 秒，等不到就带着明确的说明变红（而不是抛一个与真实原因无关的超时）。
     */
    private static String awaitObserved(AtomicReference<String> observed, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (observed.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(observed.get()).as("%s 在 5 秒内没有发生", what).isNotNull();
        return observed.get();
    }

    /**
     * 真实 {@link ApiKeyResolver} + 必然失败的 Redis 桩 + 固定 admin 应答。
     * 桩在被调用时记录线程名，于是「阻塞调用跑在哪个线程上」可以被直接断言。
     * {@code lenient} 是因为本地 Caffeine 命中时 Redis 压根不会被调用。
     */
    @SuppressWarnings("unchecked")
    private static ApiKeyResolver resolverWith(Optional<ApiKeyView> adminResult,
                                              AtomicReference<String> readThread,
                                              AtomicReference<String> writeThread) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenAnswer(invocation -> {
            readThread.set(Thread.currentThread().getName());
            throw new RedisConnectionFailureException("测试桩：Redis 不可用");
        });
        // set(...) 返回 void，不能用 when(...)：对 void 方法必须用 doAnswer(...).when(mock)。
        lenient().doAnswer(invocation -> {
            writeThread.set(Thread.currentThread().getName());
            throw new RedisConnectionFailureException("测试桩：Redis 不可用");
        }).when(valueOps).set(anyString(), anyString(), ArgumentMatchers.<Duration>any());
        AdminClient adminClient = keyHash -> Mono.just(adminResult);
        return new ApiKeyResolver(PROPERTIES, adminClient, redis);
    }

    private static MockServerWebExchange exchange(String authorization) {
        return MockServerWebExchange.from(MockServerHttpRequest
                .method(HttpMethod.POST, "/v1/chat/completions")
                .header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .build());
    }

    /** 下游链替身：记录「有没有被放行」，并给属性断言提供观测点。 */
    private static WebFilterChain downstream(AtomicReference<ServerWebExchange> reached) {
        return actualExchange -> {
            reached.set(actualExchange);
            actualExchange.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        };
    }

    private static String bodyOf(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
    }
}
