package com.aihub.gateway.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线程模型：**限流判定绝不占用订阅它的那个线程**（复审 Fix 2 的 (b)）。
 *
 * <p><b>为什么这件事必须单独证</b>：判定这一跳上有两处**阻塞**调用 —— 策略解析会在两级缓存
 * 未命中时做同步 Redis GET（必要时还有一次同步回源），而 {@code RedisRateLimiter} 是阻塞的 Lua
 * 调用。{@code spring.data.redis.timeout} 是 2 秒：Redis **卡住**（不是拒绝）时，在 event loop 上做这些
 * 事会把共享同一个 loop 的所有请求一起按住 2 秒 —— 而且它不是异常，{@code catch} 救不了一个
 * 正在阻塞的线程。这与 M2 给 {@code ApiKeyResolver} 的 Redis 读取所做处置是同一条理由。
 *
 * <p><b>为什么不用「真 Redis 卡住」来证</b>：那需要让一个 socket 接受连接却永不回应，
 * 而本项目不允许测试依赖 Docker/外部服务（也没有这样的桩），端口不会「半开」，因此做不到
 * 确定性驱动。这里改用一个**确定性的等价物**：把「阻塞的判定」直接做成一个可控的阻塞替身
 * （{@code BlockingRateLimiter}），并把它订阅在一个**专用单线程调度器**上扮演 event loop。
 * 判据因此是执行层面的、与 Redis 无关：判定停在哪个线程上、以及那个线程期间还能不能干活。
 *
 * <p>两条互补的判据：
 * <ol>
 *   <li><b>判定不在订阅线程上执行</b> —— 订阅线程名与判定线程名必须不同；
 *       「同一个线程」正是修复前（同步调用）的形态，这条会红。</li>
 *   <li><b>判定阻塞期间那个线程仍然可用</b> —— 判定卡住时向订阅线程投一个探针，探针必须
 *       在很短的时间内落地。修复前判定同步跑在订阅线程上，探针必须等判定让出线程（约 300 ms），
 *       这条同样会红。</li>
 * </ol>
 */
class RateLimitEventLoopTest {

    /** 扮演 event loop 的调度器：单线程，名字可辨。 */
    private final Scheduler eventLoop = Schedulers.newSingle("test-event-loop");

    @AfterEach
    void shutDownEventLoop() {
        eventLoop.dispose();
    }

    @Test
    void theBlockingDecisionRunsOffTheSubscribingThreadAndLeavesItFree() throws Exception {
        // 判定替身：卡住 300 ms（模拟一个「卡住的 Redis」在 event loop 上会造成的后果）。
        BlockingRateLimiter limiter = new BlockingRateLimiter(300L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions").build());
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        AtomicLong probeLatencyMillis = new AtomicLong(-1L);
        AtomicBoolean probeFired = new AtomicBoolean(false);
        CountDownLatch probeDone = new CountDownLatch(1);
        // 「判定已经进入、正在阻塞」时，从扮演 event loop 的那个线程上投一个探针：
        // 投递本身是即时的（schedule 不阻塞），因此探针的落地延迟就是「这个 loop 被占住了多久」。
        limiter.onEnter = () -> {
            long submittedAt = System.nanoTime();
            eventLoop.schedule(() -> {
                probeLatencyMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submittedAt));
                probeFired.set(true);
                probeDone.countDown();
            });
        };

        Mono<Void> filtered = new RateLimitFilter(limiter, true, new SimpleMeterRegistry())
                .filter(exchange, chain(passed));
        // 订阅发生在 test-event-loop 上（模拟「本次请求由 event loop 线程驱动」）。
        // **不能**在测试线程上再 block 一次：那会在测试线程上重新订阅一遍（subscribeOn 的
        // 第一次订阅才决定执行线程），于是这次用例就不再是「event loop 订阅的」了。
        eventLoop.schedule(() -> filtered.subscribe());
        // 等判定落地（判定线程写下的信号）之后，再给「写响应头 / 调用下游链」一点时间。
        assertThat(limiter.awaitDecision(Duration.ofSeconds(10))).as("判定必须真的跑完").isTrue();
        await(() -> probeFired.get() && passed.get() != null, "放行结局必须落地");

        assertThat(limiter.decisionThreadName)
                .as("阻塞的判定绝不能跑在订阅它的那个线程（event loop）上")
                .isNotEqualTo("test-event-loop")
                .contains("boundedElastic");
        assertThat(probeFired).as("探针必须真的投出去过").isTrue();
        assertThat(probeDone.await(5, TimeUnit.SECONDS)).as("探针必须落地，不能被判定卡住").isTrue();
        assertThat(probeLatencyMillis.get())
                .as("判定（约 300 ms）阻塞期间，event loop 线程必须仍然可用")
                .isLessThan(150L);
        assertThat(passed.get()).as("放行判定仍须把请求交给下游链").isNotNull();
    }

    /**
     * 同一件事的另一半：拒绝路径同样不占 event loop，且响应形状不变。
     * （判定替身仍然阻塞 50 ms；时序判据由上面那条负责。）
     */
    @Test
    void aRejectionIsAlsoDecidedOffTheSubscribingThread() {
        BlockingRateLimiter limiter = new BlockingRateLimiter(50L);
        limiter.deny = true;
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions").build());
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        Mono<Void> filtered = new RateLimitFilter(limiter, true, new SimpleMeterRegistry())
                .filter(exchange, chain(passed));
        // 与上面同理：只在 event loop 上订阅，测试线程只等结果。
        eventLoop.schedule(() -> filtered.subscribe());
        await(() -> exchange.getResponse().getStatusCode() != null, "拒绝结局必须落地");

        assertThat(limiter.awaitDecision(Duration.ofSeconds(10))).isTrue();
        assertThat(limiter.decisionThreadName).contains("boundedElastic");
        assertThat(passed.get()).as("拒绝不得进入下游链").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** 轮询一个由被观察线程写入的条件；超时就由随后的断言去报「没落地」。 */
    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static WebFilterChain chain(AtomicReference<ServerWebExchange> passed) {
        return exchange -> {
            passed.set(exchange);
            return Mono.empty();
        };
    }

    /**
     * 一个**真的会阻塞**的 {@link RateLimiter} 替身：记录判定发生在哪个线程与第几次调用，
     * 并可选地在进入时回调（用来投递 event loop 探针）。父类构造器收到的三个 null 永远不会被触碰。
     */
    private static final class BlockingRateLimiter extends RateLimiter {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch decided = new CountDownLatch(1);
        volatile String decisionThreadName;
        volatile Runnable onEnter = () -> { };
        volatile boolean deny;

        private final long blockMillis;

        BlockingRateLimiter(long blockMillis) {
            super(null, null, null);
            this.blockMillis = blockMillis;
        }

        boolean awaitDecision(Duration timeout) {
            try {
                return decided.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public RateLimitDecision acquire(long tenantId, Long apiKeyId, String keyHash) {
            calls.incrementAndGet();
            decisionThreadName = Thread.currentThread().getName();
            onEnter.run();
            try {
                Thread.sleep(blockMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            decided.countDown();
            return deny
                    ? RateLimitDecision.denied(250L, 10, 20, RateLimitDecision.Source.LOCAL)
                    : RateLimitDecision.allowed(9, 10, 20, RateLimitDecision.Source.REDIS);
        }
    }
}
