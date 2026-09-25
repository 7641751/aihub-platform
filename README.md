# aihub-platform

面向「大模型应用平台」场景的后端系统：一个统一的 OpenAI 兼容网关 + 一套控制面业务平台。

- **aihub-gateway**（数据面）：统一入口，负责鉴权、限流、配额、多渠道路由、SSE 流式转发与 token 计量。
- **aihub-admin**（控制面）：租户、API Key、渠道、配额、知识库文档、审计与账单的唯一真相源。

设计文档见 [`docs/superpowers/specs/2026-09-23-aihub-platform-design.md`](docs/superpowers/specs/2026-09-23-aihub-platform-design.md)，实施计划见 [`docs/superpowers/plans/2026-09-23-m0-foundation.md`](docs/superpowers/plans/2026-09-23-m0-foundation.md)。

## 当前进度

- [x] **M0 地基**：多模块骨架、统一响应与异常、Flyway 表结构、基础设施连通与健康检查、最小 SSE 转发验证、Docker Compose 全栈
- [x] **M1 网关直通**：API Key 鉴权（Caffeine → Redis → admin 三级回源）+ 单渠道字节级透传（流式与非流式同一段代码）+ 上游状态码 / 响应体 / `Content-Type` 原样透传 + `GET /v1/models`
- [ ] **M2 流式与计量**：SSE 转发 + usage 捕获 + 计量落库
- [ ] **M3 流量治理**：Lua 令牌桶限流 + 多渠道路由 + 故障转移
- [ ] **M4 业务平台**：租户 / API Key / 渠道管理 / 配额 / 审计
- [ ] **M5 异步流水线**：文档上传 → 解析 → 嵌入 → 向量库
- [ ] **M6 压测与打磨**：压测报告、故障注入报告、上线

**M1 到底做了什么**：`/v1/**` 现在必须带 API Key，缺省或非法一律 `401` 加 OpenAI 形状错误体（`{"error":{"code":"invalid_api_key",...}}`）；密钥解析走三级回源（本机 Caffeine → 共享 Redis → admin 的 HMAC 内部接口），任一级故障都降级到下一级而不是拒绝请求；转发端点是**字节级直通代理**，上游的状态码、响应体字节与 `Content-Type` 原样回写，因此流式（SSE）与非流式（JSON）由请求体里的 `stream` 字段决定，网关不分流、也不再把上游错误折叠成 `500`；新增 `GET /v1/models`，单渠道场景下回报配置的默认模型。

**验收状态**：全量测试实测 **71 项通过 / 0 失败 / 0 错误**（aihub-common 13、aihub-web 21、aihub-gateway 37；`mvn -B clean test` → `BUILD SUCCESS`）。Docker Compose 全栈从当前代码 `--build` 重建后，admin / gateway 的 `/healthz` 实测 `status: UP`；经发布端口用真实 HTTP 走通「无 key 401 / 带 key 200 的 `/v1/models`」与「带 key 的 `POST /v1/chat/completions` 经代理落到 OpenAI 兼容上游并原样回写状态码与响应体」。该端到端测试使用的上游是自建 stub（本环境无法启动本地模型服务），**「真实模型回答了问题」这一条尚未验证**。

**复现口径（诚实说明）**：上面的数字与「`mvn -B test` 在本机全绿」都产自这台开发机：除了 Docker 守护进程，它还依赖两项**不在仓库里**的环境配置 —— 用户级 `~/.testcontainers.properties`（把 Testcontainers 指向 TCP 上的 Docker）以及本机 `.mvn/maven.config` 里的 JVM 参数。因此在一台干净机器上，需自行保证：Docker 可达，且 JDK 21+ 上允许 Mockito 的动态 agent 挂载（例如 `mvn -B test -DargLine="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"`）；这些**环境作用域**的 JVM 开关有意不进 `pom.xml`。

## M0/M1 已知边界

以下是有意划出的范围边界，不是缺陷清单：

- `/healthz` 会返回组件明细且不鉴权，等控制面鉴权落地后会一并收紧。
- `request_log` 按月分区；分区维护（新增 / 清理分区）尚未自动化。
- 网关**不做**限流（Redis + Lua 令牌桶）、配额预扣、计量与对账 —— 属于 M2/M3。
- 网关**不做**多渠道、权重路由、熔断与故障转移 —— M1 只有单渠道，`GET /v1/models` 也只回报一个配置的模型（M3）。
- 网关**不做**租户 / 渠道 / 配额管理、审计、账单与管理台；API Key 的控制台签发 / 列表 / 吊销接口属于 M4（M1 只有本地 CLI 铸造路径）。
- `request_log` 落库尚未实现（M2）；M1 的转发不写请求日志。
- `POST /v1/embeddings` 与文档上传 / 向量化流水线属于 M5，M1 只做 chat 直通。
- **Redis 是鉴权的信任源之一**：网关把 Redis 里的密钥缓存命中当作**权威结果**，命中即放行、不再回查 MySQL；能往 Redis 写 `aihub:apikey:<sha256>` 的对端等于能伪造任意 API Key，所以 Redis 必须与控制面同等级隔离保护。由此，密钥的吊销 / 停用也不会立刻生效 —— 要等缓存过期（本机 Caffeine ≤30s，Redis ≤5m）。
- `docker-compose.yml` 仅供**本地开发**：Redis 没有密码，宿主映射已收紧为 `127.0.0.1:6380:6379`（本机 IDE 仍可连 6380，容器之间仍走 `redis:6379`）。生产环境的 Redis 认证（`requirepass`）与网络隔离属于 M3 加固项。
- **后端全挂时客户端看到的是 401 `invalid_api_key`**：当 Redis 未命中且 admin 不可达 / 5xx / 配置错时，网关按 fail-closed 一律按「key 不存在」处理，与「你的 key 是错的」在客户端**完全不可区分**（有意为之）。两者的区别只体现在网关日志：admin 回源失败会打 ERROR，并区分「key 不存在」与传输 / 5xx。
- 压测与故障注入报告属于 M6。

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

## 测试

需要 Docker Desktop 处于运行状态（集成测试使用 Testcontainers 起真实容器）。JDK 21+ 上还需要允许 Mockito 挂载动态 agent，例如 `mvn -B test -DargLine="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"` —— 这类环境作用域的 JVM 开关不在 `pom.xml` 里（详见上文「复现口径」）。

```powershell
mvn -B test
```

当前实测：`mvn -B clean test` → `BUILD SUCCESS`，**Tests run: 71, Failures: 0, Errors: 0, Skipped: 0**（aihub-common 13、aihub-web 21、aihub-gateway 37）。

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
