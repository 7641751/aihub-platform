package com.aihub.gateway.meter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 磁盘 spool 的定时补偿重投（设计文档 §9：RabbitMQ 不可用时「落本地磁盘队列 + 定时补偿重投」）。
 * 运行在 Spring 的调度线程上（不是 event loop），因此这里允许阻塞 I/O。
 */
@Component
public class MeteringSpoolReplayer {

    private static final Logger log = LoggerFactory.getLogger(MeteringSpoolReplayer.class);

    private final MeteringDispatcher dispatcher;

    public MeteringSpoolReplayer(MeteringDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${aihub.metering.replay-interval-ms:30000}")
    public void replay() {
        try {
            int replayed = dispatcher.replayOnce();
            if (replayed > 0) {
                log.info("已重投 {} 条计量事件", replayed);
            }
        } catch (RuntimeException e) {
            // 定时任务抛出会终止后续调度，必须吞掉；下一轮继续试。
            log.error("计量 spool 重投失败（下一轮重试）: {}", e.toString());
        }
    }
}
