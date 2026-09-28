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

/**
 * 控制台令牌（D3）的**纯单元**用例：没有 Spring 上下文、没有 Docker、没有 Redis ——
 * 令牌是 JDK 类型 + Jackson 的纯函数，任何容器都只会让这条契约更难判红。
 *
 * <p><b>用例覆盖</b>：往返（claims 逐字段不丢）、线格式（三段 + 固定 header 字节）、
 * 篡改（载荷与签名各一次）、已正确签名但载荷不合契约、过期、换密钥、{@code alg:none} 两条、
 * 以及分段数不对的四种输入。
 *
 * <p><b>判别性（实测，不是推断）</b>：把 {@code verify} 改成「解析请求 header，{@code alg} 为
 * {@code none} 就跳过签名比较」之后，**只有**
 * {@link #aThreeSegmentTokenWithAnAlgNoneHeaderIsRejectedBySignatureComparisonNotByShape()}
 * 会变红（实测 8/9），其余八条**全部仍然绿** —— 包括 brief 自己那条 {@code alg:none}（因为空签名段
 * 被 {@code split} 吞掉末尾空串、只有两段，在形状检查处就拒了，与是否看 header 无关）与
 * 线格式那条（header 字节是"写出来的"而不是"读进来的"）。所以「算法由服务端钉死」这条性质的**唯一**
 * 有效判据是那条补上的三段用例；它的 javadoc 里写明了为什么。
 *
 * <p><b>为什么失败路径只断言异常类型</b>：契约是「任何失败都抛 {@link IllegalArgumentException}」，
 * 调用方（{@code ConsoleAuthFilter}）只接这一种异常。断言消息文本会把实现细节钉死成契约，
 * 而这条契约的全部价值在**异常类型**上（受检异常逃出去就是 500，而契约要求 401）。
 */
class ConsoleTokenTest {

    private static final byte[] SECRET = "m4-console-test-secret".getBytes(StandardCharsets.UTF_8);

    /**
     * "仍然有效"的令牌夹具（{@code iat}/{@code exp} 为 UTC epoch 秒）。
     *
     * <p><b>为什么不是 brief 里的 {@code 1_700_000_000}/{@code 1_700_007_200}</b>：那一对是
     * <b>2023-11-14 ~ 2023-11-15</b>，而本机时钟是 **2026-09-28** —— 校验方按
     * {@code Instant.now()} 判定，这对时间戳在任何"服务端按当前时间判过期"的实现下都**必然是过期令牌**，
     * 于是 `roundTripsClaims` 不可能绿。这里改用 brief 自己那条 {@code alg:none} 载荷用的
     * {@code 9999999999}（2286-11-20）附近的值：同样是**固定**的（不用 {@code Instant.now()} 造时间，
     * 否则用例会引入时钟依赖、在边界秒上偶发变红），只是把"有效"这件事变成对实现真正成立的断言。
     * 失败路径（{@code anExpiredTokenIsRejected}）仍用 brief 原样的 {@code 1_600_000_000} 那一对 ——
     * 那一对**本来就该是过去的**。这是本任务唯一一处偏离 brief 字面量的地方，已登记在报告里。
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
     * **2** 段，于是在「三段形状检查」处就被拒了 —— 一个**信任请求 header 里 {@code alg}** 的错误实现
     * 同样会拒掉它（只是理由不同）。实测：把 {@code verify} 改成「header 说 {@code none} 就跳过签名比较」
     * 之后，上面那条用例**仍然全绿**（8/8）。
     *
     * <p>这条用例给签名段一个**非空**值，于是令牌真的是三段、真的走到签名判定：
     * 只有"拿服务端写死的 HS256 重算并比对"的实现才会拒它；任何看 header 的实现都会放行一条
     * 攻击者自造的、载荷完全可信的令牌（{@code exp} 是 2286 年）。这既是 D3 要求的"alg 混淆面无"
     * 的**判别性**证据，也是 brief 验收判据里「{@code alg:none} 全部被拒」的完整覆盖。
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
