package com.aihub.service.console;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.dao.entity.SysUserEntity;
import com.aihub.dao.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 控制台登录：用户名 + 口令 → {@link ConsoleClaims}（令牌的**载荷**，签发本身由
 * {@link ConsoleTokenService#issue} 做）。
 *
 * <p><b>防用户枚举必须是完整的</b>：口令错、用户名不存在、账号停用三条路径返回**同一句**文案
 * （{@link #UNIFORM_FAILURE}）、同一个状态码（401），并且 —— 这一条最容易被漏掉 —— 用户名不存在时
 * **也要**对一个常量假哈希跑一次 {@link BCryptPasswordEncoder#matches}。否则两条路径的耗时差约 100ms，
 * 响应体一样也照样能枚举用户；"少跑一次 bcrypt"正是那个漏洞的成因，因此它有一条专门的用例
 * （{@code ConsoleAuthFilterTest#aMissingUserStillPaysTheBcryptCost}）钉住**调用发生**，
 * 而不是去断言墙上时钟（时间断言在 CI 上必然偶发）。
 *
 * <p><b>密钥不可用是配置故障，不是凭证故障</b>（D16）：此时登录回 500 + {@code CONFIGURATION_ERROR}，
 * 明确告诉运维"平台没配好"，而不是 401 让他去翻口令。门的那一半（{@code /api/**} 一律 401）由
 * {@code ConsoleAuthFilter} 负责。
 *
 * <p><b>本类不判角色</b>（D10）：{@code sys_user.role} 原样进令牌，{@code ADMIN}/{@code VIEWER} 以外的
 * 角色由 {@code ConsoleAuthFilter} **fail-closed** 拒掉（连读都不放行）。在这里再判一次会造出第二个
 * 角色真相源，而令牌本身并不携带任何权限 —— 一个未知角色的令牌什么都授权不了。
 *
 * <p><b>本任务不写审计</b>：{@code AuditService} 属 Task 7（尚未落地），按 brief 的规定这里**不写**
 * 占位调用，也不写"待 Task 7 收口"这类注释。登录失败的可观测性目前只有 HTTP 响应本身。
 */
@Service
public class ConsoleAuthService {

    private static final String ACTIVE = "ACTIVE";

    /**
     * 统一失败文案：口令错 / 用户不存在 / 账号停用**共用**它。文案里不许出现"用户不存在"这类区分，
     * 也不许回显用户名（那本身就是枚举信号）。
     */
    static final String UNIFORM_FAILURE = "用户名或口令不正确";

    /**
     * 常量假哈希：用户名不存在时对着它跑一次 bcrypt，把两条路径的耗时拉平。
     *
     * <p><b>为什么在类加载时用真编码器生成</b>而不是写一个字面量：{@code BCryptPasswordEncoder.matches}
     * 对**格式不合法**的哈希抛 {@code IllegalArgumentException}（"Invalid salt version"），
     * 那会把"用户不存在"从 401 变成 500；而一个手抄的 {@code $2a$10$...} 字面量恰好很容易抄错
     * （长度/字符集）。生成一次（cost 与默认一致，约 100ms，只发生在类加载）就没有这个风险。
     * 它是**一次性**的一次性口令哈希，对应的明文从不入库、也绝不用于任何账号。
     * <p><b>残余（诚实登记）</b>：假哈希用 {@code BCryptPasswordEncoder} 的**默认 cost（10）**生成，
     * 而某个真实用户的哈希可能由运维用更高的 cost 生成；那种情况下两条路径的耗时仍有可测量的差
     * （约一个 cost 档位）。本任务没有"用户管理"接口去约束 cost，所以这条差异只能登记、不能在此消除。
     * 它比"完全不做 bcrypt"小一个数量级，且要利用它需要先知道目标用户名存在（也就是说枚举本身
     * 仍然需要逐名试探）。
     */
    private static final String DUMMY_PASSWORD_HASH =
            new BCryptPasswordEncoder().encode("aihub-console-dummy-password-never-used-by-any-account");

    private static final String CONFIG_FAULT_MESSAGE =
            "控制台签名密钥未配置或过短（aihub.console.secret / 环境变量 AIHUB_CONSOLE_SECRET）："
                    + "这是平台配置故障，不是口令问题";

    private final SysUserMapper sysUserMapper;
    private final ConsoleTokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder;

    @Autowired
    public ConsoleAuthService(SysUserMapper sysUserMapper, ConsoleTokenService tokenService) {
        this(sysUserMapper, tokenService, new BCryptPasswordEncoder());
    }

    /**
     * 允许传入一个**受控的**口令编码器的装配口（单测用它判定"什么时候、对哪个哈希调用了 bcrypt"）。
     *
     * <p>两个构造器里必须有一个 {@code @Autowired}：Spring 不会在有多个候选时替我们猜。
     */
    public ConsoleAuthService(SysUserMapper sysUserMapper, ConsoleTokenService tokenService,
                              BCryptPasswordEncoder passwordEncoder) {
        this.sysUserMapper = sysUserMapper;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 校验口令并产出令牌载荷。
     *
     * @throws BizException {@code CONFIGURATION_ERROR}（500）密钥未配置/过短；
     *                      {@code UNAUTHORIZED}（401）口令错、用户不存在或账号停用（三条路径不可区分）
     */
    public ConsoleClaims login(String username, String password) {
        if (!tokenService.secretUsable()) {
            throw new BizException(ErrorCode.CONFIGURATION_ERROR, CONFIG_FAULT_MESSAGE);
        }
        SysUserEntity user = lookup(username);
        // 用户不存在时也走一次 bcrypt（同一个编码器、同一个 cost）：两条路径的**耗时**也必须同构。
        String storedHash = user == null ? DUMMY_PASSWORD_HASH : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(password == null ? "" : password, storedHash);
        if (user == null || !passwordMatches || !ACTIVE.equals(user.getStatus())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, UNIFORM_FAILURE);
        }
        long issuedAt = Instant.now().getEpochSecond();
        // TTL 由 ConsoleTokenService 按 D3 封顶（exp − iat ≤ 2h），这里只负责把它加进去。
        return new ConsoleClaims(user.getId(), user.getTenantId(), user.getRole(),
                issuedAt, issuedAt + tokenService.tokenTtl().toSeconds());
    }

    /** 按用户名取一行；{@code username} 为 null/空白时**不查库**（走"用户不存在"那条同构路径）。 */
    private SysUserEntity lookup(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getUsername, username));
    }
}
