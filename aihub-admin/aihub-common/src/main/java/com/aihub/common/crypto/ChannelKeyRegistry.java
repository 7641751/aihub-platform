package com.aihub.common.crypto;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
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
 */
public record ChannelKeyRegistry(Map<Integer, byte[]> keys) {

    /** AES-256 要求 32 字节密钥。 */
    private static final int KEY_LENGTH_BYTES = 32;

    /** 一个版本段：{@code v<数字>:<base64>}；大小写不敏感（运维手写环境变量时不该被大小写坑到）。 */
    private static final Pattern SEGMENT = Pattern.compile("^[vV](\\d{1,9}):(.+)$");

    /**
     * 版本数上界。它不是安全边界，而是**配置错误的上界**：环境变量里塞了几十个版本通常意味着
     * 有人把「历史密钥」当成了备份手段。超出的段被跳过。
     */
    private static final int MAX_VERSIONS = 8;

    public ChannelKeyRegistry {
        keys = Map.copyOf(keys);
    }

    /** 解析环境变量。任何一段解析失败都只是被跳过，**本方法永不抛异常**。 */
    public static ChannelKeyRegistry parse(String envValue) {
        Map<Integer, byte[]> parsed = new TreeMap<>();
        if (envValue == null || envValue.isBlank()) {
            return new ChannelKeyRegistry(Map.of());
        }
        for (String rawSegment : envValue.split(",")) {
            if (parsed.size() >= MAX_VERSIONS) {
                break;
            }
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
        return new ChannelKeyRegistry(new LinkedHashMap<>(parsed));
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

    public Optional<byte[]> key(int version) {
        return Optional.ofNullable(keys.get(version));
    }

    /** 供日志使用：只出现版本号，**绝不出现密钥内容**。 */
    public String describe() {
        return keys.isEmpty() ? "无主密钥" : "已加载主密钥版本 " + keys.keySet();
    }

    @Override
    public String toString() {
        // 覆写 record 默认 toString：它会把 byte[] 的 hashCode 打出来，虽不泄漏内容，
        // 但会让「主密钥表」有办法出现在日志/异常消息里 —— 从源头掐掉。
        return "ChannelKeyRegistry[" + describe() + "]";
    }
}
