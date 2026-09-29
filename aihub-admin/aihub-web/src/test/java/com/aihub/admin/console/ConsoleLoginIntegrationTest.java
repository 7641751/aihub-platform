package com.aihub.admin.console;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.entity.SysUserEntity;
import com.aihub.dao.mapper.SysUserMapper;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleToken;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录与 {@code /api/**} 鉴权的**线上契约**：真容器（MySQL）+ 真 bcrypt + 真 HTTP + 真令牌。
 *
 * <p><b>密钥从 {@code aihub.console.secret} 注入</b>（{@code @TestPropertySource}）：登录要能签发，
 * 就必须有一把合规（≥32 字符）的密钥，而 {@code application.yml} 的默认值是空（D16 刻意没有默认值）。
 * 这是**合成**密钥，只活在这个测试上下文里。
 *
 * <p><b>夹具的纪律</b>：{@code sys_user} 行由测试自己用 {@link SysUserMapper} 插入（口令是
 * 测试里现算的 bcrypt 哈希，明文是**合成**的，绝不是任何真实凭证）；{@link AbstractIntegrationTest}
 * 是 JVM 级共享的 Testcontainers 单例且**没有全局清理**，因此每条夹具都用唯一用户名（{@code console-it-}
 * 前缀 + 随机后缀），并在前后各删一次该前缀的行 —— 既不依赖"表是干净的"，也不留垃圾给别的用例。
 *
 * <p><b>为什么"口令错"与"用户名不存在"要逐字比较响应体</b>：两条路径的**状态码**一样并不能阻止枚举用户
 * ——真正的判据是信封逐字节相同（这正是本类断言 equalTo 而不是断言两个 code 的原因）；
 * 时序那一半由 {@code ConsoleAuthFilterTest#aMissingUserStillPaysTheBcryptCost} 在单元层钉住。
 *
 * <p><b>为什么本上下文要关掉 MQ 监听器</b>（{@code spring.rabbitmq.listener.simple.auto-startup=false}）：
 * Testcontainers 的 broker 是**整个 JVM 共享**的，队列也是共享的。本类因为要注入合成密钥而必然是一个
 * **独立**的 Spring 上下文（{@code @TestPropertySource} 会改变上下文缓存的键，这是不可避免的：密钥必须有值），
 * 于是一旦它排在 {@code MeteringConsumerIntegrationTest}（包名 {@code metering} 排在 {@code console} 之后）
 * 之前，broker 上就同时挂了**两个**消费者。那个既有用例的绿色建立在一条明确写下的假设上
 * ——"队列是单消费者 FIFO：栅栏行出现时，排在它前面的重复消息必然已经整条处理完"（见该用例的注释），
 * 而两个消费者会让这条推理失效：栅栏可能被另一个消费者先处理，断言读 appender 时重复消息的 INFO 还没打出来。
 *
 * <p>实测（原始日志见报告 §7）：{@code -Dtest=ConsoleLoginIntegrationTest,MeteringConsumerIntegrationTest}
 * **3/3 红**（`theSameEventTwiceInsertsExactlyOneRow`，`Expecting actual not to be empty`）；
 * 把本类排除后整个 admin 套件 **113/113 绿**；而在**既有**类上做同样的竞争
 * （`InternalAuthFilterContextPathTest,MeteringConsumerIntegrationTest`）**4/4 绿** ——
 * 因为 surefire 的 filesystem 顺序把它排在 metering **之后**，竞争根本没发生。
 * 所以本类关掉自己的监听器：本用例判的是 HTTP 鉴权，与 MQ 消费无关，不该在共享 broker 上多挂一个消费者。
 * <b>残余（登记给后续任务）</b>：这条 fragility 仍然存在 —— Task 7–16 只要再在 {@code metering} 之前
 * 新增一个带独立上下文的集成测试类，同一个用例会以同样的方式变红；真正的修法是让那个用例不再依赖
 * "单消费者 FIFO"（例如改成轮询 DB/等待 INFO 出现），但那个文件不在本任务的 11 个文件范围内。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=" + ConsoleLoginIntegrationTest.SECRET,
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class ConsoleLoginIntegrationTest extends AbstractIntegrationTest {

    /**
     * 合成密钥（≥32 字符）：只为让登录路径有一把合规密钥。
     *
     * <p>刻意**不是** {@code private}：{@code @TestPropertySource} 上的常量表达式引用本类的私有常量时
     * javac 会报"private 访问控制"（注解值不在类的成员访问上下文里求值），实测如此。
     */
    static final String SECRET = "console-it-secret-0123456789abcdefghijklmn";

    private static final String USERNAME_PREFIX = "console-it-";
    private static final String PASSWORD = "synthetic-console-password";
    private static final String WRONG_PASSWORD = "synthetic-console-password-wrong";

    private static final String ADMIN = ConsoleClaims.ROLE_ADMIN;
    private static final String VIEWER = ConsoleClaims.ROLE_VIEWER;
    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";

    private static final long TENANT_ID = 1L;

    /** {@code aihub.console.token-ttl} 的默认值（D3）：2 小时。 */
    private static final long DEFAULT_TTL_SECONDS = 7_200L;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String PING_PATH = "/api/ping";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SysUserMapper sysUserMapper;

    @BeforeEach
    @AfterEach
    void removeFixtureUsers() {
        sysUserMapper.delete(new LambdaQueryWrapper<SysUserEntity>()
                .likeRight(SysUserEntity::getUsername, USERNAME_PREFIX));
    }

    @Test
    void loginReturnsATokenAndTheFilterAcceptsIt() throws Exception {
        SysUserEntity admin = insertUser(unique("admin"), PASSWORD, ADMIN, ACTIVE);

        ResponseEntity<String> login = login(admin.getUsername(), PASSWORD);
        assertThat(login.getStatusCode()).as("登录必须成功（响应体=%s）", login.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = MAPPER.readTree(login.getBody()).path("data");
        String token = data.path("token").asText();
        assertThat(token).as("data.token 必须存在（响应体=%s）", login.getBody()).isNotBlank();
        assertThat(data.path("role").asText()).isEqualTo(ADMIN);

        // 用**测试自己**的密钥字节解一遍：签发与校验必须用同一份 UTF-8 密钥（brief 的"必须与签发端一致"）。
        ConsoleClaims claims = ConsoleToken.verify(SECRET.getBytes(StandardCharsets.UTF_8), token);
        assertThat(claims.userId()).isEqualTo(admin.getId());
        assertThat(claims.tenantId()).isEqualTo(TENANT_ID);
        assertThat(claims.role()).isEqualTo(ADMIN);
        assertThat(claims.expiresAtEpochSecond() - claims.issuedAtEpochSecond())
                .as("D3：默认 token-ttl=2h，且 exp − iat 必须由签发端自己封顶")
                .isEqualTo(DEFAULT_TTL_SECONDS);

        ResponseEntity<String> ping = ping(token);
        assertThat(ping.getStatusCode()).as("带令牌的 GET /api/ping 必须 200（响应体=%s）", ping.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode envelope = MAPPER.readTree(ping.getBody());
        assertThat(envelope.path("code").asText()).isEqualTo("OK");
        assertThat(envelope.path("message").asText()).isEqualTo("success");
        JsonNode pingData = envelope.path("data");
        assertThat(pingData.path("userId").asLong()).isEqualTo(admin.getId());
        assertThat(pingData.path("tenantId").asLong()).isEqualTo(TENANT_ID);
        assertThat(pingData.path("role").asText()).isEqualTo(ADMIN);
    }

    @Test
    void aWrongPasswordIs401AndDoesNotRevealWhetherTheUserExists() throws Exception {
        SysUserEntity existing = insertUser(unique("exists"), PASSWORD, ADMIN, ACTIVE);

        ResponseEntity<String> wrongPassword = login(existing.getUsername(), WRONG_PASSWORD);
        ResponseEntity<String> missingUser = login(unique("missing"), WRONG_PASSWORD);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missingUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(MAPPER.readTree(missingUser.getBody()).path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(missingUser.getBody())
                .as("口令错 与 用户名不存在 必须是**逐字相同**的信封，否则这是一个用户枚举接口")
                .isEqualTo(wrongPassword.getBody());
    }

    @Test
    void viewerRoleMayReadButNotWrite() throws Exception {
        SysUserEntity viewer = insertUser(unique("viewer"), PASSWORD, VIEWER, ACTIVE);
        ResponseEntity<String> login = login(viewer.getUsername(), PASSWORD);
        assertThat(login.getStatusCode()).as("VIEWER 也应当能登录（响应体=%s）", login.getBody())
                .isEqualTo(HttpStatus.OK);
        String token = tokenOf(login);

        ResponseEntity<String> read = ping(token);
        assertThat(read.getStatusCode()).as("VIEWER 可以读（响应体=%s）", read.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(MAPPER.readTree(read.getBody()).path("data").path("role").asText()).isEqualTo(VIEWER);

        ResponseEntity<String> write = restTemplate.exchange(PING_PATH, HttpMethod.POST,
                new HttpEntity<>("{}", bearerHeaders(token)), String.class);
        assertThat(write.getStatusCode())
                .as("VIEWER 的写操作必须被过滤器拦成 403 admin 信封，而不是落到 MVC 的 405（响应体=%s）",
                        write.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(MAPPER.readTree(write.getBody()).path("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void internalAndHealthEndpointsAreNotAffected() {
        ResponseEntity<String> health = restTemplate.getForEntity("/healthz", String.class);
        assertThat(health.getStatusCode().is2xxSuccessful())
                .as("/healthz 无令牌也必须 200（响应体=%s）", health.getBody()).isTrue();
        assertThat(health.getBody()).contains("\"status\":\"UP\"");

        ResponseEntity<String> unsignedInternal = restTemplate.exchange("/internal/api-keys/resolve", HttpMethod.POST,
                new HttpEntity<>("{\"keyHash\":\"deadbeef\"}", jsonHeaders()), String.class);
        assertThat(unsignedInternal.getStatusCode())
                .as("/internal/** 仍然只由既有的 InternalAuthFilter 决定（响应体=%s）", unsignedInternal.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unsignedInternal.getBody())
                .as("必须是 InternalAuthFilter 自己写的信封：控制台过滤器不许经手 /internal/**")
                .contains("invalid internal signature");
    }

    @Test
    void anInactiveUserCannotLogIn() throws Exception {
        SysUserEntity inactive = insertUser(unique("inactive"), PASSWORD, ADMIN, INACTIVE);

        ResponseEntity<String> login = login(inactive.getUsername(), PASSWORD);

        assertThat(login.getStatusCode()).as("停用账号必须 401（响应体=%s）", login.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(MAPPER.readTree(login.getBody()).path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    // --- 夹具与助手 ------------------------------------------------------

    private SysUserEntity insertUser(String username, String rawPassword, String role, String status) {
        SysUserEntity user = new SysUserEntity();
        user.setTenantId(TENANT_ID);
        user.setUsername(username);
        user.setPasswordHash(ENCODER.encode(rawPassword));
        user.setRole(role);
        user.setStatus(status);
        sysUserMapper.insert(user);
        assertThat(user.getId()).as("MyBatis-Plus 必须把自增主键回填进实体（否则断言 userId 就是假通过）")
                .isNotNull();
        return user;
    }

    private static String unique(String label) {
        return USERNAME_PREFIX + label + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<String> login(String username, String password) {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        return restTemplate.exchange(LOGIN_PATH, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    private ResponseEntity<String> ping(String token) {
        return restTemplate.exchange(PING_PATH, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
    }

    private static String tokenOf(ResponseEntity<String> loginResponse) throws Exception {
        String token = MAPPER.readTree(loginResponse.getBody()).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
