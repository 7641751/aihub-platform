package com.aihub.admin.web.config;

import com.aihub.service.console.ConsoleTokenService;

import java.time.Duration;

/**
 * {@code aihub.console.*} 在 **web 层**的视图：令牌签名密钥与存活期（D3 / D16）。
 *
 * <p><b>为什么它是个普通 record，而不是 {@code @ConfigurationProperties} / {@code @Component}</b>
 * （两处硬约束，都不是审美）：
 * <ol>
 *   <li><b>模块方向</b>：{@code ConsoleTokenService} 在 {@code aihub-service}，它读的也是这两个属性，
 *       但它**不能**依赖 {@code aihub-web} 的类型（依赖是 web → service）。两处各读一次同一个属性键，
 *       而"是否可用"的判据只有一份（{@link ConsoleTokenService#isSecretUsable(String)}），
 *       所以不会漂移。</li>
 *   <li><b>切片上下文</b>：{@code jakarta.servlet.Filter} 在 {@code @WebMvcTest} 的默认包含清单里
 *       （已在 spring-boot-test-autoconfigure 的 {@code WebMvcTypeExcludeFilter} 字节码里核实），
 *       因此 {@code ConsoleAuthFilter} 会在任何一个 Web 切片里被实例化 —— 而切片里**没有**
 *       {@code @Service}/{@code @Component}。所以过滤器只能从 {@code @Value} 拿配置、自己 new 出
 *       本类与 {@code ConsoleTokenService}；否则既有的 {@code GlobalExceptionHandlerTest} 会因为
 *       "找不到 ConsoleProperties bean"而整体变红。</li>
 * </ol>
 * 这也是既有 {@code InternalAuthFilter} 的手法（{@code @Value("${aihub.internal.secret:}")}）。
 *
 * <p>两个谓词（{@link #secretUsable()}、{@link #tokenTtlMisconfigured()}）只给启动告警用；
 * 请求路径上的判定必须走 {@link ConsoleTokenService}（它拿的是同一份配置，且是唯一的判据出口）。
 *
 * @param secret   令牌的 HMAC 密钥（UTF-8 字节）；空 = 门关着（D16），**永不生成默认值**。
 *                 构造期经 {@link ConsoleTokenService#normalizeSecret(String)} 规范化（首尾空白去掉），
 *                 因此 {@link #secret()} 的长度、{@link #secretUsable()} 的判定与签发端真正使用的
 *                 密钥字节是同一个值 —— 启动 WARN 里报的长度不可能与可用性判据矛盾
 * @param tokenTtl 令牌存活期；超出 {@link ConsoleTokenService#MAX_TOKEN_TTL} 时由签发端夹到 2 小时
 */
public record ConsoleProperties(String secret, Duration tokenTtl) {

    public ConsoleProperties {
        // 规范化必须走 ConsoleTokenService 的那**一个**入口：这里再写一次 strip() 就等于又开了一个
        // "被验证的值"，而启动 WARN 报的长度正是从这里读的（长度与可用性判据必须同源）。
        secret = ConsoleTokenService.normalizeSecret(secret);
        tokenTtl = tokenTtl == null ? ConsoleTokenService.MAX_TOKEN_TTL : tokenTtl;
    }

    /** 密钥是否可用（非空白且 ≥ 32 字符）。语义与判据都在 {@link ConsoleTokenService}。 */
    public boolean secretUsable() {
        return ConsoleTokenService.isSecretUsable(secret);
    }

    /**
     * TTL 是否配得不合理：非正数（会被当作未配置、回落到 2 小时）或超出 2 小时封顶（会被夹到 2 小时）。
     * 两种情况都不影响启动，但都必须让运维在启动日志里看见"你配的值没有按字面生效"。
     */
    public boolean tokenTtlMisconfigured() {
        return tokenTtl.isZero() || tokenTtl.isNegative()
                || tokenTtl.compareTo(ConsoleTokenService.MAX_TOKEN_TTL) > 0;
    }
}
