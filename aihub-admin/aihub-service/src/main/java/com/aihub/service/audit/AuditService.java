package com.aihub.service.audit;

import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.mapper.AuditLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
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
 * <p><b>detail 里不放敏感值首先是调用方的责任；本类的脱敏只是安全网，不是许可证</b>（D8）。
 * 计划第 25 行是硬约束 ——「审计表**绝不记录** API Key 明文、渠道明文密钥、{@code api_key_cipher}
 * 密文原文、主密钥、控制台口令/口令哈希、令牌原文」。因此：
 * <ul>
 *   <li><b>调用方绝不许把上述任何值放进 {@code detail}</b>。审计记录的准确性依赖调用方的纪律：
 *       写入边界的脱敏按**键名片段**与**值的形态**两个**启发式**工作，两条都不是保证 ——
 *       例如 {@code "Bearer sk-…"}、{@code "prefix v1:…"}、AWS 的 {@code AKIA…}
 *       这类带前后缀的形态都不会被识别（见 {@link #SENSITIVE_VALUE_PATTERNS}）。
 *       判据「审计 detail 不含任何密钥/口令/密文/令牌」的第一条路径永远是**调用方不放**；</li>
 *   <li>安全网覆盖的形状：嵌套对象/数组/任意可被 Jackson 表示的值（含 POJO —— 先被转成 JSON 树，
 *       再逐节点按同一套规则走一遍，因此实体的字段**不会**被原样序列化）、按键名命中的值、
 *       按值形态命中的字符串，以及**自身就是密钥形态的键**（见 {@link #REDACTED_KEY}）；</li>
 *   <li><b>已知代价：过度脱敏</b>。键名用「包含」匹配（见 {@link #SENSITIVE_KEY_FRAGMENTS}），
 *       于是 {@code tokenCount} 这类**非敏感**键也会被替换成 {@value #REDACTED} ——
 *       审计读到的就是「这里有 tokenCount，值被抹了」。这是**有意接受的代价**：多一个
 *       {@code [REDACTED]} 远轻于漏掉一把真密钥。它是被用例钉住的决定，不是文档里的一句免责；</li>
 *   <li><b>替换而不是丢字段</b>：审计要能回答「改了哪些字段」，悄悄丢掉一个字段 = 少一行事实，
 *       比多一个 {@code [REDACTED]} 更糟。深度上限触顶时被替换的是**子树的内容**，字段本身仍在
 *       （见 {@link #MAX_DEPTH}）。</li>
 * </ul>
 *
 * <p>{@code created_at} 由本类**显式**写成 UTC 墙上时间
 * （{@code LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)}，与计量路径
 * {@code RequestLogService} 完全一致）：{@code audit_log.created_at} 是**无时区**的
 * {@code DATETIME(3)}，若靠 {@code DEFAULT CURRENT_TIMESTAMP(3)} 填充，存储基准就跟着 MySQL 会话时区走，
 * 而 {@link AuditLogEntity#getCreatedAt()} 读回来的是那一格的字面值 —— 同一列于是可能出现两套墙钟基准。
 * 显式按 UTC 写之后，写入基准与库会话时区、与 JDBC 驱动的时区推导都无关（CONVENTIONS §7）。
 * 时钟可注入（{@link #AuditService(AuditLogMapper, Clock)}），默认 {@code Clock.systemUTC()}。
 */
@Service
public class AuditService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 脱敏后的占位符：它必须一眼能看出「这里原本有值，被有意抹掉了」。 */
    static final String REDACTED = "[REDACTED]";

    /**
     * **键本身**是密钥形态时的占位符（{@code {"sk-…": "ENABLED"}}）。
     *
     * <p>键的文本和值一样是调用方交来的**内容**，所以它也过一遍值形态判断；命中就把键换掉、
     * **保留值** —— 「这里有一个字段」这一条事实要留下（见类注释「替换而不是丢字段」）。
     */
    static final String REDACTED_KEY = "[REDACTED_KEY]";

    /**
     * 敏感**键名**（先归一化成小写并去掉 {@code _} / {@code -}）。
     *
     * <p>用「包含」而不是「相等」：真实的键名会长成 {@code apiKeyCipher}、{@code new_api_key}、
     * {@code consoleToken}、{@code masterSecret} 这些形态，穷举相等是永远追不上的。
     * 代价是会误伤（例如 {@code tokenCount}），而误伤的后果只是审计里多一个 {@value #REDACTED} ——
     * 比漏掉一把真密钥轻得多，这正是这个边界该有的偏向。
     *
     * <p>这一条**只作用于键对应的值**，不作用于键名本身：键名 {@code apiKey} 是**标签**，
     * 它自己不泄漏任何内容，而把两个标签都替换成同一个占位符会让「改了哪两个字段」这件事消失。
     * 键名本身是密钥的情况由值形态判断兜住（见 {@link #REDACTED_KEY}）。
     */
    private static final List<String> SENSITIVE_KEY_FRAGMENTS = List.of(
            "apikey", "secret", "password", "passwd", "credential",
            "token", "cipher", "masterkey", "privatekey", "bcrypt", "hash");

    /**
     * 敏感**值的形态**。键名不可信（{@code {"reason": "sk-live-..."}} 看起来完全无害），
     * 所以值的形状必须单独判一次。
     *
     * <p><b>这是启发式，不是保证</b>：四个模式都是**锚定**的，带前后缀的形态
     * （{@code "Bearer sk-…"}、{@code "prefix v1:…"}、{@code "key=sk-…"}）一律漏过。
     * 它只能当安全网用 —— 调用方仍然不许把密钥放进 detail（见类注释）。
     */
    private static final List<Pattern> SENSITIVE_VALUE_PATTERNS = List.of(
            Pattern.compile("^v\\d+:"),                       // AesGcmChannelCipher 的自描述密文 v{n}:{base64}
            Pattern.compile("^sk-[A-Za-z0-9_-]{4,}$"),        // OpenAI/Anthropic 风格的明文 Key
            Pattern.compile("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\."),  // JWT（控制台令牌）
            Pattern.compile("^\\$2[aby]\\$\\d\\d\\$"));       // bcrypt 口令哈希

    /**
     * 递归深度上限。detail 是调用方构造的 map，理论上可能自引用；
     * 在这里撞上 {@code StackOverflowError} 会让「审计失败要响亮」变成「整个线程挂掉」。
     * 超过深度就**把该子树的内容**替换为 {@value #REDACTED}：键与这一层事实都还在，
     * 丢掉的只是更深的内容（与类注释的「替换而不是丢字段」一致）。
     */
    private static final int MAX_DEPTH = 8;

    private final AuditLogMapper auditLogMapper;
    private final Clock clock;

    /**
     * Spring 注入用的构造器：时钟默认 {@code Clock.systemUTC()}（生产路径不依赖 JVM 默认时区）。
     */
    @Autowired
    public AuditService(AuditLogMapper auditLogMapper) {
        this(auditLogMapper, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造器：用例用它钉住固定瞬时，证明 {@code created_at} 来自**本类的时钟**
     * 而不是库默认值（也不是 JVM 本地墙钟）。
     */
    public AuditService(AuditLogMapper auditLogMapper, Clock clock) {
        this.auditLogMapper = auditLogMapper;
        this.clock = clock;
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
     *
     * <p>detail 只放**非敏感**字段（如渠道名、权重、状态）。**调用方仍不得把明文密钥、渠道密钥、
     * {@code api_key_cipher} 密文、主密钥、口令/口令哈希、令牌放进 detail**：本类在写入边界的脱敏是
     * **安全网，不是许可证** —— 它按启发式工作、覆盖不到所有形态，审计记录的准确性依赖调用方的纪律
     * （见类注释）。
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
        // 显式 UTC 墙上时间（不是库默认值、不是 JVM 本地墙钟）：见类注释。
        row.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
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
            // （调用方一旦传错，把内容打进日志就等于把密钥泄漏到另一个地方）。只给（已脱敏的）字段名。
            throw new IllegalStateException(
                    "审计 detail 无法序列化为 JSON，审计失败即业务失败（detail 键：" + safe.keySet() + "）", ex);
        }
    }

    /** 逐层脱敏，保持插入顺序（LinkedHashMap）：审计的 detail 要人能读。 */
    private static Map<String, Object> redactMap(Map<?, ?> source, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            putEntry(out, entry.getKey(), entry.getValue(), depth);
        }
        return out;
    }

    /**
     * 放一个键值对进脱敏结果：键先按**值形态**过一遍（键的文本也是内容），值再递归脱敏。
     *
     * @param containerDepth 容器自身的层级；值在 {@code containerDepth + 1} 层
     */
    private static void putEntry(Map<String, Object> out, Object rawKey, Object rawValue, int containerDepth) {
        String key = String.valueOf(rawKey);
        String storedKey = isSensitiveValue(key) ? REDACTED_KEY : key;
        out.put(uniqueKey(out, storedKey),
                isSensitiveKey(key) ? REDACTED : redactValue(rawValue, containerDepth + 1));
    }

    /**
     * 同一个 map 里两把不同的密钥当键名时，它们都会被换成 {@value #REDACTED_KEY} ——
     * 直接用 {@code put} 会让后者覆盖前者，把「两个字段」变成「一个字段」（等于丢字段，
     * 与类注释的「替换而不是丢字段」相反）。这里给重复的占位键加序号，事实一条都不少。
     */
    private static String uniqueKey(Map<String, Object> out, String key) {
        if (!out.containsKey(key)) {
            return key;
        }
        int suffix = 2;
        while (out.containsKey(key + "#" + suffix)) {
            suffix++;
        }
        return key + "#" + suffix;
    }

    /**
     * 把一个值变成「可以安全序列化」的形态：标量照抄（命中的字符串替换成 {@value #REDACTED}），
     * Map / {@link Iterable} 递归，**其它一切**（POJO、record、enum、数组…）先让 Jackson 转成
     * JSON 树再按同一套规则走 —— 这就是「非 String/Map/List 的值不会被原样写出去」的保证点。
     */
    private static Object redactValue(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return REDACTED;
        }
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return isSensitiveValue(text) ? REDACTED : text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        // ⚠️ 必须排在 Map / Iterable 之前：Jackson 的 JsonNode 自己实现了 Iterable（而且 ObjectNode
        // 的 iterator() 迭代的是**值**、会丢掉键）。让 JsonNode 落进下面任何一个分支都会静默改形状。
        if (value instanceof JsonNode tree) {
            return redactNode(tree, depth);
        }
        if (value instanceof Map<?, ?> nested) {
            return redactMap(nested, depth);
        }
        if (value instanceof Iterable<?> items) {
            List<Object> out = new ArrayList<>();
            for (Object item : items) {
                out.add(redactValue(item, depth + 1));
            }
            return out;
        }
        // 未知类型（POJO / record / enum / 各种数组 / 其它容器）：让 Jackson 把它变成 JSON 树，
        // 再走 redactNode —— 实体的字段因此不再有「原样落库」这条路。
        return redactJsonTree(value, depth);
    }

    private static Object redactJsonTree(Object value, int depth) {
        JsonNode tree;
        try {
            tree = MAPPER.valueToTree(value);
        }
        catch (IllegalArgumentException ex) {
            // Jackson 无法把它表示成 JSON（例如裸 Object）：审计写不出来 → 业务必须失败。
            // 消息里只给**类型**，不给值（见 serializeDetail 的注释）。
            throw new IllegalStateException(
                    "审计 detail 里有无法序列化为 JSON 的值（类型：" + value.getClass().getName()
                            + "），审计失败即业务失败", ex);
        }
        return redactNode(tree, depth);
    }

    /**
     * 走 Jackson 的 JSON 树：对象、数组、文本、数字、布尔全部覆盖。
     *
     * <p>无法归类为这几类的节点（binary / POJO 节点等）**整体替换**为 {@value #REDACTED}：
     * 这里的原则是「认不出来就不写出去」，而不是「认不出来就照抄」。
     */
    private static Object redactNode(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            return REDACTED;
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                putEntry(out, field.getKey(), field.getValue(), depth);
            }
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                out.add(redactNode(item, depth + 1));
            }
            return out;
        }
        if (node.isTextual()) {
            return isSensitiveValue(node.textValue()) ? REDACTED : node.textValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return REDACTED;
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
