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

`/v1/**` **上由网关处理**的路径，错误一律是 OpenAI 兼容体（`GatewayErrors.serialize`），**不是** admin 的 `{code,message,data}` 信封：

> **已知缺口（2026-09-26 的 compose 全栈验收实测；M1/M2 起就有的形状，M3 未改动）**：`/v1/**` 下**没有处理器**的路径 / 方法**不走**这套体，返回的是 Spring 默认错误体 `{"timestamp","path","status","error","requestId"}`（仍带 `x-request-id`，因为 `RequestIdFilter` 排在最前）：实测 `POST /v1/models` → `405`、`POST /v1/embeddings` → `404`、`GET /v1/nope` → `404`。所以「`/v1/**` 一律 OpenAI 形状」这句话**只对网关实际处理到的路径成立**；今天**没有**为 404/405 注册 OpenAI 形状的处理器，补上它属未来里程碑。未改动生产代码，本轮只登记事实。

```json
{"error":{"message":"...","type":"...","param":null,"code":"..."}}
```

客户端是各种 OpenAI SDK，它们按 `error.message` / `error.code` 取值；给它们套 admin 信封等于把数据面协议换成私有协议。字段取值约定：

| `code` | HTTP | `type` | 触发场景 | 由谁产出 |
|---|---|---|---|---|
| `invalid_api_key` | `401` | `invalid_request_error` | 缺 `Authorization`、格式不是 `Bearer <key_id>.<secret>`、key 不存在 / 已停用 / 已过期（admin **权威地**说没有这把 key —— `AdminResolution.NOT_FOUND` —— 或给了一份 `usable()==false` 的视图）。**不含**「判不了」那一类，见下一行 | `ApiKeyAuthFilter` |
| `service_unavailable` | `503` | `api_error` | 网关**无法判定**本次 API Key 是否有效：`AdminResolution.UNAVAILABLE`（admin 不可达 / 超时 / 5xx / 响应畸形 / 内部签名失败，含 `aihub.internal.secret` 为空这种平台配置故障），或 `ApiKeyResolver` 打破了「永不返回空 Mono」的契约。消息逐字为「密钥服务暂时不可用：网关无法校验本次 API Key，请稍后重试」。**结论未知 ⇒ 绝不进缓存**（见第 5 节），控制面一恢复即自愈 | `ApiKeyAuthFilter` |
| `rate_limit_exceeded` | `429` | `rate_limit_error` | 租户 / API Key 超过限流策略（`rate_limit_policy` 的 qps/burst）。**降级到本机令牌桶时同样回 429**（降级 ≠ 放行） | `RateLimitFilter` |
| `model_not_found` | `404` | `invalid_request_error` | 请求的 `model` 在配置快照的 `model_route` 里没有任何可用候选（且没有遗留单渠道可回落） | `ChatRelayController` |
| `upstream_unreachable` | `502` | `api_error` | 连不上上游（`WebClientRequestException`）；上游的**业务**错误状态码不走这里 | `ChatRelayController` |
| ~~`internal_error`~~ | ~~`500`~~ | — | **这一行在当前实现里不可达，保留仅为说明设计意图。** `GatewayErrors.serialize` 的 catch 分支确实会吐出这个码，但 `GatewayErrors.write` 是先 `response.setStatusCode(status)`、再 `serialize(...)`，所以那条兜底体**只会以调用方原本要写出去的状态码**（`401` / `404` / `429` / `502` / `503`）出现，永远不会是 `500`。见下面「容易踩的规则」。 | `GatewayErrors` |

几条容易踩的规则：

- **上游状态码与响应体原样透传**：上游返回 `401` / `429` / `502` 时，客户端看到的就是上游的状态码与 body，网关不重写、不折叠成 `500`；只有「根本没连上上游」才是 `502 upstream_unreachable`。同理上游的 `Content-Type` 也原样拷贝（不解析），上游没发就不补默认值。
- **畸形请求头按 401 处理**，不按 400：密钥格式错误不是参数校验问题，回 400 会让客户端以为换个 body 就能通过。
- **`/v1/**` 目前没有全局 500 处理器**：网关自身未预期的异常落到 Spring WebFlux 的默认错误响应，形状由 `Accept` 决定（JSON 或 HTML 错误页），**不保证**是上面的 OpenAI 体。新增数据面错误码时请走 `GatewayErrors.write`，不要依赖默认处理。
- **上表的 `internal_error` 行不可达**：`GatewayErrors.write` 先设置调用方给的状态码、再调用 `serialize`，因此 `serialize` 的 catch 分支（它才写 `internal_error`）**只会复用调用方原本的状态码**。结果是一个 `429`/`502`/`401` 状态码配一具 `{"code":"internal_error"}` 的兜底体 —— 「500 + internal_error」这个组合在代码里没有任何路径能产生。它不是待修的行为（兜底体的意义是「连错误体都编不出来时仍然给客户端一个合法 JSON」），但**不要**再把它当成一个可触发的错误码来写文档或告警规则。
- admin 的 `{code,message,data}` 信封只属于 admin 自己的接口（含 `/internal/**` 的 401）。数据面不套用，admin 也不套用 OpenAI 形状。
- **设计文档 §9 的「流开始前失败 → 统一错误体 `{"code","message"}`」已被取代**：`/v1/**` 上**由网关处理**的错误体是上面的 OpenAI 形状（**例外是「没有处理器」的 404/405**，见本节开头登记的已知缺口）。M1 用官方 OpenAI Python SDK 验收过这条契约，给数据面套 admin 信封会让所有 SDK 的 `error.message` 取值路径同时失效 —— 那不是「按 spec 实现」，是回归。admin 与 `/internal/**` 仍然是 `{"code","message","data"}`。
- **`429` 是限流用户唯一该看的信号**：`rate_limit_exceeded` 附带 `Retry-After`（秒）、`Retry-After-MS`（毫秒，Azure 风格）与 IETF 的 `RateLimit-Limit` / `RateLimit-Remaining` 两类头（`limit, burst` 形状，见 6.6 节）。客户端退避请读 `Retry-After`，不要自己猜窗口。这些头**由网关自己的限流判定写入，不是「只在网关拒绝时」才出现**：放行响应同样带 `RateLimit-Limit` / `RateLimit-Remaining`（`RateLimitFilter.writeDecisionHeaders` 对放行与拒绝都写这两条），只有 `Retry-After` / `Retry-After-MS` 是拒绝专有。**网关不写 `RateLimit-Reset`** —— 令牌桶是惰性补充的，没有可上报的重置时刻；`ratelimit-reset` 在网关里只是透传白名单里的一个**上游**头名。上游返回的 `Retry-After` / `x-ratelimit-*` 属于透传白名单（第 3 节），两者语义相同、来源不同。

## 5. 内部接口约定（`/internal/**`）

控制面内部接口只给数据面调用，**永不暴露公网**（部署时在入口网关/安全组上直接拒绝该前缀）。当前唯一一个：`POST /internal/api-keys/resolve`。

- 请求必须带 `X-Internal-Timestamp`（epoch 秒）与 `X-Internal-Signature`；缺任一个或不合法一律 `401`。
- 签名算法只有一份实现：`com.aihub.common.internal.InternalHmac`（`HmacSHA256`）。签名内容为 `timestamp + "\n" + METHOD + "\n" + path`，METHOD 按 `Locale.ROOT` 转大写；输出十六进制小写。**body 不参与签名**（当前内部接口只传一个哈希，需要时再加）。
- 被签名的 `path` 是**应用内路径**（如 `/internal/api-keys/resolve`）：不含 `server.servlet.context-path`、已 URL 解码、已清理 `;jsessionid`。admin 侧用 `UrlPathHelper.getPathWithinApplication` 取路径再验签；gateway 侧必须对「base-url 之后追加的那段相对路径」签名，用 base-url 拼出来的带前缀路径永远验不过。
- 时间窗 ±300 秒，**防重放由调用方（`InternalAuthFilter`）负责**：`InternalHmac` 只比较签名，既不解析也不校验时间戳。
- 共享密钥来自 `aihub.internal.secret`（环境变量 `AIHUB_INTERNAL_SECRET`），两端必须配同一个值；密钥为空时 `verify` 一律返回 `false`（fail-closed），admin 侧会在启动日志里大声告警但仍允许启动。
- **改一处必须同时改另一处**：admin 的 `InternalAuthFilter` 与 gateway 的 `AdminClient.Http` 是这份契约的两端，任何一端改了路径、header 名、方法名或时间窗，另一端都要跟着改，并同步更新本节。
- **「判不了」与「key 不存在」对客是两种不同的答案（D4，项目所有者的裁决，推翻了本节早先「两者不可区分」那条）**：Redis 未命中且 admin 不可达 / 超时 / 5xx / 配置错时，gateway **不再**按「key 不存在」处理 —— 那条老决策把平台级故障对每个客户端都伪装成「你的 key 错了」，客户端（各种 OpenAI SDK）据此会去改密钥，是**错的反应**。**新裁决**：两种情况都仍然是**拒绝**，都走不到限流 / 路由 / 上游，客户端都拿不到 `200`，**安全姿态完全不变**；变的只是**诊断通道** —— admin **权威地**说没有这把 key → `401 invalid_api_key`（客户端该改密钥），我们**判不了** → `503 service_unavailable`（客户端该退避重试，OpenAI SDK 对 5xx 有内建重试）。因此「503 不是削弱鉴权」这句话是承重的：它不是放行，只是诚实。**日志仍然是运维区分两者的信号**：admin 回源失败打 **ERROR**（并区分「key 不存在」与传输 / 5xx），真正的「key 不存在」只打 DEBUG。
- **（D1 起）它们在缓存上必须区分**：`AdminResolution`（`com.aihub.gateway.admin`）把回源结果分成三态 —— `FOUND`（命中）/ `NOT_FOUND`（admin **权威地**说没有这把 key）/ `UNAVAILABLE`（超时 / 传输失败 / 5xx / 畸形 / 签名失败 = **结论未知**）。**只有 `FOUND` 与 `NOT_FOUND` 可以进缓存**（前者进本地 + Redis，后者只进本地负缓存，见第 6 节）；`UNAVAILABLE` **一次都不写**。把故障也当成「不存在」写进负缓存，等于把一次瞬时故障放大成 `aihub.auth.local-cache-ttl`（默认 30 秒）的固定拒绝 —— 那正是 D1。改这一段时注意：`AdminClient.resolve` 仍然返回 `Optional`（形状不变、`Optional.empty()` 仍然不可区分），要区分就必须走 `AdminClient.resolveOutcome`；网关的本地缓存（`ApiKeyResolver`）也自 D4 起直接缓存 `AdminResolution` —— 因为「权威否定」与「admin 给了一份已过期 / 已停用的视图」都是 `usable()==false`，用视图类型根本表达不出三态。
- **已知可用性缺口的修复记录（D1，2026-09-26 的 compose 全栈验收发现，本轮修复）**：验收时 Redis 停机会让 admin 侧 `ApiKeyService.resolve` 的「读缓存 + 回写缓存」各等一次 Redis 超时，于是 admin 的回答超过 gateway `AdminClientConfig` 给内部跳的 `responseTimeout(3s)`；gateway 把这条连接当传输失败、fail-closed 回 `401 invalid_api_key`，并把这次的 MISS 写进本地负缓存（约 30 秒），于是连 Redis 恢复以后同一把 key 也要等负缓存过期才回到 200。**修法（两半一起做）**：① admin 侧 —— `ApiKeyService` 在一次 Redis 访问失败后进入 **5 秒粘性降级**（窗口内读与写都不碰 Redis，只走 MySQL；同一次请求里的回写被跳过），并把 admin 的 `spring.data.redis.timeout` 从 `2s` 收到 `500ms`（admin 主代码里唯一的 Redis 消费者就是这个密钥缓存，另有 actuator 的 redis 健康指示器）；② gateway 侧 —— 见上一条，故障结果不进负缓存。**实测**（黑障 Redis + 真实 Lettuce）：`resolve` 从 4404ms 降到 811ms（未命中 2420ms → 804ms；故障后的第二个请求 4203ms → 1ms），gateway 侧「故障清除后同一把 key 立刻恢复」，并且「Redis 不可用 + 有效但未缓存的 key」在模块级端到端上会**打到 429 而不是 401**。**残余（D4 已改）**：admin 自身真的不可达 / 极慢时客户端**不再**看到 401 —— D4 把这条残余的对客状态码改成 **`503 service_unavailable`**（与设计文档 §9「MySQL 不可用 → admin 返回 503」的控制面故障口径一致），并且**仍然不缓存该结论**，因此控制面一恢复即自愈，不需要等负缓存过期。**2026-09-27 已在真实 compose 全栈（最终 HEAD `2aab02f`，镜像 ID 与运行中的容器逐一核对）上复验**：`stop redis` + 一把**从未用过**的 key 三次请求全部 200（窗口内**没有**任何 `AdminClient` fail-closed ERROR），限流降级后**仍然拒绝**（14 个**并发**请求 → 10×200 + 4×429，10 正好等于 key 级 `burst=10`，全程没有 401 / 503）；`stop admin` + 冷缓存 key → **503 `service_unavailable`**（无 `Retry-After`，约 2.05 秒返回），`start admin` 后**第一个**请求即恢复 200，而真正不存在的 key 仍是 **401 `invalid_api_key`**。**这 2.05 秒的构成本轮没有测清楚**（网关日志里同时有「Redis 命令超时」（网关侧 `spring.data.redis.timeout` 仍是 2 秒、本轮未改）与「到 admin 的连接在 2000 ms 后超时」两处 ~2 秒的等待，两者之和放不进 2.05 秒，所以**不要**把它当成「2 秒全是 Redis」或「2 秒全是 admin」）。**方法上的坑（必须知道）**：`stop redis` 之后**顺序** curl 做不成突发 —— 一个请求要赔多次 2 秒超时（实测约 7 秒/请求，16 个顺序请求花了 110 秒），在此期间本机令牌桶早按 5/秒补满，于是会得到「全部 200」的**假结论**；突发必须**并发**发。细节与原始输出见 `.superpowers/sdd/d1-fix-report.md`、`.superpowers/sdd/fault503-report.md` 与 `.superpowers/sdd/m3-acceptance.md` 第 11 节。

## 6. API Key 约定

- 客户端 bearer 的值是 `<key_id>.<secret>`：`key_id` 形如 `ak_` + 16 位小写字母/数字（总长 19），`secret` 是 32 字节随机数的 URL-safe Base64（无填充）。只有 `secret` 参与哈希。
- 库中只存 `SHA-256(secret)` 的小写十六进制（64 字符，`key_hash CHAR(64)`），**永不存明文**；用 `ApiKeyHasher.hash` 计算，admin 与 gateway 共用这一份实现。
- **明文只在铸造时打印一次**，之后无法取回；丢了只能重新铸一把。
- 铸造走 `ApiKeyMintRunner`（本地 CLI 路径，默认关闭）：`--aihub.mint-key.enabled=true`（容器里是 `AIHUB_MINT_KEY_ENABLED=true`）配合 `--aihub.mint-key.tenant-name` / `--aihub.mint-key.name` / `--aihub.mint-key.valid-days`。**它不是 HTTP 接口** —— 公网上不存在造密钥的入口。
- gateway 侧的解析顺序是 Caffeine（本地，30s）→ Redis（5m，key 用 `ApiKeyCacheCodec.CACHE_KEY_PREFIX`）→ admin 内部接口；任何一级故障都降级到下一级，**绝不能因为缓存故障而拒绝请求**（2026-09-26 的全栈验收实测这条在 Redis 停机时不成立：admin 的回源撞上 3 秒内部跳预算，请求在鉴权处就 fail-closed 成 `401` —— **该缺陷（D1）本轮已修**，见第 5 节最后两条；残余是 admin 自身真的不可达时客户端拿到 `503 service_unavailable` 而不是 401（D4 起的对客口径），并且**仍然不被负缓存**、控制面一恢复即自动恢复）。「是否可用」的判据只有一处：`ApiKeyView.usable()`。
- **Redis 是鉴权的信任源，不只是缓存**：gateway 把 Redis 的命中当作**权威结果**，命中即放行、不再回查 MySQL；因此任何能写 `aihub:apikey:<sha256(secret)>` 的对端都能伪造出一把可用的 API Key。Redis 必须与控制面同等级隔离保护（网络、凭据、访问审计）。同样的原因，密钥的**吊销 / 停用不会立刻生效**，要等缓存过期：本机 Caffeine ≤30s、集群 Redis ≤5m。
- `docker-compose.yml` 里的 Redis 是**本地开发**配置：无密码，宿主映射为 `127.0.0.1:6380:6379`（只有本机能连；容器之间仍走 `redis:6379`）。生产加固 —— `requirepass` 并把它接进两个服务的配置、以及网络隔离 —— **没有在 M3 做**（M3 已收口），与 README「已知边界」里那条同属**未来里程碑**的生产部署要求；M1 只做了最小收敛。
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
- **`api_key_id` 与 `channel_id` 自 M3 起有值**：前者来自共享 `ApiKeyView` 新增的数值主键
  （`ApiKeyCacheCodec` 的载荷因此是 **6 段**，旧载荷会被判为畸形 → 缓存未命中 → 回源重写，
  这是收敛而非故障），后者来自路由结果。鉴权关闭、或 exchange 里没有 key 视图时 `api_key_id` 是
  `NULL` 而 `tenant_id` 记哨兵 `0`。走**遗留单渠道兜底**时 `channel_id` 是 `Long.MIN_VALUE`
  哨兵（不是 NULL），因此「走了兜底路径」在 `request_log` 里可查。超长 `model`（>128 字符）**只在
  计量事件里被截断**，转发给上游的请求体逐字节不变。
- **「非超时」的上游中途中断在计量上仍与客户端断连不可区分（M3 的已知分类缺口）**：响应已提交之后
  上游把连接断掉（不是读超时、不是连不上）时，Reactor 给出的形状与「客户端跑掉」完全一样
  （`RelayAttempts.isUpstreamFailure` 按原因链只认超时与连接失败），因此那一行记的是
  `CANCELLED` / `client_disconnected`，而不是 `ERROR` / `upstream_stream_error`。
  `WireMockChannelFaultInjectionTest#midStreamBreakIsMeteredAsAClientDisconnect` 把这个缺口钉成了
  可执行事实。**按 `client_disconnected` 做客户端行为统计前必须知道它包含了这一部分上游故障。**
- **`client_disconnected` 的判定依赖 Netty 的写回路径**：中继把「上游响应体的订阅被取消」当作客户端
  断连的信号（M3 Task 10）。这条判定只在 **Netty**（生产用的反应式服务器）上成立；如果网关被部署到
  Servlet 模式的容器（Tomcat / Jetty），Servlet 的 async 完成回调会在**每次响应正常结束**时取消写-flush
  处理器，于是每一次成功响应都会被误记成客户端断连。网关的测试 classpath 因此显式把 Web 服务器工厂钉成
  Netty（`NettyWebServerTestAutoConfiguration` + `AihubGatewayApplicationTests` 的断言）——
  谁再把 servlet 容器带上测试 classpath，那条断言会先红，而不是让计量状态悄悄失真。

- **`request_log` 没有 `channel_id` / `api_key_id` 的索引**（V1 的既有形状，M3 不加迁移）：
  这两列是 M4 聚合（按渠道 / 按 Key 出账与告警）的前提，但那类查询在分区表上会走扫描 ——
  M4 做聚合时要一并决定加索引还是改成分区裁剪友好的查询。
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

## 6.6 渠道密钥与配置快照（admin → gateway）

- **渠道密钥是 AES-GCM 密文**：`channel.api_key_cipher` 存 `v{n}:{base64(nonce‖ciphertext+tag)}`，
  **自描述版本**。主密钥来自环境变量 `AIHUB_CHANNEL_MASTER_KEY`（格式 `v1:<base64 32 字节>[,v2:…]`），
  **不落库、不进镜像、不打日志**。加解密实现只有一份：`com.aihub.common.crypto`（JDK `javax.crypto`，
  仍然零第三方依赖）。密钥长度为 32 字节是**构造期**校验：16 字节会被当成「配错了」直接拒绝，
  而不是静默按 AES-128 用（那会让同一条密文在两种配置下读出两种结果）。
- **明文只在网关本地出现**：admin 只下发密文；gateway 拿到密文后用本地主密钥解密，再把明文注入到
  该渠道的上游请求头。明文**不跨越网络**、不进 admin 的任何日志/指标。轮换 = 「环境变量里同时放旧新两把
  → 解密旧版本重加密为新版本 → 确认没有行还指着旧版本 → 删掉旧密钥」。密文自描述版本让**回退**安全：
  把新密钥去掉，旧密文照样可解。
- **主密钥缺失两侧行为不同（有意）**：admin 是**写入路径**，`encrypt` 直接抛异常（静默写坏数据更糟）；
  gateway 是**请求路径**，解不开只返回空（该渠道被跳过），**照常启动**。
- **快照接口**：`GET /internal/config/snapshot`（HMAC 签名，`GET` + 应用内路径 + 时间戳；契约见第 5 节）。
  一次返回 `{version, generatedAtEpochMilli, defaultModel, channels[], routes[], ratePolicies[]}`。
  **本接口自己的 `defaultModel` 当前恒为 `null`**：admin 侧没有 `aihub.upstream.default-model`
  这个属性（`ConfigSnapshotService` 的构造参数默认空串 → 快照里写 `null`），而 gateway 侧虽然
  解析并保留了它，`ModelsController` 的模型集合用的是网关**自己的** `aihub.upstream.default-model`
  （`UpstreamProperties`），从来不读快照里的那个分量。因此不要把它当成一条「控制面可下发默认模型」
  的活链路 —— 它是为将来 M4 的控制台预留的字段，今天两端一个不填、一个不读。
  `version` 是**三张表 `updated_at` 的最大值**（epoch 毫秒），任何配置写入都会推进它。
  **已知缺口（M4 前无删除接口），且今天没有任何缓解措施**：`max(updated_at)` 会**回退** ——
  删掉最新更新的那一行（或删空）之后 version 变小，而网关的比对是严格的 `>`
  （`ConfigClient.rememberGood`、`ConfigCache` 的 Redis 版本比对都是 `>`），于是更旧的快照会被
  lastGood 记住并继续服务。`ConfigSnapshotService` **只是取最大值**，它**不会**检测、也不会告警
  版本回退或同一毫秒撞车（全类唯一的 WARN 是「同维度存在多条 ACTIVE 限流策略」，与版本无关）——
  换句话说这条缺口目前**没有任何观测手段**：M3 没有删除 API（只能手写 SQL），所以是潜在缺口而不是
  现网缺陷；M4 的渠道/策略 CRUD 落地时必须一并解决（高水位持久化，或把比对放宽到 `>=`）。
- **路由的候选来自 `model_route`**：`priority` **数字小的组优先**，组内按 `model_route.weight`
  **权重随机**（权重非正数按 1）；`channel.status != ACTIVE` 或渠道不可用的行被排除。
  熔断渠道在组内**排到最后**（不是删除）；所有候选都熔断时仍然放行最高优先级那一组（best-effort + WARN，
  并给 `aihub.route.all-broken` 计数 +1）。**`channel.models_json` 不参与路由**，`GET /v1/models` 的
  模型集合来自「`model_route` 里出现过的模型名 ∪ 遗留默认模型」。
- **故障转移只看两件事**：① 上游结果是否属于「可切换」（**429 与 5xx**；4xx 不切换、原样透传）；
  ② 响应是否**尚未提交**（`response.isCommitted()` 为假）。第二个条件是铁律：一旦有字节写回客户端，
  再切换就会把半截响应拼成脏数据 —— 这就是「仅在未输出任何 token 时允许切换」的机器形式。
  一次请求最多试 **3** 条候选（`RelayAttempts.MAX_ATTEMPTS`，首选 + 两条备用），上界由「候选列表截断」
  与「尝试循环结构上每条候选最多订阅一次」两半共同兑现。
- **熔断只由 429 触发**（Redis key `aihub:channel:circuit:{id}`，TTL **30 秒**，跨实例共享）；
  5xx 与超时只触发**当次**切换。Redis 不可用时退化为**本机**熔断表（单机近似）；标记**无论 Redis 写成功
  与否都镜像到本机表**，否则「Redis 接受了标记、随后这 30 秒内读不到」会让已熔断的渠道静默复活。
- **限流按 `tenant + api_key` 两个维度选策略**（与设计文档 §8.1 ② 的维度一致）：先取与本次请求
  `apiKeyId` 匹配的 key 级行（`rate_limit_policy.api_key_id = api_key.id`），没有才用该租户的租户级行
  （`api_key_id IS NULL`），都没有则用内置默认 `qps=10 / burst=20`。**每一维内部**多条 ACTIVE 时取
  `id` 最大的那条（表上没有唯一约束，M4 的控制台会强制单条生效）。非正的 qps/burst 一律回落到默认值。
  **桶的状态维度**是 `aihub:ratelimit:{tenantId}:{sha256(secret)}`（与策略维度是两件事，别混）；
  本机降级桶的键是 `local:ratelimit:{tenantId}:{hash}`（前缀只加一次）。
  鉴权关闭时没有数值主键 → `apiKeyId` 为 `null`，此时只按租户级判定（不是「不限流」）。
- **限流的响应契约**：超限回 `429` + 第 4 节的 OpenAI 错误体（`code=rate_limit_exceeded`，
  `type=rate_limit_error`），并带 `Retry-After`（秒）、`Retry-After-MS`（毫秒）、
  `RateLimit-Limit: {qps}, {burst}`、`RateLimit-Remaining`。
  **`RateLimit-Limit` 直接暴露生效的策略值**，因此「key 级覆盖有没有生效」在响应头里就能看见。
  **网关只写这四个头，没有 `RateLimit-Reset`**（放行响应带前两个，拒绝再加两个退避头）。
- **降级链（数据面永不因控制面故障整体不可用）**：Redis 不可用 → 限流退化为**本机令牌桶**
  （单机近似；多实例下实际放行量约为「策略 × 实例数」）**且照常拒绝**；熔断退化为**本机**熔断表；
  admin 不可达 → 继续用**陈旧快照**（Redis 或本地），完全没有快照时才回落到 `aihub.upstream.*`
  合成的**遗留单渠道**（其渠道 id 是 `Long.MIN_VALUE` 哨兵，会在 `request_log.channel_id` 里可见）。
  限流自身出故障（连本机桶都抛异常）时是 **fail-open**：记 `aihub.ratelimit.fail_open` 并放行 ——
  「限流组件坏了」不该让整个数据面 500。
- **配置快照的读取是三级 + 两级缓存**：Caffeine（本机，30 秒）→ Redis（10 分钟）→ admin；
  本地命中也会做一次 **Redis 版本比对**（M3 没有 Pub/Sub 发布方，这是唯一的跨实例收敛手段）。
  同一次缺失由 **singleflight** 合并成一次回源；回源失败/超时后进入 **cooldown**（`aihub.config.refresh-cooldown`，
  默认 5 秒）以请求速率遏制控制面；两级缓存都空且 admin 不可达时，服务**内存里的 last-good 快照**
  （永不过期，取版本更高的那个），最后才是遗留单渠道。**热生效延迟不能按本机的 30 秒 TTL 来读 —— 共享 Redis 条目才是实际的上界**（2026-09-26 的
  compose 全栈验收实测，`.superpowers/sdd/m3-acceptance.md` 第 7 节）：本地副本 30 秒过期之后，网关读到的
  是 Redis 里那份**同版本的旧快照**（version 相等 → 采用它），只有共享条目也消失（`snapshotTtl` 默认
  **10 分钟**到期、或 Redis 不可用）才会回源 admin。实测：改 `channel.base_url` 后 **101 秒**新快照仍
  不可见（用一条只读的 `probe-model` 路由做探针），删掉 `aihub:config:snapshot` 之后 **15 秒**内才收敛。
  **部署含义：只要共享条目还在，控制面的一次变更最长要约 10 分钟才到达数据面**，除非运维刷新或删除该
  条目 —— M3 没有主动刷新共享条目的手段，按「30 秒热生效」操作会等错时间。
- **`GET /v1/models` 的冷缓存读可能回源一次**：它读的是同一个 `ConfigClient`，缓存冷/过期时会**同步**
  回源 admin（有 5 秒上界），因此这个端点的首字节延迟在冷启动时可能达到秒级。生产默认
  `aihub.ratelimit.enabled=true`，请求链在限流过滤器里已经被切到 `boundedElastic`；**网关测试的默认是
  false**，于是 Relay 路径上的同一次配置读会落在 Netty 事件循环上 —— 「生产调度」与「测试调度」不是同一个，
  改动这条链时要两边都想一遍。
- **未鉴权的 `/v1/**` 请求不会被限流**：鉴权过滤器（`+100`）排在限流过滤器（`+150`）之前，
  没有合法 key 的请求在鉴权处就 401 了，根本走不到限流。因此「未鉴权洪峰」不在这套限流的保护范围内。
- **404 走的是 `gateway_error`**：`model_not_found`（没有可用候选）在控制器入口就返回，
  计量按「未预期错误」记 `ERROR` / `gateway_error` —— 也就是说一个**客户端**问题会抬高网关的错误计数。
  按 `gateway_error` 告警时要先看 HTTP 状态码是不是 404。
- **限流与配额是两件事**：`RateLimitFilter` 管 QPS/burst（丢弃是暂时的、下个窗口自动恢复）；
  配额（§6.2）管余额（扣减是持久的）。**不要把 429 `rate_limit_exceeded` 与未来的 `QUOTA_EXCEEDED`
  混为一谈**；配额整体属 M4，M3 不碰 `quota` 表。
- **API Key 的吊销 / 停用延迟是显式接受的**：本机 Caffeine ≤30s、集群 Redis ≤5m。M3 **不加**
  吊销广播，也**不写** Pub/Sub 监听器：§6.3 的 Pub/Sub 失效只针对**配置快照**，而 M3 没有配置写入方
  （发布端不存在），为一个不存在的发布端写监听器只会得到一条永远不触发的代码路径。真正的收敛手段是
  M4 的吊销接口 + 显式 `DEL`。
- **dev-only 的渠道 seeder**：`aihub.demo-seed.enabled`（`AIHUB_DEMO_SEED_ENABLED`）**默认关闭**，
  且关掉时**不创建任何 bean**；打开时还需要 `AIHUB_CHANNEL_MASTER_KEY` 与两个 demo 上游 base-url
  （demo 密钥**没有默认值**，必须在环境里给），否则**在任何写库动作之前**就抛异常。它是本地联调工具，
  **不是**初始化数据的手段。
- **admin 的快照装配不做版本回退检测，也不为此告警**（更正：本文件此前在这里承诺过一条不存在的
  WARN）。`ConfigSnapshotService.currentVersion()` 只是对三张表各取一次 `max(updated_at)` 再取最大值
  —— 它**没有**「上一次的 version」这个概念，因此既发现不了回退、也发现不了同一毫秒撞车。
  该类唯一的 WARN 与版本无关：同一 `tenant + api_key` 维度存在**多条 ACTIVE 限流策略**时，
  按维度各告警一次。因此 6.6 节登记的版本回退缺口今天**没有任何缓解措施**（既无高水位、也无告警），
  关掉它是 **M4** 的条目。看到上面那条 WARN 时先查是不是有人直接改了库。

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
- **网关测试的 Web 服务器被显式钉成 Netty**：Boot 3.5 按 Tomcat → Jetty → Undertow → Netty 的顺序
  `@Import` 反应式服务器工厂，每个都是 `@ConditionalOnMissingBean`，**第一个 classpath 上成立的胜出**。
  只要测试 classpath 上出现 servlet 容器（M3 引入 WireMock 时就发生过：它的 Jetty 12 绑定带进了
  `jetty-ee10-servlet`），整个网关测试套件就会静默改跑「Servlet 模式下的 Jetty」，响应写回路径因此变化
  （详见 6.5 的 `client_disconnected` 那条）。`NettyWebServerTestAutoConfiguration` +
  `AihubGatewayApplicationTests#theGatewayTestContextRunsOnNettyNotOnAServletContainer` 是这条约束的
  实现与守卫：**不要删它们，也不要为了绕开某个测试依赖而把断言放宽**。
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
