# M2 技术笔记：流式与计量

> **证据索引型笔记**（M2 不在本次会话范围内）。tag：**`m2`** → `e4f4103`（收口提交主题是
> `docs: correct the metering kill-switch javadoc to match the replayer's real behaviour` ⇒ 收口时修的是**计量 kill-switch 的文档与真实行为不一致**）。

## 目标（设计 §12 原文）

SSE 转发 + usage 捕获 + 计量落库。**验收标准**：流式问答端到端可用，token 数准确。

## 证据在哪

| 内容 | 位置 |
|---|---|
| 实施计划（279 KB，为各里程碑中最详）| `docs/superpowers/plans/2026-09-23-m2-metering.md` |
| 计量链路契约 | `docs/CONVENTIONS.md` **§6.5 计量事件契约（gateway → admin）** |
| 任务报告 / 反证实验 | `.superpowers/sdd/task-9-*.log`（`counterproof-a/b/c`、`fix-cp1..cp4`、`r2-cpa/cpb` 等，规模很大）|
| MQ 拓扑同构要求 | 计划里的"逐字同构 `aihub.metering.*`"（M5 的 `KbTopology` 就是照它抄的）|

## 现在仍成立、且被后续里程碑反复验证的结论

1. **计量是旁路**：RabbitMQ 挂掉时业务**无感**（M6 实测：停 MQ 期间 370 个请求全 200）+ **计量零丢失**
   （恢复后**精确回补 370 条**，`MeteringSpoolReplayer: 已重投 370 条`）⇒ 说明 M2 设计的"**落磁盘 spool + 定时补偿重投**"是真的在工作。
2. **客户端断连不丢计量**：M6 的失败尝试（被我掐断的两轮）同样进了 `request_log`。
3. **路由失败也进计量**：`gateway_error` + `latency_ms=0` + 无 channel（M6 观察到，来自"到达上游之前就被判掉"的请求）。

## 说明

token 数准确性等数字请以 M2 计划与 `.superpowers/sdd/task-9-*` 为准；本篇只写我在 M6 亲手上复现过的结论。
