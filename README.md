# aihub-platform

面向「大模型应用平台」场景的后端系统：一个**统一的 OpenAI 兼容网关**（数据面）+ 一套**控制面业务平台**（运营台）。
M0–M6 七个里程碑全部完成并打 tag，控制面配置 → 数据面**秒级生效**，知识库支持**文档上传 → 向量化 → 可检索**。

| | 模块 | 角色 |
|---|---|---|
| **数据面** | `aihub-gateway`（WebFlux）| `/v1/**` 统一入口：API Key 鉴权 → 限流 → 配额 → 多渠道路由 → **字节级直通转发**（流式/非流式同一段代码）→ token 计量 |
| **控制面** | `aihub-admin`（Spring MVC 单体，多模块）| `/api/**` 运营台 + `/internal/**` 内部接口 + Flyway 库 + 计量落库 + **零构建管理台** + 知识库流水线 |
| **共享** | `aihub-admin/aihub-common` | **零依赖**（JDK-only）的跨模块契约：错误码、HMAC 内部签名、计量文本 codec |

> **想读逐条验收证据（每个里程碑的实测原文、已知边界全文）** → [`docs/archive/README-2026-10-09.md`](docs/archive/README-2026-10-09.md)（重写前的原始 README，93 KB）。
> 本文只讲**功能、用法、技术栈、运行流程**。

---

## 1. 功能地图

### 1.1 数据面（`/v1/**`，OpenAI 兼容）

| 能力 | 说明 |
|---|---|
| **API Key 鉴权** | `Authorization: Bearer <key_id>.<secret>`；三级回源（本机 Caffeine → 共享 Redis → admin 的 HMAC 内部接口），任一级故障降级到下一级 |
| **限流** | Redis + Lua **令牌桶**，按 `tenant + api_key` 两维选策略（key 级 → 租户级 → 内置 `qps=10/burst=20`）；超限 `429 rate_limit_exceeded` + `Retry-After` / `RateLimit-*`；**Redis 挂了降级成本机桶但仍拒绝**（降级 ≠ 放行）|
| **配额** | `POST /v1/chat/completions` 按估算值 **预扣** → 拿到上游真实 `usage` 后**校正**；超限 `429 insufficient_quota`（与限流**同为 429 但语义不同**）|
| **多渠道路由** | `model_route` 按 `priority` 分组、组内按 `weight` 加权随机；`channel.status != ACTIVE` 排除；**故障转移**只在「上游 429/5xx **且**响应尚未提交」时换（最多 3 条候选）；上游 429 额外打 30 秒跨实例熔断标记 |
| **字节级直通代理** | 上游的**状态码 / 响应体字节 / `Content-Type` 原样回写**（流式与非流式由请求体 `stream` 决定，网关不分流）；渠道密钥是 **AES-GCM 密文**，**解密只在网关本地** |
| **计量** | 每个 `/v1/**` 请求铸 `x-request-id`；`POST /v1/chat/completions` 落 `request_log` 一行（详见 §4.5）|
| **端点** | `GET /v1/models`、`POST /v1/chat/completions` |

**错误契约**：网关**实际处理**的路径一律 OpenAI 形状 `{"error":{"message","type","param","code"}}`；
`invalid_api_key`(401) · `service_unavailable`(503，**"我们判不了这把 key"，不是放行**) · `rate_limit_exceeded`(429) ·
`insufficient_quota`(429) · `model_not_found`(404) · `upstream_unreachable`(502)。
⚠️ **没有处理器**的路径/方法（`POST /v1/models` ⇒ 405、`GET /v1/nope` ⇒ 404）返回的是 Spring 默认错误体（已知缺口）。

### 1.2 控制面（`/api/**`，运营台）

| 能力 | 入口 |
|---|---|
| 登录签发令牌（`ADMIN` / `VIEWER` 两级，`VIEWER` 只读）| `POST /api/auth/login`、`GET /api/ping` |
| 租户 | `POST/GET /api/tenants`、`PUT /api/tenants/{id}` |
| API Key（**明文只在创建那一次出现**；支持停用/启用/删除）| `POST/GET /api/api-keys`、`POST /api/api-keys/{id}/disable|enable`、`DELETE /api/api-keys/{id}` |
| 渠道（含**真实连通性探测**与密钥轮换）| `POST/GET /api/channels`、`GET/PUT/DELETE /api/channels/{id}`、`POST /api/channels/{id}/rotate-key`、`POST /api/channels/{id}/probe` |
| 模型路由 | `POST/GET /api/routes`、`PUT/DELETE /api/routes/{id}` |
| 配额 / 限流策略 | `GET/PUT /api/quotas`、**`GET /api/quotas/usage`（只读用量：限额 + 数据面预扣桶 + 剩余量）**、`POST/GET /api/rate-limits`、`PUT/DELETE /api/rate-limits/{id}` |
| 运营查询（**必须显式 `tenantId` + 时间范围**）| `GET /api/logs`、`GET /api/audit`、`GET /api/billing/daily` |
| 知识库（上传/列表）| `POST/GET /api/kb/documents` |
| **零构建管理台**（`console.js`，无 npm、无打包、无第三方脚本）| `GET /console/index.html` |

> **租户模型（`docs/CONVENTIONS.md` §10）**：控制面是**平台运营台**，不是租户自助台 ——
> 「**读按租户、写不限租户**」是**有意的不对称**；出现第一个非平台方账号 / 对非可信网络暴露 / 引入租户自助之前，必须先落地租户隔离。
> **已知缺口**：管理台只接了 **5 个端点**（登录 / ping / 渠道 / Key / 日志），其余 20+ 个端点只有 API、没有界面。

### 1.3 知识库（写入侧）

`POST /api/kb/documents`（multipart）→ 算 sha256 + **原子落盘** → `kb_document(PENDING)` → 提交后发 MQ →
**解析**（`md`/`txt`/`pdf`，PDF 抽文本层）→ **切分**（默认 800 字符窗口 / 100 重叠，无缝隙）→ 写 `kb_chunk` →
分批**嵌入**（OpenAI 兼容 `/v1/embeddings`）→ 写 **Chroma** → 全部打满 `embedded_at` ⇒ **`READY`**。
任一阶段重试耗尽 ⇒ **清 Chroma → 删 `kb_chunk` → `FAILED` + 审计**，消息进 `aihub.kb.dlq`（**全成或全清**）。

⚠️ **检索侧不在本仓库**（属另一个 Python 项目）：它直接查 Chroma，契约见 `docs/CONVENTIONS.md` **§6.8**。

---

## 2. 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 构建 | **Java 21**（`maven.compiler.release=21`）、Maven（**不用 wrapper**）|
| 框架 | **Spring Boot 3.5.16**；admin = **Spring MVC**（Servlet）、gateway = **WebFlux**（Netty）|
| 持久层 | **MyBatis-Plus 3.5.17** + **Flyway**（MySQL 8.4）；`V1__init_schema.sql` 起 |
| 缓存 / 分布式 | **Redis 7**（Lettuce；**Lua** 令牌桶与配额脚本；**Pub/Sub** 做配置主动失效）|
| 消息 | **RabbitMQ 3.13**（两条链路：`aihub.metering.*` 计量、`aihub.kb.*` 知识库；各有 DLQ）|
| 向量库 | **Chroma 0.5.23**（镜像走 `docker.m.daocloud.io` 代理；向量是**可重建的派生数据**）|
| 文档解析 / 凭据 | **PDFBox 3.x**（抽 PDF 文本层）、**spring-security-crypto**（bcrypt + 渠道密钥 AES-GCM）|
| 测试 | JUnit 5 + AssertJ + Mockito、**Testcontainers**（admin 侧真容器）、**WireMock 3.9.1**（**仅 gateway test 作用域、进程内**）、JaCoCo 0.8.14 |
| 压测 / 故障注入 | **k6**（容器镜像）、本机 OpenAI 兼容 chat 桩（`load/stub/ChatStub.java`）|
| 前端 | **零构建**：一个 `console.js`（无 npm/无打包；`sessionStorage` 存令牌、`textContent` 渲染防 XSS）|
| 编排 | **Docker Compose**（6 服务：mysql / redis / rabbitmq / chroma / admin / gateway）|

镜像：`mysql:8.4`、`redis:7-alpine`、`rabbitmq:3.13-management-alpine`、`chromadb/chroma:0.5.23`。

---

## 3. 运行流程

### 3.1 数据面：一次 `/v1/chat/completions` 走过什么

```
客户端 ──Bearer <key_id>.<secret>──▶ gateway
  ① RequestIdFilter      铸 UUID ⇒ 响应头 x-request-id（401/404 也带）
  ② ApiKeyAuthFilter     本地缓存 → Redis → admin 内部 HMAC；判不了 ⇒ 503（不是 401）
  ③ RateLimitFilter      Redis+Lua 令牌桶（判定整体 offload 到 boundedElastic，不占事件循环）
  ④ QuotaFilter          仅 /v1/chat/completions：Redis+Lua 预扣（Redis 挂 ⇒ fail-open + 可选 admin 兜底）
  ⑤ ChatRelayController  读配置快照路由 → 逐请求注入该渠道密钥（本地解密）→ 字节级转发
  ⑥ MeteringPublisher    doFinally 读 usage ⇒ MQ 事件 ⇒ admin 幂等落 request_log
```

- **配置从哪来**：`ConfigClient` 三级读（本机 Caffeine 30s → 共享 Redis 10m → admin 快照）+ 内存 last-good；
  admin 改配置后发 **Pub/Sub 失效广播** ⇒ 数据面**秒级**可见（M4 实测 **0.03 / 0.1 秒**，对照无广播时的 **101 秒 / 最长 10 分钟**）。
- **失败语义**：上游 4xx **原样透传不换渠道**；429/5xx **且尚未提交**才换（最多 3 条候选）；没连上上游 ⇒ 网关自产 `502 upstream_unreachable`。
- **降级矩阵**：Redis 挂 ⇒ 限流退本机桶**仍拒绝**、配额放行、鉴权回源 admin；admin 挂 ⇒ 冷缓存 key ⇒ `503 service_unavailable`（**不缓存该结论**，控制面一恢复即自愈）；broker 挂 ⇒ 转发照常，计量先内存队列再磁盘 spool。

### 3.2 控制面：改一次配置如何生效

```
浏览器/curl ──Bearer <控制台令牌>──▶ admin
  ① ConsoleAuthFilter   校验令牌（除 /auth/login 外全 /api/** 都要；VIEWER 只读）
  ② Controller/Service  写 MySQL（审计与状态迁移同一事务）
  ③ Pub/Sub 广播        aihub:config:invalidate（带版本水位）
  ④ gateway ConfigSubscriber 收到 ⇒ 清本地 Caffeine + 删共享条目 ⇒ 下一请求回源 admin
```

### 3.3 知识库：一条文档的生命周期

```
POST /api/kb/documents (multipart: file + tenantId)
   └ KbFileStore.stage()  边读边算 sha256，落同目录临时文件
   └ kb_document(PENDING) + promote() 原子改名 ⇒ {root}/{tenantId}/{sha256}
   └ after-commit 发 parse:{docId}                     ← 回滚时钩子不执行 ⇒ "消息发了行没提交"不可能
KbParseService（一个事务）：PENDING|PARSING|FAILED → PARSING ⇒ 抽文本 ⇒ 切分 ⇒ upsert kb_chunk
   └ chunk_count=N + EMBEDDING ⇒ after-commit 发 ceil(N/batchSize) 条 embed:{docId}:{from}:{to}
KbEmbedService：调 /v1/embeddings ⇒ upsert 进 Chroma（id="{docId}:{seq}"）⇒ 打 embedded_at
   └ 全段打满（从行数算出）⇒ 条件迁移 EMBEDDING → READY
失败：重试 3 次指数退避耗尽 ⇒ KbDocumentCleanup（① 删 Chroma ② 删 kb_chunk）⇒ FAILED + 审计 ⇒ 消息进 aihub.kb.dlq
```

**状态机**：`PENDING → PARSING → EMBEDDING → READY`；`FAILED` 是终态但**可重入**（重传同一份内容即重跑）。
**两个刻意的不变量**：① **原件是唯一真相源**，向量可重建；② **幂等三件套** = 唯一键（`uk_kb_document_tenant_sha` / `uk_kb_chunk_doc_seq`）+ Chroma `upsert` + **带条件的状态迁移**（`affected=0` 就 ack 丢弃）。

---

## 4. 怎么用

### 4.1 启动

```powershell
Copy-Item .env.example .env          # 开发默认口令可直接用，真实部署前务必改掉
docker compose up -d --build         # 首次；之后改完代码要先重建镜像，见下面 ⚠️
```

| | 地址 |
|---|---|
| **管理台** | <http://localhost:8081/console/index.html> |
| 数据面 | <http://localhost:8080/v1>（需要 API Key）|
| 健康检查 | admin `:8081/healthz` · gateway `:8080/healthz` · chroma `:8000/api/v1/heartbeat` |
| RabbitMQ 管理台 | <http://localhost:15672> |

**端口对照（宿主 / 容器内）**：admin `8081/8081` · gateway `8080/8080` · MySQL **`3307`/3306** · Redis **`6380`/6379** · RabbitMQ `5672/5672`（管理台 `15672`）· chroma `8000/8000`。
（3307/6380 是给宿主已占用的 3306/6379 让位；**容器之间**仍用 `mysql:3306` / `redis:6379`。）

⚠️ **三个本机坑（都实测踩过）**
1. **`docker compose up --build` 可能构建失败**：新增的 Maven 依赖不在构建容器的缓存里，而**构建容器解析不了 DNS**（`repo.maven.apache.org: No address associated with hostname`）⇒ 用
   `docker build --network=host -t aihub-platform-admin -f aihub-admin/aihub-web/Dockerfile .`（gateway 同理）再 `docker compose up -d --no-build`。
   **改完代码必须重建镜像，否则 compose 验收验的是旧行为。**
2. **Docker Desktop 自己退出后栈不会自愈**（实测：容器停在 `Exited(143)/(0)` 21 小时没回来）⇒ 恢复就是 `docker compose up -d --no-build` 一条命令；运维判据 = `docker ps` 里 6 个容器 **且** 两个 `healthz=200`。
3. **必填变量**：`.env` 里有 **11 处** `${VAR:?…}` 校验，缺失时 compose **拒绝启动并点名变量**。
   注意 `${VAR:?}` 对"**已设置但为空**"的 shell 变量**不报错**，要自测这条得用 `--env-file <另一份不完整 env>`。

**本机运行**（需 JDK 21+ / Maven 3.9+ / 可达的 MySQL·Redis·RabbitMQ）：

```powershell
Copy-Item .env.example .env
docker compose up -d mysql redis rabbitmq     # 只起基础设施
mvn -B clean package                          # 加 -DskipTests 只出 jar
java -jar aihub-admin/aihub-web/target/aihub-web-0.0.1-SNAPSHOT.jar
java -jar aihub-gateway/target/aihub-gateway-0.0.1-SNAPSHOT.jar
```
本机直跑时 Redis 要显式 `SPRING_DATA_REDIS_PORT=6380`（compose 的宿主映射）；gateway 上游用 `AIHUB_UPSTREAM_BASE_URL`（默认本机 Ollama `http://127.0.0.1:11434`，容器内默认 `http://host.docker.internal:11434`）。

### 4.2 数据面：curl / OpenAI SDK

```powershell
$env:AIHUB_TOKEN = '<key_id>.<secret>'    # 签发方式见 §4.3

curl.exe -s http://localhost:8080/v1/models `
  -H "Authorization: Bearer $env:AIHUB_TOKEN"

curl.exe -s http://localhost:8080/v1/chat/completions `
  -H "Authorization: Bearer $env:AIHUB_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"model":"default","messages":[{"role":"user","content":"你好"}],"stream":false}'
```
（`curl.exe` 是真 curl；PowerShell 的 `curl` 是 `Invoke-WebRequest` 的别名。加 `"stream":true` 即 SSE，**不用换端点**。）

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8080/v1", api_key="<minted-token>")
print([m.id for m in client.models.list()])
print(client.chat.completions.create(model="default",
      messages=[{"role": "user", "content": "你好"}]).choices[0].message.content)
```

⚠️ **PS 5.1 工装坑**：把**带内嵌双引号**的 JSON 直接传给 `curl.exe` 会被拆坏（表现为网关收到 `model=null` ⇒ `404 不提供该模型: null`）⇒ 用 `--data-binary "@body.json"`。

### 4.3 签发 API Key

**方式一（推荐）· 控制台 API**：明文**只在创建那一次**返回，库里只存 `SHA-256(secret)`。

```powershell
$tok = (Invoke-RestMethod http://localhost:8081/api/auth/login -Method Post -ContentType 'application/json' `
        -Body '{"username":"<sys_user>","password":"<口令>"}').data.token      # 2 小时有效
Invoke-RestMethod http://localhost:8081/api/api-keys -Method Post -ContentType 'application/json' `
  -Headers @{Authorization="Bearer $tok"} -Body '{"tenantId":1,"name":"my-key","validDays":30}'
# → data.plaintextKey 就是 <key_id>.<secret>
```
撤销/恢复：`POST /api/api-keys/{id}/disable` · `.../enable` · `DELETE /api/api-keys/{id}`。
⚠️ **吊销不是瞬时生效**：网关两级密钥缓存（本机 Caffeine 30s + Redis）会让它再放行**一个 TTL 左右**（实测 45 秒内变 401）。

**方式二 · 本机 CLI**（M1 的铸造口，默认关闭，公网无此入口）：

```powershell
java -jar aihub-admin/aihub-web/target/aihub-web-0.0.1-SNAPSHOT.jar `
  --aihub.mint-key.enabled=true --aihub.mint-key.tenant-name=demo --aihub.mint-key.name=my-key
```
启动日志里打印一次 `API KEY ISSUED (仅显示一次)`；租户名不存在会自动创建，默认 365 天（`--aihub.mint-key.valid-days=0` = 不过期）。

### 4.4 知识库

```powershell
# ① 上传（multipart：part 名 file + 参数 tenantId）
curl.exe -s -X POST http://localhost:8081/api/kb/documents `
  -H "Authorization: Bearer $tok" -F "tenantId=1" -F "file=@.\docs\CONVENTIONS.md;type=text/markdown"
# → {"code":"OK","data":{"id":N,"status":"PENDING","chunkCount":0,"duplicate":false}}

# ② 轮询（page 缺省 0、size 缺省 20 且 >200 钳到 200；tenantId 缺省 = 令牌里的租户）
Invoke-RestMethod "http://localhost:8081/api/kb/documents?tenantId=1&size=20" -Headers @{Authorization="Bearer $tok"} |
  Select-Object -ExpandProperty data | Select-Object -ExpandProperty items |
  Format-Table id,filename,sizeBytes,status,chunkCount,errorMsg

# ③ 检索（本仓库没有检索端点：直接查 Chroma，文本回查 MySQL）
$cid = (Invoke-RestMethod http://localhost:8000/api/v1/collections | Where-Object name -eq 'kb_chunks').id
curl.exe -s "http://localhost:8000/api/v1/collections/$cid/count"                 # ⚠️ count 是 GET（POST ⇒ 405）
curl.exe -s -X POST "http://localhost:8000/api/v1/collections/$cid/get" `
  -H "Content-Type: application/json" -d '{\"where\":{\"doc_id\":N}}'
curl.exe -s -X POST "http://localhost:8000/api/v1/collections/$cid/query" `
  -H "Content-Type: application/json" `
  -d '{\"query_embeddings\":[[...1024 维...]],\"n_results\":3,\"where\":{\"tenant_id\":1}}'
```

- **跨语言向量契约**（改它就是**破坏性变更**）：collection `kb_chunks`；记录 id **`"{docId}:{seq}"`**（可反推）；
  metadata **必填** `doc_id`(long) / `tenant_id`(long) / `seq`(int)；**文本不进 Chroma**；删除 = `where={"doc_id":N}`。
- **文件类型**：`md` / `txt` / `pdf`（PDF 抽**文本层**；扫描版 ⇒ `FAILED("无可提取文本")`，**不做 OCR**）；上传上限 **20 MB**。
- **重传同一份内容 = 现成的重试手势**（`duplicate:true` 不新增行/文件，但会重新触发解析，`FAILED` 行也能被重新驱动）。
- ⚠️ **JDK 客户端**打 Chroma 必须钉 `HTTP_1_1`（明文 http 默认先发 h2c 前奏 ⇒ uvicorn 报 422；Python 无此问题）。

### 4.5 计量与 `request_log`

**只有 `POST /v1/chat/completions` 计量**（`GET /v1/models`、以及控制器之前被短路的 401/503/404 都带 `x-request-id` 但**不落行**）。

- **`request_id` = 响应头 `x-request-id`**（网关铸造；客户端自带/上游返回的同名头都不采用）。
- **幂等键 `(request_id, created_at)`**：两个值都由**网关**在请求开始时生成并随事件投递，消费端**原样写入** ⇒ 重投不产生第二行，`created_at` 是**事件发生时刻**。
- `status` ∈ `SUCCESS|ERROR|CANCELLED`；`error_code` ∈ `upstream_http_<code>` / `upstream_unreachable` / `upstream_stream_error` / `usage_missing` / `client_disconnected` / `gateway_error`。
- `ttft_ms` **只有流式**有值（非流式 `NULL`）；拿不到 `usage` 时 `completion_tokens` 是**估算**、`prompt_tokens = 0` 的语义是**「未知」不是「零」**。

```powershell
docker -H tcp://127.0.0.1:2375 compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -D aihub -e "select request_id, model, total_tokens, latency_ms, ttft_ms, status, error_code, created_at from request_log order by created_at desc limit 5"'
```
排查顺序：`docker compose logs gateway` 有无 `dropped` ERROR → 网关 spool 目录 → RabbitMQ 里 `aihub.metering.queue` / `.dlq` 深度。
**禁止**执行 `docker compose config` 或任何会把 `.env` 插值打印出来的命令。

### 4.6 关键配置（`.env` / 环境变量）

| 变量 | 作用 | 默认 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` / `RABBITMQ_PASSWORD` / `RABBITMQ_USER` | 基础设施凭据（compose 必填校验）| `.env.example` 可直用 |
| `AIHUB_INTERNAL_SECRET` | admin ↔ gateway 内部接口 HMAC 密钥（**两端必须一致**，≥32 字符随机）| 必填 |
| `AIHUB_CHANNEL_MASTER_KEY` | 渠道密钥 AES-GCM 主密钥（**只在环境变量**，解密只在网关本地）| 必填 |
| `AIHUB_CONSOLE_SECRET` | 控制台令牌签发/校验密钥（**轮换会让已签发令牌立即失效**）| 必填 |
| `AIHUB_UPSTREAM_BASE_URL` / `_API_KEY` / `_DEFAULT_MODEL` | 遗留单渠道上游（无控制面快照时的兜底）| 本机 Ollama |
| `AIHUB_KB_STORAGE_ROOT` | 原件存储根（compose 挂在 `admin-files` 卷 ⇒ 跨重启存活）| `target/kb-storage` |
| `AIHUB_KB_EMBEDDING_BASE_URL` / `_MODEL` / `_API_KEY` | embeddings 上游（OpenAI 兼容）| 空 ⇒ 调用时**响亮失败** ⇒ 文档走 `FAILED` |
| `AIHUB_KB_CHROMA_BASE_URL` | 向量库地址 | `http://chroma:8000` |
| `AIHUB_RATELIMIT_ENABLED` / `AIHUB_QUOTA_ENABLED` / `AIHUB_AUTH_ENABLED` / `AIHUB_METERING_ENABLED` | 各链路的开关（默认全 `true`）| `true` |

⚠️ **`AIHUB_KB_EMBEDDING_BASE_URL` 不要带尾部 `/v1`**（客户端自己拼 `/v1/embeddings`；带上会 404）。
其余可调项（限流桶容量、计量捕获窗口、KB 切分大小/批次、Chroma 集合名等）见 `docs/CONVENTIONS.md` §6.8 与各模块 `application.yml`。

---

## 5. 测试与验收

需要 **Docker 在运行**（`aihub-web` 的集成测试用 Testcontainers 起真容器；**`aihub-gateway` 的测试不需要 Docker 也不需要 broker**）。
JDK 21+ 上 Mockito 需要允许动态 agent，并让 Testcontainers 找到 Docker：

```powershell
$env:DOCKER_HOST='tcp://127.0.0.1:2375'
mvn -B clean test
```

**当前实测（2026-10-07）**：`BUILD SUCCESS`，**Tests run: 777, Failures: 0, Errors: 0, Skipped: 0**
（`aihub-common` **68** · `aihub-web` **315** · `aihub-gateway` **394**），8 个模块全 `SUCCESS`。
**核心链路行覆盖率 91.07%**（鉴权 89.22% / 限流 96.84% / 配额 89.97% / 路由 98.26% / 计量 92.80% / 流水线 86.04%；全仓 `com.aihub.*` 92.48%）。

只跑网关（**不需要 Docker**）：`mvn -B clean test -pl aihub-gateway -am`
只跑某一类：`mvn -B clean test -pl aihub-gateway -am "-Dtest=A,B"` ← **用逗号**（`A+B` 会被当成一个类名而**静默跳过**且照样 `BUILD SUCCESS`）；**单独构建一个模块必须带 `-am`**（否则会撞 `.m2repo` 里的空 jar，报假的 "package does not exist"）。

> **验收方法**（每个里程碑都这么做）：真实 **compose 全栈**上跑，**每条判据配反证**（例：关掉主动失效订阅 ⇒ 同样的配置变更 150 秒仍不可见；关掉 `cleanup` ⇒ Chroma 应留残留）。原始记录在 `.superpowers/sdd/`（git-ignored）。
> M6 的量化结论：桩上游非流式 **397.5 QPS / P95 129.6ms / 失败 0%**、流式 **TTFT 127.4ms**；限流开/关对照；缓存冷/热 **P99 差 38.9×**；故障注入 **MQ 挂 = 计量零丢失**、**Redis 挂 = 不误拒但每请求变慢**；真上游抽测（DashScope `qwen-turbo`）**0% 失败 / TTFT 243ms**。

---

## 6. 目录结构

```text
aihub-platform/
├── aihub-admin/           控制面（Spring MVC 单体，多模块）
│   ├── aihub-common/      零依赖共享类型（错误码 / HMAC / 计量与 KB 的消息 codec）
│   ├── aihub-dao/         持久层与 Flyway 迁移（V1 起）
│   ├── aihub-service/     业务服务（console / kb / audit / quota / metering …）
│   ├── aihub-mq/          消息生产与消费（metering / kb 两条链路的拓扑与消费者）
│   └── aihub-web/         Controller 与启动模块（含静态管理台 console/）
├── aihub-gateway/         数据面（WebFlux 独立服务）
├── load/                  k6 压测脚本 + 本机 chat 桩（M6）
├── docs/
│   ├── CONVENTIONS.md     项目约定（**契约**：租户模型 §10、数据面错误 §4、KB/向量 §6.8、测试纪律 §8）
│   ├── superpowers/       设计文档（specs）与逐里程碑实施计划（plans）
│   └── archive/           历史文档（含重写前的 README）
├── docker-compose.yml     全栈编排（6 服务）
├── .env.example           环境变量样例
└── pom.xml                聚合 POM（Spring Boot 3.5.16 / release 21）
```

---

## 7. 里程碑与已知边界

| | 里程碑 | 一句话 |
|---|---|---|
| ✅ | **M0 地基** | 多模块骨架、统一响应/异常、Flyway、compose 全栈、健康检查 |
| ✅ | **M1 网关直通** | API Key 三级回源 + **字节级透传** + `GET /v1/models` |
| ✅ | **M2 流式与计量** | SSE 转发 + usage 捕获 + MQ 落 `request_log` + spool 兜底 |
| ✅ | **M3 流量治理** | Lua 限流 + 多渠道路由 + 故障转移/熔断 + 渠道密钥本地解密 + 配置快照 |
| ✅ | **M4 业务平台** | 租户/Key/渠道/路由/配额/审计/账单 + 零构建管理台 + **配置秒级生效** |
| ✅ | **M5 异步流水线** | 文档上传→解析→嵌入→**真 Chroma**；全成或全清 + DLQ |
| ✅ | **M6 压测与打磨** | k6 压测（含限流/缓存对照）+ 故障注入 + 部署硬化 + 上线章节 |

tag `m0`–`m6` 均已打（`m6` = M6 收口点；其后另有若干文档与网关 `Redis` 超时修复提交）。

**已知边界（摘要）**——**完整版在归档 README 的「已知边界」章节**：

1. **Redis 降级**：不误拒（无 401），但请求路径上有 5–6 个串行阻塞式 Redis 触点，各付一次 `spring.data.redis.timeout`（**已从 2s 收到 300ms**，并把逐请求版本探测按"主动失效是否接上"分档 ⇒ 单请求从 **10–12s 降到 0.44–0.75s**）。残留：`ConfigClient.current()` 仍是**同步** API。
2. **`/v1/**` 无处理器的 404/405**（`POST /v1/models` 等）返回 Spring 默认错误体，不是 OpenAI 形状。
3. **流式中途上游断流不发 SSE `error` 帧**（直接收尾，客户端看到被截断的流）。
4. **计量只覆盖 `POST /v1/chat/completions`**；`request_log.model` 超 128 码点会被截断（转发体逐字节不变）。
5. **管理台只接 5 个端点**；`/actuator/metrics` 未开（计量/限流/降级的计数器不对外暴露）。
6. **控制面不隔离租户**（平台运营台的有意设计；升级门槛见 `CONVENTIONS.md` §10）。
7. **KB**：不做 OCR；`kb_chunk` 存了一份文本；换嵌入模型 = 重建 collection；**检索侧不在本仓库**。
8. **真上游只有抽测量级**（2 VU × 20s、单模型、未覆盖上游错误路径）⇒ 不可当容量规划依据。
9. **`quota.token_used` / `request_used` 是"预留但未接线"的列**（无任何生产写入方 ⇒ **生产恒为 0**）：
   `GET /api/quotas` 的 `tokenUsed` 因此**永远不是真实用量**（实测并列：`PUT` 回 `tokenUsed:0`，
   同一时刻 `GET /api/quotas/usage` 回 `tokenUsed:73`）。**真实用量只在数据面的 Redis 桶里** ⇒
   看用量用 `GET /api/quotas/usage`（只读、桶口径），别拿 `GET /api/quotas` 的已用量做看板或计费。
   同类还有 `api_key.last_used_at`（恒为 `null`）。

---

## 8. 文档索引

| 文档 | 用途 |
|---|---|
| [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md) | **项目契约**：接口/数据面错误/内部接口/租户模型/数据库/测试纪律 —— 改代码前先读 |
| [`docs/superpowers/specs/`](docs/superpowers/specs/) | 设计文档（含 §12 里程碑表）|
| [`docs/superpowers/plans/`](docs/superpowers/plans/) | M0–M6 逐里程碑实施计划（含每任务的判据与完成记录）|
| [`docs/archive/README-2026-10-09.md`](docs/archive/README-2026-10-09.md) | **重写前的原始 README**：逐条验收证据、已知边界全文、部署与上线细节 |
| `.superpowers/sdd/`（git-ignored）| 台账与原始验收记录（`*-acceptance.md`、`m6-load-report.md`、覆盖率汇总等）|
