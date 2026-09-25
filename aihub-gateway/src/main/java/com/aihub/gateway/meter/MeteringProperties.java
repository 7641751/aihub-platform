package com.aihub.gateway.meter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code aihub.metering.*}。所有项都带默认值：任何一个键没配都不该让网关起不来 ——
 * 计量是派生数据，它的配置缺失绝不能影响数据面可用性。
 *
 * <p><b>{@code maxCaptureBytes} 的口径（重要）</b>：捕获的是响应字节的**尾部**。流式下这是对的
 * （{@code usage} 在最后一帧），但**非流式**响应体是一个整体 JSON：一旦超过本上限，头部会被丢掉、
 * JSON 不再可解析，{@code UsageExtractor.fromJsonBody} 返回空，于是精确 usage 被**静默降级**成
 * {@code usage_missing} + 估算值（估算不出内容时甚至记 {@code prompt_tokens = 0}）。
 * 默认 1 MiB 对现实中的非流式 body 足够，因此保留默认值；把它调小之前请先确认非流式 body 仍然完整
 * 落在窗口内（{@code UsageCaptureTest.parsesALargeNonStreamingBodyThatFitsTheDefaultCaptureWindow}
 * 就是这条底线）。
 *
 * @param enabled                  计量总开关；关掉后 {@code MeteringPublisher} 直接返回（不发、不落盘）
 * @param maxCaptureBytes          单请求捕获上限（尾部滑窗，见 {@code TailBuffer}）
 * @param queueCapacity            内存队列上界；满了就丢弃 + 计数（绝不阻塞 event loop、绝不 OOM）
 * @param spoolMaxFiles            磁盘 spool 文件数上界
 * @param replayIntervalMs         定时重投间隔
 * @param confirmTimeoutMs         等待 publisher confirm 的超时
 * @param transportRetryBackoffMs  一次投递失败后的冷却时间（冷却期内直接落盘，不再试连接）
 * @param spoolDir                 spool 目录（相对路径按进程工作目录解析）
 */
@ConfigurationProperties(prefix = "aihub.metering")
public record MeteringProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1048576") int maxCaptureBytes,
        @DefaultValue("10000") int queueCapacity,
        @DefaultValue("50000") int spoolMaxFiles,
        @DefaultValue("30000") long replayIntervalMs,
        @DefaultValue("5000") long confirmTimeoutMs,
        @DefaultValue("5000") long transportRetryBackoffMs,
        @DefaultValue("data/metering-spool") String spoolDir) {
}
