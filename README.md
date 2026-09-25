# aihub-platform

面向「大模型应用平台」场景的后端系统：一个统一的 OpenAI 兼容网关 + 一套控制面业务平台。

- **aihub-gateway**（数据面）：统一入口，负责鉴权、限流、配额、多渠道路由、SSE 流式转发与 token 计量。
- **aihub-admin**（控制面）：租户、API Key、渠道、配额、知识库文档、审计与账单的唯一真相源。

设计文档见 [`docs/superpowers/specs/2026-09-23-aihub-platform-design.md`](docs/superpowers/specs/2026-09-23-aihub-platform-design.md)，实施计划见 [`docs/superpowers/plans/2026-09-23-m0-foundation.md`](docs/superpowers/plans/2026-09-23-m0-foundation.md)。

## 当前进度

- [x] **M0 地基**：多模块骨架、统一响应与异常、Flyway 表结构、基础设施连通与健康检查、最小 SSE 转发验证、Docker Compose 全栈
- [x] **M1 网关直通**：API Key 鉴权（Caffeine → Redis → admin 三级回源）+ 单渠道字节级透传（流式与非流式同一段代码）+ 上游状态码 / 响应体 / `Content-Type` 原样透传 + `GET /v1/models`
- [x] **M2 流式与计量**：SSE 转发 + usage 捕获 + 计量落库
- [ ] **M3 流量治理**：Lua 令牌桶限流 + 多渠道路由 + 故障转移
- [ ] **M4 业务平台**：租户 / API Key / 渠道管理 / 配额 / 审计
- [ ] **M5 异步流水线**：文档上传 → 解析 → 嵌入 → 向量库
- [ ] **M6 压测与打磨**：压测报告、故障注入报告、上线

**M1 到底做了什么**：`/v1/**` 现在必须带 API Key，缺省或非法一律 `401` 加 OpenAI 形状错误体（`{"error":{"code":"invalid_api_key",...}}`）；密钥解析走三级回源（本机 Caffeine → 共享 Redis → admin 的 HMAC 内部接口），任一级故障都降级到下一级而不是拒绝请求；转发端点是**字节级直通代理**，上游的状态码、响应体字节与 `Content-Type` 原样回写，因此流式（SSE）与非流式（JSON）由请求体里的 `stream` 字段决定，网关不分流、也不再把上游错误折叠成 `500`；新增 `GET /v1/models`，单渠道场景下回报配置的默认模型。

**M2 到底做了什么**：用量捕获是**旁路观察者** —— 转发路径仍然是字节级直通（上游状态码 / `Content-Type` / 响应体字节一字不改），只是挂了一只只读的 `asByteBuffer()` 探针，把经过的字节复制进一个有界尾窗。非流式从整体 JSON 里读 `usage`；流式则在请求体里注入 `stream_options.include_usage`（**这是唯一被允许的请求体改写**），再从最后一帧读 `usage`。每个 `/v1/**` 请求由网关铸一个 UUID 写进响应头 `x-request-id`，它就是计量事件的 `request_id`；幂等键是 `(request_id, created_at)`，两个值都由**网关**在请求开始时各生成一次（`created_at` 截断到毫秒）并随事件投递，消费端原样使用 —— 消费端若用自己的 `now()`，每次重投都会写成新的一行。事件经 RabbitMQ 用共享的分隔符文本 codec（不是 JSON：`aihub-common` 是零依赖的）投给 admin，由 admin 幂等落 `request_log`；**只有 admin 声明拓扑**，网关只发布。broker 不可用时网关先落内存队列（有界）、再落磁盘 spool，由定时任务重投，任何丢弃都会让 `dropped` 计数 +1 并打 ERROR（绝不静默丢弃）。`request_log` 的月分区由运行时维护：启动补齐 + 每日 03:10 UTC 前推 2 个月。另有 M1 的三处遗留修复：SSE 增量 flush 的可证伪断言、客户端断连计量、`retry-after` 与 `x-ratelimit-*` 透传，以及 `defaultModel` 为空的 NPE。

**验收状态**：全量测试实测 **200 项通过 / 0 失败 / 0 错误 / 0 跳过**（aihub-common 21、aihub-web 47、aihub-gateway 132；`mvn -B clean test` → `BUILD SUCCESS`）。真实上游端到端验收（Docker Compose 全栈 + 真实模型）结论：非流式与流式响应都带 `x-request-id`，两次请求在 `request_log` 各落一行且 token 数与上游响应体里的 `usage` **完全一致**，非流式那行 `ttft_ms` 为 `NULL`、流式那行为正数，行的 `request_id` 等于客户端看到的响应头、`created_at` 等于事件里的值，重放同一事件不产生第二行。M0/M1 时代「用自建 stub 上游验收」的做法已被这一轮真实上游验收取代，M1 遗留的「真实模型回答了问题」就此关闭。

**复现口径（诚实说明）**：上面的数字与「`mvn -B test` 在本机全绿」都产自这台开发机：除了 Docker 守护进程，它还依赖两项**不在仓库里**的环境配置 —— 用户级 `~/.testcontainers.properties`（把 Testcontainers 指向 TCP 上的 Docker）以及本机 `.mvn/maven.config` 里的 JVM 参数。因此在一台干净机器上，需自行保证：Docker 可达，且 JDK 21+ 上允许 Mockito 的动态 agent 挂载（例如 `mvn -B test -DargLine="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"`）；这些**环境作用域**的 JVM 开关有意不进 `pom.xml`。`aihub-web` 的集成测试要真起容器，必须让 Testcontainers 找到 Docker（本机是 `DOCKER_HOST=tcp://127.0.0.1:2375`）；`aihub-gateway` 的测试**不需要** Docker，也不需要有 broker 在跑。


## M0/M1/M2 已知边界

以下是有意划出的范围边界与**尚未被验证的东西**，不是缺陷清单。带「未验证 / 未做」字样的条目请当作事实陈述读：它们没有被任何测试或真实环境证明过。

- `/healthz` 会返回组件明细且不鉴权，等控制面鉴权落地后会一并收紧。
- 网关**不做**限流（Redis + Lua 令牌桶）、配额预扣与校正、对账与账单 —— 属于 M3/M4。
- 网关**不做**多渠道、权重路由、熔断与故障转移 —— M2 仍是单渠道，`GET /v1/models` 也只回报一个配置的模型（M3）。
- 网关**不做**租户 / 渠道 / 配额管理、审计与管理台；API Key 的控制台签发 / 列表 / 吊销接口属于 M4（目前只有本地 CLI 铸造路径）。
- `POST /v1/embeddings` 与文档上传 / 向量化流水线属于 M5，M2 只计量 chat 直通。
- **Redis 是鉴权的信任源之一**：网关把 Redis 里的密钥缓存命中当作**权威结果**，命中即放行、不再回查 MySQL；能往 Redis 写 `aihub:apikey:<sha256>` 的对端等于能伪造任意 API Key，所以 Redis 必须与控制面同等级隔离保护。由此，密钥的吊销 / 停用也不会立刻生效 —— 要等缓存过期（本机 Caffeine ≤30s，Redis ≤5m）。
- `docker-compose.yml` 仅供**本地开发**：Redis 没有密码，宿主映射已收紧为 `127.0.0.1:6380:6379`（本机 IDE 仍可连 6380，容器之间仍走 `redis:6379`）。生产环境的 Redis 认证（`requirepass`）与网络隔离属于 M3 加固项。
- **后端全挂时客户端看到的是 401 `invalid_api_key`**：当 Redis 未命中且 admin 不可达 / 5xx / 配置错时，网关按 fail-closed 一律按「key 不存在」处理，与「你的 key 是错的」在客户端**完全不可区分**（有意为之）。两者的区别只体现在网关日志：admin 回源失败会打 ERROR，并区分「key 不存在」与传输 / 5xx。
- 压测与故障注入报告属于 M6。

M2 新增的边界：

- **`request_log` 的 `api_key_id` 与 `channel_id` 恒为 `NULL`**：共享的 `ApiKeyView` 里没有 `api_key` 的数值主键（补它属于跨服务契约变更），多渠道也还没做。因此这个里程碑的 `request_log` **还不能按 API Key 或渠道聚合**。鉴权关闭、或 exchange 里没有 key 视图时，`tenant_id` 记哨兵 `0`。
- **`prompt_tokens = 0` 表示「未知」，不是「零」**：拿不到上游 `usage` 时（上游没回、响应体超出捕获窗口），`completion_tokens` 是**估算值**（1 个汉字 ≈ 0.6 token、1 个非汉字字符 ≈ 0.3 token，向上取整），`prompt_tokens` 记 0，并用 `error_code = usage_missing`（客户端中断则是 `client_disconnected`）把这件事标出来。M4 的账单 / 对账必须把带这两个 `error_code` 的行当**近似值**处理，不能当精确用量。
- **捕获窗口是 1 MiB 的「尾部」**（`aihub.metering.max-capture-bytes`，默认 `1048576`）：流式下这是对的（`usage` 在最后一帧），但**非流式**响应体是一整块 JSON —— 一旦超过这个上限，头部被丢掉、JSON 不再可解析，精确 `usage` 会**静默降级**成估算值。现实中的非流式 body 远小于 1 MiB，所以保留了默认值；要调小它，先确认非流式 body 仍能完整落在窗口内。`TailBuffer.truncated()` 目前没有计数器，窗口被截断只在结果上体现为 `usage_missing`。
- **计量计数已注册但不对外暴露**：`aihub.metering.published / spooled / dropped / replayed` 四个 Micrometer 计数器存在，但 gateway 只 `include` 了 `health,info`，`/actuator/metrics` 没开（对外暴露计量指标属于 M6）。运维信号目前只有「drop 计数 + ERROR 日志」，spool 也只能看日志与目录，**没有**人工巡检接口，也**没有** DLQ 重投工具。
- **网关的 rabbit 健康指示器被有意关掉**（`management.health.rabbit.enabled: false`）：AMQP starter 会自动注册 `RabbitHealthIndicator`，broker 不可达时 `/healthz` 会变成 `503 DOWN`；但「broker 挂了」是**已设计的降级状态**（事件先落内存队列再落磁盘 spool，恢复后定时重投），把它报成 DOWN 会让编排层（K8s / Compose）去重启一个**正在正常转发**的网关，反而毁掉 spool 兜底的意义。这是评审过的、刻意的取舍 —— 请不要顺手把它改回 `true`；真正的运维信号是 drop 计数与 ERROR 日志。
- **磁盘 spool 假定「单写者」**：一个网关进程独占一个 spool 目录。两个进程共享同一目录时文件名仍可能撞车，`spool-max-files` 这个上界会失守（撞车会被计入 `dropped`，不会静默）。
- **`mandatory` / publisher-returns 这条分支没有得到真实 broker 的端到端验证**：发布端在 gateway 模块，broker 夹具在 admin 侧，模块依赖方向不允许两边相遇（gateway 的测试也不允许依赖 Docker）。代码把「消息被退回」当作投递失败从而落盘，但这条分支目前只有单元测试撑着。
- **分区维护的边界**：启动补齐 + 每日 03:10 UTC 前推 `aihub.metering.partition-months-ahead`（默认 2）个月；**中间空洞只告警、不自动补**（补它要把已有数据搬到锁下，属运维决策）。另外「启动补齐」今天**观测不到**，因为 V1 基线迁移的分区已经到 `2026-12`，在 2026 年 12 月之前不会有新分区被创建。
- 真实上游那一轮只覆盖了**一条**上游、一个模型、一次非流式 + 一次流式；真实上游的错误路径（上游 `429` / 中途断流 / `retry-after` 的实际取值）仍是 stub 级别的验证。

## 技术栈

Java 21（编译目标）· Spring Boot 3.5.16 · MyBatis-Plus 3.5.17 · MySQL 8 · Flyway · Redis 7 · RabbitMQ 3.13 · WebFlux · Testcontainers · Docker Compose

## 快速开始

### 方式一：Docker Compose（推荐）

```powershell
Copy-Item .env.example .env   # 开发默认口令可直接用，真实部署前务必改掉
docker compose up -d --build
```

启动后：

- admin 健康检查：<http://localhost:8081/healthz>
- gateway 健康检查：<http://localhost:8080/healthz>
- 网关入口：<http://localhost:8080/v1>（需要 API Key，见下文「签发 API Key」）
- RabbitMQ 管理台：<http://localhost:15672>

停止：`docker compose down`

**端口对照（宿主 / 容器内）**：

| 服务 | 宿主端口 | 容器内端口 | 说明 |
|---|---|---|---|
| admin | 8081 | 8081 | 控制面 |
| gateway | 8080 | 8080 | 数据面入口 |
| MySQL | **3307** | 3306 | 宿主机原生 MySQL 占用 3306，故让位 |
| Redis | **6380** | **6379** | 宿主机原生 Redis 占用 6379，故让位；容器之间仍用 `redis:6379` |
| RabbitMQ | 5672 | 5672 | 管理台宿主 `15672` |

### 方式二：本机运行

需要 JDK 21+（编译目标为 21）、Maven 3.9+（本仓库不使用 Maven wrapper），以及本机可访问的 MySQL / Redis / RabbitMQ。

用 Docker 只起基础设施前，**必须先有 `.env`**：`docker-compose.yml` 里的 `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` / `RABBITMQ_PASSWORD` / `AIHUB_INTERNAL_SECRET` 都是 `${VAR:?...}` 必填插值，缺少 `.env` 时任何 `docker compose` 命令都会立即报错退出。`.env.example` 的两项业务口令与 admin 端默认值一致（`application.yml` 的 `spring.datasource.password`、`spring.rabbitmq.password` 默认均为 `aihub`），直接复制即可同时满足 compose 与本机运行；若要改用其他口令，启动时用 `SPRING_DATASOURCE_PASSWORD` / `SPRING_RABBITMQ_PASSWORD` 覆盖即可。`AIHUB_INTERNAL_SECRET` 请换成一串 32 字符以上的随机串，admin 与 gateway 必须配**同一个值**。

```powershell
Copy-Item .env.example .env
docker compose up -d mysql redis rabbitmq   # 只起基础设施
mvn -B clean package   # 只想产出 jar 时加 -DskipTests
java -jar aihub-admin/aihub-web/target/aihub-web-0.0.1-SNAPSHOT.jar
java -jar aihub-gateway/target/aihub-gateway-0.0.1-SNAPSHOT.jar
```

本机模式下默认连 `127.0.0.1:3307` 的 MySQL、`127.0.0.1:6379` 的 Redis、`127.0.0.1:5672` 的 RabbitMQ。注意 Redis 端口：以 Docker Compose 起 Redis 时宿主端口是 **6380**，本机直跑服务时要显式覆盖 `SPRING_DATA_REDIS_PORT=6380`（或 `--spring.data.redis.port=6380`）才能连上 compose 里的 Redis。gateway 的上游地址由 `AIHUB_UPSTREAM_BASE_URL` 指定：本机直跑 gateway 时默认 `http://127.0.0.1:11434`（本地 Ollama）；compose 里的 gateway 容器默认 `http://host.docker.internal:11434`（容器访问宿主机的 Ollama）。上游需要密钥时用 `AIHUB_UPSTREAM_API_KEY` 传入，本地 Ollama 留空。

## 调用方式

网关暴露 OpenAI 兼容协议，`base_url` 是 `http://localhost:8080/v1`。Token 形如 `<key_id>.<secret>`，签发方式见下一节。

### curl

```powershell
$env:AIHUB_TOKEN = '<minted-token>'   # 占位符：换成「签发 API Key」一节实际打印出来的 token

# 列出模型
curl.exe -s http://localhost:8080/v1/models `
  -H "Authorization: Bearer $env:AIHUB_TOKEN"

# 非流式对话
curl.exe -s http://localhost:8080/v1/chat/completions `
  -H "Authorization: Bearer $env:AIHUB_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"model":"default","messages":[{"role":"user","content":"你好"}],"stream":false}'
```

`curl.exe` 是本机真正的 curl（PowerShell 的 `curl` 是 `Invoke-WebRequest` 的别名）。不带 `Authorization` 时返回 `401`：

```json
{"error":{"message":"缺少 API Key：请在 Authorization 头里带 Bearer <key_id>.<secret>","type":"invalid_request_error","param":null,"code":"invalid_api_key"}}
```

### OpenAI SDK（Python）

需要 `pip install openai`。

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="<minted-token>",  # 占位符：换成实际签发出来的 token
)

print([m.id for m in client.models.list()])
print(client.chat.completions.create(
    model="default",
    messages=[{"role": "user", "content": "你好"}],
).choices[0].message.content)
```

流式只需加 `stream=True`：网关是字节级透传，SSE 会原样回给客户端，无需换端点或换参数。

## 签发 API Key

M1 的铸造入口是**本地 CLI 路径**（Spring Boot 的 `ApplicationRunner`，默认关闭），**不是 HTTP 接口** —— 公网上不存在造密钥的入口。控制台的签发 / 列表 / 吊销属于 M4。

用本机 jar：

```powershell
java -jar aihub-admin/aihub-web/target/aihub-web-0.0.1-SNAPSHOT.jar `
  --aihub.mint-key.enabled=true `
  --aihub.mint-key.tenant-name=demo `
  --aihub.mint-key.name=my-key
```

用容器（`--no-deps` 表示复用已在运行的基础设施；admin 起完 Web 服务不会自己退出 —— 看到 `API KEY ISSUED` 后 `Ctrl+C` 结束它，`--rm` 负责清理容器）：

```powershell
$env:AIHUB_MINT_KEY_ENABLED = 'true'   # mint 默认关闭，容器里必须显式打开
docker compose run --rm --no-deps `
  -e AIHUB_MINT_KEY_ENABLED `
  -e SPRING_DATASOURCE_URL='jdbc:mysql://mysql:3306/aihub?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' `
  -e SPRING_DATA_REDIS_HOST=redis `
  -e SPRING_RABBITMQ_HOST=rabbitmq `
  admin --aihub.mint-key.tenant-name=demo --aihub.mint-key.name=my-key
```

启动日志里会打印一次：

```text
==================== API KEY ISSUED (仅显示一次) ====================
token    : ak_xxxxxxxxxxxxxxxx.xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
keyId    : ak_xxxxxxxxxxxxxxxx
tenant   : demo
有效期至 : 2027-09-25T09:41:20.123Z
====================================================================
```

- **明文只打印这一次**：库里只存 `SHA-256(secret)`，之后无法再取回；丢了只能重新铸一把。
- 租户名不存在时会自动创建；默认有效期 365 天，可用 `--aihub.mint-key.valid-days=0` 铸不过期的 key。

## 计量与 `request_log`

每个 `/v1/**` 请求都会在网关侧被计量，最终由 admin 落到 `request_log` 一行。要点：

- **`request_id` 就是响应头 `x-request-id`**：网关为每个请求铸一个 UUID，既写进响应头、也写进计量事件。客户端自带的同名请求头不回显；上游返回的同名头也不透传（幂等键必须是网关自产的那个）。
- **幂等键是 `(request_id, created_at)`**（与唯一索引 `uk_request_log_request_id` 一致）：两个值都由**网关**在请求开始时各生成一次（`created_at` 截断到毫秒）并随事件投递，消费端**原样写入**。因此重投 / 重放**不会**产生第二行，而且 `created_at` 是**事件发生的时刻**，不是落库时刻。
- **`status`** 取 `SUCCESS` / `ERROR` / `CANCELLED`；**`error_code`** 取 `upstream_http_<code>`（上游非 2xx）、`upstream_unreachable`（根本没连上上游，客户端看到 `502`）、`upstream_stream_error`（流式中途断）、`usage_missing`（2xx 但拿不到 usage）、`client_disconnected`（客户端断连）、`gateway_error`（网关未预期异常）。
- **`ttft_ms` 只有流式请求有值**，非流式为 `NULL`；`latency_ms` 两者都有。
- **拿不到 usage 时 token 是估算值**：`completion_tokens` 按内容估（1 个汉字 ≈ 0.6 token、1 个非汉字字符 ≈ 0.3 token，向上取整），`prompt_tokens` 记 **0 —— 语义是「未知」，不是「零」**，并用 `error_code` 标出。按 token 计费 / 对账的逻辑必须先看 `error_code`。
- **事件走 RabbitMQ**：交换器 / 队列 / 死信队列（`aihub.metering.exchange`、`aihub.metering.queue`、`aihub.metering.dlq`）由 **admin 声明**，网关只发布。broker 不可用时网关先落内存队列、再落磁盘 spool（容器内 `/app/data/metering-spool`，本机直跑默认 `data/metering-spool`），由定时任务每 30 秒重投；任何丢弃都会让 `aihub.metering.dropped` 计数 +1 并打 ERROR，**绝不静默丢弃**。计量是旁路：broker 挂了转发照常，`/healthz` 也照常 `UP`。
- 配置项都有默认值（`enabled=true`、`max-capture-bytes=1048576`、`queue-capacity=10000`、`spool-max-files=50000`、`replay-interval-ms=30000`）—— 计量是派生数据，缺配置不允许拦住网关启动。

**查自己刚才那次请求**（在容器里查，**不要**把 `.env` 里的口令拼进宿主命令行；`-H tcp://127.0.0.1:2375` 是本机走 TCP 连 Docker 的写法，能直连 Docker 时可以省掉）：

```powershell
docker -H tcp://127.0.0.1:2375 compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -D aihub -e "select request_id, model, prompt_tokens, completion_tokens, total_tokens, latency_ms, ttft_ms, status, error_code, created_at from request_log order by created_at desc limit 5"'
```

上面的 `order by … limit 5` 换成 `where request_id = <响应头里的那个 UUID>`（SQL 里记得加引号）就能只看自己那一次。

看不到某一行时按顺序排查：`docker compose logs gateway` 里有没有 `dropped` 的 ERROR → 网关的 spool 目录（`docker compose exec gateway ls /app/data/metering-spool`）有没有文件 → RabbitMQ 管理台里 `aihub.metering.queue` 与 `aihub.metering.dlq` 两个队列的深度。**禁止**执行 `docker compose config` 或任何会把 `.env` 插值打印出来的命令。

## 测试

需要 Docker Desktop 处于运行状态（`aihub-web` 的集成测试使用 Testcontainers 起真实容器；`aihub-gateway` 的测试**不需要** Docker，也不需要 broker）。JDK 21+ 上还需要允许 Mockito 挂载动态 agent，例如 `mvn -B test -DargLine="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"` —— 这类环境作用域的 JVM 开关不在 `pom.xml` 里（详见上文「复现口径」）。本机要让 Testcontainers 找到 Docker，还需 `$env:DOCKER_HOST='tcp://127.0.0.1:2375'`。

```powershell
mvn -B clean test
```

当前实测：`mvn -B clean test` → `BUILD SUCCESS`，**Tests run: 200, Failures: 0, Errors: 0, Skipped: 0**（aihub-common 21、aihub-web 47、aihub-gateway 132）。注意 Maven 的**进程退出码不可信**（本机见过 `BUILD SUCCESS` 却给出 `[exit code: 1]`），判定以 surefire 汇总 + `BUILD SUCCESS` 为准；另外 Surefire 对含 `@Nested` 的外层类会打印 `Tests run: 0`，那种情况下以 XML 为准。

聚焦跑单个类时用**逗号**而不是加号（`-Dtest=A,B`）—— `-Dtest=A+B` 在 Surefire 3.5.6 上会被当成**一个**类名，配合本仓库 `.mvn/maven.config` 里的 `-Dsurefire.failIfNoSpecifiedTests=false`，它会**静默跳过**你要跑的类却照样 `BUILD SUCCESS`；`-Dtest=A,B` 在 pwsh 里还要加引号（逗号会被 shell 吃掉）。

## 目录结构

```text
aihub-platform/
├── aihub-admin/           控制面（Spring MVC 单体，多模块）
│   ├── aihub-common/      零依赖共享类型
│   ├── aihub-dao/         持久层与 Flyway 迁移
│   ├── aihub-service/     业务服务
│   ├── aihub-mq/          消息生产与消费
│   └── aihub-web/         Controller 与启动模块
├── aihub-gateway/         数据面（WebFlux 独立服务）
├── docs/
│   ├── CONVENTIONS.md     项目约定
│   └── superpowers/       设计文档与实施计划
├── docker-compose.yml     全栈编排
├── .env.example           环境变量样例
└── pom.xml                聚合 POM
```

项目约定见 [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md)。
