package com.aihub.service.console;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * 控制台令牌：**JWT 形状**的自研 HS256 实现（D3），不是 JWT 标准实现。
 *
 * <p>线格式 {@code base64url(header).base64url(payload).base64url(HMAC-SHA256(前两段))}，
 * header 恒为 {@code {"alg":"HS256","typ":"JWT"}}。**算法与 header 都是服务端写死的：
 * {@link #verify} 根本不读请求里的 header**，因此不存在 {@code alg} 混淆面
 * （{@code alg:none}、{@code alg:HS256} 与任何其它 header 字节都会被同样处理：
 * 要么三段形状不对被拒，要么按 HS256 重算签名后被拒）。
 *
 * <p><b>为什么放在 {@code aihub-service} 而不是零依赖的 {@code aihub-common}</b>：载荷用 Jackson
 * 读写（固定五个字段），而 {@code aihub-common} 的 main 作用域必须零第三方依赖；只有 admin 用它。
 * 手写一份 JSON 解析器只会给令牌引入无谓的脆弱点。
 *
 * <p><b>故障面（契约）：任何失败都抛 {@link IllegalArgumentException}</b> —— 格式不对、base64 不合法、
 * 签名不匹配、已过期、claims 缺失或类型不对，全部同一种异常。调用方（{@code ConsoleAuthFilter}）只接
 * 这一种异常并回 401；**受检的 {@link JsonProcessingException} 必须在这里被包住**，否则它会穿透过滤器
 * 变成 500 —— 401 与 500 的区别正是"你的令牌不行"与"平台坏了"。
 *
 * <p><b>D3 登记的边界（必须随代码一起被读到）</b>：
 * <ul>
 *   <li><b>只支持 HS256</b>：没有 RS256/JWKS/密钥协商。对称密钥同时具备签发与校验能力，
 *       任何拿到密钥的一方都能伪造令牌 —— 所以它只能存在于签发方与校验方是同一个信任域的部署里。</li>
 *   <li><b>不做密钥轮换</b>：没有 {@code kid}，换密钥 = 所有已签发令牌立刻失效，
 *       没有"新旧密钥并存一段时间"的过渡。</li>
 *   <li><b>不校验 {@code aud}/{@code iss}</b>：不验签发方，也不验受众。</li>
 *   <li><b>没有 refresh、也没有吊销黑名单</b>：令牌一旦签发，在 {@code exp} 之前一直有效；
 *       要提前失效只能换密钥（等于把所有人踢下线）。</li>
 * </ul>
 * 这些边界是刻意的取舍（本控制台是**单签发方、单算法、短生命周期**的场景，引 JWT 库要付三个依赖
 * 而为不存在的需求买单）。<b>一旦出现第二签发方、需要吊销、或需要非对称密钥，本类必须被替换成库
 * （jjwt / java-jwt / nimbus），不要在它上面加补丁</b> —— 手写密码学代码的正确性无法靠测试穷尽。
 *
 * <p>恒定时间比较（{@link MessageDigest#isEqual}）与"忽略请求 alg"是两处防伪必需项，各有一条用例。
 */
public final class ConsoleToken {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private static final String ALGORITHM = "HmacSHA256";

    private static final String SUB_CLAIM = "sub";
    private static final String TENANT_ID_CLAIM = "tenantId";
    private static final String ROLE_CLAIM = "role";
    private static final String ISSUED_AT_CLAIM = "iat";
    private static final String EXPIRES_AT_CLAIM = "exp";

    /**
     * 写死的 header 的 base64url 形式。校验方只把它当作签名输入的一部分（**不解析它**），
     * 所以请求里换一个 header 只会让签名对不上，而不会改变校验行为。
     */
    private static final String HEADER = ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private ConsoleToken() {
    }

    /**
     * 签发：把 claims 写成固定五个字段，再对 {@code header.payload} 做 HMAC-SHA256。
     *
     * <p>这里**不校验** {@code claims} 的内容（例如 {@code exp} 是否已经过去）：签发过期令牌是调用方
     * 的编程错误，而 {@link #verify} 会拒绝它 —— 在签发处再判一次只会多一条今天不可达的分支。
     */
    public static String issue(byte[] secret, ConsoleClaims claims) {
        String payload = ENCODER.encodeToString(write(claims));
        String signingInput = HEADER + "." + payload;
        return signingInput + "." + ENCODER.encodeToString(hmac(secret, signingInput));
    }

    /**
     * 校验并取回 claims。**任何失败都抛 {@link IllegalArgumentException}**（见类注释的故障面），
     * 因此调用方不需要 {@code Optional} 的三态：要么拿到可信的 claims，要么 401。
     *
     * @throws IllegalArgumentException 令牌为 null、不是三段、签名不匹配、载荷不是合法 base64/JSON、
     *                                  claims 缺失或类型不对、已过期
     */
    public static ConsoleClaims verify(byte[] secret, String token) {
        if (token == null) {
            throw new IllegalArgumentException("请求里没有控制台令牌");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            // alg:none 的手拼令牌（签名段为空 → 只有两段）在这一步就被拒，永远不会走到签名比较。
            throw new IllegalArgumentException("控制台令牌必须是 header.payload.signature 三段");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(secret, signingInput);
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (RuntimeException e) {
            // 非法 base64（含 Java 的 IllegalArgumentException）：与签名不匹配同一类失败。
            throw new IllegalArgumentException("控制台令牌的签名段不是合法的 base64url", e);
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            // 恒定时间比较（D3）：按字节提前返回会把签名逐字节试出来。
            throw new IllegalArgumentException("控制台令牌的签名不匹配");
        }
        ConsoleClaims claims = read(decodePayload(parts[1]));
        if (claims.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("控制台令牌已过期");
        }
        return claims;
    }

    /** 载荷段的 base64url 解码；畸形输入统一转成 IAE（与签名段同一处理）。 */
    private static byte[] decodePayload(String segment) {
        try {
            return DECODER.decode(segment);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("控制台令牌的载荷段不是合法的 base64url", e);
        }
    }

    /**
     * 把 claims 序列化成**固定五个**字段。{@code userId} 在线上写作标准的 {@code sub} 声明
     * （其余四个字段名与 record 组件同名）。
     */
    private static byte[] write(ConsoleClaims claims) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(SUB_CLAIM, claims.userId());
        node.put(TENANT_ID_CLAIM, claims.tenantId());
        node.put(ROLE_CLAIM, claims.role());
        node.put(ISSUED_AT_CLAIM, claims.issuedAtEpochSecond());
        node.put(EXPIRES_AT_CLAIM, claims.expiresAtEpochSecond());
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            // 对象里全是 long/String，这条路径今天不可达；保留它是为了让"签发失败"也有确定类型。
            throw new IllegalStateException("无法序列化控制台令牌载荷", e);
        }
    }

    /**
     * 反序列化**固定五个**字段：{@code sub} → {@code userId}，其余四个显式按名字取。
     *
     * <p><b>为什么不直接 {@code readValue(bytes, ConsoleClaims.class)}</b>：那需要
     * {@code -parameters}（本仓库的编译器配置里没有）或给 record 加 {@code @JsonProperty}，
     * 而且 {@code sub} 与 {@code userId} 的名字差异还需要一个注解去桥接。手写映射把"线上声明名"
     * 这件事收在 {@link ConsoleToken} 内部，并且让"缺失/类型不对"变成一个**显式**的 IAE，
     * 而不是 Jackson 那串又长又依赖版本的消息。
     *
     * <p><b>刻意不做字符串/数字互转</b>：载荷是照着本类写出来的（{@link #write} 只写数字），
     * 所以"数字类型"就是契约本身；容忍 {@code "7"} 这种写法只会让未来某次线格式漂移变得不可见。
     *
     * @throws IllegalArgumentException 载荷不是合法 JSON、不是对象、或任一字段缺失/类型不对
     */
    private static ConsoleClaims read(byte[] payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("控制台令牌的载荷不是 JSON 对象");
            }
            return new ConsoleClaims(
                    requireLong(node, SUB_CLAIM),
                    requireLong(node, TENANT_ID_CLAIM),
                    requireText(node, ROLE_CLAIM),
                    requireLong(node, ISSUED_AT_CLAIM),
                    requireLong(node, EXPIRES_AT_CLAIM));
        } catch (IOException e) {
            // **受检异常必须在这里被包住**：漏出去就是过滤器外的 500，而契约要求 401。
            // 注意 catch 的是 IOException 而不是 JsonProcessingException：Jackson 的
            // `readTree(byte[])` 把受检异常声明成 IOException（JsonProcessingException 是它的子类），
            // 只 catch 子类在这里根本编译不过 —— 而这正是本类要防的那件事。
            throw new IllegalArgumentException("控制台令牌的载荷不是合法 JSON", e);
        } catch (RuntimeException e) {
            // 上面的 requireXxx 抛的 IAE 直接透传语义；其余运行时异常（含 Jackson 包装层的意外）
            // 也一并归到 IAE —— 校验路径上不允许有第二种异常类型逃出去。
            if (e instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("控制台令牌的载荷无法解析", e);
        }
    }

    private static long requireLong(JsonNode node, String claim) {
        JsonNode value = node.get(claim);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("控制台令牌缺少整数声明 " + claim);
        }
        return value.longValue();
    }

    private static String requireText(JsonNode node, String claim) {
        JsonNode value = node.get(claim);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("控制台令牌缺少字符串声明 " + claim);
        }
        return value.textValue();
    }

    /** HMAC-SHA256；算法名是写死的常量，因此 {@code NoSuchAlgorithmException} 属于 JVM 层面的故障。 */
    private static byte[] hmac(byte[] secret, String signingInput) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算控制台令牌签名", e);
        }
    }
}
