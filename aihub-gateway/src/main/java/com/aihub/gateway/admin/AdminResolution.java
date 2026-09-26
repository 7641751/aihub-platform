package com.aihub.gateway.admin;

import com.aihub.common.apikey.ApiKeyView;

/**
 * admin 回源那一跳的**三态**结果。存在的唯一理由：把
 * 「admin **权威地**回答『没有这把 key』」与「**解析不了**（平台故障）」分开。
 *
 * <p><b>为什么必须分开（D1）</b>：这两种结果在客户端**表现相同**（都 401，见
 * {@code docs/CONVENTIONS.md} 第 4/5 节登记的 fail-closed 决策），但在**缓存**上必须完全不同：
 * <ul>
 *   <li>{@link Status#NOT_FOUND} 是 admin 的**结论**，可以负缓存 —— 这正是 M1 登记过的决策
 *       （同一个不存在的 key 不必每次都打 admin）；</li>
 *   <li>{@link Status#UNAVAILABLE} 只是**我们不知道**。把它当成 {@code NOT_FOUND} 缓存起来，
 *       等于把一次瞬时故障写成了「这把 key 不存在」，而且经本地负缓存
 *       （{@code aihub.auth.local-cache-ttl}，默认 30 秒）**放大**成 30 秒的固定 401 ——
 *       故障早就清除了，客户端还在被拒。这正是 compose 全栈验收实测到的 D1。</li>
 * </ul>
 * 「故障绝不能被缓存」还有第二重意义：它把一次故障的服务端后果限制在**那一次请求**上，
 * 于是故障清除后第一个请求就恢复，不需要任何人工干预。
 *
 * <p><b>本类型不改变对客状态码</b>：{@link Status#UNAVAILABLE} 仍然是 401
 * （fail-closed 是已登记的决策）。「把平台故障标成 5xx 而不是凭证错误」是一个**独立**的、
 * 与既有登记相冲突的提案，本类没有顺带采纳它 —— 见 {@code .superpowers/sdd/d1-fix-report.md}。
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
