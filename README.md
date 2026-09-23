# aihub-platform

面向「大模型应用平台」场景的后端系统：一个统一的 OpenAI 兼容网关 + 一套控制面业务平台。

- **aihub-gateway**（数据面）：统一入口，负责鉴权、限流、配额、多渠道路由、SSE 流式转发与 token 计量。
- **aihub-admin**（控制面）：租户、API Key、渠道、配额、知识库文档、审计与账单的唯一真相源。

设计文档见 [`docs/superpowers/specs/2026-09-23-aihub-platform-design.md`](docs/superpowers/specs/2026-09-23-aihub-platform-design.md)，实施计划见 [`docs/superpowers/plans/2026-09-23-m0-foundation.md`](docs/superpowers/plans/2026-09-23-m0-foundation.md)。

## 当前进度

- [x] **M0 地基**：多模块骨架、统一响应与异常、Flyway 表结构、基础设施连通与健康检查、最小 SSE 转发验证、Docker Compose 全栈
- [ ] **M1 网关直通**：API Key 鉴权 + 单渠道非流式转发
- [ ] **M2 流式与计量**：SSE 转发 + usage 捕获 + 计量落库
- [ ] **M3 流量治理**：Lua 令牌桶限流 + 多渠道路由 + 故障转移
- [ ] **M4 业务平台**：租户 / API Key / 渠道管理 / 配额 / 审计
- [ ] **M5 异步流水线**：文档上传 → 解析 → 嵌入 → 向量库
- [ ] **M6 压测与打磨**：压测报告、故障注入报告、上线

**验收状态**：代码与单元/集成测试（18 项通过）已验证，admin / gateway 两个服务的 `/healthz` 亦均已验证。Docker Compose 全栈已实际构建并启动：`docker compose up -d --build` 成功，两个服务镜像构建完成，五个服务全部启动，其中 MySQL / Redis / RabbitMQ 为 `healthy`，发布端口与约定一致；admin 与 gateway 的 `/healthz` 实测返回 `status: UP`（含 `redis`、`rabbit` 组件 `UP`）。`docker compose down` 正常退出，无残留容器。计划中的 M0 验收标准「`docker compose up` 起全栈，`/healthz` 通」已满足。

## M0 已知边界

M0 只交付地基，下面是有意划出的范围边界，不是缺陷清单：

- 转发端点只有 `POST /v1/chat/completions`，且**只支持 SSE**：非流式请求当前会返回 `200` 加空 body，而不是报错（非流式链路在 M1 补齐）。
- 该端点在 M0 **按设计不做鉴权**：API Key 鉴权、限流、配额、多渠道路由与计量属于 M1–M3。
- **上游错误状态码尚未透传**：上游的 `401` / `429` / `502` 目前对客户端表现为笼统的 `500`（M1 补齐），因此 `docs/CONVENTIONS.md` 中 `/v1/**` 的标准状态码描述的是目标契约，而不是当前的保证。
- `/healthz` 会返回组件明细且不鉴权，等鉴权落地后会一并收紧。

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
- RabbitMQ 管理台：<http://localhost:15672>

停止：`docker compose down`

### 方式二：本机运行

需要 JDK 21+（编译目标为 21）、Maven 3.9+（本仓库不使用 Maven wrapper），以及本机可访问的 MySQL / Redis / RabbitMQ。

用 Docker 只起基础设施前，**必须先有 `.env`**：`docker-compose.yml` 里的 `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` / `RABBITMQ_PASSWORD` 都是 `${VAR:?...}` 必填插值，缺少 `.env` 时任何 `docker compose` 命令都会立即报错退出。`.env.example` 的两项业务口令与 admin 端默认值一致（`application.yml` 的 `spring.datasource.password`、`spring.rabbitmq.password` 默认均为 `aihub`），直接复制即可同时满足 compose 与本机运行；若要改用其他口令，启动时用 `SPRING_DATASOURCE_PASSWORD` / `SPRING_RABBITMQ_PASSWORD` 覆盖即可。

```powershell
Copy-Item .env.example .env
docker compose up -d mysql redis rabbitmq   # 只起基础设施
mvn -B clean package   # 只想产出 jar 时加 -DskipTests
java -jar aihub-admin/aihub-web/target/aihub-web-0.0.1-SNAPSHOT.jar
java -jar aihub-gateway/target/aihub-gateway-0.0.1-SNAPSHOT.jar
```

本机模式下默认连 `127.0.0.1:3307` 的 MySQL、`127.0.0.1:6379` 的 Redis、`127.0.0.1:5672` 的 RabbitMQ；gateway 的上游地址由 `AIHUB_UPSTREAM_BASE_URL` 指定（默认 `http://127.0.0.1:11434`）。

## 测试

需要 Docker Desktop 处于运行状态（集成测试使用 Testcontainers 起真实容器）。

```powershell
mvn -B test
```

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
