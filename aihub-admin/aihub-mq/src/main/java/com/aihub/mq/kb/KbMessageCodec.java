package com.aihub.mq.kb;

/**
 * kb 消息的线格式：**文本分隔符、无 JSON**（与 {@code MeteringEventCodec} 同风格）。
 *
 * <p><b>为什么不是 JSON</b>：{@code aihub-mq} 的依赖面只有 {@code aihub-common} +
 * {@code spring-boot-starter-amqp}（**没有 Jackson**，实测 pom）——引 Jackson 只为编解码是净增依赖。
 * 文本分隔符是**唯一与现有依赖面一致**的选择（D11）。
 *
 * <p>两种载荷：
 * <ul>
 *   <li>{@code parse:{docId}}</li>
 *   <li>{@code embed:{docId}:{seqFrom}:{seqTo}}</li>
 * </ul>
 *
 * <p><b>畸形 / 错路由的载荷必须抛 {@link IllegalArgumentException}</b>（不是返回 {@code null}）：
 * 静默 ACK 一条解不开的消息等于**永久丢数据**。抛出后交给容器的重试策略，仍失败则 reject → 进 DLQ
 * （照 {@code MeteringConsumer} 的纪律）。
 */
public final class KbMessageCodec {

    private static final String PARSE_PREFIX = "parse:";
    private static final String EMBED_PREFIX = "embed:";
    private static final String DELIMITER = ":";

    private KbMessageCodec() {
    }

    /** 编码一条解析消息：{@code parse:{docId}}。 */
    public static String parse(long docId) {
        return PARSE_PREFIX + docId;
    }

    /** 编码一条嵌入消息：{@code embed:{docId}:{seqFrom}:{seqTo}}。 */
    public static String embed(long docId, int seqFrom, int seqTo) {
        return EMBED_PREFIX + docId + DELIMITER + seqFrom + DELIMITER + seqTo;
    }

    /**
     * 解一条解析载荷，返回 {@code docId}。
     *
     * @throws IllegalArgumentException 载荷为 {@code null}、不是 parse 前缀、字段数不对、或数字解析失败
     */
    public static long decodeParse(String payload) {
        if (payload == null || !payload.startsWith(PARSE_PREFIX)) {
            throw new IllegalArgumentException("不是 parse 载荷：" + describe(payload));
        }
        String rest = payload.substring(PARSE_PREFIX.length());
        if (rest.isEmpty() || rest.indexOf(':') >= 0) {
            throw new IllegalArgumentException("畸形 parse 载荷：" + describe(payload));
        }
        return parseLong(rest, payload);
    }

    /**
     * 解一条嵌入载荷。
     *
     * @throws IllegalArgumentException 载荷为 {@code null}、不是 embed 前缀、字段数不是 3、
     *                                  数字解析失败、或批次区间非法（{@code seqFrom < 0} 或 {@code seqTo < seqFrom}）
     */
    public static KbEmbedBatch decodeEmbed(String payload) {
        if (payload == null || !payload.startsWith(EMBED_PREFIX)) {
            throw new IllegalArgumentException("不是 embed 载荷：" + describe(payload));
        }
        String[] parts = payload.substring(EMBED_PREFIX.length()).split(DELIMITER, -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("畸形 embed 载荷：" + describe(payload));
        }
        long docId = parseLong(parts[0], payload);
        int seqFrom = parseInt(parts[1], payload);
        int seqTo = parseInt(parts[2], payload);
        if (seqFrom < 0 || seqTo < seqFrom) {
            throw new IllegalArgumentException(
                    "非法的批次区间 [" + seqFrom + "," + seqTo + "]：" + describe(payload));
        }
        return new KbEmbedBatch(docId, seqFrom, seqTo);
    }

    private static long parseLong(String value, String payload) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("字段不是整数：" + describe(payload));
        }
    }

    private static int parseInt(String value, String payload) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("字段不是整数：" + describe(payload));
        }
    }

    /**
     * 给日志用的短描述：载荷来自 broker，**长度与内容都不受本进程控制**，所以截断后再拼进异常消息，
     * 免得一条超大/带换行的消息在 3 次重试里把日志冲爆、或伪造日志行。
     */
    private static String describe(String payload) {
        if (payload == null) {
            return "<null>";
        }
        return payload.length() > 64 ? payload.substring(0, 64) + "…(" + payload.length() + " 字符)" : payload;
    }
}
