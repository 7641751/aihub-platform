package com.aihub.admin.console;

import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleToken;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 控制台令牌（D3）的**纯单元**用例：没有 Spring 上下文、没有 Docker、没有 Redis ——
 * 令牌是 JDK 类型 + Jackson 的纯函数，任何容器都只会让这条契约更难判红。
 *
 * <p><b>用例覆盖</b>：往返（claims 逐字段不丢）、线格式（三段 + 固定 header 字节）、
 * 篡改（载荷与签名各一次）、已正确签名但载荷不合契约（缺字段 / 不是 JSON / 不是 JSON 对象）、
 * 过期、换密钥、{@code alg:none} 两条、分段数不对的四种输入，
 * 以及**密钥为 null/空 ⇒ {@link IllegalStateException}** 这条契约的另一半。
 *
 * <p><b>判别性（实测，不是推断）</b>：把 {@code verify} 改成「解析请求 header，{@code alg} 为
 * {@code none} 就跳过签名比较」之后，**只有**
 * {@link #aThreeSegmentTokenWithAnAlgNoneHeaderIsRejectedBySignatureComparisonNotByShape()}
 * 会变红（实测 **10/11**），其余十条**全部仍然绿** —— 包括 brief 自己那条 {@code alg:none}（因为空签名段
 * 被 {@code split} 吞掉末尾空串、只有两段，在形状检查处就拒了；**这条用例的拒绝理由来自分段数**，
 * 所以它既证明不了"实现不看请求 header"，也不能被用来证明"实现看了 header"）与
 * 线格式那条（header 字节是"写出来的"而不是"读进来的"）。所以「算法由服务端钉死」这条性质的**唯一**
 * 有效判据是那条补上的三段用例；它的 javadoc 里写明了它到底钉住哪一种错误实现。
 *
 * <p><b>为什么失败路径只断言异常类型</b>：契约是「令牌层失败抛 {@link IllegalArgumentException}，
 * 密钥为 null/空抛 {@link IllegalStateException}」，调用方（{@code ConsoleAuthFilter}）必须**分别**
 * 接这两种异常、而不是只 catch IAE。断言消息文本会把实现细节钉死成契约，而这条契约的全部价值在
 * **异常类型**上（受检异常逃出去就是 500，而契约要求 401；把平台配置故障并进凭证故障是 503-vs-401
 * 那个错误的翻版）。
 */
class ConsoleTokenTest {

    private static final byte[] SECRET = "m4-console-test-secret".getBytes(StandardCharsets.UTF_8);

    /**
     * "仍然有效"的令牌夹具（{@code iat}/{@code exp} 为 UTC epoch 秒）。
     *
     * <p><b>为什么不是 brief 里的 {@code 1_700_000_000}/{@code 1_700_007_200}</b>：那一对是
     * <b>2023-11-14 ~ 2023-11-15</b>，而本机时钟是 **2026-09-28** —— 校验方按
     * {@code Instant.now()} 判定，这对时间戳在任何"服务端按当前时间判过期"的实现下都**必然是过期令牌**，
     * 于是 `roundTripsClaims` 不可能绿。这里改用另一对**固定的、远在未来**的时间戳
     * {@code 4_000_000_000}/{@code 4_000_007_200}（UTC **2096-10-02 07:06:40 ~ 09:06:40**），
     * 同样是**固定**的（不用 {@code Instant.now()} 造时间，否则用例会引入时钟依赖、在边界秒上偶发变红），
     * 只是把"有效"这件事变成对实现真正成立的断言。
     * 失败路径（{@code anExpiredTokenIsRejected}）仍用 brief 原样的 {@code 1_600_000_000} 那一对 ——
     * 那一对**本来就该是过去的**。这是本任务偏离 brief 字面量的第一处（第二处是那条 `alg:none` 用例里多出的断言），两处都已登记在报告 §1。
     */
    private static final long ACTIVE_IAT = 4_000_000_000L;
    private static final long ACTIVE_EXP = 4_000_007_200L;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void roundTripsClaims() {
        ConsoleClaims claims = new ConsoleClaims(7L, 1L, ConsoleClaims.ROLE_ADMIN, ACTIVE_IAT, ACTIVE_EXP);
        assertThat(ConsoleToken.verify(SECRET, ConsoleToken.issue(SECRET, claims))).isEqualTo(claims);
    }

    @Test
    void theWireFormIsHeaderDotPayloadDotSignature() {
        String token = ConsoleToken.issue(SECRET, claims());
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8))
                .isEqualTo("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
    }

    @Test
    void aTamperedPayloadOrSignatureIsRejected() {
        String token = ConsoleToken.issue(SECRET, claims());
        String[] parts = token.split("\\.");
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET,
                parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("{\"sub\":\"999\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[2]));
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, parts[0] + "." + parts[1] + ".AAAA"));
    }

    /**
     * 载荷被**正确签名**但内容不合契约（claims 缺失）时同样必须是 {@link IllegalArgumentException}：
     * 这条与上面的「篡改」不是同一件事 —— 篡改在签名比较处就被拦下，而这里签名是**有效**的，
     * 异常只能来自 claims 解析。它守住的是「解析段的异常必须被转成 IAE」（Jackson 抛的是受检的
     * {@code JsonProcessingException}，漏包就会穿透成 500）。
     */
    @Test
    void aProperlySignedButIncompletePayloadIsRejectedAsIllegalArgument() {
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, signed("{\"sub\":\"7\"}")));
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, signed("not json at all")));
    }

    /**
     * 契约的另一半：**签名有效、但载荷不是 JSON 对象**（{@code []} / {@code "x"} / {@code null} / 空）
     * 同样必须是 IAE。它与上一条并列 —— 上一条走"字段缺失 / 整段不是 JSON"，这条走
     * {@code read()} 里的 {@code !node.isObject()} 那条分支；两者都只在签名**有效**之后才可能到达，
     * 篡改用例到不了。
     */
    @Test
    void aProperlySignedNonObjectPayloadIsRejectedAsIllegalArgument() {
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, signed("[]")));
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, signed("\"x\"")));
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, signed("null")));
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, signed("")));
    }

    /**
     * **密钥为 null/空是平台配置故障，不是凭证故障**：抛 {@link IllegalStateException}，
     * **不是** {@link IllegalArgumentException}（契约给令牌层失败用的那一种）。
     *
     * <p>两个方向都被钉住：{@code isExactlyInstanceOf(IllegalStateException.class)} 要求异常类型是
     * ISE 本身（IAE 不是 ISE 的子类，所以"退化成 IAE"会红），把类型换成别的 ISE 子类也会红。
     * 为什么要专门钉这条：{@code ConsoleToken.hmac} 把 JDK {@code SecretKeySpec} 对 null/空密钥抛的
     * IAE 包成 ISE，调用方若只 catch IAE 就会把"密钥没配"当成"令牌不行"，或者相反地让它变成 500。
     */
    @Test
    void aNullOrEmptySigningKeyIsAConfigurationFaultNotACredentialFault() {
        String validToken = ConsoleToken.issue(SECRET, claims());
        assertThatThrownBy(() -> ConsoleToken.verify(new byte[0], validToken))
                .isExactlyInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ConsoleToken.verify(null, validToken))
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        ConsoleClaims expired = new ConsoleClaims(7L, 1L, ConsoleClaims.ROLE_ADMIN, 1_600_000_000L, 1_600_000_001L);
        assertThatIllegalArgumentException().isThrownBy(
                () -> ConsoleToken.verify(SECRET, ConsoleToken.issue(SECRET, expired)));
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        String token = ConsoleToken.issue("other".getBytes(StandardCharsets.UTF_8), claims());
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, token));
    }

    @Test
    void aTokenWhoseHeaderClaimsAlgNoneIsRejectedBecauseTheAlgorithmIsServerSide() {
        // 手工拼一个 alg:none 的令牌（签名段为空）—— 校验方**根本不看请求里的 header**
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64("{\"sub\":\"7\",\"tenantId\":\"1\",\"role\":\"ADMIN\",\"exp\":9999999999}");
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, header + "." + payload + "."));
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, "alg-none-id." + payload + "."));
    }

    /**
     * ⚠️ **上面那条断言证明不了"算法由服务端钉死"**，这里是补上的那一条（已登记为实现记录）。
     *
     * <p>Java 的 {@code String.split("\\.")} **会丢掉末尾的空串**：{@code "h.p."} 得到的是
     * **2** 段，于是在「三段形状检查」处就被拒了 —— 一个「先判段数、再信请求 header 里 {@code alg}」
     * 的错误实现同样会拒掉它（只是理由不同）。实测：把 {@code verify} 改成「header 说 {@code none}
     * 就跳过签名比较」之后，上面那条用例**仍然全绿**（10/11）。
     *
     * <p>这条用例给签名段一个**非空**值，于是令牌真的是三段、真的走到签名判定：
     * **只要实现是"拿服务端写死的 HS256 重算并比对"，它就会被拒**。它钉住的那**一个**错误实现是：
     * 「解析请求 header，看到 {@code alg:none} 就**跳过签名比较**」—— 只有这种实现会放行一条攻击者
     * 自造的、载荷完全可信的令牌（{@code exp} 是 2286 年）。**不要把这件事说过头**：一个读了 header
     * 但把 header 当**白名单**判的实现（例如要求 {@code alg} 必须是 {@code HS256}、或把 header 字节与
     * 钉死的 header 逐字节比较）**同样会拒它** —— 判别的关键不是"看不看 header"，而是
     * "会不会因为 header 说 {@code none} 就跳过签名比较"。这既是 D3 要求的"alg 混淆面无"的
     * **判别性**证据，也是 brief 验收判据里「{@code alg:none} 全部被拒」的完整覆盖。
     */
    @Test
    void aThreeSegmentTokenWithAnAlgNoneHeaderIsRejectedBySignatureComparisonNotByShape() {
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        // 载荷本身**完全合法**（数字类型、字段齐全、exp 远在未来）：放行它只可能来自"跳过了签名比较"。
        String payload = b64("{\"sub\":7,\"tenantId\":1,\"role\":\"ADMIN\",\"iat\":4000000000,\"exp\":9999999999}");
        String forged = header + "." + payload + ".AAAA";

        assertThat(forged.split("\\.")).as("这条令牌必须是真正的三段，才能走到签名判定").hasSize(3);
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, forged));
    }

    /** 没有分段的输入（null / 空串 / 两段 / 四段）也必须走 IAE，而不是 NPE 或数组越界。 */
    @Test
    void aTokenThatIsNotThreeSegmentsIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, null));
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, ""));
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, "a.b"));
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, "a.b.c.d"));
    }

    private static ConsoleClaims claims() {
        return new ConsoleClaims(7L, 1L, ConsoleClaims.ROLE_VIEWER, ACTIVE_IAT, ACTIVE_EXP);
    }

    private static String b64(String raw) {
        return B64.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 造一条**签名正确**的令牌（header 与 {@link ConsoleToken#issue} 写死的完全一致，签名用同一个密钥），
     * 好让失败只能来自载荷解析 —— 用它来隔离「claims 缺失/类型不对」这条路径。
     */
    private static String signed(String rawPayload) {
        String signingInput = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "." + b64(rawPayload);
        return signingInput + "." + B64.encodeToString(hmac(signingInput));
    }

    private static byte[] hmac(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("测试夹具无法计算 HMAC", e);
        }
    }
}
