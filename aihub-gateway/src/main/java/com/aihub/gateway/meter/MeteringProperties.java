package com.aihub.gateway.meter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code aihub.metering.*}。所有项都带默认值：任何一个键没配都不该让网关起不来 ——
 * 计量是派生数据，它的配置缺失绝不能影响数据面可用性。
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
