# aihub-platform 设计文档

- 日期：2026-09-23
- 状态：已与用户逐节确认（架构边界 / 模块与数据模型 / 数据流与里程碑）
- 目标读者：项目作者本人（大二上，主投 Java 后端）

---

## 1. 背景与目标

### 1.1 背景

作者已掌握 Java、Python、SpringBoot、MyBatis、MySQL、Redis、LangChain、LangGraph，了解 RabbitMQ、Linux、Docker，倾向后端开发与大模型应用开发。工作区内已有两个完整的 Python 侧项目（`rag_qa_project`、`zhilv-yuntu`），但**没有任何 Java 工程**，因此缺少一条能在 Java 后端岗面试中被深挖的主线项目。

### 1.2 项目目标

建设一个「大模型应用平台」后端，作为面试纵深型项目：规模可控，但每个模块都有可追问的难点与可量化的数据。

具体目标：

1. 产出一个可独立部署、可演示、可压测的 Java 后端系统。
2. 覆盖 Java 后端面试的高频深挖点：限流、缓存一致性、MQ 幂等、并发扣减、流式转发、故障转移、密钥管理。
3. 与既有 Python 资产（RAG 检索与编排）形成异构集成，而不是重复造轮子。

### 1.3 约束

| 项 | 约束 |
|---|---|
| 岗位方向 | Java 后端为主，AI 能力作为加分项 |
| 项目性质 | 面试纵深型（能扛住 40 分钟深挖），不追求技术广度 |
| 时间预算 | 每周 8–12 小时，总计约 22 周（约 5 个月）+ 1 个月缓冲 |
| 时间节点 | 目标大二暑假投递实习 |
| 技术形态 | 主业务单体（SpringBoot 多模块）+ 一个独立 AI 网关服务（WebFlux） |

### 1.4 成功标准

- 一条 `docker compose up` 能起全栈（MySQL + Redis + RabbitMQ + admin + gateway + console）。
- 用任意 OpenAI 兼容 SDK 改 `base_url` 与 API Key，即可完成流式对话，token 计量准确。
- 通过 WireMock 注入上游 429 / 超时 / 中途断流，系统能按预期降级与故障转移。
- 产出压测报告，包含 QPS、P99、TTFT，以及「限流开/关」「缓存开/关」的对照数据。
- 能就第 11 节的每个深挖问题给出有依据的回答。

---

## 2. 非目标（YAGNI）

明确不做以下内容，避免范围膨胀：

- 微服务全家桶（Nacos / Sentinel / Seata / Spring Cloud Gateway）
- Kubernetes 部署
- 分库分表、读写分离
- 自研模型推理、模型微调
- 多模态（图像、语音）
- 复杂管理台 UI（仅保留三个必要页面）
- 在线支付对接
- 完整的多轮对话编排（编排继续由既有 Python 服务承担）

---

## 3. 系统架构

### 3.1 全景

```
    管理台（console，极简，非重点）
         │
         ▼
┌──────────────────────────────────┐
│  aihub-admin   （SpringBoot 单体）│  控制面：租户 / 用户 / API Key /
│  多模块 Maven                     │  渠道 / 配额 / 知识库 / 审计 / 账单
└───────┬──────────────────▲───────┘
        │ 内部 HTTP + HMAC │ MQ 消费（计量事件落库）
        ▼                  │
┌──────────────────────────────────┐
│  aihub-gateway （WebFlux 独立服务）│  数据面：统一 OpenAI 兼容入口
│  鉴权 / 限流 / 配额 / 路由 /      │  /v1/chat/completions
│  流式转发 / 计量                  │
└───┬─────────────┬────────────────┘
    │             │
    ▼             ▼
多渠道 LLM     Python RAG 服务（复用既有 rag_qa_project）
DeepSeek
DashScope
本地 vLLM

基础设施：MySQL 8 + Redis 7 + RabbitMQ 3.13 + Docker Compose
```

### 3.2 三条关键边界决策

**决策 A：网关不直连 MySQL，只通过内部接口向 admin 取配置快照。**

- 理由：gateway 是数据面，要求无状态、可水平扩展、不因数据库抖动而雪崩；admin 是控制面，是配置的唯一真相源。
- 代价：必须解决配置变更热生效问题，方案见第 6.3 节两级缓存。
- 收益：MySQL 故障时 gateway 仍可凭缓存继续服务。

**决策 B：检索与编排留在 Python，Java 只做平台与流量治理。**

- 理由：langchain4j 生态成熟度远低于 Python 侧，重写是净损失；Python 侧已有可运行实现。
- 收益：异构集成（超时、熔断、降级、跨语言链路追踪）本身是面试加分项。

**决策 C：计量走 MQ 异步，不阻塞用户响应。**

- 理由：token 统计属于派生数据，其失败不应影响用户请求。
- 代价：必须实现至少一次投递、幂等消费与对账。

---

## 4. 模块划分

```
aihub-platform/
├── aihub-admin/                    SpringBoot MVC 单体（控制面）
│   ├── aihub-common/               统一响应、异常体系、工具、常量、共享 DTO
│   ├── aihub-dao/                  MyBatis-Plus Entity/Mapper + Flyway 迁移脚本
│   ├── aihub-service/              租户/配额/渠道/知识库/审计 业务服务
│   ├── aihub-mq/                   生产者与消费者：计量事件、文档流水线
│   └── aihub-web/                  Controller + DTO + 启动模块
├── aihub-gateway/                  SpringBoot WebFlux 独立服务（数据面）
│   ├── filter/                     ApiKeyAuth、RateLimit、Quota、TraceId
│   ├── route/                      渠道解析、权重选择、健康度与熔断
│   ├── upstream/                   OpenAI 兼容上游客户端（WebClient）
│   ├── relay/                      SSE 流式转发 + usage 捕获 + 断连处理
│   └── meter/                      计量组装 + MQ 投递 + 降级落盘
└── aihub-console/                  极简管理台
```

### 4.1 依赖与隔离原则

- admin 内部依赖方向单向：`aihub-web → aihub-service → aihub-dao`；`aihub-mq` 被 `service` 依赖。
- gateway **不依赖 admin 的任何模块**，仅共享 `aihub-common` 中的 DTO 定义。
- 每个模块有单一职责，可独立编译与测试；gateway 可独立构建、独立部署、单独压测。

### 4.2 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言/运行时 | 编译目标 Java 21（`maven.compiler.release=21`），运行于本机已装的 JDK 25.0.2 | Spring Boot 3.5.16 官方支持 Java 17–25；编译目标锁 21 以与多数公司环境一致 |
| 框架 | Spring Boot **3.5.16**，Maven 3.9.12 多模块 | admin 用 Spring MVC，gateway 用 WebFlux |
| 持久层 | MyBatis-Plus **3.5.17**（`mybatis-plus-spring-boot3-starter`）+ MySQL 8 | 沿用已有 MyBatis 技能 |
| 数据库迁移 | Flyway（版本由 Spring Boot BOM 管理，需额外引入 `flyway-mysql`） | 建表脚本纳入版本控制 |
| 缓存 | Redis 7（Lettuce）+ Caffeine | 两级缓存 |
| 消息 | RabbitMQ 3.13 | 削峰、死信、幂等消费 |
| 鉴权 | JWT（管理台）+ API Key（数据面） | 两套体系互不混用 |
| 加密 | AES-GCM（渠道密钥）+ bcrypt（用户口令）+ SHA-256（API Key 哈希） | 见 6.1 |
| 上游客户端 | WebClient（Reactor Netty） | 支持流式 |
| 前端 | 极简管理台 | 渠道、配额、文档三个页面 |
| 测试 | JUnit 5 + Mockito + Testcontainers（版本由 Spring Boot BOM 管理） | 用真实 MySQL/Redis/RabbitMQ 容器跑集成测试；WireMock 在 M3 引入时再锁定版本 |
| 压测 | k6 或 JMeter | 产出量化数据 |
| 可观测 | Micrometer + Prometheus + Grafana（可选加分项） | 指标：QPS、P99、TTFT、限流拒绝数 |
| 部署 | Docker Compose | 一条命令起全栈 |

---

## 5. 数据模型

全部表由 Flyway 管理。字符集 `utf8mb4`，时间字段统一 `datetime(3)` UTC 存储。

### 5.1 控制面（admin 为唯一真相源）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `tenant` | id, name, status, created_at | 租户 = 企业客户 |
| `sys_user` | id, tenant_id, username, password_hash, role, status | 管理台用户，口令 bcrypt |
| `api_key` | id, key_id, tenant_id, key_hash, name, status, expire_at, last_used_at | 只存 SHA-256 哈希，不存明文 |
| `channel` | id, name, provider, base_url, api_key_cipher, models_json, weight, priority, timeout_ms, status | 上游渠道，密钥 AES-GCM 加密 |
| `model_route` | id, model_name, channel_id, weight, priority, status | 模型 → 多候选渠道，支持故障转移 |
| `quota` | tenant_id, period, token_limit, token_used, request_limit, request_used, version | version 为乐观锁，仅用于 admin 侧并发写（如对账任务与人工调整同时发生）；高频扣减在 Redis 上完成，见 6.2 |
| `rate_limit_policy` | id, tenant_id, api_key_id, qps, burst, status | 限流策略 |

### 5.2 数据面 / 派生（可重建）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `request_log` | request_id, tenant_id, api_key_id, channel_id, model, prompt_tokens, completion_tokens, total_tokens, latency_ms, ttft_ms, status, error_code, created_at | 按月分区；`request_id` 唯一索引 |
| `kb_document` | id, tenant_id, filename, size, sha256, status, chunk_count, error_msg, uploaded_at, updated_at | 状态机见 6.2 |
| `billing_daily` | tenant_id, stat_date, requests, tokens, cost | 日汇总，可由 `request_log` 重算 |

### 5.3 索引与分区要点

- `request_log` 按 `created_at` 做 RANGE 分区（按月），历史分区可归档，避免单表膨胀。
- `request_log(request_id)` 唯一索引，支撑计量幂等。
- `kb_document(tenant_id, status)` 联合索引，支撑状态轮询与运维查询。
- `api_key(key_hash)` 唯一索引，支撑鉴权回源。

---

## 6. 关键设计决策

### 6.1 密钥管理

- **API Key（数据面凭证）**：创建时生成 `key_id` + 随机 secret，明文**仅返回一次**；库中存 `SHA-256(secret)`。校验时对请求携带的 key 求哈希后比对，因此库被拖也不可直接冒用。
- **上游渠道密钥**：AES-GCM 加密存储（`api_key_cipher`）。主密钥由环境变量注入，**不落库、不进镜像**。
- **网关解密位置**：admin 只下发密文，gateway 持主密钥在本地解密。明文密钥不跨越网络，也不进入 admin 之外的可观测链路。
- **轮换**：主密钥支持 `key_version` 双版本共存，轮换时先解密旧版本重加密为新版本，全部完成后再下线旧密钥。

### 6.2 配额扣减：预扣估算 → 实际校正 → 异步对账

1. **请求前预扣**：按 `估算 prompt token + max_tokens` 在 Redis 上用 Lua 原子预扣；余额不足直接返回 429 `QUOTA_EXCEEDED`。
2. **请求后校正**：拿到上游真实 `usage` 后补扣或退回差额。
3. **落库与对账**：计量事件经 MQ 异步写入 MySQL（真相源，`request_id` 唯一键幂等），每日 02:00 对账任务按 `request_log` 聚合重算 `billing_daily`，并与 Redis 计数比对、修正并告警偏差。

为什么不是「先扣再算」：估算偏差会导致额度虚耗。为什么不是「算完再扣」：并发下会超发。追问「Redis 与 MySQL 不一致窗口多大、如何收敛」时，答案即每日对账任务 + 预扣估算偏保守。

### 6.3 三级配置读取与两级缓存

- **读取顺序**：Caffeine 本地缓存 → Redis 共享缓存 → admin 内部接口回源。
- **失效方式**：admin 变更配置后通过 Redis Pub/Sub 广播失效消息，各 gateway 实例清理本地缓存；本地 TTL 30 秒作为兜底。
- **缓存击穿防护**：同一 key 并发回源使用 singleflight（Reactor 侧 `Mono.cache()` 或本地锁）合并。
- **消息丢失**：靠 TTL 兜底 + 快照 `version` 号比对，本地版本落后则丢弃并回源。

### 6.4 文档入库状态机

```
PENDING → PARSING → EMBEDDING → READY
                ↘ 失败重试 3 次（指数退避 + 抖动）→ 死信 → FAILED(error_msg)
   取消 / 最终失败 → 按 doc_id 清理已写入向量（要么全成，要么库里干净）
```

- 上传时计算 `sha256`，同租户下同哈希可秒传与去重。
- 原件必须落盘（或对象存储），因为向量库是可重建的派生数据。
- 嵌入按 10 段/批写入（沿用既有 Python 项目的批大小经验）。

**写入与检索的职责划分（重要，避免两套实现冲突）：**

- **写入侧由 Java 负责**：解析、切分、批处理调度、状态机、重试、回滚，以及调用 OpenAI 兼容的 `/v1/embeddings` 接口与 Chroma REST API 落库。
- **检索侧由 Python 负责**：查询改写、向量召回、Rerank 与编排，即既有 `rag_qa_project` 的在线链路，不重复实现。
- 因此 gateway 转发对话请求时，若该请求需要检索增强，则转调 Python 服务的内部接口，而非自行检索。

**向量库元数据契约（跨语言接口，必须双方一致）：**

| 字段 | 必填 | 说明 |
|---|---|---|
| `doc_id` | 是 | 删除 / 替换 / 回滚的定位键 |
| `tenant_id` | 是 | 租户隔离依据，Python 检索侧必须按此过滤 |
| `kb_id` | 是 | 知识库归属 |
| `origin` | 是 | 固定 `upload`，与预置文档区分 |
| `filename` | 是 | 展示与同名检测 |
| `chunk` | 是 | 段序号，从 0 连续 |
| `sha256` | 否 | 便于一致性核对 |

---

## 7. 接口设计

### 7.1 网关对外（OpenAI 兼容，客户端只需改 `base_url`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/chat/completions` | 支持 `stream=true`，核心接口 |
| POST | `/v1/embeddings` | 文本嵌入 |
| GET | `/v1/models` | 当前租户可用模型列表 |
| GET | `/healthz` | 就绪探针，不带鉴权 |

### 7.2 admin 对内（gateway 调用，HMAC 内部签名 + 内网隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal/config/snapshot` | 一次性拉取渠道 + 路由快照（含密文与 version） |
| POST | `/internal/quota/reserve` | 预扣兜底回源（正常路径走 Redis 本地判定） |

### 7.3 admin 对外（管理台）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录，签发 JWT |
| POST / GET | `/api/channels` | 渠道创建与列表 |
| POST | `/api/channels/{id}/probe` | 连通性探测（真实请求一次上游） |
| POST | `/api/api-keys` | 创建 Key，仅此一次返回明文 |
| POST | `/api/kb/documents` | 上传文档，触发异步流水线 |
| GET | `/api/kb/documents` | 列表 + 状态轮询 |
| GET | `/api/billing/daily?from=&to=` | 账单查询 |
| GET | `/api/logs` | 请求日志分页查询 |

---

## 8. 数据流

### 8.1 链路 1：流式对话

```
Client ──POST /v1/chat/completions (stream=true)──▶ gateway
  ① ApiKeyAuthFilter   key_hash 查 Caffeine → 未命中查 Redis → 未命中回源 admin
  ② RateLimitFilter    Redis + Lua 令牌桶（维度：tenant + api_key）
  ③ QuotaFilter        Redis 原子预扣估算额度，不足则 429 QUOTA_EXCEEDED
  ④ RouteResolver      model → 候选渠道快照，按权重 + 健康度选择
  ⑤ Relay              注入渠道真实密钥 → WebClient 转发 → SSE 逐帧回写
       ├─ stream_options.include_usage=true，捕获最后一帧 usage
       ├─ 客户端断连 → cancel 上游 + 按已收 chunk 估算 token
       └─ 记录 TTFT（首字延迟）与总耗时
  ⑥ Meter              组装计量事件 → RabbitMQ ──▶ admin consumer 落库 + 校正配额
```

### 8.2 链路 2：文档入库流水线

```
admin 上传 → 计算 sha256 → 落盘 → kb_document(PENDING) → 发 MQ
   consumer-parse:  解析 PDF/MD/TXT → 切分 → PARSING → EMBEDDING → 发下一段 MQ
   consumer-embed:  10 段/批嵌入 → 写向量库 → 全部完成 → READY
   失败:  重试 3 次（指数退避 + 抖动）→ 死信队列 → FAILED + error_msg
   回滚:  取消或最终失败 → 按 doc_id 清理已写入向量
```

### 8.3 链路 3：计量与对账

```
MQ 计量事件 ──▶ admin consumer
   ├─ 幂等：request_id 唯一键，重复消费直接丢弃
   ├─ 写 request_log（按月分区）
   └─ 累加 billing_daily
每日 02:00 对账任务
   └─ 按 request_log 聚合重算 billing_daily → 与 Redis 配额比对 → 修正并告警
```

---

## 9. 错误处理与降级

| 场景 | 处理策略 |
|---|---|
| 上游 5xx / 超时 | 换同模型下一候选渠道重试；**仅在未输出任何 token 时允许切换** |
| 上游 429 | 在 Redis 给该渠道打 30s 熔断标记，立即换渠道 |
| 流中途失败 | 保留已输出内容，发 SSE `error` 事件后关流，按已产生用量计费 |
| 客户端断连 | cancel 上游、释放连接，按已收 chunk 估算计量，不计入错误告警 |
| Redis 不可用 | 限流降级为本地令牌桶（单机近似），配额降级为放行 + 告警，不阻断服务 |
| RabbitMQ 不可用 | 计量事件落本地磁盘队列 + 定时补偿重投，不阻塞响应 |
| MySQL 不可用 | admin 返回 503；gateway 凭缓存继续服务 |
| 网关无法判定密钥有效性（控制面故障） | `503 service_unavailable`，不缓存该结论、控制面恢复即自愈（与 `invalid_api_key` 的 401 区分开） |
| 向量库写入失败 | 死信 + 状态置 FAILED + 已写入分片回滚 |

降级总原则：**数据面永不因控制面故障而整体不可用。**

上游失败与客户端断开的状态码约定：

- 流开始前失败 → 标准 HTTP 状态码 + 统一错误体 `{"code","message"}`
- 流开始后失败 → 发 SSE `event: error` 后关流（响应头已发出，无法再改状态码）

---

## 10. 测试策略

| 层次 | 手段 | 覆盖内容 |
|---|---|---|
| 单元测试 | JUnit 5 + Mockito | 限流算法、路由权重、计量计算、配额估算 |
| 集成测试 | Testcontainers（真 MySQL / Redis / RabbitMQ） | 配额并发扣减、MQ 幂等、流水线状态机、死信 |
| 上游契约 | WireMock 模拟多渠道 | 超时、429、中途断流、usage 缺失 → 验证故障转移 |
| 压测 | k6 或 JMeter | QPS、P99、TTFT；限流开/关、缓存开/关对照 |
| 故障注入 | Toxiproxy 或直接停容器 | Redis 挂、MQ 挂时的降级行为 |

覆盖率目标：核心链路（鉴权、限流、配额、路由、计量、流水线）行覆盖 ≥ 70%；故障注入用例必须包含「流中途断开」。

---

## 11. 面试深挖清单

以下六个难点，每个应能独立支撑 5–8 分钟追问：

1. **SSE 流式转发**：Reactor 背压、客户端断连处理、半途失败的 token 如何计量。
2. **限流**：Redis + Lua 令牌桶的原子性、时钟漂移、Redis 故障时的降级。
3. **两级缓存**：Caffeine + Redis 的一致性、Pub/Sub 失效消息丢失、缓存击穿。
4. **渠道故障转移**：健康探测、权重选择、已输出内容的幂等与去重。
5. **异步流水线**：文档解析向量化的「全成或全清」、MQ 幂等与死信。
6. **计量与对账**：流式响应中 `usage` 的获取、重复消费、账单差异定位。

---

## 12. 里程碑

| 阶段 | 时长 | 交付物 | 验收标准 |
|---|---|---|---|
| M0 地基 | 2 周 | 多模块骨架、Flyway 建表、Docker Compose、统一异常与响应 | `docker compose up` 起全栈，`/healthz` 通 |
| M1 网关直通 | 3 周 | 鉴权 + 单渠道 + 非流式转发 | 用 OpenAI SDK 改 `base_url` 能调通 |
| M2 流式与计量 | 3 周 | SSE 转发 + usage 捕获 + 计量落库 | 流式问答端到端可用，token 数准确 |
| M3 流量治理 | 4 周 | Lua 令牌桶限流 + 多渠道路由 + 故障转移 + 熔断 | WireMock 注入 429/超时，能自动切换 |
| M4 业务平台 | 4 周 | 租户/API Key/渠道管理/配额/审计 + 极简管理台 | 控制面配置 → 数据面生效全链路打通 |
| M5 异步流水线 | 3 周 | 文档上传 → 解析 → 嵌入 → 向量库，含回滚与死信 | 中断上传不留脏数据 |
| M6 压测与打磨 | 3 周 | k6 压测报告、故障注入报告、README、部署上线 | 拿出 P99 / TTFT / 限流生效数据 |

合计约 22 周（≈5 个月），另留 1 个月缓冲。

每个里程碑完成后打 git tag，并写一篇短技术笔记，作为面试话术底稿。

**实施计划的粒度说明**：本设计覆盖 7 个里程碑，规模超出单份实施计划的范围。实施计划**按里程碑分别产出**，先做 M0（地基），M0 验收通过后再为 M1 写计划，依此类推。这样每份计划都可在一次迭代内执行完毕并验收。

---

## 13. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| WebFlux / Reactor 学习曲线 | M1–M2 延期 | gateway 自 M1 起即为 WebFlux 工程，不使用 MVC 过渡（避免后期重写）；M0 阶段先独立写一个最小 SSE 转发 demo 验证可行性，再进入 M1 |
| 模型 API 额度不足 | 无法端到端验证 | 用本地 vLLM 或 Ollama 作为渠道之一；测试大量使用 WireMock，不依赖真实额度 |
| 范围膨胀 | 5 个月做不完 | 严格按第 2 节非目标清单裁剪；任何新增需求先记录到「后续迭代」 |
| 管理台耗时过多 | 挤占后端时间 | 管理台只做三个页面，用现成组件库，不做样式打磨 |
| 只写不总结 | 面试讲不出来 | 每个里程碑强制产出一篇技术笔记 + 一次压测/故障注入记录 |
| 缺乏对比数据 | 简历无可写数字 | M6 必须产出「限流开/关」「缓存开/关」四组对照数据 |

---

## 14. 后续迭代（本期不做）

- 语义缓存（相似问题命中缓存，需向量检索支持）
- 提示词模板管理与版本化
- 成本预算告警与自动降级到便宜模型
- 多渠道灰度与 A/B 测试
- 与 `rag_qa_project` 的跨语言链路追踪（OpenTelemetry）打通
- 管理台的用量趋势图表
