package com.aihub.admin.apikey;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.apikey.ApiKeyCacheCodec;
import com.aihub.common.apikey.ApiKeyHasher;
import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.internal.InternalHmac;
import com.aihub.service.apikey.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyMintAndResolveTest extends AbstractIntegrationTest {

    private static final String TEST_SECRET = "test-internal-secret-test-internal-secret";
    private static final String RESOLVE_PATH = "/internal/api-keys/resolve";

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void mintedKeyIsResolvableBySecretHashAndNeverStoresPlaintext() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-mint", "key-1", null);
        String secret = secretOf(issued);
        String keyHash = ApiKeyHasher.hash(secret);
        String cacheKey = ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash;

        // mint 顺带写了一份缓存；先删掉它，否则后面所有断言都由 Redis 兜住，
        // loadFromDb 永远不会执行 —— 那样「MySQL 才是真相源」和「缓存可降级」都没有被守住。
        redisTemplate.delete(cacheKey);
        assertThat(redisTemplate.opsForValue().get(cacheKey))
                .as("缓存必须已清空，否则本用例证明不了回源路径")
                .isNull();

        Optional<ApiKeyView> resolved = apiKeyService.resolve(keyHash);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().keyId()).isEqualTo(issued.keyId());
        assertThat(resolved.get().tenantName()).isEqualTo("t-mint");
        assertThat(resolved.get().usable()).isTrue();
        // 决策 14：数值主键必须真的来自 api_key.id。本用例前面已清掉缓存，因此这一行走的正是
        // loadFromDb 回源路径 —— 少了它，loadFromDb 少填一个参数（或填错列）不会有任何测试变红。
        assertThat(resolved.get().apiKeyId()).isEqualTo(dbIdOf(issued.keyId()));
        assertThat(apiKeyService.resolve(ApiKeyHasher.hash("wrong-secret"))).isEmpty();

        // 用例名里的安全不变量必须真的被验证：直接查库，只看落盘的 hash。
        assertThat(jdbcTemplate.queryForObject(
                "select key_hash from api_key where key_id = ?", String.class, issued.keyId()))
                .isEqualTo(keyHash);

        // 不写死列名清单：扫 api_key 的**全部**文本列，这样将来新加一列也不会悄悄存明文。
        List<String> textColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = database() and table_name = 'api_key' "
                        + "and data_type in ('char', 'varchar', 'text', 'tinytext', 'mediumtext', 'longtext') "
                        + "order by ordinal_position", String.class);
        assertThat(textColumns).contains("key_id", "key_hash", "name", "status");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select " + String.join(", ", textColumns) + " from api_key where key_id = ?", issued.keyId());
        assertThat(row)
                .as("api_key 里必须能查到刚铸造的这一行")
                .isNotEmpty();
        for (Map.Entry<String, Object> column : row.entrySet()) {
            assertThat(String.valueOf(column.getValue()))
                    .as("api_key.%s 不得包含明文 secret", column.getKey())
                    .doesNotContain(secret)
                    .doesNotContain(issued.token());
        }
    }

    @Test
    void expiredKeyResolvesButIsNotUsable() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-exp", "key-exp", Instant.now().minusSeconds(60));
        String keyHash = ApiKeyHasher.hash(secretOf(issued));
        String cacheKey = ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash;

        // 先证明 mint **真的**把载荷写进了 Redis。cache() 吞掉 RuntimeException（Redis 不可用时
        // 降级回 MySQL 是有意的），因此「写缓存静默失败」不会以异常形式露面 —— 它只会让本用例
        // 悄悄改走 loadFromDb，然后因为两条路径的值恰好相同而**依然全绿**。读出这条 entry
        // 才把「来自缓存」与「来自 DB」两条路径区分开。
        String cached = redisTemplate.opsForValue().get(cacheKey);
        assertThat(cached)
                .as("mint 必须真的写入 Redis，否则本用例证明不了 mint 写缓存这条路径")
                .isNotNull();

        ApiKeyView view = apiKeyService.resolve(keyHash).orElseThrow();

        assertThat(view.usable()).isFalse();
        // 决策 14 的**另一半**：本用例命中的是 mint 顺手写进 Redis 的那份载荷（不是回源），
        // 因此它钉的是 mint 路径 —— 只补 loadFromDb 会让「刚铸出来的 key」那一条路径静默丢了数值主键。
        assertThat(view.apiKeyId()).isEqualTo(dbIdOf(issued.keyId()));
        // 解析结果必须**就是**缓存里那份载荷：否则上面的断言可能只是又一次走 loadFromDb 得来的。
        assertThat(ApiKeyCacheCodec.decode(cached))
                .as("resolve 必须真的从 mint 写入的缓存载荷返回")
                .isEqualTo(view);
    }

    /**
     * 决策 14 的**不兼容变更之所以不需要清 Redis**，全部依据就是这条收敛行为：Redis 里已经躺着的旧
     * 5 段载荷必须被当成**缓存未命中**（而不是异常）→ 回源 MySQL → 就地重写成 6 段。
     *
     * <p>只在 codec 层面钉「返回 {@code null}」证明不了这一点：调用方一个 {@code requireNonNull}、
     * 一次把 null 当故障记 ERROR、或者直接把 decode 结果丢进断言，都会让「收敛」变成「故障」。
     * 所以这里从 {@code ApiKeyService.resolve} 这一层走一遍真实路径，并断言拿回来的是**真相源**的值
     * （旧载荷里的 {@code ak_legacy}/{@code tenantId=9} 不得泄漏出来），以及条目真的被重写了。
     */
    @Test
    void legacyFiveFieldCachePayloadIsAMissNotAnErrorAndIsRewrittenInPlace() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-legacy", "key-legacy", null);
        String keyHash = ApiKeyHasher.hash(secretOf(issued));
        String cacheKey = ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash;

        // 覆盖 mint 写的 6 段载荷，模拟「上一版 admin 留下的」旧格式 entry。
        redisTemplate.opsForValue().set(cacheKey, "ak_legacy|9|t-legacy|ACTIVE|1800000000");
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isEqualTo("ak_legacy|9|t-legacy|ACTIVE|1800000000");

        // 不抛异常，且回源拿到真相源的值 —— 旧段数载荷被当作未命中，而不是被解成半成品。
        ApiKeyView view = apiKeyService.resolve(keyHash).orElseThrow();
        assertThat(view.keyId()).as("必须回源到 MySQL，而不是采信旧载荷").isEqualTo(issued.keyId());
        assertThat(view.apiKeyId()).isEqualTo(dbIdOf(issued.keyId()));

        // 条目被就地重写成 6 段：下一个请求不再回源。
        assertThat(ApiKeyCacheCodec.decode(redisTemplate.opsForValue().get(cacheKey)))
                .as("旧 5 段载荷必须已被 6 段载荷覆盖")
                .isEqualTo(view);
    }

    /**
     * Finding I1：**非 NULL** 的 {@code expire_at} 走「回源 MySQL」这条路径必须被真正执行一次。
     *
     * <p>本类原有的两个用例都绕过了它：上面那条铸的是永不过期（{@code expireAt = null}），
     * {@code expiredKeyResolvesButIsNotUsable} 解析命中的是 {@code mint} 顺手写进 Redis 的那份载荷。
     * 于是 MyBatis 的 {@code Instant} ↔ MySQL {@code datetime(3)} 映射从没跑过，而
     * {@link ApiKeyView#usable()} 把 {@code expireAt == null} 当作**永久有效** —— 任何一次读回失败
     * （字段映射错、时区错、列名错、值被截断）都会把一年期的 key 静默降级成永不过期的 key，
     * 而这正好落在鉴权路径上。这里删掉缓存逼出 DB 路径，把这条静默降级钉死。
     */
    @Test
    void futureExpiryRoundTripsThroughTheDatabasePath() {
        Instant expireAt = Instant.now().plus(Duration.ofDays(365));
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-future", "key-future", expireAt);
        String keyHash = ApiKeyHasher.hash(secretOf(issued));

        // 必须先清掉 mint 写的缓存，否则断言还是由 Redis 载荷兜住，DB 映射依然没被执行。
        redisTemplate.delete(ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash);
        assertThat(redisTemplate.opsForValue().get(ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash))
                .as("缓存必须已清空，否则本用例证明不了 DB 回源路径")
                .isNull();

        ApiKeyView view = apiKeyService.resolve(keyHash).orElseThrow();

        // 先钉住「非 null」：null 的含义就是永不过期，正是要防的那种降级。
        assertThat(view.expireAt())
                .as("回源读回 null 等于把有期限的 key 变成永不过期的 key")
                .isNotNull();
        assertThat(Duration.between(expireAt, view.expireAt()).abs())
                .as("datetime(3) 只有毫秒精度，允许 1 秒的截断误差")
                .isLessThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(view.usable()).isTrue();
    }

    /** Finding I1 的另一半：过期时间在过去时，**DB 回源**同样必须判为不可用（不靠 Redis 里那份载荷）。 */
    @Test
    void pastExpiryResolvedThroughTheDatabasePathIsNotUsable() {
        ApiKeyService.IssuedKey issued = apiKeyService.mint("t-past-db", "key-past-db",
                Instant.now().minus(Duration.ofDays(1)));
        String keyHash = ApiKeyHasher.hash(secretOf(issued));

        redisTemplate.delete(ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash);

        ApiKeyView view = apiKeyService.resolve(keyHash).orElseThrow();

        assertThat(view.expireAt()).as("回源读回 null 会变成永不过期").isNotNull();
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

        // 用另一个密钥签名 → 必须 401。否则「只检查签名非空、不校验密钥」的实现也能全绿。
        ResponseEntity<String> wrongSecret = restTemplate.exchange(RESOLVE_PATH, HttpMethod.POST,
                new HttpEntity<>(body, signedHeaders("POST", RESOLVE_PATH, "wrong-internal-secret-wrong-internal-secret")),
                String.class);
        assertThat(wrongSecret.getStatusCode().value()).isEqualTo(401);

        // 同一密钥、签在另一个路径上 → 同样必须 401（钉住 path 参与签名）。
        ResponseEntity<String> wrongPath = restTemplate.exchange(RESOLVE_PATH, HttpMethod.POST,
                new HttpEntity<>(body, signedHeaders("POST", RESOLVE_PATH + "-elsewhere")), String.class);
        assertThat(wrongPath.getStatusCode().value()).isEqualTo(401);
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

    /** {@code api_key.id}：决策 14 的数值主键真相源（网关限流与计量都要用它）。 */
    private Long dbIdOf(String keyId) {
        return jdbcTemplate.queryForObject("select id from api_key where key_id = ?", Long.class, keyId);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders signedHeaders(String method, String path) {
        return signedHeaders(method, path, TEST_SECRET);
    }

    private HttpHeaders signedHeaders(String method, String path, String secret) {
        HttpHeaders headers = jsonHeaders();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        headers.add("X-Internal-Timestamp", timestamp);
        // body 不参与签名 —— M1 的最小内部守卫只防未授权调用，body 签名留待需要时再加
        headers.add("X-Internal-Signature", InternalHmac.sign(secret, timestamp, method, path));
        return headers;
    }
}
