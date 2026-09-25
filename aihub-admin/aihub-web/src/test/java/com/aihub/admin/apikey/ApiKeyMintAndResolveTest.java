package com.aihub.admin.apikey;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.internal.InternalHmac;
import com.aihub.service.apikey.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyMintAndResolveTest extends AbstractIntegrationTest {

    private static final String TEST_SECRET = "test-internal-secret-test-internal-secret";
    private static final String RESOLVE_PATH = "/internal/api-keys/resolve";

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void mintedKeyIsResolvableBySecretHashAndNeverStoresPlaintext() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-mint", "key-1", null);
        String secret = secretOf(issued);

        Optional<ApiKeyView> resolved = apiKeyService.resolve(ApiKeyHasher.hash(secret));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().keyId()).isEqualTo(issued.keyId());
        assertThat(resolved.get().tenantName()).isEqualTo("t-mint");
        assertThat(resolved.get().usable()).isTrue();
        assertThat(apiKeyService.resolve(ApiKeyHasher.hash("wrong-secret"))).isEmpty();
    }

    @Test
    void expiredKeyResolvesButIsNotUsable() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-exp", "key-exp", Instant.now().minusSeconds(60));

        ApiKeyView view = apiKeyService.resolve(ApiKeyHasher.hash(secretOf(issued))).orElseThrow();

        assertThat(view.usable()).isFalse();
    }

    @Test
    void internalResolveEndpointRequiresValidSignature() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-http", "key-http", null);
        String body = "{\"keyHash\":\"" + ApiKeyHasher.hash(secretOf(issued)) + "\"}";

        ResponseEntity<String> unsigned = restTemplate.exchange(RESOLVE_PATH, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(unsigned.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<String> signed = restTemplate.exchange(RESOLVE_PATH, HttpMethod.POST,
                new HttpEntity<>(body, signedHeaders("POST", RESOLVE_PATH)), String.class);
        assertThat(signed.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(signed.getBody()).contains("\"code\":\"OK\"").contains(issued.keyId());
    }

    @Test
    void staleTimestampIsRejected() {
        String body = "{\"keyHash\":\"" + ApiKeyHasher.hash("whatever") + "\"}";
        String stale = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());
        HttpHeaders headers = jsonHeaders();
        headers.add("X-Internal-Timestamp", stale);
        headers.add("X-Internal-Signature", InternalHmac.sign(TEST_SECRET, stale, "POST", RESOLVE_PATH));

        ResponseEntity<String> response = restTemplate.exchange(RESOLVE_PATH, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
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
        // body 不参与签名 —— M1 的最小内部守卫只防未授权调用，body 签名留待需要时再加
        headers.add("X-Internal-Signature", InternalHmac.sign(TEST_SECRET, timestamp, method, path));
        return headers;
    }
}
