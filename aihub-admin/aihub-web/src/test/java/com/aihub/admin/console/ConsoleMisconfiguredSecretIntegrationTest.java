package com.aihub.admin.console;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D16 的**真上下文**用例：空密钥下登录必须回 500 + {@code CONFIGURATION_ERROR}，而 {@code /api/**} 的门
 * 必须关着（401），且不许把配置故障泄漏成别的形状。
 *
 * <p><b>为什么必须有这一类</b>：空/短密钥这条路径此前只在
 * {@code ConsoleAuthFilterTest} 的 {@code MockMvcBuilders.standaloneSetup(...)} 里被断言过 ——
 * 那是手工装配的 advice，而集成测试 {@code ConsoleLoginIntegrationTest} 用 {@code @TestPropertySource}
 * 强制注入了一把合规密钥，所以真 Spring 上下文里**从来没有**出现过空密钥。也就是说
 * 「{@code ErrorCode.CONFIGURATION_ERROR} → 500」这个具体映射虽然由
 * {@code GlobalExceptionHandler.handleBizException} 通用推导得出，却从未被端到端跑过。
 * 本类把它跑出来：真容器、真 HTTP、真 {@code @RestControllerAdvice}。
 *
 * <p><b>本上下文刻意不关 MQ 监听器</b>：它要的正是"与 metering 类形状相同的上下文"
 * （只有 {@code @TestPropertySource} 一个差异属性）。在 {@code MeteringConsumerIntegrationTest}
 * 去掉"单消费者 FIFO"假设之前，本类会让那条用例变红 —— 这正是先修 metering 的原因。
 */
@TestPropertySource(properties = {
        "aihub.console.secret="
})
class ConsoleMisconfiguredSecretIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String PING_PATH = "/api/ping";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aBlankSecretIsA500ConfigurationErrorOverRealHttp() throws Exception {
        ResponseEntity<String> login = restTemplate.exchange(LOGIN_PATH, HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"console-misconfig-nobody\",\"password\":\"whatever\"}",
                        jsonHeaders()),
                String.class);

        assertThat(login.getStatusCode())
                .as("D16：空密钥是**平台配置故障**（500），不是凭证故障（401）—— 401 会让运维去翻口令；"
                        + "这条断言的判别力就在于它经过真的 GlobalExceptionHandler（响应体=%s）", login.getBody())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        JsonNode envelope = MAPPER.readTree(login.getBody());
        assertThat(envelope.path("code").asText()).isEqualTo("CONFIGURATION_ERROR");

        List<String> fieldNames = new ArrayList<>();
        envelope.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames)
                .as("失败响应必须是 admin 信封且字段顺序固定（响应体=%s）", login.getBody())
                .containsExactly("code", "message", "data");
        assertThat(envelope.path("data").isNull())
                .as("失败响应的 data 必须是 JSON null（响应体=%s）", login.getBody())
                .isTrue();

        assertThat(envelope.path("message").asText())
                .as("文案必须点名环境变量，否则运维无法据此行动（响应体=%s）", login.getBody())
                .contains("AIHUB_CONSOLE_SECRET");
    }

    @Test
    void theGateStaysClosedAndDoesNotLeakTheConfigurationFaultAsA500() throws Exception {
        ResponseEntity<String> guarded = restTemplate.getForEntity(PING_PATH, String.class);

        assertThat(guarded.getStatusCode())
                .as("密钥不可用时 /api/** 无令牌一律 401（fail-closed），绝不放行、也绝不回 5xx "
                        + "（回 5xx 等于把「平台没配好」告诉每一个匿名请求者）（响应体=%s）", guarded.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode envelope = MAPPER.readTree(guarded.getBody());
        assertThat(envelope.path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(envelope.path("message").asText())
                .as("这条 401 必须来自**配置分支**（点名 AIHUB_CONSOLE_SECRET），"
                        + "而不是「缺令牌」那条分支（响应体=%s）", guarded.getBody())
                .contains("AIHUB_CONSOLE_SECRET");

        // 即使带着令牌也一样：门关着的时候没有任何令牌能被认出来（空密钥下的验签不构成"可用"）。
        ResponseEntity<String> withToken = restTemplate.exchange(PING_PATH, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders("whatever.token.value")), String.class);
        assertThat(withToken.getStatusCode())
                .as("空密钥下随便带什么令牌都必须 401（响应体=%s）", withToken.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
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
