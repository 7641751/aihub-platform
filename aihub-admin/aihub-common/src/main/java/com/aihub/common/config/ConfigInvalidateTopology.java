package com.aihub.common.config;

/**
 * 配置失效链路的 Pub/Sub 频道名：**admin（发布方）与 gateway（订阅方）共用同一份字面量**（决策 D4）。
 *
 * <p>放在 {@code aihub-common} 而不是 admin 的 {@code aihub-service}：gateway 不得依赖 admin 的任何模块，
 * 而两侧必须用同一个字符串。单侧改名不会编译报错，只会变成「消息永远投不到任何人」——
 * 那正好是 M3 记录的那个故障形状（改了配置，数据面最长 10 分钟不收敛）。
 *
 * <p>与 {@code MeteringTopology} 同一纪律：拓扑名字只有一处声明，由 {@code ConfigInvalidateCodecTest}
 * 用例钉住字面量。频道**名**是这里的一部分；载荷格式在 {@link ConfigInvalidateCodec}。
 */
public final class ConfigInvalidateTopology {

    public static final String CHANNEL = "aihub:config:invalidate";

    private ConfigInvalidateTopology() {
    }
}
