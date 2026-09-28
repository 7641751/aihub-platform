package com.aihub.service.console;

/**
 * 控制台令牌的载荷（D3）：{@code sub}（= {@code sys_user.id}）、{@code tenantId}、{@code role}、
 * {@code iat}、{@code exp} —— **恰好这五个字段**，线上用的声明名与这里的组件名一一对应
 * （{@code userId} ↔ {@code sub} 是唯一一处改名，写死在 {@link ConsoleToken} 的读写里，不做任何
 * "兼容"别的拼法）。
 *
 * <p><b>为什么没有 {@code aud}/{@code iss}/{@code jti}</b>：本控制台的签发方与校验方是同一个进程、
 * 同一种密钥、同一种算法，这两个声明只会被签出来而没有任何人会去比较它（"不校验的字段就等于没有"）。
 * 详见 {@link ConsoleToken} 的类注释里登记的边界。
 *
 * <p>时间字段一律是 **UTC epoch 秒**（与 {@code Instant.now().getEpochSecond()} 同一基准），
 * 不是毫秒：令牌的生存期是小时级，秒足够，且更小的整数让线上令牌短一点。
 *
 * <p>两个角色常量是 {@code /api/**} 授权的唯一两级（D10）：{@code ADMIN} 可读写、
 * {@code VIEWER} 只读。这里放常量而不是枚举，是为了让 Task 6 的过滤器与权限判定直接按字符串比较，
 * 不需要在令牌解析路径上再做一次枚举解析（解析失败又是一个 500 面）。
 */
public record ConsoleClaims(long userId, long tenantId, String role, long issuedAtEpochSecond, long expiresAtEpochSecond) {

    /** 控制台管理员：{@code /api/**} 可读可写。 */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 控制台只读用户：{@code /api/**} 仅 GET/HEAD。 */
    public static final String ROLE_VIEWER = "VIEWER";
}
