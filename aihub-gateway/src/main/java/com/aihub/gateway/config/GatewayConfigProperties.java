package com.aihub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code aihub.config.*}：三级配置读取与两级缓存的参数（设计文档 §6.3）。
 *
 * @param localTtl                Caffeine 本地缓存 TTL（§6.3 明文要求 30 秒兜底）
 * @param snapshotTtl             Redis 共享缓存 TTL（spec 没定；取 10 分钟）
 * @param maxLocalSnapshotSources 本地缓存的来源数上界（当前恒为 1，保留是为了让容量在配置里可见）
 * @param refreshCooldown         **两次回源尝试之间的最小间隔**（取 **5 s**，见下）：尝试一开始就把
 *                                窗口推后，窗口内不再回源（无论上一次尝试成功还是失败）。它管的是
 *                                「两次尝试之间的**间隔下限**」，不是「单次尝试的时长上限」：
 *                                单飞槽位让尝试彼此串行，但**不**限速 —— 快速失败的 admin（连接被拒 /
 *                                返回空快照）会让重试速率逼近**请求速率**。5 s 的取值理由：与
 *                                {@code ConfigClient} 的回源等待上限同量级（所以一次「慢失败」与一次
 *                                「快失败」的退避量级相当），同时比本地 TTL（30 s）小一个数量级 ——
 *                                故障恢复后最多 5 s 就能重新拿到控制面快照，不会让 TTL 白白走完。
 *                                {@code ConfigClient.invalidate(long)}（配置变更信号）会显式放行这个窗口。
 */
@ConfigurationProperties(prefix = "aihub.config")
public record GatewayConfigProperties(@DefaultValue("30s") Duration localTtl,
                                      @DefaultValue("10m") Duration snapshotTtl,
                                      @DefaultValue("300") int maxLocalSnapshotSources,
                                      @DefaultValue("5s") Duration refreshCooldown) {
}
