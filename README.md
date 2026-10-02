# aihub-platform

面向「大模型应用平台」场景的后端系统：一个统一的 OpenAI 兼容网关 + 一套控制面业务平台。

- **aihub-gateway**（数据面）：统一入口，负责鉴权、限流、配额、多渠道路由、SSE 流式转发与 token 计量。
- **aihub-admin**（控制面）：租户、API Key、渠道、配额、知识库文档、审计与账单的唯一真相源。

设计文档见 [`docs/superpowers/specs/2026-09-23-aihub-platform-design.md`](docs/superpowers/specs/2026-09-23-aihub-platform-design.md)，实施计划见 [`docs/superpowers/plans/2026-09-23-m0-foundation.md`](docs/superpowers/plans/2026-09-23-m0-foundation.md)。

## 当前进度

- [x] **M0 地基**：多模块骨架、统一响应与异常、Flyway 表结构、基础设施连通与健康检查、最小 SSE 转发验证、Docker Compose 全栈
- [x] **M1 网关直通**：API Key 鉴权（Caffeine → Redis → admin 三级回源）+ 单渠道字节级透传（流式与非流式同一段代码）+ 上游状态码 / 响应体 / `Content-Type` 原样透传 + `GET /v1/models`
- [x] **M2 流式与计量**：SSE 转发 + usage 捕获 + 计量落库
- [x] **M3 流量治理**：Lua 令牌桶限流 + 多渠道路由 + 故障转移 + 熔断 + 渠道密钥本地解密 + 配置快照
- [x] **M4 业务平台**：租户 / API Key / 渠道管理 / 配额 / 审计 + **零构建管理台**（`GET /console/index.html`）

### M4 到底做了什么

控制面（`admin`，`/api/**`，由 `ConsoleAuthFilter` 守门）：**登录签发令牌**（`POST /api/auth/login`，`ADMIN` / `VIEWER` 两级角色，
`VIEWER` 只读）→ **租户、API Key（签发/列表/停用/删除，明文只在创建那一次出现）、渠道（含真实连通性探测）、
路由与限流策略、配额（`GET`/`PUT /api/quotas`）** 的增删改查，外加 **审计日志**（每次写操作留一行，
`tenant_id` 记**目标资源的**租户）与 **运营查询**（`/api/logs`、`/api/audit`、`/api/billing/daily`，三者都**必须显式 `tenantId` + 时间范围**）。

数据面（`gateway`）：**配额预扣**（`QuotaFilter` + Redis Lua 原子脚本，超限回 OpenAI 形状的 `429 insufficient_quota`，
与限流的 `rate_limit_exceeded` 语义不同）→ **实际校正**（拿到上游真实 `usage` 后把差额还回去）；
以及 **控制面改配置 → 数据面秒级生效**（admin 发 Pub/Sub 失效广播，网关订阅后清本地缓存与共享快照条目 —— M3 实测是 **101 秒**，M4 是**秒级**）。

**管理台怎么用（零构建，无 npm、无打包）**：容器起来后浏览器打开 `http://localhost:8081/console/index.html`
（admin 端口，见「快速开始」）→ 用 `sys_user` 的账号登录 → 令牌存在 **`sessionStorage`**（关标签页即失效）。三个视图：
**渠道**（列表 + 新建 + 探测）、**API Key**（列表 + 新建，明文**只显示一次**）、**请求日志**（按 `tenantId` + 时间范围查询）。
页面不引任何第三方脚本、不用 `innerHTML`（所有服务端文本走 `textContent`）。
- [ ] **M5 异步流水线**：文档上传 → 解析 → 嵌入 → 向量库
- [ ] **M6 压测与打磨**：压测报告、故障注入报告、上线

**M1 到底做了什么**：`/v1/**` 现在必须带 API Key，缺省或非法一律 `401` 加 OpenAI 形状错误体（`{"error":{"code":"invalid_api_key",...}}`）；密钥解析走三级回源（本机 Caffeine → 共享 Redis → admin 的 HMAC 内部接口），任一级故障都降级到下一级而不是拒绝请求；转发端点是**字节级直通代理**，上游的状态码、响应体字节与 `Content-Type` 原样回写，因此流式（SSE）与非流式（JSON）由请求体里的 `stream` 字段决定，网关不分流、也不再把上游错误折叠成 `500`；新增 `GET /v1/models`，单渠道场景下回报配置的默认模型。

**M2 到底做了什么**：用量捕获是**旁路观察者** —— 转发路径仍然是字节级直通（上游状态码 / `Content-Type` / 响应体字节一字不改），只是挂了一只只读的 `asByteBuffer()` 探针，把经过的字节复制进一个有界尾窗。非流式从整体 JSON 里读 `usage`；流式则在请求体里注入 `stream_options.include_usage`（**这是唯一被允许的请求体改写**），再从最后一帧读 `usage`。每个 `/v1/**` 请求由网关铸一个 UUID 写进响应头 `x-request-id`，`POST /v1/chat/completions` 同时把它当作计量事件的 `request_id`；幂等键是 `(request_id, created_at)`，两个值都由**网关**在请求开始时各生成一次（`created_at` 截断到毫秒）并随事件投递，消费端原样使用 —— 消费端若用自己的 `now()`，每次重投都会写成新的一行。事件经 RabbitMQ 用共享的分隔符文本 codec（不是 JSON：`aihub-common` 是零依赖的）投给 admin，由 admin 幂等落 `request_log`；**只有 admin 声明拓扑**，网关只发布。broker 不可用时网关先落内存队列（有界）、再落磁盘 spool，由定时任务重投，任何丢弃都会让 `dropped` 计数 +1 并打 ERROR（绝不静默丢弃）。`request_log` 的月分区由运行时维护：启动补齐 + 每日 03:10 UTC 前推 2 个月。另有 M1 的三处遗留修复：SSE 增量 flush 的可证伪断言、客户端断连计量、`retry-after` 与 `x-ratelimit-*` 透传，以及 `defaultModel` 为空的 NPE。

**验收状态**：全量测试实测 **491 项通过 / 0 失败 / 0 错误 / 0 跳过**（aihub-common 56、aihub-web 92、aihub-gateway 343；`mvn -B clean test` → `BUILD SUCCESS`，含 Testcontainers 真容器用例）—— 这是 **M3 收口当时**的数字；D1 修复之后是 **497**（56 / 95 / 346），见下面「测试与验收」。**（2026-09-30 复测：`aihub-common` **61** / `aihub-web` **198**；`aihub-gateway` **继承·未复核** —— 产物 `.hb2-logs/W05-admin-full.log`、`.m4t9fix-logs/R05-admin-full.log`；按 `docs/CONVENTIONS.md` §8 的纪律，继承来的数字必须重测或标注。）**M2 的真实上游端到端验收（Docker Compose 全栈 + 真实模型）结论：非流式与流式响应都带 `x-request-id`，两次请求在 `request_log` 各落一行且 token 数与上游响应体里的 `usage` **完全一致**，非流式那行 `ttft_ms` 为 `NULL`、流式那行为正数，行的 `request_id` 等于客户端看到的响应头、`created_at` 等于事件里的值，重放同一事件不产生第二行。

**M3 到底做了什么**：`/v1/**` 现在先过**限流**再过**路由**。限流是 Redis + Lua 的令牌桶，策略按 **`tenant + api_key` 两个维度**选取（key 级覆盖 → 租户级回落 → 内置默认 `qps=10 / burst=20`），桶的状态键是 `aihub:ratelimit:{tenantId}:{sha256(secret)}`；超限回 OpenAI 形状的 `429 rate_limit_exceeded`，并带 `Retry-After` / `Retry-After-MS` / `RateLimit-Limit` / `RateLimit-Remaining`（**没有 `RateLimit-Reset`**：网关不写这个头，`ratelimit-reset` 在网关里只是透传白名单里的一个上游头名；且前两个头在**放行**响应上也有，不是「只在拒绝时」才出现）；**Redis 挂了降级成本机令牌桶，但仍然拒绝**（降级 ≠ 放行；多实例下放行量约为「策略 × 实例数」）—— 这条在**修掉 D1 之后**才真正在端到端上成立：`stop redis` 时「鉴权缓存未命中的 key」原先会先被 fail-closed 成 401、请求根本走不到限流器（全栈验收实测）；根因与修复见「已知边界」第 1 条。路由按 `model_route` 的 `priority` 分组（数字小的组先服务），组内按 `model_route.weight` 权重随机，`channel.status != ACTIVE` 或不可用的渠道被排除，熔断中的渠道排到最后而不是删除。每条渠道用自己的 base-url / 超时（`channel.timeout_ms` 只对非流式生效）与**自己解密出来的**密钥，凭据**逐请求**注入、绝不挂到共享客户端上。**故障转移**的规则只有两条：上游结果可切换（**429 与 5xx**；4xx 原样透传、不换）且响应**尚未提交**（`response.isCommitted()` 为假）—— 一旦有字节写回客户端就绝不再换，这就是「仅在未输出任何 token 时允许切换」的机器形式；一次请求最多试 3 条候选（首选 + 两条备用）。上游 **429 还会给该渠道打一个 30 秒的跨实例熔断标记**（Redis key `aihub:channel:circuit:{id}`；Redis 不可用时退化为本机表），5xx 与超时只做当次切换。**渠道密钥是 AES-GCM 密文**（`v{n}:{base64(nonce‖ciphertext+tag)}`，自描述版本）：admin 只下发密文，主密钥只在环境变量 `AIHUB_CHANNEL_MASTER_KEY` 里，**解密只发生在网关本地**，明文不跨网络、不进日志；轮换 = 环境变量里新旧两把并存 → 重加密 → 删旧密钥，密文自描述版本让回退安全。控制面配置通过 HMAC 签名的 `GET /internal/config/snapshot` 下发，网关侧是**三级读取两级缓存**（Caffeine 30 秒 → Redis 10 分钟 → admin），带 singleflight 合并回源、版本比对（本地命中也会探一次 Redis 版本）、回源失败后的 cooldown 限速，以及两级缓存都空时的**内存 last-good 快照**；快照完全没有时才回落到 `aihub.upstream.*` 合成的遗留单渠道。另外收口了 M2 的三处遗留：透传白名单补上 `retry-after-ms` 与 IETF `RateLimit-*`、超长 `model` 在计量事件里按码点截断（转发给上游的请求体逐字节不变）、`api_key_id` / `channel_id` 真正落进 `request_log`。**多渠道故障注入的验收**用 **WireMock**（`org.wiremock:wiremock:3.9.1`，仅 `aihub-gateway` 的 test 作用域、**进程内**起桩，不需要 Docker / broker / Redis）在 `WireMockChannelFaultInjectionTest` 里做 —— 那是设计文档 §10 / §12 的原文口径：几条命名桩分别注入 429 / 挂住超时 / 中途断流，验证自动切换与「已开始回写就不切换」。**这是 M3 唯一的 test 作用域新依赖，生产依赖零新增**（设计文档 §4.2 说「WireMock 在 M3 引入时再锁定版本」，落点就是这里）。

**M3 验收状态**：M3 的验收标准「WireMock 注入 429/超时，能自动切换」现在**两层证据都跑过了**。

**第一层（进程内 WireMock，spec §10/§12 的原文口径）**：`WireMockChannelFaultInjectionTest` 8 条用例（`mvn -B clean test -pl aihub-gateway -am "-Dtest=WireMockChannelFaultInjectionTest"` → `Tests run: 8, Failures: 0, Errors: 0`），它证明的是每条命名桩各自的注入与切换规则、熔断标记参与路由、以及「已提交之后绝不拼接备用渠道」。不需要 Docker。

**第二层（2026-09-26 在发布的 compose 全栈上实测：真实 admin + gateway + MySQL + Redis + RabbitMQ）**：计划 Task 15 Step 5 的 1–7 步**全部跑过**。上游用宿主机上自建的两个夹具替代（一只只回 429、一只正常 OpenAI 兼容且支持 SSE；脚本在 gitignored 的 `.superpowers/m3-acceptance/` 里，**不进仓库**），演示渠道的 `base_url` 被指到夹具上。实测结论：429 渠道 → 切到备用渠道拿到 200，且 Redis 出现 `aihub:channel:circuit:1 = OPEN`（TTL 27 秒）；紧接着的第二次请求**不再打** 429 渠道（夹具计数 1 → 1）；5xx 只做当次切换、**不写熔断键**；`request_log.channel_id` 记的是**实际服务**的那条备用渠道，`request_id` 与客户端看到的 `x-request-id` 逐字符相同、token 数与上游响应的 `usage` 一致；流式（`stream:true`）走 SSE 到 `data: [DONE]` 且 `ttft_ms` 有值；限流按 key 级 5/10 生效（12×200 + 8×429，429 是 OpenAI 形状并带 `RateLimit-Limit` / `RateLimit-Remaining` / `Retry-After` / `Retry-After-MS`，**放行响应上也带** `RateLimit-Limit` / `RateLimit-Remaining`，且没有 `RateLimit-Reset`）；`stop admin` 后网关照常按缓存快照路由（200，无 5xx），`start admin` 后恢复。**逐条命令与未删改的真实输出（凭据只留前缀）在 `.superpowers/sdd/m3-acceptance.md`。**

- **2026-09-26 那一轮没有跑到的部分（历史记录；其中第 1 条已于 2026-09-27 复验、判据达成，见下一条）**：计划 Step 5 第 5 步「`stop redis` 后仍应被限流（429）」这一步**执行了，但判据未达成 —— 没有拿到 429 证据**：限流器确实降级成了本机令牌桶（日志里的 `RedisRateLimiter` / `RateLimiter` / `RateLimitFilter` 三条 WARN/ERROR，以及放行响应上的 `RateLimit-*` 都能证明），但请求先在鉴权那一步 fail-closed 成 401（**那是 D4 之前的对客口径**；D4 起同类「判不了」的结果是 `503 service_unavailable`），根本到不了限流器 —— 原因见下面第 1 条（**该缺陷已修**，并且**已于 2026-09-27 在真实 compose 全栈上重跑、判据达成**：14 个**并发**请求拿到 10×200 + 4×429（10 正好等于 key 级 `burst=10`），过程中没有 401、也没有 503；见 `docs/CONVENTIONS.md` 第 5 节、`.superpowers/sdd/d1-fix-report.md` 与 `.superpowers/sdd/m3-acceptance.md` 第 11 节）。另外**两次验收都没有使用你自己的上游**（`.env` 里的 Ollama / 云厂商）：上游位置都被换成宿主夹具，因此「你的真实上游能不能被网关转发」这一条**两轮都没有覆盖**（M1/M2 各自覆盖过）。
- **同一次验收里量到的三处问题（第 1 条是可用性缺陷，第 2、3 条是文档与实测不一致；都没有在验收里改生产代码）**：
  1. ~~**`stop redis` 后，鉴权缓存未命中的 key 会被 fail-closed 成 401，并被本地负缓存放大到约 30 秒**~~ **【D1，已修复；下面是原始记录与修复口径】**：admin 侧自己的 Redis 读 + 回写各要等一次 2 秒超时，于是 admin 的回答超过 gateway 给它的 3 秒 `responseTimeout`，网关把这条连接当成传输失败（三次独立复现，每次都是 Resolver 的 `Redis 读取失败` WARN 之后**恰好 3.01 秒**出现 `AdminClient` 的 fail-closed ERROR），随后把这个 MISS 写进本地负缓存（`aihub.auth.local-cache-ttl`，默认 30 秒）。**结果是「Redis 挂了 ⇒ 一大片 401」**，比「降级到 admin（真相源）」严重得多。它是**间歇性**的：单发请求、admin 侧 Redis 快速失败时可以成功（实测有一次 8.04 秒的 200）；MISS 一旦被写进本地负缓存，连 Redis 恢复以后也要等它过期（约 30 秒）才恢复 —— 实测 Redis 起来后 m3-a / m3-b / m3-c 才都回到 200。**修复（两半一起做，缺一条都还能撞上 3 秒）**：① admin 侧 —— `ApiKeyService.resolve` 在一次 Redis 访问失败后进入**5 秒粘性降级**（窗口内读与写都不碰 Redis，只走 MySQL），同一次请求里的回写被跳过，并把 admin 的 `spring.data.redis.timeout` 从 2s 收到 **500ms**（admin 主代码里唯一的 Redis 消费者就是这个密钥缓存）；② 网关侧 —— 把「admin **权威地**说没有这把 key」与「**解析不了**（超时/传输/5xx/畸形）」分开（`AdminResolution` 三态），**只有权威否定才进负缓存**，故障结果一次都不写。修后实测（黑障 Redis + 真实 Lettuce）：admin 的 `resolve` 从 **4404ms → 811ms**（未命中 2420ms → 804ms，故障后的第二个请求 4203ms → **1ms**），网关侧「故障清除后同一把 key 立刻恢复」并由「Redis 不可用 + 有效 key ⇒ 打到 **429** 而不是 401」钉住。**残余（D4 已改为 503）**：admin 自身真的不可达 / 极慢时，客户端**不再**看到 `401 invalid_api_key` —— D4（项目所有者的裁决）把「我们**判不了**」这类结果的对客状态码改成 **`503 service_unavailable`**（`api_error`），因为把平台故障伪装成「你的 key 错了」会让客户端去做错的事（改密钥）。**两者都是拒绝**：请求一样走不到限流 / 路由 / 上游，安全姿态不变，变的只是诊断通道。变化是**它仍然不被缓存**，因此控制面一恢复就自动恢复，不再需要等 30 秒。**已复验（2026-09-27，最终 HEAD `2aab02f`，真实 compose 全栈）**：`stop redis` + 一把**从未用过**的 key 三次请求全部 200（没有 401、没有 503），限流降级后仍然拒绝（14 个**并发**请求 → 10×200 + 4×429）；`stop admin` + 冷缓存 key → **503 `service_unavailable`**，`start admin` 后**第一个请求**即恢复 200。细节见 `.superpowers/sdd/d1-fix-report.md`、`.superpowers/sdd/fault503-report.md` 与 `.superpowers/sdd/m3-acceptance.md` 第 11 节。
  2. **配置热生效的实际延迟由 Redis 快照的 10 分钟 TTL 决定，不是 30 秒**：改 `channel.base_url` 后 **101 秒**新快照仍不可见（用一条 `probe-model` 路由做只读探针）；删掉 `aihub:config:snapshot` 之后 15 秒内收敛。「本地 TTL 30 秒 + 快照 version 比对」只在共享条目消失时才会回源 admin。**部署含义：控制面的一次变更最长约 10 分钟才到达数据面**（Redis 快照 TTL 默认 10 分钟），除非运维刷新 / 删除共享快照条目 —— M3 没有 Pub/Sub 发布方，也没有主动刷新共享条目的手段。
  3. **`/v1/**` 上没有处理器的路径 / 方法返回的是 Spring 默认错误体**（`{"timestamp","path","status","error","requestId"}`，带 `x-request-id`），不是 `docs/CONVENTIONS.md` 第 4 节说的「一律 OpenAI 形状」：实测 `POST /v1/models` → 405、`POST /v1/embeddings` → 404、`GET /v1/nope` → 404。这是 M1/M2 就有的形状，**不是 M3 引入的**；`docs/CONVENTIONS.md` 第 4 节里「一律 OpenAI 形状」的措辞已按实测收窄到网关**实际处理**的路径，并把未注册的 404/405 登记为已知缺口（README 下面「已知边界」也单独列了一条）。

**怎么复现**：先起宿主夹具（两只就够：只回 429 的、正常 OpenAI 兼容 + SSE 的），再 `docker -H tcp://127.0.0.1:2375 compose up -d --build` —— **注意镜像必须从当前 HEAD 重建**（盘上旧的可能还是 M2 时代的代码；Docker Hub 不可达时用 `--pull never`），然后按计划 Task 15 Step 5 的 1–7 步执行。完整的命令、未删改的真实输出、以及本轮量到的三处问题都记在 `.superpowers/sdd/m3-acceptance.md`。

**复现口径（诚实说明）**：上面的数字与「`mvn -B test` 在本机全绿」都产自这台开发机：除了 Docker 守护进程，它还依赖两项**不在仓库里**的环境配置 —— 用户级 `~/.testcontainers.properties`（把 Testcontainers 指向 TCP 上的 Docker）以及本机 `.mvn/maven.config` 里的 JVM 参数。因此在一台干净机器上，需自行保证：Docker 可达，且 JDK 21+ 上允许 Mockito 的动态 agent 挂载（例如 `mvn -B test -DargLine="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"`）；这些**环境作用域**的 JVM 开关有意不进 `pom.xml`。`aihub-web` 的集成测试要真起容器，必须让 Testcontainers 找到 Docker（本机是 `DOCKER_HOST=tcp://127.0.0.1:2375`）；`aihub-gateway` 的测试**不需要** Docker，也不需要有 broker 在跑。


## M0/M1/M2/M3/M4 已知边界

**M4 新增（每一条都有对应的代码 / 测试，不是"以后再说"）**

1. **配额在 Redis 不可用时放行（D7，fail-open）**：限流是「降级**仍拒绝**」，配额是「降级**放行**」——
   放行只可能造成超额，不会造成雪崩。想在 Redis 故障期间仍如实拒绝超预算租户，要开 `QuotaFallback`（admin 兜底权威判余额）。
2. **吊销不是全链路立即生效**：共享层（Redis）由 admin 显式 `DEL` **立即失效**，但**网关本机 Caffeine 仍 ≤30s**。
3. **02:00 UTC 对账只报告、不改账（D12）**：它按 `request_log` 幂等重算 `billing_daily` 并计数偏差，
   **绝不写** `quota.token_used` / `request_used`；偏差如何处置是**人的决定**。
   实际校正 `adjust` 是**非幂等**的（重复调用会重复加减），靠对账兜底。
4. **`billing_daily.cost` 恒为 0**：本里程碑没有单价表，成本口径未接。
5. **管理台的令牌存在浏览器的 `sessionStorage` 里**：页面自身不引第三方脚本、所有服务端文本走 `textContent`、
   无 `innerHTML`；但**任何**能注入脚本的入口都能读到那个令牌 —— 这是"零构建管理台"的已知 XSS 代价（D9），不是缺陷。
6. **网关缓存请求体（D17）**：为了能多次读取 body，请求体会进内存，超大 body 有内存代价。
7. **网关自己连 Redis 的超时仍是 2 秒（A3，本里程碑不改）**：Redis 停机时**顺序**发请求会看到每个请求赔多次超时
   （实测 16 个顺序请求 ≈110 秒，≈7 秒/请求）⇒ 要观察降级后的行为**必须并发压测**，否则会误判成"卡住"。
8. **未鉴权路径不限流（A6）**：限流发生在鉴权之后。
9. **`/v1/**` 下没有处理器的 404/405 仍是 Spring 默认体（A7）**，不是 OpenAI 形状（同 M3，未改动）。
10. **`last_used_at` 目前没有写入方（恒 NULL）**；`billing_daily` 的唯一写入方是 02:00 对账任务
    （M4 没有独立的计费流水来源）⇒ 账单端点在真实环境的数据**只在对账跑过之后才有**。

**测试规模**：M4 各任务收口时实测为 `aihub-common` **68** / `aihub-web` **263** / `aihub-gateway` **391**
（见各任务报告与 `.m4t*-logs/` 证据目录）；**整反应堆 `mvn -B clean test` 的权威数字与逐模块明细**，
见 `.superpowers/sdd/m4-acceptance.md`（**git-ignored**，只存原始输出，token 只留前缀）。

以下是有意划出的范围边界与**尚未被验证的东西**，以及真实验收量到的**已知缺口**（凡属缺口的都会明说「已知缺口 / 没有任何缓解措施」）。带「未验证 / 未做」字样的条目请当作事实陈述读：它们没有被任何测试或真实环境证明过。

- **M3 的 compose 全栈验收已于 2026-09-26 在发布的 compose 栈上跑过**（计划 Task 15 Step 5 的 1–7 步**全部执行过**；多渠道 429/5xx 切换、Redis 30 秒熔断键、`request_log.channel_id`、两维限流与响应头、`stop admin` 后继续路由；命令与未删改的真实输出见 `.superpowers/sdd/m3-acceptance.md`）。**但「跑过」不等于「没有缺陷」**：同一次验收量到三处必须按事实读的边界 —— ① **`stop redis` 时「鉴权缓存未命中的 key」会 fail-closed 成 401，并被本地负缓存放大到约 30 秒**（所以计划里「Redis 挂了降级后仍被限流 429」那一步的判据在全栈上无法达成）—— **此条已修复（D1）**：admin 侧给 resolve 路径加了粘性降级与 500ms 命令超时，网关侧把「权威否定」与「解析不了」分开、故障结果不再进负缓存；端到端重跑**已于 2026-09-27 完成、判据达成**（14 并发 → 10×200 + 4×429，没有 401/503），原始记录与修复口径见下面第 1 条；② **配置热生效受 Redis 快照 10 分钟 TTL 约束**，不是 30 秒 —— 实测改 `channel.base_url` 后 **101 秒**新快照仍不可见，删掉 `aihub:config:snapshot` 后 15 秒内才收敛，也就是控制面变更最长要约 10 分钟才到达数据面；③ **`/v1/**` 上没有处理器的路径 / 方法返回 Spring 默认错误体**，不是 OpenAI 形状（`POST /v1/models` → 405、`POST /v1/embeddings` → 404、`GET /v1/nope` → 404）。
- **2026-09-27 复验收（在最终 HEAD `2aab02f` 上重跑此前的 compose 全栈验收）**：① 故障转移与 429 熔断、5xx 只切换**不**写熔断键、`request_log`（`channel_id` 记**实际服务**的那条渠道）、两维限流（key 级 `5, 10` / 租户级 `20, 40`）**全部复现**；② **D1 判据达成** —— `stop redis` + 缓存冷 key 三次请求全部 200，且整个窗口里**一条 `AdminClient` fail-closed ERROR 都没有**（与 2026-09-26 那次「WARN 之后恰好 3.01 秒 ERROR」形成直接对照）；限流降级后**仍然拒绝**：14 个**并发**请求 → **10×200 + 4×429**（10 正好等于 key 级 `burst=10`，说明判定确实由降级后的本机桶按 key 级策略做出），全程没有 401、也没有 503；③ **D4 在真机上成立** —— `stop admin` + 缓存冷 key → **503 `service_unavailable`**（响应头**无** `Retry-After`），`start admin` 后**第一个**请求即 200（故障未被记住），而**真正不存在的 key 仍然是 401 `invalid_api_key`**；④ 发现 3（未注册的 404/405 不是 OpenAI 形状）逐条复现、形状与上次逐字相同；⑤ **发现 2（配置传播延迟）本轮没有拿到可信复测**：探针脚本 **v1/v2 两版本身写错了**（v1 用「删掉最新那一行」去收敛，恰好触发了已登记的**版本回退**缺口：`version = max(updated_at)` 会因删除最新行而倒退；v2 起步时共享条目根本不存在，量到的是「没有共享条目」的快路径），另外还有一个 PowerShell 陷阱影响了**全部三版**（`if (Fn …)` 会把函数的输出当条件消费掉，于是每次采样的明细行从未打印；v3 已用 `Write-Host` 修掉）。修掉之后 v3 的结果是**一个未能解释的现象**（t0 之后 5 秒就看到新路由，与「共享条目才是实际上界」的模型不符），不是脚本错误。因此结论**既未复现也未反驳** —— 上一轮的 101 秒 / 删除共享条目后 15 秒仍是本仓库唯一的实测记录。**另有一条未解释观察（不认定原因）**：删掉最新那一行 `model_route` 并删掉共享快照条目之后 **40 秒**，网关**仍然**把那条**已被删除**的路由报在 `GET /v1/models` 里；至少有两个机制都能解释它（`lastGood` 保留 vs 版本回退被拒），本轮**没有**设计出能把两者分开的实验，所以只登记现象。命令、未删改的真实输出与全部踩坑见 `.superpowers/sdd/m3-acceptance.md` 第 11 节。
- **重跑 compose 全栈验收必须先重建镜像**：`docker compose up -d` **不会**自动重建，盘上的 `admin/gateway:latest` 可能是上一个里程碑的代码（本轮验收就发现盘上镜像还是 M2 时代 `e4f4103` 构建的，不重建等于**在 M2 上验收 M3**）；请用 `docker compose up -d --build`（Docker Hub 不可达时加 `--pull never`）。这一条对以后任何人重跑都适用。

- `/healthz` 会返回组件明细且不鉴权，等控制面鉴权落地后会一并收紧。
- 配额（预扣 / 实际校正 / 异步对账与账单）整体属于 M4；M3 做的是**限流**（QPS/burst），不是**余额记账**（详见下文 M3 的边界）。
- 网关**不做**租户 / 渠道 / 配额管理、审计与管理台；API Key 的控制台签发 / 列表 / 吊销接口属于 M4（目前只有本地 CLI 铸造路径 + 一个默认关闭的 dev-only 渠道 seeder）。
- `POST /v1/embeddings` 与文档上传 / 向量化流水线属于 M5，M2 只计量 chat 直通。
- **Redis 是鉴权的信任源之一**：网关把 Redis 里的密钥缓存命中当作**权威结果**，命中即放行、不再回查 MySQL；能往 Redis 写 `aihub:apikey:<sha256>` 的对端等于能伪造任意 API Key，所以 Redis 必须与控制面同等级隔离保护。由此，密钥的吊销 / 停用也不会立刻生效 —— 要等缓存过期（本机 Caffeine ≤30s，Redis ≤5m）。
- `docker-compose.yml` 仅供**本地开发**：Redis 没有密码，宿主映射已收紧为 `127.0.0.1:6380:6379`（本机 IDE 仍可连 6380，容器之间仍走 `redis:6379`）。生产环境的 Redis 认证（`requirepass`）与网络隔离属于 M3 加固项。
- **控制面故障时客户端看到的是 503 `service_unavailable`（D4 起；此前是 401 `invalid_api_key`）**：当 Redis 未命中且 admin 不可达 / 5xx / 配置错（或 `aihub.internal.secret` 为空这类平台配置故障）时，网关**无法判定**这把 key 是否有效，于是回 `503` + OpenAI 形状的 `{"code":"service_unavailable","type":"api_error"}`，消息是「密钥服务暂时不可用：网关无法校验本次 API Key，请稍后重试」。**这不是放行**：与 401 一样是拒绝 —— 请求一样走不到限流 / 路由 / 上游，客户端一样拿不到 `200`，安全姿态没有变化；变的只是诊断通道（以前平台故障伪装成「你的 key 错了」，客户端会去改密钥，是错的反应；现在它诚实地说「稍后重试」，OpenAI SDK 对 5xx 有内建重试）。**不加 `Retry-After`**：故障会持续多久是未知的，给一个猜出来的值比不给更糟。运维侧的区分信号仍然是日志（admin 回源失败打 ERROR，真正「key 不存在」只打 DEBUG）；**到目前为止还没有 Micrometer 计数器区分这两者**（登记为 M4 待办）。**（D1 起）这类故障结果不进负缓存**：它在缓存上**不**等于「key 不存在」，因此控制面一恢复，同一把 key 的下一个请求立刻恢复，不需要等 `aihub.auth.local-cache-ttl`（30 秒）过期。真正不存在的 key 仍然回 `401 invalid_api_key` 并进负缓存（那是 admin 的权威结论），所以坏 key 不会变成对 admin/MySQL 的每次请求。
- **`/v1/**` 上没有处理器的路径 / 方法不会得到 OpenAI 形状错误体**：实测 `POST /v1/models` → `405`、`POST /v1/embeddings` → `404`、`GET /v1/nope` → `404`，返回的是 Spring 默认错误体 `{"timestamp","path","status","error","requestId"}`（仍带 `x-request-id`，因为 `RequestIdFilter` 排在最前）。这是 M1/M2 就有的形状（M3 未改动、也未声称改过），`docs/CONVENTIONS.md` 第 4 节已把「一律 OpenAI 形状」收窄到网关**实际处理**的路径；给 404/405 注册 OpenAI 形状处理器属未来里程碑，今天**已知缺口**。
- 压测与故障注入报告属于 M6。

M2 新增的边界：

- **（M3 已关闭）`request_log` 的 `api_key_id` 与 `channel_id` 曾恒为 `NULL`**：M2 时共享的 `ApiKeyView` 里没有 `api_key` 的数值主键、多渠道也还没做，所以那个里程碑的 `request_log` 不能按 API Key 或渠道聚合。M3 把两者都补上了（`ApiKeyCacheCodec` 的载荷因此是 6 段；见下文 M3 的边界）。鉴权关闭、或 exchange 里没有 key 视图时 `api_key_id` 是 `NULL`、`tenant_id` 记哨兵 `0`。
- **`prompt_tokens = 0` 表示「未知」，不是「零」**：拿不到上游 `usage` 时（上游没回、响应体超出捕获窗口），`completion_tokens` 是**估算值**（1 个汉字 ≈ 0.6 token、1 个非汉字字符 ≈ 0.3 token，向上取整），`prompt_tokens` 记 0，并用 `error_code = usage_missing`（客户端中断则是 `client_disconnected`）把这件事标出来。M4 的账单 / 对账必须把带这两个 `error_code` 的行当**近似值**处理，不能当精确用量。
- **捕获窗口是 1 MiB 的「尾部」**（`aihub.metering.max-capture-bytes`，默认 `1048576`）：流式下这是对的（`usage` 在最后一帧），但**非流式**响应体是一整块 JSON —— 一旦超过这个上限，头部被丢掉、JSON 不再可解析，精确 `usage` 会**静默降级**成估算值。现实中的非流式 body 远小于 1 MiB，所以保留了默认值；要调小它，先确认非流式 body 仍能完整落在窗口内。`TailBuffer.truncated()` 目前没有计数器，窗口被截断只在结果上体现为 `usage_missing`。
- **计量计数已注册但不对外暴露**：`aihub.metering.published / spooled / dropped / replayed` 四个 Micrometer 计数器存在，但 gateway 只 `include` 了 `health,info`，`/actuator/metrics` 没开（对外暴露计量指标属于 M6）。运维信号目前只有「drop 计数 + ERROR 日志」，spool 也只能看日志与目录，**没有**人工巡检接口，也**没有** DLQ 重投工具。
- **网关的 rabbit 健康指示器被有意关掉**（`management.health.rabbit.enabled: false`）：AMQP starter 会自动注册 `RabbitHealthIndicator`，broker 不可达时 `/healthz` 会变成 `503 DOWN`；但「broker 挂了」是**已设计的降级状态**（事件先落内存队列再落磁盘 spool，恢复后定时重投），把它报成 DOWN 会让编排层（K8s / Compose）去重启一个**正在正常转发**的网关，反而毁掉 spool 兜底的意义。这是评审过的、刻意的取舍 —— 请不要顺手把它改回 `true`；真正的运维信号是 drop 计数与 ERROR 日志。
- **磁盘 spool 假定「单写者」**：一个网关进程独占一个 spool 目录。两个进程共享同一目录时文件名仍可能撞车，`spool-max-files` 这个上界会失守（撞车会被计入 `dropped`，不会静默）。
- **`mandatory` / publisher-returns 这条分支没有得到真实 broker 的端到端验证**：发布端在 gateway 模块，broker 夹具在 admin 侧，模块依赖方向不允许两边相遇（gateway 的测试也不允许依赖 Docker）。代码把「消息被退回」当作投递失败从而落盘，但这条分支目前只有单元测试撑着。
- **分区维护的边界**：启动补齐 + 每日 03:10 UTC 前推 `aihub.metering.partition-months-ahead`（默认 2）个月；**中间空洞只告警、不自动补**（补它要把已有数据搬到锁下，属运维决策）。另外「启动补齐」今天**观测不到**，因为 V1 基线迁移的分区已经到 `2026-12`，在 2026 年 12 月之前不会有新分区被创建。
- 真实上游那一轮只覆盖了**一条**上游、一个模型、一次非流式 + 一次流式；真实上游的错误路径（上游 `429` / 中途断流 / `retry-after` 的实际取值）仍是 stub 级别的验证。
- **计量只覆盖 `POST /v1/chat/completions` 一个端点**：网关全树只有一个发布点（`ChatRelayController.chatCompletions` 的 `doFinally`，`ChatRelayController.java` 里那一次 `meteringPublisher.publish(...)`）。`GET /v1/models`、`POST /v1/embeddings`（M5 才有实现，当前是 `404`）以及所有在控制器之前就被短路的响应（`401 invalid_api_key`、`503 service_unavailable`、`404` 等）都带 `x-request-id`，但**没有计量事件、也不会在 `request_log` 落行**。所以「每个 `/v1/**` 请求一行」是错的：M4 做账单 / 对账时，不能把「日志里出现过某个 `x-request-id`」当作「库里一定有对应的行」。
- **客户端在首个字节之前中断，会被记成 `ERROR` / `gateway_error` 而不是 `CANCELLED`**：`gateway_error` 这条分支覆盖的是「响应**尚未提交**时冒出来的异常」，而响应提交之前的客户端中断无法与网关自身的真实故障可靠区分（Reactor 在提交之前不会给出可分辨的信号），代码因此不猜测、按未预期异常记账 —— 这是 brief 指定、评审后保留的行为。设计文档 §9 的「客户端断连不计入错误告警」只对**响应已提交之后**的断连成立（那条才记 `CANCELLED` / `client_disconnected`）。**按 `gateway_error` 告警前必须先确认客户端侧没有对应的主动中断**，否则会把客户端行为误报成网关故障。
- **本地 compose 下网关「起得来」要 broker，「跑得下去」不要**：`docker-compose.yml` 里 gateway 配了 `depends_on: rabbitmq: service_healthy`，所以本地 compose 下 broker 不健康时网关**根本不会被启动**；这是编排上的便利约定，**不是**运行时要求 —— 运行期 broker 挂掉网关照常转发（事件先落内存队列、再落磁盘 spool，恢复后重投）。直接 `java -jar` 跑网关没有这条依赖。
- **（M3 已关闭）超长 `model` 曾会让那一行落不进 `request_log` 并最终进死信队列**：`request_log.model` 是 `VARCHAR(128)`，而模型名直接取自客户端请求体。M2 时这会让 admin 侧 INSERT 报「数据过长」→ 重试 3 次 → 进 `aihub.metering.dlq`，而 DLQ 成了任何持合法 API Key 的客户端都能触碰的入口。M3 在**计量事件组装时**按码点把 `model` 截到 128（转发给上游的请求体逐字节不变），这条风险因此关闭；`request_log.model` 与客户端实际请求的模型名在全长超过 128 时**不再相同**，按 model 聚合时要记得这一点。
- **admin 起不来会连带把网关降级成「冷缓存一律 `503 service_unavailable`」**：`RequestLogPartitionMaintainer` 是 `ApplicationRunner`，分区覆盖建立不起来时（缺 `pmax`、补建后仍不覆盖、DDL 失败）它直接抛异常，**admin 拒绝启动**。admin 同时是网关的密钥回源后端，所以 admin 不在 = 缓存未命中的 key 一律 fail-closed（**拒绝**，请求走不到上游）—— D4 起对客是 `503 service_unavailable`「我们判不了」（控制面故障），与 `401 invalid_api_key`「我们决定了这把 key 无效」区分开。补分区是 DDL，因此应用数据库账号需要对 `request_log` 的 **`ALTER` 权限**（见 `docs/CONVENTIONS.md` 第 7 节）。
- **流式中途上游断流，网关不会给客户端发 SSE `error` 事件**：设计文档 §9 写的是「发 SSE `error` 事件后关流」，而 M1/M2 的实现是**直接收尾**（响应已提交时 `response.setComplete()`），客户端看到的是**被截断的流**（没有错误帧、通常也没有 `[DONE]`），计量侧照记 `ERROR` / `upstream_stream_error`。这是 M1 起就有的形状，M2/M3 未改动、也未声称改过。

M3 新增的边界：

- **配额（预扣 / 实际校正 / 异步对账）整体属 M4，M3 做的是「限流」而不是「余额记账」**：`RateLimitFilter` 管 QPS/burst，丢弃是**暂时**的（下个窗口自动恢复）；配额扣减是**持久**的。`429 rate_limit_exceeded` 与 M4 的 `QUOTA_EXCEEDED` 是两件事，**不要混用**。M3 不碰 `quota` 表。
- **限流的降级是「近似」**：Redis 不可用时退化为**本机**令牌桶，只看得见本进程的流量 —— 多实例下实际放行量约为「策略 × 实例数」。**降级不等于放行**：超限照样 429。**（D1 修复前）** 2026-09-26 的全栈验收发现这条降级链在鉴权那一段先断了：`stop redis` 时「鉴权缓存未命中」的 key 会被 fail-closed 成 401 并被本地负缓存放大到约 30 秒，请求到不了限流器 —— 所以当时「Redis 挂了仍会 429」只在请求能通过鉴权时成立（详见上面「M3 验收状态」与 `.superpowers/sdd/m3-acceptance.md`）。**D1 已修复**：admin 的 resolve 路径在 Redis 故障时快速回答（实测 4404ms → 811ms），网关只对 admin 的**权威否定**做负缓存（故障结果不缓存），因此有效 key 不再被故障变成 401；模块级端到端实测（真实过滤器链 + Redis 指向死端口 + 真实限流器）已经打到 **429 `rate_limit_exceeded`**，且**从头到尾没有出现任何 401**（D4 起故障期间那一次是 **503 `service_unavailable`** —— 同样是拒绝，请求照样到不了限流器，所以本用例的不变量是「只允许 503（仅限故障生效期间）/ 200 / 429」）。**已于 2026-09-27 在真实 compose 全栈上重跑并达成判据**：14 个**并发**请求（顺序 curl 在 Redis 停机时做不成突发 —— 网关自己的 2 秒 Redis 超时让每个请求赔多次超时，实测约 7 秒/请求，110 秒里本机桶早补满了，会得到「全 200」的假结论）拿到 **10×200 + 4×429**，10 正好等于 key 级 `burst=10`，过程中没有 401、也没有 503。见 `.superpowers/sdd/m3-acceptance.md` 第 11.5 节。
- **熔断的触发面只有 429**：5xx 与超时只做**当次**切换、不打熔断标记（一个坏请求不该把整条渠道关 30 秒）。**所有候选都在熔断中时仍然会尝试**最高优先级那一组（best-effort，会打 WARN 并给 `aihub.route.all-broken` 计数 +1）。
- **配置变更的热生效延迟**：M3 没有 Pub/Sub 发布方（配置 CRUD 在 M4），设计上的收敛手段是「本地 TTL 30 秒 + 快照 `version` 比对」。**但实测的有效延迟不是 30 秒**：本地副本 30 秒过期之后，网关读到的是 Redis 里那份**同版本的旧快照**并采用它，只有共享条目也消失（Redis 快照 TTL **10 分钟**到期、或 Redis 不可用）才会回源 admin —— 2026-09-26 的全栈验收里，改 `channel.base_url` 后 **101 秒**新快照仍不可见，删掉 `aihub:config:snapshot` 后 15 秒内才收敛（`.superpowers/sdd/m3-acceptance.md` 第 7 节）。**部署含义：只要共享条目还在，控制面的一次变更最长要约 10 分钟才到达数据面**，除非刷新或删除该条目 —— 部署方按「改完 30 秒生效」操作会等错时间。`version` 是**毫秒级**时间戳，同一毫秒内改两行会撞车；而 `version = max(updated_at)` 在**删除**最新更新的那一行之后会**回退**（M3 没有删除接口，只能手写 SQL，所以是潜在缺口）—— 网关的比对是严格 `>`，于是更旧的快照可能被 last-good 继续服务。**这个缺口已被登记，且今天没有任何缓解措施**：装配端不会检测回退、也不会告警（见下一条），关闭它是 M4 的条目。
- **未鉴权的 `/v1/**` 请求不会被限流**：鉴权过滤器排在限流过滤器之前，没有合法 key 的请求在 401 处就结束了。因此未鉴权洪峰不在这套限流的保护范围内。
- **限流指标与熔断日志不对外暴露**：`aihub.ratelimit.rejected` / `aihub.ratelimit.degraded` / `aihub.ratelimit.fail_open` / `aihub.route.all-broken` 已注册，但 `/actuator/metrics` 仍然只 `include` 了 `health,info`（对外暴露属 M6）。
- **`channel.models_json` 不参与路由**：路由只用 `model_route`；`GET /v1/models` 的模型集合来自「`model_route` 里出现过的模型名 ∪ 遗留默认模型」。`channel.models_json` 的路由语义与渠道 CRUD 一起属 M4。
- **快照契约里的 `defaultModel` 今天是一条死字段**：admin 侧没有 `aihub.upstream.default-model` 属性，`ConfigSnapshotService` 因此**永远下发 `null`**；网关虽然解析并保留了它，但 `ModelsController` 用的是网关**自己的** `aihub.upstream.default-model`（`UpstreamProperties`），从不读快照里的那个分量。两端一个不填、一个不读 —— 不要把它当成一条生效中的控制面能力。
- **`GET /v1/models` 的冷缓存读会回源一次**：它读同一个 `ConfigClient`，缓存冷/过期时会**同步**回源 admin（有 5 秒上界），冷启动时这个端点的首字节可能慢到秒级。生产默认 `aihub.ratelimit.enabled=true`（请求链已在限流过滤器里切到 `boundedElastic`），而**网关测试的默认是 `false`** —— 「生产调度」与「测试调度」不是同一个。
- **「非超时」的上游中途中断仍会被记成客户端断连**：响应已提交之后上游把连接断掉（不是读超时、也不是连不上）时，Reactor 给出的形状与客户端跑掉完全相同，那一行因此记 `CANCELLED` / `client_disconnected` 而不是 `ERROR` / `upstream_stream_error`。按 `client_disconnected` 统计客户端行为前必须知道它包含这部分上游故障（`WireMockChannelFaultInjectionTest#midStreamBreakIsMeteredAsAClientDisconnect` 把这个缺口钉成了可执行事实）。
- **`client_disconnected` 的判定只在 Netty 上成立**：中继把「上游响应体订阅被取消」当作客户端断连（M3 Task 10）。在 Servlet 模式的容器（Tomcat / Jetty）上，响应**正常结束**时也会触发同一次取消，于是每一次成功响应都会被误记成断连。网关测试因此把 Web 服务器工厂显式钉成 Netty（`NettyWebServerTestAutoConfiguration` + 一条断言）—— 这也是为什么 M3 引入 WireMock 时必须同时加那个测试配置（WireMock 的 Jetty 12 绑定会把 Jetty 的 servlet 类带进 classpath，Boot 的反应式服务器候选里 Jetty 排在 Netty 前面）。
- **行为变更（M3，对客户端有破坏性）**：只要网关手上有一份**真实控制面快照**，`model` 不在快照的 `model_route` 里就直接返回 **`404` `model_not_found`**（OpenAI 形状错误体）；M2 会把任何模型名原样转发给上游、由上游决定。也就是说「随便编一个模型名试试」的客户端在 M3 上会拿到 404 而不是上游的响应。仅当快照完全没有（admin 从未可达、两级缓存皆空）而回落成**遗留单渠道**快照时，才恢复 M2 的「照单转发」。`GET /v1/models` 可以预先拿到合法模型名。
- **`model_not_found`（404）会被计量成 `gateway_error`**：它是控制器入口的早返回，计量按「未预期错误」记 `ERROR` / `gateway_error` —— 一个**客户端**问题会抬高网关的错误计数。按 `gateway_error` 告警时先看 HTTP 状态码是不是 404。
- **`request_log` 没有 `channel_id` / `api_key_id` 的索引**：M3 新填的这两列是 M4 按渠道 / 按 Key 聚合的前提，但 V1 没有为它们建索引（M3 不加迁移），那类查询会在分区表上扫描。
- **dev-only 的渠道 seeder 默认关闭**：`AIHUB_DEMO_SEED_ENABLED=true` 才会创建 bean；打开时还要求 `AIHUB_CHANNEL_MASTER_KEY` 与 demo 上游 base-url（demo 密钥没有默认值），否则**在任何写库动作之前**抛异常。它是本地联调工具，不是初始化数据的手段，也**不是** M4 的控制台。
- **admin 侧的快照装配既不检测版本回退、也不为此告警**（更正：这里与 `docs/CONVENTIONS.md` 此前都承诺过一条**并不存在**的 WARN）。`ConfigSnapshotService` 只是对三张表各取一次 `max(updated_at)` 再取最大值，它没有「上一次 version」这个概念，发现不了回退、也发现不了同一毫秒撞车；该类唯一的 WARN 是「同一维度存在多条 ACTIVE 限流策略」，与版本无关。因此上面那条版本回退缺口**今天没有任何缓解措施** —— 既没有高水位持久化，也没有任何可观测信号，关闭它是 **M4** 的条目。
- **Redis 仍然没有密码**（`requirepass`）与网络隔离加固 —— 未在 M3 做（M3 已收口），仍是对生产部署的要求（见 `docs/CONVENTIONS.md` 第 6 节）。

M4 新增的边界（时间基准，2026-09-29，独立评审 `a14e209` 的产物）：

- **把 `expire_at` 的解释从「连接时区」改成「UTC」是一次**数据含义变更**，方向取决于**那条连接的偏移符号**（评审 I-4；2026-09-30 复测）。`api_key.expire_at` 现在按 UTC 墙上时间解释（实体用 `LocalDateTime` + 显式 `ZoneOffset.UTC`）。**发布配置下没有风险**：`application.yml` 与 `docker-compose.yml` 的 JDBC URL 都带 `serverTimezone=UTC`（偏移 0），旧代码在那条连接上写进去的本来就是 UTC 墙上时间（评审实测新旧读法 `old − new = 0`）。但**任何**用非 UTC 连接（覆盖了 `SPRING_DATASOURCE_URL` 而没带该参数、或用旧的本地/测试配置跑过写入）写下的行，其 `expire_at` 存的是那个连接时区的墙钟；改按 UTC 解释之后这些行的瞬时位移量 `new − old` **等于该连接的偏移**，方向由符号决定 —— 同一台容器复测（同一格写 `2026-01-01T12:00:00.123Z`）：`Asia/Shanghai`（+08:00）⇒ `+28800000 ms`，瞬时**后移**，**已经过期的 key 在该偏移那么长的时间里仍然可用**（fail-open，安全洞）；`America/New_York`（该瞬时 −05:00）⇒ `−18000000 ms`，瞬时**前移**，key **提前**过期（fail-closed，功能回归）。**这两个毫秒数有可重跑的产物**：`com.aihub.admin.time.TimeBasisOffsetFixtureTest`（三条用例各自把 `connectionTimeZone` 钉成 `UTC` / `Asia/Shanghai` / `America/New_York`，跑 `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=TimeBasisOffsetFixtureTest"`）—— 按 `docs/CONVENTIONS.md` §8 的纪律，文档里的数字必须点名产生它的产物。简式：位移最多**一个连接偏移**、方向不定，**不是固定的 8 小时**（8 小时只是本机 Asia/Shanghai 的观测值，偏移本身还随夏令时变）。处理办法也是方向相关的：先确定写那批行的连接时区（URL 上的 `serverTimezone` / `connectionTimeZone`；不带参数时就是当时那个 JVM 的默认时区）在**那些行的时间点**上的偏移 `o`（分钟、带符号），再 `UPDATE api_key SET expire_at = expire_at - INTERVAL <o> MINUTE`（`o` 带符号 ⇒ 西半球实际是往后加），或**重新签发**受影响的 key（更稳：行本身无法可靠区分基准）。同一次改动也会把这类行的 `updated_at` 改读成 UTC：偏移为正 ⇒ 版本**向前**跳（相对无害）；偏移为负 ⇒ 版本**向后**跳 —— 那是**已登记的 M3 版本回退缺口**（网关严格 `>` 比版本），**不是无害**。**没有**启动检查、也**没有**自动迁移 —— 这是已知边界，不是已关闭项。
- **时间基准不再依赖连接参数与 JVM 时区**：配置快照水位（`ConfigSnapshotService.maxUpdatedAt`）与 API Key 过期（`ApiKeyService` 的写入/读回）都走 `LocalDateTime` + 显式 `ZoneOffset.UTC`，因此 JDBC URL 上删掉 `serverTimezone=UTC`、或换一个时区的 JVM/CI 镜像都不会改变结果。**纪律**：新时间列照抄这个写法；连接时区仍然要**显式钉死**（发布 URL 已经这么做）—— 代码不依赖它 ≠ 可以随便配（见 `docs/CONVENTIONS.md` §7 第 2 条）。**覆盖边界**（2026-09-30 登记）：套件现在只跑生产（UTC）方言，非 UTC 只由那个判别上下文覆盖**两条已知路径** —— 将来新增时间列**不会**被任何用例顺带覆盖，必须显式加进 `TimeBasisIsConnectionFlavourIndependentTest`（见 `docs/CONVENTIONS.md` §8 第 4/5 条）。
- **控制台（`/api/**`）不隔离租户：任何 `ADMIN` 令牌都能跨租户读写**（2026-09-30 **定死为有意的设计**，完整规则与理由见 `docs/CONVENTIONS.md` §10）。M4 的控制台是**平台运营台**（"租户自助注册、细粒度 RBAC"是本计划的**非目标**，只做 `ADMIN`/`VIEWER` 两级），因此 `/api/logs`、`/api/audit` 按**请求参数里的 `tenantId`** 查任意租户，而资源列表（`/api/api-keys`）缺省只回**令牌所属租户**——"读按租户、写不限租户"是**有意**的不对称，不是遗漏。⇒ **在出现第一个非平台方账号、或控制台对非可信网络暴露、或引入租户自助之前，必须先落地租户隔离**（三者任一成立时这条前提即失效）。
- **列的基准现在用数据库自己的 UTC 时钟断言，但数据库**会话**时区没有被应用钉住**（评审 I-3 的残余）：V1 的配置表用 `DEFAULT CURRENT_TIMESTAMP(3)` / `ON UPDATE CURRENT_TIMESTAMP(3)` 生成时间列，那写的是**数据库会话时区**的墙上时间，而应用侧把它当 UTC 读。今天容器是 `@@session.time_zone = SYSTEM` + `@@system_time_zone = UTC`，`ConfigSnapshotServiceTest` 与 `ConnectionTimeZoneFlavourTest` 现在会断言这一点（会话时区一旦不是 UTC 就会红），但**没有**任何配置把会话时区钉成 UTC（`serverTimezone=UTC` 只钉驱动的换算时区，不会改会话）—— 换一台默认时区不是 UTC 的 MySQL，配置版本会整体偏移一个时区。


## 技术栈

Java 21（编译目标）· Spring Boot 3.5.16 · MyBatis-Plus 3.5.17 · MySQL 8 · Flyway · Redis 7 · RabbitMQ 3.13 · WebFlux · Testcontainers · WireMock 3.9.1（**仅 `aihub-gateway` 的 test 作用域，进程内**，见下）· Docker Compose

> **为什么技术栈里多了 WireMock**：设计文档 §4.2 把「WireMock 在 M3 引入时再锁定版本」写成了待办，§10 / §12 又点名用 WireMock 做多渠道故障注入验收，所以 M3 在 `aihub-gateway` 的**test 作用域**引入 `org.wiremock:wiremock:3.9.1`（另加同版本的 `wiremock-jetty12`）：进程内起 stub，**不需要 Docker / broker / Redis**，生产依赖零新增。`aihub-admin` 侧一个字节都没动。为什么还需要 `wiremock-jetty12` 与一组 `<exclusion>`：WireMock 3.x 的 core 自带 Jetty 11 绑定，而本项目的父 POM（`spring-boot-starter-parent`）把 `org.eclipse.jetty:*` 统一管到 Jetty 12，两者混在一个 classpath 上会直接 `FatalStartupException: Jetty 11 is not present` / `IncompatibleClassChangeError`（细节写在 `aihub-gateway/pom.xml` 的注释里）。JDK 的 `com.sun.net.httpserver.HttpServer` 夹具**继续保留**（`FakeUpstream`，承担细粒度口径），WireMock 与它是并存关系、不是替代。

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

`POST /v1/chat/completions` 会在网关侧被计量，最终由 admin 落到 `request_log` 一行。**只有这一个端点会**：网关全树只有一个发布点（该控制器的 `doFinally`），`GET /v1/models` 以及所有在控制器之前就被短路的响应（`401 invalid_api_key`、`503 service_unavailable`、`404` 等）都带 `x-request-id`，却既不产生计量事件、也不在 `request_log` 落行（详见「已知边界」）。要点：

- **`request_id` 就是响应头 `x-request-id`**：网关为每个 `/v1/**` 请求铸一个 UUID 并写进响应头；`POST /v1/chat/completions` 再把它写进计量事件。客户端自带的同名请求头不回显；上游返回的同名头也不透传（幂等键必须是网关自产的那个）。
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

当前实测（**D1 修复之后**，`DOCKER_HOST=tcp://127.0.0.1:2375`，从 `clean` 开始）：`mvn -B clean test` → `BUILD SUCCESS`，**Tests run: 497, Failures: 0, Errors: 0, Skipped: 0**（aihub-common 56、aihub-web 95、aihub-gateway 346；Testcontainers 的 MySQL 8.4 / Redis 7 / RabbitMQ 3.13 真的起了容器，Flyway `Successfully applied 1 migration`）。相对 M3 收口的 491（56 / 92 / 343），增量是 D1 的 7 条新用例：`ApiKeyResolveRedisOutageTest` 3 条（admin 侧 resolve 时延）+ `ApiKeyRedisOutageAuthTest` 4 条（网关侧故障不缓存 / 权威否定仍负缓存 / 限流可达 / 有效 key 打到上游）。**对账说明（诚实登记）**：仓库里保存的最后一份全量日志 `.superpowers/sdd/_m3fix-full.log` 自身合计是 **490**（56 / 92 / 342），比上面那个 491 少 1 —— 逐类比对显示除上述两条新类之外**没有任何类的用例数发生变化**（即没有既有断言被削弱或删除）。再往前：M3 的 Task 15 是 **490**（56 / 92 / 342，差额 1 条是收口时新增的「主配置的生产默认值」断言）；M3 的 Task 14 收口时 481，481→490 是 M3 的验收测试（WireMock 多渠道故障注入 8 条 + 一条「测试跑在 Netty 上」的守卫）；**M2 收口时是 200**（aihub-common 21、aihub-web 47、aihub-gateway 132）。注意 Maven 的**进程退出码不可信**（本机见过 `BUILD SUCCESS` 却给出 `[exit code: 1]`），判定以 surefire 汇总 + `BUILD SUCCESS` 为准；另外 Surefire 对含 `@Nested` 的外层类会打印 `Tests run: 0`（`ModelsControllerTest` 就是这种），那种情况下以 XML / 合计为准。

只跑 M3 的验收类（进程内 WireMock，**不需要 Docker**）：

```powershell
mvn -B clean test -pl aihub-gateway -am "-Dtest=WireMockChannelFaultInjectionTest"
```

聚焦跑单个类时用**逗号**而不是加号（`-Dtest=A,B`）—— `-Dtest=A+B` 在 Surefire 3.5.6 上会被当成**一个**类名，配合本仓库 `.mvn/maven.config` 里的 `-Dsurefire.failIfNoSpecifiedTests=false`，它会**静默跳过**你要跑的类却照样 `BUILD SUCCESS`；`-Dtest=A,B` 在 pwsh 里还要加引号（逗号会被 shell 吃掉）。另外**单独构建一个模块要带 `-am`**：`.m2repo` 里有一份空的 `aihub-common` jar，不带 `-am` 会报假的「package does not exist」。

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
