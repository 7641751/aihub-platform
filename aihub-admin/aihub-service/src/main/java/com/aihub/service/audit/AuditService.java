package com.aihub.service.audit;

import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.mapper.AuditLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 审计写入的服务层入口（决策 D1/D8）。
 *
 * <p><b>必须在调用方的事务里</b>：{@link #record} 刻意**不加** {@code @Transactional(REQUIRES_NEW)}
 * （也没有 {@code @Transactional} 本身，因此它不会开自己的事务，而是加入调用方已经打开的那个）。
 * 这不是风格选择，而是审计语义的底线 ——
 * <ul>
 *   <li>若审计用了独立事务，「业务改了但没审计」在业务回滚时就会发生（审计行活下来），
 *       审计日志会开始说谎；</li>
 *   <li>若审计不写而业务提交，「改了但没审计」同样成立，而审计是合规要求（D8），不是尽力而为。</li>
 * </ul>
 * 所以本类**永不吞异常**：{@link ObjectMapper#writeValueAsString} 抛的受检
 * {@link JsonProcessingException} 在这里被转成 {@link IllegalStateException} 抛出 ——
 * 审计失败要让业务一起失败，同时受检异常不许从方法签名里漏出去。
 *
 * <p><b>detail 只放非敏感字段，而且这一条由本类自己兜底</b>：计划第 25 行是硬约束 ——
 * 「审计表**绝不记录** API Key 明文、渠道明文密钥、{@code api_key_cipher} 密文原文、主密钥、
 * 控制台口令/口令哈希、令牌原文」。任务书给出的实现样例把调用方交来的 map **原样**
 * {@code writeValueAsString}，那样「绝不记录」只是调用方的口头承诺，而它自己的验收用例
 * （{@code theAuditDetailNeverContainsSecrets}）当场就会红。所以这里在写入边界上做**脱敏**：
 * <ul>
 *   <li><b>按键名</b>：{@code apiKey}/{@code api_key_cipher}/{@code password}/{@code token}/
 *       {@code secret}/{@code cipher} 这类名字下的值一律替换为 {@value #REDACTED}，
 *       无论值长什么样；</li>
 *   <li><b>按值的形态</b>：自描述密文前缀 {@code v1:}、明文 Key 前缀 {@code sk-}、
 *       以及形似 JWT / bcrypt 的串一律替换 —— 「名字看着无害但值就是密钥」的情况只能靠值本身判。</li>
 * </ul>
 * 脱敏是**替换成 {@value #REDACTED} 而不是丢字段**：审计要能回答「改了哪些字段」，
 * 悄悄丢掉一个字段会让审计日志少一行事实，比多一个「[REDACTED]」更糟。
 *
 * <p>{@code createdAt} 刻意不设：{@code audit_log.created_at} 是
 * {@code DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)}，由库填充（MyBatis-Plus 的默认
 * 字段策略会把 null 字段从 INSERT 里省略）。这一条由
 * {@code AuditServiceIntegrationTest#createdAtIsPopulatedByTheDatabaseDefaultEvenThoughTheServiceNeverSetsIt}
 * 断言，而不是假设。
 */
@Service
public class AuditService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 脱敏后的占位符：它必须一眼能看出「这里原本有值，被有意抹掉了」。 */
    static final String REDACTED = "[REDACTED]";

    /**
     * 敏感**键名**（先归一化成小写并去掉 {@code _} / {@code -}）。
     *
     * <p>用「包含」而不是「相等」：真实的键名会长成 {@code apiKeyCipher}、{@code new_api_key}、
     * {@code consoleToken}、{@code masterSecret} 这些形态，穷举相等是永远追不上的。
     * 代价是会误伤（例如 {@code tokenCount}），而误伤的后果只是审计里多一个 {@value #REDACTED} ——
     * 比漏掉一把真密钥轻得多，这正是这个边界该有的偏向。
     */
    private static final List<String> SENSITIVE_KEY_FRAGMENTS = List.of(
            "apikey", "secret", "password", "passwd", "credential",
            "token", "cipher", "masterkey", "privatekey", "bcrypt", "hash");

    /**
     * 敏感**值的形态**。键名不可信（{@code {"reason": "sk-live-..."}} 看起来完全无害），
     * 所以值的形状必须单独判一次。
     */
    private static final List<Pattern> SENSITIVE_VALUE_PATTERNS = List.of(
            Pattern.compile("^v\\d+:"),                       // AesGcmChannelCipher 的自描述密文 v{n}:{base64}
            Pattern.compile("^sk-[A-Za-z0-9_-]{4,}$"),        // OpenAI/Anthropic 风格的明文 Key
            Pattern.compile("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\."),  // JWT（控制台令牌）
            Pattern.compile("^\\$2[aby]\\$\\d\\d\\$"));       // bcrypt 口令哈希

    /**
     * 递归深度上限。detail 是调用方构造的 map，理论上可能自引用；
     * 在这里撞上 {@code StackOverflowError} 会让「审计失败要响亮」变成「整个线程挂掉」。
     * 超过深度就整段替换为 {@value #REDACTED}（宁可少记，不可崩）。
     */
    private static final int MAX_DEPTH = 8;

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 审计的主体。{@code actor_type} 列是 {@code VARCHAR(16)}，取值只有 {@code "USER"} 与
     * {@code "SYSTEM"}（见下）。
     */
    public record Actor(String type, String id) {
    }

    /**
     * 写一条审计行。**必须在调用方的事务里**（不加 {@code @Transactional(REQUIRES_NEW)}）：
     * 「改了但没审计」是不可接受的，所以审计失败要让业务一起回滚。
     * detail 只放**非敏感**字段（如渠道名、权重、状态）；即便调用方失误放进去了敏感值，
     * 本类也会在落库前把它替换成 {@value #REDACTED}。
     *
     * @param tenantId 租户上下文；**真的没有租户上下文时传 {@code null}**（写 SQL NULL），
     *                 不要用 {@code 0} 当哨兵 —— {@code 0} 与真实 id 空间无法区分（id 从 1 开始）。
     *                 {@code LOGIN_FAILURE}（用户名不存在）就是这种事件。
     * @param detail   非敏感的变更摘要；{@code null} 或空 map 落库为 SQL NULL。
     * @throws IllegalStateException 脱敏后的 detail 仍无法序列化成 JSON（审计写不出来 → 业务必须失败）
     */
    public void record(Long tenantId, Actor actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        AuditLogEntity row = new AuditLogEntity();
        row.setTenantId(tenantId);
        row.setActorType(actor.type());
        row.setActor(actor.id());
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setDetail(serializeDetail(detail));
        auditLogMapper.insert(row);
    }

    /**
     * 空 detail 落成 SQL NULL 而不是 {@code "{}"}：{@code "{}"} 会让「没有变更摘要」与
     * 「摘要恰好为空对象」这两种行在 SQL 里长得一样，而前者是可以被 {@code WHERE detail IS NULL}
     * 直接筛的。
     */
    private static String serializeDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = redactMap(detail, 0);
        try {
            return MAPPER.writeValueAsString(safe);
        }
        catch (JsonProcessingException ex) {
            // 异常消息里**不带** detail 的内容：它会被写进日志，而审计的 detail 就在敏感边界上
            // （调用方一旦传错，把内容打进日志就等于把密钥泄漏到另一个地方）。只给字段名。
            throw new IllegalStateException(
                    "审计 detail 无法序列化为 JSON，审计失败即业务失败（detail 键：" + safe.keySet() + "）", ex);
        }
    }

    /** 逐层脱敏，保持插入顺序（LinkedHashMap）：审计的 detail 要人能读。 */
    private static Map<String, Object> redactMap(Map<String, Object> source, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            out.put(key, isSensitiveKey(key) ? REDACTED : redactValue(entry.getValue(), depth + 1));
        }
        return out;
    }

    private static Object redactValue(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return REDACTED;
        }
        if (value instanceof String text) {
            return isSensitiveValue(text) ? REDACTED : text;
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                String key = String.valueOf(entry.getKey());
                out.put(key, isSensitiveKey(key) ? REDACTED : redactValue(entry.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(redactValue(item, depth + 1));
            }
            return out;
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    private static boolean isSensitiveValue(String value) {
        return SENSITIVE_VALUE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }
}
