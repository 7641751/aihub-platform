package com.aihub.gateway.admin;

import com.aihub.common.apikey.ApiKeyView;

/**
 * admin 回源那一跳的**三态**结果。存在的唯一理由：把
 * 「admin **权威地**回答『没有这把 key』」与「**解析不了**（平台故障）」分开。
 *
 * <p><b>为什么必须分开（D1）</b>：这两种结果在**缓存**上必须完全不同：
 * <ul>
 *   <li>{@link Status#NOT_FOUND} 是 admin 的**结论**，可以负缓存 —— 这正是 M1 登记过的决策
 *       （同一个不存在的 key 不必每次都打 admin）；</li>
 *   <li>{@link Status#UNAVAILABLE} 只是**我们不知道**。把它当成 {@code NOT_FOUND} 缓存起来，
 *       等于把一次瞬时故障写成了「这把 key 不存在」，而且经本地负缓存
 *       （{@code aihub.auth.local-cache-ttl}，默认 30 秒）**放大**成 30 秒的固定拒绝 ——
 *       故障早就清除了，客户端还在被拒。这正是 compose 全栈验收实测到的 D1。</li>
 * </ul>
 * 「故障绝不能被缓存」还有第二重意义：它把一次故障的服务端后果限制在**那一次请求**上，
 * 于是故障清除后第一个请求就恢复，不需要任何人工干预。
 *
 * <p><b>三态 → 两种对客结果（D4 起）</b>：{@link Status#NOT_FOUND} 与
 * 「{@link Status#FOUND} 但视图 {@code usable() == false}」都是**我们决定了这把 key 无效** →
 * {@code 401 invalid_api_key}；{@link Status#UNAVAILABLE} 是**我们判不了** →
 * {@code 503 service_unavailable}（{@code ApiKeyAuthFilter} 负责写状态码，本类型不带这个知识）。
 *
 * <p>两种结果**都是拒绝** —— 请求一样走不到限流 / 路由 / 上游，客户端一样拿不到 {@code 200}，
 * 因此安全姿态不变；变的只是**诊断通道**：以前平台故障伪装成「你的 key 错了」，现在诚实地告诉
 * 客户端「我们暂时判不了，请稍后重试」。这是项目所有者的裁决，登记在
 * {@code docs/CONVENTIONS.md} 第 5 节（它取代了此前「两者在客户端不可区分」那条决策）。
 *
 * @param status 三态之一；{@code null} 不允许
 * @param view   仅 {@link Status#FOUND} 时非空，其余两态恒为 {@code null}
 */
public record AdminResolution(Status status, ApiKeyView view) {

    public enum Status {
        /** admin 给出了一份密钥视图（它是否**可用**由 {@code ApiKeyView.usable()} 判）。 */
        FOUND,
        /** admin 权威地说了「没有这把 key」：404 + {@code NOT_FOUND} 信封，或 2xx 空 {@code data}。 */
        NOT_FOUND,
        /** 传输失败 / 超时 / 5xx / 响应畸形 / 签名失败 —— **结论未知**，绝不可当作「不存在」。 */
        UNAVAILABLE
    }

    public static AdminResolution found(ApiKeyView view) {
        return new AdminResolution(Status.FOUND, view);
    }

    public static AdminResolution notFound() {
        return new AdminResolution(Status.NOT_FOUND, null);
    }

    public static AdminResolution unavailable() {
        return new AdminResolution(Status.UNAVAILABLE, null);
    }

    public boolean isFound() {
        return status == Status.FOUND;
    }
}
