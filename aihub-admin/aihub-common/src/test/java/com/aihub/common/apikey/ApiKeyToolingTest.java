package com.aihub.common.apikey;

import com.aihub.common.internal.InternalHmac;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * 缓存前缀是 admin 与 gateway 之间的另一条线上契约：两侧都按
     * {@code CACHE_KEY_PREFIX + key_hash} 读写同一批 entry。写错了不会报错，只会永久缓存未命中
     * （每次都回源 MySQL），因此这里把字面量钉死 —— 改前缀必须同时是一次有意识的契约变更。
     */
    @Test
    void cacheKeyPrefixIsThePinnedCrossServiceContract() {
        assertThat(ApiKeyCacheCodec.CACHE_KEY_PREFIX).isEqualTo("aihub:apikey:");
    }

    @Test
    void cacheCodecRoundTripsANormalView() {
        ApiKeyView view = new ApiKeyView("ak_n60pawrjbxfj5oez", 42L, "acme", ApiKeyView.STATUS_ACTIVE,
                Instant.ofEpochSecond(1_800_000_000L), 42L);

        assertThat(ApiKeyCacheCodec.decode(ApiKeyCacheCodec.encode(view))).isEqualTo(view);
    }

    @Test
    void cacheCodecRoundTripsANullExpireAt() {
        ApiKeyView view = new ApiKeyView("ak_n60pawrjbxfj5oez", 42L, "acme", ApiKeyView.STATUS_ACTIVE, null, 42L);

        String payload = ApiKeyCacheCodec.encode(view);

        // 空的 expireAt 段后面紧跟数值主键段，因此固定向量是 `...|ACTIVE||42` 而不是以 `|` 结尾。
        assertThat(payload).isEqualTo("ak_n60pawrjbxfj5oez|42|acme|ACTIVE||42");
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
                    Instant.ofEpochSecond(1_800_000_001L), 42L);

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
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE")).isNull();               // 只有 4 段
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE|1|42|extra")).isNull();    // 有 7 段
        assertThat(ApiKeyCacheCodec.decode("ak_x|not-a-long|acme|ACTIVE||")).isNull();
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE|not-an-epoch|")).isNull();
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE||not-a-long")).isNull();
    }

    /** 第 6 段是数值主键：非空时必须严格往返，且载荷的字面量形态被钉死。 */
    @Test
    void cacheCodecRoundTripsTheNumericApiKeyId() {
        ApiKeyView view = new ApiKeyView("ak_abc", 7L, "demo", ApiKeyView.STATUS_ACTIVE, null, 42L);

        assertThat(ApiKeyCacheCodec.encode(view)).isEqualTo("ak_abc|7|demo|ACTIVE||42");
        assertThat(ApiKeyCacheCodec.decode(ApiKeyCacheCodec.encode(view))).isEqualTo(view);
    }

    /**
     * 旧载荷（5 段）必须被判为畸形 → 缓存未命中 → 回源 admin → 重写，而不是解出一个
     * {@code apiKeyId = null} 的「半成品」—— 后者会让限流静默丢掉 key 级策略（决策 7 修订的那一维）。
     */
    @Test
    void legacyFiveFieldPayloadsAreRejectedSoTheCacheConverges() {
        assertThat(ApiKeyCacheCodec.decode("ak_abc|7|demo|ACTIVE|1800000000")).isNull();
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

    /**
     * 签名不能依赖默认 locale：tr_TR 下 {@code "options".toUpperCase()} 是 {@code "OPTİONS"}
     * （带点的大写 I），两端默认 locale 不同就会算出不同签名 —— 一个现有测试都抓不到的不定时 401。
     * 先用 {@link Locale#ROOT} 算出基准，再切到 tr_TR 复算。
     */
    @Test
    void internalHmacIsIndependentOfTheDefaultLocale() {
        String secret = "change-me-internal-secret-change-me";
        String path = "/internal/api-keys/resolve";
        Locale original = Locale.getDefault();
        String expected;
        try {
            Locale.setDefault(Locale.ROOT);
            expected = InternalHmac.sign(secret, "1700000000", "options", path);
        } finally {
            Locale.setDefault(original);
        }

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(InternalHmac.sign(secret, "1700000000", "options", path))
                    .as("tr_TR 下的签名必须与 ROOT 下的一致")
                    .isEqualTo(expected);
            assertThat(InternalHmac.sign(secret, "1700000000", "OPTIONS", path)).isEqualTo(expected);
            assertThat(InternalHmac.verify(secret, "1700000000", "options", path, expected)).isTrue();
        } finally {
            Locale.setDefault(original);
        }
    }

    /**
     * 密钥没配好时必须 fail-closed：{@code verify} 返回 false（未来过滤器里是 401），
     * 而不是让 {@code sign} 的 {@link IllegalStateException} 冒出去变成 500。
     * {@code sign} 自身的「编程错误就抛」保持不变。
     */
    @Test
    void internalHmacVerifyFailsClosedWhenTheSecretIsMissing() {
        String path = "/internal/api-keys/resolve";
        String signature = InternalHmac.sign("change-me-internal-secret-change-me", "1700000000", "POST", path);

        assertThat(InternalHmac.verify(null, "1700000000", "POST", path, signature)).isFalse();
        assertThat(InternalHmac.verify("", "1700000000", "POST", path, signature)).isFalse();
        assertThat(InternalHmac.verify("   ", "1700000000", "POST", path, signature)).isFalse();
        // 密钥缺失时也不该因为 timestamp/signature 都合法就放行。
        assertThat(InternalHmac.verify(null, "1700000000", "POST", path, "0".repeat(64))).isFalse();

        assertThatThrownBy(() -> InternalHmac.sign(null, "1700000000", "POST", path))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InternalHmac.sign("", "1700000000", "POST", path))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- ApiKeyView -------------------------------------------------------

    @Test
    void viewIsUsableOnlyWhenActiveAndUnexpired() {
        Instant future = Instant.now().plusSeconds(3600);
        Instant past = Instant.now().minusSeconds(3600);

        assertThat(new ApiKeyView("ak_1", 1L, "t", ApiKeyView.STATUS_ACTIVE, null, null).usable()).isTrue();
        assertThat(new ApiKeyView("ak_1", 1L, "t", ApiKeyView.STATUS_ACTIVE, future, null).usable()).isTrue();
        assertThat(new ApiKeyView("ak_1", 1L, "t", ApiKeyView.STATUS_ACTIVE, past, null).usable()).isFalse();
        assertThat(new ApiKeyView("ak_1", 1L, "t", "REVOKED", future, null).usable()).isFalse();
    }

    /**
     * {@code usable()} 的判据只有两维：{@code status} 必须是 {@code ACTIVE}，且 {@code expireAt}
     * 为 {@code null}（永不过期）或尚未过期。上面那条用例覆盖了四象限；这里补的是**「不可用」不需要
     * 一个专门的哨兵实例**：任何非 {@code ACTIVE} 或已过期的视图都会让 {@code usable()} 为 false，
     * 调用方判一次即可。
     *
     * <p><b>（Task 9）</b>此前这里钉的是共享类型上的 {@code ApiKeyView.UNUSABLE} 哨兵；它在 D4 之后
     * 已无生产调用方（gateway 侧改用 {@code AdminResolution.unavailable()}），因此被删除
     * （附录 A8 要求的结论）。这条用例保留了那个哨兵**唯一**还成立的语义 —— 「不可用」是
     * {@code usable()} 的返回值，不是一个需要共享的常量。
     */
    @Test
    void unusableIsExpressedByUsableNotByASharedSentinel() {
        assertThat(new ApiKeyView("ak_1", 1L, "t", "MISSING", null, null).usable()).isFalse();
        assertThat(new ApiKeyView("ak_1", 1L, "t", "DISABLED", null, null).usable()).isFalse();
        assertThat(new ApiKeyView("ak_1", 1L, "t", ApiKeyView.STATUS_ACTIVE, Instant.now().minusSeconds(1), null)
                .usable()).isFalse();
    }
}
