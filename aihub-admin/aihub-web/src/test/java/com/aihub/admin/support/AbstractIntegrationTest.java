package com.aihub.admin.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 需要真实基础设施的集成测试的统一基类。
 * 容器使用单例模式：整个 JVM 只启动一次，被所有测试类共享，由 Testcontainers 的 Ryuk 负责回收。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final int REDIS_PORT = 6379;

    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("aihub")
                    .withUsername("aihub")
                    .withPassword("aihub");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    static {
        startContainer(MYSQL, "mysql:8.4（MySQL）");
        startContainer(REDIS, "redis:7-alpine（Redis）");
        startContainer(RABBITMQ, "rabbitmq:3.13-management-alpine（RabbitMQ）");
    }

    /**
     * 容器在静态初始化块里单例启动（三个测试类共享）。逐个包装启动失败，
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

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
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
