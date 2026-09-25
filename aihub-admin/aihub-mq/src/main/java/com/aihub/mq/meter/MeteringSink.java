package com.aihub.mq.meter;

import com.aihub.common.meter.MeteringEvent;

/**
 * 计量事件落库的接缝。
 *
 * <p>刻意定义在 {@code aihub-mq}（消费侧）而由 {@code aihub-service} 实现：依赖方向是
 * {@code web → service → mq}，mq 不能反向依赖 service，因此消费者只依赖这个接口。
 */
public interface MeteringSink {

    /**
     * 落库。
     *
     * @return true = 新写入一行；false = **幂等重复**（`(request_id, created_at)` 已存在），不是错误
     */
    boolean persist(MeteringEvent event);
}
