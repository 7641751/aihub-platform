package com.aihub.common.crypto;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 渠道密钥的主密钥表，从**环境变量**读入，格式为 {@code v1:<base64 32 字节>,v2:<base64 32 字节>}。
 *
 * <p><b>为什么可以放在 {@code aihub-common}</b>：它只用 JDK 类型（{@link Base64} / {@link Map} /
 * {@link Pattern}），因此「main 作用域零依赖」仍然成立 —— 零依赖指的是**零第三方依赖**，而不是
 * 「只能用 {@code java.lang}」（{@code InternalHmac} 早就在用 {@code javax.crypto}）。
 * 放这里的唯一理由是 admin（加密）与 gateway（解密）**必须共用同一份**解析规则：各写一份等于把
 * 主密钥格式撕成两半，单侧改动不会编译报错，只会表现为「网关解不开密钥」。
 *
 * <p><b>双版本共存</b>：轮换时环境变量里同时放旧、新两把密钥 → 网关既能解开旧密文（存量行）、
 * 又能解开新密文（重加密后的行）；全部重加密完成后把旧密钥去掉。
 * 因此 {@link #parse} 对无法解析的段采取**跳过**而不是抛异常，{@link #currentVersion()} 取
 * 「解析成功的最大版本号」—— 环境变量里出现垃圾时最坏的后果是「那个版本解不开」，而不是「网关起不来」。
 *
 * <p><b>不变量（评审 Fix 3）</b>：版本号必须为正、密钥必须**恰好 32 字节**（AES-256）、版本数不超过
 * {@link #MAX_VERSIONS}。这三条由**紧凑构造器**强制，而不只是由 {@link #parse} 过滤 —— 只过滤入参
 * 意味着 {@code new ChannelKeyRegistry(Map.of(1, new byte[16]))} 仍然能构造出表：16 字节会被 JDK
 * 当作 AES-128 **静默接受**（不报错，但密文与 AES-256 的存量数据不是一回事），而 31 字节要到
 * {@code encrypt} 时才抛出与「密文被篡改」无法区分的 {@code "AES-GCM 运算失败"}。
 *
 * <p><b>密钥数组的归属（评审 Fix 5）</b>：这类不可变值对象必须在**边界上复制**字节数组 ——
 * 构造器复制入参，{@link #key(int)} 与 {@link #keys()} 交出副本。否则任何拿到表的人都持有一份
 * 可以就地清零的主密钥。
 *
 * <p><b>equals/hashCode 的语义</b>：record 自动生成的实现用 {@code Objects.equals} 比较
 * {@code byte[]}，即**按引用比较** —— 两个内容相同的主密钥表并不相等。这是刻意保留的：本类型是
 * 进程内的主密钥容器，不需要值语义，而按引用比较至少是「便宜且不假装正确」。要比版本集合请用
 * {@link #describe()} 或 {@code keys().keySet()}；**不要**为此补一个逐字节比较的 equals（brief 没有这个契约）。
 */
public record ChannelKeyRegistry(Map<Integer, byte[]> keys) {

    /** AES-256 要求 32 字节密钥。 */
    private static final int KEY_LENGTH_BYTES = 32;

    /** 一个版本段：{@code v<数字>:<base64>}；大小写不敏感（运维手写环境变量时不该被大小写坑到）。 */
    private static final Pattern SEGMENT = Pattern.compile("^[vV](\\d{1,9}):(.+)$");

    /**
     * 版本数上界。它不是安全边界，而是**配置错误的上界**：环境变量里塞了几十个版本通常意味着
     * 有人把「历史密钥」当成了备份手段。超过上界的配置**整表拒绝**（见 {@link #parse}），
     * 而不是静默截断。
     */
    private static final int MAX_VERSIONS = 8;

    /**
     * 校验并复制入参，使非法表**根本无法被构造**（评审 Fix 3 / Fix 5）。
     *
     * @throws IllegalArgumentException 版本号非正、密钥不是 32 字节（含 null）、版本数超过 {@link #MAX_VERSIONS}
     */
    public ChannelKeyRegistry {
        Objects.requireNonNull(keys, "主密钥表不得为 null");
        Map<Integer, byte[]> validated = new LinkedHashMap<>();
        for (Map.Entry<Integer, byte[]> entry : keys.entrySet()) {
            Integer version = entry.getKey();
            byte[] key = entry.getValue();
            if (version == null || version <= 0) {
                throw new IllegalArgumentException("主密钥版本号必须是正整数，实际 " + version);
            }
            if (key == null || key.length != KEY_LENGTH_BYTES) {
                throw new IllegalArgumentException("主密钥 v" + version + " 必须是 " + KEY_LENGTH_BYTES
                        + " 字节（AES-256），实际 " + (key == null ? "null" : key.length + " 字节"));
            }
            // 复制入参数组：不可变性不能建立在「调用方不再改这个数组」的信任上。
            // 消息里只有版本号与长度，绝不包含密钥内容。
            validated.put(version, key.clone());
        }
        if (validated.size() > MAX_VERSIONS) {
            throw new IllegalArgumentException(
                    "主密钥版本数不得超过 " + MAX_VERSIONS + "，实际 " + validated.size());
        }
        keys = Map.copyOf(validated);
    }

    /**
     * 解析环境变量。任何一段解析失败都只是被跳过，**本方法永不抛异常**。
     *
     * <p><b>超过 {@link #MAX_VERSIONS} 个有效版本时整表拒绝（评审 Fix 6）</b>：返回空表而不是截断。
     * 旧行为是遇到第 9 段就 {@code break}，实测 9 个版本时 {@code size=8, currentVersion()=8,
     * has(9)=false} —— {@code encrypt} 会悄悄改用较小的版本号加密，运维多写一段环境变量就选错密钥，
     * 唯一的信号是 {@code describe()}。返回空表等于「未配置主密钥」：admin 侧 {@code encrypt} 立刻抛
     * {@link IllegalStateException}，日志里 {@code describe()} 是「无主密钥」—— 响亮，而不是沉默。
     */
    public static ChannelKeyRegistry parse(String envValue) {
        if (envValue == null || envValue.isBlank()) {
            return new ChannelKeyRegistry(Map.of());
        }
        Map<Integer, byte[]> parsed = new TreeMap<>();
        for (String rawSegment : envValue.split(",")) {
            String segment = rawSegment.strip();
            if (segment.isEmpty()) {
                continue;
            }
            Matcher matcher = SEGMENT.matcher(segment);
            if (!matcher.matches()) {
                continue;
            }
            int version;
            byte[] key;
            try {
                version = Integer.parseInt(matcher.group(1));
                key = Base64.getDecoder().decode(matcher.group(2).strip());
            } catch (RuntimeException e) {
                continue;
            }
            if (version <= 0 || key.length != KEY_LENGTH_BYTES) {
                continue;
            }
            parsed.put(version, key);
        }
        if (parsed.size() > MAX_VERSIONS) {
            return new ChannelKeyRegistry(Map.of());
        }
        return new ChannelKeyRegistry(parsed);
    }

    /** 当前（最新）版本号；表为空时返回 {@code 0}，调用方据此判断「不可加密」。 */
    public int currentVersion() {
        return keys.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public boolean has(int version) {
        return keys.containsKey(version);
    }

    /** 指定版本密钥的**副本**（评审 Fix 5）：调用方拿到的数组与表内密钥不共享存储。 */
    public Optional<byte[]> key(int version) {
        return Optional.ofNullable(keys.get(version)).map(byte[]::clone);
    }

    /**
     * 主密钥表的**副本**，连 {@code byte[]} 一起复制（评审 Fix 5）：直接交出内部数组等于把
     * 「就地清零主密钥」的能力送给任何调用方。
     */
    @Override
    public Map<Integer, byte[]> keys() {
        Map<Integer, byte[]> copies = new LinkedHashMap<>();
        for (Map.Entry<Integer, byte[]> entry : keys.entrySet()) {
            copies.put(entry.getKey(), entry.getValue().clone());
        }
        return Map.copyOf(copies);
    }

    /**
     * 供日志使用：只出现版本号，**绝不出现密钥内容**。版本号按升序排列（评审 Fix 4）——
     * {@code Map.copyOf} 丢弃迭代顺序，而且 {@code ImmutableCollections} 的遍历顺序带**每次 JVM 启动
     * 随机化的 SALT**：同一份输入 {@code parse("v7:…,v1:…,v4:…")} 实测出现过 {@code [4, 1, 7]}、
     * {@code [4, 7, 1]}、{@code [1, 7, 4]} 三种顺序，不排序的话运维在轮换日志里根本认不出「同一份配置」。
     */
    public String describe() {
        return keys.isEmpty()
                ? "无主密钥"
                : "已加载主密钥版本 " + keys.keySet().stream().sorted().toList();
    }

    @Override
    public String toString() {
        // 覆写 record 默认 toString：它会把 byte[] 的 hashCode 打出来，虽不泄漏内容，
        // 但会让「主密钥表」有办法出现在日志/异常消息里 —— 从源头掐掉。
        return "ChannelKeyRegistry[" + describe() + "]";
    }
}
