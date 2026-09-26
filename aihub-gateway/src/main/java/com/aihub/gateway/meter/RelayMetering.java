package com.aihub.gateway.meter;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.auth.ApiKeyAuthFilter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个请求的计量累加器：把「上游状态 / 捕获到的用量 / 结束信号」翻译成一条 {@link MeteringEvent}。
 *
 * <p><b>两个键都在这里生成一次</b>：{@code requestId}（由调用方传入，来自 {@code RequestIdFilter}）与
 * {@code createdAt}（截断到毫秒）。它们就是 {@code request_log} 幂等键的两半，因此**绝不重新生成**
 * —— 重投时必须是同一个值（计划「决策登记」第 1/2 条）。
 *
 * <p>{@code tenantId} 取自 {@code ApiKeyAuthFilter} 写进 exchange 的 {@link ApiKeyView}；鉴权关闭时
 * 用 {@link #TENANT_UNKNOWN} 哨兵（列 NOT NULL，丢数据比记哨兵更糟）。
 *
 * <p>{@code channelId} 由控制器在每次尝试前通过 {@link #onChannelSelected(ChannelDescriptor)} 更新，
 * 因此事件里记的是**实际服务**（或最后尝试）的那条渠道 —— 故障转移之后它不再是首选那条。
 */
public final class RelayMetering {

    /** 拿不到 ApiKeyView（鉴权关闭）时的 tenant 哨兵：V1 没有外键，0 是安全值。 */
    public static final long TENANT_UNKNOWN = 0L;

    /** 「请求失败」类的错误码：它们的上游 HTTP 状态可能是 200（头已发出），因此不能只看状态码。 */
    private static final Set<String> FAILURE_CODES = Set.of(
            MeteringEvent.ERROR_UPSTREAM_UNREACHABLE,
            MeteringEvent.ERROR_UPSTREAM_STREAM,
            MeteringEvent.ERROR_GATEWAY);

    private final String requestId;
    private final Instant createdAt;
    private final long tenantId;
    private final Long apiKeyId;
    /**
     * 实际被服务（或被最后尝试）的渠道 id。**可变**：一次请求可能在故障转移里走过好几条候选，
     * 事件里要的是**最后那条**（它产生了客户端拿到的响应），而不是最先被选中的那条。
     */
    private final AtomicReference<Long> channelId = new AtomicReference<>();
    private final String model;
    private final UsageCapture capture;
    private final AtomicReference<HttpStatusCode> upstreamStatus = new AtomicReference<>();
    private final AtomicReference<String> errorCode = new AtomicReference<>();
    private volatile boolean clientDisconnected;

    public static RelayMetering start(ServerWebExchange exchange, String requestId, String model,
                                      boolean streaming, int maxCaptureBytes) {
        ApiKeyView view = (ApiKeyView) exchange.getAttributes().get(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW);
        return new RelayMetering(
                requestId,
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                view == null ? TENANT_UNKNOWN : view.tenantId(),
                null,   // apiKeyId：计量侧的落库是 Task 11 的遗留项之一（决策 14 的契约在 Task 2 已就位）
                model,
                UsageCapture.start(streaming, maxCaptureBytes));
    }

    RelayMetering(String requestId, Instant createdAt, long tenantId, Long apiKeyId,
                  String model, UsageCapture capture) {
        this.requestId = requestId;
        this.createdAt = createdAt;
        this.tenantId = tenantId;
        this.apiKeyId = apiKeyId;
        this.model = model;
        this.capture = capture;
    }

    /**
     * 记录本次请求**最后尝试**的那条候选渠道（多渠道/故障转移，M3）。
     *
     * <p>控制器在每次尝试之前调用它，因此事件里的 {@code channel_id} 是「最后被调用、并产生了
     * 客户端看到的那个结果」的渠道：正常路径下它等于服务的那条；故障转移后它等于**备用**那条
     * （不是首选那条）；所有候选都在响应未提交时失败时它等于最后一次尝试的那条，故障因此可归因。
     *
     * <p>遗留单渠道的哨兵 id（{@code LegacyChannel.ID}）也照样写进事件（不是 NULL）：这样
     * 「走了兜底路径」在 {@code request_log} 里是可查的，而不是与「多渠道正常路径」混在一起。
     */
    public void onChannelSelected(ChannelDescriptor channel) {
        if (channel != null) {
            channelId.set(channel.id());
        }
    }

    /** 只读观察上游响应体（不消费、不改动字节）。 */
    public void onChunk(DataBuffer buffer) {
        capture.onChunk(buffer);
    }

    public void onUpstreamStatus(HttpStatusCode status) {
        upstreamStatus.compareAndSet(null, status);
    }

    /** 投递失败 / 上游中途断流：由控制器在对应分支里标注。 */
    public void onUpstreamFailure(String code) {
        errorCode.compareAndSet(null, code);
    }

    /**
     * 客户端断连。响应已提交之后的写失败**无法**区分「客户端走了」与「上游断了」，
     * 而后者有更具体的信号（{@code WebClientRequestException}，由控制器单独标注），
     * 因此走到这里的一律按客户端断连计 —— 设计文档 §8.1 ⑤ 要求它**不计入错误告警**。
     *
     * <p>M3 起控制器在**两处**调用它（同一事实的两种收尾形状）：顶层「已提交 + 写失败」，
     * 以及转发时上游 body 的订阅被取消（Netty 在没有在飞的写时会以 {@code onComplete} 收尾，
     * 顶层信号因此是「正常完成」—— 见 {@code ChatRelayController#relay}）。
     */
    public void onClientDisconnected() {
        clientDisconnected = true;
    }

    /** 网关自身未预期异常（响应由框架收尾）。 */
    public void onUnexpectedError() {
        errorCode.compareAndSet(null, MeteringEvent.ERROR_GATEWAY);
    }

    public String requestId() {
        return requestId;
    }

    /**
     * 组装事件。{@code signal} 来自 {@code doFinally}：{@code CANCEL} = 订阅被取消（客户端断连的
     * 另一种表现）。因此「客户端断连」有两个来源（{@code CANCEL} 与
     * {@link #onClientDisconnected()}），两条都归到 {@code CANCELLED}。
     */
    public MeteringEvent toEvent(SignalType signal) {
        UsageCapture.Captured captured = capture.capture();
        int promptTokens = captured.promptTokens();
        int completionTokens = captured.completionTokens();
        int totalTokens = captured.totalTokens();
        HttpStatusCode status = upstreamStatus.get();
        String code = errorCode.get();
        boolean cancelled = signal == SignalType.CANCEL || clientDisconnected;

        String resolvedStatus;
        if (cancelled) {
            resolvedStatus = MeteringEvent.STATUS_CANCELLED;
            if (code == null) {
                code = MeteringEvent.ERROR_CLIENT_DISCONNECTED;
            }
        } else if (code != null && FAILURE_CODES.contains(code)) {
            // 「中途断流」这类失败的上游 HTTP 状态可能仍是 200（头早就发出去了），
            // 因此不能只看状态码：错误码优先级更高。
            resolvedStatus = MeteringEvent.STATUS_ERROR;
        } else if (status == null || !status.is2xxSuccessful()) {
            resolvedStatus = MeteringEvent.STATUS_ERROR;
            if (code == null) {
                code = status == null
                        ? MeteringEvent.ERROR_GATEWAY
                        : MeteringEvent.upstreamHttpError(status.value());
            }
        } else {
            resolvedStatus = MeteringEvent.STATUS_SUCCESS;
            if (code == null && captured.source() != UsageCapture.Source.EXACT) {
                code = MeteringEvent.ERROR_USAGE_MISSING;
            }
        }

        if (status != null && !status.is2xxSuccessful()) {
            // 上游错误响应里没有 usage；用错误文本估算 token 是错的数据，宁可记 0。
            promptTokens = 0;
            completionTokens = 0;
            totalTokens = 0;
        }

        return new MeteringEvent(requestId, tenantId, apiKeyId, channelId.get(), model,
                promptTokens, completionTokens, totalTokens,
                capture.latencyMs(), captured.ttftMs(), resolvedStatus, code,
                createdAt.toEpochMilli());
    }
}
