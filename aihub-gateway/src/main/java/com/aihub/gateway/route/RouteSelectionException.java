package com.aihub.gateway.route;

/**
 * 某个模型没有任何可用候选渠道。控制器把它翻成 OpenAI 形状的 404 {@code model_not_found}
 * —— **不是** 400（请求本身没问题）、**也不是** 502（不是上游的错），而是「我们不提供这个模型」。
 * 之所以不做「未知模型就回落到默认模型」：那会把客户端的拼写错误变成一次静默的错误计费。
 */
public class RouteSelectionException extends RuntimeException {

    private final transient String model;

    public RouteSelectionException(String model) {
        super("没有可用的渠道提供模型: " + model);
        this.model = model;
    }

    public String model() {
        return model;
    }
}
