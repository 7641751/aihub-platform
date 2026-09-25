package com.aihub.admin.web.internal;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.common.internal.InternalHmac;
import com.aihub.service.apikey.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 2 的回归测试：一旦配置了 {@code server.servlet.context-path}，
 * {@code InternalAuthFilter} 不得被静默跳过。
 *
 * <p>改动前的实现用原始 {@code request.getRequestURI()} 判断前缀，而它带 context path：
 * URI 是 {@code /admin/internal/api-keys/resolve}，前缀判断落空 → 过滤器不生效 →
 * 控制器照样映射 {@code /internal/api-keys/resolve} 并对外服务，等于没有鉴权。
 * 因此 {@link #unsignedRequestUnderAContextPathIsStillRejected} 在改动前会失败（未签名请求会直接打到控制器）。
 *
 * <p>本类刻意用**绝对 URL + 自己 new 出来的 {@link TestRestTemplate}**：容器自动注入的那个
 * 会按 {@code server.servlet.context-path} 自动补前缀，用它就分不清「context path 没生效」和
 * 「生效了但过滤器没跑」。配套的对照断言（不带 context path 的同一 URL 必须 404）保证本类
 * 在 context path 被删掉时也会失败，而不是变成一个永远为真的空测试。
 */
@TestPropertySource(properties = "server.servlet.context-path=/admin")
class InternalAuthFilterContextPathTest extends AbstractIntegrationTest {

    private static final String TEST_SECRET = "test-internal-secret-test-internal-secret";
    private static final String CONTEXT_PATH = "/admin";
    private static final String RESOLVE_PATH = "/internal/api-keys/resolve";

    /** 不带 root uri handler：请求 URL 原样发出，不会自动补 context path。 */
    private final TestRestTemplate client = new TestRestTemplate();

    @LocalServerPort
    private int port;

    @Autowired
    private ApiKeyService apiKeyService;

    @Test
    void unsignedRequestUnderAContextPathIsStillRejected() {
        String body = "{\"keyHash\":\"" + ApiKeyHasher.hash("any-secret") + "\"}";

        // 对照：context path 之外的同一路径根本不存在（Tomcat 直接 404）。
        // 它保证 context path 真的生效了，本用例不是「请求其实打到了根路径」的假阳性。
        ResponseEntity<String> outsideContextPath = client.exchange(url("", RESOLVE_PATH), HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(outsideContextPath.getStatusCode().value())
                .as("context path 之外的 %s 不应存在（响应体=%s）", RESOLVE_PATH, outsideContextPath.getBody())
                .isEqualTo(404);

        ResponseEntity<String> unsigned = client.exchange(url(CONTEXT_PATH, RESOLVE_PATH), HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(unsigned.getStatusCode().value())
                .as("context path 下未签名请求仍须被过滤器拦下（响应体=%s）", unsigned.getBody())
                .isEqualTo(401);
    }

    @Test
    void signedRequestUnderAContextPathIsAcceptedAndTheSignedPathIsContextRelative() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-ctx", "key-ctx", null);
        String body = "{\"keyHash\":\"" + ApiKeyHasher.hash(secretOf(issued)) + "\"}";

        ResponseEntity<String> signed = client.exchange(url(CONTEXT_PATH, RESOLVE_PATH), HttpMethod.POST,
                new HttpEntity<>(body, signedHeaders("POST", RESOLVE_PATH)), String.class);
        assertThat(signed.getStatusCode().is2xxSuccessful())
                .as("应用内路径签名后必须放行（响应体=%s）", signed.getBody())
                .isTrue();
        assertThat(signed.getBody()).contains("\"code\":\"OK\"").contains(issued.keyId());

        // 反之，若按「带 context path 的完整路径」签名，必须被拒 —— 契约是应用内路径，
        // 这样 admin 改 context path 时 gateway 无须跟着改签名。
        ResponseEntity<String> signedWithFullPath = client.exchange(url(CONTEXT_PATH, RESOLVE_PATH), HttpMethod.POST,
                new HttpEntity<>(body, signedHeaders("POST", CONTEXT_PATH + RESOLVE_PATH)), String.class);
        assertThat(signedWithFullPath.getStatusCode().value()).isEqualTo(401);
    }

    private String url(String contextPath, String path) {
        return "http://127.0.0.1:" + port + contextPath + path;
    }

    private String secretOf(ApiKeyService.IssuedKey issued) {
        return issued.token().substring(issued.token().indexOf('.') + 1);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders signedHeaders(String method, String path) {
        HttpHeaders headers = jsonHeaders();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        headers.add("X-Internal-Timestamp", timestamp);
        headers.add("X-Internal-Signature", InternalHmac.sign(TEST_SECRET, timestamp, method, path));
        return headers;
    }
}
