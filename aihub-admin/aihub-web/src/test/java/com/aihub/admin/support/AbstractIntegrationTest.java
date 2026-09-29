package com.aihub.admin.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * 需要真实基础设施的集成测试的统一基类。容器使用单例模式：整个 JVM 只启动一次，被所有测试类共享，
 * 由 Testcontainers 的 Ryuk 负责回收（容器事实源见 {@link TestContainers}）。
 *
 * <p><b>本基类把连接时区方言显式钉成生产方言：{@code serverTimezone=UTC}。</b>
 * 也就是 {@link TestContainers#utcFlavouredJdbcUrl()}，与
 * {@code aihub-admin/aihub-web/src/main/resources/application.yml:8} 和 {@code docker-compose.yml:65}
 * 的发布 URL 同类。这个选择是 2026-09-29 独立评审的产物：在那之前，测试 URL 是 Testcontainers 返回的
 * **裸 URL**（不带任何连接时区参数），驱动因此按 **JVM 默认时区**解释 {@code datetime}（LOCAL 方言）——
 * 与生产**恰好相反**，而且没有任何地方写下来，于是 "RED 证据" 看起来像一个生产缺陷，
 * 实际上在发布 URL 下旧代码与新代码逐位相等。
 *
 * <p>为什么主体套件选 UTC 而不是继续用 LOCAL：
 * <ol>
 *   <li><b>生产等同性</b>：发布的两个 URL 都带 {@code serverTimezone=UTC}；套件的大部分断言应当
 *       描述生产上的行为，而不是生产上不存在的方言。</li>
 *   <li><b>可复现性</b>：LOCAL 方言下同一份代码在本机（Asia/Shanghai）与一个 UTC 的 CI 镜像上
 *       会给出**不同**的数字 —— 套件本身不可复现，而 UTC 钉死之后与 JVM 默认时区无关。</li>
 * </ol>
 *
 * <p>「代码不依赖连接时区 / JVM 时区」这条性质因此需要**它自己的显式用例**，而不是靠基类的偶然方言：
 * <ul>
 *   <li>{@code com.aihub.admin.time.ConnectionTimeZoneFlavourTest} —— 断言本基类跑的**就是**这个方言
 *       （读回 {@code spring.datasource.url} + 驱动行为探针 + 数据库自己的 UTC 时钟），
 *       将来谁改了 URL，它会红而不是让套件静默换一个方言；</li>
 *   <li>{@code com.aihub.admin.time.TimeBasisIsConnectionFlavourIndependentTest} —— 故意把连接时区
 *       钉成一个**固定的非 UTC 区**（`connectionTimeZone=Asia/Shanghai`）起第二个上下文，
 *       跑同一条生产读/写路径，证明结果与连接时区无关。这条才是能判别修复本身的用例，
 *       而且因为它钉的是固定区、不是 LOCAL，判别在 UTC 的 CI 镜像上同样成立。</li>
 * </ul>
 *
 * <p>改这条 URL 之前先读 {@code docs/CONVENTIONS.md} §8「测试纪律」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final int REDIS_PORT = TestContainers.REDIS_PORT;

    protected static final MySQLContainer<?> MYSQL = TestContainers.MYSQL;

    protected static final GenericContainer<?> REDIS = TestContainers.REDIS;

    protected static final RabbitMQContainer RABBITMQ = TestContainers.RABBITMQ;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // 生产方言（serverTimezone=UTC）；非 UTC 方言的第二个上下文（connectionTimeZone=Asia/Shanghai）
        // 在 TimeBasisIsConnectionFlavourIndependentTest 里显式注册自己的 URL。
        registry.add("spring.datasource.url", TestContainers::utcFlavouredJdbcUrl);
        TestContainers.registerInfrastructure(registry);
    }
}
