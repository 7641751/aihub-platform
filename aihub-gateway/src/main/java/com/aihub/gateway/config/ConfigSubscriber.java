package com.aihub.gateway.config;

import com.aihub.common.config.ConfigInvalidateCodec;
import com.aihub.common.config.ConfigInvalidateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 配置失效频道的**订阅方**（决策 D4 / 设计文档 §6.3）：admin 每次成功写配置后广播一条消息，
 * 本类把它翻译成一次 {@link ConfigClient#invalidate(long)}，于是「控制面配置 → 数据面生效」
 * 不再受本地 TTL（30 s）与共享 TTL（10 m）的约束 —— 那正是 M3 登记的 10 分钟上界的来源。
 *
 * <p><b>版本来自消息本身</b>（{@link ConfigInvalidateMessage#version()}，由
 * {@link ConfigInvalidateCodec} 解码）：它就是失效后的写入水位。用本实例手上的
 * {@code visibleVersion} 代替是不行的 —— 一个落后的实例会因此把水位抬得太低，让在飞的旧回填
 * 把刚删掉的陈旧共享条目又写回去。
 *
 * <p><b>坏消息一律忽略并 WARN，绝不抛异常</b>：一条畸形载荷既没有可抬水位的版本，也没有可据以
 * 行动的 reason，唯一有用的事就是让它可见（WARN）然后继续。抛出去并不会换来一次重投 ——
 * Redis Pub/Sub 没有重投与应答，{@code RedisMessageListenerContainer} 自己用
 * {@code handleListenerException} 把监听器的异常吞掉，抛出去只是**多赔上这条原因**。
 * {@link ConfigInvalidateCodec#decode(String)} 对畸形载荷返回 {@code null}（它自己不抛），
 * 这里再做一次空 body 的兜底。
 *
 * <p>装配在 {@link ConfigInvalidateSubscriptionConfig} 里（**不是** {@code ConfigConfig}）：
 * 这个类只依赖 {@link ConfigClient}，因此可以在不起 Spring 上下文的情况下被直接驱动
 * （见 {@code ConfigSubscriberTest}）。
 */
@Component
public class ConfigSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ConfigSubscriber.class);

    private final ConfigClient configClient;

    public ConfigSubscriber(ConfigClient configClient) {
        this.configClient = configClient;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        byte[] body = message == null ? null : message.getBody();
        ConfigInvalidateMessage decoded =
                ConfigInvalidateCodec.decode(body == null ? null : new String(body, UTF_8));
        if (decoded == null) {
            log.warn("忽略畸形的配置失效消息（载荷解不开，不做任何失效）");
            return;
        }
        log.info("收到配置失效消息（version={}, reason={}），清理本地与共享缓存", decoded.version(), decoded.reason());
        configClient.invalidate(decoded.version());
    }
}
