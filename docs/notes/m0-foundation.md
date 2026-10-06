# M0 技术笔记：地基

> **本篇为"证据索引"型笔记**：M0 不在本次会话的工作范围内，因此**不引用任何我未亲眼复核的数字**，
> 只写可验证的目标与证据位置。tag：**`m0`** → `8f25713`。

## 目标（设计 §12 原文）

多模块骨架、Flyway 建表、Docker Compose、统一异常与响应。**验收标准**：`docker compose up` 起全栈，`/healthz` 通。

## 证据在哪

| 内容 | 位置 |
|---|---|
| 实施计划（73 KB，含逐 Task 步骤与判据）| `docs/superpowers/plans/2026-09-23-m0-foundation.md` |
| 任务报告（多份，含 RED/GREEN 与回归数字）| `.superpowers/sdd/task-5-report-m0.md`、`task-6-report-m0.md`（`*-m0.md` 后缀的那些）|
| 台账（里程碑级流水）| `.superpowers/sdd/progress.md` |
| 契约（模块/端口/错误形状）| `docs/CONVENTIONS.md` §1–§4 |

## 现在仍成立的东西（可直接验证，不必引用历史数字）

- 多模块布局与端口约定见 `docs/CONVENTIONS.md` §1/§2；`/healthz` 至今是所有服务健康检查的探针（M6 的 compose 健康检查就用它）。
- 统一异常与响应形状见 §3/§4 —— M3 之后该节的措辞按**实测**收窄过（未注册路径的 404/405 是 Spring 默认体，不是 OpenAI 形状），这条已登记在 README 的已知边界里。

## 说明

M0 的"踩坑与裁定"我**没有第一手材料**，故不写；需要的话从上面的计划与 `*-m0.md` 报告里提炼（它们本身已含 RED/GREEN 证据与回归数字）。
