package com.aihub.common.meter;

/**
 * 计量链路的拓扑名字：**admin 是唯一的声明方**（{@code MeteringTopologyConfig}），
 * gateway 只按这些名字发布（见计划「决策登记」第 4/5 条）。
 *
 * <p>放在 {@code aihub-common} 而不是 admin 的 {@code aihub-mq}：gateway 不得依赖 admin 的任何模块，
 * 而两侧必须用**同一份字面量**。单侧改名不会报错，只会变成「消息永远投不到队列」。
 */
public final class MeteringTopology {

    public static final String EXCHANGE = "aihub.metering.exchange";
    public static final String ROUTING_KEY = "aihub.metering.usage";
    public static final String QUEUE = "aihub.metering.queue";

    public static final String DEAD_LETTER_EXCHANGE = "aihub.metering.dlx";
    public static final String DEAD_LETTER_ROUTING_KEY = "aihub.metering.dlq";
    public static final String DEAD_LETTER_QUEUE = "aihub.metering.dlq";

    public static final String MESSAGE_CONTENT_TYPE = "text/plain;charset=UTF-8";

    private MeteringTopology() {
    }
}
