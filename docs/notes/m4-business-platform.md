# M4 技术笔记：业务平台（租户 / API Key / 渠道 / 配额 / 审计 + 零构建管理台）

> **证据索引型笔记**（M4 不在本次会话范围内）。tag：**`m4`** → `f3cdd2f`
> （收口提交主题：`docs(readme): record the M4 full-stack acceptance results` ⇒ 与 `m3` 的 tag 形状一致：**以验收记录收口**）。

## 目标（设计 §12 原文）

租户 / API Key / 渠道管理 / 配额 / 审计 + 极简管理台。**验收标准**：**控制面配置 → 数据面生效全链路打通**。

## 证据在哪

| 内容 | 位置 |
|---|---|
| 实施计划（307 KB）| `docs/superpowers/plans/2026-09-27-m4-business-platform.md` |
| 全栈验收原始输出 | `.superpowers/sdd/m4-acceptance.md` |
| 任务级证据 | `.superpowers/sdd/_m4-task-*-commit-msg.txt`、`_m4t6-enc-probe.txt`、`_ledger-append*.txt` |
| 契约 | `docs/CONVENTIONS.md` **§6.6 渠道密钥与配置快照**、**§6.7 配额**、**§10 控制面租户模型** |

## 现在仍成立、且被后续里程碑反复引用的结论

1. **控制面 → 数据面"秒级生效"**：admin 发 Pub/Sub 失效广播，网关订阅后清本地缓存与共享快照条目
   （README 记：M3 实测 **101 秒** → M4 是**秒级**）。M6 复验到这条机制的**键与 TTL**：
   `aihub:config:snapshot` TTL≈10 分钟、`aihub:config:invalidate` 频道（网关启动日志里可见）✓
2. **渠道密钥是 AES-GCM 密文**，主密钥只在环境变量里、**解密只发生在网关本地**（§6.6）；
   `POST /api/channels` 由 admin 加密 ⇒ M6 造压测夹具时正是靠它把"随便填的明文"变成密文 ✓
3. **compose 的 `environment:` 是显式白名单**（M4 用一次验收失败换来）⇒ 这条在 M5/M6 直接复用：
   新增 env（KB 的三个、embeddings 模型名、api-key）都必须显式列进去。
4. **必填变量校验**：`.env` 里的 `${VAR:?…}`（M6 现有 11 处）⇒ 缺失时 compose **拒绝启动并点名变量**（M6 实测）。

## 说明

配额/审计的具体数字请以 `m4-acceptance.md` 与 §6.7 为准；本篇只写我在 M5/M6 亲手上复现过的部分。
