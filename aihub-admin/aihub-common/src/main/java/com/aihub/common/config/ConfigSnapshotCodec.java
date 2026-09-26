package com.aihub.common.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 快照的分隔符编解码。**只服务 gateway 自己的两级缓存**（Caffeine 值 + Redis value），
 * 不是跨服务契约 —— admin 发的是 JSON（网关用 Jackson 解），因此这里不需要
 * 「两侧共用同一份字面量」的强约束，只需要「严格互逆 + 畸形不炸」。
 *
 * <p>格式：
 * <pre>
 * #v1|{version}|{defaultModel}|{generatedAtEpochMilli}
 * C|{id}|{name}|{baseUrl}|{apiKeyCipher}|{keyVersion}|{timeoutMs}|{status}|{weight}|{priority}
 * R|{modelName}|{channelId}|{weight}|{priority}|{status}
 * L|{tenantId}|{apiKeyId}|{qps}|{burst}
 * </pre>
 * 字段内转义 {@code \} {@code |} {@code \n} {@code \r}（与 {@code MeteringEventCodec} /
 * {@code ApiKeyCacheCodec} 同一套转义器语义）。**未知段字母被跳过**：将来加新段时，老网关
 * 读到新载荷不会整体判死，而是丢掉不认识的那一段。
 */
public final class ConfigSnapshotCodec {

    /** 载荷格式版本。首行以 {@code #v{FORMAT_VERSION}} 开头，不认识就判为载荷畸形。 */
    public static final int FORMAT_VERSION = 1;

    private static final String HEADER_PREFIX = "#v";
    private static final String DELIMITER = "|";
    private static final String SECTION_CHANNEL = "C";
    private static final String SECTION_ROUTE = "R";
    private static final String SECTION_POLICY = "L";
    private static final int HEADER_FIELDS = 4;
    private static final int CHANNEL_FIELDS = 10;
    private static final int ROUTE_FIELDS = 6;
    /** 段字母本身也算一个字段：{@code L|7||20|40} 切开是 5 个 token（与 {@code C}=10 / {@code R}=6 同一口径）。 */
    private static final int POLICY_FIELDS = 5;

    private ConfigSnapshotCodec() {
    }

    public static String encode(ConfigSnapshot snapshot) {
        StringBuilder out = new StringBuilder(512);
        out.append(HEADER_PREFIX).append(FORMAT_VERSION).append(DELIMITER)
                .append(snapshot.version()).append(DELIMITER)
                .append(escape(snapshot.defaultModel())).append(DELIMITER)
                .append(snapshot.generatedAtEpochMilli());
        for (ChannelDescriptor channel : snapshot.channels()) {
            out.append('\n').append(SECTION_CHANNEL).append(DELIMITER)
                    .append(channel.id()).append(DELIMITER)
                    .append(escape(channel.name())).append(DELIMITER)
                    .append(escape(channel.baseUrl())).append(DELIMITER)
                    .append(escape(channel.apiKeyCipher())).append(DELIMITER)
                    .append(channel.keyVersion()).append(DELIMITER)
                    .append(channel.timeoutMs()).append(DELIMITER)
                    .append(escape(channel.status())).append(DELIMITER)
                    .append(channel.weight()).append(DELIMITER)
                    .append(channel.priority());
        }
        for (ModelRouteDescriptor route : snapshot.routes()) {
            out.append('\n').append(SECTION_ROUTE).append(DELIMITER)
                    .append(escape(route.modelName())).append(DELIMITER)
                    .append(route.channelId()).append(DELIMITER)
                    .append(route.weight()).append(DELIMITER)
                    .append(route.priority()).append(DELIMITER)
                    .append(escape(route.status()));
        }
        for (RatePolicy policy : snapshot.ratePolicies()) {
            out.append('\n').append(SECTION_POLICY).append(DELIMITER)
                    .append(policy.tenantId() == null ? "" : policy.tenantId()).append(DELIMITER)
                    .append(policy.apiKeyId() == null ? "" : policy.apiKeyId()).append(DELIMITER)
                    .append(policy.qps()).append(DELIMITER)
                    .append(policy.burst());
        }
        return out.toString();
    }

    /** 载荷畸形（首行不符、字段个数不对、数字解析失败）时返回 {@code null}：调用方视作缓存未命中。 */
    public static ConfigSnapshot decode(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        String[] lines = payload.split("\n", -1);
        List<String> header = splitFields(lines[0]);
        if (header.size() != HEADER_FIELDS || !(HEADER_PREFIX + FORMAT_VERSION).equals(header.get(0))) {
            return null;
        }
        List<ChannelDescriptor> channels = new ArrayList<>();
        List<ModelRouteDescriptor> routes = new ArrayList<>();
        List<RatePolicy> policies = new ArrayList<>();
        try {
            long version = Long.parseLong(header.get(1));
            String defaultModel = emptyToNull(header.get(2));
            long generatedAt = Long.parseLong(header.get(3));
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                List<String> fields = splitFields(lines[i]);
                switch (fields.get(0)) {
                    case SECTION_CHANNEL -> {
                        if (fields.size() != CHANNEL_FIELDS) {
                            return null;
                        }
                        channels.add(new ChannelDescriptor(
                                Long.parseLong(fields.get(1)), emptyToNull(fields.get(2)), emptyToNull(fields.get(3)),
                                emptyToNull(fields.get(4)), Integer.parseInt(fields.get(5)),
                                Integer.parseInt(fields.get(6)), emptyToNull(fields.get(7)),
                                Integer.parseInt(fields.get(8)), Integer.parseInt(fields.get(9))));
                    }
                    case SECTION_ROUTE -> {
                        if (fields.size() != ROUTE_FIELDS) {
                            return null;
                        }
                        routes.add(new ModelRouteDescriptor(
                                emptyToNull(fields.get(1)), Long.parseLong(fields.get(2)),
                                Integer.parseInt(fields.get(3)), Integer.parseInt(fields.get(4)),
                                emptyToNull(fields.get(5))));
                    }
                    case SECTION_POLICY -> {
                        if (fields.size() != POLICY_FIELDS) {
                            return null;
                        }
                        policies.add(new RatePolicy(
                                optionalLong(fields.get(1)), optionalLong(fields.get(2)),
                                Integer.parseInt(fields.get(3)), Integer.parseInt(fields.get(4))));
                    }
                    // 不认识的段字母：跳过（前向兼容），不让整份载荷判死。
                    default -> {
                    }
                }
            }
            return new ConfigSnapshot(version, generatedAt, channels, routes, policies, defaultModel);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long optionalLong(String value) {
        return value.isEmpty() ? null : Long.valueOf(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '|' -> out.append("\\|");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** 单趟扫描：{@code \\} {@code \|} {@code \n} {@code \r} 还原成一个字符，光秃秃的 {@code |} 才是边界。 */
    private static List<String> splitFields(String line) {
        List<String> parts = new ArrayList<>(CHANNEL_FIELDS);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                switch (next) {
                    case '\\' -> {
                        current.append('\\');
                        i++;
                        continue;
                    }
                    case '|' -> {
                        current.append('|');
                        i++;
                        continue;
                    }
                    case 'n' -> {
                        current.append('\n');
                        i++;
                        continue;
                    }
                    case 'r' -> {
                        current.append('\r');
                        i++;
                        continue;
                    }
                    default -> {
                        // 孤立的转义符按字面量保留。
                    }
                }
            }
            if (c == '|') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }
}
