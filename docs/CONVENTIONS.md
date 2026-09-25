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

跨服务共享的工具一律放 `aihub-common`，**不要各写一份**：`ApiKeyHasher`（哈希与生成）、`ApiKeyView`（密钥视图与「是否可用」的判据）、`ApiKeyCacheCodec`（含公开常量 `CACHE_KEY_PREFIX`）、`InternalHmac`（内部调用签名）。admin 与 gateway 都依赖它。

## 2. 端口

admin `8081`；gateway `8080`；RabbitMQ `5672`（管理台 `15672`）。数据库与缓存要区分**宿主端口**与**容器内端口**：

| 服务 | 宿主端口 | 容器内端口 | 原因 |
|---|---|---|---|
| MySQL | `3307` | `3306` | 宿主机原生 MySQL 占用 3306 |
| Redis | `6380` | `6379` | 宿主机原生 Redis 占用 6379 |

容器之间互访一律用**容器内端口**（compose 里就是 `mysql:3306`、`redis:6379`）；从宿主机直连 compose 里的 Redis 要用 `6380`（本机直跑服务时覆盖 `SPRING_DATA_REDIS_PORT=6380`）。**不要**把 `redis` 的宿主映射改回 6379。

## 3. 接口约定

- 所有 admin 接口返回 `{"code","message","data"}`；`code` 取 `ErrorCode` 枚举名，成功时固定为字符串 `"OK"`（由 `ApiResponse.ok` 产出）。
- 业务错误直接 `throw new BizException(ErrorCode.X, "说明")`，由 `GlobalExceptionHandler` 统一转换，不要自己拼响应体。
- 健康检查统一为 `/healthz`（Actuator health 端点映射而来），不带鉴权。
- 数据面接口（`/v1/**`）遵循 OpenAI 兼容协议；错误体形状与 admin 信封**严格区分**，见第 4 节。

## 4. 数据面错误契约

`/v1/**` 的错误一律是 OpenAI 兼容体（`GatewayErrors.serialize`），**不是** admin 的 `{code,message,data}` 信封：

```json
{"error":{"message":"...","type":"...","param":null,"code":"..."}}
```

客户端是各种 OpenAI SDK，它们按 `error.message` / `error.code` 取值；给它们套 admin 信封等于把数据面协议换成私有协议。字段取值约定：

| `code` | HTTP | `type` | 触发场景 | 由谁产出 |
|---|---|---|---|---|
| `invalid_api_key` | `401` | `invalid_request_error` | 缺 `Authorization`、格式不是 `Bearer <key_id>.<secret>`、key 不存在 / 已停用 / 已过期 | `ApiKeyAuthFilter` |
| `upstream_unreachable` | `502` | `api_error` | 连不上上游（`WebClientRequestException`）；上游的**业务**错误状态码不走这里 | `ChatRelayController` |
| `internal_error` | `500` | `api_error` | 兜底：连错误体本身都序列化失败时（`GatewayErrors.serialize` 的 catch 分支） | `GatewayErrors` |

三条容易踩的规则：

- **上游状态码与响应体原样透传**：上游返回 `401` / `429` / `502` 时，客户端看到的就是上游的状态码与 body，网关不重写、不折叠成 `500`；只有「根本没连上上游」才是 `502 upstream_unreachable`。同理上游的 `Content-Type` 也原样拷贝（不解析），上游没发就不补默认值。
- **畸形请求头按 401 处理**，不按 400：密钥格式错误不是参数校验问题，回 400 会让客户端以为换个 body 就能通过。
- **`/v1/**` 目前没有全局 500 处理器**：网关自身未预期的异常（不是上面这三个码）落到 Spring WebFlux 的默认错误响应，形状由 `Accept` 决定（JSON 或 HTML 错误页），**不保证**是上面的 OpenAI 体。新增数据面错误码时请走 `GatewayErrors.write`，不要依赖默认处理。
- admin 的 `{code,message,data}` 信封只属于 admin 自己的接口（含 `/internal/**` 的 401）。数据面不套用，admin 也不套用 OpenAI 形状。

## 5. 内部接口约定（`/internal/**`）

控制面内部接口只给数据面调用，**永不暴露公网**（部署时在入口网关/安全组上直接拒绝该前缀）。当前唯一一个：`POST /internal/api-keys/resolve`。

- 请求必须带 `X-Internal-Timestamp`（epoch 秒）与 `X-Internal-Signature`；缺任一个或不合法一律 `401`。
- 签名算法只有一份实现：`com.aihub.common.internal.InternalHmac`（`HmacSHA256`）。签名内容为 `timestamp + "\n" + METHOD + "\n" + path`，METHOD 按 `Locale.ROOT` 转大写；输出十六进制小写。**body 不参与签名**（当前内部接口只传一个哈希，需要时再加）。
- 被签名的 `path` 是**应用内路径**（如 `/internal/api-keys/resolve`）：不含 `server.servlet.context-path`、已 URL 解码、已清理 `;jsessionid`。admin 侧用 `UrlPathHelper.getPathWithinApplication` 取路径再验签；gateway 侧必须对「base-url 之后追加的那段相对路径」签名，用 base-url 拼出来的带前缀路径永远验不过。
- 时间窗 ±300 秒，**防重放由调用方（`InternalAuthFilter`）负责**：`InternalHmac` 只比较签名，既不解析也不校验时间戳。
- 共享密钥来自 `aihub.internal.secret`（环境变量 `AIHUB_INTERNAL_SECRET`），两端必须配同一个值；密钥为空时 `verify` 一律返回 `false`（fail-closed），admin 侧会在启动日志里大声告警但仍允许启动。
- **改一处必须同时改另一处**：admin 的 `InternalAuthFilter` 与 gateway 的 `AdminClient.Http` 是这份契约的两端，任何一端改了路径、header 名、方法名或时间窗，另一端都要跟着改，并同步更新本节。

## 6. API Key 约定

- 客户端 bearer 的值是 `<key_id>.<secret>`：`key_id` 形如 `ak_` + 16 位小写字母/数字（总长 19），`secret` 是 32 字节随机数的 URL-safe Base64（无填充）。只有 `secret` 参与哈希。
- 库中只存 `SHA-256(secret)` 的小写十六进制（64 字符，`key_hash CHAR(64)`），**永不存明文**；用 `ApiKeyHasher.hash` 计算，admin 与 gateway 共用这一份实现。
- **明文只在铸造时打印一次**，之后无法取回；丢了只能重新铸一把。
- 铸造走 `ApiKeyMintRunner`（本地 CLI 路径，默认关闭）：`--aihub.mint-key.enabled=true`（容器里是 `AIHUB_MINT_KEY_ENABLED=true`）配合 `--aihub.mint-key.tenant-name` / `--aihub.mint-key.name` / `--aihub.mint-key.valid-days`。**它不是 HTTP 接口** —— 公网上不存在造密钥的入口。
- gateway 侧的解析顺序是 Caffeine（本地，30s）→ Redis（5m，key 用 `ApiKeyCacheCodec.CACHE_KEY_PREFIX`）→ admin 内部接口；任何一级故障都降级到下一级，**绝不能因为缓存故障而拒绝请求**。「是否可用」的判据只有一处：`ApiKeyView.usable()`。
- 不要新增第二套 key 格式或第二个哈希实现；控制台的签发 / 列表 / 吊销接口属于 M4。

## 7. 数据库约定

- 字符集 `utf8mb4`，时间字段 `datetime(3)` 且按 UTC 存储。
- 表结构变更一律新增 `V{n}__{描述}.sql`，禁止修改已执行过的迁移脚本。
- `request_log` 按月分区；由于 MySQL 要求分区列出现在每个唯一索引中，其主键为 `(id, created_at)`，`request_id` 唯一键同样是 `(request_id, created_at)`。

## 8. 测试纪律

- 需要真实基础设施的测试必须继承 `AbstractIntegrationTest`（Testcontainers 单例容器），执行前确认 Docker Desktop 在运行。
- 纯 Web 层测试用 `@WebMvcTest`（gateway 侧用 `@WebFluxTest` / `WebTestClient`），不要为了省事拖起整个上下文。
- 同一个 bug 的修复必须先补一个会失败的测试。

## 9. 提交约定

- conventional commits：`feat:` / `fix:` / `test:` / `chore:` / `docs:` / `refactor:`。
- 每个里程碑完成后打 tag：`m0`、`m1`……
- 密钥、口令一律不进仓库，走环境变量或 `.env`。
