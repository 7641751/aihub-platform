package com.aihub.service.console;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 控制台令牌的签发/校验薄封装：把 {@code aihub.console.*} 的配置读进来，再把密钥策略与 TTL 封顶
 * 一次性钉死（D3 / D16），让调用方（{@code ConsoleAuthService}、{@code ConsoleAuthFilter}、
 * {@code ConsoleAuthController}）不需要各自重复判一遍。
 *
 * <p><b>本类承担的三件"契约之外"的事</b>（{@link ConsoleToken#verify} 刻意都不做，Task 5 的评审逐条点名）：
 * <ol>
 *   <li><b>密钥是否可用</b>：{@link ConsoleToken#verify} 对 {@code null}/空密钥抛的是
 *       {@link IllegalStateException}（配置故障），而 {@code /api/**} 的契约是 401、登录接口的契约是
 *       500 + {@code CONFIGURATION_ERROR}。因此"密钥为空**或短于 32 字符**"必须在这里判
 *       （{@link #isSecretUsable(String)} 是两处共用的**唯一**判据）。</li>
 *   <li><b>TTL 封顶</b>：{@link ConsoleToken#verify} 不看 {@code iat} 与 {@code exp} 的关系，
 *       所以"exp − iat ≤ 2h"只能在**签发端**保证 —— {@link #tokenTtl()} 返回的就是已按
 *       {@link #MAX_TOKEN_TTL} 夹过的值。</li>
 *   <li><b>密钥字节</b>：签发与校验共用 {@link #secretBytes()}（UTF-8），两边一致这件事不靠约定，
 *       靠只有一个出口。</li>
 * </ol>
 *
 * <p><b>密钥为空的语义是"门关着"，不是"用默认值"</b>（D16）：本类**没有**默认密钥，也不会生成一把
 * （默认密钥 = 所有人都能签令牌 = 管理台等于没有鉴权）。{@code application.yml} 里
 * {@code aihub.console.secret} 的默认值就是空串，且只来自环境变量。
 *
 * <p><b>为什么是 {@code @Value} 而不是一个 {@code @ConfigurationProperties} 记录</b>：见
 * {@code ConsoleProperties} 的类注释（模块方向 + {@code @WebMvcTest} 切片里
 * {@code jakarta.servlet.Filter} 会被实例化，过滤器只能依赖配置属性本身）。
 */
@Service
public class ConsoleTokenService {

    /**
     * HMAC 密钥的**长度下限**（字符数）：短于此值的密钥可被离线爆破，等于管理台没有鉴权
     * （与 {@code AesGcmChannelCipher} 在构造期拒绝 16 字节主密钥同一纪律）。
     *
     * <p>判据是"含 32"：32 字符可用，31 字符不可用。用字符数而不是字节数，是因为运维在
     * {@code .env} 里数的是字符；UTF-8 字节只在 HMAC 那一层出现。
     */
    public static final int MIN_SECRET_LENGTH = 32;

    /** 令牌的最大存活期（D3）：{@code exp − iat ≤ 2h}，签发端负责封顶。 */
    public static final Duration MAX_TOKEN_TTL = Duration.ofHours(2);

    private final String secret;
    private final Duration tokenTtl;

    public ConsoleTokenService(@Value("${aihub.console.secret:}") String secret,
                               @Value("${aihub.console.token-ttl:2h}") Duration tokenTtl) {
        this.secret = secret == null ? "" : secret;
        this.tokenTtl = clamp(tokenTtl);
    }

    /**
     * 密钥是否**可用**：非空白且不短于 {@link #MIN_SECRET_LENGTH} 字符。
     *
     * <p>唯一的判据：登录接口（回 {@code CONFIGURATION_ERROR}）与过滤器（一律 401）都调它，
     * 任何一处自己写"是不是空"都迟早与另一处漂移。
     */
    public static boolean isSecretUsable(String secret) {
        return secret != null && !secret.isBlank() && secret.strip().length() >= MIN_SECRET_LENGTH;
    }

    public boolean secretUsable() {
        return isSecretUsable(secret);
    }

    /** 已按 D3 封顶的 TTL（签发端用它算 {@code exp}）；零/负值按未配置处理，回落到封顶值。 */
    public Duration tokenTtl() {
        return tokenTtl;
    }

    /**
     * 签发令牌。
     *
     * @throws IllegalStateException 密钥不可用（平台配置故障）。**不是** {@code IllegalArgumentException}：
     *                               调用方必须能把"平台坏了"与"你的令牌不行"分开（D16）。
     */
    public String issue(ConsoleClaims claims) {
        requireUsableSecret();
        return ConsoleToken.issue(secretBytes(), claims);
    }

    /**
     * 校验令牌。
     *
     * @throws IllegalArgumentException 令牌层失败（格式/base64/签名/过期/claims 不对）
     * @throws IllegalStateException    密钥不可用（平台配置故障）
     */
    public ConsoleClaims verify(String token) {
        requireUsableSecret();
        return ConsoleToken.verify(secretBytes(), token);
    }

    private void requireUsableSecret() {
        if (!secretUsable()) {
            throw new IllegalStateException("控制台签名密钥未配置或短于 " + MIN_SECRET_LENGTH
                    + " 字符（aihub.console.secret / 环境变量 AIHUB_CONSOLE_SECRET）：这是平台配置故障");
        }
    }

    private byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 零/负值按"未配置"处理并回落到 {@link #MAX_TOKEN_TTL}，而不是签出一张**立刻过期**的令牌：
     * D3 的语义是"最多 2 小时"，一个 {@code 0s} 只会让登录接口 200、下一个请求 401 —— 那是把配置事故
     * 伪装成"令牌坏了"。超出封顶的值夹到 2 小时（并在启动时由 {@code ConsoleAuthFilter} 打 WARN）。
     */
    private static Duration clamp(Duration configured) {
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return MAX_TOKEN_TTL;
        }
        return configured.compareTo(MAX_TOKEN_TTL) > 0 ? MAX_TOKEN_TTL : configured;
    }
}
