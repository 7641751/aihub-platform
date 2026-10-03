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
| `insufficient_quota` | `429` | `insufficient_quota` | 周期配额用尽：`QuotaFilter` 按估算值在 Redis 上**预扣**时被 Lua 判为余额不足（`code` 与 `type` **同为** `insufficient_quota`，与限流那行的 `type=rate_limit_error` 不同）。**限流（`rate_limit_exceeded`）是"太快"，配额（`insufficient_quota`）是"这个周期的量用完了"** —— 两者都是 429，但**语义、判据、可否靠等待自动恢复都不同**：限流等一个窗口就好，配额要等下个周期（或控制面调额）。**Redis 不可用时配额**放行**（D7 fail-open），只有兜底路径（`QuotaFallback`）能在 Redis 故障期间如实拒绝超预算租户 | `QuotaFilter` |
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
- **API Key 的吊销 / 停用延迟（M4 后已收窄，2026-10-02 实测改写）**：控制台吊销 / 停用 / 删除后**共享层立即失效**
  —— admin 在**事务体内**显式 `DEL` 掉 `aihub:apikey:<sha256(secret)>`（`ApiKeyAdminService.evictSharedCache`；
  `DEL` 失败只 WARN、不影响主流程，见该方法的注释）。⇒ **唯一残留的延迟是网关本机 Caffeine ≤30s**（每个实例各自过期一次），
  **不再**是「集群 Redis ≤5m」—— 本文下面/上面若有「≤5m」的旧说法，以本条为准。M3 **不加**
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

## 6.7 配额：预扣 → 校正 → 对账（2026-10-02，M4 Task 12/13/15）

三段各司其职，**互相不越界**：

1. **预扣（数据面，gateway `QuotaFilter`）**：按「估算 prompt token + `max_tokens`」在 Redis 上用 **Lua 原子**预扣
   （`QuotaScript`：一次往返内 `HMGET` → 判定 → `HSET` 写回 → `PEXPIRE`）。余额不足 ⇒ 数据面
   **429 `insufficient_quota`**（见 §4 的表；`code` 与 `type` **同为** `insufficient_quota`，与限流的
   `type=rate_limit_error` 不同）。**估算值只是估算**，所以预扣量与真实用量必然有偏差，靠第 2 段校正。
2. **校正（数据面，gateway `QuotaCorrector`）**：拿到上游真实 `usage` 后把差额**还回去**。
   ⚠️ **`adjust` 是非幂等的**：重复调用会重复加减 —— 它只能靠「一次请求一次校正」与下面第 3 段的对账兜底，
   **不要**把它当成可重放的补偿事务。
3. **对账（控制面，admin 02:00 UTC `QuotaReconciliationJob`）**：按 `request_log` **幂等重算** `billing_daily`，
   并**只报告**偏差（偏差超过 `toleranceRatio` 才计一次 Micrometer 计数 + 一条审计）。
   ⚠️ **对账绝不改账（D12）**：它**不写** `quota.token_used` / `quota.request_used`；偏差如何处置是**人的决定**。

两条必记的边界：

- **`0` 限额 = 不限（D15）**：`token_limit = 0`（或 `request_limit = 0`）表示**该维度不限制**，不是"额度为零"。
  升级是惰性的：没有配额行 ⇔ 零额度 ⇔ 不限。
- **Redis 不可用时配额**放行**（D7，fail-open）**：这与限流「降级**仍拒绝**」是**相反**的取向 ——
  配额判的是"这个周期用了多少"，放行只可能造成超额；限流判的是"现在多快"，放行会直接压垮上游。
  想在 Redis 故障期间仍如实拒绝超预算租户，靠 `QuotaFallback`（admin 兜底权威判余额）；关掉它 = 回到纯 fail-open。

> **设计文档 §6.2 写的 `429 QUOTA_EXCEEDED` 已被本节的 `insufficient_quota` 取代**（数据面契约以 §4 的表为准）。
> 注意 **admin 信封**里的 `ErrorCode.QUOTA_EXCEEDED` **仍然存在**，那是 **admin 侧的码**（`/api/**` 的 `code`），
> **不要**与 `/v1/**` 上发给 OpenAI SDK 的 `error.code` 混为一谈。

## 7. 数据库约定

- 字符集 `utf8mb4`，时间字段 `datetime(3)` 且按 UTC 存储。
- 表结构变更一律新增 `V{n}__{描述}.sql`，禁止修改已执行过的迁移脚本。
  **迁移的数量由 `SchemaMigrationTest` 显式钉住**（`flywayAppliesExactlyTwoMigrations`：断言
  `hasSize(2)` 且 `version` 恰好是 `["1","2"]`）：M4 加入第二条（`V2__m4_console.sql`）是**有意的**，
  并**已同步改过那条断言**。⇒ **任何人再加一条迁移，必须同时改 `SchemaMigrationTest` 的期望值**，否则该用例会红 ——
  这是**不让人偷偷加表**的闸门，**不要**为了使它变绿去放宽那条断言。
- `request_log` 按月分区；由于 MySQL 要求分区列出现在每个唯一索引中，其主键为 `(id, created_at)`，`request_id` 唯一键同样是 `(request_id, created_at)`。
- `request_log` 的月分区由运行时维护（`RequestLogPartitionMaintainer`）：启动补齐 + 每日 03:10 前推
  `aihub.metering.partition-months-ahead`（默认 2）个月。补建必须用
  `ALTER TABLE request_log REORGANIZE PARTITION pmax INTO (…, pmax)` —— 有 `pmax` 时 `ADD PARTITION` 会报
  `ERROR 1493`；`REORGANIZE` 还会把已经落进 `pmax` 的行按新边界重新分配。时间基准是 **UTC**。
  中间空洞（历史月份缺失）只告警不自动补（补它要搬已有数据，属运维决策）。因此**应用数据库账号需要
  `request_log` 的 `ALTER` 权限**。
- 分区边界只声明**上界**，最低的那个分区是下无界的：所以「时间戳早于最早分区」**不会**落库失败
  （2020 年的时间戳照样进 `p202609`）。真正会被判死信的是**超出 `DATETIME` 值域**的时间戳（`ERROR 1292`）。
- **`datetime(3)` 必须映射成 Java `LocalDateTime` 并在应用侧显式按 UTC 写入；不要用 `Instant`，也不要依赖
  `DEFAULT CURRENT_TIMESTAMP(3)`。** 三条实测理由 + 一条必须记住的数据含义变更（2026-09-29，Task 7 的
  独立评审在真容器里量出来的；第 2 条已按 2026-09-29 的第二次独立评审 `a14e209` 更正 ——
  原文把只在非 UTC 连接上出现的偏差写成了生产缺陷）：
  1. `DEFAULT CURRENT_TIMESTAMP(3)` 写的是**数据库会话时区**的墙上时间。Testcontainers 的 MySQL 基准恰好是
     UTC（`NOW(3) == UTC_TIMESTAMP(3)`），所以今天两张表看起来一致 —— 但一旦某台 MySQL 不是 UTC，同一个列
     就会出现**两种基准**（应用显式写 UTC、默认值写本地）。基准不能取决于数据库服务器的时区配置。
  2. MyBatis 用 `getTimestamp()` 读 `datetime`，而它按**哪个时区**解释那个墙上时间，取决于
     **JDBC 连接时区**：**只有连接时区解析成 LOCAL 时**（URL 不带 `serverTimezone` / `connectionTimeZone`，
     即 Testcontainers 返回的无参数 URL）驱动才按 **JVM 默认时区**解释它。本机 JVM 是 Asia/Shanghai，
     于是 `getTimestamp().toInstant()` 与实体里的 `Instant` 字段都**早了整整 8.0 小时**；最要命的后果是
     **时间范围查询**：驱动把边界整体推后一个时区偏移，命中的是**另一个窗口**的行 —— 复核实测同一个
     ±10 分钟窗口在无参数连接上 `LocalDateTime`(UTC) 绑定命中 **1/4** 行、`Timestamp` 绑定命中 **3/4** 行，
     而在 `serverTimezone=UTC` 连接上两种绑定选中的是**同一个窗口**（2026-09-30 复测：**3/4** 与 **3/4**，
     两条计数相等；`connectionTimeZone=UTC` 与 `connectionTimeZone=SERVER` 同样各是 3/4 与 3/4）。
     **计数依赖 fixture，所以把它一起写在这里**（产生上面数字的 4 行，2026-09-30 在本机 JVM=Asia/Shanghai
     的容器上复测）：一个临时表 `probe_t(id int primary key, v datetime(3), s varchar(64))`，四行写的是
     **同一个瞬时** `2026-01-01T12:00:00.123Z`，分别用 `setObject(LocalDateTime@UTC)` /
     `setTimestamp(Timestamp.from)` / `setObject(Instant)` / `setObject(LocalDateTime@JVM)` 绑定；查询是
     `select count(*) from probe_t where v >= ? and v <= ?`，边界 `FIXED±10min` 分别用 `LocalDateTime@UTC`
     与 `Timestamp` 绑定。第 4 行用的是 **JVM 的墙上时间**、任何方言下都不被换算（本机 = 20:00.123），
     所以无参数连接的 1/4 是「只有第 1 行落在那个 UTC 窗口里」、`Timestamp` 绑定的 3/4 是「另外三行」；
     在 `serverTimezone=UTC` 上四行塌成 12:00.123 / 12:00.123 / 12:00.123 / 20:00.123，两种绑定都选中前三行。
     **计数同时取决于方言与 JVM 时区**，只作形状举例、不作判据：UTC 的 JVM 上 LOCAL 类方言（无参数 /
     `serverTimezone=UTC` / `connectionTimeZone=UTC` / `=SERVER`）会塌成四行全在 12:00.123、两种绑定都是 4/4；
     而**钉死的非 UTC 时区不受 JVM 时区影响** —— `connectionTimeZone=Asia/Shanghai` 在 UTC 的 JVM 上是
     **2/4 与 2/4**、在 Asia/Shanghai 的 JVM 上是 **1 与 3**（2026-09-30 两次实测）。当年那个被推翻的 `4/4`
     **多半**就是某次 UTC JVM 上的运行结果，但那次运行**没有留下任何记录**，所以这里只登记为**推断，不写成出处**。
     **与 JVM 时区无关、可以当判据的那一条**（已固化成可重跑用例
     `com.aihub.admin.time.TimeBasisOffsetFixtureTest#onTheUtcConnectionBothBindingsSelectTheSameRows`）：
     **UTC 方言下两种绑定选中同一批行** —— 生产就是 UTC 方言，所以「承载方式不影响结果」在生产上成立。
     本节与其它处引用的 `+28800000` / `−18000000` / `0` 三个毫秒数、以及「最小非零偏移 60 分钟」，
     分别由仓库内的 `com.aihub.admin.time.TimeBasisOffsetFixtureTest`（三条位移用例，各自钉死一个
     `connectionTimeZone`）与 `com.aihub.admin.time.TimeZoneOffsetExtremesTest` 产生，都可用
     `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=…"` 重跑。
     **这不是「生产上正在发生的错误」**：发布的两个 URL 都钉了 `serverTimezone=UTC`
     （`application.yml:8`、`docker-compose.yml:65`），第二次独立评审在那条连接上实测**旧写法与新写法
     逐位相等**（`channel.updated_at` 与 `api_key.expire_at` 两个列的 `old − new` 都是 **0**）。
     真正的结论是**纪律**，不是「生产在错」：
     **① 连接时区必须显式钉死**（发布 URL 已经这么做，新环境照抄；新代码**不许**依赖它）；
     **② 基准写在代码里**：`LocalDateTime` 字段 + 显式 UTC 赋值
     （`LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)`，见 `RequestLogService`）读写对称、
     没有隐式转换，在任何连接方言与任何 JVM 时区下都给同一个答案。
  3. 所以新增时间列**照抄 `RequestLogService` 的写法**。写测试时**不要**用「拿数据库的钟和数据库的钟对」
     去证明**折常量**是对的 —— 那正好把这个错误盖住（Task 7 第一版就是这么写的，1 小时容差 + 双 DB 钟
     比较，全绿但错）。要断言「读回来的 `LocalDateTime` 按 UTC 折算成 `Instant` 后，与 `Instant.now()`
     相差在秒级」。**例外**：可以、而且**应该**用数据库**自己的 UTC 时钟**（`UTC_TIMESTAMP(3)`）去钉
     **列的基准** —— 它和上面那条是**两条独立**的信息：前者钉「列里存的是不是 UTC」，后者钉「应用折得
     对不对」。`ConfigSnapshotServiceTest` 现在两条都有，并且在会话时区不是 UTC 时额外要求这一格
     **不**落在 `NOW(3)`（会话时钟）附近。会话时区本身没有被应用钉住（`@@session.time_zone = SYSTEM`），
     这是已经登记的残余（独立评审 I-3）。
  4. **把 `expire_at` 的解释从「连接时区」改成「UTC」是一次**数据含义变更**，方向取决于那条连接的
     偏移符号**（2026-09-29 独立评审 I-4；2026-09-30 复测）**：已经由旧代码在**非 UTC 连接**上写进库的
     行，其 `api_key.expire_at` 存的是**那个连接时区的墙钟**；改用 UTC 解释之后，这些行的瞬时位移量
     `new − old` **等于该连接的偏移**（写这批行与升级后读它们的，实际是同一套环境）。同一台容器复测
     （同一格写 `2026-01-01T12:00:00.123Z`，旧 `Timestamp.toInstant()` 读 vs 新 `LocalDateTime`@UTC 读）：
     `connectionTimeZone=Asia/Shanghai`（+08:00）⇒ `new − old = +28800000 ms`，
     `connectionTimeZone=America/New_York`（该瞬时是 −05:00）⇒ `new − old = −18000000 ms`。因此：
     - **偏移为正（东半球）**：瞬时**后移** —— 已经过期的 key 在**该偏移那么长**的时间里仍然可用
       （fail-open，安全洞）；
     - **偏移为负（西半球）**：瞬时**前移** —— key 比预期**更早**过期（fail-closed，是功能回归而不是安全洞）。
     简式：**位移量 = 那条连接的偏移，方向由它的符号决定，最多一个连接偏移**（注意偏移本身随日期变，
     夏令时下纽约是 −04:00 而不是 −05:00）。**8 小时只是本机 Asia/Shanghai 的观测值，不是常量。**
     `config_version` / 配置表的 `updated_at` 同样被改读，版本跳变的方向由同一个符号决定：
     偏移为正 ⇒ 版本**向前**跳（相对无害）；偏移为负 ⇒ 版本**向后**跳 —— 那是**已登记的 M3 版本回退缺口**
     （网关用严格 `>` 比版本，见 README「已知边界」），**不是「无害」**。
     本仓库发布的两个 URL 都钉了 `serverTimezone=UTC`（偏移 0），因此**在发布配置下不存在这种行**
     （旧代码写进去的已经是 UTC）；但任何覆盖了 `SPRING_DATASOURCE_URL` 而没有带该参数、或升级前用非 UTC
     连接跑过的环境，都需要一次性处理，而且**办法是方向相关的**：先确定写那批行的连接时区（URL 上的
     `serverTimezone` / `connectionTimeZone`；不带参数时就是当时那个 JVM 的默认时区）在**那些行的时间点**上的
     偏移 `o`（分钟、带符号），再把列里的墙上时间搬回真实瞬时 ——
     `UPDATE api_key SET expire_at = expire_at - INTERVAL <o> MINUTE`（`o` **带符号**：西半球为负，
     实际就是往后加），或直接**重新签发**受影响的 key
     （更稳：行本身无法可靠区分基准，见 README「已知边界」的 M4 条目）。
- **`eq(column, null)` 恒不成立，而 MyBatis-Plus 不会替你忽略它**：
  `LambdaQueryWrapper.eq(AuditLogEntity::getTenantId, null)` 生成 `WHERE (tenant_id = ?)`、参数是 `null`
  ⇒ 恒为 UNKNOWN ⇒ **静默返回 0 行**（既不报错也不忽略条件）。实测：`eq(null)` 得 0 行，`isNull()` 得 1 行，
  裸 SQL `count(*) where tenant_id is null` = 1。凡是按**可选维度**（`tenant_id`、`api_key_id`）过滤的查询
  —— Task 8/9/10 的列表与 Task 11 的日志/审计查询都会遇到 —— **必须**用 `isNull()`/`isNotNull()`，绝不把
  `null` 交给 `eq`。这属于「静默给出错答案」那一类，比抛异常危险得多。

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
- **admin 集成测试的连接时区方言是显式选择，不是巧合**（2026-09-29 第二次独立评审的产物）：
  `AbstractIntegrationTest` 用 `TestContainers.utcFlavouredJdbcUrl()` 把 `spring.datasource.url` 钉成
  **生产方言**（`serverTimezone=UTC`，与 `application.yml:8` / `docker-compose.yml:65` 同类）；
  `com.aihub.admin.time.TimeBasisIsConnectionFlavourIndependentTest` 用
  `TestContainers.nonUtcFlavouredJdbcUrl()`（`connectionTimeZone=Asia/Shanghai` —— 一个**固定的非 UTC 区**）
  起一个**独立上下文**，专门证明时间基准与连接时区无关。在此之前测试 URL 是 Testcontainers
  返回的裸 URL ⇒ 落在 **LOCAL** 方言（= 跑测试的 JVM 默认时区）、与生产**相反**，而没有任何地方写下来，
  于是「RED 证据」看起来像一个生产缺陷（见 §7 第 2 条）。三个决定、它们的理由，外加两条登记：
  1. **主体套件跑生产方言**：它断言的是生产上会发生的行为；而且 UTC 钉死之后结果**不随跑测试的 JVM
     时区变化**（LOCAL 方言下本机 Asia/Shanghai 与一个 UTC 的 CI 镜像会得到不同的数字，套件不可复现）。
  2. **判别「代码依赖不依赖连接时区」的用例自己起一个非 UTC 方言的上下文**：
     那条性质只在连接时区不是 UTC 时可观测。**必须钉一个固定区，不能靠 LOCAL** —— LOCAL 的行为随 JVM
     默认时区变化，在 UTC 的机器/CI 镜像上与 UTC 行为完全一致，判别力**静默归零**（评审 I-1(b) 指出的
     盲区）。该类的方言用例把这件事量出来：固定区下 `Timestamp` 载体的读数恒定偏离 UTC 折 8 小时，
     而裸 URL（LOCAL）下的读数等于 JVM 时区的解释 —— 后者只作为「评审之前套件跑的是什么」的**记录**。
  3. **方言被断言，不会被静默移动**：`ConnectionTimeZoneFlavourTest` 读回
     `spring.datasource.url`（字符串级，任何 JVM 时区下都有效）、做驱动行为探针、并断言数据库**会话**
     时钟就是 UTC。将来谁删掉 `serverTimezone=UTC`，它会红，而不是让整个套件换一个方言继续跑。
  4. **已知覆盖边界（2026-09-30 登记）**：钉成生产方言之后，**主体套件（**21** 个继承
     `AbstractIntegrationTest` 的类）只跑 UTC**；唯一跑非 UTC 的是上面那个判别上下文，而它只覆盖**两条
     已知路径**（`ConfigSnapshotService.currentVersion()` 与 `ApiKeyService.mint` / `resolve`）。
     因此**将来第三个同类站点没有任何「顺带被跑到」的覆盖** —— 以前至少会在非 UTC 的开发者机器上偶然
     暴露（虽然没有任何断言），现在连那个偶然性也没有了。**新增时间列的人必须显式决定**：把它加进
     `TimeBasisIsConnectionFlavourIndependentTest`，否则「连接时区不是 UTC 时的行为」无人看着。
     **（数字订正 2026-09-30：这里原写"19 个"，复测为 **21** —— Task 8 与 Task 9 各新增一个集成类。产物 = 全仓 `Select-String 'extends AbstractIntegrationTest'` 命中 **21** 个文件，全部在 `aihub-web`；`TimeBasisIsConnectionFlavourIndependentTest` **不**继承 `AbstractIntegrationTest`（它是独立的 `@SpringBootTest` 上下文），因此不计入这 21。）**（二次订正 2026-10-01：按路径去重再计数为 **23** —— Task 10 新增 `RouteAndRateLimitAdminIntegrationTest`、Task 11 **未**新增集成类（探测用例并进既有的 `ChannelAdminIntegrationTest`）。产物 = 全仓 `Select-String 'extends AbstractIntegrationTest'` 按路径去重。）**
  5. **已知代价（2026-09-30 登记）**：那个独立上下文给**每个全量套件 JVM 增加第二个完整 Spring 上下文**
     ——第二套 Tomcat、第二套 `@RabbitListener` 消费者、第二份 `@Scheduled` 任务，共享同一组容器。
     今天全量绿（`aihub-web` 173/0；**2026-09-30 复测订正为 `aihub-common` 61 / `aihub-web` 198（0F/0E/0S）**，产物 `.hb2-logs/W05-admin-full.log` 与 `.m4t9fix-logs/R05-admin-full.log`；同一轮实测 `Tomcat started on port` = **7** ⇒ Task 9 及其修复轮**没有**新增 Spring 上下文），两个上下文写的是同一张表，也没有观测到互相干扰；但代价是真实的
     （全量运行多付一次上下文启动，且两套消费者/定时任务同时在跑），登记在此以免事后才发现。它换来的是
     「判别力在任何 JVM 时区下都成立」，这个交换仍值得，只是不要忘了它的价格。
  6. **已知代价（2026-09-30 登记，Task 8 的修复轮实测）**：`ChannelAdminIntegrationTest` 的
     `@TestPropertySource`（合成控制台签名密钥 + 双版本渠道主密钥）在套件里是**唯一**的属性集，因此它
     又 fork 出**一个完整的 Spring 上下文**。修复轮实测（命令 `mvn -B clean test -pl :aihub-web -am`，
     全量日志 `.m4t8fix-logs/F02-admin-full.log`）：同一个 JVM 里 `Tomcat started on port` 出现 **7** 次、
     `HikariPool-N - Start completed` 也出现 **7** 次 —— 即 7 个完整上下文，本任务贡献其中之一
     （自己的 Tomcat、Hikari 池、`@RabbitListener` 容器与 `@Scheduled` 任务，共享同一组容器）。
     代价与上面第 5 条同类；它换来的是「渠道/租户写路径的真 HTTP + 真加密 + 真 Redis 失效广播」这一层
     覆盖。登记在此，免得下一次「套件为什么这么慢」又被当成谜（Task 8 的独立评审也量到同样的 7 / 7，
     见被 gitignore 的 `.superpowers/sdd/m4-task-8-review-verification.log` §E）。
- **断言一个异步副作用之前，必须先等它发生**（有界等待）：被观测的调用如果是「发后不管」的
  （例如 `aihub-gateway` 的 Redis 回填 `subscribeOn(...).subscribe()`），那么「它跑完了没有」与
  `block()` 返回的时刻**没有先后关系**。M2 收口时在这里踩过一次：`ApiKeyFilterContractTest` 的
  Redis 回填线程断言直接读一个 `AtomicReference`，热态重复调用下约 2/3 的轮次读到的还是 `null`。
  正确写法是有界等待 + 「等不到就带着原因变红」，这样既不赌调度、又不削弱断言。
- 同一个 bug 的修复必须先补一个会失败的测试。
- **文档与注释里的每个数字都必须点名它的来源**（2026-09-30 第三次独立评审的结论）：连续三轮
  修正都在修上一轮写错的话时引入了新的错话，根因是同一个 —— **数字从上一份报告里抄，而产生它的
  fixture 谁都没有记下来**（`4/4`、`1/1`、`15 分钟` 都是这么来的）。纪律：**一个数字必须点名产生它的
  命令或工件**（探针、日志、用例），并且按那个名字**能重新量出来**；任何**继承**来的数字在再次写出去
  之前必须**重新测量**，测不了的就只能标成「继承、未复核」，**不许**当成事实复述。数字依赖 fixture 时，
  fixture（插了哪几行、用什么绑定）要跟数字写在一起。**这条与时间无关，适用于所有「实测值」「数量级」
  「N 倍」的表述。**
- **不可证伪的断言不算断言**（2026-10-01，Task 11 独立评审的产物）：`assertThat(body).doesNotContain("sk-…")`
  这类断言，若那个字符串**在任何实现下都不可能出现在响应里**（列不存在、投影里没有该字段、哨兵值从未入库），
  那它对**任何**生产变异都不会变红 —— 它给出的只是"看起来很严"的错觉。纪律：**每条断言都要能回答
  「哪个最小变异能让它红」**；答不出来就换成可证伪的形状（例如把"不含某字段"改成**响应 JSON 的字段集
  逐字等价**，于是"给视图加一个字段"立刻成为最小反证），否则只能降级成注释里的记录性说明。
  Task 11 实测：字段集断言在给 `RequestLogView` 加一个字段后精确变红，而原来的三条 `doesNotContain` 无任何变异能打红。
  同理，**边界断言的阈值必须与判据文字一致** —— 判据写"超时上限 3 秒"、断言却写 `isLessThan(8_000L)`，
  于是 5 秒的实现照样通过（Task 11 的 I-2 就是这么来的）。
- **子代理会话被中止可能留下未还原的变异 ⇒ 控制器必须在任何非正常结束之后复查工作树**（2026-10-01 实测）：
  一次窄口径复评的子代理跑到一半被中止（`code=10003`），它在 `aihub-admin/aihub-web/.../BillingController.java`
  里留下的 `// MUTANT-R1-V1`（`stat_date` 的 `.ge/.le` 已删）**没有还原**；是下一次复评的开工探测才发现并修回的。
  纪律：**任何子代理非正常结束之后**，继续之前必须先查四处 —— `git status --porcelain`、`git diff --stat`、
  生产文件 SHA256（对比该轮记录过的 pristine 值）、`git grep -n MUTANT -- '*.java'`；四处都干净才继续。
  这也是「变异前必须 `Copy-Item` 字节级备份、且备份写在被变异文件之外」的另一半理由：备份是唯一能证明还原正确的凭据。
- **并发「读或建」的正确写法（2026-10-01，Task 12 付费换来）**：`INSERT … ON DUPLICATE KEY UPDATE id = id`
  （原子、**不抛异常**、无锁等待面）+ **外层刻意不加 `@Transactional`**（这样第二次读是**新的一致性读视图**，
  能看见并发对手已提交的那一行）。反例（实测 8 线程稳定复现）：`catch DuplicateKeyException` 之后再用
  `SELECT … FOR UPDATE` 重读 —— 会 `Deadlock found when trying to get lock`；而且**即便不死锁**，
  REPEATABLE READ 下同一事务的普通重读**看不见**并发提交的那一行（一致性读视图在第一次 `SELECT` 返回 `null`
  时就已经固定），于是会「插入 → 撞唯一键 → 重读仍是 null → 再插入」循环。
- **测试容器里的 MySQL 用户没有 `PROCESS` 权限**（2026-10-01 实测）：`SELECT … FROM information_schema.innodb_trx`
  / `SHOW PROCESSLIST` 一律 `Access denied; you need (at least one of) the PROCESS privilege(s)`。
  ⇒ **不要**用它们做「某事务正在等行锁」的观测；如果找不到**无特权**的可观测量，就**不要伪造确定性**
  （禁用「睡够时间」），把该用例**移除并登记为残余**，同时说明残余风险由哪两条既有覆盖兜住。
- **子串黑名单断言的能力边界，以及对付变体的"计数不变量"**（2026-10-02，Task 16 的独立评审给的）：
  `doesNotContain("innerHTML")` 这类断言**能**证伪"代码里出现了被禁 token"，**不能**证伪
  ① "视图被掏空"（把 `submitX` 全清空、只留端点字面量 ⇒ 照样绿）、② "没接通"（删光 `addEventListener` ⇒ 照样绿）、
  ③ 未列入黑名单的同类危险 API（`insertAdjacentHTML`/`setAttribute("href", raw)`/`Range.createContextualFragment`）。
  ⇒ 写这类断言时**必须**同时给出**正向锚**（同一读路径上先 `contains(...)` 或 `isNotEmpty()`，否则"读空了"会真空通过），
  并**明确写下它证伪不了什么**，别把"黑名单全过"说成"安全"。
  **对付大小写/空白绕过**（`<SCRIPT>`、`<script >`）的便宜形状是**计数不变量**：
  `count(html.toLowerCase(), "<script") == count(lower, "<script src=\"/console/console.js\"")` ——
  多一个内联脚本、或唯一那个标签被改写，立刻不等。
- **前端的原生控件值不能直接透传给"要 UTC Instant 的服务端"**（2026-10-02，Task 16 的 B-1，真实缺陷）：
  `<input type="datetime-local">` 的值形如 `2026-10-02T15:30`（**无秒、无时区**），而 §7 的服务端契约要求
  **可被 `Instant.parse` 解析的带 `Z` 字面量**（解析失败 400）⇒ **填了也 400**；若前端还"值非空才带参数"，
  那么空输入时更是**必然 400**。⇒ 前端必须**无条件**带参数并**规范化**（补秒 + 补 `Z`；为空时给一个有意义的默认窗口），
  且这类"缺省即 400"的端点要在**页面侧**就给出默认值，而不是指望操作者知道服务端的必填规则。
- **派发被取消 ≠ 那个运行真的结束了；要假设"同一工作区里可能还有第二个运行"**（2026-10-03，M5 Task 5 实测）：
  同一个派发产生了**两个**子代理运行 —— 第一个跑了 **155 分钟**（`10:27:49`–`13:03:28`，期间有三段 **30/61/64 分钟**的静默）被 harness 以
  `Idle timeout - no output for too long` 取消；**第二个（peer）在控制器接手实现之后才开始**，它发现"另一个 agent 正在同一工作区实现 Task 5 并跑 Maven"，
  于是**停手、只写了 `CONFLICT-REPORT.txt`**（`.m5t5-logs/`），没有动手改文件。
  ⇒ **纪律**：① 派发被取消后，**先**查四处（工作树 / `MUTANT` 残留 / 陌生新文件 / 运行中的容器），**再**查**日志目录里有没有你（或你的派发）没写过的文件**；
  ② **绝不允许两个运行同时跑 Maven**：同一个模块的 `target/` 会被互相覆盖，测试结果可能是并发假象 ⇒ 拿到绿色之后，**在确认"没有 peer 在写"的前提下复跑一次**再采信；
  ③ peer 的存在本身不是灾难（它按规矩停手了），但**必须**在报告里登记"本轮有成色风险"。
- **长静默是子代理的头号杀手：进度要"边做边落盘"，长等待要自己设上限**（2026-10-03，同一事件）：
  那次 155 分钟的运行里，**三段静默直接对应三次超时风险**，而它把 `STATUS.txt` **留到最后一刻（13:03:28）才写** —— 也就是说整个 2.5 小时里，
  它的进度只存在于自己的上下文里，harness 看到的只有"没有输出"。⇒ **纪律**：① **每条命令之前/之后立刻**更新 `STATUS.txt`（不要"最后统一写"）；
  ② **每条命令都要短**（宁可分 5 条各 30 秒，也不要 1 条阻塞 10 分钟）；③ 任何轮询/重试**自己带上限**（`curl -m`、重试次数、`Start-Sleep` 循环的墙钟上限），
  并把每轮的输出打出来（"没有输出"与"在等"在 harness 眼里是一样的）；④ 危险/耗时的**第一步**（如探针容器）最好**单独派发**，做完就落盘，这样被取消也留得下可续的现场。
- **明文 `http://` + JDK `HttpClient` 默认走 HTTP/2 的 `h2c` 升级前奏 ⇒ 自建 HTTP/1.1 上游会"收不到请求体"**（2026-10-03，M5 Task 5 实测，代价是一次 422 误判）：
  `HttpClient.newBuilder()` 在**明文 http** 下默认协商 HTTP/2，会先发 `Upgrade: h2c` 前奏。uvicorn/h11（Chroma 的 `0.5.23` 镜像）**解析不了那个前奏**，
  于是 FastAPI 报 **422 `{"detail":[{"loc":["body"],"msg":"Field required","input":null}]}`**（像"没带请求体"），有时干脆 **400 `Invalid HTTP request received.`**。
  同一个 URL、同一份 JSON，用 `curl`（HTTP/1.1）得到 200 —— **症状会把人往"请求体写错了"上带，而真正的原因是协议协商**。
  ⇒ **纪律**：调**自建/容器化的 HTTP/1.1 上游**（Chroma、自建 OpenAI 兼容 embeddings、uvicorn 类服务）时，
  **显式 `.version(HttpClient.Version.HTTP_1_1)`**。判别法：**换成 `curl -X POST --data-binary @file` 如果好使，问题就在协议层，不在你的 JSON**。
- **"流水线终点后移"会让所有"等中间态"的断言变成竞态或假红**（2026-10-03，M5 Task 5 实测，一次全量里连红 2 条）：
  M5 里 `PENDING → PARSING → EMBEDDING → READY` 是两步流水线；Task 4 时"等到 `EMBEDDING`"等于"解析消息已发出并被消费"，
  到 Task 5（embed 消费者落地）之后**流水线不再停靠 `EMBEDDING`** ⇒ 等中间态要么**超时红**、要么在更早的版本里**立刻返回（假绿）**。
  ⇒ **纪律**：判据盯**终态**（`READY`/`FAILED`）或**不变量**（行数、`embedded_at` 计数），**不要盯中间状态**；
  换阶段时必须 grep 一遍 `"EMBEDDING"|"PARSING"` 这类字符串，把"等中间态"的地方一起改（Task 5 里连改 3 个文件才全绿）。
- **变异证据必须落在"最终提交的那个修订"上**（2026-10-03，M5 Task 4 评审发现的出处问题）：
  变异实验的**红点行号/断言文本会被后续编辑移动**。若在一轮"跑变异 → 再改测试/实现 → 再提交"的过程中采集红证，
  那些日志就与**最终提交**不是同一版本 —— 结论可能仍成立，但**证据出处已经不对**，下一位读者会核不上（Task 4 实测：
  红证记 `:274`，冻结提交上却是 `:268`）。
  ⇒ **纪律**：① 尽量**先定稿、再做变异**（变异是**最后一步**）；② 若中途改过测试/实现，**重做**受影响的变异；
  ③ 报告里每条红证都要**标明它是哪个修订上的**（至少给出当时 `git rev-parse` 或"最后编辑之后"的明示）；
  ④ 复核者可以只**重做一条**最关键的变异来验证出处（Task 4 的评审就是这么做的：在冻结提交上重做 M3 拿到逐字相同的红）。
- **Spring 测试上下文预算：要注入属性，就必须"逐字复用某个既有类的属性集"**（2026-10-03，M5 Task 2 实测）：
  **事实**：`aihub.console.secret` 在 `application.yml` 里**刻意没有默认值**（D16：空 = 门关着），
  而**任何**要签发控制台令牌的集成测试都**必须**注入它 —— 所以"集成测试不许写 `@TestPropertySource`"
  **是一条错的纪律**（照它写，测试根本写不出来：`ConsoleTokenService.issue` 直接抛
  `IllegalStateException: 控制台签名密钥未配置或不足 32 字符`）。
  **真正的规则是不许新增第 8 个 Spring 上下文**，做法是：**让 `@TestPropertySource` 的属性与某个既有测试类
  逐字相同，并且不加 `@Import`**（`@Import` 会把导入者类算进上下文缓存键 ⇒ 必然 fork；
  `@DynamicPropertySource` 同理）。先例就在仓库里：`ApiKeyAdminIntegrationTest` 的 javadoc 明写它与
  `ConsoleLoginIntegrationTest` **属性逐字相同、且不加 `@Import`**，因此**共用一个上下文**；
  `ChannelAdminIntegrationTest:589` 则记着"新写一个带同样属性的类会 fork **第 8 个**"。
  **判据**：跑**全量** admin 套件后数 `Tomcat started on port` —— 必须仍是 **7**。
  M5 Task 2 的 `KbUploadIntegrationTest` 用一把逐字相同的字面量 `console-it-secret-0123456789abcdefghijklmn` 注入、
  不加 `@Import`，全量实测 **`Tomcat` = 7** ✓（`aihub-common` 68/0、`aihub-web` 270/0）。
  **推论（同样适用于"只想改存储目录/超时"这类需求）**：**不要**为了控制某个可配置项去加属性；
  要么把它做成 `application.yml` 的**默认值**（像 `aihub.kb.storage.root: target/kb-storage` 那样，
  测试直接用默认值 + 自己清理），要么复用既有属性集。
- **变异实验必须在「干净」状态下进行，且"突然出现的红"要先怀疑残留变异**（2026-10-02，Task 15 用一次**误判**换来的）：
  正确顺序 = **变异前 `Copy-Item` 字节级备份 → 变异 → `clean` → 跑**（**不许只删 `*.class`**）**→ 还原 → 再 `clean` → 跑绿**。
  为什么：Maven 的增量判定会让"删了 class 但没 `clean`"的构建拿到**类缺失的假红**（Task 15 实测：8 条 `ERROR` ＝ 上下文加载失败）；
  更阴的是**还原源码后不 `clean`** —— 备份的 mtime 比上一次变异编译出的 class **更旧**，Maven 判定"无需重编译"，
  于是**上一个变异的 class 继续生效**。Task 15 里这造成了一次**连环误判**：控制器把"只比当天"那个变异
  （实得 `{903005L=1.5}` = `|40−100|/40`）的红**读成了"测试与共享状态耦合、顺序条件绿"**，据此**宣布任务 BLOCKED 并拒绝推送**，
  还写了一个打在不存在问题上的"修复"。
  ⇒ **纪律**：① 变异与还原**两头都要 `clean`**；② 见到一条**突然出现**的红，**先**核 `target/` 里的 class 与源码是否一致
  （`javap -p -c` 与 pristine 比），排除"残留变异"，**再**去怀疑产品逻辑或测试状态；
  ③ **`git status` / `git diff` 都不能证明 `target/` 干净** —— 它们只看被跟踪的工作树。
  ④ 认得出"变异签名"能省很多时间：一条断言实得的值若正好等于某个变异应有的数值，**先想变异**。
- **PowerShell 5.1 会把无 BOM 的 `.ps1` 按 ANSI 码页读取**（2026-10-01 实测）：脚本里出现非 ASCII
  （尤其中文注释）会让解析器直接报「表达式或语句中包含意外的标记」，且报错位置**指向别处**、极易误判。
  ⇒ **工具脚本一律纯 ASCII**（`<workspace>/.superpowers/sdd/*.ps1` 本来都遵守这条，是控制器新写变异工装时踩的）。
- **派发长任务时如何避免被杀（判据是「可观测活动」，不是「有没有在干活」）**（2026-10-02，M4 全程 **9 次**子代理被杀后的归纳）：
  **一次工具调用 = 一个静默窗口** —— 工具输出通常在**命令结束时**才整体交付，所以"在一条命令里顺序跑 2–4 个 Maven"
  等于制造一个 10–20 分钟的**零输出黑洞**，看门狗据此判 idle 并取消。
  **对照证据（本会话实测）**：能活 **6099 秒 / 100 分钟** 的那次实施做了 **69 次工具调用**，节奏是"一条命令 → 落盘 → 下一条"；
  口径压到"1 条命令 + 1 个变异"的复评 **270 秒**就成功交付；**被杀的那几次**，任务书里都是长串回归命令或"跑一次 `clean` 全量"。
  ⇒ **纪律**：① **一条命令只跑一个 Maven**；② **一个派发单只干一件事**（口径压小）；
  ③ **每跑完一条命令立刻落盘**：`Tee-Object` 落日志 **+ 把进度写进 `<证据目录>/STATUS.txt`**（做到哪、下一步、哪些文件已改），
  这是"被中止后能从盘上续"的唯一凭据（Task 12/15 都是这么救回来的）；
  ④ **不要 `Tee-Object | Select-String` 筛掉输出去干等** —— 要么让 Maven 直接打，要么落盘后在**下一条**命令里读 tail（把静默切成两段短调用）；
  ⑤ `clean` 是时间杀手（本仓 `aihub-web clean test` ≈ **3:49**、`aihub-gateway` ≈ **2:19**、整反应堆更久），
  而变异实验**必须** `clean`（见下一条）⇒ 变异要**逐条跑、逐条落盘**，不要攒成一轮长活儿。
  另有一种**会话级中止**（签名 `code=10003`）：它可以在**开工 1 分钟内**发生（Task 16 首次评审只写了 2 个文件就被中止）
  ⇒ **与 idle 无关**，成因在 harness 之外。无论哪种签名，**恢复动作相同**：先查工作树四处，再从 `STATUS.txt` 续。
- **数字更新（2026-10-01，Task 11 收口；覆盖上面第 5、6 条的旧计数）**：Task 10/11 之后全量为
  `aihub-common` **61** / `aihub-web` **229**（0F/0E/0S），`Tomcat started on port` = **7**
  （产物：控制器 `.hb2-logs/C3-admin-full.log`、修复轮 `.m4t11fix-logs/G06-*.log`、评审 `.m4t11review-logs/V06-*.log`）。
  **Task 11 没有新增 Spring 上下文**：探测用例放进既有的 `ChannelAdminIntegrationTest`（那里才有渠道主密钥），
  查询用例只声明 `aihub.console.secret` 且不加 `@Import` ⇒ 与 `ConsoleLoginIntegrationTest` 共用上下文。
  上面第 4 条的集成类计数在 2026-10-01 复测为 **23**（Task 11 **未**新增集成类：探测用例并进既有的 `ChannelAdminIntegrationTest`，查询用例并进既有的属性集）。

## 9. 提交约定

- conventional commits：`feat:` / `fix:` / `test:` / `chore:` / `docs:` / `refactor:`。
- 每个里程碑完成后打 tag：`m0`、`m1`……
- 密钥、口令一律不进仓库，走环境变量或 `.env`。

## 10. 控制面租户模型（`/api/**`）

**决策（2026-09-30 定死；此前这条规则从未成文，于是每个实现者各自猜 —— Task 9 的实现者为此在测试里被迫让"令牌租户"等于"请求体租户"）**：
M4 的控制台是**平台运营台**，**不是**租户自助台。依据（都是本计划原文，不是推断）：

- 「**细粒度 RBAC、租户自助注册**、第三方登录、邮件通知」列为**非目标**（`:105`）；「管理台的多租户 RBAC 细粒度权限（**只做 `ADMIN`/`VIEWER` 两级**）」列在 Global Constraints 的"不做的事"里；
- Task 11 **明文**要求 `GET /api/logs?tenantId=&from=&to=…`（`:1631`）与 `GET /api/audit?tenantId=&from=&to=…`（`:1644`），其用例断言**不带 `tenantId` 要 400**（`:1659-1661`）——即**调用方选租户是计划要求**；那条"强制 tenant + 时间范围"的理由是**防无界扫描**（`request_log` / `audit_log` 的索引都是 `(tenant_id, created_at)`），**不是**授权；
- Task 17 的验收用**一次性插入的合成 `sys_user`**（`:2214`）拿令牌 ⇒ 单操作者。

**四条规则（Task 10 起一律照办）**

| # | 规则 | 理由 |
|---|---|---|
| **R1** | **全局资源**（`tenant` / `channel` / `model_route` / `config_version` —— 表里**没有** `tenant_id`）的读写是**平台级**，任何 `ADMIN` 都有权。审计的 `tenant_id` 记该资源本身的租户（`tenant` 写 = 目标租户）或 SQL `NULL`（`channel` 写 = `NULL`，Task 8 已如此并登记） | 这些资源本来就没有租户维度；强行造一个会是伪维度 |
| **R2** | **租户维度资源**（`sys_user` / `api_key` / `quota` / `rate_limit_policy` / `request_log` / `billing_daily` / `kb_document`）的**写**同样是**平台级**，任何 `ADMIN` 都有权（运营台必须能处置任一租户的资源，**尤其是对违规租户做应急吊销**）；但审计的 `tenant_id` **必须**记**目标资源的**租户 id —— **不是**操作者的租户、**不是** `NULL`（Task 9 已如此，Task 10–15 照办） | 若把写路径对普通 `ADMIN` 收窄而不同时引入第三级角色，平台方会**失去应急吊销能力** —— 那是运营台的核心功能。所以"只做一半的 fail-closed"比不做更糟 |
| **R3** | **租户维度资源的查**，按"是否需要防无界扫描"分两类：**① 运营查询**（`/api/logs`、`/api/audit`、`/api/billing/daily`）**必须显式 `tenantId`**、缺省 400；**② 资源列表**（`/api/api-keys`，将来的 `/api/quotas`、`/api/rate-limits`）**缺省 = 令牌里的 `tenantId`**（least privilege），将来运营需要跨租户列举时再加**可选**的显式覆盖（**今天不做**，登记为待办） | 两类查询的失败代价不同：无界扫描会退化成全表扫描（索引以 `tenant_id` 打头）；列表则是默认最小权限更安全 |
| **R4** | 令牌里的 `tenantId`（`ConsoleClaims.tenantId`）**不是授权边界**，只在 R3.2 的**缺省值**上起作用；不许拿它做"谁可以做什么"的判定 | 避免把"归属"误当"权限"——真要权限就用角色（`ADMIN`/`VIEWER`），要跨租户就用显式参数 |

**升级门槛（硬）**：出现以下**任一**情形时，本节的"平台运营台"前提**失效**，**必须先落地租户隔离**（引入第三级角色 + 把租户维度资源的**写**按租户收窄，越权以 `404` 表现）**再继续开发**：
① 出现**第一个非平台方账号**（任何 `sys_user` 不属于平台自己的租户）；② 控制台对**非可信网络**暴露；③ 引入**租户自助**。三者任一成立时，"披露"不再是可接受的手段。

**已评估并否决的方案**：MyBatis-Plus 的 `TenantLineInnerInterceptor` / `TenantLineHandler`（全局租户拦截器）。**否决理由（可复核）**：控制面**不是**唯一的数据访问方 —— `ApiKeyService.resolve` 由 gateway 经内部 HTTP 调用（**无租户上下文**）、`MeteringConsumer` 在 MQ 消费者线程里、`@Scheduled` 的分区维护与对账任务**必须跨租户**；再加上要维护忽略表清单（`tenant` / `channel` / `model_route` / `config_version` / `audit_log`）与 `ThreadLocal` 泄漏面，收益远小于风险。**要做租户隔离时，在控制器/服务层显式带 `tenantId`，不要靠拦截器。**

**实现现状（诚实登记，2026-09-30）**：Task 9 的 `GET /api/api-keys` 按**令牌的 `tenantId`** 过滤（= R3.2 的缺省语义），`POST /api/api-keys` 的 `tenantId` 来自**请求体**（= R2，合法）。⇒ 因此"**读按租户、写不限租户**"是**有意的不对称**，不是遗漏。Task 9 的集成测试类注释里"令牌租户与请求体租户必须一致才看得到"就是这条规则的副作用。
