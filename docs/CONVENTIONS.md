# 项目约定

本文件记录 aihub-platform 的团队约定。新增代码请与既有实现保持一致；确有必要偏离时，先在评审中说明理由。

## 1. 模块与包名

| 模块 | 包名前缀 | 职责 |
|---|---|---|
| `aihub-common` | `com.aihub.common` | 零依赖共享类型：响应体、错误码、业务异常，以及跨服务共用的密钥工具（`ApiKeyHasher` / `ApiKeyView` / `ApiKeyCacheCodec` / `InternalHmac`） |
| `aihub-dao` | `com.aihub.dao` | Entity、Mapper、Flyway 迁移脚本 |
| `aihub-service` | `com.aihub.service` | 业务服务 |
| `aihub-mq` | `com.aihub.mq` | 消息生产与消费 |
| `aihub-web` | `com.aihub.admin` | Controller、配置、启动类 |
| `aihub-gateway` | `com.aihub.gateway` | 数据面：鉴权、限流、路由、转发、计量 |

依赖方向严格单向：`aihub-web → aihub-service → aihub-dao`，`aihub-service → aihub-mq`，`aihub-web → aihub-mq`。`aihub-gateway` 只共享零依赖的 `aihub-common`，不得依赖 admin 的业务模块（`aihub-dao` / `aihub-service` / `aihub-mq` / `aihub-web`），以保持数据面可独立构建、独立部署、单独压测。

跨服务共享的工具一律放 `aihub-common`，**不要各写一份**：`ApiKeyHasher`（哈希与生成）、`ApiKeyView`（密钥视图与「是否可用」的判据）、`ApiKeyCacheCodec`（含公开常量 `CACHE_KEY_PREFIX`）、`InternalHmac`（内部调用签名）、`MeteringTopology` + `MeteringEventCodec` + `MeteringEvent`（计量链路的拓扑名与线格式，见第 6.5 节）。admin 与 gateway 都依赖它。

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
- 数据面 `/v1/**` 的响应带 `x-request-id`：由**网关**生成的 UUID（`RequestIdFilter`），也是计量事件的
  `request_id` 与 `request_log` 幂等键的一半。客户端自带的同名请求头**不回显**；上游返回的同名头
  **不透传**（同名两值无法共存，幂等键必须是网关自产的那个）。过滤器顺序是承重的：`RequestIdFilter`
  （`HIGHEST_PRECEDENCE + 50`）排在 `ApiKeyAuthFilter`（`+100`）之前，所以连 `401` / `404` 也带这个头。
  改这条链时注意：响应头用 `set` 写入、透传白名单用 `put` 回写，把 `x-request-id` 加回透传白名单会
  **静默覆盖**幂等键（已有回归测试钉住）。

## 4. 数据面错误契约

`/v1/**` 的错误一律是 OpenAI 兼容体（`GatewayErrors.serialize`），**不是** admin 的 `{code,message,data}` 信封：

```json
{"error":{"message":"...","type":"...","param":null,"code":"..."}}
```

客户端是各种 OpenAI SDK，它们按 `error.message` / `error.code` 取值；给它们套 admin 信封等于把数据面协议换成私有协议。字段取值约定：

| `code` | HTTP | `type` | 触发场景 | 由谁产出 |
|---|---|---|---|---|
| `invalid_api_key` | `401` | `invalid_request_error` | 缺 `Authorization`、格式不是 `Bearer <key_id>.<secret>`、key 不存在 / 已停用 / 已过期，以及**所有密钥来源都失败**（Redis 未命中且 admin 不可达 / 5xx，见第 5 节） | `ApiKeyAuthFilter` |
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
- **「所有来源都失败」与「key 不存在」在客户端不可区分（有意为之）**：Redis 未命中且 admin 不可达 / 超时 / 5xx / 配置错时，gateway 一律按「key 不存在」处理并回 `401 invalid_api_key` —— 这是 fail-closed 的代价，平台级故障对每个客户端都表现为「你的 key 错了」。二者的区分只落在 gateway 日志上：admin 回源失败打 **ERROR**（并区分「key 不存在」与传输 / 5xx），真正的「key 不存在」只打 DEBUG。

## 6. API Key 约定

- 客户端 bearer 的值是 `<key_id>.<secret>`：`key_id` 形如 `ak_` + 16 位小写字母/数字（总长 19），`secret` 是 32 字节随机数的 URL-safe Base64（无填充）。只有 `secret` 参与哈希。
- 库中只存 `SHA-256(secret)` 的小写十六进制（64 字符，`key_hash CHAR(64)`），**永不存明文**；用 `ApiKeyHasher.hash` 计算，admin 与 gateway 共用这一份实现。
- **明文只在铸造时打印一次**，之后无法取回；丢了只能重新铸一把。
- 铸造走 `ApiKeyMintRunner`（本地 CLI 路径，默认关闭）：`--aihub.mint-key.enabled=true`（容器里是 `AIHUB_MINT_KEY_ENABLED=true`）配合 `--aihub.mint-key.tenant-name` / `--aihub.mint-key.name` / `--aihub.mint-key.valid-days`。**它不是 HTTP 接口** —— 公网上不存在造密钥的入口。
- gateway 侧的解析顺序是 Caffeine（本地，30s）→ Redis（5m，key 用 `ApiKeyCacheCodec.CACHE_KEY_PREFIX`）→ admin 内部接口；任何一级故障都降级到下一级，**绝不能因为缓存故障而拒绝请求**。「是否可用」的判据只有一处：`ApiKeyView.usable()`。
- **Redis 是鉴权的信任源，不只是缓存**：gateway 把 Redis 的命中当作**权威结果**，命中即放行、不再回查 MySQL；因此任何能写 `aihub:apikey:<sha256(secret)>` 的对端都能伪造出一把可用的 API Key。Redis 必须与控制面同等级隔离保护（网络、凭据、访问审计）。同样的原因，密钥的**吊销 / 停用不会立刻生效**，要等缓存过期：本机 Caffeine ≤30s、集群 Redis ≤5m。
- `docker-compose.yml` 里的 Redis 是**本地开发**配置：无密码，宿主映射为 `127.0.0.1:6380:6379`（只有本机能连；容器之间仍走 `redis:6379`）。生产加固 —— `requirepass` 并把它接进两个服务的配置、以及网络隔离 —— 列为 **M3** 项，M1 只做最小收敛。
- 不要新增第二套 key 格式或第二个哈希实现；控制台的签发 / 列表 / 吊销接口属于 M4。

## 6.5 计量事件契约（gateway → admin）

- **链路**：网关不直连数据库（决策 A）。用量在网关侧捕获，经 RabbitMQ 投给 admin，由 admin 幂等落
  `request_log`。拓扑名与线格式的唯一真相都在 `aihub-common`：`MeteringTopology`（交换器 / 队列 / DLQ /
  content-type）与 `MeteringEventCodec`（分隔符文本，13 段）。**admin 是唯一的拓扑声明方**，网关只发布。
- **为什么不用 JSON**：`aihub-common` 在 main 作用域零依赖（不能引 Jackson），发布端与消费端必须共用
  同一份编解码；同一份 codec 也兼作网关磁盘 spool 的文件内容格式（转义掉 `\n`/`\r`，一条事件一个文件）。
- **幂等键是 `(request_id, created_at)`**，与 `uk_request_log_request_id` 一致。两个值都由**网关**在请求
  开始时各生成一次（`created_at` 截断到毫秒）并随事件投递；消费端**原样使用**。消费端若用自己的
  `now()`，每次重投都会写成新的一行（实测：`created_at` 差 1 毫秒即两行）。
- **投递语义是「至少一次 + 幂等消费」**：网关用 publisher confirm + persistent + mandatory 发布，失败
  （含 broker 不可用）先落**磁盘 spool**（`aihub.metering.spool-dir`），由 `@Scheduled` 定时补偿重投；
  内存队列（`aihub.metering.queue-capacity`）与 spool（`spool-max-files`）都有上界，任何丢弃都会
  `aihub.metering.dropped` +1 并打 ERROR。**绝不静默丢弃。**
- **消费者对不可解码载荷抛异常**（不是 log + return）：重试 3 次（指数退避）后 reject 进
  `aihub.metering.dlq`。只有 `DuplicateKeyException` 被吞（那是幂等重复，不是错误）。
- **`status` / `error_code` 取值**：`SUCCESS`（2xx 且拿到 usage）/ `ERROR`（上游非 2xx、连不上、中途断流、
  网关未预期异常）/ `CANCELLED`（客户端断连）；`error_code` 取 `upstream_http_<code>`、
  `upstream_unreachable`、`upstream_stream_error`、`usage_missing`、`client_disconnected`、`gateway_error`。
- **例外：「响应提交之前」的失败按未预期异常记账（M2 的已知分类缺口，先读这段再按 `error_code` 告警）**：
  `gateway_error` 覆盖的是「响应**尚未提交**时冒出来的异常」这条分支，**客户端在首个字节之前就中断**的情形
  也落在这里：响应提交之前 Reactor 无法可靠区分「客户端中断」与「网关真实故障」，代码因此不猜测、按未预期
  异常计。所以「客户端断连 ⇒ `CANCELLED` / `client_disconnected`」**只对响应已提交之后的断连成立**。
  运营含义：**看到 `gateway_error` 不能不加核对就当作网关故障告警** —— 必须先确认客户端侧没有对应的主动
  中断，否则会把客户端行为误报成网关故障。这是 brief 指定、M2 评审后保留的行为，不是待修的笔误；设计文档
  §9 的「客户端断连不计入错误告警」要照此理解。
- **拿不到 usage 时** `completion_tokens` 是**估算值**（1 个汉字 ≈ 0.6 token、1 个非汉字字符 ≈ 0.3 token，
  向上取整），`prompt_tokens` 记 0，并用 `error_code` 标出这个事实。M4 的账单/对账必须把带
  `usage_missing` / `client_disconnected` 的行当近似值处理。
- **M2 的已知缺口**：`api_key_id` 与 `channel_id` 恒为 `NULL`（共享的 `ApiKeyView` 里没有 `api_key` 数值
  主键；多渠道属 M3），鉴权关闭时 `tenant_id` 记哨兵 `0`。
- **`prompt_tokens = 0` 的语义是「未知」而不是「零」**：下游任何按 token 计费 / 对账的代码都必须先看
  `error_code`，不能把 0 当成真实用量。
- **捕获窗口是响应字节的尾部**（`aihub.metering.max-capture-bytes`，默认 1 MiB）：流式下 `usage` 在最后
  一帧，尾部窗口天然正确；**非流式**响应是一整块 JSON，超过窗口就会丢掉头部、JSON 不可解析，精确 usage
  **静默降级**成估算值。要调小这个上限，先确认非流式 body 仍能完整落在窗口内（`UsageCaptureTest` 里有
  这条底线）。「尾部保留 usage」是**流式**的性质，不要当成普遍保证。
- **磁盘 spool 假定单写者**：一个网关进程独占一个 spool 目录（`spool-max-files` 的上界建立在这个前提
  上）。多进程共享同一目录时文件名可能撞车，撞车会计入 `dropped`（不静默），但上界会失守。
- **计量是旁路，broker 的健康不是网关的健康**：网关主配置里 `management.health.rabbit.enabled: false`
  是**有意**的（评审过）—— broker 挂掉是已设计的降级状态，报成 DOWN 会让编排层重启一个正在正常转发的
  网关。运维信号是 `published` / `spooled` / `dropped` / `replayed` 四个 Micrometer 计数器（已注册，
  但 `/actuator/metrics` 没开放到 M6）+ ERROR 日志；**不要**把这个开关改回 `true`。
- **未经真实 broker 端到端验证的分支**：`mandatory` / publisher-returns 的「消息被退回算投递失败」这条
  分支只有单元测试撑着 —— 发布端在 gateway 模块，broker 夹具在 admin 这边，而 gateway 的测试不允许
  依赖 Docker、也不允许依赖 admin 的模块。改动它时请补单元级证据，别假设它被端到端覆盖了。

## 7. 数据库约定

- 字符集 `utf8mb4`，时间字段 `datetime(3)` 且按 UTC 存储。
- 表结构变更一律新增 `V{n}__{描述}.sql`，禁止修改已执行过的迁移脚本。
- `request_log` 按月分区；由于 MySQL 要求分区列出现在每个唯一索引中，其主键为 `(id, created_at)`，`request_id` 唯一键同样是 `(request_id, created_at)`。
- `request_log` 的月分区由运行时维护（`RequestLogPartitionMaintainer`）：启动补齐 + 每日 03:10 前推
  `aihub.metering.partition-months-ahead`（默认 2）个月。补建必须用
  `ALTER TABLE request_log REORGANIZE PARTITION pmax INTO (…, pmax)` —— 有 `pmax` 时 `ADD PARTITION` 会报
  `ERROR 1493`；`REORGANIZE` 还会把已经落进 `pmax` 的行按新边界重新分配。时间基准是 **UTC**。
  中间空洞（历史月份缺失）只告警不自动补（补它要搬已有数据，属运维决策）。因此**应用数据库账号需要
  `request_log` 的 `ALTER` 权限**。
- 分区边界只声明**上界**，最低的那个分区是下无界的：所以「时间戳早于最早分区」**不会**落库失败
  （2020 年的时间戳照样进 `p202609`）。真正会被判死信的是**超出 `DATETIME` 值域**的时间戳（`ERROR 1292`）。

## 8. 测试纪律

- 需要真实基础设施的测试必须继承 `AbstractIntegrationTest`（Testcontainers 单例容器），执行前确认 Docker Desktop 在运行。
- 纯 Web 层测试用 `@WebMvcTest`（gateway 侧用 `@WebFluxTest` / `WebTestClient`），不要为了省事拖起整个上下文。
- **`aihub-gateway` 的测试永远不允许依赖 Docker，也不允许要求有 broker 在跑**：数据面要能独立构建、
  独立测试。计量链路因此被设计成「发布端可替换」（内存投递替身），需要真 broker 的用例一律放在 admin 侧。
- **admin 的集成测试要真起容器**：本机必须让 Testcontainers 找到 Docker，即
  `DOCKER_HOST=tcp://127.0.0.1:2375`（用户级 `~/.testcontainers.properties` 里也写着同一个值；缺了它
  `AbstractIntegrationTest` 会直接 `IllegalStateException` 而不是静默跳过）。
- **断言一个异步副作用之前，必须先等它发生**（有界等待）：被观测的调用如果是「发后不管」的
  （例如 `aihub-gateway` 的 Redis 回填 `subscribeOn(...).subscribe()`），那么「它跑完了没有」与
  `block()` 返回的时刻**没有先后关系**。M2 收口时在这里踩过一次：`ApiKeyFilterContractTest` 的
  Redis 回填线程断言直接读一个 `AtomicReference`，热态重复调用下约 2/3 的轮次读到的还是 `null`。
  正确写法是有界等待 + 「等不到就带着原因变红」，这样既不赌调度、又不削弱断言。
- 同一个 bug 的修复必须先补一个会失败的测试。

## 9. 提交约定

- conventional commits：`feat:` / `fix:` / `test:` / `chore:` / `docs:` / `refactor:`。
- 每个里程碑完成后打 tag：`m0`、`m1`……
- 密钥、口令一律不进仓库，走环境变量或 `.env`。
