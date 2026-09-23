# 项目约定

本文件记录 aihub-platform 的团队约定。新增代码请与既有实现保持一致；确有必要偏离时，先在评审中说明理由。

## 1. 模块与包名

| 模块 | 包名前缀 | 职责 |
|---|---|---|
| `aihub-common` | `com.aihub.common` | 零依赖共享类型：响应体、错误码、业务异常 |
| `aihub-dao` | `com.aihub.dao` | Entity、Mapper、Flyway 迁移脚本 |
| `aihub-service` | `com.aihub.service` | 业务服务 |
| `aihub-mq` | `com.aihub.mq` | 消息生产与消费 |
| `aihub-web` | `com.aihub.admin` | Controller、配置、启动类 |
| `aihub-gateway` | `com.aihub.gateway` | 数据面：鉴权、限流、路由、转发、计量 |

依赖方向严格单向：`aihub-web → aihub-service → aihub-dao`，`aihub-service → aihub-mq`，`aihub-web → aihub-mq`。`aihub-gateway` 只共享零依赖的 `aihub-common`，不得依赖 admin 的业务模块（`aihub-dao` / `aihub-service` / `aihub-mq` / `aihub-web`），以保持数据面可独立构建、独立部署、单独压测。

## 2. 端口

admin `8081`；gateway `8080`；MySQL 宿主机 `3307`（容器内 `3306`）；Redis `6379`；RabbitMQ `5672`（管理台 `15672`）。

## 3. 接口约定

- 所有 admin 接口返回 `{"code","message","data"}`；`code` 取 `ErrorCode` 枚举名，成功时固定为字符串 `"OK"`（由 `ApiResponse.ok` 产出）。
- 业务错误直接 `throw new BizException(ErrorCode.X, "说明")`，由 `GlobalExceptionHandler` 统一转换，不要自己拼响应体。
- 健康检查统一为 `/healthz`（Actuator health 端点映射而来），不带鉴权。
- 数据面接口（`/v1/**`）遵循 OpenAI 兼容协议，错误响应使用标准 HTTP 状态码，不套用 admin 的响应体。

## 4. 数据库约定

- 字符集 `utf8mb4`，时间字段 `datetime(3)` 且按 UTC 存储。
- 表结构变更一律新增 `V{n}__{描述}.sql`，禁止修改已执行过的迁移脚本。
- `request_log` 按月分区；由于 MySQL 要求分区列出现在每个唯一索引中，其主键为 `(id, created_at)`，`request_id` 唯一键同样是 `(request_id, created_at)`。

## 5. 测试纪律

- 需要真实基础设施的测试必须继承 `AbstractIntegrationTest`（Testcontainers 单例容器），执行前确认 Docker Desktop 在运行。
- 纯 Web 层测试用 `@WebMvcTest`，不要为了省事拖起整个上下文。
- 同一个 bug 的修复必须先补一个会失败的测试。

## 6. 提交约定

- conventional commits：`feat:` / `fix:` / `test:` / `chore:` / `docs:` / `refactor:`。
- 每个里程碑完成后打 tag：`m0`、`m1`……
- 密钥、口令一律不进仓库，走环境变量或 `.env`。
