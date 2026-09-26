package com.aihub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.embedded.netty.NettyWebServer;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AihubGatewayApplicationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Test
    void healthzReturnsUp() {
        webTestClient.get().uri("/healthz")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                // 同时钉住 /healthz 的路径映射（来自主配置的 management.endpoints.web.path-mapping.health）
                // 与测试用的 Redis / RabbitMQ 健康开关真的生效了：本机没有 Redis，
                // 若该指示器还在，这里会是 DOWN 且响应体里出现 "redis"；RabbitMQ 即使本机恰好有一个
                // 在跑也必须不出现 —— 否则测试套件会静默依赖一个活 broker。
                .value(body -> assertThat(body)
                        .contains("\"status\":\"UP\"")
                        .doesNotContain("\"redis\"")
                        .doesNotContain("\"rabbit\""));
    }

    /**
     * 主配置（{@code src/main/resources/application.yml}）确实在测试里生效，而不是被测试副本取代。
     * <p>这里断言的都是**只写在主配置里**、且不含占位符（不受环境变量影响）的键；测试资源目录下只有
     * 一份 {@code application.properties}，且只含 {@code management.health.redis.enabled}。
     * 只要主配置被整体复制成测试资源、或者主配置里这些键被删改而测试副本没跟着改，本测试就会红。
     */
    @Test
    void mainApplicationYamlIsMergedIntoTheTestConfiguration() {
        // 主配置独有：api-key 缓存的 TTL。测试副本从来没有（也不该有）这两个键。
        assertThat(environment.getProperty("aihub.auth.local-cache-ttl")).isEqualTo("30s");
        assertThat(environment.getProperty("aihub.auth.key-cache-ttl")).isEqualTo("5m");
        // /healthz 能在测试里工作，靠的就是主配置的这条路径映射。
        assertThat(environment.getProperty("management.endpoints.web.path-mapping.health")).isEqualTo("healthz");
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("always");
        // 同时确认测试自己的那一行也合并在内（两份配置真的叠加，而不是二选一）。
        assertThat(environment.getProperty("management.health.redis.enabled")).isEqualTo("false");
        // 限流的开关同样只写在测试资源里（M3 Task 9）：绝大多数网关测试只关心转发与鉴权，
        // 不该为每个请求先撞一次「Redis 指向不存在端口」的超时。端到端验证限流的
        // RateLimitWiringTest 用 @DynamicPropertySource 显式打开它，因此这条为 false 是前提而不是泄漏。
        assertThat(environment.getProperty("aihub.ratelimit.enabled")).isEqualTo("false");
        // 主配置里的默认值仍然是「开」，且键名一致 —— 否则上面那条 false 可能只是「两边都没有这个键」。
        assertThat(environment.getProperty("aihub.ratelimit.max-local-buckets")).isEqualTo("100000");
        // rabbit 健康指示器的开关同样只写在主配置里，且是「网关测试不需要活 broker」的前提：
        // 没有它，healthzReturnsUp 会静默依赖一个正在跑的 RabbitMQ（本机恰好有一个时全绿，
        // CI / 别的机器上随机红）。
        assertThat(environment.getProperty("management.health.rabbit.enabled")).isEqualTo("false");
    }

    /**
     * <b>生产默认值必须真的是「开」</b>：`aihub.ratelimit.enabled` 是 M3 头条特性（限流）的总开关，
     * 而它在**测试里**被 {@code src/test/resources/application.properties} 钉成 {@code false}
     * （理由见上面那条断言）。于是「生产默认是什么」在整套测试里**没有任何断言** ——
     * 主配置里把键名写错（例如 {@code aihub.ratelimit.enable}）或者把默认值写成 {@code flase}，
     * 生产环境的限流会被静默关掉，而全量测试照样全绿。本用例就是补上这一条。
     *
     * <p>为什么不能直接断言 {@code environment.getProperty(...)}：那是**合并后**的结果，
     * 测试副本的 {@code false} 优先级更高，永远看不到主配置那一行（这正是缺口存在的原因）。
     * 所以这里读的是**出厂** {@code src/main/resources/application.yml} 本身
     * （沿用 {@code UsageCaptureTest} 读同一个文件的口径），并且断言的是那一行的**原文**：
     * 它必须是 {@code ${AIHUB_RATELIMIT_ENABLED:true}} —— 环境变量的**名字**与**默认值**两者缺一不可。
     *
     * <p>为什么刻意连占位符原文一起钉：这条键在 {@code RateLimitFilter} 里读作
     * {@code @Value("${aihub.ratelimit.enabled:true}")}，它是唯一读它的地方（不是
     * {@code @ConfigurationProperties}），因此「默认值」与「覆盖用的环境变量名」都只写在
     * application.yml 这一行上。只断言「不是 false」会漏掉环境变量名写错这类回归 ——
     * 那正是本用例要防的「一个 typo 就静默关掉限流」。
     */
    @Test
    void theProductionDefaultOfTheRateLimitSwitchIsOn() {
        Object shipped = shippedRateLimitEnabledValueFromApplicationYml();

        assertThat(shipped)
                .as("主配置必须显式写出 aihub.ratelimit.enabled —— 它是限流唯一的总开关，"
                        + "且是环境变量 AIHUB_RATELIMIT_ENABLED 的唯一读取点")
                .isNotNull();
        // 断言这一行的**原文**：默认值与环境变量名两者缺一不可。只断言「不是 false」会漏掉
        // 环境变量名写错这类回归（那等于这个开关再也覆盖不了）。
        assertThat(String.valueOf(shipped))
                .as("生产默认必须解析为 true，且覆盖用的环境变量名必须是 AIHUB_RATELIMIT_ENABLED；"
                        + "否则一个 typo 就能在全绿测试下静默关掉限流")
                .isEqualTo("${AIHUB_RATELIMIT_ENABLED:true}");
    }

    /** 直接读**出厂** {@code aihub-gateway/src/main/resources/application.yml} 里那一行（原文，未插值）。 */
    private static Object shippedRateLimitEnabledValueFromApplicationYml() {
        try {
            var shipped = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            return shipped.get(0).getProperty("aihub.ratelimit.enabled");
        } catch (IOException e) {
            throw new IllegalStateException("读不到出厂 application.yml", e);
        }
    }

    /**
     * 资源层面的证据：classpath 上解析到的 {@code application.yml} 不能来自测试资源目录。
     * 同名测试资源会排在主资源前面（test-classes 先于 classes），一旦有人再放一份进去，
     * 它就会整体取代主配置 —— 这正是被修掉的那个陷阱。
     */
    @Test
    void noTestResourceShadowsTheMainApplicationYaml() {
        URL yaml = getClass().getClassLoader().getResource("application.yml");

        assertThat(yaml).as("classpath 上必须有主配置 application.yml").isNotNull();
        assertThat(yaml.toString())
                .as("测试 classpath 上不允许再出现同名的 application.yml（它会整体取代主配置）；"
                        + "本地若只是 target/test-classes 里的旧残留，先跑 mvn clean")
                .doesNotContain("test-classes");
    }

    /**
     * <b>测试环境必须跑在生产用的那个 Web 服务器上</b>（Netty），而不是「classpath 上第一个成立的候选」。
     *
     * <p>Boot 3.5.16 的 {@code ReactiveWebServerFactoryAutoConfiguration} 按
     * Tomcat → Jetty → Undertow → Netty 的顺序 {@code @Import} 四个候选，每个都是
     * {@code @ConditionalOnMissingBean}：只要 classpath 上先有一个成立的，Netty 就被跳过。
     * M3 引入 WireMock（{@code wiremock-jetty12}）后 Jetty 的 servlet 与 server 类进了测试 classpath，
     * 于是整个网关测试套件静默改跑「Servlet 模式下的 Jetty」——
     * 在 Servlet 的写回路径上，响应正常结束时的 async 完成回调会取消写-flush 处理器，
     * 让中继把**每一次成功响应**都误记成客户端断连（{@code CANCELLED/client_disconnected}），
     * 既有用例实测红 5 条。
     *
     * <p>修复是显式的：测试 classpath 上用 {@link com.aihub.gateway.testsupport.NettyWebServerTestAutoConfiguration}
     * 注册一个 {@code NettyReactiveWebServerFactory}。这条断言把「修复还在」变成可执行的 ——
     * 谁删掉那个自动配置（或再引入一个自带 servlet 容器的测试依赖），这里立刻红，
     * 而不是等到某次「计量状态莫名变成 CANCELLED」的排查。
     */
    @Test
    void theGatewayTestContextRunsOnNettyNotOnAServletContainer() {
        assertThat(context.getBean(ReactiveWebServerFactory.class))
                .as("Boot 选中的反应式服务器工厂必须是 Netty（生产用的那个）；"
                        + "Jetty/Tomcat 会把响应写回路径换成 servlet 的，计量状态会因此失真")
                .isInstanceOf(NettyReactiveWebServerFactory.class);
        assertThat(((WebServerApplicationContext) context).getWebServer())
                .as("真正跑起来的服务器也必须是 Netty")
                .isInstanceOf(NettyWebServer.class);
    }
}
