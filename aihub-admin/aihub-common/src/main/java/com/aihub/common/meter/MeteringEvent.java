package com.aihub.common.meter;

import java.time.Instant;

/**
 * 计量事件：gateway 组装、经 RabbitMQ 投递给 admin，最终落 {@code request_log} 一行。
 *
 * <p><b>幂等键是 {@code (requestId, createdAtEpochMilli)}</b>，与 V1 的
 * {@code uk_request_log_request_id (request_id, created_at)} 一一对应。两个字段都由 gateway
 * 在**请求开始时各生成一次**、随事件投递；重投 / 重放 / 重复消费必须携带**同一个值**。
 * 消费端如果用自己的 {@code now()} 生成 {@code created_at}，同一事件就会写成两行
 * （实测：同一 request_id、created_at 差 333ms → 2 行）。
 *
 * <p>{@code apiKeyId} 在 M2 恒为 {@code null}（见计划「决策登记」第 8 条：当时共享类型里没有数值主键）；
 * M3（Task 11）起 gateway 填**鉴权视图里的真实值**，鉴权关闭或视图没有数值主键时仍为 {@code null}
 * —— 该列可空，没有「哨兵」语义（与 {@code tenantId} 的 {@code 0} 不同）。{@code channelId} 同理：
 * M2 为 {@code null}，M3 起填**实际服务**（故障转移后是备用）的那条渠道 id。
 * 两者都只是**值**的变化：字段数、顺序与转义规则（即跨服务契约面）没有改动，
 * {@code MeteringEventCodec} 的固定向量与畸形载荷用例因此一行未改。
 *
 * <p>{@code ttftMs} 在非流式请求里为 {@code null}；{@code model} / {@code errorCode} 可空。
 * {@code model} 不保证与客户端原样一致：超长时 gateway 在**事件组装处**截断到
 * {@code request_log.model} 的列宽（{@code VARCHAR(128)}），而转发给上游的请求体不受影响。
 */
public record MeteringEvent(
        String requestId,
        long tenantId,
        Long apiKeyId,
        Long channelId,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        int latencyMs,
        Integer ttftMs,
        String status,
        String errorCode,
        long createdAtEpochMilli) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 根本没连上上游（网关回 502）。 */
    public static final String ERROR_UPSTREAM_UNREACHABLE = "upstream_unreachable";
    /** 上游流中途断开（响应已提交，客户端拿到半截流）。 */
    public static final String ERROR_UPSTREAM_STREAM = "upstream_stream_error";
    /** 2xx 但响应里找不到 usage（上游没回，或捕获窗口不可用）：token 是**估算**值。 */
    public static final String ERROR_USAGE_MISSING = "usage_missing";
    /** 客户端断连：token 按已收内容估算（设计文档 §8.1 ⑤）。 */
    public static final String ERROR_CLIENT_DISCONNECTED = "client_disconnected";
    /** 网关自身未预期异常（响应由框架处理）。 */
    public static final String ERROR_GATEWAY = "gateway_error";

    /** 上游非 2xx：形如 {@code upstream_http_429}。 */
    public static String upstreamHttpError(int status) {
        return "upstream_http_" + status;
    }

    public Instant createdAt() {
        return Instant.ofEpochMilli(createdAtEpochMilli);
    }
}
