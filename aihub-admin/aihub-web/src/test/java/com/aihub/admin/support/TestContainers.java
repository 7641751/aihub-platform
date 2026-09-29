package com.aihub.admin.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试的容器与基础设施属性事实源（单例：整个 JVM 只启动一次，被所有测试类共享，
 * 由 Testcontainers 的 Ryuk 负责回收）。
 *
 * <p><b>为什么单独抽出来</b>：连接时区方言（{@code connectionTimeZone} / {@code serverTimezone}）
 * 是**显式的测试设计选择**，而同一个数据库需要被**三种方言**访问：
 * <ul>
 *   <li>{@link #utcFlavouredJdbcUrl()} —— 与生产 URL 同方言（{@code serverTimezone=UTC}），
 *       由 {@link AbstractIntegrationTest} 使用：套件的主体断言的是**生产方言**下的行为；</li>
 *   <li>{@link #nonUtcFlavouredJdbcUrl()} —— 连接时区**钉成一个固定的非 UTC 区**（{@code Asia/Shanghai}），
 *       由 {@code com.aihub.admin.time.TimeBasisIsConnectionFlavourIndependentTest} 使用：
 *       这是能**在任何 JVM 时区下**判别「代码依赖不依赖连接时区」的方言
 *       （固定区不随跑测试的机器变化）；</li>
 *   <li>{@link #localFlavouredJdbcUrl()} —— **不带**任何连接时区参数，驱动因此按 **JVM 默认时区**
 *       解释 {@code datetime}（LOCAL 方言）。这就是 2026-09-29 独立评审之前**测试套件实际在跑**的方言，
 *       它没有被任何地方写下来过；现在只被方言用例用来**记录**这个历史事实
 *       （LOCAL 的行为随 JVM 默认时区变化，所以它不能当判别器）。</li>
 * </ul>
 *
 * <p>取舍与守卫写在 {@code docs/CONVENTIONS.md} §8「测试纪律」；不要在没有改那一段的情况下
 * 改动这里的方言定义 —— 在评审之前测试 URL 的方言是**意外**形成的，谁都没有看出来，
 * 于是「RED 证据」看起来像一个生产缺陷。
 */
public final class TestContainers {

    public static final int REDIS_PORT = 6379;

    public static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("aihub")
                    .withUsername("aihub")
                    .withPassword("aihub");

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    public static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    static {
        startContainer(MYSQL, "mysql:8.4（MySQL）");
        startContainer(REDIS, "redis:7-alpine（Redis）");
        startContainer(RABBITMQ, "rabbitmq:3.13-management-alpine（RabbitMQ）");
    }

    private TestContainers() {
    }

    /**
     * 容器在静态初始化块里单例启动（所有测试类共享）。逐个包装启动失败，
     * 让原本不透明的 {@code ExceptionInInitializerError} / {@code NoClassDefFoundError}
     * 直接指出是哪个容器起不来、原因是什么。
     */
    private static void startContainer(GenericContainer<?> container, String description) {
        try {
            container.start();
        }
        catch (RuntimeException | Error ex) {
            throw new IllegalStateException(
                    "Testcontainers 无法启动 " + description + " 容器："
                            + ex.getClass().getName() + ": " + ex.getMessage()
                            + "（集成测试需要可用的 Docker，请确认 Docker 正在运行且 Testcontainers 能连上它）",
                    ex);
        }
    }

    /**
     * 生产方言：连接时区**显式钉死为 UTC**，与
     * {@code aihub-admin/aihub-web/src/main/resources/application.yml:8} 和
     * {@code docker-compose.yml:65} 的发布 URL 逐字同类。
     * 这个钉法是**故意的**：驱动只有在这个参数存在时才不把 {@code datetime} 的时区交给 JVM。
     */
    public static String utcFlavouredJdbcUrl() {
        return withConnectionTimezoneParameter("serverTimezone=UTC");
    }

    /**
     * 判别用方言：连接时区**钉成一个固定的非 UTC 区**（`Asia/Shanghai`）。
     *
     * <p>为什么不用裸 URL（LOCAL）：LOCAL 的行为取决于**跑测试的 JVM 默认时区** ——
     * 在一台 UTC 的机器/CI 镜像上，LOCAL 与 UTC 的行为**完全一致**，判别力归零
     * （这正是独立评审 I-1(b) 指出的盲区：常见的 UTC CI 上这条路无人保护）。
     * 把连接时区钉成固定区之后，判别在任何 JVM 时区下都成立。
     * 会话时区不受影响（没有开 {@code forceConnectionTimeZoneToSession}），
     * 因此 {@code DEFAULT CURRENT_TIMESTAMP(3)} 写的仍是数据库会话时区的墙上时间。
     */
    public static String nonUtcFlavouredJdbcUrl() {
        return withConnectionTimezoneParameter("connectionTimeZone=Asia/Shanghai");
    }

    /**
     * **历史方言**：不带 {@code serverTimezone} / {@code connectionTimeZone}，
     * 驱动把 {@code datetime} 与 {@code Timestamp}/{@code Instant} 之间的换算交给 **JVM 默认时区**（LOCAL）。
     *
     * <p>这就是 2026-09-29 独立评审之前**测试套件实际在跑的方言**（Testcontainers 返回裸 URL），
     * 它与生产（`serverTimezone=UTC`）**相反**而没有任何地方写下来。现在它只被方言用例用来
     * **记录**这个历史事实：它的行为随 JVM 默认时区变化，因此不适合当判别器。
     */
    public static String localFlavouredJdbcUrl() {
        return MYSQL.getJdbcUrl();
    }

    private static String withConnectionTimezoneParameter(String parameter) {
        String url = MYSQL.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + parameter;
    }

    /**
     * 两种方言都要注册的基础设施属性（Redis / RabbitMQ / 内部密钥）。
     * {@code spring.datasource.url} **不在**这里 —— 它正是两个 flavour 的分叉点。
     */
    public static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        registry.add("aihub.internal.secret", () -> "test-internal-secret-test-internal-secret");
    }
}
