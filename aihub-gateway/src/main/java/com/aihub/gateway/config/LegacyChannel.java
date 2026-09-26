package com.aihub.gateway.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.gateway.upstream.UpstreamProperties;

/**
 * 把 M1/M2 的**单渠道配置**（{@code aihub.upstream.base-url} / {@code api-key} / {@code default-model}）
 * 合成一条「渠道」，让冷启动 + admin 不可达时数据面仍然能服务（决策 6 的最后一层兜底）。
 *
 * <p>{@code id = Long.MIN_VALUE}：一个**不可能与数据库自增主键相撞**的哨兵。它的存在让
 * 「这条请求走的是遗留渠道」在计量事件里可见（{@code channel_id = Long.MIN_VALUE} 而不是 NULL），
 * 从而不会与「多渠道正常路径」混淆。{@code apiKeyCipher} 为 {@code null} 是刻意的：
 * 遗留渠道的密钥来自配置而不是密文，解密步骤会跳过它（见 {@code ChannelKeyDecryptor}）。
 */
public final class LegacyChannel {

    /** 遗留单渠道的哨兵 id（负数且远离 0/自增序列）。 */
    public static final long ID = Long.MIN_VALUE;

    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private LegacyChannel() {
    }

    public static ChannelDescriptor of(UpstreamProperties properties) {
        return new ChannelDescriptor(ID, "legacy-single-channel", properties.baseUrl(), null, 0,
                DEFAULT_TIMEOUT_MS, ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    public static boolean isLegacy(long channelId) {
        return channelId == ID;
    }
}
