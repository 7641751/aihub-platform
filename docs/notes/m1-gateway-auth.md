# M1 技术笔记：网关直通（鉴权 + 单渠道 + 非流式转发）

> **证据索引型笔记**（M1 不在本次会话范围内，**不引用未经复核的数字**）。tag：**`m1`** → `819e689`。

## 目标（设计 §12 原文）

鉴权 + 单渠道 + 非流式转发。**验收标准**：用 OpenAI SDK 改 `base_url` 能调通。

## 证据在哪

| 内容 | 位置 |
|---|---|
| 实施计划（95 KB）| `docs/superpowers/plans/2026-09-23-m1-gateway-auth.md` |
| 任务报告 | `.superpowers/sdd/task-7-report-m1.md`、`task-4-report-m1.md`（`*-m1.md` 后缀的那些）|
| 契约（对外 OpenAI 兼容形状）| `docs/CONVENTIONS.md` §3、§4 |
| 运行时冒烟（打真 jar）| `.superpowers/sdd/task4-smoke-jar.log`、`task4-smoke-jar2.log` |

## 现在仍成立的东西

- `/v1/**` 对外是 OpenAI 兼容形状（§3），错误体形状与未注册路径的缺口见 §4 + README 已知边界。
- 上游默认模型由 `AIHUB_UPSTREAM_DEFAULT_MODEL` 这类 env 注入容器 —— `m1` 这个 tag 的**收口提交主题恰好是**
  `fix: pass AIHUB_UPSTREAM_DEFAULT_MODEL into the gateway container` ⇒ 说明 M1 收口时处理过"env 没进容器"这类部署问题
  （与 M4 那条"compose 的 `environment:` 是显式白名单"是同一类坑，后来在 CONVENTIONS 里固化）。

## 说明

M1 的具体性能/覆盖率数字我**没有复核**，请以计划与 `*-m1.md` 报告为准。
