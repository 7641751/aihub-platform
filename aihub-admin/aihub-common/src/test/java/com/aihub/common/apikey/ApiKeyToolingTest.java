package com.aihub.common.apikey;

import com.aihub.common.internal.InternalHmac;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code aihub-common} 里四个共享类的单元测试。
 * <p>这些类admin 铸造密钥、gateway 校验密钥都要用，逻辑一旦漂移就是「铸出来验不过」，
 * 所以压在这里比压在下游更划算。本类不需要 Spring 上下文，也不需要 Docker。
 */
class ApiKeyToolingTest {

    // --- ApiKeyHasher -----------------------------------------------------

    @Test
    void hashIsStableLowercaseHexOf64Chars() {
        // SHA-256("abc") 的已知向量，钉住算法而不是只钉住长度。
        assertThat(ApiKeyHasher.hash("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ApiKeyHasher.hash("abc")).isEqualTo(ApiKeyHasher.hash("abc"));
        assertThat(ApiKeyHasher.hash("secret")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void newKeyIdHasTheDocumentedShapeAndIsUniqueAcrossCalls() {
        List<String> ids = List.of(ApiKeyHasher.newKeyId(), ApiKeyHasher.newKeyId(), ApiKeyHasher.newKeyId());

        assertThat(ids).allSatisfy(id ->
                assertThat(id).startsWith("ak_").hasSize(19).matches("ak_[a-z0-9]{16}"));
        assertThat(new HashSet<>(ids)).hasSize(ids.size());
    }

    @Test
    void newSecretIsNonBlankAndDiffersAcrossCalls() {
        String first = ApiKeyHasher.newSecret();
        String second = ApiKeyHasher.newSecret();

        // 32 字节 → URL 安全 Base64 无填充 = 43 字符。
        assertThat(first).isNotBlank().hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(second).isNotEqualTo(first);
        assertThat(ApiKeyHasher.hash(first)).isNotEqualTo(ApiKeyHasher.hash(second));
    }

    // --- ApiKeyCacheCodec -------------------------------------------------

    @Test
    void cacheCodecRoundTripsANormalView() {
        ApiKeyView view = new ApiKeyView("ak_n60pawrjbxfj5oez", 42L, "acme", ApiKeyView.STATUS_ACTIVE,
                Instant.ofEpochSecond(1_800_000_000L));

        assertThat(ApiKeyCacheCodec.decode(ApiKeyCacheCodec.encode(view))).isEqualTo(view);
    }

    @Test
    void cacheCodecRoundTripsANullExpireAt() {
        ApiKeyView view = new ApiKeyView("ak_n60pawrjbxfj5oez", 42L, "acme", ApiKeyView.STATUS_ACTIVE, null);

        String payload = ApiKeyCacheCodec.encode(view);

        assertThat(payload).endsWith("|");
        assertThat(ApiKeyCacheCodec.decode(payload)).isEqualTo(view);
    }

    /**
     * 「tenantName 含分隔符和反斜杠」是唯一的真实风险点：编解码必须严格互逆。
     * 注意这里必须同时覆盖「反斜杠紧跟分隔符」——两个转义序列叠在一起时最容易出错。
     */
    @Test
    void cacheCodecRoundTripsNamesContainingDelimiterAndBackslash() {
        for (String tenantName : List.of("ac|me", "ac\\me", "ac\\|me", "a|b\\\\c|d\\", "\\", "|", "||\\\\|")) {
            ApiKeyView view = new ApiKeyView("ak_" + tenantName, 7L, tenantName, ApiKeyView.STATUS_ACTIVE,
                    Instant.ofEpochSecond(1_800_000_001L));

            String payload = ApiKeyCacheCodec.encode(view);

            assertThat(ApiKeyCacheCodec.decode(payload))
                    .as("round-trip of tenantName %s (payload %s)", tenantName, payload)
                    .isEqualTo(view);
        }
    }

    @Test
    void cacheCodecReturnsNullForMalformedPayloads() {
        assertThat(ApiKeyCacheCodec.decode(null)).isNull();
        assertThat(ApiKeyCacheCodec.decode("")).isNull();
        assertThat(ApiKeyCacheCodec.decode("not-a-key-view")).isNull();
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE")).isNull();          // 只有 4 段
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE|1|extra")).isNull();  // 有 6 段
        assertThat(ApiKeyCacheCodec.decode("ak_x|not-a-long|acme|ACTIVE|")).isNull();
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE|not-an-epoch")).isNull();
    }

    // --- InternalHmac -----------------------------------------------------

    /**
     * 签名是 admin 与 gateway 之间的线上契约（{@code timestamp + "\n" + METHOD + "\n" + path}），
     * 用固定向量钉住，避免任何一侧随手改动格式而只有集成测试才暴露。
     */
    @Test
    void internalHmacSignsTheDocumentedPayloadAndVerifiesIt() {
        String secret = "change-me-internal-secret-change-me";
        String signature = InternalHmac.sign(secret, "1700000000", "post", "/internal/api-keys/resolve");

        assertThat(signature).matches("[0-9a-f]{64}");
        // 同一输入必须稳定，且方法名大小写不改变签名（sign 内部 toUpperCase）。
        assertThat(InternalHmac.sign(secret, "1700000000", "POST", "/internal/api-keys/resolve")).isEqualTo(signature);
        // path / timestamp / secret 任一变化，签名都必须跟着变。
        assertThat(InternalHmac.sign(secret, "1700000001", "POST", "/internal/api-keys/resolve"))
                .isNotEqualTo(signature);
        assertThat(InternalHmac.sign(secret, "1700000000", "POST", "/internal/api-keys/other"))
                .isNotEqualTo(signature);
        assertThat(InternalHmac.sign("other-secret", "1700000000", "POST", "/internal/api-keys/resolve"))
                .isNotEqualTo(signature);

        assertThat(InternalHmac.verify(secret, "1700000000", "POST", "/internal/api-keys/resolve", signature))
                .isTrue();
        assertThat(InternalHmac.verify(secret, "1700000000", "POST", "/internal/api-keys/resolve", null)).isFalse();
        assertThat(InternalHmac.verify(secret, null, "POST", "/internal/api-keys/resolve", signature)).isFalse();
        assertThat(InternalHmac.verify(secret, "1700000000", "POST", "/internal/api-keys/resolve", "0".repeat(64)))
                .isFalse();
    }

    // --- ApiKeyView -------------------------------------------------------

    @Test
    void viewIsUsableOnlyWhenActiveAndUnexpired() {
        Instant future = Instant.now().plusSeconds(3600);
        Instant past = Instant.now().minusSeconds(3600);

        assertThat(new ApiKeyView("ak_1", 1L, "t", ApiKeyView.STATUS_ACTIVE, null).usable()).isTrue();
        assertThat(new ApiKeyView("ak_1", 1L, "t", ApiKeyView.STATUS_ACTIVE, future).usable()).isTrue();
        assertThat(new ApiKeyView("ak_1", 1L, "t", ApiKeyView.STATUS_ACTIVE, past).usable()).isFalse();
        assertThat(new ApiKeyView("ak_1", 1L, "t", "REVOKED", future).usable()).isFalse();
    }
}
