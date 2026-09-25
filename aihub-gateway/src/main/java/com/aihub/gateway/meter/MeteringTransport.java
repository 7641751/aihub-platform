package com.aihub.gateway.meter;

/**
 * 计量载荷的投递接缝。生产实现是 {@link RabbitMeteringTransport}，测试用假实现
 * （见 {@code RecordingMeteringTransport}）—— 网关测试不允许依赖 Docker。
 *
 * <p><b>契约</b>：{@link #send} 是**阻塞**的（调用方在专用线程上），成功返回 true；
 * 连接失败 / broker nack / confirm 超时一律返回 false，**不抛异常**（调用方据此落盘）。
 */
public interface MeteringTransport {

    boolean send(String payload);
}
