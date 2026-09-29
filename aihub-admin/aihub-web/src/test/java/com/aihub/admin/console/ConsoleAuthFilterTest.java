package com.aihub.admin.console;

import com.aihub.admin.web.console.ConsoleAuthController;
import com.aihub.admin.web.console.ConsoleAuthFilter;
import com.aihub.admin.web.error.GlobalExceptionHandler;
import com.aihub.dao.entity.SysUserEntity;
import com.aihub.dao.mapper.SysUserMapper;
import com.aihub.service.console.ConsoleAuthService;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleToken;
import com.aihub.service.console.ConsoleTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code ConsoleAuthFilter} + 登录链路的**纯单元**用例：MockMvc standalone（真过滤器 + 真控制器 +
 * 真 {@link GlobalExceptionHandler}），没有 Spring 上下文、没有 Docker、没有 MySQL ——
 * 这里判定的三件事（密钥是否可用、令牌是否可解、角色是否被允许）全部是过滤器自己的决定，
 * 任何容器都只会让"某条用例为什么红"更难看清。
 *
 * <p><b>为什么 D16 与角色判定必须在本类里有独立用例</b>（brief 的验收判据要求"出错时能指着一条红的用例"）：
 * <ul>
 *   <li>把 {@link ConsoleAuthFilter} 的"密钥不可用 ⇒ 401"改成"密钥不可用 ⇒ 放行"之后，
 *       {@link #aBlankConsoleSecretFailsClosedWithConfigurationError()} 与
 *       {@link #aShortConsoleSecretAlsoFailsClosedWithConfigurationError()} 会红；</li>
 *   <li>把两级角色判定删掉（只验签名）之后，{@link #viewerWritesAreForbiddenWithTheAdminEnvelope()}
 *       与 {@link #anUnknownRoleFailsClosedForReadsAndWrites()} 会红 —— 注意判据是
 *       **403 而不是落进 MVC 的 405**：{@code POST /api/ping} 没有映射，若过滤器只验签名，
 *       这个请求会变成 405 INVALID_PARAM，"403 + FORBIDDEN"因此是有判别力的断言。</li>
 * </ul>
 *
 * <p><b>口令编码器是 mock</b>：bcrypt 的**正确性**由 spring-security-crypto 负责（D2），本类只判定
 * "调用它的时机与参数"——包括「用户名不存在时也必须对一个**合法**的常量假哈希跑一次 matches」。
 * 那条用例断言的是<b>机制</b>（bcrypt 真的被调用了、且假哈希能过 bcrypt 的格式校验）而不是墙上时钟差：
 * 时间断言在 CI 上必然偶发，而"少跑一次 bcrypt"正是被评审点名的那个漏洞的成因。
 */
class ConsoleAuthFilterTest {

    /** ≥32 字符：D16 的下限是"不含 32 以下"，因此本类的正常路径都用一把合规密钥。 */
    private static final String SECRET = "console-unit-test-secret-0123456789abcdef";
    private static final String OTHER_SECRET = "console-unit-test-secret-ffffffffffffffffff";

    /** 恰好 32 字符：下限是**包含**的，32 必须能用、31 必须不能。 */
    private static final String MIN_LENGTH_SECRET = "0123456789abcdef0123456789abcdef";

    private static final String SHORT_SECRET = "short";

    private static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String PING_PATH = "/api/ping";
    private static final String HEALTH_PATH = "/healthz";
    private static final String INTERNAL_PROBE_PATH = "/internal/api-keys/resolve";

    private static final String EXISTING_USERNAME = "console-unit-existing";
    private static final String MISSING_USERNAME = "console-unit-nobody";
    private static final String PASSWORD = "synthetic-console-password";
    private static final long TENANT_ID = 7L;

    /**
     * 固定的「远在未来」时间戳（UTC 2096-10-02），与 {@code ConsoleTokenTest} 同一纪律：
     * 用 {@code Instant.now()} 造夹具会把用例变成对时钟的断言（Task 5 踩过：brief 原稿的
     * 1_700_000_000 是 2023 年，恒过期）。
     */
    private static final long ACTIVE_IAT = 4_000_000_000L;
    private static final long ACTIVE_EXP = 4_000_007_200L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SysUserMapper sysUserMapper = mock(SysUserMapper.class);
    private final BCryptPasswordEncoder passwordEncoder = mock(BCryptPasswordEncoder.class);

    private ConsoleTokenService tokenService;
    private MockMvc mvc;

    // --- 用例 -----------------------------------------------------------

    @Test
    void aBlankConsoleSecretFailsClosedWithConfigurationError() throws Exception {
        buildConsole("");

        MvcResult login = login(EXISTING_USERNAME, PASSWORD);
        assertThat(login.getResponse().getStatus())
                .as("D16：密钥为空是**平台配置故障**，不是凭证故障 —— 登录必须回 500，"
                        + "401 会让运维去翻口令（响应体=%s）", body(login))
                .isEqualTo(500);
        assertThat(code(login)).isEqualTo("CONFIGURATION_ERROR");
        assertAdminEnvelope(login);
        verify(sysUserMapper, times(0)).selectOne(any());

        MvcResult guarded = mvc.perform(get(PING_PATH)).andReturn();
        assertThat(guarded.getResponse().getStatus())
                .as("门必须是关着的：密钥不可用时 /api/** 无令牌一律 401，绝不放行（响应体=%s）", body(guarded))
                .isEqualTo(401);
        assertThat(code(guarded)).isEqualTo("UNAUTHORIZED");
        assertThat(body(guarded))
                .as("这一条 401 必须来自 **D16 的配置分支**（消息点名 AIHUB_CONSOLE_SECRET），"
                        + "而不是「缺令牌」那条分支、更不是放行到 MVC 之后由控制器兜底："
                        + "把配置分支删掉/改成放行，两者的可观测差异就在这里（响应体=%s）", body(guarded))
                .contains("AIHUB_CONSOLE_SECRET");
    }

    @Test
    void aShortConsoleSecretAlsoFailsClosedWithConfigurationError() throws Exception {
        buildConsole(SHORT_SECRET);

        MvcResult login = login(EXISTING_USERNAME, PASSWORD);
        assertThat(login.getResponse().getStatus())
                .as("短密钥（%d 字符 < 32）与空密钥同罪：可离线爆破的 HMAC 密钥等于没有鉴权（响应体=%s）",
                        SHORT_SECRET.length(), body(login))
                .isEqualTo(500);
        assertThat(code(login)).isEqualTo("CONFIGURATION_ERROR");

        MvcResult guarded = mvc.perform(get(PING_PATH)).andReturn();
        assertThat(guarded.getResponse().getStatus()).as("响应体=%s", body(guarded)).isEqualTo(401);
        assertThat(code(guarded)).isEqualTo("UNAUTHORIZED");

        // 判别力所在：**用短密钥签一张合法令牌**。短密钥是可离线爆破的，"放行"意味着攻击者
        // 猜出密钥后直接拿到读写权限；而 401（而不是 200）正是"短密钥 = 门关着"的判据。
        String signedWithShortSecret = ConsoleToken.issue(secretBytes(SHORT_SECRET),
                claims(ConsoleClaims.ROLE_ADMIN, ACTIVE_IAT, ACTIVE_EXP));
        MvcResult shortSecretToken = bearer(signedWithShortSecret);
        assertThat(shortSecretToken.getResponse().getStatus())
                .as("用短密钥签出的令牌必须**认不出来**（401）；若是 200，说明过滤器在拿一把可爆破的密钥验签"
                        + "（响应体=%s）", body(shortSecretToken))
                .isEqualTo(401);
        assertThat(code(shortSecretToken)).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void aSecretOfExactlyThirtyTwoCharactersIsAccepted() throws Exception {
        buildConsole(MIN_LENGTH_SECRET);
        when(sysUserMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.matches(any(), anyString())).thenReturn(false);

        MvcResult result = login(MISSING_USERNAME, PASSWORD);

        assertThat(result.getResponse().getStatus())
                .as("32 字符是**含**在下限内的：门槛只对更短的值生效（响应体=%s）", body(result))
                .isEqualTo(401);
        assertThat(code(result)).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void aRequestWithoutATokenGetsTheAdminEnvelope() throws Exception {
        buildConsole(SECRET);

        MvcResult result = mvc.perform(get(PING_PATH)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertAdminEnvelope(result);
        assertThat(code(result)).isEqualTo("UNAUTHORIZED");
        assertThat(result.getResponse().getContentType())
                .as("必须是 JSON（否则前端 fetch 拿到的是一坨 HTML）")
                .contains(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void anInvalidOrAnExpiredTokenIsUnauthorized() throws Exception {
        buildConsole(SECRET);
        String valid = ConsoleToken.issue(secretBytes(SECRET), claims(ConsoleClaims.ROLE_ADMIN, ACTIVE_IAT, ACTIVE_EXP));
        String[] parts = valid.split("\\.");

        MvcResult tampered = bearer(parts[0] + "." + parts[1] + ".AAAA");
        assertThat(tampered.getResponse().getStatus()).as("响应体=%s", body(tampered)).isEqualTo(401);
        assertThat(code(tampered)).isEqualTo("UNAUTHORIZED");

        // 过期令牌用**过去的**固定时间戳签出来（正确签名 + 已过期），不依赖当前时钟做边界判断。
        String expired = ConsoleToken.issue(secretBytes(SECRET),
                claims(ConsoleClaims.ROLE_ADMIN, 1_600_000_000L, 1_600_000_001L));
        MvcResult expiredResult = bearer(expired);
        assertThat(expiredResult.getResponse().getStatus()).as("响应体=%s", body(expiredResult)).isEqualTo(401);
        assertThat(code(expiredResult)).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void aTokenSignedWithAnotherSecretIsUnauthorized() throws Exception {
        buildConsole(SECRET);
        String foreign = ConsoleToken.issue(secretBytes(OTHER_SECRET),
                claims(ConsoleClaims.ROLE_ADMIN, ACTIVE_IAT, ACTIVE_EXP));

        MvcResult result = bearer(foreign);

        assertThat(result.getResponse().getStatus())
                .as("换密钥签的令牌必须 401（本用例里配置的密钥是合规的，401 只能来自签名比较）", body(result))
                .isEqualTo(401);
        assertThat(code(result)).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void viewerWritesAreForbiddenWithTheAdminEnvelope() throws Exception {
        buildConsole(SECRET);
        String viewer = ConsoleToken.issue(secretBytes(SECRET),
                claims(ConsoleClaims.ROLE_VIEWER, ACTIVE_IAT, ACTIVE_EXP));

        MvcResult read = bearer(viewer);
        assertThat(read.getResponse().getStatus()).as("VIEWER 可以读（响应体=%s）", body(read)).isEqualTo(200);
        JsonNode data = MAPPER.readTree(body(read)).path("data");
        assertThat(data.path("role").asText()).isEqualTo(ConsoleClaims.ROLE_VIEWER);
        assertThat(data.path("userId").asLong()).isEqualTo(42L);
        assertThat(data.path("tenantId").asLong()).isEqualTo(TENANT_ID);

        MvcResult write = mvc.perform(post(PING_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer)).andReturn();
        assertThat(write.getResponse().getStatus())
                .as("VIEWER 的写操作必须被**过滤器**拦成 403；POST /api/ping 没有映射，"
                        + "若判据是 405 就说明过滤器根本没在判角色（响应体=%s）", body(write))
                .isEqualTo(403);
        assertThat(code(write)).isEqualTo("FORBIDDEN");
        assertAdminEnvelope(write);
    }

    @Test
    void anAdminWriteIsPassedThroughToTheMvcLayer() throws Exception {
        buildConsole(SECRET);
        String admin = ConsoleToken.issue(secretBytes(SECRET),
                claims(ConsoleClaims.ROLE_ADMIN, ACTIVE_IAT, ACTIVE_EXP));

        MvcResult write = mvc.perform(post(PING_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andReturn();

        assertThat(write.getResponse().getStatus())
                .as("ADMIN 的写操作必须被放行：/api/ping 只有 GET 映射，因此 MVC 回 405 —— "
                        + "它证明的是「被拦下来的是角色，不是方法」（响应体=%s）", body(write))
                .isEqualTo(405);
        assertThat(code(write)).isEqualTo("INVALID_PARAM");
    }

    @Test
    void anUnknownRoleFailsClosedForReadsAndWrites() throws Exception {
        buildConsole(SECRET);
        String stranger = ConsoleToken.issue(secretBytes(SECRET), claims("AUDITOR", ACTIVE_IAT, ACTIVE_EXP));

        MvcResult read = bearer(stranger);
        assertThat(read.getResponse().getStatus())
                .as("D10 只认 ADMIN/VIEWER：未知角色连**读**都必须拒（fail-closed 不能只对着写方法）（响应体=%s）",
                        body(read))
                .isEqualTo(403);
        assertThat(code(read)).isEqualTo("FORBIDDEN");

        MvcResult write = mvc.perform(post(PING_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger)).andReturn();
        assertThat(write.getResponse().getStatus()).as("响应体=%s", body(write)).isEqualTo(403);
        assertThat(code(write)).isEqualTo("FORBIDDEN");
    }

    @Test
    void pathsOutsideTheApiPrefixAreNotGuardedEvenWhenTheGateIsClosed() throws Exception {
        buildConsole("");

        MvcResult health = mvc.perform(get(HEALTH_PATH)).andReturn();
        assertThat(health.getResponse().getStatus())
                .as("/healthz 不经本过滤器（密钥为空时也必须一样）（响应体=%s）", body(health))
                .isEqualTo(200);

        MvcResult internal = mvc.perform(post(INTERNAL_PROBE_PATH)).andReturn();
        assertThat(internal.getResponse().getStatus())
                .as("/internal/** 仍由 InternalAuthFilter 决定，本过滤器一个字都不许插嘴（响应体=%s）", body(internal))
                .isEqualTo(200);
    }

    @Test
    void theLoginPathIsReachableWithoutAToken() throws Exception {
        buildConsole(SECRET);
        when(sysUserMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.matches(any(), anyString())).thenReturn(false);

        MvcResult result = login(MISSING_USERNAME, PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(result)).isEqualTo("UNAUTHORIZED");
        // 请求必须**真的走到了服务层**：若过滤器把 /api/auth/login 也守起来，这两次交互根本不会发生。
        verify(sysUserMapper).selectOne(any());
        verify(passwordEncoder).matches(any(), anyString());
    }

    @Test
    void aMissingUserStillPaysTheBcryptCost() throws Exception {
        buildConsole(SECRET);
        SysUserEntity existing = activeAdmin();
        when(passwordEncoder.matches(any(), anyString())).thenReturn(false);

        when(sysUserMapper.selectOne(any())).thenReturn(existing);
        assertThat(login(EXISTING_USERNAME, PASSWORD).getResponse().getStatus()).isEqualTo(401);

        when(sysUserMapper.selectOne(any())).thenReturn(null);
        assertThat(login(MISSING_USERNAME, PASSWORD).getResponse().getStatus()).isEqualTo(401);

        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder, times(2)).matches(any(), storedHash.capture());
        String dummy = storedHash.getAllValues().get(1);

        assertThat(dummy)
                .as("用户名不存在时也必须对一个**常量假哈希**跑一次 bcrypt：否则两条路径差约 100ms，"
                        + "响应体一样也照样能枚举用户")
                .isNotEqualTo(existing.getPasswordHash())
                .startsWith("$2");
        assertThat(new BCryptPasswordEncoder().matches("aihub-unit-probe", dummy))
                .as("假哈希必须是**合法**的 bcrypt 串：格式不对时 BCrypt 抛 IAE（→500），"
                        + "而这条路径的契约是「与口令错同构的 401」")
                .isFalse();
    }

    /**
     * 防枚举的**线上形状**：口令错 与 用户名不存在 必须给出逐字节相同的响应
     * （状态码相同是不够的 —— 换一个 message、换一个 code、换一个字段顺序都是可枚举的信号）。
     *
     * <p><b>判别力</b>：把"用户不存在"那一支换成任何**不同的信封**（例如
     * {@code BizException(UNAUTHORIZED, "用户名不存在")}）之后，这条用例变红，而
     * {@link #aMissingUserStillPaysTheBcryptCost()} 仍然绿（bcrypt 照跑）—— 两条用例分别钉住
     * "信封相同"与"两条路径都付 bcrypt 代价"这两件**不同**的事，谁也不能替代谁。
     */
    @Test
    void aWrongPasswordAndAMissingUserAreByteIdenticalOnTheWire() throws Exception {
        buildConsole(SECRET);
        when(passwordEncoder.matches(any(), anyString())).thenReturn(false);

        when(sysUserMapper.selectOne(any())).thenReturn(activeAdmin());
        MvcResult wrongPassword = login(EXISTING_USERNAME, PASSWORD);

        when(sysUserMapper.selectOne(any())).thenReturn(null);
        MvcResult missingUser = login(MISSING_USERNAME, PASSWORD);

        assertThat(wrongPassword.getResponse().getStatus())
                .as("口令错：响应体=%s", body(wrongPassword))
                .isEqualTo(401);
        assertThat(missingUser.getResponse().getStatus())
                .as("用户不存在：响应体=%s", body(missingUser))
                .isEqualTo(wrongPassword.getResponse().getStatus());
        assertThat(missingUser.getResponse().getContentAsByteArray())
                .as("两条路径的响应体必须**逐字节**相同（字符串相等还不够：字符集、字段顺序、"
                        + "以及任何「顺手加一句提示」的改动都在这里现形）")
                .isEqualTo(wrongPassword.getResponse().getContentAsByteArray());
        assertThat(code(missingUser)).isEqualTo("UNAUTHORIZED");
        assertAdminEnvelope(missingUser);
    }

    @Test
    void anInactiveUserIsRejectedLikeAWrongPassword() throws Exception {
        buildConsole(SECRET);
        SysUserEntity inactive = activeAdmin();
        inactive.setStatus("INACTIVE");
        when(sysUserMapper.selectOne(any())).thenReturn(inactive);
        when(passwordEncoder.matches(any(), anyString())).thenReturn(true);

        MvcResult result = login(EXISTING_USERNAME, PASSWORD);

        assertThat(result.getResponse().getStatus())
                .as("口令对但账号停用 ⇒ 仍然是 401（响应体=%s）", body(result))
                .isEqualTo(401);
        assertThat(code(result)).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void theIssuedTokenIsCappedAtTwoHours() throws Exception {
        when(sysUserMapper.selectOne(any())).thenReturn(activeAdmin());
        when(passwordEncoder.matches(any(), anyString())).thenReturn(true);

        assertThat(issuedTtlSeconds(Duration.ofHours(10)))
                .as("D3：签发端必须自己把 exp − iat 封顶在 2 小时（verify 不做最大存活期校验）")
                .isEqualTo(7_200L);
        assertThat(issuedTtlSeconds(Duration.ofMinutes(30)))
                .as("比封顶更短的 TTL 必须原样保留（封顶不是「一律改成 2 小时」）")
                .isEqualTo(1_800L);
    }

    // --- 夹具与断言助手 --------------------------------------------------

    private void buildConsole(String secret) {
        buildConsole(secret, DEFAULT_TTL);
    }

    private void buildConsole(String secret, Duration tokenTtl) {
        tokenService = new ConsoleTokenService(secret, tokenTtl);
        ConsoleAuthService authService = new ConsoleAuthService(sysUserMapper, tokenService, passwordEncoder);
        mvc = MockMvcBuilders
                .standaloneSetup(new ConsoleAuthController(authService, tokenService),
                        new OutsideGuardedPathsProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ConsoleAuthFilter(secret, tokenTtl))
                .build();
    }

    private MvcResult login(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        return mvc.perform(post(LOGIN_PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult bearer(String token) throws Exception {
        return mvc.perform(get(PING_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private long issuedTtlSeconds(Duration configured) throws Exception {
        buildConsole(SECRET, configured);
        MvcResult result = login(EXISTING_USERNAME, PASSWORD);
        assertThat(result.getResponse().getStatus()).as("响应体=%s", body(result)).isEqualTo(200);
        String token = MAPPER.readTree(body(result)).path("data").path("token").asText();
        ConsoleClaims claims = ConsoleToken.verify(secretBytes(SECRET), token);
        assertThat(claims.expiresAtEpochSecond()).isGreaterThan(claims.issuedAtEpochSecond());
        return claims.expiresAtEpochSecond() - claims.issuedAtEpochSecond();
    }

    private static SysUserEntity activeAdmin() {
        SysUserEntity user = new SysUserEntity();
        user.setId(42L);
        user.setTenantId(TENANT_ID);
        user.setUsername(EXISTING_USERNAME);
        user.setPasswordHash("$2a$10$stored-hash-placeholder-（口令编码器是 mock，这里不需要真哈希）");
        user.setRole(ConsoleClaims.ROLE_ADMIN);
        user.setStatus("ACTIVE");
        return user;
    }

    private static ConsoleClaims claims(String role, long iat, long exp) {
        return new ConsoleClaims(42L, TENANT_ID, role, iat, exp);
    }

    private static byte[] secretBytes(String secret) {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String code(MvcResult result) throws Exception {
        return MAPPER.readTree(body(result)).path("code").asText();
    }

    /** 失败响应必须是 admin 信封：字段**恰好**是 code/message/data（顺序也钉住），data 是 JSON null。 */
    private static void assertAdminEnvelope(MvcResult result) throws Exception {
        JsonNode node = MAPPER.readTree(body(result));
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).as("admin 信封的字段与顺序固定为 code/message/data（响应体=%s）", body(result))
                .containsExactly("code", "message", "data");
        assertThat(node.path("data").isNull())
                .as("失败响应的 data 必须是 JSON null，不是缺字段：字段缺失钉不住这个形状（响应体=%s）", body(result))
                .isTrue();
    }

    /**
     * 只为了证明「本过滤器不守 /api/** 之外的路径」的探针：{@code /healthz} 与
     * {@code /internal/**} 在真应用里分别由 actuator 与 {@code InternalAuthFilter} 负责，
     * standalone 里没有它们，用一个映射到同一路径的探针代替（断言的是**过滤器放行**，不是那个端点的语义）。
     */
    @RestController
    static class OutsideGuardedPathsProbeController {

        @GetMapping("/healthz")
        String healthz() {
            return "{\"status\":\"UP\"}";
        }

        @PostMapping("/internal/api-keys/resolve")
        String resolve() {
            return "{\"code\":\"OK\",\"message\":\"success\",\"data\":null}";
        }
    }
}
