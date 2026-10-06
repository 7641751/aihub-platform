# M3 技术笔记：流量治理（限流 + 多渠道路由 + 故障转移 + 熔断）

> **证据索引型笔记**（M3 不在本次会话范围内，但**限流部分我在 M6 亲手复验过** ⇒ 那部分给数字）。tag：**`m3`** → `183c9c0`。

## 目标（设计 §12 原文）

Lua 令牌桶限流 + 多渠道路由 + 故障转移 + 熔断。**验收标准**：WireMock 注入 429/超时，能自动切换。

## 证据在哪

| 内容 | 位置 |
|---|---|
| 实施计划（488 KB，全项目最大）| `docs/superpowers/plans/2026-09-23-m3-traffic-governance.md` |
| 全栈验收原始输出（**437 个文件**）| `.superpowers/m3-acceptance/` |
| 403/429 等契约 | `docs/CONVENTIONS.md` §3/§4 + README 的 M3 段落 |

## M6 复验过的部分（这些数字是本次会话实测的）

- **限流真的按策略工作**：策略 `qps=50/burst=100` ⇒ 通过 **3,196/60s ≈ 53.3/s**（与配置吻合），拒绝 **759,036**（快速失败，avg 3.79ms 未触上游）。
- **429 的响应形状**与 §4/M3 记录**逐字一致**：OpenAI 形状体（`type=rate_limit_error`、`code=rate_limit_exceeded`）+
  `Retry-After` / `Retry-After-MS` / `RateLimit-Limit` / `RateLimit-Remaining`，且**没有 `RateLimit-Reset`**；
  **放行（200）响应上同样带 `RateLimit-Limit` / `RateLimit-Remaining`** ✓
- **Redis 键格式**与 §6.6 记录逐字一致：`aihub:ratelimit:{tenantId}:{sha256(secret)}`、`aihub:apikey:{sha256(secret)}`、
  `aihub:config:snapshot`（TTL≈10 分钟）。

## M6 新发现的、与 M3 降级路径有关的边界

**Redis 挂掉时**：契约成立（**不误拒**：极端并发下没有 401 ⇒ M4 修掉的 D1 确认已修），**但吞吐塌约 100 倍**
（0.34/s vs 35.6/s，每请求 ~10s）⇒ M3 的"降级成本机令牌桶"在**正确性**上没问题，但**代价接近停摆**。
**根因未查**（只登记现象），已写进 README/设计 §12 的已知边界 —— 这是后续打磨的首要候选。
