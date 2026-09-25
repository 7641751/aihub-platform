package com.aihub.gateway.meter;

import com.aihub.common.meter.MeteringEvent;
import com.aihub.common.meter.MeteringEventCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制器唯一依赖的发布门面：编码 + 入队（**非阻塞**）。
 *
 * <p>它被调用的位置是响应收尾（{@code doFinally}，即 Netty event loop），因此这里绝不允许出现
 * 任何网络或磁盘 I/O —— 真正的投递在 {@link MeteringDispatcher} 的守护线程上。
 *
 * <p>计量是派生数据（设计决策 C）：任何异常都只记日志，绝不影响用户响应。
 */
public class MeteringPublisher {

    private static final Logger log = LoggerFactory.getLogger(MeteringPublisher.class);

    private final MeteringProperties properties;
    private final MeteringDispatcher dispatcher;

    public MeteringPublisher(MeteringProperties properties, MeteringDispatcher dispatcher) {
        this.properties = properties;
        this.dispatcher = dispatcher;
    }

    public void publish(MeteringEvent event) {
        if (!properties.enabled()) {
            return;
        }
        try {
            dispatcher.enqueue(MeteringEventCodec.encode(event));
        } catch (RuntimeException e) {
            log.error("计量事件入队失败（请求 {}）: {}", event.requestId(), e.toString());
        }
    }
}
