# M6 实施计划：压测与打磨（压测 + 故障注入 + 部署硬化）

> 设计依据：`docs/superpowers/specs/2026-09-23-aihub-platform-design.md` **§1.4 / §10 / §12**（M6 行）。
> 本文由 2026-10-05 的 brainstorming 逐项确认后写成（每节都经用户选择）。

## 1. 目标与范围

**范围（用户选定 B）**：① k6 压测（QPS/P99/TTFT，含**限流开/关**与**缓存冷/热**对照）；② 故障注入（Redis 挂 / MQ 挂 / **流中途断开**）；③ 部署硬化（生产化 compose：健康检查、重启策略、资源限制、`.env` 校验）+ README 上线章节。
**非目标**：**K8s 部署**（设计 §2 明确非目标）；**Prometheus/Grafana 可观测栈**（设计 §10 里是"可选加分项"，本期不做）。

**成功判据（设计 §1.4 原文口径）**：产出一份压测报告，包含 **QPS / P99 / TTFT**，以及 **「限流开/关」「缓存开/关」的对照数据**；再加一份故障注入报告（Redis/MQ 降级 + 流中途断开）。

## 2. 三条已确认的关键决策

| 决策 | 选择 | 理由 / 口径 |
|---|---|---|
| 压测**上游** | **本机 OpenAI 兼容 chat 桩为主力 + 真 DashScope 抽测一次** | 桩固定延迟/固定 token ⇒ QPS/P99/TTFT 与限流/缓存对照**可重复、可归因到我们自己的网关**；真上游只做一次**小样本**真实性对照（它受配额与网络抖动支配，不能当主力数据） |
| **缓存开/关**对照 | **冷/热对照（零代码改动）** | "空缓存首访（回源 admin/Redis）" vs "稳态（Caffeine 命中）"的 P99 差 = 缓存效果；不新增开关（YAGNI）。报告必须写清这个口径 |
| **限流开/关** | 用**既有** DB 策略启用/停用 | 限流策略本来就在 `ratelimit_rule` 表里（M3），停用即"关"；判据 = 关闭后 `429` 与 `x-ratelimit` 语义必须分别消失/保持 |
| **k6 的运行方式** | **`grafana/k6` 容器镜像**（走 `docker.m.daocloud.io` 代理） | 宿主不装东西；与"镜像走代理"的既有事实一致（Docker Hub 直连在本机不通） |

## 3. 交付物

- `load/`（新目录）：`chat-baseline.js`（QPS/P99/TTFT）、`ratelimit-off.js`（对照用同一脚本 + 前置 DB 切换）、`cache-cold-warm.js`、`fault-stream-abort.js`
- `load/stub/`：**OpenAI 兼容 chat 桩**（固定首字延迟与 token 数，可注入"中途断流"）—— 与 M5 的 embeddings 假上游同一思路（D6）
- `.superpowers/sdd/m6-load-report.md`、`.superpowers/sdd/m6-fault-report.md`（原始数据 + 结论，**git-ignored**）
- `docker-compose.yml` 的生产化硬化 + `README.md` 上线章节 + 设计文档 §12 的 M6 完成标注

## 4. Task 分解（每个 Task 都必须有"能证伪的判据"）

1. **T1 压测桩**：OpenAI 兼容 `/v1/chat/completions`，参数可调（首字延迟、每 token 延迟、token 数、是否中途断流）。判据：直接 `curl` 桩能拿到合规响应；流式是**逐块 SSE**。
2. **T2 基线压测**（桩上游）：目标 **50 VU × 2 min** 非流式 + 20 VU × 2 min 流式。判据：k6 summary 里有 **QPS、p(95)/p(99)、TTFT**（流式用 `http_req_waiting` 或自定义 `Trend`），并记 `request_log` 行数与之吻合。
3. **T3 限流对照**：同脚本、同负载，仅切 DB 策略（关→开）。判据：**关闭时 `429` 计数 = 0**、**开启时 > 0 且 `429` 体是 OpenAI 形状**（这是"对照真的起作用"的证据；若两边一样，说明观测点选错）。
4. **T4 缓存冷/热对照**：清空网关本地缓存与 Redis 后立刻打一轮（冷）vs 稳态再打一轮（热）。判据：两轮的 **P99 差 > 0**，且日志/指标能证明"冷的那轮真的回源了"（否则就是压测噪声）。
5. **T5 故障注入**：① `docker compose stop redis` ⇒ 请求**仍被拒绝**（降级≠放行）且限流退回本机桶；② `stop rabbitmq` ⇒ 计量事件**不丢请求**（计费允许滞后）；③ **流中途断开**：k6 侧 client abort + 桩侧断流各一次（设计 §10 明确要求）。判据：每条都要有"**恢复后行为回到基线**"的对照。
6. **T6 部署硬化**：compose 加 `restart: unless-stopped`、健康检查（复用各服务既有 healthcheck 语义）、`deploy.resources.limits`、`.env` 必填项校验（缺失即**拒绝启动并给出变量名**）。判据：故意抽掉一个必填变量 ⇒ 启动失败且报出变量名；补齐后一条命令起全栈。
7. **T7 收口**：两份报告 + README 上线章节 + 设计 §12 标注完成 + 台账。

## 5. 环境事实（本项目已实测，必须照做）

- **`docker compose up --build` 可能因构建容器解析不了 DNS 而失败** ⇒ 用 `docker build --network=host -t <img> -f <dockerfile> .` 再 `docker compose up -d --no-build`。**改完代码必须先重建镜像**，否则验收验的是旧行为（M5 已踩过一次）。
- **Docker Hub 直连不通** ⇒ 拉镜像走 `docker.m.daocloud.io/...`（k6 与任何新镜像同理）。
- **真上游（DashScope）**：key 在 `.env`（`AIHUB_KB_EMBEDDING_API_KEY` 已存在；chat 用哪把由 T2 定），`base-url = https://dashscope.aliyuncs.com/compatible-mode`（**不带尾部 `/v1`**）。**配额/限速会被触发** ⇒ 真上游抽测必须小样本并在报告里标注"受上游支配"。
- **压测自身会撞限流**（M3 默认 `qps=10/burst=20`）⇒ 基线轮必须先给压测租户配一条足够宽的策略，否则测的是限流器不是网关。**这一点必须写进报告口径**。
- **绝不同时跑两个 Maven / 避免同时跑两个 k6**（共享宿主资源会污染数据）。
- **`docker compose up --build` 可能因构建容器解析不了 DNS 而失败** ⇒ 用 `docker build --network=host -t <img> -f <dockerfile> .` 再 `docker compose up -d --no-build`。**改完代码必须先重建镜像**，否则验收验的是旧行为（M5 已踩过一次）。
- **Docker Desktop 会自己停（已发生两次）**：特征是全量套件报**大量 errors + `Tomcat started on port` = 0**（一个 Spring 上下文都没起）⇒ **先查 Docker**（`docker -H tcp://127.0.0.1:2375 version`），别怀疑代码。拉起：`Start-Process "$env:LOCALAPPDATA\Programs\DockerDesktop\Docker Desktop.exe"`，等 `version` 通即可（本次 12 秒）。
- 密钥类文件一律**不落盘、不打印**；`.env` 不读。

## 7. 进度

- **T1 ✅（2026-10-05，TDD 先红后绿）**：`load/stub/ChatStub.java`（JDK **单文件**程序，`java ChatStub.java <port>`，全 ASCII ——
  单文件模式按平台编码读源码，中文注释在 GBK 控制台会编译失败）+ `aihub-web/src/test/java/com/aihub/admin/load/ChatStubContractTest.java`
  （**不起 Spring**、不占上下文预算；把桩当子进程起起来做 HTTP 黑盒断言 ⇒ 同时验证了 compose 要用的那条启动命令）。
  **RED**：`IllegalStateException: 找不到 load/stub/ChatStub.java`（桩还不存在，失败原因正确）；
  **GREEN**：5/0 —— 健康检查 / 非流式形状+`usage` / **逐块 SSE + `[DONE]`** / **首字延迟可控（250ms 配了就必须 ≥200ms）** /
  **中途断流注入（收不到 `[DONE]`，这正是"流中断"的机器特征）**。
  **全量**：`aihub-common` **68/0**、`aihub-web` **315/0**（= 310 + 新增 5）、**`Tomcat` = 7**。
  ⚠️ 期间 Docker 掉线一次 ⇒ 全量报 `306 run / 214 errors / Tomcat=0`（**环境级**，与代码无关）；拉起后同一条命令回绿。

## 6. 风险与如实登记

- **压测数据的说服力**：桩上游的数据只能说明"网关自身开销"，**不能**说明"真实模型端到端性能" ⇒ 报告必须两类数据分开列，且给出口径。
- **TTFT 的定义**：流式首字延迟取"客户端收到第一个 SSE data 块"的时刻（k6 里用 `Trend` 记录），报告写清定义，避免与"上游首 token"混为一谈。
- **故障注入的副作用**：停 Redis/MQ 会影响同宿主上的其它容器 ⇒ 每条注入后必须**恢复并复测基线**。
