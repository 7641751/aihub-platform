package com.aihub.gateway.admin;

import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.internal.InternalHmac;
import com.aihub.gateway.testsupport.FakeAdminServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 {@link AdminClient.Http} 的执行覆盖：签名内容、请求路径、Content-Type、body、以及各种
 * 失败响应的降级。这是 {@code ApiKeyAuthFilterTest} 里 {@code @Primary} 假 bean 永远覆盖不到的一半 ——
 * 「签名的是应用内相对路径而不是带前缀的 URL」这类错误此前不会有任何测试变红。
 * <p>假 admin 用 JDK {@code com.sun.net.httpserver.HttpServer}，无 Docker、无 Redis。
 */
class AdminClientHttpTest {

    private static final String SECRET = "test-internal-secret";
    private static final String PATH = "/internal/api-keys/resolve";
    private static final String HASH = ApiKeyHasher.hash("some-secret");

    private static FakeAdminServer admin;

    @BeforeAll
    static void startAdmin() {
        admin = FakeAdminServer.start();
    }

    @AfterAll
    static void stopAdmin() {
        admin.stop();
    }

    @Test
    void postsTheSignedApplicationPathAndParsesTheView() {
        admin.enqueueJson(200, """
                {"code":"OK","message":"success","data":{"keyId":"ak_1","tenantId":7,\
                "tenantName":"t","status":"ACTIVE","expireAt":null}}""");

        Optional<ApiKeyView> view = client(SECRET).resolve(HASH).block();

        assertThat(view).isPresent();
        assertThat(view.orElseThrow().keyId()).isEqualTo("ak_1");
        assertThat(view.orElseThrow().tenantId()).isEqualTo(7L);

        FakeAdminServer.CapturedRequest request = admin.lastRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.rawPath()).isEqualTo(PATH);
        assertThat(request.headers().get("content-type")).startsWith("application/json");
        assertThat(request.body()).isEqualTo("{\"keyHash\":\"" + HASH + "\"}");
        // 真正的契约：admin 的 InternalAuthFilter 会用同一个 secret + 应用内路径复算签名。
        assertThat(InternalHmac.verify(SECRET, request.headers().get("x-internal-timestamp"),
                "POST", PATH, request.headers().get("x-internal-signature"))).isTrue();
    }

    @Test
    void notFoundDegradesToEmpty() {
        admin.enqueueJson(404, "{\"code\":\"NOT_FOUND\",\"message\":\"key not found\",\"data\":null}");

        assertThat(client(SECRET).resolve(HASH).block()).isEmpty();
    }

    @Test
    void serverErrorDegradesToEmpty() {
        admin.enqueueJson(500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\",\"data\":null}");

        assertThat(client(SECRET).resolve(HASH).block()).isEmpty();
    }

    @Test
    void garbageBodyDegradesToEmpty() {
        admin.enqueueJson(200, "not json at all");

        assertThat(client(SECRET).resolve(HASH).block()).isEmpty();
    }

    /** 2xx 但没有 {@code data}（例如 admin 的 200 空信封）同样视作「不存在」，不是 500。 */
    @Test
    void missingDataDegradesToEmpty() {
        admin.enqueueJson(200, "{\"code\":\"OK\",\"message\":\"success\",\"data\":null}");

        assertThat(client(SECRET).resolve(HASH).block()).isEmpty();
    }

    /**
     * 出厂默认 {@code aihub.internal.secret} 为空串：{@code InternalHmac.sign} 必抛。
     * 契约是「resolve 不抛异常」，所以这里必须拿到 {@code Optional.empty()}（调用方据此 401），
     * 而不是让 {@code IllegalStateException} 逃出去（那会变成 500）。
     * 换句话说：签名必须发生在 {@code Mono.defer} **内部**，{@code onErrorResume} 才看得见它。
     */
    @Test
    void blankInternalSecretFailsClosedInsteadOfThrowing() {
        assertThat(client("").resolve(HASH).block()).isEmpty();
    }

    private static AdminClient client(String secret) {
        return AdminClient.http(WebClient.builder().baseUrl(admin.baseUrl()).build(), secret);
    }
}
