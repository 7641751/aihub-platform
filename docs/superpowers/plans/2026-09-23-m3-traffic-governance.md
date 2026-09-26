# aihub-platform M3（流量治理）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把网关从「单渠道字节透传 + 计量」升级为**受治理的数据面**：Redis + Lua 令牌桶限流（Redis 挂了降级成本机令牌桶并继续服务）、`model → 多候选渠道` 的权重 + 优先级 + 健康度路由、上游 429 / 5xx / 超时下的**自动故障转移与跨实例熔断**（且只在「尚未向客户端转发任何字节」时切换）、渠道密钥的 AES-GCM 密文下发与**网关本地解密**（明文密钥永不跨网络、永不进日志），并顺手清掉 M2 留下的三处小尾巴。

**Architecture:** 控制面仍然只有一个真相源（admin），网关**不连数据库**（决策 A 延续）：admin 用一把来自环境变量的主密钥把 `channel.api_key_cipher` 加密后，连同 `model_route` / `rate_limit_policy` 一起放在**一个 HMAC 签名的 `GET /internal/config/snapshot`** 里；网关按 §6.3 的**三级读取两级缓存**取它（Caffeine 30s → Redis → admin，带单一飞行与快照 `version` 比对），并把它切成四块纯数据（渠道 / 路由 / 限流策略 / 默认模型）交给四个消费者：`RateLimitFilter`（Lua 令牌桶，`tenant` 维度）、`RouteResolver`（按 `priority` 分组 + 组内按权重随机 + 跳过熔断渠道）、`ChatRelayController` 的失败转移循环、以及本地解密渠道密钥的 `ChannelKeyDecryptor`。**故障转移的合法性由「响应是否已提交」判定**：上游响应体第一个字节写回客户端之前，响应未提交，切换是合法的（客户端什么都没看到）；一旦 `response.isCommitted()`，任何切换都会把半截响应拼成脏数据 —— 那正是设计文档 §9「仅在未输出任何 token 时允许切换」的机器可判定形式。熔断状态放 Redis（30s TTL，跨实例共享），Redis 不可用时退化为**进程内**熔断表（单机近似），**任何降级都不阻断数据面**。

**Tech Stack:** Java 21（编译目标，运行于 JDK 25.0.2）、Spring Boot 3.5.16、WebFlux（Reactor Netty）、Spring Data Redis 7（Lettuce，同步 API）+ Lua、Caffeine、MyBatis-Plus 3.5.17、MySQL 8.4、Flyway、RabbitMQ 3.13、JUnit 5 + AssertJ + Mockito、Testcontainers（仅 admin 侧）、JDK `com.sun.net.httpserver.HttpServer`（网关侧假上游 / 假 admin，M1/M2 既有夹具，**保留**）、**WireMock `org.wiremock:wiremock:3.9.1`（仅 gateway 的 test 作用域，用于 spec §10 / 里程碑验收要求的多渠道故障注入；进程内起 stub，不需要 Docker）**、Maven 3.9.12。

## Global Constraints

- 项目根目录：`D:\PycharmProjects\aihub-platform`；本计划在分支 `m3`（由控制器从 `master`（`e4f4103`，含标签 `m0` / `m1` / `m2`）切出，**本计划不负责建分支**）。
- 编译目标固定 Java 21（`<maven.compiler.release>21</maven.compiler.release>`），本机运行 JVM 为 JDK 25.0.2。不要改。
- Spring Boot 固定 `3.5.16`；MyBatis-Plus 固定 `3.5.17`。**M3 不新增任何生产依赖**（AES-GCM 用 JDK `javax.crypto`；Lua 用已有的 Spring Data Redis）。**测试作用域只新增一个依赖**：`aihub-gateway` 的 test 作用域加 `org.wiremock:wiremock:3.9.1`（**只有 gateway、只有 test 作用域**，用于 spec §10 / §12 要求的多渠道故障注入验收；版本必须显式锁死，它不在 Spring Boot BOM 里）。M1/M2 的 JDK `com.sun.net.httpserver.HttpServer` 夹具（`FakeUpstream` / `FakeAdminServer`）**继续保留并继续承担细粒度用例**（见「决策登记」第 11 条：本机已实测 WireMock 可解析且**进程内**可跑通）。
- 包名前缀：`com.aihub.common` / `com.aihub.dao` / `com.aihub.service` / `com.aihub.mq` / `com.aihub.admin` / `com.aihub.gateway`。
- 端口不变：admin `8081`、gateway `8080`、MySQL 宿主 `3307`、Redis 宿主 `6380`（容器内 `6379`）、RabbitMQ `5672` / 管理台 `15672`。
- **`aihub-common` 在 main 作用域必须零依赖**（不许出现 Jackson、Spring、Redis、Lettuce 的任何 import）：它只能出现 JDK 类型。渠道密钥加解密（`javax.crypto` / `java.util.Base64` / `java.security`）因此放在 `aihub-common` —— 它是 **JDK 类型**，不违反零依赖，而且 admin（加密）与 gateway（解密）必须共用**同一份**实现（见「决策登记」第 1 条）。
- **两套响应契约不要混用**：admin（含 `/internal/**`）是 `{"code","message","data"}`；网关 `/v1/**` 是 OpenAI 兼容错误体 `{"error":{"message","type","param","code"}}`。设计文档 §9 第 334 行写的「流开始前失败 → 统一错误体 `{"code","message"}`」**与 M1 定下、并被官方 OpenAI SDK 验收过的契约冲突，以 M1 为准**，该行 spec 文本按「已被取代」登记（见「决策登记」第 13 条）。
- **M1 的字节级透传是铁律**：上游状态码、`Content-Type`、响应体字节三者在流式与非流式下都必须原样回写。**故障转移不得违反它**：唯一合法的切换时机是「响应尚未提交（上游第一个响应体字节尚未写回客户端）」。任何「先把一部分 body 写给客户端、再换渠道继续写」的实现都是错的。
- **渠道明文密钥绝不出现在**：日志、错误体、指标名/标签、测试夹具、任何 tracked 文件、任何提交信息里。测试一律用**一眼可辨的合成值**（如 `sk-channel-plaintext-synthetic`）。主密钥只来自环境变量（`AIHUB_CHANNEL_MASTER_KEY`），**不落库、不进镜像**。
- **禁止读 / 打印 / echo `.env` 或任何密钥文件**；不要执行 `docker compose config` 或任何会把 `.env` 插值打进 stdout 的命令。要判断某个环境变量是否存在，只判断**是否为空**，不要打印它的值。M1 已经因此泄漏过一次真实上游 key。
- **不得新增 Flyway 迁移脚本**，`V1__init_schema.sql` 已执行过、禁止修改。演示渠道数据用**开发专用的 seeder**（`@ConditionalOnProperty` 默认关闭）写入，理由见「决策登记」第 12 条（`aihub-web` 的 `SchemaMigrationTest.flywayAppliesExactlyOneMigration` 断言恰好 1 条迁移，任何加迁移的做法都必须改这个断言，那是在削弱护栏）。
- 所有时间字段按 UTC 存储（`datetime(3)`）。令牌桶的时间基准是**调用方传入的毫秒时间戳**（不是 Redis `TIME`），熔断的时间基准是 **Redis TTL** —— 两者的理由分别写在各自类的 javadoc 里。
- **`aihub-gateway` 的测试永远不允许依赖 Docker，也不允许要求有活 broker 或活 Redis**：Redis 相关的故障降级用例一律用「指向不存在端口 / Mockito 桩」构造（M1/M2 已确立同一手法）。**WireMock 也必须以进程内（in-process）方式使用**（`@WireMockTest` / `WireMockExtension`，stub 起在同一个 JVM 的随机端口上）—— 这一点在决策 11 里已在本机实测过，因此新增这个测试作用域依赖**不**放松本条约束；**不允许**用 `docker run wiremock/...` 或任何外部容器/远程 stub 服务。admin 侧的集成测试沿用 `AbstractIntegrationTest`（Testcontainers 单例容器，MySQL + Redis + RabbitMQ 已在基类里）。
- 每个 Task 完成后立即 commit（conventional commits：`feat:` / `fix:` / `test:` / `docs:` / `chore:`），**只 stage 显式路径**，禁止 `git add -A` / `git add .`（工作树里有第二个写入者）。
- **每个任务的提交必须让整个反应堆编译通过，且该任务自述的测试全绿 —— 不允许跨任务占位，也不允许任何任务依赖「后续任务才引入」的生产代码改动。** 共享契约（例如 `ApiKeyView` 的数值主键 `apiKeyId`、`ApiKeyCacheCodec` 的段数）必须在**第一个需要它的任务之前**就位：契约变更与它的生产者/消费者各一行改动放在**同一个提交**里，之后的任务直接读真值，不写「先传 `null`、X 任务再补」这类占位，也不写「此处待 Y 任务收口」这类注释。判定方法：每个任务结束前跑一次全反应堆 `mvn -B -q test-compile -DskipTests`，它必须绿。**本计划曾把 `ApiKeyView`/`ApiKeyCacheCodec` 的契约变更放在 Task 11，导致 Task 9 必须带一个跨任务的 `apiKeyId` 占位 —— 2026-09-26 已把该契约前移到 Task 2 修掉，本节就是那次修正留下的一般规则。**
- 命令一律在项目根目录执行；不使用 Maven wrapper，用本机 `mvn`。
- **不修改 `docker-compose.yml` 里 Redis 的宿主端口绑定**（`127.0.0.1:6380:6379` 是用户刻意设的）。
- **不做的事**（本里程碑明确越界，评审时按此判断）：`QuotaFilter` 的预扣 / 校正 / 对账（§6.2，属 M4，见「决策登记」第 12 条）、Redis Pub/Sub 失效的**发布方与订阅方**、管理台、`/api/channels` CRUD、租户与 API Key 管理、审计、账单、文档流水线。

### 本机环境前提（写给实施者，**每一条都在别的里程碑上真实踩过**）

- 每次 shell 调用都是**全新的 `pwsh` 进程**：变量、工作目录都不保留。每条命令都必须显式带 `workdir`（或先 `cd`）。
- **Docker 只能走 TCP**：所有 `docker` CLI 命令必须带 `-H tcp://127.0.0.1:2375`（默认的命名管道被沙箱拒绝）。Maven / Testcontainers 需要 `$env:DOCKER_HOST='tcp://127.0.0.1:2375'`（在**同一次** `mvn` 调用前 export）。
- **永远不要修改 `~/.testcontainers.properties`，也永远不要给它写 BOM**（历史上写坏过一次，全部容器测试变红）。
- `.mvn/maven.config` 是**未跟踪、已 gitignore** 的本机适配文件（`-Dmaven.repo.local=.m2repo`、surefire 的 argLine、`-Dsurefire.failIfNoSpecifiedTests=false`）。不要修改、不要 `git add`。
- **`-Dtest=A+B` 在 Surefire 3.5.6 上不会选中两个类**：它被当成**一个**类名，而 `.mvn/maven.config` 又设了 `-Dsurefire.failIfNoSpecifiedTests=false`，于是运行照样打印 `BUILD SUCCESS` 却**静默跳过**你要跑的类。永远用**逗号形式** `-Dtest=A,B`，在 pwsh 里要加引号（逗号会被 shell 吃掉）。
- **单独构建一个模块时必须加 `-am`**：`.m2repo` 里存着一个**空的** `aihub-common` jar，不加 `-am` 会报一堆假的 "package does not exist"。
- **Maven 的退出码不可信**：本机实测过 `BUILD SUCCESS` 却给出 `[exit code: 1]`，也见过同一命令退出 0。判定标准永远是 **surefire 汇总行 + `BUILD SUCCESS`**，不是 `[exit code: N]`。Surefire 对**含 `@Nested` 的外层类**会打印 `Tests run: 0`：那种情况下以 `target/surefire-reports/*.xml` 为准。
- `Set-Content -Encoding UTF8` 在这个 shell 上**会写 BOM**。写文件一律用 `write` / `edit` 工具（它们不写 BOM）。禁止 BOM；`docs/CONVENTIONS.md` 与 README 都不带 BOM。
- **绝不要用 `Get-Content` / `Set-Content` / `[System.IO.File]::WriteAllLines` 处理本仓库的中文文档**：本机 ANSI 代码页是 **GBK（cp936）**，`Get-Content` 会按 GBK 解码 UTF-8 文档，回写时把整篇中文变成乱码（本计划编写时就踩过一次）。要批量改写中文文档，只能用 `write` / `edit` 工具。
- **工作树里有第二个写入者**（用户或他们的编辑器/代理会改文件）。每次派发子代理前、以及每个子代理返回后都要 `git status --porcelain`；发现非自己改动的文件时**不要**顺手 stage，先报告。
- 磁盘上已有 `mysql:8.4`、`redis:7-alpine`、`rabbitmq:3.13-management-alpine`、`testcontainers/ryuk:0.12.0` 镜像。
- **`git push` 在本机不可能成功**（hosts 把 `github.com` 指向 127.0.0.1，真实 IP 也被出口阻断；只有 `gh` 的 REST API 通）。**不要尝试 push**，只做本地提交；控制器会在里程碑末尾通过 GitHub Git Data API 重放提交。
- 基线（`e4f4103` 实测）：`aihub-common 21`、`aihub-web 47`、`aihub-gateway 132` = **200 项通过 / 0 失败 / 0 错误 / 0 跳过**。M3 的每个任务都要报告**实测**数字，不要照抄本计划的估算。

---

## 决策登记（设计文档沉默处的显式选择，评审时逐条看这里）

下面每一条都是「设计文档没有钉死」或「需要与既有实现取舍」的地方。实施者按这里的决定执行，**不要自由发挥**；评审者按这里判断是否越权。

| # | 决策 | 理由 | 影响面 / 证据 |
|---|---|---|---|
| 1 | 渠道密钥加解密放在 **`aihub-common`**（`com.aihub.common.crypto`），只用 JDK `javax.crypto` / `java.util.Base64` / `java.security`，**不引任何第三方加密库**。零依赖规则因此**仍然成立**（零依赖 = 零第三方依赖，不是「只能用 `java.lang`」；`InternalHmac` 已经先用了 `javax.crypto`）。 | admin 要加密、gateway 要解密，**必须是同一份实现**：各写一份等于把「密文格式 + 主密钥解析规则」撕成两半，任何一侧单方面改动都不会编译报错，只会表现为「网关解不开密钥」。放 `aihub-common` 是唯一能同时满足「两侧共用」与「gateway 不依赖 admin 业务模块」的位置。 | 新增 `aihub-common` 的 `crypto` 包 2 个类；`aihub-common/pom.xml` **不动**（仍然只有 test 作用域的 starter-test）。Task 1 有专门的「main 作用域零依赖」验收项。 |
| 2 | 密文是**自描述**的：`v{版本号}:{base64(nonce‖ciphertext)}`（nonce 固定 12 字节前置）。解密时**从载荷里读版本**去主密钥表里取对应密钥，`channel.key_version` 只作元数据/运维展示。主密钥环境变量格式为 `v1:<base64 32 字节>,v2:<base64 32 字节>`（逗号分隔、允许空格）。 | 双版本共存的目的是「先解密旧版本重加密为新版本，全部完成后再下线旧密钥」。如果解密必须先去查 `key_version` 列，那么轮换期间任何一条 `key_version` 写歪的行都会变成不可恢复的数据丢失；自描述载荷让**回退**安全：把新密钥从环境变量里去掉，旧密文照样可解。`channel.key_version` 保留为权威展示值（写入时与载荷版本一致）。 | 新增 `ChannelKeyRegistry.parse(String)`；解析规则被固定向量测试钉住（含「最多 8 个版本」的上界，防止有人在环境变量里塞一长串）。 |
| 3 | 主密钥**至少需要一个版本**才能加密；一个版本都没有时 **admin 拒绝加密**（`IllegalStateException`，seeder 因此直接失败并打出配置指引），而 **gateway 拒绝解密**（返回空 `Optional`，按「渠道不可用」处理，绝不抛到请求路径上）。 | 空主密钥是配置错误，不是运行时状态。admin 侧是写入路径，静默写坏数据比启动失败糟得多；gateway 侧在请求路径上，抛异常会把配置错误变成客户端 500 —— 与 M1「缓存故障绝不变成 500」的处理方式一致。 | gateway 的 `AesGcmChannelCipher.decrypt` 永不抛异常；admin 的 `encrypt` 在无版本时抛 `IllegalStateException`。两条都有测试。 |
| 4 | Redis 快照缓存与本地 Caffeine 载荷共用**同一份**分隔符编解码 `ConfigSnapshotCodec`（不用 JSON）：首行 `#v1\|{version}\|{defaultModel}\|{generatedAtEpochMilli}`，之后是 `C\|…` / `R\|…` / `L\|…` 行，字段内转义 `\` `\|` `\n` `\r`。 | gateway 侧其实**可以**用 Jackson（它自带），但「缓存载荷」与「本地载荷」共用一份编解码只需维护一个转义器，而且 Redis 里的字节可以被测试当作固定向量钉住。行为与 `MeteringEventCodec` / `ApiKeyCacheCodec` 完全一致。**这不是跨服务契约**（admin 不读它，admin 发的是 JSON），因此不像 `MeteringTopology` 那样有「单侧改名不报错」的风险。 | 编解码只出现在 gateway 与 `aihub-common`；`ConfigSnapshotCodecTest` 用固定向量钉字段顺序。 |
| 5 | 快照 `version` 用**单调时间戳**：`max(channel.updated_at, model_route.updated_at, rate_limit_policy.updated_at)` 折算成 epoch 毫秒；没有任何配置行时用 `0`。**版本只增不减**由「任何配置写入都会推进 `updated_at`」保证（V1 三张表都有 `ON UPDATE CURRENT_TIMESTAMP(3)`）。 | 需要一个「能比较新旧」的标量来支撑 §6.3 的「本地版本落后则丢弃并回源」。计数器（如行数）会漏掉「改字段不改行数」，哈希要读全表两次，时间戳是最便宜且随每次写入必然推进的量。⚠️ **已知缺口（诚实登记）**：M3 没有任何配置写入方（CRUD 在 M4），因此 `version` 的变化路径只有 seeder 与手工 SQL；两行配置在**同一毫秒**内被改会撞车（MySQL `updated_at` 精度是毫秒）—— M4 的控制台串行写入下可接受。 | admin 侧 `ConfigSnapshotService.version()` 有真实 MySQL 用例（改一行 → 版本严格变大）。 |
| 6 | 快照**取不到时的降级顺序**：本地 Caffeine（命中即用）→ Redis（命中即用，**且用它的 version 刷新本地**）→ admin。admin 不可达时**继续用已有的陈旧快照**（哪怕过期），只有在**完全没有任何快照**时才用「遗留单渠道」兜底（`aihub.upstream.*`，即 M1/M2 的形状）。 | §9 的总原则是「数据面永不因控制面故障而整体不可用」。一个 30 秒前的路由表远比「没有路由表」安全。完全无快照（冷启动 + admin 挂）时，退回 M1 单渠道至少还能服务 —— 比 503 好。 | 三种状态各有用例：`adminFailureKeepsServingTheCachedSnapshot`、`noSnapshotAnywhereFallsBackToTheLegacySingleChannel`、`emptySnapshotFromAdminStillAllowsTheLegacyFallback`。 |
| 7 | **限流按 `tenant + api_key` 两个维度选策略**（**2026-09-26 依控制器 pre-flight 评审修订**：原文只做 `tenant` 维度、key 级行「读进来但不参与判定」，是被评审打回的那一版）。选取顺序是确定性的：① 与本次请求 `apiKeyId` 匹配的 ACTIVE key 级行（`api_key_id = 该值`）优先；② 没有则取该租户的租户级行（该租户 `api_key_id IS NULL` 的 ACTIVE 行）；③ 都没有才用内置默认 `qps=10, burst=20`（与 V1 的列默认值一致）。**每一级内部**多条时取列表里的**最后一条**（读取顺序与 tie-break 见决策 17）。非正的 qps/burst 一律回落到内置默认值（**保留**原有的按行校验规则）。**桶 key 不变**，仍是 `aihub:ratelimit:{tenantId}:{sha256(secret)}`（决策 8）—— 每个密钥的**状态**本来就是隔离的，本次修订只让**策略查找**变成两级。 | **为什么原文是错的（诚实登记）**：原文的理由是「共享的 `ApiKeyView` 在 M3 之前只有字符串 `keyId`，没有 `api_key` 的数值主键，因此无法把请求映射到 `rate_limit_policy.api_key_id`」。这条在同一个计划里就被否掉了：决策 14 正好给 `ApiKeyView` 补了可空的数值 `Long apiKeyId`（admin 侧填 `api_key.id`），而 `ApiKeyView` 由 M1/M2 的 `ApiKeyAuthFilter` 放进 exchange 属性 `ATTRIBUTE_KEY_VIEW`，限流过滤器又**排在鉴权之后**（`@Order(HIGHEST_PRECEDENCE + 150)` > 鉴权），因此**认证后的视图连同数值主键在限流器手里本来就已经可得**；真实 DDL 里 `rate_limit_policy.api_key_id` 就是 `BIGINT NULL`，指向 `api_key.id`。设计文档 §8.1 ② 明写维度是 **`tenant + api_key`**（spec 第 284 行）。原文把「M4 才接 key 级」写成结论，实质是把 spec 要求的维度主动降级，故按评审改回两维。 | 快照里 `RatePolicy` 继续带 `apiKeyId`（`null` = 租户级），但**两级都参与判定**：`ConfigSnapshot.keyPolicies(tenantId, apiKeyId)`（Task 2）+ `ConfigSnapshot.tenantPolicies(tenantId)`，由 `RateLimitResolver.resolve(tenantId, apiKeyId)` 依次取（Task 4）。用例换成**真覆盖**：`prefersTheKeyLevelPolicyWhenTheApiKeyIdsMatch`（id 匹配时 key 级赢过租户级）与 `usesTheTenantLevelPolicyWhenThereIsNoKeyLevelRow`（没有 key 级行 → 用租户级行），原 `RateLimitResolverTest.ignoresKeyLevelPolicies` **删除**。**契约顺序**：数值主键（决策 14）在 **Task 2** 就随 `ApiKeyView` 与 `ApiKeyCacheCodec` 一起进共享契约（同一个提交里含 admin 侧填充与 gateway `AdminClient.parse` 的透传），因此 Task 4 的两级解析与 Task 9 的「请求 → `apiKeyId`」都**直接读真值**，没有任何跨任务占位、也没有「后续任务收口」这一步（见全局约束与附录第 14 条）。 |
| 8 | 令牌桶 key 布局：`aihub:ratelimit:{tenantId}:{sha256(secret)}`，类型是 **Hash**（字段 `t` = 令牌毫数、`k` = 上次填充的毫秒时间戳）；**首次创建时设 `PEXPIRE = max(60s, 20 × burst/qps 秒)`**。本机降级桶的 key 前缀是 `local:ratelimit:`（**与 Redis 布局分开**）。 | `tenant + api_key` 是 §8.1 ② 的明文维度。用 Hash 而不是 String，是为了让「令牌数 + 时间戳」在一次 `HMGET` 里原子读出，并让 `PEXPIRE` 与「两个字段一起消失」成为同一个操作。TTL 让长期不活跃的桶自动回收 —— 否则多租户下 Redis 会被键撑满。**不把「请求的 tenant+key」写进指标标签**（高基数），指标只按「结果」与「来源（redis/local）」打标签。 | Task 3 有键布局与 TTL 公式的固定向量测试；Task 14 的真 Redis 用例直接读 `PTTL`。 |
| 9 | Lua 脚本**只做令牌桶算术**，时间和参数都由调用方传入：`KEYS[1]` = 桶 key，`ARGV = {nowMillis, qps, burst, ttlMillis}`，返回 `{allowed, remaining, retryAfterMillis}`。**不用 Redis 的 `TIME`**。 | 用 `TIME` 会把「网关的处理时刻」与「桶的判定时刻」拆成两个时钟，排查限流问题时无法把一次拒绝对应到网关日志里的时间戳；而且单机开发时 Redis 容器与宿主时钟偶有偏移。代价是时钟回拨会重置桶（放宽而不是收紧），已在脚本与纯算术里写明。**脚本必须是一次往返、服务器端原子**：这正是 §11 深挖清单第 2 题「Redis + Lua 令牌桶的原子性」的落点。 | `RETRYAFTER` 的公式是 `ceil((1000 - tokensMilli) / qps)`，可被纯算术单元测试精确断言，并由 Task 14 的真 Redis 并发用例证明不超发。 |
| 10 | 熔断状态 key 布局：`aihub:channel:circuit:{channelId}`，值为 `OPEN`，**TTL 30 秒**（spec 写死的数字），写失败只记 WARN。Redis 不可用时退化为**进程内**熔断表（`ConcurrentHashMap<Long, Long>` + 毫秒时钟）。**所有候选都被熔断时，忽略熔断标记照常选最高优先级的那一组**（并打 WARN + 计数器），而不是回 503。熔断的**触发面只有上游 429**；5xx 只做当次切换，不熔断。 | §9 明文「在 Redis 给该渠道打 30s 熔断标记」，Redis TTL 是唯一能同时做到「跨实例共享」和「自动过期」的载体。全候选熔断时返回 503 会制造一个**由我们自己短路出来的**整体不可用，与总原则冲突。「429 熔断、5xx 不熔断」的理由：30 秒熔断一个 429 渠道是 spec 的要求（429 表示该渠道的配额/速率已满，继续打只会持续失败），而 5xx 可能只是一个坏请求触发的单次故障，把整条渠道熔断 30 秒过于激进。 | Task 5 有 9 条用例（含「Redis 写失败仍标记本地」「TTL 30s 的字面量」「本机标记的两个过期边界」）；Task 6 有「全熔断放行」用例。 |
| 11 | **引入 WireMock**（`org.wiremock:wiremock`，**版本锁定 3.9.1**），**只加在 `aihub-gateway` 的 test 作用域**，且**只用于多渠道故障注入的里程碑验收**；M1/M2 已建成的 JDK `com.sun.net.httpserver.HttpServer` 夹具（`FakeUpstream` / `FakeAdminServer`）**全部保留**，细粒度、可证伪的用例继续用它们。 | 设计文档在**三处**点名 WireMock：§4.2 技术栈「WireMock 在 M3 引入时再锁定版本」、§10 测试策略「上游契约 = WireMock 模拟多渠道」、§12 里程碑表 M3 的验收标准「**WireMock 注入 429/超时，能自动切换**」。M3 的正式验收标准就写在这个工具名下，用一个自研夹具等价替代会让验收与 spec 的文字对不上。JDK 夹具确实能表达那四种注入，但它**不是一个「多渠道」抽象**：WireMock 的**命名 stub + 请求日志（`verify(...)`）**才能直接表达「三条渠道各注入一种故障，并证明请求真的打到了哪一条」。**可构建性已实测（2026-09-26）**：`mvn -B dependency:get -Dartifact=org.wiremock:wiremock:3.9.1` 经 `aliyunmaven` 镜像解析成功并落进 `.m2repo`（直连 `repo.maven.apache.org` 被出口阻断，镜像通）；另在**仓库之外**的探针工程里用 `@WireMockTest` **进程内**跑通三种注入（429 stub / `withFixedDelay` 挂住 → 客户端超时 / `Fault.MALFORMED_RESPONSE_CHUNK` 中途断流），`Tests run: 1, Failures: 0, Errors: 0` + `BUILD SUCCESS`，端口是本进程内的随机端口（60380），**无 Docker、无活 broker** —— 因此 M1/M2 那条「网关测试不依赖 Docker / 不要求活 broker」的约束仍然成立。 | `aihub-gateway/pom.xml` 增加一个 **test 作用域**的 `org.wiremock:wiremock:3.9.1`（它**不在** Spring Boot BOM 里，必须显式锁版本）；**生产依赖仍然零新增**，`aihub-common/pom.xml` 不动。新增 `WireMockChannelFaultInjectionTest`（Task 10），它承担 spec 原文的里程碑验收；Task 15 Step 3 的「没有新增依赖」核对改为「**生产**依赖无新增 + WireMock 只出现在 test 作用域」。 |
| 12 | **`QuotaFilter`（§6.2 预算扣减 / 补扣 / 对账）整体推迟到 M4。** 注册这个决策的理由是**两份文档冲突**：M2 计划的「不做」清单把「配额预扣」划给了 M3，而设计文档 §12 的里程碑表把 `配额` 放在 **M4（业务平台）**那一行。**以里程碑表为准**：配额预扣需要 `quota` 表的控制面（租户额度 CRUD）、`billing_daily` 累加、以及每日 02:00 的对账任务，这三样都在 M4 的范围里；M3 先做它没有可扣的额度来源。**并且必须说清 M3 做的是限流、不是余额记账**：`RateLimitFilter` 管的是「每秒能发几个请求」（QPS/burst，令牌桶，丢弃是暂时的、下一个窗口自动恢复）；配额管的是「这个租户还剩多少 token」（余额，扣减是持久的、用完了要充值）。两者共用 Redis 但语义完全不同，**不要把 429 `rate_limit_exceeded` 和 429 `QUOTA_EXCEEDED` 混为一谈**。 | M3 **不碰** `quota` 表、不做 `POST /internal/quota/reserve`、不写 `billing_daily`。被推迟的还有 §9 的「Redis 不可用时配额降级为放行 + 告警」——没有配额就没有这条降级。README 的「已知边界」必须写下这条移交。 |
| 13 | **`/v1/**` 的错误体一律保持 OpenAI 形状**，包括 M3 新增的 `rate_limit_exceeded`（429）与 `model_not_found`（404）。设计文档 §9 第 334 行「流开始前失败 → 标准 HTTP 状态码 + 统一错误体 `{"code","message"}`」**与 M1 契约冲突，该行按「已被取代」处理**。 | M1 已经用**官方 OpenAI Python SDK 2.41.1** 验收过 `/v1/**` 的 401 形状（`error.code == "invalid_api_key"`），M2 也在此契约上加了计量。给数据面套 admin 信封等于把协议换成私有协议，所有 SDK 的 `error.message` 取值路径会同时失效 —— 这不是「按 spec 实现」，是回归。 | 新错误码的取值与场景固化进 `docs/CONVENTIONS.md` 第 4 节的表；`GatewayErrors` 的签名不新增重载。 |
| 14 | 为了让 `request_log.api_key_id` 有值，**给共享的 `ApiKeyView` 加一个可空 `Long apiKeyId` 分量**（放在 `expireAt` 之后）。admin 侧解析回源/铸造时填 `api_key.id`，Redis 载荷加第 6 段，gateway 侧透传。 | M2 决策 8 明确把「`api_key_id` 恒为 NULL」列为 M4 前置项，理由是「补它等于改跨服务契约（admin resolve 响应 + Redis 载荷格式 + 两侧测试）」。M3 正好要动 `channel_id`（多渠道让「哪条渠道服务了这次请求」第一次有了含义），把两个 id 一起补上，成本只是同一批文件的同一个改动，收益是 `request_log` 第一次能按渠道和 key 聚合。**载荷加段是向后不兼容的**：旧 Redis entry 会被新 `decode` 判为畸形并**返回 null（缓存未命中 → 回源 admin → 重写）**，这正是我们要的收敛行为，不需要清库。 | **Task 2 落地**（与共享数据契约同一个提交，见全局约束的「不许跨任务占位」）：`ApiKeyCacheCodec` 段数 5 → 6；`ApiKeyToolingTest` 的固定向量必须同步更新（那是契约测试，改它是对的）；admin `ApiKeyService` 与 gateway `AdminClient.Http.parse` 各一行。Task 9 的 `RateLimitFilter` 因此**直接**读 `view.apiKeyId()`（没有占位、不需要后续任务收口）；计量侧的 `RelayMetering` 填充属 Task 11 —— 那是**计量持久化**的一环，不是契约变更。`channelId` 仍由路由结果填，不属于这一条。 |
| 15 | 客户端传来的 **`model` 只在「进入计量事件」时截断**：超过 128 字符则截到 128（保留前 128 个字符的原文，不加省略号）。**转发给上游的请求体逐字节不变**（仍然原样带上客户端写的 model）。 | `request_log.model` 是 `VARCHAR(128)`，超长会让 INSERT 报「数据过长」→ `DataAccessException` → 重试 3 次 → 进 `aihub.metering.dlq`，而 DLQ 是**任何持合法 API Key 的客户端都能触碰**的入口（M2 已知边界）。**不能**在网关拒掉这个请求：那会把一个纯粹的计量侧问题变成客户端可见的行为变更，而且上游本来能处理长模型名。截断发生在组装事件的地方（计量是派生的，截断只损失精度，不损失真值 —— 真值在上游日志里）。 | Task 11 有用例 `oversizedModelIsTruncatedInTheEventButForwardedVerbatim` 同时断言「事件里是 128 字符」与「上游收到的请求体含原始长 model」。 |
| 16 | **API Key 吊销 / 停用的生效延迟是显式接受的**：本机 Caffeine `≤30s`、跨实例 Redis `≤5m`。M3 **不加**吊销广播，也**不写** Pub/Sub 监听器。 | M1 的 t4-1 把这个问题挂起来等 M3 定。现在的答案是「接受，并说清楚为什么」：① §6.3 的 Pub/Sub 失效机制只针对**配置快照**，而 API Key 缓存是**鉴权信任源**，它的失效通道需要鉴权保护（否则能发消息的人就能驱逐任意 key 的缓存）；② M3 没有配置写入方，Pub/Sub 连发布端都不存在，为一个不存在的发布端写监听器 = 一条永远不触发的代码路径（在「必须有测试」的纪律下它只能靠直接调用监听方法来「测」，那是自欺）；③ 真正的收敛手段是 M4 的吊销接口 + 显式 `DEL`，比 TTL 更精确。**不接受的做法**：为了「立刻生效」而把 Redis TTL 调到几秒 —— 那会让 Redis 变成每次请求都回源的控制面，与决策 A 的初衷相反。 | 写进 `docs/CONVENTIONS.md` 第 6.6 节（已有 ≤30s/≤5m 的表述，补上「M3 显式接受该窗口，收敛手段是 M4 的显式 DEL」）与 README 已知边界。**代码不改**。 |
| 17 | `rate_limit_policy` **没有唯一约束**（V1 只有 `KEY idx_rate_limit_tenant (tenant_id)`），M3 **不加**唯一索引。策略行的选取规则（**2026-09-26 随决策 7 的修订扩到两个维度，读取顺序本身不变**）：组装快照时按 `tenant_id ASC, api_key_id IS NULL DESC, id ASC` 读取；网关侧**在每一级内部**取列表里**最后一条** —— 租户级取最后一条 `api_key_id IS NULL` 的行，key 级取该 `apiKeyId` 的最后一行。两者合起来等于「**同一租户、同一维度内取 `id` 最大的那条 ACTIVE 策略**」（key 级行按 `id` 升序交错在租户级行之后，按 `apiKeyId` 过滤后仍是 `id` 最大者）。同租户出现多条**同维度** ACTIVE 策略时 admin 侧打一次 WARN（租户级与 key 级各判各的）。 | 加唯一索引要改 V1（禁止）或加 V2（决策 12 禁止）。语义上「取最后插入的那条」是可预测且无需 DDL 的：M4 的控制台写入应当先停用旧行（`status`），因此 `status='ACTIVE'` + `id DESC` 恰好表达「当前生效的那条」。打 WARN 而不是抛异常：数据脏不能变成数据面不可用。 | Task 4 的 `usesTheLastPolicyOfTheMatchingDimensionWhenSeveralArePresent`（并覆盖 key 级同键多条取最后一条）与 Task 13 的 `multipleTenantLevelPoliciesWarnButStillPickTheLast` 各钉一半（读取顺序 + 实际取值）。 |
| 18 | **不新增 `/actuator/metrics` 暴露**（仍然是 `health,info`）；M3 新增的计数器一律只进 Micrometer registry + 日志。 | 沿用 M2 决策 13：网关只有 `ApiKeyAuthFilter` 守 `/v1/**`，`/metrics` 会变成**无鉴权**的新公网面。可观测性对外暴露属于 M6。 | Task 5 / Task 9 的计数器（`aihub.ratelimit.rejected`、`aihub.ratelimit.degraded`）只被测试通过 registry 读取，不新增端点。 |

### 本里程碑**不做**的事（写进文档，避免范围蔓延）

- **`QuotaFilter`：预扣估算 / 实际校正 / 对账报账**（§6.2）—— M4。**M3 做的是限流（QPS/burst），不是余额记账**，两码事（见决策 12）。
- **Redis Pub/Sub 失效的发布方与订阅方**（没有配置写入方就没有发布者）—— M4 与配置 CRUD 一起做（决策 16）。
- **管理台、`/api/channels` CRUD、`/api/api-keys`、租户管理、审计、账单、`GET /api/logs`** —— M4。
- **`POST /v1/embeddings`、文档流水线** —— M5。
- **压测报告、故障注入报告、指标端点暴露** —— M6。
- **`channel.models_json` 的语义**：M3 的路由只用 `model_route` 表，不读渠道自带的 `models_json`（它能表达「这条渠道支持哪些模型」，但 `model_route` 已经表达了「这个模型可以走哪些渠道」，两者同时存在时以哪个为准 spec 没说；登记在 README 已知边界，M4 的控制台一并定）。`GET /v1/models` 回报「快照里出现过的模型名 ∪ 遗留默认模型」。
- **上游健康探测（主动探活）**：§9 只要求「失败后标记」，主动探测（`/api/channels/{id}/probe`）属 M4 的控制台功能。
- **Redis `requirepass` 与网络隔离加固**：仍未做（README 继续披露）。

---

## File Structure

M3 结束后新增/修改的文件（`改` = 修改既有文件；**没有** `aihub-common` 的 pom 改动，也**没有**新的 Flyway 脚本。**唯一的依赖变更是** `aihub-gateway/pom.xml` 里一个 test 作用域依赖（WireMock，决策 11）——**生产依赖零新增**）：

| 文件 | 职责 |
|---|---|
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/crypto/ChannelKeyRegistry.java` | 新增：主密钥表解析（`v1:<b64>,v2:<b64>`），按版本查密钥，`currentVersion()` |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/crypto/AesGcmChannelCipher.java` | 新增：AES-GCM 加解密（`v{n}:{b64(nonce‖ct)}` 自描述载荷），JDK-only |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ChannelDescriptor.java` | 新增：渠道的纯数据视图（含 `apiKeyCipher` 与 `keyVersion`），**admin 与 gateway 共用** |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ModelRouteDescriptor.java` | 新增：`model → channel` 的候选行（含 route 级 weight/priority） |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/RatePolicy.java` | 新增：限流策略的纯数据视图（tenantId / apiKeyId / qps / burst）；**两个维度都生效**（决策 7 修订） |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshot.java` | 新增：快照聚合（version / defaultModel / generatedAt / channels / routes / ratePolicies） |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshotCodec.java` | 新增：快照的分隔符编解码（**Redis 缓存载荷 + 本地缓存载荷**，见决策 4） |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/ratelimit/RateLimitScript.java` | 新增：Lua 脚本与令牌桶键布局的**唯一真相**（gateway 跑它，admin 侧的集成测试验它） |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyView.java` | **改**：加可空 `Long apiKeyId` 分量（决策 14，**Task 2**） |
| `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyCacheCodec.java` | **改**：载荷 5 段 → 6 段（决策 14，**Task 2**） |
| `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ChannelEntity.java` | 新增：`channel` 实体（`apiKeyCipher` / `keyVersion` / `weight` / `priority` / `timeoutMs` / `status`） |
| `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ModelRouteEntity.java` | 新增：`model_route` 实体 |
| `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/RateLimitPolicyEntity.java` | 新增：`rate_limit_policy` 实体（含 `apiKeyId`） |
| `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/ChannelMapper.java` | 新增：Mapper |
| `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/ModelRouteMapper.java` | 新增：Mapper |
| `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/RateLimitPolicyMapper.java` | 新增：Mapper |
| `aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigSnapshotService.java` | 新增：从三张表组装 `ConfigSnapshot`，算单调 `version`，按决策 17 选租户级限流策略 |
| `aihub-admin/aihub-service/src/main/java/com/aihub/service/channel/ChannelKeyService.java` | 新增：admin 侧的**加密**入口（seeder / M4 的控制台共用），主密钥来自 `aihub.channel.master-key` |
| `aihub-admin/aihub-service/src/main/java/com/aihub/service/channel/DemoChannelSeeder.java` | 新增：**开发专用**演示数据（`@ConditionalOnProperty("aihub.demo-seed.enabled")`，默认关闭） |
| `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/internal/InternalConfigController.java` | 新增：`GET /internal/config/snapshot`（admin 信封，`/internal/**` 已被 `InternalAuthFilter` 守卫） |
| `aihub-admin/aihub-web/src/main/resources/application.yml` | **改**：`aihub.channel.master-key`、`aihub.demo-seed.*` |
| `aihub-gateway/src/main/java/com/aihub/gateway/config/GatewayConfigProperties.java` | 新增：`aihub.config.*`（本地 TTL 30s、Redis TTL、singleflight 超时） |
| `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigCache.java` | 新增：Caffeine 本地一级 + Redis 二级（存取层） |
| `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigClient.java` | 新增：三级读取 + singleflight + 版本比对（§6.3 的落点），**永不返回 null** |
| `aihub-gateway/src/main/java/com/aihub/gateway/config/LegacyChannel.java` | 新增：`aihub.upstream.*` 合成的**哨兵渠道**（`id = Long.MIN_VALUE`） |
| `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigConfig.java` | 新增：`@EnableConfigurationProperties(GatewayConfigProperties.class)` + beans |
| `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java` | **改**：加 `CONFIG_SNAPSHOT_PATH` 与 `configSnapshot()`（复用同一个 `Http` 实现与 `InternalHmac`）；`parse` 填 `apiKeyId`（决策 14，**Task 2**，Task 7 不再动它） |
| `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimitDecision.java` | 新增：判定结果 record（allowed / remaining / retryAfterMs / limit / burst / source） |
| `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/TokenBucket.java` | 新增：令牌桶的**纯算术**（Redis Lua 与本机降级桶共用） |
| `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/LuaTokenBucket.java` | 新增：对 `RateLimitScript` 的**纯委托** |
| `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RedisRateLimiter.java` | 新增：Redis + Lua 实现（失败返回 null = 「我这级不可用」） |
| `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/LocalRateLimiter.java` | 新增：Redis 不可用时的**本机令牌桶**（Caffeine 有界） |
| `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimitResolver.java` | 新增：`(tenantId, apiKeyId)` → `RatePolicy`（key 级优先、租户级回落、内置默认；决策 7/17） |
| `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimiter.java` | 新增：**唯一被过滤器依赖的门面**（先 Redis、失败降级本机、绝不抛异常） |
| `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimitFilter.java` | 新增：`/v1/**` 的限流过滤器（`@Order(HIGHEST_PRECEDENCE + 150)`），超限回 OpenAI 形状 429 |
| `aihub-gateway/src/main/java/com/aihub/gateway/route/CircuitState.java` | 新增：熔断状态 record（open / source） |
| `aihub-gateway/src/main/java/com/aihub/gateway/route/ChannelCircuitBreaker.java` | 新增：Redis 30s 标记 + 本机降级表（决策 10） |
| `aihub-gateway/src/main/java/com/aihub/gateway/route/RouteSelectionException.java` | 新增：按模型找不到任何渠道时抛出（携带 `model`） |
| `aihub-gateway/src/main/java/com/aihub/gateway/route/RouteResolver.java` | 新增：`model` → 有序候选（priority 分组 → 组内权重随机 → 熔断排最后），**可注入随机源** |
| `aihub-gateway/src/main/java/com/aihub/gateway/relay/RelayAttempts.java` | 新增：失败转移的**纯规则**（可切换判据 / 密钥可服务候选 / model 截断） |
| `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChannelKeyDecryptor.java` | 新增：把渠道密文解成本次请求要用的明文（**永不抛异常**） |
| `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamClientFactory.java` | 新增：`ChannelDescriptor` → `WebClient`（每渠道 base-url + 超时；流式不设响应超时） |
| `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamProperties.java` | **改**：javadoc 说明它现在只服务「遗留单渠道兜底」 |
| `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamClientConfig.java` | **改**：把构造交给工厂，保留 `upstreamWebClient` bean（= `factory.legacy()`） |
| `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java` | **改**：路由选择 + 失败转移循环 + 每渠道密钥注入 + 白名单加 `retry-after-ms` / IETF `RateLimit-*` |
| `aihub-gateway/src/main/java/com/aihub/gateway/relay/ModelsController.java` | **改**：模型列表 = 快照模型名 ∪ 遗留默认模型 |
| `aihub-gateway/src/main/java/com/aihub/gateway/meter/RelayMetering.java` | **改**：`channelId` 填充（Task 10）+ `apiKeyId` 取自 `view.apiKeyId()`（决策 14，**Task 11**）、model 截断 128（决策 15） |
| `aihub-gateway/src/main/java/com/aihub/gateway/auth/ApiKeyAuthFilter.java` | **改**：把 `sha256(secret)` 写进 exchange 属性供限流用（`ATTRIBUTE_KEY_HASH`） |
| `aihub-gateway/src/main/java/com/aihub/gateway/error/GatewayErrors.java` | **不改**：新增错误码只用到既有的 `write(...)` 签名 |
| `aihub-gateway/src/main/resources/application.yml` | **改**：`aihub.channel.master-key`、`aihub.config.*`、`aihub.ratelimit.enabled` |
| `aihub-gateway/src/test/resources/application.properties` | **改**：`aihub.ratelimit.enabled=false`（理由同 `aihub.metering.enabled=false`） |
| `aihub-gateway/pom.xml` | **改**：test 作用域加 `org.wiremock:wiremock:3.9.1`（**锁死版本**，不在 Spring Boot BOM 里）。**生产依赖零新增**；`aihub-common/pom.xml` 不动（决策 11） |
| `docker-compose.yml` | **改**：admin 与 gateway 都加 `AIHUB_CHANNEL_MASTER_KEY`；admin 加 `AIHUB_DEMO_SEED_ENABLED` |
| `.env.example` | **改**：`AIHUB_CHANNEL_MASTER_KEY` 的占位符 + **生成方法**，**不放任何真实密钥** |
| `README.md` / `docs/CONVENTIONS.md` | **改**：M3 进度、限流与错误码、快照与密钥约定、降级与熔断、边界清单、配额移交 M4 |

### 测试文件（全部新增，除注明外）

| 文件 | 覆盖 |
|---|---|
| `aihub-common/src/test/java/com/aihub/common/crypto/ChannelKeyCipherTest.java` | 往返、nonce 唯一、篡改必失败、版本自描述、轮换、未知版本、空主密钥、主密钥表解析 |
| `aihub-common/src/test/java/com/aihub/common/config/ConfigSnapshotCodecTest.java` | 编解码往返、固定向量、字段转义、畸形载荷、四个 record 的语义 |
| `aihub-common/src/test/java/com/aihub/common/ratelimit/RateLimitScriptTest.java` | 脚本字面量 + 键前缀 + Hash 字段名（跨模块契约） |
| `aihub-common/src/test/java/com/aihub/common/apikey/ApiKeyToolingTest.java` | **改**（**Task 2**）：6 段固定向量 + `apiKeyId` 往返 + 旧 5 段载荷被判畸形（决策 14） |
| `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/TokenBucketTest.java` | 纯算术：满桶、补充、封顶、拒绝、retryAfter 公式、时钟回拨 |
| `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/LuaTokenBucketTest.java` | 委托后的脚本与键布局与共享常量逐字节一致 |
| `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/LocalRateLimiterTest.java` | 本机桶：独立 key、退避、并发不超发（真实线程）、空闲淘汰 |
| `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/RateLimitResolverTest.java` | 默认策略、**key 级命中优先**、无 key 级行时回落租户级、其他租户不生效、各级取最后一条（含 key 级同键多条）、惰性读快照 |
| `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/RedisRateLimiterTest.java` | 脚本调用约定（1 key + 4 argv）、异常→null、返回三元素 |
| `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/RateLimitFilterTest.java` | 放行、429 形状与头、维度、降级仍拒绝、fail-open、`@Order` |
| `aihub-gateway/src/test/java/com/aihub/gateway/route/ChannelCircuitBreakerTest.java` | 30s 字面量、Redis 故障退化本机、本机过期边界、清除、不抛 |
| `aihub-gateway/src/test/java/com/aihub/gateway/route/RouteResolverTest.java` | priority 分组、权重随机（固定随机源）、跳过熔断、全熔断放行、无候选抛异常 |
| `aihub-gateway/src/test/java/com/aihub/gateway/config/ConfigCacheTest.java` | 三级顺序、版本落后丢弃本地、singleflight 合并并发、Redis/admin 故障降级、遗留兜底 |
| `aihub-gateway/src/test/java/com/aihub/gateway/relay/RelayAttemptsTest.java` | 可切换判据、密钥过滤、model 截断 |
| `aihub-gateway/src/test/java/com/aihub/gateway/relay/ChannelKeyDecryptorTest.java` | 解密、遗留渠道、解不开→空、不泄漏明文 |
| `aihub-gateway/src/test/java/com/aihub/gateway/upstream/UpstreamClientFactoryTest.java` | 遗留客户端带 Bearer、渠道客户端不带、流式无响应超时、缓存键 |
| `aihub-gateway/src/test/java/com/aihub/gateway/relay/FailoverRelayTest.java` | **M3 的核心验收**：429 立即切换并熔断、5xx 切换、超时切换、400 不切换、全挂透传最后一个失败、未知模型 404、每渠道密钥注入（JDK `HttpServer` 夹具） |
| `aihub-gateway/src/test/java/com/aihub/gateway/relay/WireMockChannelFaultInjectionTest.java` | **spec §10 / 里程碑验收的字面落点**：WireMock 命名 stub 扮三条渠道（429 / 挂住超时 / 中途断流）→ 自动切换、提交后不切换、请求日志证明打到了哪条（**进程内**，不需要 Docker） |
| `aihub-gateway/src/test/java/com/aihub/gateway/relay/ChatRelayControllerTest.java` | **改**：补 `retry-after-ms` / IETF `RateLimit-*` 透传与「精确名」回归 |
| `aihub-gateway/src/test/java/com/aihub/gateway/relay/RelayMeteringFlowTest.java` | **改**：`channel_id` / `api_key_id` 有值、超长 model 截断、既有契约回归 |
| `aihub-gateway/src/test/java/com/aihub/gateway/relay/ModelsControllerTest.java` | **改**：模型列表 = 快照 ∪ 遗留默认 |
| `aihub-gateway/src/test/java/com/aihub/gateway/testsupport/FakeUpstream.java` | **改**：加 `enqueueError(int, String)` 与 `enqueueStall(long)` |
| `aihub-web/src/test/java/com/aihub/admin/channel/ChannelKeyServiceTest.java` | admin 加密：无主密钥抛错、跨侧互操作、版本、不泄漏 |
| `aihub-web/src/test/java/com/aihub/admin/config/ConfigSnapshotServiceTest.java` | 真 MySQL：三张表组装、version 单调、决策 7/17 |
| `aihub-web/src/test/java/com/aihub/admin/config/InternalConfigSnapshotIntegrationTest.java` | HMAC 端到端、未签名/错签名 401、响应形状 |
| `aihub-web/src/test/java/com/aihub/admin/ratelimit/RedisTokenBucketIntegrationTest.java` | **真 Redis**：Lua 的补充/封顶/拒绝/TTL + 并发 20×20 恰好放行 burst（原子性证据） |

**本计划的预期测试总数**（Task 15 会要求实测并写进 README；**以实测为准**，这里的数字只是给实施者一个「跑偏了没有」的参照）：
`aihub-common` 基线 21 → 约 **48**；`aihub-gateway` 基线 132 → 约 **246**（含 WireMock 多渠道故障注入验收的 5 条）；`aihub-web` 基线 47 → 约 **71**。

---

## Task 1: 渠道密钥加解密内核（`aihub-common`，JDK-only，双版本）

**Files:**
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/crypto/ChannelKeyRegistry.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/crypto/AesGcmChannelCipher.java`
- Test: `aihub-admin/aihub-common/src/test/java/com/aihub/common/crypto/ChannelKeyCipherTest.java`

**Interfaces:**
- Consumes: 无（只依赖 JDK：`javax.crypto` / `java.security` / `java.util.Base64` / `java.util.regex`）。
- Produces（后续任务全部按这里的签名调用）：
  - `record ChannelKeyRegistry(Map<Integer, byte[]> keys)`：`static ChannelKeyRegistry parse(String envValue)`（**永不抛异常**，无法解析的段跳过）、`int currentVersion()`（无版本时 `0`）、`boolean isEmpty()`、`boolean has(int version)`、`Optional<byte[]> key(int version)`、`String describe()`（只含版本号）。
  - `AesGcmChannelCipher`：构造器 `AesGcmChannelCipher(ChannelKeyRegistry registry)`；`String encrypt(String plaintext)`（无可用版本抛 `IllegalStateException`）；`Optional<String> decrypt(String payload)`（**永不抛**）；`boolean canDecrypt(String payload)`；静态 `String labelOf(String payload)`。
  - 载荷格式：`v{version}:{Base64(nonce ‖ ciphertext+tag)}`，nonce 12 字节，tag 128 位。

**测试用例清单**（`ChannelKeyCipherTest`，12 条）：

| 用例 | 钉住什么 | 会红在什么错误实现上 |
|---|---|---|
| `roundTripsAPlaintextChannelKey` | `decrypt(encrypt(x)) == x`（含中英混排、超长、空串） | 编码/解码不对称 |
| `everyEncryptionUsesAFreshNonce` | 同一明文两次密文不同，且都解得回来 | 固定 nonce（AES-GCM 复用 nonce 是致命缺陷） |
| `payloadIsSelfDescribingWithTheVersionLabel` | 载荷以 `v2:` 开头（表里最大版本）；`labelOf` 一致 | 把版本只放在 DB 列里（决策 2 的反面） |
| `tamperedCiphertextFailsToDecrypt` | 改一个 base64 字符 → 空 `Optional` | 用 AES/CBC 或无 tag 校验 |
| `tamperedNonceFailsToDecrypt` | 只改 nonce 段 → 空 `Optional` | 同上 |
| `rotationDecryptsTheOldVersionAndReencryptsToTheNewOne` | 旧密文仍可解；`encrypt` 一定用 `currentVersion()` | 只支持「当前版本」的轮换实现 |
| `retiringTheOldKeyMakesOldCiphertextUndecryptable` | 去掉 v1 后 v1 密文 → 空（不是抛异常） | 解密时抛异常 |
| `unknownVersionLabelReturnsEmptyInsteadOfThrowing` | `v9:AAAA` → 空 | 抛 `IllegalArgumentException` |
| `malformedPayloadsReturnEmpty` | `null` / `""` / `"v1"` / `"v1:"` / 非 base64 / 截断 → 空 | 「差不多能解」的宽容解析 |
| `registryParsingAcceptsWhitespaceAndRejectsGarbage` | `" v1:AA , 不是版本 , v2:BB , v3:short "` → 只留下 1/2，`currentVersion()==2` | 用 `split(",")` 后直接 `Base64.decode`（空白段会炸） |
| `registryRequiresExactly32ByteKeys` | 16 / 31 / 33 字节的段全部被跳过 | 接受任意长度（`InvalidKeyException` 会在请求路径上冒出来） |
| `emptyRegistryCannotEncryptAndCannotDecrypt` | `isEmpty()` 真；`encrypt` 抛 `IllegalStateException`；`decrypt` 返回空；`key(1)` 空 | 用零密钥加密（静默产生不可解数据） |

- [ ] **Step 1: 写失败测试**

创建 `aihub-admin/aihub-common/src/test/java/com/aihub/common/crypto/ChannelKeyCipherTest.java`：

```java
package com.aihub.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道密钥的 AES-GCM 加解密是**跨服务**能力：admin 加密（写入 {@code channel.api_key_cipher}），
 * gateway 解密（本地注入上游密钥）。两侧共用本文件里的实现，因此「密文格式」「主密钥解析规则」
 * 「版本自描述」这三件事都压在这里。
 *
 * <p>测试用的明文一律是**一眼可辨的合成值**（{@code sk-channel-plaintext-synthetic}）：
 * 真实渠道密钥不允许出现在任何 tracked 文件里，包括测试夹具。
 *
 * <p>本类不需要 Spring 上下文，也不需要 Docker / Redis。
 */
class ChannelKeyCipherTest {

    private static final String PLAINTEXT = "sk-channel-plaintext-synthetic";

    /** 32 字节（AES-256）的合成主密钥。 */
    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static ChannelKeyRegistry registry(int... versions) {
        StringBuilder out = new StringBuilder();
        for (int version : versions) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append('v').append(version).append(':').append(b64Key(version));
        }
        return ChannelKeyRegistry.parse(out.toString());
    }

    @Test
    void roundTripsAPlaintextChannelKey() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        for (String plaintext : List.of(PLAINTEXT, "", "中文渠道密钥", "x".repeat(4096))) {
            String payload = cipher.encrypt(plaintext);
            assertThat(cipher.decrypt(payload)).contains(plaintext);
        }
    }

    /**
     * AES-GCM 下**复用 nonce 是致命缺陷**（同一密钥 + 同一 nonce 会泄露明文异或并摧毁认证性）。
     * 这条用例是它的防线：同一明文加密两次必须得到两个不同的密文，且都能解回来。
     */
    @Test
    void everyEncryptionUsesAFreshNonce() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        String first = cipher.encrypt(PLAINTEXT);
        String second = cipher.encrypt(PLAINTEXT);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).contains(PLAINTEXT);
        assertThat(cipher.decrypt(second)).contains(PLAINTEXT);
    }

    @Test
    void payloadIsSelfDescribingWithTheVersionLabel() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1, 2));

        String payload = cipher.encrypt(PLAINTEXT);

        // 环境变量里最大的版本号就是 currentVersion。
        assertThat(payload).startsWith("v2:");
        assertThat(AesGcmChannelCipher.labelOf(payload)).isEqualTo("v2");
        assertThat(cipher.canDecrypt(payload)).isTrue();
    }

    @Test
    void tamperedCiphertextFailsToDecrypt() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));
        String payload = cipher.encrypt(PLAINTEXT);

        String flipped = payload.substring(0, payload.length() - 2)
                + (payload.endsWith("A") ? "B" : "A");

        assertThat(cipher.decrypt(flipped)).isEmpty();
    }

    @Test
    void tamperedNonceFailsToDecrypt() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));
        String payload = cipher.encrypt(PLAINTEXT);
        int colon = payload.indexOf(':');
        byte[] nonceAndBody = Base64.getDecoder().decode(payload.substring(colon + 1));
        nonceAndBody[0] ^= 0x01;

        String tampered = payload.substring(0, colon + 1) + Base64.getEncoder().encodeToString(nonceAndBody);

        assertThat(cipher.decrypt(tampered)).isEmpty();
    }

    /**
     * 轮换的完整含义：**旧密文仍然解得开**（旧版本还在表里），而**新加密一定用新版本**。
     * 只有「解密时先查 DB 的 key_version 再选密钥」的实现才会在这条上红。
     */
    @Test
    void rotationDecryptsTheOldVersionAndReencryptsToTheNewOne() {
        String oldPayload = new AesGcmChannelCipher(registry(1)).encrypt(PLAINTEXT);

        AesGcmChannelCipher rotated = new AesGcmChannelCipher(registry(1, 2));

        assertThat(rotated.decrypt(oldPayload)).contains(PLAINTEXT);
        String newPayload = rotated.encrypt(PLAINTEXT);
        assertThat(newPayload).startsWith("v2:");
        assertThat(rotated.decrypt(newPayload)).contains(PLAINTEXT);
    }

    @Test
    void retiringTheOldKeyMakesOldCiphertextUndecryptable() {
        String oldPayload = new AesGcmChannelCipher(registry(1)).encrypt(PLAINTEXT);
        AesGcmChannelCipher retired = new AesGcmChannelCipher(registry(2));

        assertThat(retired.decrypt(oldPayload)).isEmpty();
        assertThat(retired.canDecrypt(oldPayload)).isFalse();
    }

    @Test
    void unknownVersionLabelReturnsEmptyInsteadOfThrowing() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        assertThat(cipher.decrypt("v9:" + Base64.getEncoder().encodeToString(new byte[40]))).isEmpty();
    }

    @Test
    void malformedPayloadsReturnEmpty() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        for (String payload : new String[]{null, "", "v1", "v1:", "nonsense", "v1:!!!not-base64!!!", "v1:AAAA"}) {
            assertThat(cipher.decrypt(payload)).as("payload %s 必须解成空而不是抛异常", payload).isEmpty();
        }
        assertThat(AesGcmChannelCipher.labelOf("nonsense")).isNull();
        assertThat(AesGcmChannelCipher.labelOf(null)).isNull();
    }

    @Test
    void registryParsingAcceptsWhitespaceAndRejectsGarbage() {
        ChannelKeyRegistry parsed = ChannelKeyRegistry.parse(
                " v1:" + b64Key(1) + " , 不是版本 , v2:" + b64Key(2) + ",v3:short ");

        assertThat(parsed.has(1)).isTrue();
        assertThat(parsed.has(2)).isTrue();
        assertThat(parsed.has(3)).as("不足 32 字节的版本必须被跳过").isFalse();
        assertThat(parsed.currentVersion()).as("当前版本 = 解析成功的最大版本号").isEqualTo(2);
        assertThat(parsed.describe()).as("describe 只含版本号，绝不含密钥内容").contains("1").contains("2");
    }

    @Test
    void registryRequiresExactly32ByteKeys() {
        ChannelKeyRegistry parsed = ChannelKeyRegistry.parse(
                "v1:" + Base64.getEncoder().encodeToString(new byte[16])
                        + ",v2:" + Base64.getEncoder().encodeToString(new byte[31])
                        + ",v3:" + Base64.getEncoder().encodeToString(new byte[33]));

        assertThat(parsed.isEmpty()).isTrue();
    }

    @Test
    void emptyRegistryCannotEncryptAndCannotDecrypt() {
        ChannelKeyRegistry empty = ChannelKeyRegistry.parse("");
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(empty);

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.currentVersion()).isZero();
        assertThatThrownBy(() -> cipher.encrypt(PLAINTEXT)).isInstanceOf(IllegalStateException.class);
        assertThat(cipher.decrypt("v1:AAAA")).isEmpty();
        assertThat(empty.key(1)).isEqualTo(Optional.empty());
        assertThat(empty.keys()).isEqualTo(Map.of());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（编译失败也算红）**

```powershell
mvn -B -pl aihub-admin/aihub-common test "-Dtest=ChannelKeyCipherTest"
```

预期：`BUILD FAILURE` + `cannot find symbol: class ChannelKeyRegistry`（两个生产类还不存在）。

- [ ] **Step 3: 写 `ChannelKeyRegistry`**

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/crypto/ChannelKeyRegistry.java`：

```java
package com.aihub.common.crypto;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 渠道密钥的主密钥表，从**环境变量**读入，格式为 {@code v1:<base64 32 字节>,v2:<base64 32 字节>}。
 *
 * <p><b>为什么可以放在 {@code aihub-common}</b>：它只用 JDK 类型（{@link Base64} / {@link Map} /
 * {@link Pattern}），因此「main 作用域零依赖」仍然成立 —— 零依赖指的是**零第三方依赖**，而不是
 * 「只能用 {@code java.lang}」（{@code InternalHmac} 早就在用 {@code javax.crypto}）。
 * 放这里的唯一理由是 admin（加密）与 gateway（解密）**必须共用同一份**解析规则：各写一份等于把
 * 主密钥格式撕成两半，单侧改动不会编译报错，只会表现为「网关解不开密钥」。
 *
 * <p><b>双版本共存</b>：轮换时环境变量里同时放旧、新两把密钥 → 网关既能解开旧密文（存量行）、
 * 又能解开新密文（重加密后的行）；全部重加密完成后把旧密钥去掉。
 * 因此 {@link #parse} 对无法解析的段采取**跳过**而不是抛异常，{@link #currentVersion()} 取
 * 「解析成功的最大版本号」—— 环境变量里出现垃圾时最坏的后果是「那个版本解不开」，而不是「网关起不来」。
 */
public record ChannelKeyRegistry(Map<Integer, byte[]> keys) {

    /** AES-256 要求 32 字节密钥。 */
    private static final int KEY_LENGTH_BYTES = 32;

    /** 一个版本段：{@code v<数字>:<base64>}；大小写不敏感（运维手写环境变量时不该被大小写坑到）。 */
    private static final Pattern SEGMENT = Pattern.compile("^[vV](\\d{1,9}):(.+)$");

    /**
     * 版本数上界。它不是安全边界，而是**配置错误的上界**：环境变量里塞了几十个版本通常意味着
     * 有人把「历史密钥」当成了备份手段。超出的段被跳过。
     */
    private static final int MAX_VERSIONS = 8;

    public ChannelKeyRegistry {
        keys = Map.copyOf(keys);
    }

    /** 解析环境变量。任何一段解析失败都只是被跳过，**本方法永不抛异常**。 */
    public static ChannelKeyRegistry parse(String envValue) {
        Map<Integer, byte[]> parsed = new TreeMap<>();
        if (envValue == null || envValue.isBlank()) {
            return new ChannelKeyRegistry(Map.of());
        }
        for (String rawSegment : envValue.split(",")) {
            if (parsed.size() >= MAX_VERSIONS) {
                break;
            }
            String segment = rawSegment.strip();
            if (segment.isEmpty()) {
                continue;
            }
            Matcher matcher = SEGMENT.matcher(segment);
            if (!matcher.matches()) {
                continue;
            }
            int version;
            byte[] key;
            try {
                version = Integer.parseInt(matcher.group(1));
                key = Base64.getDecoder().decode(matcher.group(2).strip());
            } catch (RuntimeException e) {
                continue;
            }
            if (version <= 0 || key.length != KEY_LENGTH_BYTES) {
                continue;
            }
            parsed.put(version, key);
        }
        return new ChannelKeyRegistry(new LinkedHashMap<>(parsed));
    }

    /** 当前（最新）版本号；表为空时返回 {@code 0}，调用方据此判断「不可加密」。 */
    public int currentVersion() {
        return keys.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public boolean has(int version) {
        return keys.containsKey(version);
    }

    public Optional<byte[]> key(int version) {
        return Optional.ofNullable(keys.get(version));
    }

    /** 供日志使用：只出现版本号，**绝不出现密钥内容**。 */
    public String describe() {
        return keys.isEmpty() ? "无主密钥" : "已加载主密钥版本 " + keys.keySet();
    }

    @Override
    public String toString() {
        // 覆写 record 默认 toString：它会把 byte[] 的 hashCode 打出来，虽不泄漏内容，
        // 但会让「主密钥表」有办法出现在日志/异常消息里 —— 从源头掐掉。
        return "ChannelKeyRegistry[" + describe() + "]";
    }
}
```

- [ ] **Step 4: 写 `AesGcmChannelCipher`**

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/crypto/AesGcmChannelCipher.java`：

```java
package com.aihub.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 渠道密钥的 AES-GCM 加解密。**JDK 自带实现，不引第三方加密库**。
 *
 * <p><b>密文自描述</b>：{@code v{版本}:{Base64(nonce ‖ ciphertext+tag)}}。网关解密时**从载荷里
 * 读版本**，而不是去查 {@code channel.key_version} 列 —— 轮换期间只要有一条行的那一列写歪，
 * 「查列选密钥」的实现就会把一条本来完好的密文变成永久不可解的数据丢失。自描述载荷让回退安全：
 * 把新密钥从环境变量里去掉，旧密文照样可解。
 *
 * <p><b>失败语义</b>：{@link #decrypt} **永不抛异常**，畸形载荷 / 未知版本 / tag 校验失败一律返回
 * 空 {@link Optional}。调用它的是数据面请求路径，配置错误不能变成客户端 500
 * （与 M1「缓存故障绝不变成 500」同一条纪律）。{@link #encrypt} 相反：它只在 admin 的写入路径上被
 * 调用，没有可用主密钥时直接抛 {@link IllegalStateException} —— 静默写坏数据比启动失败糟得多。
 */
public final class AesGcmChannelCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    /** GCM 推荐 96 位 nonce。 */
    private static final int NONCE_LENGTH_BYTES = 12;
    /** 认证标签 128 位。 */
    private static final int TAG_LENGTH_BITS = 128;
    private static final Pattern LABEL = Pattern.compile("^([vV]\\d{1,9}):(.*)$");

    private final ChannelKeyRegistry registry;
    private final SecureRandom random = new SecureRandom();

    public AesGcmChannelCipher(ChannelKeyRegistry registry) {
        this.registry = registry;
    }

    /**
     * 用 {@link ChannelKeyRegistry#currentVersion()} 加密。
     *
     * @throws IllegalStateException 主密钥表为空（配置缺失）
     */
    public String encrypt(String plaintext) {
        int version = registry.currentVersion();
        if (version == 0) {
            throw new IllegalStateException(
                    "未配置渠道主密钥（aihub.channel.master-key / AIHUB_CHANNEL_MASTER_KEY），拒绝加密渠道密钥");
        }
        byte[] key = registry.key(version).orElseThrow();
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = doFinal(Cipher.ENCRYPT_MODE, key, nonce, plaintext.getBytes(StandardCharsets.UTF_8));
        ByteBuffer combined = ByteBuffer.allocate(nonce.length + ciphertext.length);
        combined.put(nonce).put(ciphertext);
        return "v" + version + ":" + Base64.getEncoder().encodeToString(combined.array());
    }

    /** 解密；任何失败都返回空而不是抛异常。 */
    public Optional<String> decrypt(String payload) {
        if (payload == null) {
            return Optional.empty();
        }
        Matcher matcher = LABEL.matcher(payload.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int version;
        byte[] combined;
        try {
            version = Integer.parseInt(matcher.group(1).substring(1));
            combined = Base64.getDecoder().decode(matcher.group(2).strip());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        Optional<byte[]> key = registry.key(version);
        if (key.isEmpty() || combined.length <= NONCE_LENGTH_BYTES) {
            return Optional.empty();
        }
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        byte[] ciphertext = new byte[combined.length - NONCE_LENGTH_BYTES];
        System.arraycopy(combined, 0, nonce, 0, NONCE_LENGTH_BYTES);
        System.arraycopy(combined, NONCE_LENGTH_BYTES, ciphertext, 0, ciphertext.length);
        try {
            byte[] plaintext = doFinal(Cipher.DECRYPT_MODE, key.get(), nonce, ciphertext);
            return Optional.of(new String(plaintext, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // AEADBadTagException 走这里：被篡改 / 用错密钥 / 载荷被截断。
            return Optional.empty();
        }
    }

    /** 载荷在当前主密钥表里能不能解（运维巡检用：不解密就能数出「有几行解不开」）。 */
    public boolean canDecrypt(String payload) {
        return decrypt(payload).isPresent();
    }

    /** 只读版本标签（{@code v1}），畸形或 null 返回 {@code null}；不解密、不泄漏内容。 */
    public static String labelOf(String payload) {
        if (payload == null) {
            return null;
        }
        Matcher matcher = LABEL.matcher(payload.strip());
        return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static byte[] doFinal(int mode, byte[] key, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // 解密侧由调用方捕获 RuntimeException 折算成空 Optional；这条统一包成
            // IllegalStateException（本机 JDK 的 AES-GCM 不会在这里失败）。
            throw new IllegalStateException("AES-GCM 运算失败", e);
        }
    }
}
```

- [ ] **Step 5: 运行测试，确认全绿**

```powershell
mvn -B -pl aihub-admin/aihub-common test "-Dtest=ChannelKeyCipherTest"
```

预期：`Tests run: 12, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 6: 跑该模块全部测试（确认没破坏既有 21 项）**

```powershell
mvn -B -pl aihub-admin/aihub-common test
```

预期：`Tests run: 33, Failures: 0, Errors: 0`（基线 21 + 本任务 12）。**自己数一遍 `@Test` 并报告实测数字**，不要照抄这个数字。

- [ ] **Step 7: 验证「main 作用域零依赖」仍然成立**

```powershell
Select-String -Path aihub-admin/aihub-common/src/main/java/com/aihub/common/crypto/*.java -Pattern "^import" | ForEach-Object { $_.Line.Trim() } | Sort-Object -Unique
```

预期：输出里**只**出现 `java.*` 与 `javax.crypto.*`。出现任何 `com.fasterxml` / `org.springframework` / `io.lettuce` / `com.github.benmanes` 都是错的。

- [ ] **Step 8: 提交**

```powershell
git add aihub-admin/aihub-common/src/main/java/com/aihub/common/crypto aihub-admin/aihub-common/src/test/java/com/aihub/common/crypto
git commit -m "feat: add the shared aes-gcm channel key cipher with dual-version master keys"
```

**验收标准**
1. `encrypt` / `decrypt` 严格互逆；同一明文两次加密的密文不同（nonce 每次新生成）；篡改 nonce 或密文任一字节都解不开。
2. 载荷自描述版本（`v{n}:`），主密钥表里同时存在 v1/v2 时旧密文仍可解；`currentVersion()` 是解析成功的最大版本号。
3. 主密钥表为空时 `encrypt` 抛 `IllegalStateException`、`decrypt` 返回空 `Optional`（**永不抛**）；畸形载荷与未知版本一律返回空。
4. `aihub-common` 的 main 作用域仍然零第三方依赖（Step 7 的输出只剩 `java.*` / `javax.crypto.*`）。
5. 测试里没有任何真实密钥（只有 `sk-channel-plaintext-synthetic` 这类一眼可辨的合成值）。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-admin/aihub-common test "-Dtest=ChannelKeyCipherTest"` | `Tests run: 12, Failures: 0, Errors: 0` + `BUILD SUCCESS` |
| `mvn -B -pl aihub-admin/aihub-common test` | `Tests run: 33, Failures: 0, Errors: 0` + `BUILD SUCCESS` |
| `Select-String … -Pattern "^import"`（Step 7） | 只有 `java.*` / `javax.crypto.*` |

> 退出码提醒：本机实测过 `BUILD SUCCESS` 之后进程/管道仍报 `[exit code: 1]`（也见过退出 0）。判定以 surefire 汇总行与 `BUILD SUCCESS` 为准。

---

## Task 2: 共享数据契约与编解码（`aihub-common`：配置快照 + `ApiKeyView` 的数值主键）

> **本任务承担两个契约**：① 配置快照的四个 record 与 `ConfigSnapshotCodec`（决策 4）；
> ② 决策 14 的 `ApiKeyView` 第 6 个分量与 `ApiKeyCacheCodec` 的 5 → 6 段。两者都是「后续任务直接使用」的
> 共享契约，因此都必须**在本任务内一次性落地并让整个反应堆编译通过**（全局约束：不许跨任务占位）。
> 第 ② 项之所以在这里而不是在 Task 11：Task 4 的 `RateLimitResolver.resolve(tenantId, apiKeyId)` 与
> Task 9 的限流过滤器都要读这个数值主键，契约晚于使用方就意味着 Task 9 必须带一个占位 —— 那正是
> 本计划 2026-09-26 修掉的结构问题。

**Files:**
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ChannelDescriptor.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ModelRouteDescriptor.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/RatePolicy.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshot.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshotCodec.java`
- Modify: `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyView.java`（**决策 14**：加可空 `Long apiKeyId`）
- Modify: `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyCacheCodec.java`（**决策 14**：5 段 → 6 段）
- Modify: `aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyService.java`（`mint` 与 `loadFromDb` 各一行，填 `api_key.id`）
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java`（`parse` 透传 `apiKeyId`，一行）
- Modify: `aihub-gateway/src/test/java/com/aihub/gateway/auth/ApiKeyAuthFilterTest.java`、`aihub-gateway/src/test/java/com/aihub/gateway/auth/ApiKeyFilterContractTest.java`、`aihub-gateway/src/test/java/com/aihub/gateway/meter/RelayMeteringTest.java`、`aihub-gateway/src/test/java/com/aihub/gateway/relay/RelayMeteringFlowTest.java`（既有 `new ApiKeyView(...)` 补第 6 个参数）
- Test: `aihub-admin/aihub-common/src/test/java/com/aihub/common/config/ConfigSnapshotCodecTest.java`
- Test: `aihub-admin/aihub-common/src/test/java/com/aihub/common/apikey/ApiKeyToolingTest.java`（**改**：固定向量同步 + 两条新用例）

**Interfaces:**
- Consumes: 无（JDK-only）。
- Produces：
  - `record ChannelDescriptor(long id, String name, String baseUrl, String apiKeyCipher, int keyVersion, int timeoutMs, String status, int weight, int priority)`，常量 `STATUS_ACTIVE = "ACTIVE"`，`boolean usable()`（`status` ACTIVE 且 `baseUrl` 非空且 `timeoutMs > 0`）。
  - `record ModelRouteDescriptor(String modelName, long channelId, int weight, int priority, String status)`，常量 `STATUS_ACTIVE`，`boolean usable()`。
  - `record RatePolicy(Long tenantId, Long apiKeyId, int qps, int burst)`，`boolean tenantLevel()`（`apiKeyId == null`），常量 `DEFAULT_QPS = 10` / `DEFAULT_BURST = 20`，静态 `RatePolicy defaultFor(long tenantId)`。
  - `record ConfigSnapshot(long version, long generatedAtEpochMilli, List<ChannelDescriptor> channels, List<ModelRouteDescriptor> routes, List<RatePolicy> ratePolicies, String defaultModel)`：静态 `ConfigSnapshot empty()`；`Optional<ChannelDescriptor> channel(long id)`；`List<ChannelDescriptor> channelsSupporting(String model)`；`List<ModelRouteDescriptor> routesFor(String model)`；`List<RatePolicy> tenantPolicies(long tenantId)`；`List<RatePolicy> keyPolicies(long tenantId, long apiKeyId)`；`Set<String> modelNames()`（排序去重）。**决策 7 修订后两个维度都要有各自的取值入口**：`keyPolicies` 是 key 级（`apiKeyId` 相等）、`tenantPolicies` 是租户级（`apiKeyId == null`）。
  - `ConfigSnapshotCodec`：`static String encode(ConfigSnapshot)`、`static ConfigSnapshot decode(String)`（畸形返回 `null`）、常量 `FORMAT_VERSION = 1`。
  - **重要**：`channelsSupporting(model)` 返回的是**渠道**列表（顺序 = `routes` 的顺序），`weight` / `priority` **以 `model_route` 的值为准**，因此 `RouteResolver`（Task 6）必须用 `routesFor(model)` 拿 route 级权重、再用 `channel(id)` 联表。两者都在同一个 record 上，避免两侧各拼一份。
  - **决策 14 的共享契约（本任务第 ② 项，Task 4 / 9 / 11 直接使用，不再有占位）**：
    - `record ApiKeyView(String keyId, long tenantId, String tenantName, String status, Instant expireAt, Long apiKeyId)`（**新参数在最后**；`apiKeyId` 可空，`UNUSABLE` 补 `null`）。
    - `ApiKeyCacheCodec` 载荷变为 **6 段**：`{keyId}|{tenantId}|{tenantName}|{status}|{expireAtEpochSecond}|{apiKeyId}`（第 6 段空串表示 `null`）；**旧 5 段载荷被 `decode` 判为畸形并返回 `null`**（缓存未命中 → 回源 admin → 重写，这是收敛行为而不是故障）。
    - 生产者/消费者各一行：admin `ApiKeyService` 的 `mint(...)` / `loadFromDb(...)` 填 `api_key.id`；gateway `AdminClient.Http.parse` 把响应里的 `apiKeyId` 读进视图。

**测试用例清单**（`ConfigSnapshotCodecTest`，12 条；**决策 14 的契约测试另加在 `ApiKeyToolingTest`**，见 Step 9）：

| 用例 | 钉住什么 |
|---|---|
| `encodingProducesThePinnedHeaderAndSectionOrder` | 首行 `#v1\|{version}\|{defaultModel}\|{generatedAt}`，之后 `C\|` / `R\|` / `L\|` 行，字段顺序固定 |
| `roundTripsAFullyPopulatedSnapshot` | 三张表都有数据时严格往返 |
| `roundTripsAnEmptySnapshot` | `ConfigSnapshot.empty()` 往返（`defaultModel` 为 null → 空段） |
| `roundTripsFieldsContainingDelimiterBackslashAndNewlines` | 渠道名含 `\|` `\` `\n` `\r` 时严格互逆，且编码结果不含裸的 `\r` |
| `decodeRejectsMalformedPayloads` | null / 空串 / 缺首行 / 格式版本不符 / 未知字段数 / 数字解析失败 → `null` |
| `decodeSkipsUnknownSectionLetters` | 未来新增段字母时老实现不炸（前向兼容） |
| `usableRequiresActiveStatusBaseUrlAndPositiveTimeout` | `ChannelDescriptor.usable()` 的三个条件 |
| `channelsSupportingIgnoresInactiveRoutesAndChannels` | `status != ACTIVE` 的路由/渠道都被排除；同一渠道不重复 |
| `routesForReturnsOnlyActiveRoutesOfThatModel` | route 级 weight/priority 的来源 |
| `tenantPoliciesFiltersToTenantLevelOfThatTenantAndKeyPoliciesToThatKey` | 决策 7（修订）：`tenantPolicies` 只给租户级、`keyPolicies` 只给该 `apiKeyId`，两个维度互不串味 |
| `modelNamesIsTheSortedDistinctUnionOfActiveRoutes` | `GET /v1/models` 的数据源 |
| `emptySnapshotHasNoChannelsAndNoModels` | 空快照的行为 |

- [ ] **Step 1: 写失败测试**

创建 `aihub-admin/aihub-common/src/test/java/com/aihub/common/config/ConfigSnapshotCodecTest.java`：

```java
package com.aihub.common.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照的三件事压在这里：① 四个共享 record 的语义（`usable()` / 候选解析 / 策略过滤）；
 * ② 分隔符编解码的严格互逆；③ 固定向量 —— Redis 里的字节形态从此不再随手可改。
 *
 * <p><b>它不是跨服务契约</b>：admin 发的是 JSON（网关用 Jackson 解），这份编解码只服务网关自己的
 * 两级缓存（Caffeine 与 Redis 载荷）。因此不需要「两侧字面量钉死」那一套，只需要
 * 「编解码互逆 + 畸形不炸 + 字段顺序稳定」。
 */
class ConfigSnapshotCodecTest {

    private static final ChannelDescriptor PRIMARY = new ChannelDescriptor(
            11L, "deepseek-primary", "https://api.deepseek.com", "v1:QUJD", 1, 60000, "ACTIVE", 100, 0);
    private static final ChannelDescriptor BACKUP = new ChannelDescriptor(
            12L, "deepseek-backup", "https://backup.example.com", "v2:QUJD", 2, 30000, "ACTIVE", 300, 0);

    private static ConfigSnapshot populated() {
        return new ConfigSnapshot(
                1_800_000_000_123L,
                1_800_000_000_456L,
                List.of(PRIMARY, BACKUP),
                List.of(new ModelRouteDescriptor("deepseek-chat", 11L, 100, 0, "ACTIVE"),
                        new ModelRouteDescriptor("deepseek-chat", 12L, 300, 0, "ACTIVE")),
                List.of(new RatePolicy(7L, null, 20, 40), new RatePolicy(7L, 42L, 100, 200)),
                "deepseek-chat");
    }

    @Test
    void encodingProducesThePinnedHeaderAndSectionOrder() {
        String payload = ConfigSnapshotCodec.encode(populated());
        List<String> lines = payload.lines().toList();

        assertThat(lines.get(0)).isEqualTo("#v1|1800000000123|deepseek-chat|1800000000456");
        assertThat(lines.get(1)).isEqualTo("C|11|deepseek-primary|https://api.deepseek.com|v1:QUJD|1|60000|ACTIVE|100|0");
        assertThat(lines.get(2)).isEqualTo("C|12|deepseek-backup|https://backup.example.com|v2:QUJD|2|30000|ACTIVE|300|0");
        assertThat(lines.get(3)).isEqualTo("R|deepseek-chat|11|100|0|ACTIVE");
        assertThat(lines.get(5)).isEqualTo("L|7||20|40");
        assertThat(lines.get(6)).isEqualTo("L|7|42|100|200");
    }

    @Test
    void roundTripsAFullyPopulatedSnapshot() {
        assertThat(ConfigSnapshotCodec.decode(ConfigSnapshotCodec.encode(populated()))).isEqualTo(populated());
    }

    @Test
    void roundTripsAnEmptySnapshot() {
        ConfigSnapshot empty = ConfigSnapshot.empty();
        String payload = ConfigSnapshotCodec.encode(empty);

        assertThat(payload.lines().toList().get(0)).isEqualTo("#v1|0||0");
        assertThat(ConfigSnapshotCodec.decode(payload)).isEqualTo(empty);
    }

    @Test
    void roundTripsFieldsContainingDelimiterBackslashAndNewlines() {
        for (String name : List.of("a|b", "a\\b", "line\nbreak", "cr\rlf", "\\|", "||||")) {
            ConfigSnapshot snapshot = new ConfigSnapshot(1L, 2L,
                    List.of(new ChannelDescriptor(1L, name, "https://x", "v1:QUJD", 1, 1000, "ACTIVE", 1, 0)),
                    List.of(), List.of(), name);

            String payload = ConfigSnapshotCodec.encode(snapshot);

            assertThat(payload).as("渠道名 %s 不得把载荷撑成多行", name).doesNotContain("\r");
            assertThat(ConfigSnapshotCodec.decode(payload))
                    .as("渠道名 %s（载荷 %s）必须严格往返", name, payload)
                    .isEqualTo(snapshot);
        }
    }

    @Test
    void decodeRejectsMalformedPayloads() {
        assertThat(ConfigSnapshotCodec.decode(null)).isNull();
        assertThat(ConfigSnapshotCodec.decode("")).isNull();
        assertThat(ConfigSnapshotCodec.decode("C|11|x|https://x|v1:QUJD|1|60000|ACTIVE|100|0")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v9|0||0")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v1|not-a-long||0")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v1|0||0\nC|11|x|https://x|v1:QUJD|1|60000|ACTIVE|100")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v1|0||0\nC|11|x|https://x|v1:QUJD|1|bad|ACTIVE|100|0")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v1|0||0\nR|m|11|100")).isNull();
        assertThat(ConfigSnapshotCodec.decode("#v1|0||0\nL|7||20")).isNull();
    }

    @Test
    void decodeSkipsUnknownSectionLetters() {
        ConfigSnapshot decoded = ConfigSnapshotCodec.decode("#v1|5||9\nX|future|stuff\nL|7||20|40");

        assertThat(decoded).isNotNull();
        assertThat(decoded.version()).isEqualTo(5L);
        assertThat(decoded.ratePolicies()).hasSize(1);
    }

    @Test
    void usableRequiresActiveStatusBaseUrlAndPositiveTimeout() {
        assertThat(PRIMARY.usable()).isTrue();
        assertThat(new ChannelDescriptor(1L, "x", "https://x", "v1:QUJD", 1, 1000, "DISABLED", 1, 0).usable()).isFalse();
        assertThat(new ChannelDescriptor(1L, "x", "", "v1:QUJD", 1, 1000, "ACTIVE", 1, 0).usable()).isFalse();
        assertThat(new ChannelDescriptor(1L, "x", "https://x", "v1:QUJD", 1, 0, "ACTIVE", 1, 0).usable()).isFalse();
    }

    @Test
    void channelsSupportingIgnoresInactiveRoutesAndChannels() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, 2L,
                List.of(PRIMARY, new ChannelDescriptor(12L, "off", "https://b", "v1:QUJD", 1, 1000, "DISABLED", 1, 0)),
                List.of(new ModelRouteDescriptor("m", 11L, 1, 0, "ACTIVE"),
                        new ModelRouteDescriptor("m", 12L, 1, 0, "ACTIVE"),
                        new ModelRouteDescriptor("m", 11L, 1, 0, "DISABLED"),
                        new ModelRouteDescriptor("m2", 11L, 1, 0, "DISABLED")),
                List.of(), null);

        assertThat(snapshot.channelsSupporting("m")).extracting(ChannelDescriptor::id).containsExactly(11L);
        assertThat(snapshot.channelsSupporting("m2")).isEmpty();
        assertThat(snapshot.channelsSupporting("unknown")).isEmpty();
        assertThat(snapshot.channelsSupporting(null)).isEmpty();
        assertThat(snapshot.channel(99L)).isEqualTo(Optional.empty());
        assertThat(snapshot.channel(11L)).contains(PRIMARY);
    }

    @Test
    void routesForReturnsOnlyActiveRoutesOfThatModel() {
        assertThat(populated().routesFor("deepseek-chat"))
                .extracting(ModelRouteDescriptor::channelId).containsExactly(11L, 12L);
        assertThat(populated().routesFor("other")).isEmpty();
        assertThat(populated().routesFor(null)).isEmpty();
    }

    @Test
    void tenantPoliciesFiltersToTenantLevelOfThatTenantAndKeyPoliciesToThatKey() {
        // 决策 7（已按控制器 pre-flight 评审修订）：两个维度都参与判定，因此两个入口都必须
        // 只返回自己那一维的行 —— key 级行不得混进 tenantPolicies（否则「租户级回落」会拿到 key 级策略），
        // 租户级行也不得混进 keyPolicies（否则「key 级优先」会命中不属于这个 key 的策略）。
        assertThat(populated().tenantPolicies(7L))
                .as("key 级策略（apiKeyId=42）不得出现在租户级结果里")
                .hasSize(1);
        assertThat(populated().tenantPolicies(7L).get(0).qps()).isEqualTo(20);
        assertThat(populated().tenantPolicies(8L)).isEmpty();

        assertThat(populated().keyPolicies(7L, 42L))
                .as("只应命中该租户该 key 的那一行")
                .hasSize(1);
        assertThat(populated().keyPolicies(7L, 42L).get(0).qps()).isEqualTo(100);
        assertThat(populated().keyPolicies(7L, 43L)).as("别的 key 不得命中").isEmpty();
        assertThat(populated().keyPolicies(8L, 42L)).as("别的租户不得命中").isEmpty();
    }

    @Test
    void modelNamesIsTheSortedDistinctUnionOfActiveRoutes() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, 2L, List.of(), List.of(
                new ModelRouteDescriptor("b", 1L, 1, 0, "ACTIVE"),
                new ModelRouteDescriptor("a", 1L, 1, 0, "ACTIVE"),
                new ModelRouteDescriptor("b", 2L, 1, 0, "ACTIVE"),
                new ModelRouteDescriptor("off", 1L, 1, 0, "DISABLED")), List.of(), null);

        assertThat(snapshot.modelNames()).containsExactly("a", "b");
    }

    @Test
    void emptySnapshotHasNoChannelsAndNoModels() {
        assertThat(ConfigSnapshot.empty().channels()).isEmpty();
        assertThat(ConfigSnapshot.empty().modelNames()).isEmpty();
        assertThat(ConfigSnapshot.empty().channelsSupporting("m")).isEmpty();
        assertThat(ConfigSnapshot.empty().version()).isZero();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```powershell
mvn -B -pl aihub-admin/aihub-common test "-Dtest=ConfigSnapshotCodecTest"
```

预期：`BUILD FAILURE` + `cannot find symbol: class ConfigSnapshot`。

- [ ] **Step 3: 写四个 record**

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ChannelDescriptor.java`：

```java
package com.aihub.common.config;

/**
 * 一条上游渠道的**纯数据视图**。admin 从 {@code channel} 表组装（含 AES-GCM 密文），
 * gateway 解密后使用；字段与 V1 的列一一对应，因此两侧不需要各自定义 DTO。
 *
 * <p>{@code apiKeyCipher} 是**密文**：明文渠道密钥永不跨越网络（设计文档 §6.1）。
 *
 * @param id           {@code channel.id}（网关放进计量事件的 {@code channel_id}）
 * @param name         渠道名，仅用于日志与运维定位（**明文密钥绝不出现在日志里**）
 * @param baseUrl      上游 base-url
 * @param apiKeyCipher AES-GCM 自描述密文（{@code v{n}:{base64}}）
 * @param keyVersion   admin 侧记录的版本号（展示/巡检用；解密以密文里的标签为准）
 * @param timeoutMs    单次上游请求的超时（**只对非流式生效**；流式不设响应超时，见 Task 8）
 * @param status       {@code ACTIVE} / {@code DISABLED}
 * @param weight       渠道级默认权重（**路由权重以 {@code model_route.weight} 为准**）
 * @param priority     渠道级默认优先级（**路由优先级以 {@code model_route.priority} 为准**）
 */
public record ChannelDescriptor(long id, String name, String baseUrl, String apiKeyCipher, int keyVersion,
                                int timeoutMs, String status, int weight, int priority) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 「这条渠道现在能用吗」的**唯一**判据（与 {@code ApiKeyView.usable()} 同一套纪律：只有一处定义）。 */
    public boolean usable() {
        return STATUS_ACTIVE.equals(status)
                && baseUrl != null && !baseUrl.isBlank()
                && timeoutMs > 0;
    }
}
```

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ModelRouteDescriptor.java`：

```java
package com.aihub.common.config;

/**
 * 一条「模型 → 渠道」候选。权重与优先级取 {@code model_route} 的值
 * （**不是** {@code channel} 的默认值）：同一个渠道可以给不同模型不同的权重，这是灰度与容量的常用手段。
 */
public record ModelRouteDescriptor(String modelName, long channelId, int weight, int priority, String status) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public boolean usable() {
        return STATUS_ACTIVE.equals(status) && modelName != null && !modelName.isBlank();
    }
}
```

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/RatePolicy.java`：

```java
package com.aihub.common.config;

/**
 * 限流策略的纯数据视图（对应 {@code rate_limit_policy}）。
 *
 * <p><b>两个维度 ({@code tenantId} / {@code apiKeyId}) 都参与判定</b>（决策 7，2026-09-26 依控制器
 * pre-flight 评审修订）：{@code apiKeyId == null} 是**租户级**策略（该租户所有 key 的兜底），
 * 非空是**key 级**策略（只作用于该 {@code api_key.id}）。{@link ConfigSnapshot} 上两个维度各有
 * 一个取值入口，由 gateway 的 {@code RateLimitResolver} 按「key 级优先 → 租户级回落 → 内置默认」
 * 的顺序取（Task 4）。
 */
public record RatePolicy(Long tenantId, Long apiKeyId, int qps, int burst) {

    public static final int DEFAULT_QPS = 10;
    public static final int DEFAULT_BURST = 20;

    /** 该策略是否作用于整个租户（key 级为 false）。 */
    public boolean tenantLevel() {
        return apiKeyId == null;
    }

    /** 没有任何策略行（或策略值非法）时的兜底：与 V1 两列的默认值一致。 */
    public static RatePolicy defaultFor(long tenantId) {
        return new RatePolicy(tenantId, null, DEFAULT_QPS, DEFAULT_BURST);
    }
}
```

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshot.java`：

```java
package com.aihub.common.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一次 {@code GET /internal/config/snapshot} 的完整结果：渠道（含密文）+ 路由 + 限流策略 + 默认模型
 * + 单调递增的 {@code version}。
 *
 * <p>{@code version} 是 §6.3「本地版本落后则丢弃并回源」的判据，也是缓存失效的唯一信号
 * （M3 没有 Pub/Sub 发布方，见计划决策 16）。
 *
 * <p>{@link #channelsSupporting(String)} / {@link #routesFor(String)} 是路由的**唯一**入口：
 * 它们把「路由行」与「渠道行」的联表逻辑收在一处，避免「admin 与 gateway 各拼一份候选」这种必然漂移的写法。
 */
public record ConfigSnapshot(long version, long generatedAtEpochMilli,
                             List<ChannelDescriptor> channels, List<ModelRouteDescriptor> routes,
                             List<RatePolicy> ratePolicies, String defaultModel) {

    public ConfigSnapshot {
        channels = List.copyOf(channels);
        routes = List.copyOf(routes);
        ratePolicies = List.copyOf(ratePolicies);
    }

    /** 冷启动 + admin 不可达时的空快照（调用方再决定是否回落「遗留单渠道」）。 */
    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(0L, 0L, List.of(), List.of(), List.of(), null);
    }

    public Optional<ChannelDescriptor> channel(long id) {
        return channels.stream().filter(channel -> channel.id() == id).findFirst();
    }

    /** 某个模型对应的路由行（**route 级 weight/priority 的来源**）。 */
    public List<ModelRouteDescriptor> routesFor(String model) {
        if (model == null || model.isBlank()) {
            return List.of();
        }
        List<ModelRouteDescriptor> matched = new ArrayList<>();
        for (ModelRouteDescriptor route : routes) {
            if (route.usable() && model.equals(route.modelName())) {
                matched.add(route);
            }
        }
        return matched;
    }

    /**
     * 某个模型可以走的渠道（**顺序保持 {@code routes} 的顺序**；同一渠道不重复）。
     * 选择算法在 gateway 的 {@code RouteResolver} 里，本方法只负责联表与过滤。
     */
    public List<ChannelDescriptor> channelsSupporting(String model) {
        List<ChannelDescriptor> candidates = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (ModelRouteDescriptor route : routesFor(model)) {
            if (!seen.add(route.channelId())) {
                continue;
            }
            channel(route.channelId()).filter(ChannelDescriptor::usable).ifPresent(candidates::add);
        }
        return candidates;
    }

    /**
     * 该租户的**租户级**策略（{@code apiKeyId == null}，决策 7 的第二级）；
     * 顺序 = 组装顺序（admin 已按 id 升序），因此「取最后一条」= 「取 id 最大的那条」。
     */
    public List<RatePolicy> tenantPolicies(long tenantId) {
        List<RatePolicy> matched = new ArrayList<>();
        for (RatePolicy policy : ratePolicies) {
            if (policy.tenantLevel() && policy.tenantId() != null && policy.tenantId() == tenantId) {
                matched.add(policy);
            }
        }
        return matched;
    }

    /**
     * 该租户、该 key 的 **key 级**策略（{@code apiKeyId} 非空且相等 —— 决策 7 的第一级）。
     * 顺序同上，因此「取最后一条」同样等于「取 id 最大的那条」。
     *
     * <p>它是 {@code RateLimitResolver} 的**第一优先**来源：这一维**只要有一行**就由它收口
     * （值非法则回落到内置默认，**不**再去看租户级那一维 —— 一条写坏的 key 级行不该变成
     * 「额度比不写还大」）。**这里不做值校验**（非正的 qps/burst 由 resolver 判定），
     * 本方法只负责「哪些行属于这个 (租户, key)」。
     */
    public List<RatePolicy> keyPolicies(long tenantId, long apiKeyId) {
        List<RatePolicy> matched = new ArrayList<>();
        for (RatePolicy policy : ratePolicies) {
            if (!policy.tenantLevel() && policy.tenantId() != null && policy.tenantId() == tenantId
                    && policy.apiKeyId() == apiKeyId) {
                matched.add(policy);
            }
        }
        return matched;
    }

    /** {@code GET /v1/models} 的数据源（排序去重，便于测试与客户端缓存）。 */
    public Set<String> modelNames() {
        Set<String> names = new TreeSet<>();
        for (ModelRouteDescriptor route : routes) {
            if (route.usable()) {
                names.add(route.modelName());
            }
        }
        return names;
    }
}
```

- [ ] **Step 4: 写 `ConfigSnapshotCodec`**

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshotCodec.java`：

```java
package com.aihub.common.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 快照的分隔符编解码。**只服务 gateway 自己的两级缓存**（Caffeine 值 + Redis value），
 * 不是跨服务契约 —— admin 发的是 JSON（网关用 Jackson 解），因此这里不需要
 * 「两侧共用同一份字面量」的强约束，只需要「严格互逆 + 畸形不炸」。
 *
 * <p>格式：
 * <pre>
 * #v1|{version}|{defaultModel}|{generatedAtEpochMilli}
 * C|{id}|{name}|{baseUrl}|{apiKeyCipher}|{keyVersion}|{timeoutMs}|{status}|{weight}|{priority}
 * R|{modelName}|{channelId}|{weight}|{priority}|{status}
 * L|{tenantId}|{apiKeyId}|{qps}|{burst}
 * </pre>
 * 字段内转义 {@code \} {@code |} {@code \n} {@code \r}（与 {@code MeteringEventCodec} /
 * {@code ApiKeyCacheCodec} 同一套转义器语义）。**未知段字母被跳过**：将来加新段时，老网关
 * 读到新载荷不会整体判死，而是丢掉不认识的那一段。
 */
public final class ConfigSnapshotCodec {

    /** 载荷格式版本。首行以 {@code #v{FORMAT_VERSION}} 开头，不认识就判为载荷畸形。 */
    public static final int FORMAT_VERSION = 1;

    private static final String HEADER_PREFIX = "#v";
    private static final String DELIMITER = "|";
    private static final String SECTION_CHANNEL = "C";
    private static final String SECTION_ROUTE = "R";
    private static final String SECTION_POLICY = "L";
    private static final int HEADER_FIELDS = 4;
    private static final int CHANNEL_FIELDS = 10;
    private static final int ROUTE_FIELDS = 6;
    private static final int POLICY_FIELDS = 4;

    private ConfigSnapshotCodec() {
    }

    public static String encode(ConfigSnapshot snapshot) {
        StringBuilder out = new StringBuilder(512);
        out.append(HEADER_PREFIX).append(FORMAT_VERSION).append(DELIMITER)
                .append(snapshot.version()).append(DELIMITER)
                .append(escape(snapshot.defaultModel())).append(DELIMITER)
                .append(snapshot.generatedAtEpochMilli());
        for (ChannelDescriptor channel : snapshot.channels()) {
            out.append('\n').append(SECTION_CHANNEL).append(DELIMITER)
                    .append(channel.id()).append(DELIMITER)
                    .append(escape(channel.name())).append(DELIMITER)
                    .append(escape(channel.baseUrl())).append(DELIMITER)
                    .append(escape(channel.apiKeyCipher())).append(DELIMITER)
                    .append(channel.keyVersion()).append(DELIMITER)
                    .append(channel.timeoutMs()).append(DELIMITER)
                    .append(escape(channel.status())).append(DELIMITER)
                    .append(channel.weight()).append(DELIMITER)
                    .append(channel.priority());
        }
        for (ModelRouteDescriptor route : snapshot.routes()) {
            out.append('\n').append(SECTION_ROUTE).append(DELIMITER)
                    .append(escape(route.modelName())).append(DELIMITER)
                    .append(route.channelId()).append(DELIMITER)
                    .append(route.weight()).append(DELIMITER)
                    .append(route.priority()).append(DELIMITER)
                    .append(escape(route.status()));
        }
        for (RatePolicy policy : snapshot.ratePolicies()) {
            out.append('\n').append(SECTION_POLICY).append(DELIMITER)
                    .append(policy.tenantId() == null ? "" : policy.tenantId()).append(DELIMITER)
                    .append(policy.apiKeyId() == null ? "" : policy.apiKeyId()).append(DELIMITER)
                    .append(policy.qps()).append(DELIMITER)
                    .append(policy.burst());
        }
        return out.toString();
    }

    /** 载荷畸形（首行不符、字段个数不对、数字解析失败）时返回 {@code null}：调用方视作缓存未命中。 */
    public static ConfigSnapshot decode(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        String[] lines = payload.split("\n", -1);
        List<String> header = splitFields(lines[0]);
        if (header.size() != HEADER_FIELDS || !(HEADER_PREFIX + FORMAT_VERSION).equals(header.get(0))) {
            return null;
        }
        List<ChannelDescriptor> channels = new ArrayList<>();
        List<ModelRouteDescriptor> routes = new ArrayList<>();
        List<RatePolicy> policies = new ArrayList<>();
        try {
            long version = Long.parseLong(header.get(1));
            String defaultModel = emptyToNull(header.get(2));
            long generatedAt = Long.parseLong(header.get(3));
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                List<String> fields = splitFields(lines[i]);
                switch (fields.get(0)) {
                    case SECTION_CHANNEL -> {
                        if (fields.size() != CHANNEL_FIELDS) {
                            return null;
                        }
                        channels.add(new ChannelDescriptor(
                                Long.parseLong(fields.get(1)), emptyToNull(fields.get(2)), emptyToNull(fields.get(3)),
                                emptyToNull(fields.get(4)), Integer.parseInt(fields.get(5)),
                                Integer.parseInt(fields.get(6)), emptyToNull(fields.get(7)),
                                Integer.parseInt(fields.get(8)), Integer.parseInt(fields.get(9))));
                    }
                    case SECTION_ROUTE -> {
                        if (fields.size() != ROUTE_FIELDS) {
                            return null;
                        }
                        routes.add(new ModelRouteDescriptor(
                                emptyToNull(fields.get(1)), Long.parseLong(fields.get(2)),
                                Integer.parseInt(fields.get(3)), Integer.parseInt(fields.get(4)),
                                emptyToNull(fields.get(5))));
                    }
                    case SECTION_POLICY -> {
                        if (fields.size() != POLICY_FIELDS) {
                            return null;
                        }
                        policies.add(new RatePolicy(
                                optionalLong(fields.get(1)), optionalLong(fields.get(2)),
                                Integer.parseInt(fields.get(3)), Integer.parseInt(fields.get(4))));
                    }
                    // 不认识的段字母：跳过（前向兼容），不让整份载荷判死。
                    default -> {
                    }
                }
            }
            return new ConfigSnapshot(version, generatedAt, channels, routes, policies, defaultModel);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long optionalLong(String value) {
        return value.isEmpty() ? null : Long.valueOf(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '|' -> out.append("\\|");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** 单趟扫描：{@code \\} {@code \|} {@code \n} {@code \r} 还原成一个字符，光秃秃的 {@code |} 才是边界。 */
    private static List<String> splitFields(String line) {
        List<String> parts = new ArrayList<>(CHANNEL_FIELDS);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                switch (next) {
                    case '\\' -> {
                        current.append('\\');
                        i++;
                        continue;
                    }
                    case '|' -> {
                        current.append('|');
                        i++;
                        continue;
                    }
                    case 'n' -> {
                        current.append('\n');
                        i++;
                        continue;
                    }
                    case 'r' -> {
                        current.append('\r');
                        i++;
                        continue;
                    }
                    default -> {
                        // 孤立的转义符按字面量保留。
                    }
                }
            }
            if (c == '|') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }
}
```

- [ ] **Step 5: 运行测试，确认全绿**

```powershell
mvn -B -pl aihub-admin/aihub-common test "-Dtest=ConfigSnapshotCodecTest"
```

预期：`Tests run: 12, Failures: 0, Errors: 0` + `BUILD SUCCESS`。若 `roundTripsFieldsContainingDelimiterBackslashAndNewlines` 报「不得把载荷撑成多行」，说明转义漏了 `\n`/`\r`。

- [ ] **Step 6: 跑该模块全部测试**

```powershell
mvn -B -pl aihub-admin/aihub-common test
```

预期：`Tests run: 45, Failures: 0, Errors: 0`（Task 1 的 33 + 本任务 12）。报告实测数字。

- [ ] **Step 7: 改 `ApiKeyView`（决策 14：加可空 `Long apiKeyId`）**

> **从这里开始的第 ② 项契约与前面的快照契约在**同一个提交**里落地**（全局约束：契约变更必须与它的
> 生产者/消费者同一个提交，否则后续任务就得带占位）。第 ② 项是决策 14，它被 Task 4（策略解析）、
> Task 9（限流过滤器读数值主键）、Task 11（计量落库）直接使用 —— 因此必须在本任务内完成，
> 让整个反应堆编译通过。

把 `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyView.java` 的 record 头与 `UNUSABLE` 改为：

```java
public record ApiKeyView(String keyId, long tenantId, String tenantName, String status, Instant expireAt,
                         Long apiKeyId) {
```

```java
    public static final ApiKeyView UNUSABLE = new ApiKeyView("", 0L, "", "MISSING", null, null);
```

并在类 javadoc 里补一段：

```java
 * <p>{@code apiKeyId} 是 {@code api_key} 表的**数值主键**，M3 起随密钥视图一起下发：
 * M2 时它恒为 {@code null}（共享类型里没有它），导致 {@code request_log.api_key_id} 无法填充、
 * 用量无法按 API Key 聚合，也导致限流无法把请求映射到 {@code rate_limit_policy.api_key_id}
 * （决策 7 的两维策略需要一个数值键）。补齐它需要同时改三处 —— 本 record、{@code ApiKeyCacheCodec}
 * 的载荷段数、两侧的解析（admin 的 {@code ApiKeyService} 与 gateway 的 {@code AdminClient.Http.parse}）
 * （计划决策 14）。{@code UNUSABLE} 与「查不到」的哨兵仍然带 {@code null}：**匿名桶没有数值主键，
 * 那是正常路径而不是错误**。
```

> 第 6 个参数放在**最后**（`expireAt` 之后）：所有既有构造点都会变成编译错误，编译器会把它们
> 一一点出来 —— 这是好事（见 Step 11），而不是要绕开的麻烦。

- [ ] **Step 8: 改 `ApiKeyCacheCodec`（5 段 → 6 段）**

在 `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyCacheCodec.java` 里把 `FIELD_COUNT` 改为 `6`，`encode` 末尾追加一段，`decode` 解析第 6 段：

```java
    /**
     * 载荷段数。M3 起是 6（新增 {@code apiKeyId}）；旧载荷会被 {@link #decode} 判为畸形
     * → 缓存未命中 → 回源重写。这是**收敛行为**，不是故障，因此不需要清 Redis。
     */
    private static final int FIELD_COUNT = 6;
```

```java
    public static String encode(ApiKeyView view) {
        return escape(view.keyId()) + DELIMITER
                + view.tenantId() + DELIMITER
                + escape(view.tenantName()) + DELIMITER
                + escape(view.status()) + DELIMITER
                + (view.expireAt() == null ? "" : String.valueOf(view.expireAt().getEpochSecond())) + DELIMITER
                + (view.apiKeyId() == null ? "" : String.valueOf(view.apiKeyId()));
    }
```

`decode` 里把第 5、6 段一起取出再构造：

```java
        try {
            String expireAt = parts.get(4);
            String apiKeyId = parts.get(5);
            return new ApiKeyView(parts.get(0), Long.parseLong(parts.get(1)), parts.get(2), parts.get(3),
                    expireAt.isEmpty() ? null : Instant.ofEpochSecond(Long.parseLong(expireAt)),
                    apiKeyId.isEmpty() ? null : Long.valueOf(apiKeyId));
        } catch (RuntimeException e) {
            return null;
        }
```

并在类 javadoc（现在是 `{@code keyId|tenantId|tenantName|status|expireAtEpochSecond}` 那句）里改成 6 段并补一句：

```java
 * <p>载荷：{@code keyId|tenantId|tenantName|status|expireAtEpochSecond|apiKeyId}（第 6 段空串表示 null）。
 *
 * <p><b>段数是跨服务契约</b>：gateway 读 admin 写的载荷，任何一侧改了段数都会让另一侧
 * {@code decode} 返回 {@code null}（缓存未命中 → 回源 → 重写）。这**不是**故障，是收敛行为，
 * 因此不需要清 Redis；但改段数时必须同步 {@code ApiKeyToolingTest} 的固定向量。
```

- [ ] **Step 9: 同步契约测试 `ApiKeyToolingTest`（固定向量 + 两条新用例）**

在 `aihub-admin/aihub-common/src/test/java/com/aihub/common/apikey/ApiKeyToolingTest.java` 里：

（a）所有既有的 `new ApiKeyView(...)` 补第 6 个参数 —— `cacheCodecRoundTripsANormalView`、
`cacheCodecRoundTripsANullExpireAt`、`cacheCodecRoundTripsNamesContainingDelimiterAndBackslash` 用数值主键 `42L`
（它们钉的是编解码互逆）；`viewIsUsableOnlyWhenActiveAndUnexpired` 的四处构造用 `null`（它们只钉 `usable()`）。

（b）把段数契约的那条用例改成 6 段下的形态（**这不是「削弱」，而是把契约从 5 段换成 6 段**：
4 段仍然畸形、7 段仍然畸形，第 6 段的数字解析失败也必须畸形）：

```java
    @Test
    void cacheCodecReturnsNullForMalformedPayloads() {
        assertThat(ApiKeyCacheCodec.decode(null)).isNull();
        assertThat(ApiKeyCacheCodec.decode("")).isNull();
        assertThat(ApiKeyCacheCodec.decode("not-a-key-view")).isNull();
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE")).isNull();               // 只有 4 段
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE|1|42|extra")).isNull();    // 有 7 段
        assertThat(ApiKeyCacheCodec.decode("ak_x|not-a-long|acme|ACTIVE||")).isNull();
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE|not-an-epoch|")).isNull();
        assertThat(ApiKeyCacheCodec.decode("ak_x|42|acme|ACTIVE||not-a-long")).isNull();
    }
```

（c）新增两条用例（契约测试本身）：

```java
    /** 第 6 段是数值主键：非空时必须严格往返，且载荷的字面量形态被钉死。 */
    @Test
    void cacheCodecRoundTripsTheNumericApiKeyId() {
        ApiKeyView view = new ApiKeyView("ak_abc", 7L, "demo", ApiKeyView.STATUS_ACTIVE, null, 42L);

        assertThat(ApiKeyCacheCodec.encode(view)).isEqualTo("ak_abc|7|demo|ACTIVE||42");
        assertThat(ApiKeyCacheCodec.decode(ApiKeyCacheCodec.encode(view))).isEqualTo(view);
    }

    /**
     * 旧载荷（5 段）必须被判为畸形 → 缓存未命中 → 回源 admin → 重写，而不是解出一个
     * {@code apiKeyId = null} 的「半成品」—— 后者会让限流静默丢掉 key 级策略（决策 7 修订的那一维）。
     */
    @Test
    void legacyFiveFieldPayloadsAreRejectedSoTheCacheConverges() {
        assertThat(ApiKeyCacheCodec.decode("ak_abc|7|demo|ACTIVE|1800000000")).isNull();
    }
```

```powershell
mvn -B -pl aihub-admin/aihub-common test "-Dtest=ApiKeyToolingTest"
```

预期：`Tests run: 15, Failures: 0, Errors: 0` + `BUILD SUCCESS`（既有 13 + 新增 2）。

- [ ] **Step 10: 生产者与消费者各一行（同一个提交里必须一起改）**

（a）在 `aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyService.java` 的 `loadFromDb` 返回里补第 6 个参数：

```java
        return Optional.of(new ApiKeyView(entity.getKeyId(), entity.getTenantId(), tenantName,
                entity.getStatus(), entity.getExpireAt(), entity.getId()));
```

`mint(...)` 里的 `cache(keyHash, new ApiKeyView(...))` 也补 `entity.getId()`（MyBatis-Plus 在 insert 后会回填自增主键）：

```java
        cache(keyHash, new ApiKeyView(keyId, tenant.getId(), tenantName, ApiKeyView.STATUS_ACTIVE, expireAt,
                entity.getId()));
```

（b）在 `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java` 的 `Http.parse(int status, String body)` 里把构造 `ApiKeyView` 的那一行改成：

```java
                return Optional.of(new ApiKeyView(
                        data.path("keyId").asText(),
                        data.path("tenantId").asLong(),
                        data.path("tenantName").asText(),
                        data.path("status").asText(),
                        expireAt.isNull() || expireAt.isMissingNode() ? null : Instant.parse(expireAt.asText()),
                        data.path("apiKeyId").isNumber() ? data.get("apiKeyId").asLong() : null));
```

> admin 的 `/internal/api-keys/resolve` 与网关的 `parse` 共用同一个 `ApiKeyView`，因此 admin 侧加了第 6 个
> 分量后响应体里会**自动**多出 `apiKeyId`；网关侧只需要把这一行读进来。**Task 7 新增 `configSnapshot()` 时
> 不要再动这一行**（那时它已经是 6 个参数）。

- [ ] **Step 11: 编译整个反应堆，补掉所有既有 `new ApiKeyView(...)` 调用点**

```powershell
mvn -B -q test-compile -DskipTests
```

预期：`BUILD FAILURE` 并列出所有 `constructor ApiKeyView cannot be applied to given types` 的位置。**逐个补参数**（测试里补 `42L`、或补 `null` 表示「这条用例不关心数值主键」；生产代码里补真实值），至少包括：

- `ApiKeyToolingTest`（Step 9 已补）；
- `aihub-gateway/src/test/java/com/aihub/gateway/auth/ApiKeyAuthFilterTest.java`（两处构造）；
- `aihub-gateway/src/test/java/com/aihub/gateway/auth/ApiKeyFilterContractTest.java` 的 `VALID_VIEW`；
- `aihub-gateway/src/test/java/com/aihub/gateway/meter/RelayMeteringTest.java`；
- `aihub-gateway/src/test/java/com/aihub/gateway/relay/RelayMeteringFlowTest.java` 的假 admin 视图。

补齐后**再跑一次同一条命令**，必须绿（全反应堆 `BUILD SUCCESS`）。

> 契约在本任务就位之后，Task 7 / 9 / 10 **新建**的测试与代码一律**按 6 个分量写**（Task 9 的
> `ApiKeyView` 直接带数值主键 `42L`，`RateLimitFilter` 直接读 `view.apiKeyId()`），不存在
> 「先写 5 个分量、以后有人回来补」这种跨任务占位。
>
> **不要**用「加一个 5 参数的便捷构造器」来减少改动：那会让「谁填了 apiKeyId」变得不可追踪，
> 而这条决策的全部价值就是让它**必须**被显式填一次。

- [ ] **Step 12: 跑本任务自述的测试与受影响模块**

```powershell
mvn -B -pl aihub-admin/aihub-common test
mvn -B -pl aihub-gateway test
$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ApiKeyMintAndResolveTest"
```

预期：`aihub-common 47`（Task 1 的 33 + 快照 12 + 契约测试 2）、`aihub-gateway 132`（**M2 基线，不回归**）、
`ApiKeyMintAndResolveTest` 绿。若它报「`api_key_id` 为 null」，检查 `mint` 是否也补了 `entity.getId()`
（MyBatis-Plus 只在 insert 后回填自增主键，漏一处就只有「新铸的 key」那一条路径是 null）。

- [ ] **Step 13: 提交**

```powershell
git add aihub-admin/aihub-common/src/main/java/com/aihub/common/config aihub-admin/aihub-common/src/test/java/com/aihub/common/config aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey aihub-admin/aihub-common/src/test/java/com/aihub/common/apikey aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyService.java aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java aihub-gateway/src/test/java/com/aihub/gateway/auth/ApiKeyAuthFilterTest.java aihub-gateway/src/test/java/com/aihub/gateway/auth/ApiKeyFilterContractTest.java aihub-gateway/src/test/java/com/aihub/gateway/meter/RelayMeteringTest.java aihub-gateway/src/test/java/com/aihub/gateway/relay/RelayMeteringFlowTest.java
git commit -m "feat: add the shared config snapshot contract and carry the numeric api key id"
```

**验收标准**
1. 四个 record 与 V1 的列一一对应；`ChannelDescriptor.usable()` 是「渠道能不能用」的唯一判据。
2. `routesFor(model)` 只返回该模型且 ACTIVE 的路由（**route 级 weight/priority 的来源**）；`channelsSupporting(model)` 的候选只来自「路由 + 可用渠道」的联表，同一渠道不重复，`status != ACTIVE` 的行全部被排除。
3. 编解码严格互逆（含字段里出现 `|` / `\` / `\n` / `\r`），载荷不含裸的 `\r`；畸形载荷（含未知格式版本、字段数不对）返回 `null`，未知**段字母**只跳过该段。
4. `tenantPolicies(tenantId)` 只返回租户级策略、`keyPolicies(tenantId, apiKeyId)` 只返回该 key 的 key 级策略（决策 7 修订后两个维度都生效，各自有取值入口且互不串味）。
5. main 作用域仍然零第三方依赖。
6. **决策 14 的契约在本任务内完整落地**：`ApiKeyView` 是 6 个分量（`Long apiKeyId` 在最后，可空）；`ApiKeyCacheCodec` 是 6 段，**旧的 5 段载荷被判为畸形并返回 `null`**（缓存收敛，不是故障）；固定向量与段数用例同步更新。
7. **契约的生产者/消费者与契约同一个提交**：admin `ApiKeyService` 的 `mint` / `loadFromDb` 填 `api_key.id`，gateway `AdminClient.parse` 透传 `apiKeyId`；`ApiKeyMintAndResolveTest` 证明 `resolve` 返回的视图里 `apiKeyId == api_key.id`。
8. **整个反应堆编译通过**：`mvn -B -q test-compile -DskipTests` 绿，仓库里**没有**任何「5 个分量的 `ApiKeyView`」残留，也没有任何「先传 `null`、以后补」的占位或待办注释（后续任务一律直接使用真值）。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-admin/aihub-common test "-Dtest=ConfigSnapshotCodecTest"` | `Tests run: 12, Failures: 0, Errors: 0` + `BUILD SUCCESS` |
| `mvn -B -pl aihub-admin/aihub-common test "-Dtest=ApiKeyToolingTest"` | `Tests run: 15, Failures: 0, Errors: 0` + `BUILD SUCCESS` |
| `mvn -B -q test-compile -DskipTests`（整个反应堆） | `BUILD SUCCESS`（**没有** `constructor ApiKeyView cannot be applied to given types`） |
| `mvn -B -pl aihub-admin/aihub-common test` | `Tests run: 47, Failures: 0, Errors: 0` + `BUILD SUCCESS` |
| `mvn -B -pl aihub-gateway test` | `Tests run: 132, Failures: 0, Errors: 0` + `BUILD SUCCESS`（M2 基线，不回归） |
| `$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ApiKeyMintAndResolveTest"` | `Failures: 0, Errors: 0` + `BUILD SUCCESS` |

---

## Task 3: 令牌桶的 Lua 脚本、纯算术与本机降级实现

**Files:**
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/ratelimit/RateLimitScript.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimitDecision.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/TokenBucket.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/LuaTokenBucket.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/LocalRateLimiter.java`
- Test: `aihub-admin/aihub-common/src/test/java/com/aihub/common/ratelimit/RateLimitScriptTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/TokenBucketTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/LuaTokenBucketTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/LocalRateLimiterTest.java`

**Interfaces:**
- Consumes: 无（本任务全是新类）。
- Produces：
  - **`com.aihub.common.ratelimit.RateLimitScript`（`aihub-common`，JDK-only）**：常量 `SCRIPT`、`KEY_PREFIX = "aihub:ratelimit:"`、`FIELD_TOKENS = "t"`、`FIELD_LAST_REFILL = "k"`、`static long idleTtlMillis(int qps, int burst)`。**脚本与键布局的唯一真相在这里**：gateway 要跑它，而 admin 侧的集成测试（Task 14，需要 Docker，因此不能放在 gateway）也要验它 —— 与 `MeteringTopology` 放在共享模块的理由一致，而且它只是 JDK 字符串，不破坏零依赖。
  - `record RateLimitDecision(boolean allowed, int remaining, long retryAfterMs, int limit, int burst, Source source)`，`enum Source { REDIS, LOCAL }`；静态 `allowed(int remaining, int limit, int burst, Source)` / `denied(long retryAfterMs, int limit, int burst, Source)`；`boolean degraded()`。
  - `record TokenBucket.State(long tokensMilli, long lastRefillMillis)`。
  - `TokenBucket`：`static RateLimitDecision tryConsume(State, long nowMillis, int qps, int burst)`（**纯函数**）、`static State nextState(State, long, int, int, RateLimitDecision)`、常量 `MILLI = 1000L`。
  - `LuaTokenBucket`（gateway 侧的**纯委托**）：`SCRIPT` / `KEY_PREFIX` / `FIELD_TOKENS` / `FIELD_LAST_REFILL` 全部来自 `RateLimitScript`；`static long idleTtlMillis(int, int)` 转发。
  - `LocalRateLimiter`：构造器 `LocalRateLimiter(int maxBuckets, LongSupplier clockMillis)`；`RateLimitDecision tryConsume(String key, int qps, int burst)`；`int trackedBuckets()`；`void clear()`；常量 `KEY_PREFIX = "local:ratelimit:"`。

**统一公式（三处实现必须逐字一致：Lua、纯算术、本机桶）**

```
capacity   = max(burst, 1)
rate       = max(qps, 0)
tokens    += max(0, now - lastRefill) * rate         // 单位：毫令牌
tokens     = min(capacity * 1000, tokens)
if tokens >= 1000: 放行; tokens -= 1000; remaining = min(burst, tokens / 1000)
else:              拒绝; retryAfterMs = rate > 0 ? ceil((1000 - tokens) / rate) : 3_600_000
写入 tokens 与 now
```

**测试用例清单**（23 条）：

| 类 | 用例 | 钉住什么 |
|---|---|---|
| `RateLimitScriptTest`（1） | `scriptAndKeyLayoutAreThePinnedContract` | 脚本必须含 `HGET`/`HSET`/`PEXPIRE`/`KEYS[1]`/`ARGV[1..4]`，不含 `TIME`/`pcall`；键前缀与两个 Hash 字段名 |
| `TokenBucketTest`（8） | `freshBucketStartsFull` / `consumesOneTokenPerRequest` / `refillsAtTheConfiguredQps` / `neverExceedsBurst` / `deniesWhenTokensAreExhausted` / `retryAfterIsTheTimeForOneToken` / `clockRollbackDoesNotCreateTokensFromTheFuture` / `nextStateNeverGoesNegative` | 纯算术语义（Lua 与本机桶都必须与它一致） |
| `LuaTokenBucketTest`（6） | `scriptIsASingleAtomicServerSideProgram` / `scriptUsesOnlyThePassedClockAndNeverRedisTime` / `scriptReadsKeysAndArgsAtThePinnedPositions` / `scriptReturnsAllowedRemainingAndRetryAfter` / `keyPrefixIsThePinnedLayout` / `idleTtlIsBoundedBelowAndAbove` | 网关侧看到的脚本与键布局与共享常量**逐字节一致** |
| `LocalRateLimiterTest`（8） | `allowsUpToBurstThenDenies` / `refillsAfterTheWindow` / `differentKeysHaveIndependentBuckets` / `refusesToExceedBurstUnderConcurrency` / `evictsIdleBucketsAtTheCap` / `zeroQpsDeniesEverything` / `nonPositiveBurstIsTreatedAsOne` / `clearResetsAllBuckets` | 降级桶的正确性与有界性 |

- [ ] **Step 1: 写 `RateLimitScript`（`aihub-common`）与它的固定向量测试**

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/ratelimit/RateLimitScript.java`：

```java
package com.aihub.common.ratelimit;

/**
 * Redis + Lua 令牌桶的**脚本与键布局**，放在共享模块里，理由有两条：
 * <ol>
 *   <li>它是**跨模块契约**：gateway 要跑这段脚本，而 admin 侧的集成测试（需要 Docker，
 *       因此不能放在 gateway 模块）要验它；两侧必须逐字节同一份，否则「测试验过的脚本」
 *       与「线上跑的脚本」会漂移。与 {@code MeteringTopology} 放在共享模块的理由相同。</li>
 *   <li>它只由 JDK 的字符串常量组成，**不破坏 main 作用域零依赖**。</li>
 * </ol>
 *
 * <p>字段名 {@code t}（令牌毫数）与 {@code k}（上次补充的毫秒时间戳）也是契约的一部分：
 * 运维用 {@code HGETALL} 巡检桶时必须认识它们，测试也用它们断言 TTL。
 *
 * <p><b>为什么必须是 Lua</b>（设计文档 §11 深挖清单第 2 题）：令牌桶的「补充 → 判定 → 扣减」是
 * 三步读改写。若由客户端分三次调用 Redis，并发下两个请求会读到同一个令牌数、各自判定成功，
 * 于是 burst 被击穿（经典的超发）。Lua 脚本在 Redis 里**单线程原子执行**，三步之间不可能插入
 * 别的请求，因此判定与扣减是同一个原子操作。Task 14 用「20 线程 × 20 次并发、恰好放行 burst 次」
 * 在真 Redis 上证明这一点。
 *
 * <p><b>为什么不用 Redis 的 {@code TIME}</b>（计划决策 9）：时间由 {@code ARGV[1]} 传入。
 * 用 {@code TIME} 会把「网关的处理时刻」与「桶的判定时刻」拆成两个时钟，排查限流问题时无法把一次
 * 拒绝对应到网关日志里的时间戳。代价是时钟回拨会重置桶（放宽而不是收紧），与纯算术一致。
 */
public final class RateLimitScript {

    /** 桶键前缀：完整键是 {@code aihub:ratelimit:{tenantId}:{sha256(secret)}}（计划决策 8）。 */
    public static final String KEY_PREFIX = "aihub:ratelimit:";

    public static final String FIELD_TOKENS = "t";
    public static final String FIELD_LAST_REFILL = "k";

    private static final long MIN_IDLE_TTL_MILLIS = 60_000L;

    /**
     * 一次往返、服务器端原子的令牌桶。**这段字符串是唯一真相**：gateway 的 {@code LuaTokenBucket}
     * 直接委托它，admin 侧的集成测试也读它。任何改动都必须先过 {@code RateLimitScriptTest}。
     *
     * <p>ARGV = {nowMillis, qps, burst, ttlMillis}；返回值 = {allowed, remaining, retryAfterMillis}。
     * 所有算术都用整数毫令牌（与 {@code TokenBucket} 逐字一致）：Lua 只有 double，Java 这个公式用
     * 整数才能保证「降级前后行为完全相同」。
     */
    public static final String SCRIPT = """
            local tokens = tonumber(redis.call('HGET', KEYS[1], 't') or '0')
            local lastRefill = tonumber(redis.call('HGET', KEYS[1], 'k') or '0')
            local now = tonumber(ARGV[1])
            local qps = tonumber(ARGV[2])
            local burst = tonumber(ARGV[3])
            if burst < 1 then burst = 1 end
            if qps < 0 then qps = 0 end
            local capacity = burst * 1000
            local elapsed = now - lastRefill
            if elapsed > 0 and qps > 0 then
              tokens = math.min(capacity, tokens + elapsed * qps)
            end
            if elapsed < 0 then
              lastRefill = now
            end
            local allowed = 0
            local remaining = 0
            local retryAfter = 0
            if tokens >= 1000 then
              tokens = tokens - 1000
              allowed = 1
              remaining = math.floor(tokens / 1000)
              if remaining > burst then remaining = burst end
            else
              local missing = 1000 - tokens
              if qps > 0 then
                retryAfter = math.ceil(missing / qps)
              else
                retryAfter = 3600000
              end
              if retryAfter < 1 then retryAfter = 1 end
            end
            redis.call('HSET', KEYS[1], 't', tokens, 'k', now)
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return {allowed, remaining, retryAfter}
            """;

    private RateLimitScript() {
    }

    /**
     * 空闲 TTL：至少 1 分钟；正常取「把满桶放空所需时间」的 20 倍。
     * 太小 → 桶频繁被重建，限流会被打穿；太大 → 键不回收。
     */
    public static long idleTtlMillis(int qps, int burst) {
        int capacity = Math.max(burst, 1);
        int rate = Math.max(qps, 1);
        long timeToDrain = (long) capacity * 1000L / rate;
        return Math.max(MIN_IDLE_TTL_MILLIS, timeToDrain * 20L);
    }
}
```

创建 `aihub-admin/aihub-common/src/test/java/com/aihub/common/ratelimit/RateLimitScriptTest.java`：

```java
package com.aihub.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脚本与键布局是**跨模块契约**（gateway 跑它、admin 的集成测试验它），因此把字面量与
 * 必须存在的调用点钉在这里。任何一侧「顺手改一下脚本」都会先在这里变红。
 */
class RateLimitScriptTest {

    @Test
    void scriptAndKeyLayoutAreThePinnedContract() {
        assertThat(RateLimitScript.KEY_PREFIX).isEqualTo("aihub:ratelimit:");
        assertThat(RateLimitScript.FIELD_TOKENS).isEqualTo("t");
        assertThat(RateLimitScript.FIELD_LAST_REFILL).isEqualTo("k");

        // 一次往返、服务器端原子：读改写三步都在同一个脚本里，参数位置固定。
        for (String required : List.of("HGET", "HSET", "PEXPIRE", "KEYS[1]", "ARGV[1]", "ARGV[2]", "ARGV[3]",
                "ARGV[4]", "return {")) {
            assertThat(RateLimitScript.SCRIPT).as("脚本必须包含 %s", required).contains(required);
        }
        assertThat(RateLimitScript.SCRIPT).as("时间必须来自调用方，不能用 Redis 的 TIME")
                .doesNotContain("'TIME'").doesNotContain("\"TIME\"");
        // 不允许出现「客户端分步调用」的痕迹：脚本里不该有 pcall 包住的多次读改写。
        assertThat(RateLimitScript.SCRIPT).doesNotContain("pcall");

        // TTL 公式的数字写死在这里：任何改动（含把下限从 60 秒挪走）都必须先过这一关。
        assertThat(RateLimitScript.idleTtlMillis(10, 20)).isEqualTo(60_000L);      // 2000*20=40_000 → 下限
        assertThat(RateLimitScript.idleTtlMillis(1, 1)).isEqualTo(60_000L);        // 1000*20=20_000 → 下限
        assertThat(RateLimitScript.idleTtlMillis(1, 100)).isEqualTo(2_000_000L);   // 100_000*20
        assertThat(RateLimitScript.idleTtlMillis(0, 0)).isEqualTo(60_000L);        // 除零保护后 → 下限
    }
}
```

- [ ] **Step 2: 运行，确认全绿**

```powershell
mvn -B -pl aihub-admin/aihub-common test "-Dtest=RateLimitScriptTest"
```

预期：`Tests run: 1, Failures: 0, Errors: 0`。

- [ ] **Step 3: 写 `TokenBucketTest` 与 `RateLimitDecision` / `TokenBucket`**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/TokenBucketTest.java`：

```java
package com.aihub.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌桶的**纯算术**是「Redis Lua 实现」与「Redis 挂了时的本机实现」共享的单一真相：
 * 两边的补充速率、封顶、拒绝时的退避时间都必须逐字一致，否则降级前后客户端会看到两种行为。
 * 因此这里把语义钉死，Redis 侧只负责「原子地调用它」。
 *
 * <p>令牌数用**千分之一**的整数（{@code tokensMilli}）存：浮点在 Lua 与 Java 两侧的取整规则
 * 不完全一致，整数毫令牌让「同一状态、同一时刻」在两侧得到完全相同的判定。
 */
class TokenBucketTest {

    @Test
    void freshBucketStartsFull() {
        // 新桶 = 空状态（令牌 0、时间戳 0）→ 立即按 qps 补满到 burst。
        RateLimitDecision decision = TokenBucket.tryConsume(new TokenBucket.State(0L, 0L), 10_000L, 10, 20);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).as("补满 20 个、用掉 1 个 → 余 19").isEqualTo(19);
        assertThat(decision.limit()).isEqualTo(10);
        assertThat(decision.burst()).isEqualTo(20);
        assertThat(decision.source()).isEqualTo(RateLimitDecision.Source.REDIS);
    }

    @Test
    void consumesOneTokenPerRequest() {
        TokenBucket.State state = new TokenBucket.State(5_000L, 1_000L);

        // 同一毫秒内连续消费：不允许补充，令牌逐个减少。
        assertThat(TokenBucket.tryConsume(state, 1_000L, 10, 20).remaining()).isEqualTo(4);
        assertThat(TokenBucket.tryConsume(state, 1_000L, 10, 20).remaining()).isEqualTo(3);
    }

    @Test
    void refillsAtTheConfiguredQps() {
        // qps=10 → 每 100ms 补 1 个。
        TokenBucket.State empty = new TokenBucket.State(0L, 1_000L);

        RateLimitDecision afterOneHundredMillis = TokenBucket.tryConsume(empty, 1_100L, 10, 20);

        assertThat(afterOneHundredMillis.allowed()).isTrue();
        assertThat(afterOneHundredMillis.remaining()).isZero();
    }

    @Test
    void neverExceedsBurst() {
        TokenBucket.State state = new TokenBucket.State(5_000L, 1_000L);

        // 过了一小时也只补到 burst（20），不是无限的。
        assertThat(TokenBucket.tryConsume(state, 3_601_000L, 10, 20).remaining()).isEqualTo(19);
    }

    @Test
    void deniesWhenTokensAreExhausted() {
        RateLimitDecision decision = TokenBucket.tryConsume(new TokenBucket.State(0L, 1_000L), 1_000L, 10, 20);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
    }

    /**
     * 被拒时 `Retry-After` 必须是「再攒出一个令牌需要多久」：
     * {@code ceil((1000 - tokensMilli) / qps)} 毫秒。qps=10、桶空 → 100ms。
     * 这条公式会被网关直接写进响应的 {@code Retry-After} 头与错误体，客户端据此退避。
     */
    @Test
    void retryAfterIsTheTimeForOneToken() {
        assertThat(TokenBucket.tryConsume(new TokenBucket.State(0L, 1_000L), 1_000L, 10, 20).retryAfterMs())
                .isEqualTo(100L);
        // qps=1 → 一秒一个令牌。
        assertThat(TokenBucket.tryConsume(new TokenBucket.State(0L, 1_000L), 1_000L, 1, 2).retryAfterMs())
                .isEqualTo(1_000L);
        // 半个令牌：还需 500 毫令牌 → ceil(500/10)=50ms。
        assertThat(TokenBucket.tryConsume(new TokenBucket.State(500L, 1_000L), 1_000L, 10, 20).retryAfterMs())
                .isEqualTo(50L);
    }

    /** 时钟回拨（NTP 校正、容器迁移）不能让桶凭空多出令牌，也不能把它算成负数。 */
    @Test
    void clockRollbackDoesNotCreateTokensFromTheFuture() {
        TokenBucket.State state = new TokenBucket.State(3_000L, 5_000L);

        RateLimitDecision decision = TokenBucket.tryConsume(state, 1_000L, 10, 20);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(2);
    }

    @Test
    void nextStateNeverGoesNegative() {
        TokenBucket.State state = new TokenBucket.State(0L, 1_000L);
        RateLimitDecision decision = TokenBucket.tryConsume(state, 1_000L, 0, 1);

        TokenBucket.State next = TokenBucket.nextState(state, 1_000L, 0, 1, decision);

        assertThat(next.tokensMilli()).isZero();
        assertThat(next.lastRefillMillis()).isEqualTo(1_000L);
        // 拒绝时时间戳同样推进：否则下一次补充会把已经算过的时间重复补一遍。
        assertThat(TokenBucket.nextState(new TokenBucket.State(0L, 0L), 100L, 10, 1,
                RateLimitDecision.denied(100L, 10, 1, RateLimitDecision.Source.REDIS)).tokensMilli())
                .isEqualTo(1_000L);
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimitDecision.java`：

```java
package com.aihub.gateway.ratelimit;

/**
 * 一次限流判定的结果。{@code remaining} / {@code limit} / {@code burst} 直接映射到响应头
 * （IETF {@code RateLimit-*}；网关自己回给客户端的是 IETF 那一族，见 Task 9）。
 *
 * @param allowed      是否放行
 * @param remaining    本窗口剩余请求数（放行后）
 * @param retryAfterMs 被拒时的建议退避毫秒数（放行时为 0）
 * @param limit        本策略的 qps
 * @param burst        本策略的突发上限
 * @param source       {@code REDIS}（正常）或 {@code LOCAL}（Redis 降级）
 */
public record RateLimitDecision(boolean allowed, int remaining, long retryAfterMs, int limit, int burst, Source source) {

    /** 判定发生在哪一级 —— 也是「Redis 是否降级」的唯一可观测信号（指标只按它打标签）。 */
    public enum Source {
        REDIS,
        LOCAL
    }

    public static RateLimitDecision allowed(int remaining, int limit, int burst, Source source) {
        return new RateLimitDecision(true, remaining, 0L, limit, burst, source);
    }

    public static RateLimitDecision denied(long retryAfterMs, int limit, int burst, Source source) {
        return new RateLimitDecision(false, 0, retryAfterMs, limit, burst, source);
    }

    /** 本次判定是否走了降级路径（Redis 不可用）。 */
    public boolean degraded() {
        return source == Source.LOCAL;
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/TokenBucket.java`：

```java
package com.aihub.gateway.ratelimit;

/**
 * 令牌桶的**纯算术**。Redis Lua 脚本（{@link RateLimitScript} 里的 {@code SCRIPT}）与 Redis 不可用
 * 时的本机实现（{@link LocalRateLimiter}）都遵守它，因此「补充速率 / 封顶 / 退避时间」在降级前后
 * **逐字一致** —— 这是设计文档 §9「限流降级为本地令牌桶（单机近似）」能被称为「近似」而不是
 * 「另一种算法」的前提。
 *
 * <p><b>为什么用整数毫令牌</b>：Lua 的数字只有 double，Java 侧如果用 {@code double} 累加，
 * 长时间运行后两侧的取整会有偏差，导致「Redis 通的时候拒绝、断了之后放行」这类只在切换瞬间
 * 出现的怪现象。用千分之一的整数（{@code tokensMilli}）后两侧都是整数运算，结果完全可复现。
 *
 * <p><b>时间由调用方传入</b>（不用 Redis 的 {@code TIME}，计划决策 9）：一次拒绝必须能对应到网关
 * 日志里的时间戳，否则排查限流问题时无从下手。代价是时钟回拨会重置桶（放宽而不是收紧）。
 */
public final class TokenBucket {

    /** 一个令牌的千分之一表示。 */
    public static final long MILLI = 1000L;

    private TokenBucket() {
    }

    /**
     * 桶的状态：{@code tokensMilli} 是**当前令牌数 × 1000**，{@code lastRefillMillis} 是上次补充时刻。
     * 新桶用 {@code (0, 0)} 表示，第一次调用就会被补满到 burst。
     */
    public record State(long tokensMilli, long lastRefillMillis) {
    }

    /**
     * 尝试消费一个令牌。**纯函数**：不修改传入的 state，也不读任何全局状态。
     *
     * <p>时钟回拨（{@code now < lastRefill}）时**不补充**也不报错，只把时间戳前移 —— 也就是说回拨
     * 期间桶只减不增。这比「按负的 elapsed 扣令牌」安全：后者会让桶瞬间变成负数，随后需要一个
     * 很长的窗口才能恢复到能放行。
     *
     * @param state     当前状态（{@code null} 视为空桶）
     * @param nowMillis 调用方的当前毫秒时间戳
     * @param qps       每秒补充的令牌数（非正数视为「永不补充」→ 只允许 burst 次突发）
     * @param burst     桶容量（非正数视为 1）
     */
    public static RateLimitDecision tryConsume(State state, long nowMillis, int qps, int burst) {
        int capacity = Math.max(burst, 1);
        int rate = Math.max(qps, 0);
        long tokens = state == null ? 0L : state.tokensMilli();
        long lastRefill = state == null ? 0L : state.lastRefillMillis();

        long elapsed = nowMillis - lastRefill;
        if (elapsed > 0 && rate > 0) {
            tokens = Math.min((long) capacity * MILLI, tokens + elapsed * rate);
        }
        if (elapsed < 0) {
            // 时钟回拨：把基准时间前移，且不补充令牌。
            lastRefill = nowMillis;
        }

        if (tokens >= MILLI) {
            long remainingTokens = tokens - MILLI;
            return RateLimitDecision.allowed((int) Math.min(capacity, remainingTokens / MILLI), rate, capacity,
                    RateLimitDecision.Source.REDIS);
        }

        long missingTokensMilli = MILLI - tokens;
        // qps=0：永远补不出下一个令牌。给一个明确的上界而不是 Long.MAX_VALUE，
        // 免得被写进 Retry-After 头时变成一个荒唐的数字。
        long retryAfterMs = rate == 0 ? 3_600_000L : Math.max(1L, ceilDiv(missingTokensMilli, rate));
        return RateLimitDecision.denied(retryAfterMs, rate, capacity, RateLimitDecision.Source.REDIS);
    }

    /** 判定之后应该写回的状态（放行与拒绝都要写回：拒绝时时间戳同样推进，否则补充会被重复计算）。 */
    public static State nextState(State state, long nowMillis, int qps, int burst, RateLimitDecision decision) {
        int capacity = Math.max(burst, 1);
        int rate = Math.max(qps, 0);
        long tokens = state == null ? 0L : state.tokensMilli();
        long lastRefill = state == null ? 0L : state.lastRefillMillis();
        long elapsed = nowMillis - lastRefill;
        if (elapsed > 0 && rate > 0) {
            tokens = Math.min((long) capacity * MILLI, tokens + elapsed * rate);
        }
        if (decision != null && decision.allowed()) {
            tokens = Math.max(0L, tokens - MILLI);
        }
        return new State(tokens, nowMillis);
    }

    private static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
```

- [ ] **Step 4: 运行 `TokenBucketTest`**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=TokenBucketTest"
```

预期：`Tests run: 8, Failures: 0, Errors: 0`。

- [ ] **Step 5: 写 `LuaTokenBucketTest` 与 `LuaTokenBucket`（纯委托）**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/LuaTokenBucketTest.java`：

```java
package com.aihub.gateway.ratelimit;

import com.aihub.common.ratelimit.RateLimitScript;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关侧看到的脚本与键布局必须与共享模块里的那一份**逐字节一致**。这里的断言主要是
 * 「委托没被写歪」：任何一次「顺手在 gateway 里改一下脚本」都会先在这里变红。
 *
 * <p>脚本的**行为**证据在 admin 侧的真 Redis 集成测试里（{@code RedisTokenBucketIntegrationTest}）：
 * 网关测试不允许依赖 Docker，所以本类只钉结构与约定，不假装跑过 Redis。
 */
class LuaTokenBucketTest {

    @Test
    void scriptIsASingleAtomicServerSideProgram() {
        assertThat(LuaTokenBucket.SCRIPT).isSameAs(RateLimitScript.SCRIPT);
        assertThat(LuaTokenBucket.SCRIPT).contains("redis.call('HGET'").contains("redis.call('HSET'");
        assertThat(LuaTokenBucket.SCRIPT).contains("return {");
        assertThat(LuaTokenBucket.SCRIPT).doesNotContain("pcall");
    }

    /**
     * 时间**必须**来自 ARGV，不许用 Redis 的 {@code TIME}：否则一次拒绝的判定时刻与网关日志里的
     * 时刻来自两个时钟，排查限流问题时无法对齐（决策 9）。
     */
    @Test
    void scriptUsesOnlyThePassedClockAndNeverRedisTime() {
        assertThat(LuaTokenBucket.SCRIPT).contains("ARGV[1]");
        assertThat(LuaTokenBucket.SCRIPT).doesNotContain("'TIME'").doesNotContain("\"TIME\"");
    }

    @Test
    void scriptReadsKeysAndArgsAtThePinnedPositions() {
        assertThat(LuaTokenBucket.SCRIPT).contains("KEYS[1]");
        assertThat(LuaTokenBucket.SCRIPT).contains("tonumber(ARGV[1])");
        assertThat(LuaTokenBucket.SCRIPT).contains("tonumber(ARGV[2])");
        assertThat(LuaTokenBucket.SCRIPT).contains("tonumber(ARGV[3])");
        assertThat(LuaTokenBucket.SCRIPT).contains("ARGV[4]");
        assertThat(LuaTokenBucket.FIELD_TOKENS).isEqualTo("t");
        assertThat(LuaTokenBucket.FIELD_LAST_REFILL).isEqualTo("k");
    }

    @Test
    void scriptReturnsAllowedRemainingAndRetryAfter() {
        // 三个返回值，顺序固定：allowed / remaining / retryAfterMillis。
        assertThat(LuaTokenBucket.SCRIPT).contains("return {allowed, remaining, retryAfter}");
    }

    @Test
    void keyPrefixIsThePinnedLayout() {
        assertThat(LuaTokenBucket.KEY_PREFIX).isEqualTo("aihub:ratelimit:");
        assertThat(LocalRateLimiter.KEY_PREFIX).as("本机桶必须与 Redis 布局分开，避免混淆两种存储")
                .isEqualTo("local:ratelimit:");
    }

    /** 空闲 TTL 要有下界与上界：太小会让桶频繁重建（限流被打穿），太大则键永不回收。 */
    @Test
    void idleTtlIsBoundedBelowAndAbove() {
        assertThat(LuaTokenBucket.idleTtlMillis(10, 20)).isEqualTo(60_000L);
        assertThat(LuaTokenBucket.idleTtlMillis(1, 1)).isEqualTo(60_000L);
        assertThat(LuaTokenBucket.idleTtlMillis(1, 100)).isEqualTo(2_000_000L);
        assertThat(LuaTokenBucket.idleTtlMillis(0, 0)).isEqualTo(60_000L);
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/LuaTokenBucket.java`：

```java
package com.aihub.gateway.ratelimit;

import com.aihub.common.ratelimit.RateLimitScript;

/**
 * 网关侧的令牌桶脚本入口。**本类不含任何实现**：脚本与键布局的唯一真相在
 * {@link RateLimitScript}（`aihub-common`），理由是 admin 侧的集成测试也要验这段脚本，
 * 而 gateway 的测试不允许依赖 Docker。委托保持公开常量名不变，因此生产代码与测试都只认这一个名字。
 */
public final class LuaTokenBucket {

    /** 桶键前缀：完整键是 {@code aihub:ratelimit:{tenantId}:{sha256(secret)}}（决策 8）。 */
    public static final String KEY_PREFIX = RateLimitScript.KEY_PREFIX;

    public static final String FIELD_TOKENS = RateLimitScript.FIELD_TOKENS;
    public static final String FIELD_LAST_REFILL = RateLimitScript.FIELD_LAST_REFILL;

    public static final String SCRIPT = RateLimitScript.SCRIPT;

    private LuaTokenBucket() {
    }

    /** 空闲 TTL：至少 1 分钟；正常取「把满桶放空所需时间」的 20 倍。 */
    public static long idleTtlMillis(int qps, int burst) {
        return RateLimitScript.idleTtlMillis(qps, burst);
    }
}
```

> `assertThat(LuaTokenBucket.SCRIPT).isSameAs(RateLimitScript.SCRIPT)` 断言的是「同一个字符串常量」，
> 这是委托有没有被绕开的最直接证据。若实施者改用 `equals` 也可以（不算偏离）。

- [ ] **Step 6: 写 `LocalRateLimiterTest` 与 `LocalRateLimiter`**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/LocalRateLimiterTest.java`：

```java
package com.aihub.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本机令牌桶是 **Redis 不可用时的降级路径**（设计文档 §9：绝不因为控制面故障而阻断数据面）。
 * 它的语义必须与 Redis 侧一致（共用 {@link TokenBucket}），并且**有界**（Caffeine 上限），
 * 否则一个恶意客户端可以用无数个 key 把网关内存撑爆 —— 降级反而变成攻击面。
 *
 * <p>时钟由构造器注入：所有判定都不依赖真实时间，因此没有 sleep、没有抖动。
 */
class LocalRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    private LocalRateLimiter limiter(int maxBuckets) {
        return new LocalRateLimiter(maxBuckets, clock::get);
    }

    @Test
    void allowsUpToBurstThenDenies() {
        LocalRateLimiter limiter = limiter(100);

        // qps=0：不补充，只能消费 burst 次。
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("k", 0, 5).allowed()).as("第 %s 次突发", i + 1).isTrue();
        }
        RateLimitDecision denied = limiter.tryConsume("k", 0, 5);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.source()).isEqualTo(RateLimitDecision.Source.LOCAL);
        assertThat(denied.degraded()).isTrue();
    }

    @Test
    void refillsAfterTheWindow() {
        LocalRateLimiter limiter = limiter(100);
        limiter.tryConsume("k", 10, 1);
        assertThat(limiter.tryConsume("k", 10, 1).allowed()).isFalse();

        clock.addAndGet(100L);   // qps=10 → 100ms 补一个

        assertThat(limiter.tryConsume("k", 10, 1).allowed()).isTrue();
    }

    @Test
    void differentKeysHaveIndependentBuckets() {
        LocalRateLimiter limiter = limiter(100);
        limiter.tryConsume("a", 0, 1);

        assertThat(limiter.tryConsume("a", 0, 1).allowed()).as("a 已耗尽").isFalse();
        assertThat(limiter.tryConsume("b", 0, 1).allowed()).as("b 不受 a 影响").isTrue();
    }

    /**
     * **降级路径也绝不能超发**。Caffeine 的 {@code asMap().compute} 是「单键原子」的，
     * 因此同一 key 的并发请求在桶层面是串行的；把它换成「get → 判定 → put」会让这条变红。
     */
    @Test
    void refusesToExceedBurstUnderConcurrency() throws Exception {
        LocalRateLimiter limiter = limiter(100);
        int threads = 16;
        int attemptsPerThread = 50;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < attemptsPerThread; j++) {
                    if (limiter.tryConsume("hot", 0, 7).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        }

        // qps=0 → 总共只能放行 burst=7 次，无论多少线程并发。
        assertThat(allowed.get()).isEqualTo(7);
    }

    @Test
    void evictsIdleBucketsAtTheCap() {
        LocalRateLimiter limiter = limiter(10);

        for (int i = 0; i < 200; i++) {
            limiter.tryConsume("key-" + i, 10, 5);
        }

        assertThat(limiter.trackedBuckets()).isLessThanOrEqualTo(10);
    }

    @Test
    void zeroQpsDeniesEverything() {
        LocalRateLimiter limiter = limiter(100);

        assertThat(limiter.tryConsume("k", 0, 1).allowed()).isTrue();
        RateLimitDecision denied = limiter.tryConsume("k", 0, 1);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).as("qps=0 时退避时间有明确上界").isEqualTo(3_600_000L);
    }

    @Test
    void nonPositiveBurstIsTreatedAsOne() {
        LocalRateLimiter limiter = limiter(100);

        assertThat(limiter.tryConsume("k", 0, 0).allowed()).isTrue();
        assertThat(limiter.tryConsume("k", 0, 0).allowed()).isFalse();
    }

    @Test
    void clearResetsAllBuckets() {
        LocalRateLimiter limiter = limiter(100);
        limiter.tryConsume("k", 0, 1);

        limiter.clear();

        assertThat(limiter.trackedBuckets()).isZero();
        assertThat(limiter.tryConsume("k", 0, 1).allowed()).isTrue();
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/LocalRateLimiter.java`：

```java
package com.aihub.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.function.LongSupplier;

/**
 * Redis 不可用时的**本机令牌桶降级实现**（设计文档 §9：「限流降级为本地令牌桶（单机近似）」）。
 *
 * <p>它的语义与 Redis 版**完全一致** —— 两边都遵守 {@link TokenBucket} 的纯算术，因此降级
 * 不会让客户端看到另一套限流规则。差别只在**作用域**：本机桶只看得见本进程的流量，
 * 多实例部署时实际放行量会接近「策略 × 实例数」。这是「单机近似」的准确含义，也是接受降级的
 * 代价；不接受的做法是「Redis 挂了就整体拒绝」，那违反「数据面永不因控制面故障而整体不可用」。
 *
 * <p><b>必须有界</b>：{@link Caffeine} 的 {@code maximumSize} 保证内存不会被「无数个 key」撑爆
 * （否则降级本身变成攻击面）。淘汰是**逐出即遗忘**，被逐出的桶下一次请求会以满桶重建 ——
 * 宽松方向的误差，可接受。
 *
 * <p>并发正确性来自 {@link java.util.concurrent.ConcurrentMap#compute} 的**单键原子性**：
 * 「读状态 → 判定 → 写状态」在同一个 compute 里完成，因此同一 key 的并发请求不会超发。
 */
public final class LocalRateLimiter {

    /**
     * 本机桶的键前缀。**刻意与 Redis 布局（{@code aihub:ratelimit:}）不同**：
     * 两种存储混用同一个字符串时，日志与抓包里无法判断一个 key 到底在哪一级，
     * 而且将来万一有人把本机桶写进 Redis，前缀会让这件事立刻可见。
     */
    public static final String KEY_PREFIX = "local:ratelimit:";

    private final Cache<String, TokenBucket.State> buckets;
    private final LongSupplier clockMillis;

    /**
     * @param maxBuckets  本机桶的数量上界
     * @param clockMillis 毫秒时钟（测试注入假时钟；生产用 {@code System::currentTimeMillis}）
     */
    public LocalRateLimiter(int maxBuckets, LongSupplier clockMillis) {
        this.buckets = Caffeine.newBuilder()
                .maximumSize(Math.max(maxBuckets, 1))
                .build();
        this.clockMillis = clockMillis;
    }

    /** 判定并消费。**永不抛异常**：它跑在请求路径上。 */
    public RateLimitDecision tryConsume(String key, int qps, int burst) {
        long now = clockMillis.getAsLong();
        TokenBucket.State[] holder = new TokenBucket.State[1];
        RateLimitDecision[] decisionHolder = new RateLimitDecision[1];
        buckets.asMap().compute(KEY_PREFIX + key, (ignored, state) -> {
            TokenBucket.State effective = state == null ? new TokenBucket.State(0L, 0L) : state;
            RateLimitDecision decision = TokenBucket.tryConsume(effective, now, qps, burst);
            decisionHolder[0] = decision;
            holder[0] = TokenBucket.nextState(effective, now, qps, burst, decision);
            return holder[0];
        });
        RateLimitDecision decision = decisionHolder[0];
        if (decision == null) {
            // compute 理论上必然被调用；为「永不为 null」这条契约兜底（宁可放行也不抛）。
            return RateLimitDecision.allowed(Math.max(burst, 1), qps, Math.max(burst, 1),
                    RateLimitDecision.Source.LOCAL);
        }
        return new RateLimitDecision(decision.allowed(), decision.remaining(), decision.retryAfterMs(),
                decision.limit(), decision.burst(), RateLimitDecision.Source.LOCAL);
    }

    /** 已跟踪的桶数量（测试与运维巡检用）。 */
    public int trackedBuckets() {
        buckets.cleanUp();
        return (int) buckets.estimatedSize();
    }

    /** 清空所有桶（测试用；生产没有任何调用点）。 */
    public void clear() {
        buckets.invalidateAll();
    }
}
```

- [ ] **Step 7: 运行本任务的四个测试类，确认全绿**

```powershell
mvn -B -pl aihub-admin/aihub-common test "-Dtest=RateLimitScriptTest"
mvn -B -pl aihub-gateway test -am "-Dtest=TokenBucketTest,LuaTokenBucketTest,LocalRateLimiterTest"
```

预期：`1` + `Tests run: 22, Failures: 0, Errors: 0` + `BUILD SUCCESS`。**注意逗号形式**（`A+B` 会静默跳过两个类）。

- [ ] **Step 8: 跑两个模块的全部测试（确认没破坏既有 21 / 132 项）**

```powershell
mvn -B -pl aihub-admin/aihub-common test
mvn -B -pl aihub-gateway test
```

预期：`aihub-common 48`、`aihub-gateway 154`。报告实测数字。

- [ ] **Step 9: 提交**

```powershell
git add aihub-admin/aihub-common/src/main/java/com/aihub/common/ratelimit aihub-admin/aihub-common/src/test/java/com/aihub/common/ratelimit aihub-gateway/src/main/java/com/aihub/gateway/ratelimit aihub-gateway/src/test/java/com/aihub/gateway/ratelimit
git commit -m "feat: add the token bucket lua script, its pure arithmetic and the local degrade limiter"
```

**验收标准**
1. `RateLimitScript.SCRIPT` 是脚本与键布局的唯一真相；Lua 只有一次往返（一个脚本里完成读改写）、时间来自 `ARGV[1]`、返回三元素、`ARGV[4]` 是 TTL。
2. `TokenBucket.tryConsume` 是纯函数：同样的状态 + 同样的时刻 + 同样的参数 → 同样的判定；补充按 qps、封顶到 burst、拒绝时给 `ceil((1000 - tokensMilli) / qps)` 毫秒退避；`nextState` 在拒绝时也推进时间戳且不会为负。
3. `LuaTokenBucket` 是**纯委托**（`SCRIPT` 是同一个字符串常量）；本机桶的键前缀与 Redis 键前缀不同（决策 8）。
4. `LocalRateLimiter` 与 Redis 版共用同一套算术；16 线程 × 50 次并发对同一 key 的 `burst=7` 恰好只放行 7 次；桶数量不超过 `maxBuckets`。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-admin/aihub-common test "-Dtest=RateLimitScriptTest"` | `Tests run: 1, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-gateway test -am "-Dtest=TokenBucketTest,LuaTokenBucketTest,LocalRateLimiterTest"` | `Tests run: 22, Failures: 0, Errors: 0` + `BUILD SUCCESS` |
| `mvn -B -pl aihub-admin/aihub-common test` | `Tests run: 48, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-gateway test` | `Tests run: 154, Failures: 0, Errors: 0` |

---

## Task 4: Redis 限流器、策略解析与降级门面

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RedisRateLimiter.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimitResolver.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimiter.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/RateLimitResolverTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/RedisRateLimiterTest.java`

**Interfaces:**
- Consumes：Task 3（`TokenBucket` / `LuaTokenBucket` / `LocalRateLimiter` / `RateLimitDecision`）、Task 2（`ConfigSnapshot` / `RatePolicy`）。
- Produces：
  - `RedisRateLimiter`：构造器 `RedisRateLimiter(StringRedisTemplate redis)`；`RateLimitDecision tryConsume(String bucketKey, int qps, int burst)`（**返回 `null` = 「Redis 这一级不可用」**，由 `RateLimiter` 决定降级；永不抛异常）。
  - `RateLimitResolver`：构造器 `RateLimitResolver(Supplier<ConfigSnapshot> snapshotSupplier)`；`RatePolicy resolve(long tenantId, Long apiKeyId)`（决策 7/17：**key 级优先** —— `apiKeyId != null` 时先取 `keyPolicies(tenantId, apiKeyId)` 的最后一条；没有有效的 key 级行则取 `tenantPolicies(tenantId)` 的最后一条；都没有或 qps/burst 非正则回落到 `qps=10 / burst=20`）。
  - `RateLimiter`：构造器 `RateLimiter(RedisRateLimiter redis, LocalRateLimiter local, RateLimitResolver resolver)`；`RateLimitDecision acquire(long tenantId, Long apiKeyId, String keyHash)`（生产唯一入口）；`boolean redisDegraded()`。

**测试用例清单**（`RateLimitResolverTest` 7 + `RedisRateLimiterTest` 7 = 14 条）：

| 类 | 用例 | 钉住什么 |
|---|---|---|
| `RateLimitResolverTest`（7） | `usesTheBuiltInDefaultWhenNoPolicyExists` / `prefersTheKeyLevelPolicyWhenTheApiKeyIdsMatch` / `usesTheTenantLevelPolicyWhenThereIsNoKeyLevelRow` / `ignoresPoliciesOfOtherTenants` / `usesTheLastPolicyOfTheMatchingDimensionWhenSeveralArePresent` / `treatsNonPositiveQpsAsTheDefault` / `readsTheSnapshotLazilyPerCall` | 决策 7/17 的全部语义（**两个维度都生效**） |
| `RedisRateLimiterTest`（7） | `returnsNullWhenRedisThrows` / `returnsNullWhenTheScriptResultIsNotAThreeElementList` / `passesThePinnedKeyAndArgumentsToTheScript` / `mapsTheScriptResultToADecision` / `deniedDecisionCarriesRetryAfter` / `blankKeyIsRejectedLocallyWithoutCallingRedis` / `unavailableRedisIsSignalledAsNullSoTheCallerCanDegrade` | 脚本调用约定 + 失败语义 |

- [ ] **Step 1: 写 `RateLimitResolverTest` 与 `RateLimitResolver`**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/RateLimitResolverTest.java`：

```java
package com.aihub.gateway.ratelimit;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.RatePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策略解析是「限流到底按什么数字执行」的唯一来源。**两个维度都生效**（决策 7，2026-09-26 依控制器
 * pre-flight 评审修订）：先按 {@code (tenantId, apiKeyId)} 找 key 级策略，没有有效的才回落到该租户的
 * 租户级策略，最后才是内置默认。原文那条「key 级被忽略」的用例是**错的**（数值主键由决策 14 随
 * {@code ApiKeyView} 下发、限流过滤器排在鉴权之后即可拿到），已被下面两条替换。
 */
class RateLimitResolverTest {

    private static final ConfigSnapshot SNAPSHOT = new ConfigSnapshot(1L, 2L, List.of(), List.of(), List.of(
            new RatePolicy(7L, null, 20, 40),
            new RatePolicy(7L, 42L, 100, 200),
            new RatePolicy(8L, null, 5, 10),
            new RatePolicy(null, null, 999, 999)), null);

    private static RateLimitResolver resolverOf(ConfigSnapshot snapshot) {
        return new RateLimitResolver(() -> snapshot);
    }

    @Test
    void usesTheBuiltInDefaultWhenNoPolicyExists() {
        RatePolicy policy = resolverOf(ConfigSnapshot.empty()).resolve(7L, null);

        assertThat(policy.qps()).isEqualTo(RatePolicy.DEFAULT_QPS);
        assertThat(policy.burst()).isEqualTo(RatePolicy.DEFAULT_BURST);
        assertThat(policy.tenantId()).isEqualTo(7L);
        assertThat(policy.tenantLevel()).isTrue();
    }

    /** 决策 7 的第一级：`apiKeyId` 命中时，key 级策略**赢过**同一租户的租户级策略。 */
    @Test
    void prefersTheKeyLevelPolicyWhenTheApiKeyIdsMatch() {
        RatePolicy policy = resolverOf(SNAPSHOT).resolve(7L, 42L);

        assertThat(policy.qps()).as("key 级的 100/200 必须生效").isEqualTo(100);
        assertThat(policy.burst()).isEqualTo(200);
        assertThat(policy.apiKeyId()).isEqualTo(42L);
        assertThat(policy.tenantLevel()).isFalse();
    }

    /**
     * 决策 7 的第二级（**回落**）：这个 (租户, key) 没有 key 级行时用该租户的租户级行。
     * 两种形态都要覆盖：① 该 key 根本没有策略（99L）；② 鉴权关闭、请求上下文里没有数值主键
     * （{@code null}）—— 后者是匿名桶的正常路径，绝不能因为 `apiKeyId` 为空就丢掉租户级策略。
     */
    @Test
    void usesTheTenantLevelPolicyWhenThereIsNoKeyLevelRow() {
        RatePolicy otherKey = resolverOf(SNAPSHOT).resolve(7L, 99L);
        assertThat(otherKey.qps()).isEqualTo(20);
        assertThat(otherKey.burst()).isEqualTo(40);
        assertThat(otherKey.tenantLevel()).isTrue();

        RatePolicy anonymous = resolverOf(SNAPSHOT).resolve(7L, null);
        assertThat(anonymous.qps()).as("apiKeyId 为 null 时只能走租户级").isEqualTo(20);
        assertThat(anonymous.apiKeyId()).isNull();
    }

    @Test
    void ignoresPoliciesOfOtherTenants() {
        assertThat(resolverOf(SNAPSHOT).resolve(8L, null).qps()).isEqualTo(5);
        assertThat(resolverOf(SNAPSHOT).resolve(8L, 42L).qps())
                .as("租户 8 没有 42 号 key 的 key 级策略 → 回落租户级").isEqualTo(5);
        assertThat(resolverOf(SNAPSHOT).resolve(9L, null).qps()).isEqualTo(RatePolicy.DEFAULT_QPS);
    }

    /**
     * 同维度出现多行时（V1 没有唯一约束，决策 17）：**在各自的维度内取最后一条**。
     * admin 侧组装快照时已经按 {@code id} 升序排好，因此「最后一条」就是「最后插入的那条」。
     */
    @Test
    void usesTheLastPolicyOfTheMatchingDimensionWhenSeveralArePresent() {
        ConfigSnapshot duplicated = new ConfigSnapshot(1L, 2L, List.of(), List.of(), List.of(
                new RatePolicy(7L, null, 20, 40),
                new RatePolicy(7L, null, 60, 80),
                new RatePolicy(7L, 42L, 100, 200),
                new RatePolicy(7L, 42L, 300, 400)), null);

        assertThat(resolverOf(duplicated).resolve(7L, null).qps())
                .as("租户级多条取最后一条").isEqualTo(60);
        assertThat(resolverOf(duplicated).resolve(7L, 42L).qps())
                .as("key 级同键多条同样取最后一条").isEqualTo(300);
    }

    @Test
    void treatsNonPositiveQpsAsTheDefault() {
        // qps=0 会让桶永不补充（只允许 burst 次），这几乎必然是配置事故而不是意图：
        // 把 0/负数当成「没有配置」处理，回落到内置默认值。**两级都按这条规则处理**（保留原有的
        // 按行校验语义：非正值的行不生效，且不因此降级到另一维的行）。
        ConfigSnapshot zero = new ConfigSnapshot(1L, 2L, List.of(), List.of(),
                List.of(new RatePolicy(7L, null, 0, 0)), null);
        ConfigSnapshot zeroKeyLevel = new ConfigSnapshot(1L, 2L, List.of(), List.of(),
                List.of(new RatePolicy(7L, null, 20, 40), new RatePolicy(7L, 42L, 0, 0)), null);

        RatePolicy policy = resolverOf(zero).resolve(7L, null);

        assertThat(policy.qps()).isEqualTo(RatePolicy.DEFAULT_QPS);
        assertThat(policy.burst()).isEqualTo(RatePolicy.DEFAULT_BURST);

        RatePolicy invalidKeyLevel = resolverOf(zeroKeyLevel).resolve(7L, 42L);

        assertThat(invalidKeyLevel.qps()).as("非正的 key 级行同样回落到内置默认")
                .isEqualTo(RatePolicy.DEFAULT_QPS);
        assertThat(invalidKeyLevel.burst()).isEqualTo(RatePolicy.DEFAULT_BURST);
    }

    @Test
    void readsTheSnapshotLazilyPerCall() {
        AtomicReference<ConfigSnapshot> current = new AtomicReference<>(ConfigSnapshot.empty());
        RateLimitResolver resolver = new RateLimitResolver(current::get);

        assertThat(resolver.resolve(7L, 42L).qps()).isEqualTo(RatePolicy.DEFAULT_QPS);

        current.set(SNAPSHOT);

        assertThat(resolver.resolve(7L, 42L).qps()).as("策略必须随快照刷新而生效").isEqualTo(100);
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimitResolver.java`：

```java
package com.aihub.gateway.ratelimit;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.RatePolicy;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@code (tenantId, apiKeyId) → RatePolicy}。策略来自配置快照（三级缓存），因此**每次调用都惰性
 * 读快照**，快照刷新后新策略立即生效（不需要重启，也不需要清缓存）。
 *
 * <p><b>两个维度都生效</b>（决策 7，2026-09-26 依控制器 pre-flight 评审修订），顺序是确定性的：
 * <ol>
 *   <li>该 {@code (tenantId, apiKeyId)} 的 **key 级**策略存在 → **在这一维收口**，取最后一条；</li>
 *   <li>否则该租户的**租户级**策略存在 → 取最后一条；</li>
 *   <li>都没有 → 内置默认 `qps=10 / burst=20`。</li>
 * </ol>
 * {@code apiKeyId == null}（鉴权关闭 / 匿名桶 / 没有数值主键）时**直接走第二级**：不能因为拿不到
 * key 主键就把租户级策略也丢掉。
 *
 * <p>同维度多条时取**列表里的最后一条** —— 组装快照的 admin 侧已经按 {@code id} 升序排好，
 * 因此「最后一条」就是「最后插入的那条」（决策 17）。
 *
 * <p>非正的 qps/burst 是配置事故，回落到内置默认值（让 qps=0 把租户彻底打死不是想要的运维后果；
 * 要走「停用」应该改 {@code status}）。**这条按行校验规则保留原样，并且不跨维回落**：命中 key 级的
 * 那一条如果值非法，结果是**内置默认**，而不是悄悄放宽成该租户的租户级额度 —— 一条写坏的 key 级
 * 行不该变成「额度比不写还大」。
 */
public class RateLimitResolver {

    private final Supplier<ConfigSnapshot> snapshots;

    public RateLimitResolver(Supplier<ConfigSnapshot> snapshots) {
        this.snapshots = snapshots;
    }

    public RatePolicy resolve(long tenantId, Long apiKeyId) {
        ConfigSnapshot snapshot = snapshots.get();
        if (apiKeyId != null) {
            List<RatePolicy> keyLevel = snapshot.keyPolicies(tenantId, apiKeyId);
            if (!keyLevel.isEmpty()) {
                return usableOrDefault(keyLevel.get(keyLevel.size() - 1), tenantId);
            }
        }
        List<RatePolicy> tenantLevel = snapshot.tenantPolicies(tenantId);
        if (!tenantLevel.isEmpty()) {
            return usableOrDefault(tenantLevel.get(tenantLevel.size() - 1), tenantId);
        }
        return RatePolicy.defaultFor(tenantId);
    }

    /** 非正的行按「配置事故」处理：回落到内置默认（调用方已决定不再看另一维）。 */
    private static RatePolicy usableOrDefault(RatePolicy candidate, long tenantId) {
        return candidate.qps() > 0 && candidate.burst() > 0 ? candidate : RatePolicy.defaultFor(tenantId);
    }
}
```

- [ ] **Step 2: 写 `RedisRateLimiterTest` 与 `RedisRateLimiter`**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/RedisRateLimiterTest.java`：

```java
package com.aihub.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 侧的调用约定与失败语义。**行为**（脚本真的在 Redis 里原子地补充/拒绝）由 admin 侧的
 * 真 Redis 集成测试负责 —— 网关测试不允许依赖 Docker。
 *
 * <p>本类的核心契约：Redis 出任何问题（连不上、超时、脚本被 FLUSHALL 掉）都返回 {@code null}
 * 表示「我这边不可用」，由 {@link RateLimiter} 决定降级到本机桶；**绝不抛异常到请求路径上**。
 */
@SuppressWarnings("unchecked")
class RedisRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final RedisRateLimiter limiter = new RedisRateLimiter(redis);

    @Test
    void returnsNullWhenRedisThrows() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));

        assertThat(limiter.tryConsume("aihub:ratelimit:7:abc", 10, 20)).isNull();
    }

    @Test
    void returnsNullWhenTheScriptResultIsNotAThreeElementList() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L));
        assertThat(limiter.tryConsume("k", 10, 20)).isNull();

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        assertThat(limiter.tryConsume("k", 10, 20)).isNull();
    }

    @Test
    void passesThePinnedKeyAndArgumentsToTheScript() {
        AtomicReference<List<String>> keys = new AtomicReference<>();
        AtomicReference<Object[]> args = new AtomicReference<>();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            keys.set(invocation.getArgument(1));
            args.set(invocation.getArgument(2));
            return List.of(1L, 19L, 0L);
        });

        limiter.tryConsume("aihub:ratelimit:7:abc", 10, 20);

        assertThat(keys.get()).containsExactly("aihub:ratelimit:7:abc");
        // ARGV = {nowMillis, qps, burst, ttlMillis}
        assertThat(args.get()).hasSize(4);
        assertThat(args.get()[1]).isEqualTo(String.valueOf(10));
        assertThat(args.get()[2]).isEqualTo(String.valueOf(20));
        assertThat(args.get()[3]).isEqualTo(String.valueOf(LuaTokenBucket.idleTtlMillis(10, 20)));
        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void mapsTheScriptResultToADecision() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 7L, 0L));

        RateLimitDecision decision = limiter.tryConsume("k", 10, 20);

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(7);
        assertThat(decision.limit()).isEqualTo(10);
        assertThat(decision.burst()).isEqualTo(20);
        assertThat(decision.source()).isEqualTo(RateLimitDecision.Source.REDIS);
        assertThat(decision.degraded()).isFalse();
    }

    @Test
    void deniedDecisionCarriesRetryAfter() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, 0L, 250L));

        RateLimitDecision decision = limiter.tryConsume("k", 10, 20);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterMs()).isEqualTo(250L);
    }

    /** 空/空白 key 属于编程错误：不要为此打一次 Redis，直接按「本机不可用」返回让上层降级。 */
    @Test
    void blankKeyIsRejectedLocallyWithoutCallingRedis() {
        assertThat(limiter.tryConsume("", 10, 20)).isNull();
        assertThat(limiter.tryConsume(null, 10, 20)).isNull();
    }

    /**
     * 降级信号必须是 `null` 而不是「一个拒绝判定」：把故障表达成拒绝就等于把 Redis 故障
     * 变成对客户端的限流拒绝 —— 那正是设计文档 §9 要避免的事。
     */
    @Test
    void unavailableRedisIsSignalledAsNullSoTheCallerCanDegrade() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));

        RateLimitDecision decision = limiter.tryConsume("k", 10, 20);

        assertThat(decision).as("故障绝不能伪装成一次「拒绝」").isNull();
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RedisRateLimiter.java`：

```java
package com.aihub.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Redis + Lua 令牌桶（正常路径）。
 *
 * <p><b>返回 {@code null} 表示「Redis 这一级不可用」</b>（连不上 / 超时 / 脚本返回值不合法 /
 * key 为空），由 {@link RateLimiter} 决定降级到 {@link LocalRateLimiter}。**绝不把故障表达成
 * 「拒绝」**：那等于把控制面故障变成对客户端的限流拒绝。
 *
 * <p><b>阻塞 I/O</b>：{@code StringRedisTemplate} 是 Lettuce 的**同步** API。本类只提供同步方法，
 * 调用方（过滤器路径）必须明白它的代价：Redis 不可达时每次调用会被按住
 * {@code spring.data.redis.timeout}（2 秒）。缓解手段是 {@link RateLimiter} 的**粘性降级**
 * （一旦发现不可用，接下来 1 秒内直接走本机桶，不再每次撞超时）。
 */
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(LuaTokenBucket.SCRIPT, List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 见类注释：返回 {@code null} = 「Redis 这一级不可用」。 */
    @SuppressWarnings("unchecked")
    public RateLimitDecision tryConsume(String bucketKey, int qps, int burst) {
        if (bucketKey == null || bucketKey.isBlank()) {
            return null;
        }
        try {
            List<Long> result = redis.execute(SCRIPT, List.of(bucketKey),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(qps),
                    String.valueOf(burst),
                    String.valueOf(LuaTokenBucket.idleTtlMillis(qps, burst)));
            if (result == null || result.size() != 3) {
                log.warn("限流脚本返回了意外结果（{}），本次按「Redis 不可用」降级", result);
                return null;
            }
            long allowed = result.get(0);
            int remaining = (int) Math.max(0L, result.get(1));
            long retryAfter = Math.max(0L, result.get(2));
            int capacity = Math.max(burst, 1);
            return allowed == 1L
                    ? RateLimitDecision.allowed(remaining, qps, capacity, RateLimitDecision.Source.REDIS)
                    : RateLimitDecision.denied(retryAfter, qps, capacity, RateLimitDecision.Source.REDIS);
        } catch (RuntimeException e) {
            log.warn("Redis 限流失败，降级为本地令牌桶: {}", e.toString());
            return null;
        }
    }
}
```

> `RedisScript.of(String, Class)` 在 Spring Data Redis 3.5.13 上存在（本机 `.m2repo` 已确认）。
> 返回类型必须是 `List`（脚本返回数组），写成 `Long.class` 会在运行时得到 `null`/转换异常。
> `@SuppressWarnings("rawtypes")` 是给原始类型 `List` 的，保留即可。

- [ ] **Step 3: 写 `RateLimiter`（降级门面）**

创建 `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimiter.java`：

```java
package com.aihub.gateway.ratelimit;

import com.aihub.common.config.RatePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 限流的**唯一入口**，也是「Redis 挂了怎么办」这条降级规则的唯一落点
 * （设计文档 §9：「Redis 不可用 → 限流降级为本地令牌桶（单机近似）…不阻断服务」）。
 *
 * <p>顺序：解析策略 → 试 Redis → Redis 说「我不可用」就试本机桶。
 * **绝不返回「拒绝」来表达自身故障**：故障只能转化为「换一种近似」，不能转化为「拒绝服务」。
 *
 * <p>降级是**粘性一秒**的（避免 Redis 挂掉时每个请求都先等一次 2 秒超时）：本机桶命中一次后，
 * 标记为降级并记录时刻，接下来 1 秒内直接走本机桶。这是性能取舍，不是正确性取舍 ——
 * 恢复后最多晚 1 秒回到 Redis。
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final long DEFAULT_DEGRADE_STICKY_MILLIS = 1_000L;

    private final RedisRateLimiter redis;
    private final LocalRateLimiter local;
    private final RateLimitResolver resolver;
    private final long degradeStickyMillis;

    private final AtomicBoolean redisDegraded = new AtomicBoolean(false);
    private volatile long degradedAtMillis;

    public RateLimiter(RedisRateLimiter redis, LocalRateLimiter local, RateLimitResolver resolver) {
        this(redis, local, resolver, DEFAULT_DEGRADE_STICKY_MILLIS);
    }

    public RateLimiter(RedisRateLimiter redis, LocalRateLimiter local, RateLimitResolver resolver,
                       long degradeStickyMillis) {
        this.redis = redis;
        this.local = local;
        this.resolver = resolver;
        this.degradeStickyMillis = degradeStickyMillis;
    }

    /**
     * @param tenantId 桶维度之一，也是策略的租户维度
     * @param apiKeyId {@code api_key} 的**数值主键**（策略的 key 维度，决策 7/14）；没有则为 {@code null}
     *                 （鉴权关闭 / 匿名桶），此时策略解析自动只走租户级
     * @param keyHash  密钥的 SHA-256（桶维度之二；鉴权过滤器写进 exchange 属性）
     */
    public RateLimitDecision acquire(long tenantId, Long apiKeyId, String keyHash) {
        RatePolicy policy = resolver.resolve(tenantId, apiKeyId);
        String bucketKey = LuaTokenBucket.KEY_PREFIX + tenantId + ":" + keyHash;

        if (isDegradedNow()) {
            return local.tryConsume(bucketKey, policy.qps(), policy.burst());
        }
        RateLimitDecision decision = redis.tryConsume(bucketKey, policy.qps(), policy.burst());
        if (decision == null) {
            markDegraded();
            return local.tryConsume(bucketKey, policy.qps(), policy.burst());
        }
        clearDegraded();
        return decision;
    }

    /** 最近一次判定是否走了降级路径（供 Task 9 打指标与限速日志）。 */
    public boolean redisDegraded() {
        return redisDegraded.get();
    }

    private boolean isDegradedNow() {
        return redisDegraded.get() && System.currentTimeMillis() - degradedAtMillis < degradeStickyMillis;
    }

    private void markDegraded() {
        degradedAtMillis = System.currentTimeMillis();
        if (redisDegraded.compareAndSet(false, true)) {
            log.error("Redis 限流不可用，已降级为本地令牌桶（单机近似，多实例下实际放行量约为「策略 × 实例数」）");
        }
    }

    private void clearDegraded() {
        if (redisDegraded.compareAndSet(true, false)) {
            log.info("Redis 限流已恢复");
        }
    }
}
```

- [ ] **Step 4: 运行本任务两个测试类**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=RateLimitResolverTest,RedisRateLimiterTest"
```

预期：`Tests run: 14, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: 跑网关全部测试**

```powershell
mvn -B -pl aihub-gateway test
```

预期：`Tests run: 168, Failures: 0, Errors: 0`（Task 3 的 154 + 本任务 14）。报告实测数字。

- [ ] **Step 6: 提交**

```powershell
git add aihub-gateway/src/main/java/com/aihub/gateway/ratelimit aihub-gateway/src/test/java/com/aihub/gateway/ratelimit
git commit -m "feat: add the redis token bucket, policy resolution and the degrade facade"
```

**验收标准**
1. 策略按 `(tenantId, apiKeyId)` **两个维度**解析（决策 7 修订）：key 级命中即收口，否则回落该租户的租户级，都没有或值非法时为 `qps=10 / burst=20`；每一维内部多条取**最后一条**（决策 17）。桶 key 仍是 `{tenantId}:{sha256(secret)}`，**不因策略维度变化而改变**。
2. `RedisRateLimiter` 的脚本调用是「1 个 key + 4 个字符串参数（now/qps/burst/ttl）」，返回必须是三元素；任何异常、`null` 或意外返回都变成 `null`（不抛异常、也不伪装成拒绝）。
3. `RateLimiter.acquire(tenantId, apiKeyId, keyHash)` 在 Redis 不可用时**必定**返回本机桶的判定（`source == LOCAL`），绝不返回「拒绝」来表达故障；降级期间有 ERROR 日志、恢复有 INFO 日志。
4. 降级是粘性的（1 秒），不会每个请求都重试一次 2 秒超时的 Redis。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-gateway test -am "-Dtest=RateLimitResolverTest,RedisRateLimiterTest"` | `Tests run: 14, Failures: 0, Errors: 0` + `BUILD SUCCESS` |
| `mvn -B -pl aihub-gateway test` | `Tests run: 168, Failures: 0, Errors: 0` + `BUILD SUCCESS` |

---

## Task 5: 跨实例熔断器（Redis 30s + 本机降级）

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/route/CircuitState.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/route/ChannelCircuitBreaker.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/route/ChannelCircuitBreakerTest.java`

**Interfaces:**
- Consumes: 无。
- Produces：
  - `record CircuitState(boolean open, String source)`，常量 `SOURCE_REDIS = "redis"` / `SOURCE_LOCAL = "local"`；静态 `closed()` / `open(String source)`；`boolean degraded()`。
  - `ChannelCircuitBreaker`：构造器 `ChannelCircuitBreaker(StringRedisTemplate redis, LongSupplier clockMillis)`；`boolean isOpen(long channelId)`；`void markOpen(long channelId)`；`void clear(long channelId)`；`CircuitState state(long channelId)`；`int localOpenCount()`；常量 `KEY_PREFIX = "aihub:channel:circuit:"`、`OPEN_TTL = Duration.ofSeconds(30)`、`OPEN_VALUE = "OPEN"`。
  - Redis 不可用时：`isOpen` / `markOpen` 退化为本机 `ConcurrentHashMap<Long, Long>`（值 = 打开时刻毫秒），「仍打开」= `now - openedAt < 30_000`。**所有方法永不抛异常**；未知渠道默认「未熔断」（fail-open）。

**测试用例清单**（`ChannelCircuitBreakerTest`，10 条）：

| 用例 | 钉住什么 | 会红在什么错误实现上 |
|---|---|---|
| `marksAChannelOpenInRedisWithThePinnedKeyAndTtl` | key 字面量、值 `OPEN`、TTL **恰好 30 秒** | TTL 写成 30 分钟 / 30 毫秒 |
| `readsBackTheRedisMark` | `hasKey` 为真 → `isOpen` 为真 | 读的时候用了另一个前缀 |
| `clearingRemovesTheRedisMark` | `delete` 被调用 | 只清本机不清 Redis（多实例下熔断永不恢复） |
| `degradesToALocalMarkWhenRedisThrows` | Redis 抛异常 → `markOpen` 不抛、`isOpen` 仍为真（本机） | 抛异常（会把熔断判定变成客户端 500） |
| `localMarkExpiresAfterThePinnedTtl` | 假时钟 +30_001ms → `isOpen` 变假 | 本机标记永不过期 |
| `localMarkIsStillOpenJustBeforeTheTtl` | 假时钟 +29_999ms → 仍为真 | 本机 TTL 偏移 |
| `unknownChannelIsNeverOpen` | 未标记 → 假 | 默认打开（那会让所有请求直接失败） |
| `stateExplainsWhereTheJudgementCameFrom` | `source` 为 `redis` / `local` | 只返回布尔、丢掉「判断来自哪一级」的信息 |
| `clearAlsoForgetsTheLocalMark` | `clear` 后本机表也空了 | 只清 Redis，本机标记留到自然过期 |
| `keyPrefixAndTtlAreThePinnedLiterals` | `aihub:channel:circuit:` / 30 秒 / `OPEN` | 前缀或 TTL 漂移（运维找不到标记） |

- [ ] **Step 1: 写失败测试**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/route/ChannelCircuitBreakerTest.java`：

```java
package com.aihub.gateway.route;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 熔断状态必须**跨实例共享**（设计文档 §9 的明文要求）：一个实例发现某渠道 429 了，
 * 另外九个实例应该立刻停止往它上面打流量。Redis 的 key + TTL 是唯一能同时做到「共享」与
 * 「自动过期」的载体（30 秒是 spec 写死的数字）。
 *
 * <p>Redis 不可用时退化为**本机**熔断表：单机近似，但绝不阻断数据面（决策 10）。
 * 所有判定都必须**永不抛异常** —— 它跑在请求路径上。
 */
@SuppressWarnings("unchecked")
class ChannelCircuitBreakerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private ChannelCircuitBreaker breaker() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        return new ChannelCircuitBreaker(redis, clock::get);
    }

    private void breakRedis() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));
        doThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void marksAChannelOpenInRedisWithThePinnedKeyAndTtl() {
        breaker().markOpen(42L);

        verify(values).set(eq("aihub:channel:circuit:42"), eq("OPEN"), eq(Duration.ofSeconds(30)));
    }

    @Test
    void readsBackTheRedisMark() {
        ChannelCircuitBreaker breaker = breaker();
        when(redis.hasKey("aihub:channel:circuit:42")).thenReturn(true);

        assertThat(breaker.isOpen(42L)).isTrue();
        assertThat(breaker.isOpen(43L)).isFalse();
    }

    @Test
    void clearingRemovesTheRedisMark() {
        breaker().clear(42L);

        verify(redis).delete("aihub:channel:circuit:42");
    }

    @Test
    void degradesToALocalMarkWhenRedisThrows() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();

        breaker.markOpen(42L);

        assertThat(breaker.isOpen(42L)).as("Redis 挂了也必须记住这次熔断（本机近似）").isTrue();
        assertThat(breaker.localOpenCount()).isEqualTo(1);
        assertThat(breaker.isOpen(43L)).isFalse();
    }

    @Test
    void localMarkExpiresAfterThePinnedTtl() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();
        breaker.markOpen(42L);

        clock.addAndGet(30_001L);

        assertThat(breaker.isOpen(42L)).isFalse();
    }

    @Test
    void localMarkIsStillOpenJustBeforeTheTtl() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();
        breaker.markOpen(42L);

        clock.addAndGet(29_999L);

        assertThat(breaker.isOpen(42L)).isTrue();
    }

    @Test
    void unknownChannelIsNeverOpen() {
        assertThat(breaker().isOpen(999L)).isFalse();
    }

    @Test
    void stateExplainsWhereTheJudgementCameFrom() {
        ChannelCircuitBreaker breaker = breaker();
        when(redis.hasKey("aihub:channel:circuit:42")).thenReturn(true);

        assertThat(breaker.state(42L).open()).isTrue();
        assertThat(breaker.state(42L).source()).isEqualTo(CircuitState.SOURCE_REDIS);
        assertThat(breaker.state(1L).open()).isFalse();

        breakRedis();
        breaker.markOpen(7L);

        CircuitState local = breaker.state(7L);
        assertThat(local.open()).isTrue();
        assertThat(local.source()).isEqualTo(CircuitState.SOURCE_LOCAL);
        assertThat(local.degraded()).isTrue();
    }

    @Test
    void clearAlsoForgetsTheLocalMark() {
        ChannelCircuitBreaker breaker = breaker();
        breakRedis();
        breaker.markOpen(42L);
        assertThat(breaker.isOpen(42L)).isTrue();

        breaker.clear(42L);

        assertThat(breaker.isOpen(42L)).isFalse();
        assertThat(breaker.localOpenCount()).isZero();
    }

    @Test
    void keyPrefixAndTtlAreThePinnedLiterals() {
        assertThat(ChannelCircuitBreaker.KEY_PREFIX).isEqualTo("aihub:channel:circuit:");
        assertThat(ChannelCircuitBreaker.OPEN_TTL).isEqualTo(Duration.ofSeconds(30));
        assertThat(ChannelCircuitBreaker.OPEN_VALUE).isEqualTo("OPEN");
    }
}
```

- [ ] **Step 2: 运行，确认失败**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=ChannelCircuitBreakerTest"
```

预期：`BUILD FAILURE` + `cannot find symbol: class ChannelCircuitBreaker`。

- [ ] **Step 3: 写 `CircuitState` 与 `ChannelCircuitBreaker`**

创建 `aihub-gateway/src/main/java/com/aihub/gateway/route/CircuitState.java`：

```java
package com.aihub.gateway.route;

/**
 * 一条渠道的熔断状态。
 *
 * <p>{@code source} 只用于日志与指标：它记录「这个判断是从 Redis 读到的，还是本机降级表里的」。
 * 运维含义完全不同 —— 前者是全局共识，后者只代表本实例。
 */
public record CircuitState(boolean open, String source) {

    public static final String SOURCE_REDIS = "redis";
    public static final String SOURCE_LOCAL = "local";

    public static CircuitState closed() {
        return new CircuitState(false, SOURCE_REDIS);
    }

    public static CircuitState open(String source) {
        return new CircuitState(true, source);
    }

    public boolean degraded() {
        return SOURCE_LOCAL.equals(source);
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/route/ChannelCircuitBreaker.java`：

```java
package com.aihub.gateway.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 渠道熔断：上游回 429 时给该渠道打一个 **30 秒**的标记，让所有网关实例立刻停止往它上面打流量
 * （设计文档 §9：「上游 429 → 在 Redis 给该渠道打 30s 熔断标记，立即换渠道」）。
 *
 * <p><b>为什么是 Redis TTL 而不是本机计时器</b>：熔断必须跨实例共享（一个实例发现的 429
 * 对另外九个同样有效），而 Redis 的 key TTL 是唯一能同时做到「共享」与「自动恢复」的载体。
 *
 * <p><b>为什么只有 429 会熔断（决策 10）</b>：429 表示该渠道的速率/配额已满，继续打只会持续失败；
 * 而 5xx 可能只是一个坏请求触发的单次故障，把整条渠道熔断 30 秒过于激进（5xx 仍然会触发
 * **当次**切换，只是不打熔断标记）。
 *
 * <p><b>Redis 不可用时退化为本机表</b>：{@code ConcurrentHashMap<channelId, openedAtMillis>}，
 * 判定仍按 30 秒。这是单机近似 —— 与限流的降级同一套哲学（不阻断数据面）。
 *
 * <p>本类的所有方法**永不抛异常**：它跑在请求路径上，且熔断器自身的故障绝不能变成客户端 500。
 * 未知渠道默认「未熔断」（fail-open）：默认打开会让一次误标记把所有请求挡在门外。
 */
public class ChannelCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ChannelCircuitBreaker.class);

    /** 熔断标记的 key 前缀：完整键是 {@code aihub:channel:circuit:{channelId}}（决策 10）。 */
    public static final String KEY_PREFIX = "aihub:channel:circuit:";

    /** spec 写死的 30 秒（§9）。改这个数字必须同时改这里与文档。 */
    public static final Duration OPEN_TTL = Duration.ofSeconds(30);

    public static final String OPEN_VALUE = "OPEN";

    private final StringRedisTemplate redis;
    private final LongSupplier clockMillis;
    private final Map<Long, Long> localOpen = new ConcurrentHashMap<>();

    public ChannelCircuitBreaker(StringRedisTemplate redis, LongSupplier clockMillis) {
        this.redis = redis;
        this.clockMillis = clockMillis;
    }

    /** 该渠道当前是否被熔断。Redis 说不可用就查本机表（两处都不抛异常）。 */
    public boolean isOpen(long channelId) {
        Boolean exists = readRedisFlag(channelId);
        if (exists != null) {
            return exists;
        }
        return isLocallyOpen(channelId);
    }

    /** 上游 429 时调用。Redis 写失败就在本机记一笔（多实例下仍然能挡住本实例的重复打击）。 */
    public void markOpen(long channelId) {
        boolean redisOk = false;
        try {
            redis.opsForValue().set(key(channelId), OPEN_VALUE, OPEN_TTL);
            redisOk = true;
        } catch (RuntimeException e) {
            log.warn("写入熔断标记失败（Redis 不可用），退化为本机熔断: {}", e.toString());
        }
        if (!redisOk) {
            localOpen.put(channelId, clockMillis.getAsLong());
        }
        log.warn("渠道 {} 熔断 {} 秒（上游返回 429）", channelId, OPEN_TTL.toSeconds());
    }

    /** 手动清除（测试与运维用；正常恢复靠 TTL）。 */
    public void clear(long channelId) {
        try {
            redis.delete(key(channelId));
        } catch (RuntimeException e) {
            log.debug("清除熔断标记失败（Redis 不可用），忽略: {}", e.toString());
        }
        localOpen.remove(channelId);
    }

    /** 本机表里当前仍打开的数量（指标与测试用）。 */
    public int localOpenCount() {
        localOpen.entrySet().removeIf(entry -> expired(entry.getValue()));
        return localOpen.size();
    }

    /** 某个渠道的状态，含「判断来自哪一级」。 */
    public CircuitState state(long channelId) {
        Boolean exists = readRedisFlag(channelId);
        if (exists != null) {
            return exists ? CircuitState.open(CircuitState.SOURCE_REDIS) : CircuitState.closed();
        }
        return isLocallyOpen(channelId) ? CircuitState.open(CircuitState.SOURCE_LOCAL) : CircuitState.closed();
    }

    /**
     * @return {@code TRUE} / {@code FALSE}（Redis 给了明确答案），或 {@code null}（Redis 不可用，
     *         调用方必须改查本机表）
     */
    private Boolean readRedisFlag(long channelId) {
        try {
            Boolean exists = redis.hasKey(key(channelId));
            return exists == null ? Boolean.FALSE : exists;
        } catch (RuntimeException e) {
            log.debug("读取熔断标记失败（Redis 不可用），改查本机熔断表: {}", e.toString());
            return null;
        }
    }

    private boolean isLocallyOpen(long channelId) {
        Long openedAt = localOpen.get(channelId);
        if (openedAt == null) {
            return false;
        }
        if (expired(openedAt)) {
            localOpen.remove(channelId, openedAt);
            return false;
        }
        return true;
    }

    private boolean expired(long openedAtMillis) {
        long elapsed = clockMillis.getAsLong() - openedAtMillis;
        // elapsed < 0（时钟回拨）时按「仍打开」处理：宁可多熔断一会儿，也不要因为时钟抖动
        // 把刚熔断的坏渠道立刻放回来。
        return elapsed >= OPEN_TTL.toMillis();
    }

    private static String key(long channelId) {
        return KEY_PREFIX + channelId;
    }
}
```

- [ ] **Step 4: 运行测试，确认全绿**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=ChannelCircuitBreakerTest"
```

预期：`Tests run: 10, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: 跑网关全部测试并提交**

```powershell
mvn -B -pl aihub-gateway test
git add aihub-gateway/src/main/java/com/aihub/gateway/route/CircuitState.java aihub-gateway/src/main/java/com/aihub/gateway/route/ChannelCircuitBreaker.java aihub-gateway/src/test/java/com/aihub/gateway/route/ChannelCircuitBreakerTest.java
git commit -m "feat: add the cross-instance channel circuit breaker with a local fallback"
```

预期：`Tests run: 178, Failures: 0, Errors: 0`（Task 4 的 168 + 10）。报告实测数字。

**验收标准**
1. `markOpen` 写 `aihub:channel:circuit:{id}` = `OPEN`，TTL **恰好 30 秒**；`isOpen` 读同一个键；`clear` 同时清 Redis 与本机表。
2. Redis 抛异常时 `markOpen` / `isOpen` / `state` 都不抛，退化为本机表，且本机表按同样的 30 秒过期（假时钟验证两个边界 29_999 / 30_001）。
3. 未知渠道默认「未熔断」（fail-open）；`state(...).source()` 能区分 `redis` 与 `local`。
4. **只有上游 429 会调用 `markOpen`**（5xx 不熔断，这一点由 Task 10 的端到端用例钉住）。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-gateway test -am "-Dtest=ChannelCircuitBreakerTest"` | `Tests run: 10, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-gateway test` | `Tests run: 178, Failures: 0, Errors: 0` + `BUILD SUCCESS` |

---

## Task 6: 路由解析（priority 分组 + 权重随机 + 跳过熔断）

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/route/RouteSelectionException.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/route/RouteResolver.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/config/LegacyChannel.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/route/RouteResolverTest.java`

**Interfaces:**
- Consumes：Task 2（`ConfigSnapshot` / `ChannelDescriptor` / `ModelRouteDescriptor`）、Task 5（`ChannelCircuitBreaker`）。
- Produces：
  - `class RouteSelectionException extends RuntimeException`：`RouteSelectionException(String model)`、`String model()`。
  - `LegacyChannel`：`static final long ID = Long.MIN_VALUE`、`static ChannelDescriptor of(UpstreamProperties)`、`static boolean isLegacy(long channelId)`。
  - `RouteResolver`：构造器 `RouteResolver(Supplier<ConfigSnapshot> snapshots, ChannelCircuitBreaker breaker, RandomGenerator random)`；`List<ChannelDescriptor> candidates(String model)`（**已按优先级分组 + 组内权重随机排序**，熔断的排在组末尾）；`ChannelDescriptor primary(String model)`（空则抛 `RouteSelectionException`）。

**候选选择算法（精确规则，评审按此判断）**：
1. `snapshot.routesFor(model)` 取该模型的 ACTIVE 路由；`snapshot.channel(route.channelId())` 联表并过滤 `usable()` → 候选集。
2. 候选集为空 → 返回空列表（`primary` 抛 `RouteSelectionException`，控制器回 404 `model_not_found`）。
3. 按 `route.priority` **升序**分组（数字小的优先；这是 §5.1 表里 `priority` 的常规语义）。
4. 从第一组开始考察：组内存在未被熔断的渠道 → 这一组就是候选组，把未熔断的按**权重随机**排序，**再追加**组内被熔断的渠道（最后手段），返回。
5. 整组都被熔断 → 看下一组；所有组都被熔断 → 取最高优先级的那一组，按权重随机排序返回（放行，打 WARN，决策 10）。
6. 权重随机（**必须可复现**）：`total = Σ max(weight, 1)`；`r = random.nextLong(total)`；累加游走选中；选中项与末位交换后重复直到排完。权重非正数视为 1。

**测试用例清单**（`RouteResolverTest`，12 条）：

| 用例 | 钉住什么 |
|---|---|
| `picksTheLowestPriorityNumberGroupFirst` | priority=0 的组优先于 priority=1（即使后者权重更大） |
| `fallsBackToTheNextPriorityGroupWhenTheFirstIsFullyCircuitBroken` | 整组熔断 → 换下一组 |
| `skipsCircuitBrokenChannelsInsideTheChosenGroup` | 组内熔断的渠道排到最后（不是被删除） |
| `servesTheBestGroupAnywayWhenEveryCandidateIsCircuitBroken` | 全熔断 → 仍返回候选（决策 10） |
| `unknownModelHasNoCandidates` | `candidates` 空、`primary` 抛 `RouteSelectionException`（携带 model 名） |
| `distributesByWeightWithinTheSamePriority` | 固定种子下 1:3 权重在 4000 次里比例落在 20%–30% |
| `equalWeightsAreRoughlyFair` | 同权重下两条渠道都被选到 |
| `nonPositiveWeightIsTreatedAsOne` | weight=0 的渠道仍有机会被选到 |
| `ignoresInactiveChannelsAndRoutes` | 只有 ACTIVE 且可用的渠道进入候选 |
| `legacyChannelIsTheOnlyCandidateForTheLegacySentinelId` | `LegacyChannel.of(...)` 的字段与 `isLegacy` |
| `emptySnapshotYieldsNoCandidates` | 冷启动无快照时不抛异常，只是没有候选 |
| `candidateOrderIsDeterministicForAFixedSeed` | 同一快照 + 同一种子 → 同一顺序 |

- [ ] **Step 1: 写失败测试**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/route/RouteResolverTest.java`：

```java
package com.aihub.gateway.route;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由是「一次请求打到哪条渠道」的唯一决策点，也是故障转移的**输入**：它返回的是**有序候选列表**，
 * 不是单个渠道 —— 切换能力来自「列表里还有下一个」。
 *
 * <p>选择算法的两条硬规则：① `priority` **数字小的先**（分层，主备就是两个 priority）；
 * ② 同一 priority 内按**权重随机**（而不是轮询或取第一个），这是「按权重分流」的字面含义。
 * 随机源由构造器注入，因此「权重分布」可以被确定性地断言（固定种子），测试不赌运气。
 *
 * <p>熔断渠道被排到**候选组最后**而不是直接删掉：删掉会让「唯一候选恰好被熔断」变成无候选
 * （客户端 404），而那是一种比「试一试」更糟的结果（决策 10 对全熔断场景的同一套理由）。
 */
class RouteResolverTest {

    private static ChannelDescriptor channel(long id, String name) {
        return new ChannelDescriptor(id, name, "https://" + name + ".example.com", "v1:QUJD", 1, 60_000,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    private static ConfigSnapshot snapshot(List<ModelRouteDescriptor> routes, List<ChannelDescriptor> channels) {
        return new ConfigSnapshot(1L, 2L, channels, routes, List.of(), "default-model");
    }

    private static RouteResolver resolver(ConfigSnapshot snapshot, ChannelCircuitBreaker breaker, long seed) {
        return new RouteResolver(() -> snapshot, breaker,
                RandomGeneratorFactory.of("L64X128MixRandom").create(seed));
    }

    /** 永不熔断的替身：只覆盖 isOpen，其余方法不被本类使用。 */
    private static ChannelCircuitBreaker noCircuit() {
        return new ChannelCircuitBreaker(null, System::currentTimeMillis) {
            @Override
            public boolean isOpen(long channelId) {
                return false;
            }
        };
    }

    /** 指定一组「已熔断」渠道的替身。 */
    private static ChannelCircuitBreaker brokenOnly(long... brokenIds) {
        List<Long> broken = new ArrayList<>();
        for (long id : brokenIds) {
            broken.add(id);
        }
        return new ChannelCircuitBreaker(null, System::currentTimeMillis) {
            @Override
            public boolean isOpen(long channelId) {
                return broken.contains(channelId);
            }
        };
    }

    @Test
    void picksTheLowestPriorityNumberGroupFirst() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 1, 5, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 1000, 0, "ACTIVE")),
                List.of(channel(1L, "low-priority-number"), channel(2L, "primary")));

        assertThat(resolver(snapshot, noCircuit(), 7L).candidates("m"))
                .extracting(ChannelDescriptor::id)
                .containsExactly(2L, 1L);
    }

    @Test
    void fallsBackToTheNextPriorityGroupWhenTheFirstIsFullyCircuitBroken() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 1, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 1, 1, "ACTIVE")),
                List.of(channel(1L, "primary"), channel(2L, "standby")));

        assertThat(resolver(snapshot, brokenOnly(1L), 7L).candidates("m"))
                .extracting(ChannelDescriptor::id)
                .containsExactly(2L, 1L);
    }

    @Test
    void skipsCircuitBrokenChannelsInsideTheChosenGroup() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 100, 0, "ACTIVE")),
                List.of(channel(1L, "broken"), channel(2L, "healthy")));

        assertThat(resolver(snapshot, brokenOnly(1L), 7L).candidates("m"))
                .extracting(ChannelDescriptor::id)
                .as("熔断的排最后而不是被删掉")
                .containsExactly(2L, 1L);
    }

    /** 决策 10：全候选熔断时**仍然放行最好的一组**，而不是回 503/404。 */
    @Test
    void servesTheBestGroupAnywayWhenEveryCandidateIsCircuitBroken() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 100, 1, "ACTIVE")),
                List.of(channel(1L, "broken"), channel(2L, "standby")));

        List<ChannelDescriptor> candidates = resolver(snapshot, brokenOnly(1L, 2L), 7L).candidates("m");

        assertThat(candidates).as("全熔断也必须给出候选（best-effort）").isNotEmpty();
        assertThat(candidates).extracting(ChannelDescriptor::id).contains(1L, 2L);
        assertThat(candidates.get(0).id()).as("仍然优先最高优先级的组").isEqualTo(1L);
    }

    @Test
    void unknownModelHasNoCandidates() {
        RouteResolver resolver = resolver(ConfigSnapshot.empty(), noCircuit(), 7L);

        assertThat(resolver.candidates("nope")).isEmpty();
        assertThatThrownBy(() -> resolver.primary("nope"))
                .isInstanceOf(RouteSelectionException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void distributesByWeightWithinTheSamePriority() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 300, 0, "ACTIVE")),
                List.of(channel(1L, "one"), channel(2L, "three")));
        RouteResolver resolver = resolver(snapshot, noCircuit(), 20260923L);
        int firstCount = 0;
        int rounds = 4_000;

        for (int i = 0; i < rounds; i++) {
            if (resolver.candidates("m").get(0).id() == 1L) {
                firstCount++;
            }
        }

        // 1:3 的权重 → 首位被 id=1 拿到的比例应在 25% 附近（±5% 的宽区间，避免偶发抖动）。
        assertThat(firstCount).isBetween((int) (rounds * 0.20), (int) (rounds * 0.30));
    }

    @Test
    void equalWeightsAreRoughlyFair() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 100, 0, "ACTIVE")),
                List.of(channel(1L, "a"), channel(2L, "b")));
        RouteResolver resolver = resolver(snapshot, noCircuit(), 1L);
        int firstCount = 0;
        int rounds = 2_000;

        for (int i = 0; i < rounds; i++) {
            if (resolver.candidates("m").get(0).id() == 1L) {
                firstCount++;
            }
        }

        assertThat(firstCount).isBetween((int) (rounds * 0.40), (int) (rounds * 0.60));
    }

    @Test
    void nonPositiveWeightIsTreatedAsOne() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 0, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 1, 0, "ACTIVE")),
                List.of(channel(1L, "zero-weight"), channel(2L, "one")));
        RouteResolver resolver = resolver(snapshot, noCircuit(), 42L);
        boolean sawZeroWeight = false;

        for (int i = 0; i < 500 && !sawZeroWeight; i++) {
            sawZeroWeight = resolver.candidates("m").get(0).id() == 1L;
        }

        assertThat(sawZeroWeight).as("权重 0 视为 1，仍有机会被选到（不是隐形禁用）").isTrue();
    }

    @Test
    void ignoresInactiveChannelsAndRoutes() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, 2L,
                List.of(channel(1L, "ok"),
                        new ChannelDescriptor(2L, "off", "https://off", "v1:QUJD", 1, 1000, "DISABLED", 1, 0)),
                List.of(new ModelRouteDescriptor("m", 1L, 1, 0, "ACTIVE"),
                        new ModelRouteDescriptor("m", 2L, 1, 0, "ACTIVE"),
                        new ModelRouteDescriptor("m", 1L, 1, 0, "DISABLED")),
                List.of(), null);

        assertThat(resolver(snapshot, noCircuit(), 7L).candidates("m"))
                .extracting(ChannelDescriptor::id).containsExactly(1L);
    }

    @Test
    void legacyChannelIsTheOnlyCandidateForTheLegacySentinelId() {
        UpstreamProperties properties =
                new UpstreamProperties("http://127.0.0.1:11434", "synthetic-upstream-key", "m");

        ChannelDescriptor legacy = LegacyChannel.of(properties);

        assertThat(legacy.id()).isEqualTo(LegacyChannel.ID);
        assertThat(LegacyChannel.isLegacy(legacy.id())).isTrue();
        assertThat(LegacyChannel.isLegacy(11L)).isFalse();
        assertThat(legacy.usable()).isTrue();
        assertThat(legacy.apiKeyCipher()).as("遗留渠道的密钥不走密文").isNull();
        assertThat(legacy.timeoutMs()).isPositive();
    }

    @Test
    void emptySnapshotYieldsNoCandidates() {
        assertThat(resolver(ConfigSnapshot.empty(), noCircuit(), 7L).candidates("m")).isEmpty();
    }

    @Test
    void candidateOrderIsDeterministicForAFixedSeed() {
        ConfigSnapshot snapshot = snapshot(List.of(
                new ModelRouteDescriptor("m", 1L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 2L, 100, 0, "ACTIVE"),
                new ModelRouteDescriptor("m", 3L, 100, 0, "ACTIVE")),
                List.of(channel(1L, "a"), channel(2L, "b"), channel(3L, "c")));

        List<Long> first = ids(resolver(snapshot, noCircuit(), 999L).candidates("m"));
        List<Long> second = ids(resolver(snapshot, noCircuit(), 999L).candidates("m"));

        assertThat(first).isEqualTo(second);
    }

    private static List<Long> ids(List<ChannelDescriptor> channels) {
        List<Long> ids = new ArrayList<>();
        for (ChannelDescriptor channel : channels) {
            ids.add(channel.id());
        }
        return ids;
    }
}
```

> 未使用的 import `RandomGenerator` 在最终文件里可以删掉（`RandomGeneratorFactory.of(...).create(seed)` 的返回类型不需要显式声明）；**不要**为了「用掉」它而加声明 —— 那会让 `RouteResolver` 的构造器参数类型变得不显眼。

- [ ] **Step 2: 运行，确认失败**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=RouteResolverTest"
```

预期：`BUILD FAILURE` + `cannot find symbol: class RouteResolver`。

- [ ] **Step 3: 写 `RouteSelectionException` 与 `LegacyChannel`**

创建 `aihub-gateway/src/main/java/com/aihub/gateway/route/RouteSelectionException.java`：

```java
package com.aihub.gateway.route;

/**
 * 某个模型没有任何可用候选渠道。控制器把它翻成 OpenAI 形状的 404 {@code model_not_found}
 * —— **不是** 400（请求本身没问题）、**也不是** 502（不是上游的错），而是「我们不提供这个模型」。
 * 之所以不做「未知模型就回落到默认模型」：那会把客户端的拼写错误变成一次静默的错误计费。
 */
public class RouteSelectionException extends RuntimeException {

    private final transient String model;

    public RouteSelectionException(String model) {
        super("没有可用的渠道提供模型: " + model);
        this.model = model;
    }

    public String model() {
        return model;
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/config/LegacyChannel.java`：

```java
package com.aihub.gateway.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.gateway.upstream.UpstreamProperties;

/**
 * 把 M1/M2 的**单渠道配置**（{@code aihub.upstream.base-url} / {@code api-key} / {@code default-model}）
 * 合成一条「渠道」，让冷启动 + admin 不可达时数据面仍然能服务（决策 6 的最后一层兜底）。
 *
 * <p>{@code id = Long.MIN_VALUE}：一个**不可能与数据库自增主键相撞**的哨兵。它的存在让
 * 「这条请求走的是遗留渠道」在计量事件里可见（{@code channel_id = Long.MIN_VALUE} 而不是 NULL），
 * 从而不会与「多渠道正常路径」混淆。{@code apiKeyCipher} 为 {@code null} 是刻意的：
 * 遗留渠道的密钥来自配置而不是密文，解密步骤会跳过它（见 {@code ChannelKeyDecryptor}）。
 */
public final class LegacyChannel {

    /** 遗留单渠道的哨兵 id（负数且远离 0/自增序列）。 */
    public static final long ID = Long.MIN_VALUE;

    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private LegacyChannel() {
    }

    public static ChannelDescriptor of(UpstreamProperties properties) {
        return new ChannelDescriptor(ID, "legacy-single-channel", properties.baseUrl(), null, 0,
                DEFAULT_TIMEOUT_MS, ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    public static boolean isLegacy(long channelId) {
        return channelId == ID;
    }
}
```

- [ ] **Step 4: 写 `RouteResolver`**

创建 `aihub-gateway/src/main/java/com/aihub/gateway/route/RouteResolver.java`：

```java
package com.aihub.gateway.route;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * {@code model → 有序候选渠道}。返回**列表**而不是单个渠道：故障转移的全部能力来自「还有下一个」。
 *
 * <p>选择规则（评审按此判断，实施者不要「优化」）：
 * <ol>
 *   <li>候选集 = {@code routes} 与 {@code channels} 联表（两者都必须 ACTIVE 且渠道可用）；</li>
 *   <li><b>priority 数字小的组优先</b>。主备就是 priority=0 / priority=1 两条路由，
 *       不需要额外的「主/备」字段；</li>
 *   <li>组内<b>按权重随机</b>排序（{@code model_route.weight}）。权重非正数按 1 处理 ——
 *       零权重不该变成「永不选中」的隐形禁用，那会让一条配错的渠道静默失联；</li>
 *   <li>组内被熔断的渠道**排到该组末尾**而不是被删除：删掉会让「唯一候选恰好被熔断」退化成
 *       无候选（404），而全熔断时「试一次」比「直接告诉客户端没这个模型」更诚实（决策 10）；</li>
 *   <li>整组熔断 → 看下一组；所有组都熔断 → 用最高优先级的那一组（放行 + WARN）。</li>
 * </ol>
 *
 * <p>随机源由构造器注入（{@link RandomGenerator}）：测试用固定种子做确定性断言，
 * 生产用 {@code RandomGenerator.getDefault()}。这样「按权重分流」是真的被测过，而不是靠统计运气。
 */
public class RouteResolver {

    private static final Logger log = LoggerFactory.getLogger(RouteResolver.class);

    private final Supplier<ConfigSnapshot> snapshots;
    private final ChannelCircuitBreaker breaker;
    private final RandomGenerator random;

    public RouteResolver(Supplier<ConfigSnapshot> snapshots, ChannelCircuitBreaker breaker, RandomGenerator random) {
        this.snapshots = snapshots;
        this.breaker = breaker;
        this.random = random;
    }

    /** 有序候选（最该用的排最前）。没有候选时返回空列表（调用方决定 404 还是回落遗留渠道）。 */
    public List<ChannelDescriptor> candidates(String model) {
        ConfigSnapshot snapshot = snapshots.get();
        List<ModelRouteDescriptor> routes = snapshot.routesFor(model);
        if (routes.isEmpty()) {
            return List.of();
        }
        // priority → {渠道, route 权重}；TreeMap 保证「最高优先级的组」是可预测的第一项。
        Map<Integer, List<Entry>> grouped = new TreeMap<>();
        for (ModelRouteDescriptor route : routes) {
            snapshot.channel(route.channelId())
                    .filter(ChannelDescriptor::usable)
                    .ifPresent(channel -> grouped
                            .computeIfAbsent(route.priority(), ignored -> new ArrayList<>())
                            .add(new Entry(channel, route.weight())));
        }
        if (grouped.isEmpty()) {
            return List.of();
        }
        for (List<Entry> group : grouped.values()) {
            List<Entry> healthy = new ArrayList<>();
            List<ChannelDescriptor> broken = new ArrayList<>();
            for (Entry entry : group) {
                if (breaker.isOpen(entry.channel().id())) {
                    broken.add(entry.channel());
                } else {
                    healthy.add(entry);
                }
            }
            if (!healthy.isEmpty()) {
                List<ChannelDescriptor> ordered = weightedOrder(healthy);
                ordered.addAll(broken);
                return ordered;
            }
        }
        // 所有组都被熔断：取最高优先级的那一组，仍然按权重随机（best-effort）。
        List<Entry> best = grouped.values().iterator().next();
        log.warn("模型 {} 的所有候选渠道都在熔断中（{} 条），仍按最高优先级放行一次（best-effort）",
                model, best.size());
        return weightedOrder(best);
    }

    /** 首选渠道；没有候选时抛 {@link RouteSelectionException}（控制器翻成 404 model_not_found）。 */
    public ChannelDescriptor primary(String model) {
        List<ChannelDescriptor> candidates = candidates(model);
        if (candidates.isEmpty()) {
            throw new RouteSelectionException(model);
        }
        return candidates.get(0);
    }

    /**
     * 权重随机的**可复现**实现：按 {@code max(weight,1)} 做累加游走抽取，选中的与末位交换后重复。
     * 复杂度 O(n²)，候选数是个位数，不需要更聪明。
     */
    private List<ChannelDescriptor> weightedOrder(List<Entry> entries) {
        List<Entry> pool = new ArrayList<>(entries);
        List<ChannelDescriptor> ordered = new ArrayList<>(pool.size());
        while (!pool.isEmpty()) {
            long total = 0;
            for (Entry entry : pool) {
                total += Math.max(entry.weight(), 1);
            }
            long target = random.nextLong(total);
            int picked = 0;
            long walk = 0;
            for (int i = 0; i < pool.size(); i++) {
                walk += Math.max(pool.get(i).weight(), 1);
                if (target < walk) {
                    picked = i;
                    break;
                }
            }
            ordered.add(pool.get(picked).channel());
            pool.set(picked, pool.get(pool.size() - 1));
            pool.remove(pool.size() - 1);
        }
        return ordered;
    }

    /** 把「渠道 + route 权重」绑在一起传递的内部小类型。 */
    private record Entry(ChannelDescriptor channel, int weight) {
    }
}
```

> **不要**把 `TreeMap` 换成 `HashMap`：priority 的顺序是选择算法的第一步，用无序 Map 会让「最高优先级组」变得随机。

- [ ] **Step 5: 运行测试，确认全绿**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=RouteResolverTest"
```

预期：`Tests run: 12, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 6: 跑网关全部测试并提交**

```powershell
mvn -B -pl aihub-gateway test
git add aihub-gateway/src/main/java/com/aihub/gateway/route/RouteSelectionException.java aihub-gateway/src/main/java/com/aihub/gateway/route/RouteResolver.java aihub-gateway/src/main/java/com/aihub/gateway/config/LegacyChannel.java aihub-gateway/src/test/java/com/aihub/gateway/route/RouteResolverTest.java
git commit -m "feat: resolve a model to ordered channel candidates by priority and weight"
```

预期：`Tests run: 190, Failures: 0, Errors: 0`（Task 5 的 178 + 12）。报告实测数字。

**验收标准**
1. priority 数字小的组优先；组内按权重随机（固定种子下可复现、1:3 的分布落在 20%–30%、同权重落在 40%–60%）。
2. 熔断渠道在组内被排到最后；整组熔断则换下一组；**所有组熔断时仍返回最高优先级那组的候选**并打 WARN。
3. 未知模型：`candidates` 为空、`primary` 抛 `RouteSelectionException` 且消息里带模型名。
4. `LegacyChannel.of(...)` 产出 `id = Long.MIN_VALUE`、`apiKeyCipher == null`、`usable()` 为真。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-gateway test -am "-Dtest=RouteResolverTest"` | `Tests run: 12, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-gateway test` | `Tests run: 190, Failures: 0, Errors: 0` + `BUILD SUCCESS` |

---

## Task 7: 三级配置读取与两级缓存（Caffeine → Redis → admin，含 singleflight 与版本比对）

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/config/GatewayConfigProperties.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigCache.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigClient.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigConfig.java`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java`（加 `CONFIG_SNAPSHOT_PATH` 与 `configSnapshot()`；**`parse` 的 `apiKeyId` 已经在 Task 2 落地，本任务不要再动它**）
- Modify: `aihub-gateway/src/main/resources/application.yml`（`aihub.channel.master-key` 与 `aihub.config.*`）
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/config/ConfigCacheTest.java`

**Interfaces:**
- Consumes：Task 2（`ConfigSnapshot` / `ConfigSnapshotCodec`）、既有 `AdminClient` / `InternalHmac` / `StringRedisTemplate`。
- Produces：
  - `@ConfigurationProperties(prefix = "aihub.config") record GatewayConfigProperties(@DefaultValue("30s") Duration localTtl, @DefaultValue("10m") Duration snapshotTtl, @DefaultValue("300") int maxLocalSnapshotSources)`。
  - `AdminClient.CONFIG_SNAPSHOT_PATH = "/internal/config/snapshot"`；接口新增 `default Mono<Optional<ConfigSnapshot>> configSnapshot() { return Mono.just(Optional.empty()); }`（**默认实现**：所有既有的 lambda 替身不必改就能编译；`AdminClient.Http` 覆写它）。
  - `ConfigCache`：构造器 `ConfigCache(StringRedisTemplate redis, GatewayConfigProperties properties)`；`Optional<ConfigSnapshot> local()`；`void putLocal(ConfigSnapshot)`；`void invalidateLocal()`；`Optional<ConfigSnapshot> readRedis()`；`void writeRedis(ConfigSnapshot)`；`boolean redisAvailable()`；常量 `REDIS_KEY = "aihub:config:snapshot"`。
  - `ConfigClient`：构造器 `ConfigClient(ConfigCache cache, AdminClient adminClient, UpstreamProperties upstream, GatewayConfigProperties properties)`；`ConfigSnapshot current()`（**永不 null、永不抛**）；`Mono<ConfigSnapshot> refresh()`；`ConfigSnapshot legacyFallback()`；`ChannelDescriptor legacyChannel()`；`void invalidate()`。
  - **协议**：Redis 载荷用 `ConfigSnapshotCodec`；本地过期靠 Caffeine `expireAfterWrite(localTtl)`；**版本比对**：本地命中时若 `local.version() < redisVersion` 则丢弃本地、用 Redis 并刷新本地。

**测试用例清单**（`ConfigCacheTest`，11 条）：

| 用例 | 钉住什么 |
|---|---|
| `localHitIsServedWithoutTouchingRedisOrAdmin` | 一级命中零 I/O（`redis.opsForValue()` 一次都没被调用） |
| `localMissFallsToRedis` | 二级命中 |
| `redisMissFallsToAdminAndBackfillsBothLevels` | 三级回源 + 双回填（`set` 的参数含 `snapshotTtl`） |
| `staleLocalIsDiscardedWhenRedisHasANewerVersion` | **§6.3 的版本比对** |
| `redisFailureFallsThroughToAdmin` | Redis 抛异常 → 回源 |
| `adminFailureKeepsServingTheCachedSnapshot` | admin 挂 → 陈旧快照继续服务（决策 6） |
| `noSnapshotAnywhereFallsBackToTheLegacySingleChannel` | 冷启动 + admin 挂 → 遗留渠道 |
| `emptySnapshotFromAdminStillAllowsTheLegacyFallback` | admin 返回空快照 → 遗留渠道 |
| `concurrentMissesCollapseIntoASingleAdminCall` | **singleflight**：16 并发 → admin 只被调用 1 次 |
| `legacyFallbackIsNotCachedAsARealSnapshot` | 回退结果**不写** Redis（否则 admin 恢复后仍被旧兜底覆盖） |
| `invalidateDropsTheLocalLayerOnly` | 失效之后必须重新读 Redis |

- [ ] **Step 1: 写失败测试**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/config/ConfigCacheTest.java`：

```java
package com.aihub.gateway.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ConfigSnapshotCodec;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §6.3 的三级读取与两级缓存，加上它的两条兜底（Redis 挂 / admin 挂）。
 *
 * <p>{@code concurrentMissesCollapseIntoASingleAdminCall} 是**缓存击穿防护**的可证伪形式：
 * 把 singleflight 去掉（每次 miss 都直接回源）会让 admin 被调用 N 次，用例立刻变红。
 *
 * <p>本类不需要 Docker / Redis / Spring 上下文：{@code StringRedisTemplate} 是桩，
 * {@code AdminClient} 是 lambda（靠接口的 {@code default configSnapshot()} 覆写）。
 */
@SuppressWarnings("unchecked")
class ConfigCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final GatewayConfigProperties properties =
            new GatewayConfigProperties(Duration.ofSeconds(30), Duration.ofMinutes(10), 300);

    private final UpstreamProperties upstream =
            new UpstreamProperties("http://127.0.0.1:11434", "synthetic-upstream-key", "legacy-model");

    private static ConfigSnapshot snapshot(long version, long channelId) {
        return new ConfigSnapshot(version, version * 10,
                List.of(new ChannelDescriptor(channelId, "ch-" + channelId,
                        "https://ch" + channelId + ".example.com", "v1:QUJD", 1, 60_000, "ACTIVE", 100, 0)),
                List.of(new ModelRouteDescriptor("m", channelId, 100, 0, "ACTIVE")),
                List.of(), "m");
    }

    private ConfigCache cache() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        return new ConfigCache(redis, properties);
    }

    /** 一个总是返回给定快照的 admin 替身（覆写 configSnapshot 的默认实现）。 */
    private static AdminClient adminReturning(java.util.function.Supplier<Optional<ConfigSnapshot>> supplier) {
        return new AdminClient() {
            @Override
            public Mono<Optional<com.aihub.common.apikey.ApiKeyView>> resolve(String keyHash) {
                return Mono.just(Optional.empty());
            }

            @Override
            public Mono<Optional<ConfigSnapshot>> configSnapshot() {
                return Mono.just(supplier.get());
            }
        };
    }

    private ConfigClient client(AdminClient adminClient) {
        return new ConfigClient(cache(), adminClient, upstream, properties);
    }

    @Test
    void localHitIsServedWithoutTouchingRedisOrAdmin() {
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigClient client = client(adminReturning(() -> {
            adminCalls.incrementAndGet();
            return Optional.of(snapshot(1L, 11L));
        }));

        client.refresh().block();
        ConfigSnapshot second = client.current();

        assertThat(second.channels()).extracting(ChannelDescriptor::id).containsExactly(11L);
        assertThat(adminCalls).hasValue(1);
        verify(redis, never()).opsForValue();
    }

    @Test
    void localMissFallsToRedis() {
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshot(5L, 22L)));
        ConfigClient client = client(adminReturning(() -> {
            throw new AssertionError("Redis 命中时不该回源 admin");
        }));

        assertThat(client.current().channels()).extracting(ChannelDescriptor::id).containsExactly(22L);
    }

    @Test
    void redisMissFallsToAdminAndBackfillsBothLevels() {
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(null);
        ConfigClient client = client(adminReturning(() -> Optional.of(snapshot(7L, 33L))));

        ConfigSnapshot loaded = client.current();

        assertThat(loaded.channels()).extracting(ChannelDescriptor::id).containsExactly(33L);
        assertThat(client.current().channels()).as("第二次必须走本地缓存").extracting(ChannelDescriptor::id)
                .containsExactly(33L);
        verify(values).set(eq(ConfigCache.REDIS_KEY), eq(ConfigSnapshotCodec.encode(loaded)),
                eq(properties.snapshotTtl()));
    }

    /**
     * §6.3 的「快照 version 比对」：本地版本落后就丢弃并回源（这里是回 Redis）。
     * 删掉版本比对 → 本地会一直返回 v3，本用例变红。
     */
    @Test
    void staleLocalIsDiscardedWhenRedisHasANewerVersion() {
        when(values.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshot(10L, 44L)));
        ConfigCache cache = cache();
        cache.putLocal(snapshot(3L, 99L));
        ConfigClient client = new ConfigClient(cache, adminReturning(Optional::empty), upstream, properties);

        assertThat(client.current().channels()).extracting(ChannelDescriptor::id).containsExactly(44L);
    }

    @Test
    void redisFailureFallsThroughToAdmin() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("测试桩：Redis 不可用"));
        ConfigClient client = client(adminReturning(() -> Optional.of(snapshot(1L, 55L))));

        assertThat(client.current().channels()).extracting(ChannelDescriptor::id).containsExactly(55L);
    }

    @Test
    void adminFailureKeepsServingTheCachedSnapshot() {
        when(values.get(anyString())).thenReturn(null);
        ConfigCache cache = cache();
        cache.putLocal(snapshot(4L, 66L));
        ConfigClient warmed = new ConfigClient(cache,
                adminReturning(() -> {
                    throw new IllegalStateException("admin 不可达");
                }), upstream, properties);

        assertThat(warmed.current().channels()).as("admin 挂了也要继续用陈旧快照（决策 6）")
                .extracting(ChannelDescriptor::id).containsExactly(66L);
    }

    @Test
    void noSnapshotAnywhereFallsBackToTheLegacySingleChannel() {
        when(values.get(anyString())).thenReturn(null);

        ConfigSnapshot fallback = client(adminReturning(Optional::empty)).current();

        assertThat(fallback.channels()).extracting(ChannelDescriptor::id).containsExactly(LegacyChannel.ID);
        assertThat(fallback.defaultModel()).isEqualTo("legacy-model");
        assertThat(fallback.channelsSupporting("legacy-model")).hasSize(1);
    }

    @Test
    void emptySnapshotFromAdminStillAllowsTheLegacyFallback() {
        when(values.get(anyString())).thenReturn(null);

        ConfigSnapshot fallback = client(adminReturning(() -> Optional.of(ConfigSnapshot.empty()))).current();

        assertThat(fallback.channels()).extracting(ChannelDescriptor::id).containsExactly(LegacyChannel.ID);
    }

    /**
     * **缓存击穿防护**（§6.3 的 singleflight）：16 个并发同时 miss 时，admin 只应被调用一次。
     * 去掉 singleflight 会让 admin 被调用多次 —— 在一个「配置回源是同步阻塞」的系统里，
     * 那就是把一次缓存失效放大成一次对控制面的小规模雪崩。
     */
    @Test
    void concurrentMissesCollapseIntoASingleAdminCall() throws Exception {
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger adminCalls = new AtomicInteger();
        ConfigClient client = client(adminReturning(() -> {
            adminCalls.incrementAndGet();
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(snapshot(1L, 77L));
        }));
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    assertThat(client.current().channels()).isNotEmpty();
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(adminCalls).as("并发 miss 必须合并成一次回源").hasValue(1);
    }

    @Test
    void legacyFallbackIsNotCachedAsARealSnapshot() {
        when(values.get(anyString())).thenReturn(null);

        client(adminReturning(Optional::empty)).current();

        verify(values, never()).set(eq(ConfigCache.REDIS_KEY), anyString(), any(Duration.class));
    }

    @Test
    void invalidateDropsTheLocalLayerOnly() {
        when(values.get(anyString())).thenReturn(null);
        ConfigClient client = client(adminReturning(() -> Optional.of(snapshot(1L, 99L))));
        client.current();

        client.invalidate();
        client.current();

        verify(values, atLeast(2)).get(ConfigCache.REDIS_KEY);
    }
}
```

> **`AdminClient` 的 lambda 限制**：本任务是第一次需要在测试里覆写 `configSnapshot()`，而
> `AdminClient` 是 `@FunctionalInterface`（`resolve` 是唯一抽象方法）。因此测试里用**匿名类**而不是 lambda。
> 如果实施者发现「lambda + default 方法」在别处不能编译，**正确做法**是把那些 lambda 改成匿名类或
> 局部类，**不要**把 `configSnapshot()` 改成抽象方法（那会强迫所有既有替身实现它，属于不必要的破坏）。

- [ ] **Step 2: 运行，确认失败**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=ConfigCacheTest"
```

预期：`BUILD FAILURE` + `cannot find symbol: class ConfigCache`。

- [ ] **Step 3: 写 `GatewayConfigProperties` 与 `ConfigCache`**

创建 `aihub-gateway/src/main/java/com/aihub/gateway/config/GatewayConfigProperties.java`：

```java
package com.aihub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code aihub.config.*}：三级配置读取与两级缓存的参数（设计文档 §6.3）。
 *
 * @param localTtl                Caffeine 本地缓存 TTL（§6.3 明文要求 30 秒兜底）
 * @param snapshotTtl             Redis 共享缓存 TTL（spec 没定；取 10 分钟）
 * @param maxLocalSnapshotSources 本地缓存的来源数上界（当前恒为 1，保留是为了让容量在配置里可见）
 */
@ConfigurationProperties(prefix = "aihub.config")
public record GatewayConfigProperties(@DefaultValue("30s") Duration localTtl,
                                      @DefaultValue("10m") Duration snapshotTtl,
                                      @DefaultValue("300") int maxLocalSnapshotSources) {
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigCache.java`：

```java
package com.aihub.gateway.config;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ConfigSnapshotCodec;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

/**
 * 两级缓存的存储层（§6.3）：Caffeine（本机，30s TTL）+ Redis（跨实例共享）。**不含回源逻辑**
 * —— 回源、singleflight 与版本比对都在 {@link ConfigClient} 里，这样本类只关心「怎么存取」。
 *
 * <p>本地只有**一个** key（整个快照是一份文档），因此 Caffeine 的 `maximumSize` 只是形式上的
 * 兜底；真正的失效手段是 TTL 与 {@link #invalidateLocal()}。
 *
 * <p>Redis 侧用 {@link ConfigSnapshotCodec} 的分隔符载荷（决策 4），而不是 JSON：
 * 「缓存载荷」与「本地载荷」共用一份编解码只需维护一个转义器。**这不是跨服务契约**
 * （admin 不读它，admin 只发 JSON）。
 *
 * <p>所有 Redis 调用都吞异常：Redis 挂掉只是「二级缓存不可用」，一级与三级照常工作。
 */
public class ConfigCache {

    private static final Logger log = LoggerFactory.getLogger(ConfigCache.class);

    /** Redis 里快照的唯一键。 */
    public static final String REDIS_KEY = "aihub:config:snapshot";

    private final StringRedisTemplate redis;
    private final GatewayConfigProperties properties;
    private final Cache<String, ConfigSnapshot> local;

    private volatile boolean redisUsable = true;

    public ConfigCache(StringRedisTemplate redis, GatewayConfigProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.local = Caffeine.newBuilder()
                .maximumSize(Math.max(1, properties.maxLocalSnapshotSources()))
                .expireAfterWrite(properties.localTtl())
                .build();
    }

    public Optional<ConfigSnapshot> local() {
        return Optional.ofNullable(local.getIfPresent(REDIS_KEY));
    }

    public void putLocal(ConfigSnapshot snapshot) {
        local.put(REDIS_KEY, snapshot);
    }

    public void invalidateLocal() {
        local.invalidateAll();
    }

    /** 二级读取；Redis 不可用或载荷畸形都返回空（调用方回源）。 */
    public Optional<ConfigSnapshot> readRedis() {
        try {
            ConfigSnapshot decoded = ConfigSnapshotCodec.decode(redis.opsForValue().get(REDIS_KEY));
            redisUsable = true;
            return Optional.ofNullable(decoded);
        } catch (RuntimeException e) {
            if (redisUsable) {
                log.warn("读取配置快照缓存失败（降级为直接回源 admin）: {}", e.toString());
            }
            redisUsable = false;
            return Optional.empty();
        }
    }

    /** 二级写入；失败只记日志（缓存是可丢的派生数据）。 */
    public void writeRedis(ConfigSnapshot snapshot) {
        try {
            redis.opsForValue().set(REDIS_KEY, ConfigSnapshotCodec.encode(snapshot), properties.snapshotTtl());
            redisUsable = true;
        } catch (RuntimeException e) {
            log.warn("写入配置快照缓存失败，忽略: {}", e.toString());
            redisUsable = false;
        }
    }

    /** 最近一次 Redis 访问是否成功（指标与日志用）。 */
    public boolean redisAvailable() {
        return redisUsable;
    }
}
```

- [ ] **Step 4: 改 `AdminClient`（精确改动）**

在 `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java` 里做**四处**修改。

**（a）补 import**：

```java
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.RatePolicy;

import java.util.ArrayList;
import java.util.List;
```

**（b）在 `RESOLVE_PATH` 之后加常量与接口方法**：

```java
    /**
     * 配置快照路径。与 {@code RESOLVE_PATH} 一样**必须是应用内路径**（不含 context path）：
     * admin 的 {@code InternalAuthFilter} 用 {@code UrlPathHelper.getPathWithinApplication} 验签。
     */
    String CONFIG_SNAPSHOT_PATH = "/internal/config/snapshot";

    /**
     * 拉取配置快照（渠道 + 路由 + 限流策略 + 版本号）。
     *
     * <p>**默认实现返回空**：这样所有既有的替换实现（测试里的 `keyHash -> Mono.just(...)`）
     * 不必改一行就仍然编译通过；真实实现见 {@link Http}。
     * 空 {@link Optional} 的语义是「控制面拿不到快照」，调用方据此走降级（决策 6）。
     */
    default Mono<Optional<ConfigSnapshot>> configSnapshot() {
        return Mono.just(Optional.empty());
    }
```

**（c）在 `Http` 类里实现 `configSnapshot()`**（放在 `resolve` 方法之后）：

```java
        @Override
        public Mono<Optional<ConfigSnapshot>> configSnapshot() {
            // 与 resolve 同一套 fail-closed 纪律：签名/网络/非 2xx/畸形响应一律折算成「没有快照」，
            // 由配置层决定继续用陈旧快照还是回落到遗留单渠道。绝不抛到请求路径上。
            return Mono.defer(() -> {
                String timestamp = String.valueOf(Instant.now().getEpochSecond());
                String signature = InternalHmac.sign(internalSecret, timestamp, "GET", CONFIG_SNAPSHOT_PATH);

                return webClient.get()
                        .uri(CONFIG_SNAPSHOT_PATH)
                        .header("X-Internal-Timestamp", timestamp)
                        .header("X-Internal-Signature", signature)
                        .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                                .map(body -> parseSnapshot(response.statusCode().value(), body)));
            }).onErrorResume(ex -> {
                log.error("admin 配置快照拉取失败（传输层异常），本次用缓存/遗留渠道继续服务: {}", ex.toString());
                return Mono.just(Optional.empty());
            });
        }

        /** 非 2xx / 缺 {@code data} / 字段畸形都折算成「没有快照」。 */
        static Optional<ConfigSnapshot> parseSnapshot(int status, String body) {
            if (status < 200 || status >= 300) {
                log.error("admin 配置快照拉取失败（HTTP {}），本次用缓存/遗留渠道继续服务。响应体: {}",
                        status, body);
                return Optional.empty();
            }
            try {
                JsonNode data = MAPPER.readTree(body).path("data");
                if (data.isMissingNode() || data.isNull()) {
                    log.debug("admin 配置快照：HTTP {} 响应无 data，按「没有快照」处理", status);
                    return Optional.empty();
                }
                long version = data.path("version").asLong(0L);
                long generatedAt = data.path("generatedAtEpochMilli").asLong(0L);
                String defaultModel = data.path("defaultModel").isTextual()
                        ? data.get("defaultModel").asText() : null;

                List<ChannelDescriptor> channels = new ArrayList<>();
                for (JsonNode node : data.path("channels")) {
                    channels.add(new ChannelDescriptor(
                            node.path("id").asLong(), node.path("name").asText(null),
                            node.path("baseUrl").asText(null), node.path("apiKeyCipher").asText(null),
                            node.path("keyVersion").asInt(0), node.path("timeoutMs").asInt(0),
                            node.path("status").asText(null), node.path("weight").asInt(0),
                            node.path("priority").asInt(0)));
                }
                List<ModelRouteDescriptor> routes = new ArrayList<>();
                for (JsonNode node : data.path("routes")) {
                    routes.add(new ModelRouteDescriptor(
                            node.path("modelName").asText(null), node.path("channelId").asLong(),
                            node.path("weight").asInt(0), node.path("priority").asInt(0),
                            node.path("status").asText(null)));
                }
                List<RatePolicy> policies = new ArrayList<>();
                for (JsonNode node : data.path("ratePolicies")) {
                    policies.add(new RatePolicy(
                            node.path("tenantId").isNumber() ? node.get("tenantId").asLong() : null,
                            node.path("apiKeyId").isNumber() ? node.get("apiKeyId").asLong() : null,
                            node.path("qps").asInt(0), node.path("burst").asInt(0)));
                }
                return Optional.of(new ConfigSnapshot(version, generatedAt, channels, routes, policies, defaultModel));
            } catch (Exception e) {
                log.error("admin 配置快照响应畸形，本次用缓存/遗留渠道继续服务: {}", e.toString());
                return Optional.empty();
            }
        }
```

**（d）`parse(int status, String body)` 不要动**（决策 14）：`ApiKeyView` 的第 6 个分量与这一行的
`apiKeyId` 读取**已经在 Task 2 落地**（契约与其生产者/消费者同一个提交，见全局约束）。本任务只做
(a)(b)(c)。若你在这里看到 `parse` 只有 5 个参数，那说明 Task 2 没做完 —— **回去补 Task 2，不要在这里顺手改**
（那会重新造出一个跨任务占位）。

- [ ] **Step 5: 写 `ConfigClient` 与 `ConfigConfig`**

创建 `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigClient.java`：

```java
package com.aihub.gateway.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置读取的**唯一入口**（§6.3 三级读取 + 两级缓存 + singleflight + 版本比对）。
 *
 * <p><b>{@link #current()} 永不返回 null、永不抛异常</b>，最差返回「遗留单渠道」合成的快照
 * （决策 6）。理由同 M1：「缓存/控制面故障绝不能让数据面整体不可用」。
 *
 * <p><b>singleflight</b>：并发 miss 时只有一个线程真正回源，其余等待同一个 {@link Mono}
 * （用 {@link AtomicReference} 持有「正在飞的 Mono」并用 {@code Mono.cache()} 让多个订阅者共享）。
 *
 * <p><b>线程模型</b>：{@link #current()} 会被 event loop（过滤器/控制器）调用，而
 * {@code AdminClient} 是 WebClient（异步，不阻塞）；Redis 侧只有一次同步 get/set，因此这里
 * **不引入额外的调度器** —— 与 M1 的 {@code ApiKeyResolver} 不同，本类的 Redis 调用只发生在
 * 「本地缓存 miss」时（30 秒一次那一档），代价可接受。若将来把这里改回逐请求调用，
 * 必须像 {@code ApiKeyResolver} 那样切到 {@code boundedElastic}。
 */
public class ConfigClient {

    private static final Logger log = LoggerFactory.getLogger(ConfigClient.class);

    private final ConfigCache cache;
    private final AdminClient adminClient;
    private final UpstreamProperties upstream;
    private final GatewayConfigProperties properties;
    private final AtomicReference<Mono<ConfigSnapshot>> inFlight = new AtomicReference<>();

    public ConfigClient(ConfigCache cache, AdminClient adminClient, UpstreamProperties upstream,
                        GatewayConfigProperties properties) {
        this.cache = cache;
        this.adminClient = adminClient;
        this.upstream = upstream;
        this.properties = properties;
    }

    /** 当前生效的快照。三级顺序 + 版本比对 + 兜底，**永不 null / 永不抛**。 */
    public ConfigSnapshot current() {
        ConfigSnapshot local = cache.local().orElse(null);
        ConfigSnapshot fromRedis = cache.readRedis().orElse(null);
        if (fromRedis != null && (local == null || fromRedis.version() > local.version())) {
            cache.putLocal(fromRedis);
            return fromRedis;
        }
        if (local != null) {
            return local;
        }
        ConfigSnapshot loaded = refreshBlocking();
        return loaded != null ? loaded : legacyFallback();
    }

    /** 主动回源（singleflight）并回填两级缓存。返回空表示「这次没拿到真快照」。 */
    public Mono<ConfigSnapshot> refresh() {
        Mono<ConfigSnapshot> existing = inFlight.get();
        if (existing != null) {
            return existing;
        }
        Mono<ConfigSnapshot> fresh = adminClient.configSnapshot()
                .map(maybe -> maybe.orElse(null))
                .flatMap(snapshot -> {
                    if (snapshot == null) {
                        return Mono.empty();
                    }
                    return Mono.fromRunnable(() -> {
                        cache.putLocal(snapshot);
                        cache.writeRedis(snapshot);
                    }).thenReturn(snapshot);
                })
                .onErrorResume(ex -> {
                    log.warn("配置快照回源失败，继续使用已有快照: {}", ex.toString());
                    return Mono.empty();
                })
                .doFinally(signal -> inFlight.compareAndSet(existing, null))
                .cache();
        if (inFlight.compareAndSet(null, fresh)) {
            return fresh;
        }
        Mono<ConfigSnapshot> winner = inFlight.get();
        return winner == null ? fresh : winner;
    }

    /** 同步版回源（供 {@link #current()} 在请求路径上用）：拿不到就返回 null。 */
    private ConfigSnapshot refreshBlocking() {
        try {
            return refresh().block(Duration.ofSeconds(5));
        } catch (RuntimeException e) {
            log.warn("配置快照回源等待失败，继续使用已有快照: {}", e.toString());
            return null;
        }
    }

    /** 失效本地缓存（未来 Pub/Sub 监听器的唯一调用点；M3 没有发布方，见决策 16）。 */
    public void invalidate() {
        cache.invalidateLocal();
    }

    /**
     * 冷启动 + admin 不可达时的兜底：把 {@code aihub.upstream.*} 合成一条单渠道快照。
     * **不写任何缓存** —— 否则 admin 恢复后，这份兜底会以「真快照」的身份留在 Redis 里。
     */
    public ConfigSnapshot legacyFallback() {
        ChannelDescriptor legacy = legacyChannel();
        String model = upstream.defaultModel();
        List<ModelRouteDescriptor> routes = model == null || model.isBlank()
                ? List.of()
                : List.of(new ModelRouteDescriptor(model, legacy.id(), 100, 0, ModelRouteDescriptor.STATUS_ACTIVE));
        return new ConfigSnapshot(0L, 0L, List.of(legacy), routes, List.of(), model);
    }

    public ChannelDescriptor legacyChannel() {
        return LegacyChannel.of(upstream);
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigConfig.java`：

```java
package com.aihub.gateway.config;

import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 配置层的装配点。**注意调用者可能没有配置可读**：所有 bean 都必须能在
 * 「主密钥为空 / Redis 不可用 / admin 不可达」的情况下正常创建（配置缺失不允许拦住网关启动）。
 */
@Configuration
@EnableConfigurationProperties(GatewayConfigProperties.class)
public class ConfigConfig {

    @Bean
    public ConfigCache configCache(StringRedisTemplate redis, GatewayConfigProperties properties) {
        return new ConfigCache(redis, properties);
    }

    @Bean
    public ConfigClient configClient(ConfigCache cache, AdminClient adminClient, UpstreamProperties upstream,
                                     GatewayConfigProperties properties) {
        return new ConfigClient(cache, adminClient, upstream, properties);
    }
}
```

- [ ] **Step 6: 改 `application.yml`**

在 `aihub-gateway/src/main/resources/application.yml` 的 `aihub:` 块里，**在 `auth:` 之前**插入：

```yaml
  # 渠道主密钥：**只来自环境变量**，不落库、不进镜像（设计文档 §6.1）。
  # 格式：v1:<base64 32 字节>[,v2:<base64 32 字节>]；轮换期间同时放旧、新两把。
  # 生成方法见 .env.example。
  channel:
    master-key: ${AIHUB_CHANNEL_MASTER_KEY:}
  config:
    local-ttl: 30s
    snapshot-ttl: 10m
    max-local-snapshot-sources: 300
```

- [ ] **Step 7: 运行测试，确认全绿**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=ConfigCacheTest"
```

预期：`Tests run: 11, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 8: 跑网关全部测试（顺带确认 `AdminClient` 的改动没打破既有契约测试）**

```powershell
mvn -B -pl aihub-gateway test
```

预期：`Tests run: 201, Failures: 0, Errors: 0`（Task 6 的 190 + 11）。若 `AdminClientHttpTest` 变红，检查你是不是把 `resolve` 的签名路径或 header 名改掉了（**只允许新增 `configSnapshot`，不得改动 `resolve`**）。

- [ ] **Step 9: 提交**

```powershell
git add aihub-gateway/src/main/java/com/aihub/gateway/config aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java aihub-gateway/src/main/resources/application.yml aihub-gateway/src/test/java/com/aihub/gateway/config
git commit -m "feat: read the config snapshot through three levels and two cache layers"
```

**验收标准**
1. 三级顺序可观测：本地命中**零 I/O**（`redis.opsForValue()` 一次都没被调用）；本地未命中走 Redis；Redis 未命中回源 admin 并**同时**回填本地与 Redis（`set` 带 `snapshotTtl`）。
2. 版本比对生效：`local.version < redis.version` 时丢弃本地（§6.3）。
3. Redis 抛异常 → 回源 admin；admin 抛异常/返回空 → 继续用陈旧本地快照；**任何情况下 `current()` 都不返回 null**，最差是 `LegacyChannel.ID` 的单渠道快照，且**不写缓存**。
4. 16 个并发 miss 只触发**一次** admin 调用（singleflight）。
5. `AdminClient` 只新增 `GET /internal/config/snapshot`（方法 `GET`、应用内路径、`X-Internal-Timestamp` + `X-Internal-Signature`），`resolve` 的请求形状逐字节未变。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-gateway test -am "-Dtest=ConfigCacheTest"` | `Tests run: 11, Failures: 0, Errors: 0` + `BUILD SUCCESS` |
| `mvn -B -pl aihub-gateway test` | `Tests run: 201, Failures: 0, Errors: 0` + `BUILD SUCCESS` |

---

## Task 8: 渠道级 `WebClient` 工厂与本地解密

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChannelKeyDecryptor.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamClientFactory.java`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamClientConfig.java`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamProperties.java`（只改 javadoc）
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/relay/ChannelKeyDecryptorTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/upstream/UpstreamClientFactoryTest.java`

**Interfaces:**
- Consumes：Task 1（`AesGcmChannelCipher` / `ChannelKeyRegistry`）、Task 2（`ChannelDescriptor`）、Task 6（`LegacyChannel`）、既有 `UpstreamProperties`。
- Produces：
  - `ChannelKeyDecryptor`：构造器 `ChannelKeyDecryptor(AesGcmChannelCipher cipher, UpstreamProperties upstream)`；`Optional<String> upstreamKey(ChannelDescriptor channel)`；`boolean canServe(ChannelDescriptor channel)`。
  - `UpstreamClientFactory`：构造器 `UpstreamClientFactory(UpstreamProperties legacyProperties)`；`WebClient forChannel(ChannelDescriptor channel, boolean streaming)`；`WebClient legacy()`；`int cachedClients()`。**缓存键是 `(baseUrl, timeoutMs, streaming)`**（不是 channelId：改配置后应立刻用新参数）。
  - `UpstreamClientConfig` 仍然只提供一个 `WebClient` bean：`upstreamWebClient(UpstreamClientFactory factory)` = `factory.legacy()`（**保持既有 bean 名与语义**，既有 4 个依赖它的测试类不受影响）。
  - **超时语义**：连接超时 5 秒；**非流式**客户端 `responseTimeout = channel.timeoutMs()`（`channel.timeout_ms` 第一次真正生效）；**流式**客户端**不设** `responseTimeout`（SSE 可以合法地跑几十分钟；M1/M2 的全局客户端设了 120 秒，会在长回答中途掐断流 —— M3 顺手修掉这个隐患）。

**测试用例清单**（`ChannelKeyDecryptorTest` 6 + `UpstreamClientFactoryTest` 5 = 11 条）：

| 类 | 用例 | 钉住什么 |
|---|---|---|
| `ChannelKeyDecryptorTest`（6） | `decryptsAChannelKeyWithTheConfiguredMasterKey` / `legacyChannelUsesTheConfiguredUpstreamApiKey` / `legacyChannelWithNoConfiguredKeyYieldsEmptyString` / `undecryptableCipherYieldsEmptyInsteadOfThrowing` / `neverExposesThePlaintext` / `canServeIsFalseForAnUndecryptableChannel` | 解密位置与失败语义 |
| `UpstreamClientFactoryTest`（5） | `legacyClientCarriesTheConfiguredBearerToken` / `channelClientCarriesNoDefaultBearerToken` / `streamingClientHasNoResponseTimeout` / `clientsAreCachedByBaseUrlTimeoutAndMode` / `unreachableChannelFailsFast` | 工厂语义与超时 |

- [ ] **Step 1: 写 `ChannelKeyDecryptorTest` 与 `ChannelKeyDecryptor`**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/relay/ChannelKeyDecryptorTest.java`：

```java
package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解密发生在**网关本地**（设计文档 §6.1：admin 只下发密文，gateway 持主密钥在本地解密），
 * 因此这个类就是「明文密钥不跨越网络」这句话的落点。
 *
 * <p>失败语义：解不开的渠道必须变成**空 Optional**（调用方跳过它去试下一个候选），
 * 而不是抛异常 —— 否则一次主密钥轮换事故会变成全量 500。
 */
class ChannelKeyDecryptorTest {

    private static final String SYNTHETIC_PLAINTEXT = "sk-channel-plaintext-synthetic";

    private static String masterKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 1);
        }
        return "v1:" + Base64.getEncoder().encodeToString(key);
    }

    private static AesGcmChannelCipher cipher() {
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey()));
    }

    private static ChannelDescriptor channel(String cipherText) {
        return new ChannelDescriptor(11L, "primary", "https://primary.example.com", cipherText, 1, 60_000,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    private static UpstreamProperties upstream(String apiKey) {
        return new UpstreamProperties("http://127.0.0.1:11434", apiKey, "legacy-model");
    }

    @Test
    void decryptsAChannelKeyWithTheConfiguredMasterKey() {
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(cipher(), upstream(null));

        assertThat(decryptor.upstreamKey(channel(cipher().encrypt(SYNTHETIC_PLAINTEXT))))
                .contains(SYNTHETIC_PLAINTEXT);
    }

    @Test
    void legacyChannelUsesTheConfiguredUpstreamApiKey() {
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(cipher(), upstream("legacy-configured-key"));

        assertThat(decryptor.upstreamKey(LegacyChannel.of(upstream("legacy-configured-key"))))
                .contains("legacy-configured-key");
    }

    /** 本地 Ollama 这类无密钥上游：**空字符串的语义是「不注入任何 Authorization」**，不是失败。 */
    @Test
    void legacyChannelWithNoConfiguredKeyYieldsEmptyString() {
        UpstreamProperties legacy = upstream("");
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(cipher(), legacy);

        assertThat(decryptor.upstreamKey(LegacyChannel.of(legacy))).contains("");
        assertThat(decryptor.canServe(LegacyChannel.of(legacy))).isTrue();
    }

    @Test
    void undecryptableCipherYieldsEmptyInsteadOfThrowing() {
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(cipher(), upstream(null));

        assertThat(decryptor.upstreamKey(channel("v9:QUJD"))).isEmpty();
        assertThat(decryptor.upstreamKey(channel("garbage"))).isEmpty();
        assertThat(decryptor.upstreamKey(channel(null))).isEmpty();
        assertThat(decryptor.canServe(channel("v9:QUJD"))).isFalse();
    }

    /**
     * 明文渠道密钥**绝不允许**出现在异常消息、日志或 {@code toString} 里。
     * 这条断言针对的是「实现为了方便调试把 key 塞进消息」这种最常见的泄漏方式。
     * <p>注意：解不开的情形只打 WARN，日志内容由人工复核（这里只能钉住 toString 与返回值）。
     */
    @Test
    void neverExposesThePlaintext() {
        ChannelKeyDecryptor broken = new ChannelKeyDecryptor(
                new AesGcmChannelCipher(ChannelKeyRegistry.parse("")), upstream(null));

        assertThat(broken.toString()).doesNotContain(SYNTHETIC_PLAINTEXT);
        assertThat(String.valueOf(broken.upstreamKey(channel("v1:QUJD")))).doesNotContain(SYNTHETIC_PLAINTEXT);
    }

    @Test
    void canServeIsFalseForAnUndecryptableChannel() {
        ChannelKeyDecryptor decryptor = new ChannelKeyDecryptor(
                new AesGcmChannelCipher(ChannelKeyRegistry.parse("")), upstream(null));

        assertThat(decryptor.canServe(channel("v1:QUJD"))).isFalse();
        assertThat(decryptor.canServe(null)).isFalse();
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChannelKeyDecryptor.java`：

```java
package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 把 {@link ChannelDescriptor#apiKeyCipher()} 解成本次请求要用的明文渠道密钥。
 *
 * <p><b>设计文档 §6.1 的落点</b>：admin 只把密文放进快照，**主密钥在网关本地**，
 * 明文既不跨网络也不进任何 admin 侧的可观测链路。
 *
 * <p><b>失败语义</b>：解不开 → 空 {@link Optional}（路由层据此跳过这条候选）。**绝不抛异常**：
 * 一次主密钥配置事故不该把全部请求变成 500，而应该退化成「这条渠道暂时不可用」。
 * **空字符串是有意义的值**（本地 Ollama 不需要密钥 → 不注入 Authorization），
 * 因此这里用 {@code Optional.of("")} 而不是 {@code Optional.empty()} 表达「有渠道但无需密钥」。
 *
 * <p><b>永不打印明文</b>：本类不打日志内容里的密钥；连 {@link #toString()} 都不暴露字段。
 */
public class ChannelKeyDecryptor {

    private static final Logger log = LoggerFactory.getLogger(ChannelKeyDecryptor.class);

    private final AesGcmChannelCipher cipher;
    private final UpstreamProperties legacyProperties;

    public ChannelKeyDecryptor(AesGcmChannelCipher cipher, UpstreamProperties legacyProperties) {
        this.cipher = cipher;
        this.legacyProperties = legacyProperties;
    }

    /** @return 该渠道要用的明文密钥；{@code Optional.empty()} 表示「这条渠道这次不可用」 */
    public Optional<String> upstreamKey(ChannelDescriptor channel) {
        if (channel == null) {
            return Optional.empty();
        }
        if (LegacyChannel.isLegacy(channel.id())) {
            String configured = legacyProperties.apiKey();
            return Optional.of(configured == null ? "" : configured);
        }
        if (channel.apiKeyCipher() == null || channel.apiKeyCipher().isBlank()) {
            // 没有密文的真实渠道 = 配置不完整（渠道行要求 api_key_cipher NOT NULL）。
            log.warn("渠道 {} 没有密钥密文，跳过（渠道名: {}）", channel.id(), channel.name());
            return Optional.empty();
        }
        Optional<String> plaintext = cipher.decrypt(channel.apiKeyCipher());
        if (plaintext.isEmpty()) {
            // 只打版本标签与渠道 id/名，**绝不打密文或明文**。
            log.warn("渠道 {}（{}）的密钥解不开（密文版本 {}）；请检查 aihub.channel.master-key 是否包含该版本",
                    channel.id(), channel.name(), AesGcmChannelCipher.labelOf(channel.apiKeyCipher()));
        }
        return plaintext;
    }

    public boolean canServe(ChannelDescriptor channel) {
        return upstreamKey(channel).isPresent();
    }

    /** 刻意不暴露任何字段：本对象持有的是「能解密所有渠道密钥」的能力。 */
    @Override
    public String toString() {
        return "ChannelKeyDecryptor[主密钥已加载]";
    }
}
```

- [ ] **Step 2: 写 `UpstreamClientFactoryTest` 与 `UpstreamClientFactory`**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/upstream/UpstreamClientFactoryTest.java`：

```java
package com.aihub.gateway.upstream;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.gateway.testsupport.FakeUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道级客户端工厂：每个渠道有自己的 base-url 与超时。
 *
 * <p>最关键的一条是**流式客户端不设 {@code responseTimeout}**：SSE 可以合法地跑很久
 * （M1/M2 的全局客户端设了 120 秒响应超时，长回答会在中途被掐断 —— M3 顺手把这个隐患修掉）。
 */
class UpstreamClientFactoryTest {

    private static FakeUpstream upstream;

    @BeforeAll
    static void startUpstream() {
        upstream = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    private static UpstreamProperties legacy(String apiKey) {
        return new UpstreamProperties(upstream.baseUrl(), apiKey, "legacy-model");
    }

    private static ChannelDescriptor channel(long id, String baseUrl, int timeoutMs) {
        return new ChannelDescriptor(id, "ch-" + id, baseUrl, "v1:QUJD", 1, timeoutMs,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    @Test
    void legacyClientCarriesTheConfiguredBearerToken() {
        WebClient client = new UpstreamClientFactory(legacy("legacy-bearer")).legacy();

        upstream.enqueueJson(200, FakeUpstream.completionJson());
        client.post().uri("/v1/chat/completions").bodyValue("{}").retrieve().bodyToMono(String.class).block();

        assertThat(upstream.lastRequest().headers()).containsEntry("authorization", "Bearer legacy-bearer");
    }

    /** 渠道密钥由控制器**逐请求**注入（不同渠道不同密钥，不能挂在客户端上）。 */
    @Test
    void channelClientCarriesNoDefaultBearerToken() {
        WebClient client = new UpstreamClientFactory(legacy("legacy-bearer"))
                .forChannel(channel(11L, upstream.baseUrl(), 5_000), false);

        upstream.enqueueJson(200, FakeUpstream.completionJson());
        client.post().uri("/v1/chat/completions").bodyValue("{}").retrieve().bodyToMono(String.class).block();

        assertThat(upstream.lastRequest().headers()).doesNotContainKey("authorization");
    }

    @Test
    void streamingClientHasNoResponseTimeout() {
        // 渠道超时只有 50ms；流式客户端若不设响应超时，整段 SSE 仍然读得完。
        WebClient streaming = new UpstreamClientFactory(legacy(null))
                .forChannel(channel(11L, upstream.baseUrl(), 50), true);

        upstream.enqueueSse(FakeUpstream.sseFrames());
        String body = streaming.post().uri("/v1/chat/completions").bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));

        assertThat(body).contains("data: [DONE]");
    }

    @Test
    void clientsAreCachedByBaseUrlTimeoutAndMode() {
        UpstreamClientFactory factory = new UpstreamClientFactory(legacy(null));

        WebClient first = factory.forChannel(channel(11L, upstream.baseUrl(), 5_000), false);
        WebClient second = factory.forChannel(channel(11L, upstream.baseUrl(), 5_000), false);
        WebClient differentTimeout = factory.forChannel(channel(11L, upstream.baseUrl(), 9_000), false);
        WebClient streaming = factory.forChannel(channel(11L, upstream.baseUrl(), 5_000), true);

        assertThat(first).isSameAs(second);
        assertThat(differentTimeout).isNotSameAs(first);
        assertThat(streaming).isNotSameAs(first);
        assertThat(factory.cachedClients()).isEqualTo(3);
    }

    /** 一个立刻超时的通道（连不上）必须抛 WebClientRequestException，而不是挂住。 */
    @Test
    void unreachableChannelFailsFast() {
        WebClient client = new UpstreamClientFactory(legacy(null))
                .forChannel(channel(12L, "http://127.0.0.1:1", 500), false);

        assertThatThrownBy(() -> client.post().uri("/v1/chat/completions").bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)))
                .isInstanceOf(WebClientRequestException.class);
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamClientFactory.java`：

```java
package com.aihub.gateway.upstream;

import com.aihub.common.config.ChannelDescriptor;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按渠道构造 {@link WebClient}。渠道之间只有两件事不同：base-url 与超时 —— 密钥由控制器
 * **逐请求**注入（一个客户端实例可能被多个渠道复用，把密钥挂在客户端上必然串号）。
 *
 * <p><b>流式与非流式是两个客户端</b>：
 * <ul>
 *   <li>非流式：{@code responseTimeout = channel.timeout_ms}（这是 {@code timeout_ms} 列第一次真正生效）；</li>
 *   <li>流式：**不设响应超时**。SSE 可以合法地跑几十分钟，响应级超时会在长回答中途把流掐断 ——
 *       M1/M2 的全局客户端设了 120 秒，M3 顺手把这个隐患修掉（连接超时仍然保留 5 秒）。</li>
 * </ul>
 *
 * <p>缓存键是 {@code (baseUrl, timeoutMs, streaming)}：**不是 channelId**。渠道改了 base-url 或超时后
 * 应当立刻用新参数（否则一次配置变更要等重启才生效，与「配置热生效」的目标相反）。
 */
public class UpstreamClientFactory {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final UpstreamProperties legacyProperties;
    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    public UpstreamClientFactory(UpstreamProperties legacyProperties) {
        this.legacyProperties = legacyProperties;
    }

    /** 渠道专用客户端。**不设默认 Authorization**（由控制器按本次渠道注入）。 */
    public WebClient forChannel(ChannelDescriptor channel, boolean streaming) {
        String key = "channel|" + channel.baseUrl() + "|" + channel.timeoutMs() + "|" + streaming;
        return clients.computeIfAbsent(key, ignored -> build(channel.baseUrl(),
                streaming ? null : Duration.ofMillis(Math.max(channel.timeoutMs(), 1)), null));
    }

    /**
     * M1 形状的遗留客户端：带 {@code aihub.upstream.api-key} 的默认 Authorization。
     * **保留它**是为了让单渠道配置（含既有测试）继续按原样工作 —— 否则冷启动兜底路径
     * 会丢掉上游密钥。
     */
    public WebClient legacy() {
        String key = "legacy|" + legacyProperties.baseUrl() + "|" + legacyProperties.apiKey();
        return clients.computeIfAbsent(key, ignored -> build(legacyProperties.baseUrl(),
                Duration.ofSeconds(120), legacyProperties.apiKey()));
    }

    public int cachedClients() {
        return clients.size();
    }

    private static WebClient build(String baseUrl, Duration responseTimeout, String defaultApiKey) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS);
        if (responseTimeout != null) {
            httpClient = httpClient.responseTimeout(responseTimeout);
        }
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (StringUtils.hasText(defaultApiKey)) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + defaultApiKey);
        }
        return builder.build();
    }
}
```

- [ ] **Step 3: 改 `UpstreamClientConfig`（保持 bean 名与语义）**

把 `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamClientConfig.java` 整个替换为：

```java
package com.aihub.gateway.upstream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 上游客户端的装配点。
 *
 * <p>M1/M2 时这里直接构造唯一一个 {@code WebClient}；M3 起渠道是**多条**，构造逻辑搬到
 * {@link UpstreamClientFactory}（按渠道 base-url + 超时缓存）。这里保留的 bean 是
 * **遗留单渠道**的客户端（{@code factory.legacy()}）：它服务于「冷启动 + admin 不可达」的兜底
 * 路径与所有既有的单渠道测试，语义与 M1 完全相同（含 {@code aihub.upstream.api-key} 的默认头）。
 * **不要删掉它**：删掉会让兜底路径丢掉上游密钥，那是一次静默的功能退化。
 */
@Configuration
@EnableConfigurationProperties(UpstreamProperties.class)
public class UpstreamClientConfig {

    @Bean
    public UpstreamClientFactory upstreamClientFactory(UpstreamProperties properties) {
        return new UpstreamClientFactory(properties);
    }

    @Bean
    public WebClient upstreamWebClient(UpstreamClientFactory factory) {
        return factory.legacy();
    }
}
```

同时把 `UpstreamProperties` 的 javadoc 第一段改为：

```java
/**
 * 上游模型服务配置。**M3 起它只服务「遗留单渠道」**：真正的上游由配置快照里的
 * {@code ChannelDescriptor} 决定（多渠道、每渠道 base-url / 超时 / 密文密钥）。
 * 这组配置仍然是**冷启动 + admin 不可达时的兜底**（{@code LegacyChannel}），因此不能删。
 *
 * @param apiKey       上游自己的密钥；空串表示上游无需鉴权（例如本地 Ollama）
 * @param defaultModel 遗留单渠道场景下 {@code GET /v1/models} 回报的模型名
 */
```

- [ ] **Step 4: 补两个 `@Bean`（`ChannelKeyDecryptor` 与它的 cipher）**

在 `ConfigConfig` 里追加两个 bean（`AesGcmChannelCipher` 需要主密钥，因此在这里读配置）：

```java
    /**
     * 渠道密钥的解密器（**主密钥只在网关本地**，设计文档 §6.1）。
     * 主密钥为空时 {@link com.aihub.common.crypto.ChannelKeyRegistry} 是空表，
     * 所有真实渠道都会「解不开」并被路由跳过 —— 这是**可启动**的降级，不是启动失败
     * （与 admin 侧的「无主密钥拒绝加密」相反，理由见决策 3）。
     */
    @Bean
    public AesGcmChannelCipher channelCipher(
            @Value("${aihub.channel.master-key:}") String masterKey) {
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey));
    }

    @Bean
    public ChannelKeyDecryptor channelKeyDecryptor(AesGcmChannelCipher cipher, UpstreamProperties upstream) {
        return new ChannelKeyDecryptor(cipher, upstream);
    }
```

并补 import：`com.aihub.common.crypto.AesGcmChannelCipher`、`com.aihub.common.crypto.ChannelKeyRegistry`、
`com.aihub.gateway.relay.ChannelKeyDecryptor`、`org.springframework.beans.factory.annotation.Value`。

- [ ] **Step 5: 运行本任务的两个测试类**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=ChannelKeyDecryptorTest,UpstreamClientFactoryTest"
```

预期：`Tests run: 11, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 6: 跑网关全部测试并提交**

```powershell
mvn -B -pl aihub-gateway test
git add aihub-gateway/src/main/java/com/aihub/gateway/relay/ChannelKeyDecryptor.java aihub-gateway/src/main/java/com/aihub/gateway/upstream aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigConfig.java aihub-gateway/src/test/java/com/aihub/gateway/relay/ChannelKeyDecryptorTest.java aihub-gateway/src/test/java/com/aihub/gateway/upstream/UpstreamClientFactoryTest.java
git commit -m "feat: decrypt channel keys locally and build per-channel upstream clients"
```

预期：`Tests run: 212, Failures: 0, Errors: 0`（Task 7 的 201 + 11）。若 `UpstreamAuthorizationTest` 变红，说明你改坏的是「遗留客户端」那条路径（**不得**去掉 `aihub.upstream.api-key` 的默认头）。

**验收标准**
1. 渠道密钥**只在网关本地解密**：密文渠道返回解密后的明文；遗留渠道返回 `UpstreamProperties.apiKey()`（可能是空串，语义是「不注入 Authorization」）。
2. 解不开的渠道返回空 `Optional` 且**不抛异常**，WARN 里只有渠道 id/名与**版本标签**（无明文、无密文）。
3. 流式客户端**没有** `responseTimeout`（50ms 超时的渠道也能把整段 SSE 读完）；非流式客户端的 `responseTimeout` 等于 `channel.timeoutMs`。
4. `upstreamWebClient` bean 的语义与 M1 完全一致（带默认 Authorization），既有依赖它的测试类全部保持绿色。
5. 主密钥为空时网关**照常启动**，只是所有真实渠道「解不开」并被跳过（可启动的降级）。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-gateway test -am "-Dtest=ChannelKeyDecryptorTest,UpstreamClientFactoryTest"` | `Tests run: 11, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-gateway test` | `Tests run: 212, Failures: 0, Errors: 0` + `BUILD SUCCESS` |

---

## Task 9: 限流过滤器（429 + IETF 头 + 降级仍拒绝 + 自身故障放行）

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimitFilter.java`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/auth/ApiKeyAuthFilter.java`（把 `sha256(secret)` 写进 exchange 属性）
- Modify: `aihub-gateway/src/main/resources/application.yml`（`aihub.ratelimit.enabled`）
- Modify: `aihub-gateway/src/test/resources/application.properties`（测试默认关掉限流）
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/RateLimitFilterTest.java`

**Interfaces:**
- Consumes：Task 2（`ApiKeyView` 的数值 `apiKeyId`，决策 14 —— 本任务**直接读真值**，不需要任何占位或后续收口）、Task 4（`RateLimiter.acquire(long, Long, String)` / `RateLimitDecision`）、既有 `ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW` / `ATTRIBUTE_KEY_HASH`、`GatewayErrors.write(...)`。
- Produces：
  - `ApiKeyAuthFilter.ATTRIBUTE_KEY_HASH = "aihub.apiKeyHash"`（鉴权成功时写入）。
  - `RateLimitFilter`：`@Component @Order(Ordered.HIGHEST_PRECEDENCE + 150)`；常量 `LIMIT_HEADER = "ratelimit-limit"` / `REMAINING_HEADER = "ratelimit-remaining"` / `RETRY_AFTER_HEADER = "retry-after"` / `RETRY_AFTER_MS_HEADER = "retry-after-ms"`；构造器 `RateLimitFilter(RateLimiter limiter, boolean enabled, MeterRegistry registry)`。
  - **策略维度的取值**（决策 7 修订）：租户取 `ApiKeyView.tenantId()`，key 取 `ApiKeyView.apiKeyId()`（**数值主键**，决策 14 —— 该分量自 Task 2 起就在共享契约里，本任务直接读，**没有占位**）；没有 view 时 `tenantId=0` + `apiKeyId=null`（匿名桶只走租户级策略）。**桶 key 不变**：仍是 `(tenantId, sha256(secret))`。
  - **响应头**（放行与拒绝**都**加）：`RateLimit-Limit: "{limit}, {burst}"`、`RateLimit-Remaining`。拒绝时再加 `Retry-After`（秒，向上取整 ≥1）与 `Retry-After-MS`（毫秒）。
  - **429 错误体**：`GatewayErrors.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", "rate_limit_exceeded", msg)`。
  - **降级语义**：`decision.source() == LOCAL` 时**照常执行判定**（拒绝仍是 429），打一条**每分钟最多一次**的 WARN，并让 `aihub.ratelimit.degraded` 计数 +1。**绝不因为 Redis 故障而放行全部请求**（那是「无限流」，会把上游打挂），也绝不因为 Redis 故障而拒绝全部请求。
  - **自身故障必须放行**（fail-open）：`RateLimiter` 抛异常时 `chain.filter(exchange)`，并打 ERROR。

**测试用例清单**（`RateLimitFilterTest`，11 条）：

| 用例 | 钉住什么 |
|---|---|
| `allowsWhenUnderTheLimitAndAddsTheRateLimitHeaders` | 放行 + `RateLimit-Limit` / `RateLimit-Remaining` |
| `rejectsWith429AndTheOpenAiBodyWhenOverTheLimit` | 429 + `error.code == "rate_limit_exceeded"` + `error.type == "rate_limit_error"` + `param: null` |
| `rejectionCarriesRetryAfterInSecondsAndMilliseconds` | 250ms → `Retry-After: 1` 与 `Retry-After-MS: 250`，`RateLimit-Remaining: 0` |
| `usesTheTenantAndTheKeyHashAsTheBucketDimension` | 传进 `RateLimiter` 的是 `tenantId`、认证视图里的**数值** `apiKeyId` 与鉴权过滤器写下的哈希（三者都从 `ApiKeyView` / exchange 属性里真取，**没有占位** —— 契约在 Task 2 已就位） |
| `skipsWhenDisabled` | `enabled=false` 时完全不管（`acquire` 一次都没被调用） |
| `skipsNonV1Paths` | `/healthz` 不受限流影响 |
| `usesTheAnonymousBucketWhenThereIsNoApiKeyView` | 鉴权关闭时用 `tenantId=0` 且**仍然限流** |
| `stillEnforcesTheLocalBucketWhenRedisIsDegraded` | `source == LOCAL` 且拒绝 → **仍然回 429** |
| `degradedDecisionIsCounted` | `aihub.ratelimit.degraded` 计数 +1 |
| `filterOrderIsAfterAuthentication` | 读运行时 `@Order`，严格大于鉴权过滤器的值 |
| `unexpectedLimiterFailureFailsOpen` | `RateLimiter` 抛异常时**放行** |

- [ ] **Step 1: 改 `ApiKeyAuthFilter`：写下密钥哈希**

在 `aihub-gateway/src/main/java/com/aihub/gateway/auth/ApiKeyAuthFilter.java` 里：

**（a）在 `ATTRIBUTE_KEY_VIEW` 之后加常量**：

```java
    /**
     * 本次请求密钥的 SHA-256，存放位置。M3 的限流用它当桶的第二维（{@code tenant + api_key}）。
     * 存**哈希**而不是 secret：它本来就已经算出来了，而且哈希能安全地进日志/指标（secret 不能）。
     */
    public static final String ATTRIBUTE_KEY_HASH = "aihub.apiKeyHash";
```

**（b）把 `filter` 里 `resolver.resolve(ApiKeyHasher.hash(secret))` 那一行拆开**：

```java
        // 哈希实现只有一份：com.aihub.common.apikey.ApiKeyHasher（admin 铸造端用的也是它）。
        // 哈希同时是 M3 限流桶的第二维，因此在这里（而不是在限流器里）算一次并写进属性：
        // 限流器不该拿到 secret，也不该再算一遍。
        String keyHash = ApiKeyHasher.hash(secret);
        return resolver.resolve(keyHash)
                .switchIfEmpty(Mono.just(UNRESOLVED))
                .flatMap(view -> {
                    if (!view.usable()) {
                        return unauthorized(exchange, INVALID_KEY_MESSAGE);
                    }
                    exchange.getAttributes().put(ATTRIBUTE_KEY_VIEW, view);
                    exchange.getAttributes().put(ATTRIBUTE_KEY_HASH, keyHash);
                    return chain.filter(exchange);
                });
```

- [ ] **Step 2: 写失败测试**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/RateLimitFilterTest.java`：

```java
package com.aihub.gateway.ratelimit;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.auth.ApiKeyAuthFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流过滤器是「429 长什么样、降级时怎么办」的唯一落点。
 *
 * <p>两条容易被写错的规则：
 * <ol>
 *   <li>**降级也要限流**：Redis 挂了不等于「放过所有人」（那是无限流），而是「用单机近似继续限」；</li>
 *   <li>**限流器自身故障必须放行**（fail-open）：限流是保护上游的手段，它自己坏了不能变成全量 503
 *       —— 与 M1「缓存故障绝不变成 500」是同一条纪律。</li>
 * </ol>
 */
class RateLimitFilterTest {

    private static final ApiKeyView VIEW =
            new ApiKeyView("ak_demo", 7L, "demo", ApiKeyView.STATUS_ACTIVE, null, 42L);

    private static MockServerWebExchange exchange(String path, boolean withViewAndHash) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path).header("Content-Type", "application/json").build());
        if (withViewAndHash) {
            exchange.getAttributes().put(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW, VIEW);
            exchange.getAttributes().put(ApiKeyAuthFilter.ATTRIBUTE_KEY_HASH, "hash-of-secret");
        }
        return exchange;
    }

    private static RateLimitFilter filter(RecordingRateLimiter limiter, boolean enabled) {
        return new RateLimitFilter(limiter, enabled, new SimpleMeterRegistry());
    }

    private static RateLimitDecision allow(int remaining, RateLimitDecision.Source source) {
        return RateLimitDecision.allowed(remaining, 10, 20, source);
    }

    private static RateLimitDecision deny(long retryAfterMs, RateLimitDecision.Source source) {
        return RateLimitDecision.denied(retryAfterMs, 10, 20, source);
    }

    private static String bodyOf(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
    }

    @Test
    void allowsWhenUnderTheLimitAndAddsTheRateLimitHeaders() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(allow(9, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(RateLimitFilter.LIMIT_HEADER)).isEqualTo("10, 20");
        assertThat(exchange.getResponse().getHeaders().getFirst(RateLimitFilter.REMAINING_HEADER)).isEqualTo("9");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void rejectsWith429AndTheOpenAiBodyWhenOverTheLimit() {
        RecordingRateLimiter limiter =
                new RecordingRateLimiter(deny(250L, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).as("超限不得进入下游链").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(bodyOf(exchange))
                .contains("\"error\"")
                .contains("\"code\":\"rate_limit_exceeded\"")
                .contains("\"type\":\"rate_limit_error\"")
                .contains("\"param\":null");
    }

    @Test
    void rejectionCarriesRetryAfterInSecondsAndMilliseconds() {
        RecordingRateLimiter limiter =
                new RecordingRateLimiter(deny(250L, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);

        filter(limiter, true).filter(exchange, chain(new AtomicReference<>())).block(Duration.ofSeconds(5));

        // 250ms → 向上取整 1 秒（Retry-After 的单位只能是秒）。
        assertThat(exchange.getResponse().getHeaders().getFirst(RateLimitFilter.RETRY_AFTER_HEADER)).isEqualTo("1");
        assertThat(exchange.getResponse().getHeaders().getFirst(RateLimitFilter.RETRY_AFTER_MS_HEADER))
                .isEqualTo("250");
        assertThat(exchange.getResponse().getHeaders().getFirst(RateLimitFilter.REMAINING_HEADER)).isEqualTo("0");
    }

    @Test
    void usesTheTenantAndTheKeyHashAsTheBucketDimension() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(allow(5, RateLimitDecision.Source.REDIS));

        filter(limiter, true).filter(exchange("/v1/chat/completions", true), chain(new AtomicReference<>()))
                .block(Duration.ofSeconds(5));

        assertThat(limiter.tenantId).isEqualTo(7L);
        assertThat(limiter.keyHash).isEqualTo("hash-of-secret");
        // 决策 7（修订）+ 决策 14：策略的 key 维度必须真的从认证视图里传下去 —— 这正是原文
        // 「请求无法映射到 api_key_id」那句不成立的机器证据。数值主键自 Task 2 起就在共享契约里，
        // 因此这里是**直接读真值**，不是什么「后续任务收口」。
        assertThat(limiter.apiKeyId).isEqualTo(42L);
    }

    @Test
    void skipsWhenDisabled() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(deny(1L, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, false).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNotNull();
        assertThat(limiter.calls).isZero();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void skipsNonV1Paths() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(deny(1L, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/healthz", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNotNull();
        assertThat(limiter.calls).isZero();
    }

    /** 鉴权关闭（没有 view）时不能「无限放行」：用 tenant=0 + anonymous 的桶仍然限流。 */
    @Test
    void usesTheAnonymousBucketWhenThereIsNoApiKeyView() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(allow(1, RateLimitDecision.Source.REDIS));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", false);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNotNull();
        assertThat(limiter.calls).isEqualTo(1);
        assertThat(limiter.tenantId).isZero();
        assertThat(limiter.keyHash).isEqualTo("anonymous");
        // 没有 view 时没有数值主键 → 策略解析只走租户级（决策 7 的第二级），这**不是**「不限流」。
        assertThat(limiter.apiKeyId).isNull();
    }

    /** 降级 ≠ 放行：本机桶说拒绝，就必须回 429（否则 Redis 一挂就等于关掉了限流）。 */
    @Test
    void stillEnforcesTheLocalBucketWhenRedisIsDegraded() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(deny(100L, RateLimitDecision.Source.LOCAL));
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void degradedDecisionIsCounted() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(allow(1, RateLimitDecision.Source.LOCAL));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RateLimitFilter(limiter, true, registry)
                .filter(exchange("/v1/chat/completions", true), chain(new AtomicReference<>()))
                .block(Duration.ofSeconds(5));

        assertThat(registry.get("aihub.ratelimit.degraded").counter().count()).isEqualTo(1.0);
    }

    @Test
    void filterOrderIsAfterAuthentication() {
        Order order = RateLimitFilter.class.getAnnotation(Order.class);

        assertThat(WebFilter.class).isAssignableFrom(RateLimitFilter.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 100);
        assertThat(ApiKeyAuthFilter.class.getAnnotation(Order.class).value())
                .as("鉴权必须先跑，限流才拿得到 tenant/密钥哈希").isLessThan(order.value());
    }

    @Test
    void unexpectedLimiterFailureFailsOpen() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(null);
        limiter.failWith = new IllegalStateException("限流器内部错误");
        MockServerWebExchange exchange = exchange("/v1/chat/completions", true);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter(limiter, true).filter(exchange, chain(passed)).block(Duration.ofSeconds(5));

        assertThat(passed.get()).as("限流器故障必须放行（fail-open）").isNotNull();
    }

    // --- 测试脚手架 -------------------------------------------------------

    private static WebFilterChain chain(AtomicReference<ServerWebExchange> passed) {
        return exchange -> {
            passed.set(exchange);
            return Mono.empty();
        };
    }

    /**
     * 可编排的 {@link RateLimiter} 替身：记录维度、可注入结果与异常。
     * <p>它继承真实类并覆写唯一被使用的方法 —— 父类构造器收到的三个 null 永远不会被触碰。
     */
    private static final class RecordingRateLimiter extends RateLimiter {
        private final RateLimitDecision decision;
        int calls;
        long tenantId = -1L;
        Long apiKeyId = -1L;
        String keyHash;
        RuntimeException failWith;

        RecordingRateLimiter(RateLimitDecision decision) {
            super(null, null, null);
            this.decision = decision;
        }

        @Override
        public RateLimitDecision acquire(long tenantId, Long apiKeyId, String keyHash) {
            calls++;
            this.tenantId = tenantId;
            this.apiKeyId = apiKeyId;
            this.keyHash = keyHash;
            if (failWith != null) {
                throw failWith;
            }
            return decision;
        }
    }
}
```

> **注意**：`new ApiKeyView("ak_demo", 7L, "demo", ApiKeyView.STATUS_ACTIVE, null, 42L)` 是 **6 个分量**
> —— 决策 14 的契约在 **Task 2** 就已落地（那时同一个提交里也补好了 admin 侧填充与 `AdminClient.parse`），
> 因此本任务写的每一处 `ApiKeyView` 都是 6 个分量，`RateLimitFilter` 也**直接读** `view.apiKeyId()`。
> **没有占位、没有「以后有人回来补」这一步**：策略解析在 Task 4 是两级的、请求侧的这一环在本任务
> 就是真值，key 级策略在端到端路径上自本任务起即可生效（全局约束：不许跨任务占位）。

- [ ] **Step 3: 写 `RateLimitFilter`**

创建 `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimitFilter.java`：

```java
package com.aihub.gateway.ratelimit;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.gateway.auth.ApiKeyAuthFilter;
import com.aihub.gateway.error.GatewayErrors;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据面限流（设计文档 §8.1 ②）。守 {@code /v1/**}，**排在鉴权之后**：限流的策略维度是
 * {@code tenant + api_key}（决策 7 修订），桶维度是 {@code tenant + sha256(secret)}，
 * 这些值都来自 {@link ApiKeyAuthFilter} 的解析结果。
 *
 * <p>超限回 **429 + OpenAI 形状错误体**（决策 13：数据面不套 admin 信封），并带上
 * IETF 风格的 {@code RateLimit-*} 与 {@code Retry-After}。
 *
 * <p><b>降级不等于放行</b>：Redis 不可用时 {@link RateLimiter} 会给出本机桶的判定，
 * 过滤器**照常执行**那个判定（拒绝就是拒绝）。放开全部请求不是「降级」，是「关掉限流」，
 * 会让上游被瞬间打挂 —— 那才是真正的不可用。
 *
 * <p><b>自身故障必须放行</b>（fail-open）：限流是**保护**手段，它自己坏了不能变成全量 503。
 * 与鉴权的 fail-closed 相反，这是刻意的取舍（保护层的故障方向应当朝向「让请求过去」，
 * 而信任层的故障方向应当朝向「拒绝」）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 150)
public class RateLimitFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** IETF RateLimit 字段族。 */
    public static final String LIMIT_HEADER = "ratelimit-limit";
    public static final String REMAINING_HEADER = "ratelimit-remaining";

    /** 退避信号：标准头是秒，毫秒版是非标准但客户端友好。 */
    public static final String RETRY_AFTER_HEADER = "retry-after";
    public static final String RETRY_AFTER_MS_HEADER = "retry-after-ms";

    private static final PathPattern GUARDED_PATH = new PathPatternParser().parse("/v1/**");

    /** 降级日志的节流窗口：Redis 挂掉时不要每个请求打一行。 */
    private static final long DEGRADE_LOG_INTERVAL_MILLIS = 60_000L;

    /** 没有 {@code ApiKeyView}（鉴权关闭）时的桶维度：仍然限流，而不是无限放行。 */
    private static final String ANONYMOUS_KEY_HASH = "anonymous";

    private final RateLimiter limiter;
    private final boolean enabled;
    private final MeterRegistry registry;
    private final AtomicLong lastDegradeLogMillis = new AtomicLong(0L);

    public RateLimitFilter(RateLimiter limiter,
                           @Value("${aihub.ratelimit.enabled:true}") boolean enabled,
                           MeterRegistry registry) {
        this.limiter = limiter;
        this.enabled = enabled;
        this.registry = registry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled || !GUARDED_PATH.matches(exchange.getRequest().getPath().pathWithinApplication())) {
            return chain.filter(exchange);
        }
        ApiKeyView view = (ApiKeyView) exchange.getAttributes().get(ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW);
        Object keyHash = exchange.getAttributes().get(ApiKeyAuthFilter.ATTRIBUTE_KEY_HASH);
        long tenantId = view == null ? 0L : view.tenantId();
        String hash = keyHash == null ? ANONYMOUS_KEY_HASH : keyHash.toString();
        // 决策 7（修订）+ 决策 14：key 级策略的映射键是 api_key 的**数值主键**，它随 ApiKeyView 一起
        // 下发（该分量自 Task 2 起就在共享契约里，admin 侧填 api_key.id、网关侧 parse 透传），
        // 因此这里直接读真值：没有 view（鉴权关闭）时为 null → 策略解析只走租户级那一维。
        Long apiKeyId = view == null ? null : view.apiKeyId();

        RateLimitDecision decision;
        try {
            decision = limiter.acquire(tenantId, apiKeyId, hash);
        } catch (RuntimeException e) {
            log.error("限流器自身异常，本次请求放行（fail-open）: {}", e.toString());
            return chain.filter(exchange);
        }

        if (decision.degraded()) {
            registry.counter("aihub.ratelimit.degraded").increment();
            long now = System.currentTimeMillis();
            long last = lastDegradeLogMillis.get();
            if (now - last >= DEGRADE_LOG_INTERVAL_MILLIS && lastDegradeLogMillis.compareAndSet(last, now)) {
                log.warn("限流正在使用本机令牌桶（Redis 不可用，单机近似；多实例下实际放行量约为「策略 × 实例数」）");
            }
        }

        exchange.getResponse().getHeaders().set(LIMIT_HEADER, decision.limit() + ", " + decision.burst());
        exchange.getResponse().getHeaders().set(REMAINING_HEADER, String.valueOf(decision.remaining()));

        if (decision.allowed()) {
            return chain.filter(exchange);
        }

        long retryAfterMs = Math.max(1L, decision.retryAfterMs());
        long retryAfterSeconds = Math.max(1L, (retryAfterMs + 999L) / 1000L);
        exchange.getResponse().getHeaders().set(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));
        exchange.getResponse().getHeaders().set(RETRY_AFTER_MS_HEADER, String.valueOf(retryAfterMs));
        registry.counter("aihub.ratelimit.rejected").increment();
        log.debug("限流拒绝: tenant={} limit={}qps burst={} retryAfter={}ms", tenantId, decision.limit(),
                decision.burst(), retryAfterMs);

        return GatewayErrors.write(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS,
                "rate_limit_error", "rate_limit_exceeded",
                "请求过于频繁：租户 " + tenantId + " 的限额为 " + decision.limit() + " QPS（突发 "
                        + decision.burst() + "），请在 " + retryAfterMs + " 毫秒后重试");
    }
}
```

- [ ] **Step 4: 配置：默认开关**

在 `aihub-gateway/src/main/resources/application.yml` 的 `aihub:` 块里（`config:` 之后）加：

```yaml
  ratelimit:
    enabled: ${AIHUB_RATELIMIT_ENABLED:true}
```

在 `aihub-gateway/src/test/resources/application.properties` 末尾加（**理由与 `aihub.metering.enabled=false` 完全相同**：绝大多数网关测试只关心转发/鉴权，不希望每个请求都先撞一次「Redis 指向不存在端口」的 2 秒超时）：

```properties

# 限流默认关闭：绝大多数网关测试只关心转发与鉴权，不希望每个请求都先撞一次「Redis 指向不存在端口」
# 的 2 秒超时。需要限流的测试用 properties / @DynamicPropertySource 显式打开它
# （RateLimitFilterTest 直接构造过滤器，不经过这条配置）。
aihub.ratelimit.enabled=false
```

- [ ] **Step 5: 运行本任务测试**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=RateLimitFilterTest"
```

预期：`Tests run: 11, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 6: 跑网关全部测试**

```powershell
mvn -B -pl aihub-gateway test
```

预期：`Tests run: 223, Failures: 0, Errors: 0`（Task 8 的 212 + 11）。**若 `AihubGatewayApplicationTests` 或任何端到端测试变红**，先确认 `aihub.ratelimit.enabled=false` 真的写进了测试资源（`mainApplicationYamlIsMergedIntoTheTestConfiguration` 可以加一条断言把它钉住 —— 那是加强），并且**没有**在 `src/test/resources` 下新建 `application.yml`（同名文件会整体取代主配置 —— M1 踩过这个坑，`noTestResourceShadowsTheMainApplicationYaml` 会抓它）。

- [ ] **Step 7: 提交**

```powershell
git add aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/RateLimitFilter.java aihub-gateway/src/main/java/com/aihub/gateway/auth/ApiKeyAuthFilter.java aihub-gateway/src/main/resources/application.yml aihub-gateway/src/test/resources/application.properties aihub-gateway/src/test/java/com/aihub/gateway/ratelimit/RateLimitFilterTest.java
git commit -m "feat: enforce the tenant rate limit in a gateway filter with an openai-shaped 429"
```

**验收标准**
1. 超限 → 429 + OpenAI 错误体（`code=rate_limit_exceeded`、`type=rate_limit_error`、`param=null`），并带 `Retry-After`（秒，向上取整 ≥1）、`Retry-After-MS`（毫秒）、`RateLimit-Remaining: 0`；放行时也带 `RateLimit-Limit` / `RateLimit-Remaining`。
2. 桶的维度是 `tenantId + sha256(secret)`（策略维度是 `tenantId + apiKeyId`，两者**不要混**）；`ATTRIBUTE_KEY_HASH` 由 `ApiKeyAuthFilter` 在鉴权成功时写入（限流器拿不到 secret）。**策略的 `apiKeyId` 直接取自认证后的 `ApiKeyView`**（契约见 Task 2 的决策 14；没有 view 时为 `null` → 只走租户级策略），用例断言传进 `RateLimiter` 的就是 `42L` —— 这一环**不经过任何占位**。
3. `aihub.ratelimit.enabled=false` 完全不管；非 `/v1` 路径不管；没有 view（鉴权关闭）时用 `tenant=0` + `anonymous` 桶**继续限流**。
4. Redis 降级（`source == LOCAL`）时**照常执行判定**（拒绝仍回 429），并有节流的 WARN + `aihub.ratelimit.degraded` 计数。
5. 限流器自身抛异常时请求被放行（fail-open）。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-gateway test -am "-Dtest=RateLimitFilterTest"` | `Tests run: 11, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-gateway test` | `Tests run: 223, Failures: 0, Errors: 0` + `BUILD SUCCESS` |

---

## Task 10: 渠道感知的中继 + 故障转移（M3 的核心）

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/relay/RelayAttempts.java`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/meter/RelayMetering.java`（`channelId` 填充）
- Modify: `aihub-gateway/src/test/java/com/aihub/gateway/testsupport/FakeUpstream.java`（加两个故障注入方法）
- Modify: `aihub-gateway/pom.xml`（**test 作用域**加 `org.wiremock:wiremock:3.9.1`，决策 11；生产依赖不动）
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/relay/RelayAttemptsTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/relay/FailoverRelayTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/relay/WireMockChannelFaultInjectionTest.java`（spec §10 / 里程碑验收）

**Interfaces:**
- Consumes：Task 2 / 5 / 6 / 7 / 8 的全部产出，以及既有 `RelayRequestBody` / `RelayMetering` / `MeteringPublisher` / `RequestIdFilter` / `GatewayErrors`。
- Produces：
  - `RelayAttempts`（工具类，**纯函数**）：
    - `static boolean shouldFailoverBeforeCommit(int upstreamStatus)` → `status >= 500 || status == 429`；
    - `static boolean isClientErrorThatMustNotBeRetried(int upstreamStatus)` → `400 <= status < 500 && status != 429`；
    - `static List<ChannelDescriptor> servable(List<ChannelDescriptor>, ChannelKeyDecryptor)`（过滤解不开密钥的候选；**全解不开时返回原列表**）；
    - `static String truncateModel(String)` 与常量 `MODEL_MAX_LENGTH = 128`。
  - `ChatRelayController` 的新构造器（**全部 bean 都已存在**）：
    ```java
    public ChatRelayController(UpstreamClientFactory clientFactory, ConfigClient configClient,
                               RouteResolver routeResolver, ChannelCircuitBreaker circuitBreaker,
                               ChannelKeyDecryptor keyDecryptor, MeteringPublisher meteringPublisher,
                               MeteringProperties meteringProperties)
    ```
    **`upstreamWebClient` 不再被控制器注入**（它仍然是 bean，供遗留测试与 `ModelsController` 语义使用）。
  - `RelayMetering.onChannelSelected(ChannelDescriptor channel)`（记录实际服务的渠道）。
  - `FakeUpstream.enqueueError(int status, String body)` 与 `FakeUpstream.enqueueStall(long holdMillis)`。

**失败转移的精确规则（评审按此判断）**：
1. 候选 = `routeResolver.candidates(model)` 经 `RelayAttempts.servable(...)` 过滤。为空且快照里**确实**有遗留渠道（`LegacyChannel.ID`）→ 用遗留渠道；否则回 404 `model_not_found`。
2. 逐候选尝试（**每次尝试用 `Mono.defer` 延迟订阅**）。分类：
   - `WebClientRequestException`（连不上 / 超时）→ 切换；
   - 上游 **429** → `circuitBreaker.markOpen(channelId)` 后**立即**切换；
   - 上游 **5xx** → 切换（**不打熔断**，决策 10）；
   - 上游 **4xx（非 429）** → **不切换**，原样透传；
   - 上游 **2xx** → 进入 `relay(...)` 回写，**此后不再切换**。
3. **切换的前提是「响应尚未提交」**：响应只有在我们开始 `writeAndFlushWith` 之后才提交，因此「上游响应头到达但 body 还没写」这段窗口是合法的切换时机。一旦 `relay(...)` 开始写字节，后续任何异常都只会走控制器顶层的收尾分支 —— 这就是「仅在未输出任何 token 时允许切换」的机器实现。
4. 所有候选都失败 → 客户端拿到**最后一个失败的原样**（502 `upstream_unreachable`，或最后那个上游响应原样透传 —— 由最后一次尝试把响应回写实现）。

**测试用例清单**（`RelayAttemptsTest` 7 + `FailoverRelayTest` 7 = 14 条）：

| 类 | 用例 | 钉住什么 |
|---|---|---|
| `RelayAttemptsTest`（7） | `failoverIsAllowedFor5xxAnd429` / `failoverIsRejectedForClientErrorsAndSuccess` / `clientErrorsAreNotRetriedEvenThoughTheyAreErrors` / `servableFiltersOutUndecryptableChannelsInOrder` / `servableKeepsEverythingWhenNoCandidateIsDecryptable` / `legacyChannelIsAlwaysServable` / `modelIsTruncatedToTheRequestLogColumnWidth` | 分类规则与候选过滤 |
| `FailoverRelayTest`（7） | `upstream429SwitchesImmediatelyAndMarksTheChannel` / `upstream5xxSwitchesToTheNextCandidate` / `upstreamTimeoutSwitchesToTheNextCandidate` / `upstream400IsRelayedVerbatimWithoutSwitching` / `everyCandidateFailingYieldsTheRealLastFailure` / `unknownModelReturns404WithTheOpenAiBody` / `perChannelKeyIsInjectedIntoTheUpstreamRequest` | 故障转移的端到端行为 |

- [ ] **Step 1: 写 `RelayAttemptsTest` 与 `RelayAttempts`**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/relay/RelayAttemptsTest.java`：

```java
package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败转移的**分类规则**是 M3 最容易写错的地方：哪些状态码该换渠道、哪些必须原样透传。
 * 规则本身是纯函数，因此可以在这里逐条钉死，而不必靠端到端测试去凑。
 *
 * <p>核心区分：
 * <ul>
 *   <li><b>可切换</b>：连接失败/超时（{@code WebClientRequestException}）、上游 5xx、上游 429；</li>
 *   <li><b>不可切换</b>：上游 4xx（非 429）——那是客户端的错，换渠道一样错，重试只会放大；</li>
 *   <li><b>必须保留候选</b>：所有候选的密钥都解不开时，也要给出候选（让真实的上游错误浮现），
 *       而不是把它变成 {@code model_not_found} 404。</li>
 * </ul>
 */
class RelayAttemptsTest {

    private static final String SYNTHETIC_PLAINTEXT = "sk-channel-plaintext-synthetic";

    private static String masterKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 5);
        }
        return "v1:" + Base64.getEncoder().encodeToString(key);
    }

    private static AesGcmChannelCipher cipher() {
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey()));
    }

    private static ChannelDescriptor channel(long id, String cipherText) {
        return new ChannelDescriptor(id, "ch-" + id, "https://ch" + id + ".example.com", cipherText, 1, 5_000,
                ChannelDescriptor.STATUS_ACTIVE, 100, 0);
    }

    private static ChannelKeyDecryptor decryptor(AesGcmChannelCipher cipher, UpstreamProperties upstream) {
        return new ChannelKeyDecryptor(cipher, upstream);
    }

    private static UpstreamProperties noLegacyKey() {
        return new UpstreamProperties("http://127.0.0.1:1", null, "m");
    }

    @Test
    void failoverIsAllowedFor5xxAnd429() {
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(500)).isTrue();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(502)).isTrue();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(503)).isTrue();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(429)).isTrue();
    }

    @Test
    void failoverIsRejectedForClientErrorsAndSuccess() {
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(400)).isFalse();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(401)).isFalse();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(403)).isFalse();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(404)).isFalse();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(422)).isFalse();
        assertThat(RelayAttempts.shouldFailoverBeforeCommit(200)).isFalse();
    }

    @Test
    void clientErrorsAreNotRetriedEvenThoughTheyAreErrors() {
        assertThat(RelayAttempts.isClientErrorThatMustNotBeRetried(400)).isTrue();
        assertThat(RelayAttempts.isClientErrorThatMustNotBeRetried(429)).as("429 是唯一要切换的 4xx").isFalse();
        assertThat(RelayAttempts.isClientErrorThatMustNotBeRetried(500)).isFalse();
    }

    @Test
    void servableFiltersOutUndecryptableChannelsInOrder() {
        AesGcmChannelCipher cipher = cipher();
        List<ChannelDescriptor> candidates = List.of(
                channel(1L, "v9:QUJD"),
                channel(2L, cipher.encrypt(SYNTHETIC_PLAINTEXT)),
                channel(3L, cipher.encrypt(SYNTHETIC_PLAINTEXT)));

        assertThat(RelayAttempts.servable(candidates, decryptor(cipher, noLegacyKey())))
                .extracting(ChannelDescriptor::id).containsExactly(2L, 3L);
    }

    /** 全部解不开时**不返回空**：否则「主密钥配错」会伪装成「这个模型不存在」（404）。 */
    @Test
    void servableKeepsEverythingWhenNoCandidateIsDecryptable() {
        ChannelKeyDecryptor broken = decryptor(new AesGcmChannelCipher(ChannelKeyRegistry.parse("")), noLegacyKey());
        List<ChannelDescriptor> candidates = List.of(channel(1L, "v1:QUJD"), channel(2L, "v2:QUJD"));

        assertThat(RelayAttempts.servable(candidates, broken)).isEqualTo(candidates);
    }

    @Test
    void legacyChannelIsAlwaysServable() {
        UpstreamProperties legacy = new UpstreamProperties("http://127.0.0.1:11434", "", "m");

        assertThat(RelayAttempts.servable(List.of(LegacyChannel.of(legacy)), decryptor(cipher(), legacy)))
                .hasSize(1);
    }

    @Test
    void modelIsTruncatedToTheRequestLogColumnWidth() {
        assertThat(RelayAttempts.MODEL_MAX_LENGTH).as("必须与 V1 的 model VARCHAR(128) 一致").isEqualTo(128);
        assertThat(RelayAttempts.truncateModel("x".repeat(200))).hasSize(128);
        assertThat(RelayAttempts.truncateModel("short")).isEqualTo("short");
        assertThat(RelayAttempts.truncateModel(null)).isNull();
        // 中文按字符截断（JDBC 的 VARCHAR(128) 在 utf8mb4 下按字符计）。
        assertThat(RelayAttempts.truncateModel("模".repeat(200))).hasSize(128);
    }
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/relay/RelayAttempts.java`：

```java
package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;

import java.util.ArrayList;
import java.util.List;

/**
 * 失败转移的**纯规则**：哪些上游结果该换渠道、哪些候选值得一试、model 该截到多长。
 *
 * <p>抽成纯函数（而不是塞在控制器的 lambda 里）的唯一理由是**可证伪**：这些规则是 M3 的核心
 * 正确性，必须能被单元测试逐条钉住。控制器只负责把它们接到 Reactor 链上。
 */
public final class RelayAttempts {

    /** {@code request_log.model} 是 {@code VARCHAR(128)}（V1）。超长会让那一行 INSERT 失败并进 DLQ。 */
    public static final int MODEL_MAX_LENGTH = 128;

    private RelayAttempts() {
    }

    /**
     * 上游这个状态码是否允许「换下一个候选渠道」。
     *
     * <p>只有 5xx 与 429。**4xx 不换**：401/403/404/422 是「这个请求本身有问题」，
     * 换渠道也一样错，重试只会把同一个错误在多个上游重复计费一次。
     */
    public static boolean shouldFailoverBeforeCommit(int upstreamStatus) {
        return upstreamStatus >= 500 || upstreamStatus == 429;
    }

    /** 「客户端的错，必须原样透传、不得重试」的判据（429 除外，它是要切换的）。 */
    public static boolean isClientErrorThatMustNotBeRetried(int upstreamStatus) {
        return upstreamStatus >= 400 && upstreamStatus < 500 && upstreamStatus != 429;
    }

    /**
     * 过滤掉**密钥解不开**的候选（它们注定失败，没必要浪费一次往返）。
     *
     * <p>但如果过滤之后一个都不剩，就**返回原列表**：主密钥配错时应该让客户端看到真实的上游错误
     * （或者至少一条与密钥有关的日志），而不是一个伪装成「模型不存在」的 404。
     */
    public static List<ChannelDescriptor> servable(List<ChannelDescriptor> candidates,
                                                   ChannelKeyDecryptor decryptor) {
        List<ChannelDescriptor> servable = new ArrayList<>(candidates.size());
        for (ChannelDescriptor channel : candidates) {
            if (decryptor.canServe(channel)) {
                servable.add(channel);
            }
        }
        return servable.isEmpty() ? candidates : servable;
    }

    /** 计量事件里的 model 必须能落进 {@code VARCHAR(128)}（决策 15）。 */
    public static String truncateModel(String model) {
        if (model == null || model.length() <= MODEL_MAX_LENGTH) {
            return model;
        }
        return model.substring(0, MODEL_MAX_LENGTH);
    }
}
```

- [ ] **Step 2: 运行 `RelayAttemptsTest`**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=RelayAttemptsTest"
```

预期：`Tests run: 7, Failures: 0, Errors: 0`。

- [ ] **Step 3: 给 `RelayMetering` 加 `onChannelSelected`**

在 `aihub-gateway/src/main/java/com/aihub/gateway/meter/RelayMetering.java` 里做三处修改：

（a）把 `channelId` 从构造器参数改为可变引用：

```java
    private final Long apiKeyId;
    private final AtomicReference<Long> channelId = new AtomicReference<>();
```

（b）构造器签名与赋值（去掉 `channelId` 参数）：

```java
    RelayMetering(String requestId, Instant createdAt, long tenantId, Long apiKeyId,
                  String model, UsageCapture capture) {
        this.requestId = requestId;
        this.createdAt = createdAt;
        this.tenantId = tenantId;
        this.apiKeyId = apiKeyId;
        this.model = model;
        this.capture = capture;
    }
```

并把静态工厂里传 `null` 的那两行改成只传一个 `null`（这里传的是 `apiKeyId`：契约（决策 14）虽然自 Task 2 起就在 `ApiKeyView` 里，但**计量侧把 `api_key_id` 落库**是 Task 11 的那一条遗留，本任务只做渠道填充，避免一步同时动两件事）：

```java
        return new RelayMetering(
                requestId,
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                view == null ? TENANT_UNKNOWN : view.tenantId(),
                null,   // apiKeyId：计量侧的落库是 Task 11 的遗留项之一（决策 14 的契约在 Task 2 已就位）
                model,
                UsageCapture.start(streaming, maxCaptureBytes));
```

（c）加方法：

```java
    /**
     * 记录本次实际服务的渠道。**在转发开始前调用一次**（控制器拿到首选候选后立即调）。
     * 遗留单渠道的哨兵 id 也照样写进事件（不是 NULL）：这样「走了兜底路径」在
     * {@code request_log} 里是可查的，而不是与「多渠道正常路径」混在一起。
     */
    public void onChannelSelected(ChannelDescriptor channel) {
        if (channel != null) {
            channelId.compareAndSet(null, channel.id());
        }
    }
```

（d）`toEvent(...)` 里把构造换成用 `channelId.get()`：

```java
        return new MeteringEvent(requestId, tenantId, apiKeyId, channelId.get(),
                model,
                promptTokens, completionTokens, totalTokens,
                capture.latencyMs(), captured.ttftMs(), resolvedStatus, code,
                createdAt.toEpochMilli());
```

并补 import：`import com.aihub.common.config.ChannelDescriptor;`。
（model 的截断**不在这里**做 —— 留在 Task 11 与决策 15 一起落地，避免这一步同时动两件事。）

- [ ] **Step 4: 给 `FakeUpstream` 加两个故障注入方法**

在 `aihub-gateway/src/test/java/com/aihub/gateway/testsupport/FakeUpstream.java` 里，`enqueueWithHeaders(...)` 之后加：

```java
    /**
     * 只发一个错误状态码 + JSON 错误体（用于构造上游 5xx 与 429）。
     * 与 {@link #enqueueJson(int, String)} 的实现相同，只是命名让故障注入用例读起来更清楚。
     */
    public synchronized void enqueueError(int status, String body) {
        enqueueJson(status, body);
    }

    /**
     * 连上但**永远不回响应头**：用来构造「上游超时」。
     * <p>响应头不回 = 网关的 {@code responseTimeout} 会触发（非流式客户端按 {@code channel.timeoutMs}），
     * 因此这个夹具的等待预算必须**大于**被测客户端的超时，否则用例会先被自己的兜底放行。
     *
     * @param holdMillis 最多扣留多少毫秒（到时写出 200 空 JSON 并关闭，避免测试进程挂住）
     */
    public synchronized void enqueueStall(long holdMillis) {
        queued.add(exchange -> {
            try {
                Thread.sleep(holdMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeBody(exchange, 200, "application/json; charset=utf-8", "{}", Map.of());
        });
    }
```

- [ ] **Step 5: 写 `FailoverRelayTest`（JDK 夹具）与 `WireMockChannelFaultInjectionTest`（WireMock 多渠道验收）**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/relay/FailoverRelayTest.java`：

```java
package com.aihub.gateway.relay;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import com.aihub.gateway.testsupport.RecordingMeteringTransport;
import com.aihub.gateway.trace.RequestIdFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **M3 的验收核心**：多候选渠道下的自动故障转移与熔断，全部用真 HTTP（真实网关 + JDK HttpServer
 * 假上游），**不需要 Docker、不需要真 Redis**（Redis 指向不存在的端口 → 熔断退化成本机表，
 * 这本身也是降级路径的证据）。
 *
 * <p>五个必须成立的场景：
 * <ol>
 *   <li>上游 429 → **立即**换下一个候选，并在计量里记下**实际服务**的那条渠道；</li>
 *   <li>上游 5xx → 换下一个候选；</li>
 *   <li>上游超时 → 换下一个候选；</li>
 *   <li>上游 400 → **不切换**（备用渠道一次都不该被调用）；</li>
 *   <li>候选全挂 → 客户端拿到**真实的最后一个失败**（原样状态码与 body）。</li>
 * </ol>
 *
 * <p>「第一个字节已转发之后不得切换」这条铁律的机器可判定形式由
 * {@code ChatRelayControllerTest} 的字节透传用例 + {@code RelayCommittedWriteFailureTest}
 * 共同守住（切换路径只在响应未提交时可达），本类聚焦「切换确实发生了」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=true", "aihub.metering.enabled=true",
                "aihub.ratelimit.enabled=false", "aihub.internal.secret=test-internal-secret"})
@Import({MeteringTestConfig.class, FailoverRelayTest.FakeAdmin.class})
class FailoverRelayTest {

    private static final String SECRET = "failover-secret";
    private static final String VALID_HASH = sha256Hex(SECRET);
    private static final String CHANNEL_KEY_PLAINTEXT = "sk-channel-plaintext-synthetic";
    private static final AtomicInteger SNAPSHOT_VERSION = new AtomicInteger(1);

    private static FakeUpstream primary;
    private static FakeUpstream standby;

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private RecordingMeteringTransport recorder;

    @BeforeAll
    static void startUpstreams() {
        primary = FakeUpstream.start();
        standby = FakeUpstream.start();
    }

    @AfterAll
    static void stopUpstreams() {
        primary.stop();
        standby.stop();
    }

    @BeforeEach
    void reset() {
        recorder.reset();
        primary.clearLastRequest();
        standby.clearLastRequest();
    }

    /** 主密钥表：只放一个 v1（合成）。 */
    private static String masterKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 3 + 7);
        }
        return "v1:" + Base64.getEncoder().encodeToString(key);
    }

    private static String cipherText() {
        return new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey())).encrypt(CHANNEL_KEY_PLAINTEXT);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> primary.baseUrl());
        registry.add("aihub.upstream.default-model", () -> "fallback-model");
        registry.add("aihub.channel.master-key", FailoverRelayTest::masterKey);
        // Redis 指向不存在的端口：鉴权回源到假 admin，熔断退化成本机表（两条降级路径都真跑一遍）。
        registry.add("spring.data.redis.port", () -> "1");
    }

    @TestConfiguration
    static class FakeAdmin {
        @Bean
        @Primary
        AdminClient adminClient() {
            return new AdminClient() {
                @Override
                public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
                    return keyHash.equals(VALID_HASH)
                            ? Mono.just(Optional.of(new ApiKeyView("ak_failover", 7L, "demo",
                                    ApiKeyView.STATUS_ACTIVE, null, 42L)))
                            : Mono.just(Optional.empty());
                }

                @Override
                public Mono<Optional<ConfigSnapshot>> configSnapshot() {
                    return Mono.just(Optional.of(snapshot()));
                }
            };
        }
    }

    /** 两条同优先级渠道：primary（weight 100）与 standby（weight 1）—— 让 primary 几乎总是首选。 */
    private static ConfigSnapshot snapshot() {
        return new ConfigSnapshot(
                SNAPSHOT_VERSION.incrementAndGet(), System.currentTimeMillis(),
                List.of(new ChannelDescriptor(11L, "primary", primary.baseUrl(), cipherText(), 1, 5_000, "ACTIVE", 100, 0),
                        new ChannelDescriptor(12L, "standby", standby.baseUrl(), cipherText(), 1, 5_000, "ACTIVE", 1, 0)),
                List.of(new ModelRouteDescriptor("failover-model", 11L, 100, 0, "ACTIVE"),
                        new ModelRouteDescriptor("failover-model", 12L, 1, 0, "ACTIVE")),
                List.of(), "failover-model");
    }

    @Test
    void upstream429SwitchesImmediatelyAndMarksTheChannel() throws Exception {
        primary.enqueueError(429, "{\"error\":{\"message\":\"rate limited by upstream\"}}");
        standby.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"failover-model\",\"stream\":false}");

        assertThat(response.statusCode()).as("切换成功后客户端看到的是备用渠道的 200").isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-1");
        assertThat(standby.lastRequest()).as("备用渠道真的被调用了").isNotNull();
        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId()).as("计量里的 channel_id 必须是**实际服务**的那条").isEqualTo(12L);
    }

    @Test
    void upstream5xxSwitchesToTheNextCandidate() throws Exception {
        primary.enqueueError(503, "{\"error\":{\"message\":\"upstream unavailable\"}}");
        standby.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"failover-model\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(standby.lastRequest()).isNotNull();
        MeteringEvent event = awaitEvent(response);
        assertThat(event).isNotNull();
        assertThat(event.channelId()).isEqualTo(12L);
    }

    @Test
    void upstreamTimeoutSwitchesToTheNextCandidate() throws Exception {
        // primary 连上但不回响应头；channel.timeoutMs=5000 → 非流式客户端 5 秒后超时并切换。
        primary.enqueueStall(8_000L);
        standby.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"failover-model\",\"stream\":false}");

        assertThat(response.statusCode()).as("超时后必须切到备用渠道").isEqualTo(200);
        assertThat(standby.lastRequest()).isNotNull();
    }

    /** 客户端的错（400）**不得**触发切换：那会把同一个错误在多个上游各计费一次。 */
    @Test
    void upstream400IsRelayedVerbatimWithoutSwitching() throws Exception {
        primary.enqueueError(400, "{\"error\":{\"message\":\"bad request from client\"}}");

        HttpResponse<String> response = post("{\"model\":\"failover-model\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("bad request from client");
        assertThat(standby.lastRequest()).as("400 不得切换渠道").isNull();
    }

    @Test
    void everyCandidateFailingYieldsTheRealLastFailure() throws Exception {
        primary.enqueueError(503, "{\"error\":{\"message\":\"primary down\"}}");
        standby.enqueueError(502, "{\"error\":{\"message\":\"standby down\"}}");

        HttpResponse<String> response = post("{\"model\":\"failover-model\",\"stream\":false}");

        assertThat(response.statusCode()).as("最后一个候选的上游状态必须原样透传").isEqualTo(502);
        assertThat(response.body()).contains("standby down");
    }

    @Test
    void unknownModelReturns404WithTheOpenAiBody() throws Exception {
        HttpResponse<String> response = post("{\"model\":\"no-such-model\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body())
                .contains("\"error\"")
                .contains("\"code\":\"model_not_found\"");
    }

    @Test
    void perChannelKeyIsInjectedIntoTheUpstreamRequest() throws Exception {
        primary.enqueueJson(200, FakeUpstream.completionJson());

        post("{\"model\":\"failover-model\",\"stream\":false}");

        assertThat(primary.lastRequest().headers())
                .as("网关必须用解密出来的渠道密钥覆盖客户端带来的 Authorization")
                .containsEntry("authorization", "Bearer " + CHANNEL_KEY_PLAINTEXT);
    }

    // --- 脚手架 ---------------------------------------------------------

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ak_failover." + SECRET)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private MeteringEvent awaitEvent(HttpResponse<String> response) throws InterruptedException {
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        return recorder.awaitEvent(requestId, Duration.ofSeconds(5));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

> **两个需要实施者注意的点**：
> 1. `new ApiKeyView("ak_failover", 7L, "demo", ApiKeyView.STATUS_ACTIVE, null, 42L)` 是 **6 个分量**
>    —— 决策 14 的契约在 **Task 2** 就已落地，**本任务按 6 个分量写即可，不需要也不允许留占位**。
> 2. `SNAPSHOT_VERSION` 每次 `configSnapshot()` 调用都 +1：让 `ConfigClient` 的版本比对始终认为
>    「拿到的是更新的快照」，避免本地缓存让不同用例互相影响（30 秒 TTL 内会复用同一个 Spring 上下文）。

**本 Step 的第二部分：加 WireMock 依赖（test 作用域），并写 spec §10 要求的多渠道故障注入验收**

设计文档 §10 的「上游契约」一行写的是「**WireMock 模拟多渠道**」，§12 的 M3 验收标准是「**WireMock 注入 429/超时，能自动切换**」。上面那个类用的是 M1/M2 的 JDK `HttpServer` 夹具（**保留**，它更适合细粒度、可证伪的用例），但它不是一个「多渠道」抽象。本步按决策 11 引入 WireMock，**只加在 `aihub-gateway` 的 test 作用域**：

在 `aihub-gateway/pom.xml` 的 `<dependencies>` 里加（**必须显式锁版本：WireMock 不在 Spring Boot BOM 里**）：

```xml
        <!-- 仅测试作用域（决策 11）：spec §10「上游契约 = WireMock 模拟多渠道」与 §12 的 M3
             验收标准「WireMock 注入 429/超时，能自动切换」的字面落点。
             版本锁 3.9.1：本机 .m2repo 已实测可解析（经 aliyunmaven 镜像；直连 Maven Central 被阻断），
             并在仓库外的探针工程里以 @WireMockTest 进程内跑通（无 Docker、无活 broker）。
             生产依赖零新增；aihub-common 的 pom 不动。 -->
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock</artifactId>
            <version>3.9.1</version>
            <scope>test</scope>
        </dependency>
```

然后创建 `aihub-gateway/src/test/java/com/aihub/gateway/relay/WireMockChannelFaultInjectionTest.java`。

> **脚手架复用**：`masterKey()` / `cipherText()` / `post(body)` / `awaitEvent(response)` / `sha256Hex(...)` /
> `FakeAdmin` / `@DynamicPropertySource` 与 `FailoverRelayTest` **逐字同款**（同一个假 admin、同一个
> `RecordingMeteringTransport`、Redis 同样指向不存在的端口），本类只列出**不同的部分**；实施者照抄
> `FailoverRelayTest` 的那几段即可。**不要**为它抽公共基类（会掩盖两个类各自要钉的东西）。

```java
package com.aihub.gateway.relay;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.admin.AdminClient;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.testsupport.FakeUpstream;
import com.aihub.gateway.testsupport.MeteringTestConfig;
import com.aihub.gateway.testsupport.RecordingMeteringTransport;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// 统一用类名限定调用 WireMock（`WireMock.post(...)` / `WireMock.aResponse()` / `WireMock.getRequestedFor(...)`），
// **不要**静态导入 `post`：本类照抄了 FailoverRelayTest 的 `post(String body)` 私有助手，
// 同名的单静态导入会被类内成员遮蔽（JLS 6.4.1），`post(urlPathEqualTo(...))` 会编译失败。
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * **spec §10 / §12 的字面验收**：用 WireMock 的**命名 stub** 扮多条渠道（本类用了四条），
 * 分别注入 429 / 挂住超时 / 中途断流，验证网关**自动切换**、以及「已开始回写就不再切换」。
 *
 * <p><b>进程内</b>（决策 11 已实测）：`@RegisterExtension static WireMockExtension` +
 * `options().dynamicPort()` —— stub 起在**本测试 JVM**里，**不需要 Docker、不需要活 broker**，
 * 因此 M1/M2 对网关测试的约束继续成立。**不要**换成 `docker run wiremock/...`。
 *
 * <p>它与 {@code FailoverRelayTest} 的分工：那个类用 JDK `HttpServer` 夹具钉**每一类状态码的
 * 分支**（含 400 不切换、每渠道密钥注入等细粒度断言）；本类钉**多渠道矩阵**与 spec 的验收措辞
 * ——「哪几条渠道被打了、按什么顺序、第几次请求命中谁」，用 WireMock 的请求日志（`verify`）说话。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aihub.auth.enabled=true", "aihub.metering.enabled=true",
                "aihub.ratelimit.enabled=false", "aihub.internal.secret=test-internal-secret"})
@Import({MeteringTestConfig.class, WireMockChannelFaultInjectionTest.FakeAdmin.class})
class WireMockChannelFaultInjectionTest {

    /** 四条「渠道」= 同一个进程内 WireMock 上的四个路径前缀（命名 stub 扮多渠道）。 */
    private static final String CHAT = "/v1/chat/completions";
    private static final String RATE_LIMITED = "/channel-rate-limited";
    private static final String HANGING = "/channel-hanging";
    private static final String BROKEN_STREAM = "/channel-broken-stream";
    private static final String HEALTHY = "/channel-healthy";

    private static final String SECRET = "wiremock-fault-secret";
    private static final String VALID_HASH = sha256Hex(SECRET);
    private static final String CHANNEL_KEY_PLAINTEXT = "sk-channel-plaintext-synthetic";
    private static final AtomicInteger SNAPSHOT_VERSION = new AtomicInteger(1);

    /** 渠道 id → 它在进程内 WireMock 上的路径前缀（四个 id 扮四条「命名渠道」）。 */
    private static final Map<Long, String> PREFIX_OF = Map.of(
            11L, RATE_LIMITED, 12L, HANGING, 13L, BROKEN_STREAM, 14L, HEALTHY);

    /**
     * **本用例声明快照里出现哪几条候选**（顺序 = priority 升序，因此「下一个候选是谁」是确定的）。
     * 逐个用例显式声明，而不是把四条都放进去 —— 否则「没被 stub 的那条恰好返回 404」这种外部巧合
     * 会参与判定（404 是 4xx：`RelayAttempts` 规定**不切换**，会把用例变成假绿/假红）。
     */
    private static final AtomicReference<List<Long>> CANDIDATES =
            new AtomicReference<>(List.of(11L, 12L, 13L, 14L));

    @RegisterExtension
    static final WireMockExtension UPSTREAM =
            WireMockExtension.newInstance().options(options().dynamicPort()).build();

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private RecordingMeteringTransport recorder;

    @Autowired
    private ConfigClient configClient;

    @BeforeEach
    void reset() {
        recorder.reset();
        UPSTREAM.resetAll();
        CANDIDATES.set(List.of(11L, 12L, 13L, 14L));
        // **必须**清掉配置的一级缓存：同一个类的用例之间 Spring 上下文是复用的，而每个用例的快照
        // 内容不同（CANDIDATES），本地 TTL 30 秒会让第二个用例拿到上一个用例的快照而「莫名其妙地绿/红」。
        // FailoverRelayTest 不需要这一步（它的快照在所有用例里都一样）。
        configClient.invalidate();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // 遗留单渠道兜底指向死端口：本类要验的是**快照里的多候选渠道**，不许静默走兜底。
        registry.add("aihub.upstream.base-url", () -> "http://127.0.0.1:1");
        registry.add("aihub.upstream.default-model", () -> "wiremock-model");
        registry.add("aihub.channel.master-key", WireMockChannelFaultInjectionTest::masterKey);
        registry.add("spring.data.redis.port", () -> "1");
    }

    @TestConfiguration
    static class FakeAdmin {
        @Bean
        @Primary
        AdminClient adminClient() {
            return new AdminClient() {
                @Override
                public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
                    return keyHash.equals(VALID_HASH)
                            ? Mono.just(Optional.of(new ApiKeyView("ak_wiremock", 7L, "demo",
                                    ApiKeyView.STATUS_ACTIVE, null, 42L)))
                            : Mono.just(Optional.empty());
                }

                @Override
                public Mono<Optional<ConfigSnapshot>> configSnapshot() {
                    return Mono.just(Optional.of(snapshot()));
                }
            };
        }
    }

    /**
     * 只把**本用例声明的那几条**候选放进快照（`CANDIDATES` 的顺序就是 priority 升序）。
     * 每条渠道的 base-url 都指向同一个进程内 WireMock，只用路径前缀区分 —— 这就是
     * §10 说的「WireMock 模拟多渠道」：一个 stub 服务器上挂多个命名 stub。
     */
    private static ConfigSnapshot snapshot() {
        String base = UPSTREAM.baseUrl();
        List<Long> ids = CANDIDATES.get();
        List<ChannelDescriptor> channels = new ArrayList<>(ids.size());
        List<ModelRouteDescriptor> routes = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            String prefix = PREFIX_OF.get(id);
            channels.add(new ChannelDescriptor(id, prefix.substring(1), base + prefix, cipherText(), 1, 1_000,
                    ChannelDescriptor.STATUS_ACTIVE, 100, i));
            routes.add(new ModelRouteDescriptor("wiremock-model", id, 100, i, "ACTIVE"));
        }
        return new ConfigSnapshot(SNAPSHOT_VERSION.incrementAndGet(), System.currentTimeMillis(),
                channels, routes, List.of(), "wiremock-model");
    }

    /** 429 / 超时 / 中途断流三种注入，再加一条健康渠道（用类名限定 WireMock，理由见上面的 import 注释）。 */
    private static void stubRateLimited() {
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo(RATE_LIMITED + CHAT))
                .willReturn(WireMock.aResponse()
                        .withStatus(429).withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "1")
                        .withBody("{\"error\":{\"message\":\"rate limited by stub\"}}")));
    }

    private static void stubHanging(long delayMillis) {
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo(HANGING + CHAT))
                .willReturn(WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "application/json")
                        .withFixedDelay((int) delayMillis).withBody(FakeUpstream.completionJson())));
    }

    private static void stubBrokenStream() {
        // 状态行与响应头先出去、body 中途炸掉：这正是「流已经开始回写」的形态。
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo(BROKEN_STREAM + CHAT))
                .willReturn(WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "text/event-stream")
                        .withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
    }

    private static void stubHealthy() {
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo(HEALTHY + CHAT))
                .willReturn(WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(FakeUpstream.completionJson())));
    }

    /** WireMock 的**请求日志**：spec 那句「能自动切换」的可证伪形式 —— 哪条渠道被打了几次。 */
    private static int requestsTo(String prefix) {
        return UPSTREAM.findAll(WireMock.getRequestedFor(WireMock.urlPathEqualTo(prefix + CHAT))).size();
    }

    /** ① spec 的原话「注入 429 → 能自动切换」：第一条候选 429，客户端拿到健康渠道的 200。 */
    @Test
    void wireMock429OnTheFirstChannelSwitchesToTheNextCandidate() throws Exception {
        CANDIDATES.set(List.of(11L, 14L));   // 候选顺序：rate-limited → healthy
        stubRateLimited();
        stubHealthy();

        var response = post("{\"model\":\"wiremock-model\",\"stream\":false}");

        assertThat(response.statusCode()).as("429 必须被换掉，而不是透传给客户端").isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-1");
        assertThat(requestsTo(RATE_LIMITED)).as("返回 429 的那条真的被打过一次").isEqualTo(1);
        assertThat(requestsTo(HEALTHY)).as("切换后的那条被调用").isEqualTo(1);
    }

    /** ② spec 的原话「注入超时 → 能自动切换」：第一条候选挂住超过 timeoutMs，切到健康渠道。 */
    @Test
    void wireMockTimeoutOnTheFirstChannelSwitchesToTheNextCandidate() throws Exception {
        CANDIDATES.set(List.of(12L, 14L));   // 候选顺序：hanging → healthy
        stubHanging(3_000L);                 // 远大于渠道 timeoutMs=1000
        stubHealthy();

        var response = post("{\"model\":\"wiremock-model\",\"stream\":false}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-1");
        assertThat(requestsTo(HANGING)).as("挂住的渠道被尝试过（然后超时）").isEqualTo(1);
        assertThat(requestsTo(HEALTHY)).as("超时后切到了健康渠道").isEqualTo(1);
    }

    /**
     * ③ **中途断流不切换**（§9 的「仅在未输出任何 token 时允许切换」）：换渠道的第一步是把
     * 响应头与 body 原样写回客户端，一旦开始写就不能再换 —— 否则会把半截响应拼成脏数据。
     * 这里用请求日志证明「健康渠道一次都没被打」。
     */
    @Test
    void wireMockMidStreamBreakIsNotSwitchedBecauseTheStreamAlreadyStarted() throws Exception {
        CANDIDATES.set(List.of(13L, 14L));   // 候选顺序：broken-stream → healthy
        stubBrokenStream();
        stubHealthy();

        var response = post("{\"model\":\"wiremock-model\",\"stream\":true}");

        assertThat(response.body()).as("不得变成另一条渠道的完整响应").doesNotContain("chatcmpl-1");
        assertThat(requestsTo(BROKEN_STREAM)).isEqualTo(1);
        assertThat(requestsTo(HEALTHY)).as("已经开始回写 → 不得再换渠道").isZero();
    }

    /** ④ 打到了哪一条、以及计量记的是**实际服务**的那条（WireMock 请求日志 + 计量事件双向对账）。 */
    @Test
    void wireMockRequestJournalAndMeteringAgreeOnTheChannelThatServed() throws Exception {
        CANDIDATES.set(List.of(11L, 14L));
        stubRateLimited();
        stubHealthy();

        var response = post("{\"model\":\"wiremock-model\",\"stream\":false}");
        MeteringEvent event = awaitEvent(response);

        assertThat(requestsTo(RATE_LIMITED)).isEqualTo(1);
        assertThat(requestsTo(HEALTHY)).isEqualTo(1);
        assertThat(event).isNotNull();
        assertThat(event.channelId()).as("计量记的是实际服务的那条渠道（14），不是首选的那条（11）")
                .isEqualTo(14L);
    }

    /** ⑤ 所有候选都挂 → 客户端拿到**最后一个失败的本来面目**（原样状态码），不伪造、不吞掉。 */
    @Test
    void everyWireMockChannelFailingYieldsTheLastRealFailure() throws Exception {
        CANDIDATES.set(List.of(11L, 12L, 13L, 14L));
        stubRateLimited();
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo(HANGING + CHAT))
                .willReturn(WireMock.aResponse().withStatus(503)));
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo(BROKEN_STREAM + CHAT))
                .willReturn(WireMock.aResponse().withStatus(502)));
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo(HEALTHY + CHAT))
                .willReturn(WireMock.aResponse()
                        .withStatus(504).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"last one down\"}}")));

        var response = post("{\"model\":\"wiremock-model\",\"stream\":false}");

        assertThat(response.statusCode()).as("最后一个候选的 504 原样透传").isEqualTo(504);
        assertThat(response.body()).contains("last one down");
        assertThat(requestsTo(RATE_LIMITED)).isEqualTo(1);
        assertThat(requestsTo(HANGING)).isEqualTo(1);
        assertThat(requestsTo(BROKEN_STREAM)).isEqualTo(1);
        assertThat(requestsTo(HEALTHY)).isEqualTo(1);
    }

    // --- 与 FailoverRelayTest 逐字同款的脚手架（照抄那个类） -----------------
    // masterKey() / cipherText() / post(String) / awaitEvent(HttpResponse) / sha256Hex(String)
}
```

> **注意 1**：本类的 `ApiKeyView` 同样是 **6 个分量**（`..., null, 42L)`）—— 决策 14 的契约在 **Task 2**
> 就已落地，本任务（含 `FailoverRelayTest`）按 6 个分量写，不需要也不允许留占位。
>
> **注意 2（启动顺序陷阱，实测过同类问题）**：`@RegisterExtension static WireMockExtension` 的
> `beforeAll` **未必**早于 `SpringExtension` 的上下文加载，因此 `@DynamicPropertySource` 里
> **不要**读 `UPSTREAM.baseUrl()` —— 那时它可能还没 start（会抛 `IllegalStateException`，而且表现为
> 「admin 取快照失败 → 走遗留兜底」的假故障）。本类的做法：`aihub.upstream.base-url` 指向死端口，
> **真正的渠道 base-url 在 `FakeAdmin` 响应快照时（请求期）才从 `UPSTREAM.baseUrl()` 取**。
>
> **注意 3**：WireMock 的 stub 与 `CANDIDATES` 都是**每个用例**重置的（`@BeforeEach`），因此用例之间
> 不会互相污染；配置的一级缓存也必须用 `configClient.invalidate()` 清掉（见 `reset()` 里的注释）。

- [ ] **Step 6: 运行 `FailoverRelayTest`（这一步会红，因为控制器还没改）**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=FailoverRelayTest"
```

预期：编译失败（`ChatRelayController` 的构造器还没换签名）或运行时红（未知模型没有 404、channel_id 为 null）。**这是预期的红**，下一步实现控制器。

- [ ] **Step 7: 重写 `ChatRelayController` 的转发路径**

在 `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java` 里：

**（a）`RELAYED_HEADERS` 追加三个名字**（M2 的精确名单不变，只补 M3 需要的）：

```java
    private static final Set<String> RELAYED_HEADERS = Set.of(
            "content-type",
            // 上游限流/退避信号：客户端唯一的依据，吃掉它等于让 SDK 瞎猜（M3 的治理也依赖它）。
            "retry-after",
            // M3 新增：Azure 风格的毫秒级退避（M2 刻意留下的 M3 尾巴之一）。
            "retry-after-ms",
            "x-ratelimit-limit-requests",
            "x-ratelimit-limit-tokens",
            "x-ratelimit-remaining-requests",
            "x-ratelimit-remaining-tokens",
            "x-ratelimit-reset-requests",
            "x-ratelimit-reset-tokens",
            // M3 新增：IETF 的 RateLimit 字段族（同样是**精确名**，不是前缀匹配）。
            "ratelimit-limit",
            "ratelimit-remaining",
            "ratelimit-reset");
```

**（b）字段与构造器**：

```java
    private final UpstreamClientFactory clientFactory;
    private final ConfigClient configClient;
    private final RouteResolver routeResolver;
    private final ChannelCircuitBreaker circuitBreaker;
    private final ChannelKeyDecryptor keyDecryptor;
    private final MeteringPublisher meteringPublisher;
    private final MeteringProperties meteringProperties;

    public ChatRelayController(UpstreamClientFactory clientFactory, ConfigClient configClient,
                               RouteResolver routeResolver, ChannelCircuitBreaker circuitBreaker,
                               ChannelKeyDecryptor keyDecryptor, MeteringPublisher meteringPublisher,
                               MeteringProperties meteringProperties) {
        this.clientFactory = clientFactory;
        this.configClient = configClient;
        this.routeResolver = routeResolver;
        this.circuitBreaker = circuitBreaker;
        this.keyDecryptor = keyDecryptor;
        this.meteringPublisher = meteringPublisher;
        this.meteringProperties = meteringProperties;
    }
```

**（c）`chatCompletions` 整体替换**：

```java
    @PostMapping(path = CHAT_COMPLETIONS_PATH)
    public Mono<Void> chatCompletions(@RequestBody String body, ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        RelayRequestBody.Prepared prepared = RelayRequestBody.prepare(body);
        // 必须在请求入口（响应提交之前）取 request_id：RequestIdFilter.ensure 会写响应头，
        // 而响应一旦提交，getHeaders() 变成只读，迟到的 ensure 会直接抛异常。
        String requestId = RequestIdFilter.ensure(exchange);
        RelayMetering metering = RelayMetering.start(exchange, requestId, prepared.model(),
                prepared.streaming(), meteringProperties.maxCaptureBytes());

        List<ChannelDescriptor> candidates;
        try {
            candidates = candidateList(prepared.model());
        } catch (RouteSelectionException e) {
            metering.onUnexpectedError();
            meteringPublisher.publish(metering.toEvent(SignalType.ON_COMPLETE));
            log.debug("模型没有可用渠道: {}", e.model());
            return GatewayErrors.write(response, HttpStatus.NOT_FOUND,
                    "invalid_request_error", "model_not_found", "不提供该模型: " + e.model());
        }

        return attempt(candidates, 0, prepared, response, metering)
                .onErrorResume(WebClientRequestException.class, ex -> {
                    if (response.isCommitted()) {
                        // 响应已提交说明是**流中途**断的：状态码改不了，只能收尾 + 记 ERROR。
                        metering.onUpstreamFailure(MeteringEvent.ERROR_UPSTREAM_STREAM);
                        log.warn("上游流中途失败（响应已提交，无法改状态码）: {} : {}",
                                ex.getClass().getName(), ex.getMessage());
                        return response.setComplete();
                    }
                    // 上游内网地址/异常细节只写日志，不回给客户端。
                    metering.onUpstreamFailure(MeteringEvent.ERROR_UPSTREAM_UNREACHABLE);
                    log.warn("upstream request failed, returning 502 upstream_unreachable ({}) : {}",
                            ex.getClass().getName(), ex.getMessage());
                    return GatewayErrors.write(response, HttpStatus.BAD_GATEWAY,
                            "api_error", "upstream_unreachable", "Upstream service is unreachable");
                })
                .onErrorResume(ex -> {
                    if (!response.isCommitted()) {
                        metering.onUnexpectedError();
                        return Mono.error(ex);
                    }
                    metering.onClientDisconnected();
                    log.warn("回写客户端失败（响应已提交，按客户端断连计量）: {} : {}",
                            ex.getClass().getName(), ex.getMessage());
                    return response.setComplete();
                })
                .doFinally(signal -> meteringPublisher.publish(metering.toEvent(signal)));
    }

    /**
     * 候选列表：路由结果经「密钥可服务」过滤；为空时回落到「遗留单渠道」（**只在快照里真的有
     * 遗留渠道时才回落**）；再为空 → 抛 {@link RouteSelectionException} 由调用方翻成 404。
     */
    private List<ChannelDescriptor> candidateList(String model) {
        List<ChannelDescriptor> candidates = RelayAttempts.servable(routeResolver.candidates(model), keyDecryptor);
        if (!candidates.isEmpty()) {
            return candidates;
        }
        ConfigSnapshot snapshot = configClient.current();
        ChannelDescriptor legacy = configClient.legacyChannel();
        boolean hasLegacy = snapshot.channels().stream().anyMatch(channel -> LegacyChannel.isLegacy(channel.id()));
        if (hasLegacy && keyDecryptor.canServe(legacy)) {
            return List.of(legacy);
        }
        throw new RouteSelectionException(model);
    }

    /**
     * 逐个候选尝试。**每次尝试都是延迟订阅**（{@code Mono.defer}），因此前一个候选只要
     * 「没有被写出去一个字节」，就还可以换下一个。
     *
     * <p>切换的判据是上游**响应头到达之后、响应体写回之前**这个窗口：
     * <ul>
     *   <li>连接失败/超时 → 抛 {@code WebClientRequestException} → 交给控制器顶层的 502/收尾分支；</li>
     *   <li>429 → 打熔断标记后换下一个；</li>
     *   <li>5xx → 换下一个（不打熔断）；</li>
     *   <li>其他（2xx / 4xx）→ **直接回写**，不再有下一次。</li>
     * </ul>
     * 一旦 {@link #relay} 开始写字节，响应就被提交，此后异常只会走收尾分支 ——
     * 这就是「仅在未输出任何 token 时允许切换」的机器实现。
     */
    private Mono<Void> attempt(List<ChannelDescriptor> candidates, int index, RelayRequestBody.Prepared prepared,
                               ServerHttpResponse response, RelayMetering metering) {
        if (index >= candidates.size()) {
            // 所有候选都在「响应未提交」阶段失败：用一个明确的 502 收尾（上次的失败已写日志）。
            return Mono.error(new WebClientRequestException(
                    new java.io.IOException("所有候选渠道都失败了"), HttpMethod.POST,
                    java.net.URI.create(CHAT_COMPLETIONS_PATH), HttpHeaders.EMPTY));
        }
        ChannelDescriptor channel = candidates.get(index);
        metering.onChannelSelected(channel);
        Optional<String> upstreamKey = keyDecryptor.upstreamKey(channel);
        if (upstreamKey.isEmpty()) {
            // 解不开密钥：不浪费一次往返，直接跳到下一个候选。
            log.warn("渠道 {} 的密钥不可用，跳过该候选", channel.id());
            return attempt(candidates, index + 1, prepared, response, metering);
        }
        WebClient client = clientFactory.forChannel(channel, prepared.streaming());
        String apiKey = upstreamKey.get();

        return Mono.defer(() -> {
            WebClient.RequestBodySpec spec = client.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 同时接受 SSE 与 JSON：上游返回哪种都能透传，无需在网关侧分流。
                    .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON);
            if (!apiKey.isEmpty()) {
                spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }
            return spec.bodyValue(prepared.bodyToForward())
                    .exchangeToMono(upstream -> {
                        int status = upstream.statusCode().value();
                        if (RelayAttempts.shouldFailoverBeforeCommit(status)) {
                            if (status == 429) {
                                circuitBreaker.markOpen(channel.id());
                            }
                            log.warn("渠道 {}（{}）返回 {}，尝试下一个候选渠道（第 {} 个候选失败）",
                                    channel.id(), channel.name(), status, index + 1);
                            // releaseBody 防止连接泄漏，然后再切下一个。
                            return upstream.releaseBody().then(
                                    Mono.defer(() -> attempt(candidates, index + 1, prepared, response, metering)));
                        }
                        return relay(upstream, response, metering);
                    });
        });
    }
```

**（d）`relay(...)` 的方法体保持不变**（状态码 → 白名单头 → 字节流 + 计量 tap）。**不要**改它：M1/M2 的字节级透传就在这段代码里。

**（e）补 import**：

```java
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.config.LegacyChannel;
import com.aihub.gateway.route.ChannelCircuitBreaker;
import com.aihub.gateway.route.RouteResolver;
import com.aihub.gateway.route.RouteSelectionException;
import com.aihub.gateway.upstream.UpstreamClientFactory;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.SignalType;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
```

> **`attempt` 的终止分支**：上面用「构造一个 `WebClientRequestException`」来表达「所有候选都失败」，
> 这样客户端拿到的是统一的 **502 `upstream_unreachable`**，且**不会**泄漏内部异常消息。
> 若实施者觉得构造该异常太绕，可以（**允许**）改成一个自定义异常并在控制器顶层加一条 `onErrorResume`，
> 但**不要**改成返回 `Mono.empty()`（那会让客户端拿到一个没有状态码的空响应）。

- [ ] **Step 8: 运行 `FailoverRelayTest` 与 `WireMockChannelFaultInjectionTest`，确认全绿**

```powershell
mvn -B -pl aihub-gateway test -am "-Dtest=FailoverRelayTest,WireMockChannelFaultInjectionTest"
```

预期：`Tests run: 12, Failures: 0, Errors: 0` + `BUILD SUCCESS`（JDK 夹具 7 + WireMock 验收 5）。
常见红法与诊断：
- `upstream429SwitchesImmediatelyAndMarksTheChannel` 红且 `standby.lastRequest()` 为 null → 429 没进 `shouldFailoverBeforeCommit` 分支；
- `upstreamTimeoutSwitchesToTheNextCandidate` 红 → 检查 `enqueueStall` 的 holdMillis（8000）是否大于 `channel.timeoutMs`（5000）；
- `perChannelKeyIsInjectedIntoTheUpstreamRequest` 红 → 检查 `spec.header(...)` 是不是在 `.bodyValue(...)` **之后**才设（那样头不会生效）；
- `unknownModelReturns404WithTheOpenAiBody` 红 → 检查 `candidateList` 是否在「没有候选」时错误地回落到遗留渠道（只有当快照里**真的**有 `LegacyChannel.ID` 时才允许回落）。

- [ ] **Step 9: 跑网关全部测试（回归 M1/M2 的全部字节透传与计量用例）**

```powershell
mvn -B -pl aihub-gateway test
```

预期：`Tests run: 242, Failures: 0, Errors: 0`（Task 9 的 223 + JDK 夹具 7 + 7 + WireMock 验收 5）。

**重点确认这几条仍然绿**（它们就是铁律的防线）：
- `ChatRelayControllerTest` 的「请求体逐字节不变」与 SSE / JSON 透传用例；
- `SseStreamingTest`（逐帧 flush + 客户端断连计量）；
- `UnreachableUpstreamTest`（502 + stable body）；
- `RelayCommittedWriteFailureTest`（响应已提交后的写失败不重试、按客户端断连计量）；
- `RelayMeteringFlowTest`（`x-request-id` == 事件 `request_id`）。

若有红星且原因不明，**先跑单类定位**（用逗号形式），不要把测试改松。

- [ ] **Step 10: 提交**

```powershell
git add aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java aihub-gateway/src/main/java/com/aihub/gateway/relay/RelayAttempts.java aihub-gateway/src/main/java/com/aihub/gateway/meter/RelayMetering.java aihub-gateway/src/test/java/com/aihub/gateway/relay/RelayAttemptsTest.java aihub-gateway/src/test/java/com/aihub/gateway/relay/FailoverRelayTest.java aihub-gateway/src/test/java/com/aihub/gateway/relay/WireMockChannelFaultInjectionTest.java aihub-gateway/src/test/java/com/aihub/gateway/testsupport/FakeUpstream.java aihub-gateway/pom.xml
git commit -m "feat: fail over across channel candidates with a pre-commit switch window"
```

**验收标准**
1. 上游 **429** → 给该渠道打熔断标记并**立即**换下一个候选；**5xx** → 换候选但**不**打熔断；**连接失败/超时** → 换候选（换不上则 502 `upstream_unreachable`）。
2. 上游 **4xx（非 429）** → **原样透传，不切换**（有专门用例断言备用渠道一次都没被调用）。
3. 所有候选失败 → 客户端拿到**原样的状态码与 body**（用例：502 + `standby down`）。
4. 未知模型（且快照里没有遗留渠道）→ **404 + OpenAI 形状** `model_not_found`。
5. 每个渠道的上游请求带的是**该渠道解密出来的密钥**（覆盖客户端带来的 `Authorization`）。
6. 计量事件的 `channel_id` = **实际服务**的那个渠道（不是首选的那个，也不是 NULL）。
7. M1 的字节透传用例全部保持绿色。
8. **spec §10 / §12 的里程碑验收由 `WireMockChannelFaultInjectionTest` 承担**：WireMock 的命名 stub 扮多条渠道，分别注入 429 / 挂住超时 / 中途断流，网关自动切换；中途断流（已开始回写）时**不**切换，且请求日志能证明「哪几条渠道被打过、各打了几次」。该依赖**只**是 `aihub-gateway` 的 test 作用域，且**进程内**运行（不引入 Docker / 活 broker 依赖）。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-gateway test -am "-Dtest=RelayAttemptsTest"` | `Tests run: 7, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-gateway test -am "-Dtest=FailoverRelayTest,WireMockChannelFaultInjectionTest"` | `Tests run: 12, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-gateway test` | `Tests run: 242, Failures: 0, Errors: 0` + `BUILD SUCCESS` |

---

## Task 11: M2 遗留（一）：`api_key_id` / `channel_id` / 超长 `model` 与透传白名单的回归

**Files:**
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/meter/RelayMetering.java`（计量侧填 `view.apiKeyId()` + model 截断 128）
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/relay/ModelsController.java`（快照 ∪ 遗留默认）
- Modify: `aihub-gateway/src/test/java/com/aihub/gateway/relay/ModelsControllerTest.java`
- Modify: `aihub-gateway/src/test/java/com/aihub/gateway/relay/RelayMeteringFlowTest.java`
- Modify: `aihub-gateway/src/test/java/com/aihub/gateway/relay/ChatRelayControllerTest.java`

> **本任务不再包含任何共享契约工作**：`ApiKeyView` 的第 6 个分量、`ApiKeyCacheCodec` 的 5 → 6 段、
> `ApiKeyService` 的填充、`AdminClient.parse` 的透传、`RateLimitFilter` 读数值主键与它们各自的测试，
> 全部已经前移（**决策 14 的契约在 Task 2**、限流侧的消费在 **Task 9**）。本任务只做三件 M2 遗留：
> ① `api_key_id` / `channel_id` **落进计量事件与 `request_log`**；② 超长 `model` 截断；③ 透传白名单
> 新增 `retry-after-ms` 与 IETF `RateLimit-*`。

**Interfaces:**
- Consumes：Task 2（`ApiKeyView.apiKeyId()` —— 契约已就位，直接使用，无占位）、Task 10 的 `RelayMetering.onChannelSelected` 与 `RelayAttempts.truncateModel(...)`、Task 7 的 `AdminClient.parse`（已是 6 个分量）。
- Produces：
  - `RelayMetering` 的 `apiKeyId` 取自 `view.apiKeyId()`（**Task 2 的契约**）；事件的 `model` 用 `RelayAttempts.truncateModel(...)` 截到 128（决策 15）。
  - `ModelsController`：`GET /v1/models` 返回「快照里的模型名 ∪ 遗留默认模型」（去重排序）。

- [ ] **Step 1: 改 `RelayMetering`（计量侧的 `apiKeyId` 与 model 截断）**

在 `RelayMetering.start(...)` 里把 `null` 换成视图里的值：

```java
                // 决策 14：数值 api_key_id 进共享契约后，用量第一次能按 API Key 聚合。
                view == null ? null : view.apiKeyId(),
```

在 `toEvent(...)` 里把 model 截断：

```java
        return new MeteringEvent(requestId, tenantId, apiKeyId, channelId.get(),
                RelayAttempts.truncateModel(model),
                promptTokens, completionTokens, totalTokens,
                capture.latencyMs(), captured.ttftMs(), resolvedStatus, code,
                createdAt.toEpochMilli());
```

并补 import：`import com.aihub.gateway.relay.RelayAttempts;`。

- [ ] **Step 2: 改 `ModelsController`（模型列表来自快照 ∪ 遗留默认）**

把 `ModelsController` 的依赖从 `UpstreamProperties` 改为 `(UpstreamProperties, ConfigClient)`：

```java
/**
 * OpenAI 兼容的模型列表：**快照里出现过的模型名 ∪ 遗留默认模型**（去重排序）。
 *
 * <p>M1/M2 只回报一个配置的默认模型（单渠道）；M3 起真正的模型集合来自配置快照的
 * {@code model_route}（「这个平台提供哪些模型」= 路由表里出现过的模型名）。
 * **仍然并入遗留默认模型**：迁移期（或 admin 不可达走了兜底）时不能突然把这个端点变成空列表
 * —— 那会让所有客户端以为平台没有任何模型。
 *
 * <p>与 {@code ChatRelayController} 一样，这里**不回源 admin**：读的是已经缓存好的快照，
 * 因此上游或控制面抖动不会把这个端点变成故障（它与上游其实完全无关）。
 * 鉴权由 {@code ApiKeyAuthFilter} 统一加在 {@code /v1/**} 前面，本类不重复实现。
 */
@RestController
public class ModelsController {

    private final UpstreamProperties properties;
    private final ConfigClient configClient;

    public ModelsController(UpstreamProperties properties, ConfigClient configClient) {
        this.properties = properties;
        this.configClient = configClient;
    }

    @GetMapping("/v1/models")
    public Mono<Map<String, Object>> listModels() {
        Set<String> models = new TreeSet<>(configClient.current().modelNames());
        String defaultModel = properties.defaultModel();
        if (StringUtils.hasText(defaultModel)) {
            models.add(defaultModel);
        }
        List<Map<String, Object>> data = models.stream()
                .map(id -> Map.<String, Object>of("id", id, "object", "model", "owned_by", "aihub"))
                .toList();
        return Mono.just(Map.of("object", "list", "data", data));
    }
}
```

补 import：`com.aihub.gateway.config.ConfigClient`、`java.util.Set`、`java.util.TreeSet`。

**并把 `ModelsControllerTest` 改成直接构造的单元测试**（既有 2 条用例若因构造器变化而红，按新签名调整）：

```java
    /**
     * 快照里有 model-a / model-b，遗留默认是 legacy → 三个都在，且排序去重。
     */
    @Test
    void listsTheUnionOfSnapshotModelsAndTheLegacyDefaultModel() {
        assertThat(ids()).containsExactly("legacy", "model-a", "model-b");
    }

    /** 没有快照（冷启动 + admin 不可达）时仍然回报遗留默认模型，而不是空列表。 */
    @Test
    void fallsBackToTheLegacyDefaultModelWhenThereIsNoSnapshot() {
        assertThat(ids()).containsExactly("legacy");
    }

    private List<String> ids() {
        // 直接构造：不需要 Spring 上下文，也不需要真实 ConfigCache。
        ModelsController controller = new ModelsController(
                new UpstreamProperties("http://127.0.0.1:11434", null, "legacy"),
                configClientReturning(snapshotWithModels("model-b", "model-a")));
        Map<String, Object> body = controller.listModels().block(Duration.ofSeconds(5));
        // ... 从 body 的 data 里取 id 列表
    }
```

> 上面是**写法示意**：实施者按该测试类既有的风格补齐 `configClientReturning(...)` 与
> `snapshotWithModels(...)` 两个私有助手（都用 `new ConfigClient(cache, admin, upstream, properties)` 或
> 直接构造 `ConfigClient` 的子类返回固定快照）。**不要**为一个测试去给 `ConfigClient` 抽接口。

- [ ] **Step 3: 给 `RelayMeteringFlowTest` 补两条断言（决策 14/15 的消费侧证据）**

在假 admin 的视图里补 `apiKeyId`（**契约在 Task 2 已落地，这里按 6 个分量写**）：

```java
                        ? Mono.just(Optional.of(new ApiKeyView("ak_flow", 7L, "demo",
                                ApiKeyView.STATUS_ACTIVE, null, 42L)))
```

并新增两条用例：

```java
    /**
     * M2 决策 8 留下的缺口（{@code api_key_id} / {@code channel_id} 恒为 NULL）在本里程碑关闭：
     * {@code apiKeyId} 来自共享的 {@code ApiKeyView}（决策 14），{@code channelId} 来自路由结果
     * （多渠道让「哪条渠道服务了这次请求」第一次有含义）。单渠道兜底路径会写
     * {@code LegacyChannel.ID}（哨兵，不是 NULL），因此「走了兜底」在库里可查。
     */
    @Test
    void meteringCarriesTheNumericApiKeyIdAndTheServingChannelId() throws Exception {
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"flow-model\",\"stream\":false}");

        MeteringEvent event = recorder.awaitEvent(
                response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow(), Duration.ofSeconds(5));
        assertThat(event).isNotNull();
        assertThat(event.apiKeyId()).isEqualTo(42L);
        assertThat(event.channelId()).as("兜底单渠道也必须给出可区分的渠道 id").isNotNull();
    }

    /**
     * 决策 15：超长 model **只**在计量事件里被截断到 128（{@code request_log.model} 是 VARCHAR(128)），
     * 转发给上游的请求体**逐字节不变**。M2 时这一行会 INSERT 失败 → 重试 3 次 → 进死信队列，
     * 而 DLQ 是任何持合法 API Key 的客户端都能触碰的入口。
     */
    @Test
    void oversizedModelIsTruncatedInTheEventButForwardedVerbatim() throws Exception {
        String longModel = "m".repeat(200);
        upstream.enqueueJson(200, FakeUpstream.completionJson());

        HttpResponse<String> response = post("{\"model\":\"" + longModel + "\",\"stream\":false}");

        MeteringEvent event = recorder.awaitEvent(
                response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow(), Duration.ofSeconds(5));
        assertThat(event).isNotNull();
        assertThat(event.model()).hasSize(128);
        assertThat(upstream.lastRequest().body()).as("上游必须收到客户端原始的长模型名").contains(longModel);
    }
```

- [ ] **Step 4: 给 `ChatRelayControllerTest` 补透传白名单的回归**

在该测试类里新增一条（**沿用该类既有私有助手的写法**，不要新造）：

```java
    /**
     * M2 的透传白名单是**精确名**（前缀匹配会把白名单变成开放集合）。M3 补进了
     * {@code retry-after-ms} 与 IETF 的 {@code RateLimit-*} 三兄弟，但**不得**顺手放宽成前缀匹配
     * —— 这条用例用一个「差一点」的头名（{@code retry-after-ms-x}）证明白名单仍然是封闭集合。
     */
    @Test
    void relaysRetryAfterMsAndTheIetfRateLimitFamilyButNothingElse() {
        upstream.enqueueWithHeaders(429, "application/json; charset=utf-8",
                "{\"error\":{\"message\":\"slow down\"}}",
                Map.of("Retry-After", "3",
                        "Retry-After-Ms", "250",
                        "RateLimit-Limit", "10, 20",
                        "RateLimit-Remaining", "0",
                        "RateLimit-Reset", "1",
                        "Retry-After-Ms-X", "should-not-pass",
                        "X-RateLimit-Limit-Requests", "100"));

        HttpResponse<String> response = post("{\"stream\":false}");

        assertThat(response.headers().firstValue("retry-after")).contains("3");
        assertThat(response.headers().firstValue("retry-after-ms")).contains("250");
        assertThat(response.headers().firstValue("ratelimit-limit")).contains("10, 20");
        assertThat(response.headers().firstValue("ratelimit-remaining")).contains("0");
        assertThat(response.headers().firstValue("ratelimit-reset")).contains("1");
        assertThat(response.headers().firstValue("x-ratelimit-limit-requests")).contains("100");
        assertThat(response.headers().firstValue("retry-after-ms-x"))
                .as("白名单是精确名：差一点的名字不得穿过").isNull();
    }
```

- [ ] **Step 5: 运行受影响的测试**

```powershell
mvn -B -pl aihub-gateway test
mvn -B -pl aihub-admin/aihub-common test
```

预期：`aihub-gateway 246`（Task 10 的 242 + 2 条新增 e2e + 1 条白名单用例 + ModelsController 净增 1）；
`aihub-common 48`（**本任务不再改动 `aihub-common`** —— 决策 14 的契约与它的两条契约测试都在 Task 2 落地，
这里的 48 是**回归核对**，不是本任务的增量）。

- [ ] **Step 6: 跑 admin 全量（确认「`api_key_id` 真的有值」这条链路没打破 admin 的 47 项）**

```powershell
$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test
```

预期：`aihub-web 47`，`Failures: 0, Errors: 0`。若 `ApiKeyMintAndResolveTest` 变红且报「api_key_id 为 null」，
那是 **Task 2 的 `ApiKeyService` 填充**被改坏了（`mint` / `loadFromDb` 各一行）—— 本任务不应再动 `ApiKeyService`，
回 Task 2 检查。

- [ ] **Step 7: 提交**

```powershell
git add aihub-gateway/src/main/java/com/aihub/gateway/meter/RelayMetering.java aihub-gateway/src/main/java/com/aihub/gateway/relay/ModelsController.java aihub-gateway/src/test/java/com/aihub/gateway/relay/ModelsControllerTest.java aihub-gateway/src/test/java/com/aihub/gateway/relay/RelayMeteringFlowTest.java aihub-gateway/src/test/java/com/aihub/gateway/relay/ChatRelayControllerTest.java
git commit -m "feat: persist the api key and channel ids into metering and relay the rate limit header family"
```

**验收标准**
1. **计量事件的 `api_key_id` 有值**（e2e 用例断言 `42L`）：它取自 Task 2 就已就位的共享契约 `ApiKeyView.apiKeyId()`，本任务只是把它**落进计量事件**；`channel_id` 也第一次有值（实际服务的渠道；兜底路径是 `LegacyChannel.ID` 哨兵）。
2. 超长 `model`：事件里被截到 128；上游收到的是**原始**长模型名（转发逐字节不变）。
3. 透传白名单新增 `retry-after-ms` 与 `ratelimit-limit/remaining/reset`，仍然是**精确名**。
4. `GET /v1/models` = 快照模型名 ∪ 遗留默认模型；无快照时仍然返回遗留默认模型。
5. **本任务不引入任何共享契约变更**：`ApiKeyView` / `ApiKeyCacheCodec` / `ApiKeyService` / `AdminClient.parse` / `RateLimitFilter` 的改动都已在 Task 2（契约）与 Task 9（消费）完成，本任务的文件列表里没有它们。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-gateway test` | `Tests run: 246, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-admin/aihub-common test` | `Tests run: 48, Failures: 0, Errors: 0`（回归核对，非本任务增量） |
| `$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test` | `Tests run: 47, Failures: 0, Errors: 0` |

---

## Task 12: admin 侧的渠道/路由/限流 DAO 与加密服务

**Files:**
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ChannelEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ModelRouteEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/RateLimitPolicyEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/ChannelMapper.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/ModelRouteMapper.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/RateLimitPolicyMapper.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/channel/ChannelKeyService.java`
- Modify: `aihub-admin/aihub-web/src/main/resources/application.yml`（`aihub.channel.master-key`）
- Test: `aihub-web/src/test/java/com/aihub/admin/channel/ChannelKeyServiceTest.java`

**Interfaces:**
- Consumes：Task 1（`AesGcmChannelCipher` / `ChannelKeyRegistry`）。
- Produces：
  - `ChannelEntity`（`@TableName("channel")`：`id` / `name` / `provider` / `baseUrl` / `apiKeyCipher` / `keyVersion` / `modelsJson`(String) / `weight` / `priority` / `timeoutMs` / `status`，getter/setter 齐全，**照抄既有 `ApiKeyEntity` 的写法**：无 Lombok）。
  - `ModelRouteEntity`（`modelName` / `channelId` / `weight` / `priority` / `status`）。
  - `RateLimitPolicyEntity`（`tenantId` / `apiKeyId` / `qps` / `burst` / `status`）。
  - 三个 Mapper：`interface X extends BaseMapper<XEntity> {}`（不加自定义 SQL：M3 只需要全表读取）。
  - `ChannelKeyService`：构造器 `ChannelKeyService(@Value("${aihub.channel.master-key:}") String masterKey)`；`String encrypt(String)`（**无主密钥时抛 `IllegalStateException`**，消息里给出配置指引与生成命令，**不含任何密钥内容**）；`Optional<String> decrypt(String)`；`int currentKeyVersion()`；`boolean configured()`。

**测试用例清单**（`ChannelKeyServiceTest`，6 条，纯单元测试，不需要容器）：

| 用例 | 钉住什么 |
|---|---|
| `encryptsAndDecryptsAChannelKey` | 往返 + `configured()` + `currentKeyVersion()` |
| `encryptFailsLoudlyWithoutAMasterKey` | 空主密钥 → `IllegalStateException`，消息里提到 `AIHUB_CHANNEL_MASTER_KEY` |
| `cipherTextIsUsableByTheGatewaySideImplementation` | **跨侧互操作**：用 gateway 侧同款 `AesGcmChannelCipher` 解开 admin 产出的密文 |
| `cipherTextCarriesTheCurrentKeyVersion` | 双版本时用最大版本加密 |
| `decryptReturnsEmptyForForeignCipherText` | 别的主密钥加密的密文 → 空（不抛） |
| `neverExposesTheMasterKeyInMessages` | `toString` 与异常消息不含主密钥 base64 |

- [ ] **Step 1: 写三个实体与三个 Mapper**

创建 `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ChannelEntity.java`：

```java
package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对应 Flyway V1 的 {@code channel} 表。
 *
 * <p>{@code apiKeyCipher} 是 **AES-GCM 密文**（{@code v{n}:{base64}}）：明文渠道密钥从不入库
 * （设计文档 §6.1）。{@code keyVersion} 是 admin 侧记录的版本号，与密文里的标签一致。
 *
 * <p>{@code modelsJson} 用 {@link String} 映射 {@code JSON} 列：M3 的路由只用 {@code model_route}
 * 表，不解析这个列。字段名到列名的下划线映射由 MyBatis-Plus 的默认策略完成，与既有
 * {@code ApiKeyEntity} 的写法一致（无 Lombok、显式 getter/setter）。
 */
@TableName("channel")
public class ChannelEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String provider;
    private String baseUrl;
    private String apiKeyCipher;
    private Integer keyVersion;
    private String modelsJson;
    private Integer weight;
    private Integer priority;
    private Integer timeoutMs;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyCipher() {
        return apiKeyCipher;
    }

    public void setApiKeyCipher(String apiKeyCipher) {
        this.apiKeyCipher = apiKeyCipher;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(Integer keyVersion) {
        this.keyVersion = keyVersion;
    }

    public String getModelsJson() {
        return modelsJson;
    }

    public void setModelsJson(String modelsJson) {
        this.modelsJson = modelsJson;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

创建 `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ModelRouteEntity.java`（同款风格）：

```java
package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 对应 Flyway V1 的 {@code model_route} 表：一个模型 → 多条候选渠道（权重 / 优先级 / 状态）。 */
@TableName("model_route")
public class ModelRouteEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelName;
    private Long channelId;
    private Integer weight;
    private Integer priority;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Long getChannelId() {
        return channelId;
    }

    public void setChannelId(Long channelId) {
        this.channelId = channelId;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

创建 `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/RateLimitPolicyEntity.java`：

```java
package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对应 Flyway V1 的 {@code rate_limit_policy} 表。
 * <p>{@code apiKeyId} 可空：为空表示**租户级**策略（该租户所有 key 的兜底）；非空表示 **key 级**策略
 * （只作用于该 {@code api_key.id}）。**两个维度在 M3 都生效**（决策 7，2026-09-26 依控制器 pre-flight
 * 评审修订）：网关侧先找 key 级、再回落租户级。字段与 V1 的列一一对应，两侧不需要各自定义 DTO。
 * （M3 组装进快照但不生效，等 M4 把数值主键接进请求上下文）。
 */
@TableName("rate_limit_policy")
public class RateLimitPolicyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long apiKeyId;
    private Integer qps;
    private Integer burst;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(Long apiKeyId) {
        this.apiKeyId = apiKeyId;
    }

    public Integer getQps() {
        return qps;
    }

    public void setQps(Integer qps) {
        this.qps = qps;
    }

    public Integer getBurst() {
        return burst;
    }

    public void setBurst(Integer burst) {
        this.burst = burst;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

三个 Mapper（内容同构，只有类型不同）：

```java
package com.aihub.dao.mapper;

import com.aihub.dao.entity.ChannelEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** {@code channel} 表的 Mapper。不加自定义 SQL：M3 只需要全表读取（配置快照）。 */
public interface ChannelMapper extends BaseMapper<ChannelEntity> {
}
```

（`ModelRouteMapper extends BaseMapper<ModelRouteEntity>`、`RateLimitPolicyMapper extends BaseMapper<RateLimitPolicyEntity>` 同理。）

- [ ] **Step 2: 写 `ChannelKeyServiceTest` 与 `ChannelKeyService`**

创建 `aihub-admin/aihub-web/src/test/java/com/aihub/admin/channel/ChannelKeyServiceTest.java`：

```java
package com.aihub.admin.channel;

import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import com.aihub.service.channel.ChannelKeyService;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * admin 侧的加密服务。**不需要容器、不需要 Spring**：它只是一层「主密钥 → 密文」的薄封装，
 * 真正的密码学在 {@code aihub-common} 的 {@code AesGcmChannelCipher} 里（两侧共用）。
 *
 * <p>这里最重要的一条是**跨侧互操作**：admin 产出的密文必须能被 gateway 侧的实现解开。
 * 那也是「加密实现只许有一份」这条决策的可证伪形式。
 */
class ChannelKeyServiceTest {

    private static final String SYNTHETIC = "sk-channel-plaintext-synthetic";

    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 17 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void encryptsAndDecryptsAChannelKey() {
        ChannelKeyService service = new ChannelKeyService("v1:" + b64Key(1));

        String cipherText = service.encrypt(SYNTHETIC);

        assertThat(cipherText).startsWith("v1:");
        assertThat(service.decrypt(cipherText)).contains(SYNTHETIC);
        assertThat(service.configured()).isTrue();
        assertThat(service.currentKeyVersion()).isEqualTo(1);
    }

    @Test
    void encryptFailsLoudlyWithoutAMasterKey() {
        ChannelKeyService service = new ChannelKeyService("");

        assertThat(service.configured()).isFalse();
        assertThatThrownBy(() -> service.encrypt(SYNTHETIC))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AIHUB_CHANNEL_MASTER_KEY");
    }

    @Test
    void cipherTextIsUsableByTheGatewaySideImplementation() {
        String masterKey = "v1:" + b64Key(3);
        String cipherText = new ChannelKeyService(masterKey).encrypt(SYNTHETIC);

        // gateway 侧用的是同一个类，但这里显式地「像一个独立的消费方那样」构造它。
        AesGcmChannelCipher gatewaySide = new AesGcmChannelCipher(ChannelKeyRegistry.parse(masterKey));

        assertThat(gatewaySide.decrypt(cipherText)).contains(SYNTHETIC);
    }

    @Test
    void cipherTextCarriesTheCurrentKeyVersion() {
        ChannelKeyService service = new ChannelKeyService("v1:" + b64Key(1) + ",v2:" + b64Key(2));

        assertThat(service.encrypt(SYNTHETIC)).startsWith("v2:");
        assertThat(service.currentKeyVersion()).isEqualTo(2);
    }

    @Test
    void decryptReturnsEmptyForForeignCipherText() {
        ChannelKeyService service = new ChannelKeyService("v1:" + b64Key(1));
        String foreign = new ChannelKeyService("v1:" + b64Key(9)).encrypt(SYNTHETIC);

        assertThat(service.decrypt(foreign)).isEmpty();
        assertThat(service.decrypt("garbage")).isEmpty();
        assertThat(service.decrypt(null)).isEmpty();
    }

    @Test
    void neverExposesTheMasterKeyInMessages() {
        String masterKey = "v1:" + b64Key(5);

        assertThat(new ChannelKeyService(masterKey).toString()).doesNotContain(b64Key(5));
        try {
            new ChannelKeyService("").encrypt(SYNTHETIC);
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).doesNotContain(b64Key(5));
        }
    }
}
```

创建 `aihub-admin/aihub-service/src/main/java/com/aihub/service/channel/ChannelKeyService.java`：

```java
package com.aihub.service.channel;

import com.aihub.common.crypto.AesGcmChannelCipher;
import com.aihub.common.crypto.ChannelKeyRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * admin 侧的渠道密钥加解密入口（**写入路径**：把明文加密后存进 {@code channel.api_key_cipher}）。
 *
 * <p>M3 只有「开发演示数据 seeder」会调用 {@link #encrypt}；M4 的渠道 CRUD 会复用它。
 * 之所以现在就把它独立出来（而不是把加解密散在 seeder 里）：M4 的 CRUD 必须用**同一把**
 * 主密钥与**同一份**格式，否则会出现两种互不相认的密文。
 *
 * <p><b>主密钥只来自环境变量</b>（{@code AIHUB_CHANNEL_MASTER_KEY} → {@code aihub.channel.master-key}），
 * 不落库、不进镜像、不打日志。本类**永不打印**主密钥或其 base64。
 *
 * <p>{@link #encrypt} 在无主密钥时**抛异常**（写入路径必须响亮地失败）；{@link #decrypt} 永不抛
 * （读路径不能因为配置事故把整个流程打断）。
 */
@Service
public class ChannelKeyService {

    private static final String CONFIG_HINT =
            "未配置渠道主密钥（aihub.channel.master-key / 环境变量 AIHUB_CHANNEL_MASTER_KEY）。"
                    + "生成一把（本机 pwsh）：$k=[byte[]]::new(32); "
                    + "(New-Object Security.Cryptography.RNGCryptoServiceProvider).GetBytes($k); "
                    + "'v1:' + [Convert]::ToBase64String($k)";

    private final ChannelKeyRegistry registry;
    private final AesGcmChannelCipher cipher;

    public ChannelKeyService(@Value("${aihub.channel.master-key:}") String masterKey) {
        this.registry = ChannelKeyRegistry.parse(masterKey);
        this.cipher = new AesGcmChannelCipher(registry);
    }

    /** 加密一条渠道明文密钥。返回自描述密文（{@code v{n}:{base64}}）。 */
    public String encrypt(String plaintextChannelKey) {
        if (registry.isEmpty()) {
            throw new IllegalStateException(CONFIG_HINT);
        }
        return cipher.encrypt(plaintextChannelKey == null ? "" : plaintextChannelKey);
    }

    /** 解密（读路径：只用于「探测渠道」这类未来功能）；任何失败返回空而不是抛异常。 */
    public Optional<String> decrypt(String cipherText) {
        return cipher.decrypt(cipherText);
    }

    public int currentKeyVersion() {
        return registry.currentVersion();
    }

    public boolean configured() {
        return !registry.isEmpty();
    }

    /** 只暴露「有几个版本」，**绝不含密钥内容**。 */
    @Override
    public String toString() {
        return "ChannelKeyService[" + registry.describe() + "]";
    }
}
```

- [ ] **Step 3: 配置 admin 的主密钥**

在 `aihub-admin/aihub-web/src/main/resources/application.yml` 的 `aihub:` 块里，**在 `internal:` 之前**插入：

```yaml
  # 渠道主密钥（AES-256，只来自环境变量；不落库、不进镜像、不打日志）。生成方法见 .env.example。
  channel:
    master-key: ${AIHUB_CHANNEL_MASTER_KEY:}
```

- [ ] **Step 4: 运行测试**

```powershell
mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ChannelKeyServiceTest"
```

预期：`Tests run: 6, Failures: 0, Errors: 0` + `BUILD SUCCESS`（`-am` 必须加：`.m2repo` 里的空 `aihub-common` jar 会造成假的 "package does not exist"）。

- [ ] **Step 5: 跑 admin 全量（容器测试）**

```powershell
$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test
```

预期：`aihub-web 53`（47 + 6），`aihub-common` 仍然是 48。容器真的起来了（若 Docker 不可达，`AbstractIntegrationTest` 会抛 `IllegalStateException` 而不是静默跳过）。

- [ ] **Step 6: 提交**

```powershell
git add aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ChannelEntity.java aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ModelRouteEntity.java aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/RateLimitPolicyEntity.java aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper aihub-admin/aihub-service/src/main/java/com/aihub/service/channel aihub-admin/aihub-web/src/main/resources/application.yml aihub-admin/aihub-web/src/test/java/com/aihub/admin/channel
git commit -m "feat: add the channel, route and rate limit dao plus the admin side key encryption service"
```

**验收标准**
1. 三个实体与 V1 的列一一对应（含 `api_key_cipher` / `key_version` / `timeout_ms` / `models_json`）；三张表都能被 MyBatis-Plus 读写。
2. `ChannelKeyService.encrypt` 用**当前（最大）版本**加密，无主密钥时抛 `IllegalStateException` 且消息给出配置指引与生成命令。
3. admin 产出的密文能被 gateway 侧的同一个实现解开（跨侧互操作用例）。
4. 异常消息与 `toString` 不含主密钥内容。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ChannelKeyServiceTest"` | `Tests run: 6, Failures: 0, Errors: 0` |
| `$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test` | `aihub-web Tests run: 53, Failures: 0, Errors: 0` |

---

## Task 13: `GET /internal/config/snapshot`（HMAC 签名）与开发演示数据 seeder

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigSnapshotService.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/channel/DemoChannelSeeder.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/internal/InternalConfigController.java`
- Modify: `aihub-admin/aihub-web/src/main/resources/application.yml`（`aihub.demo-seed.*`）
- Modify: `docker-compose.yml`（两个服务的 `AIHUB_CHANNEL_MASTER_KEY`，admin 另加 `AIHUB_DEMO_SEED_ENABLED`）
- Modify: `.env.example`（占位符 + 生成方法）
- Test: `aihub-web/src/test/java/com/aihub/admin/config/ConfigSnapshotServiceTest.java`
- Test: `aihub-web/src/test/java/com/aihub/admin/config/InternalConfigSnapshotIntegrationTest.java`

**Interfaces:**
- Consumes：Task 12（三个 Mapper 与 `ChannelKeyService`）、既有 `InternalAuthFilter` / `ApiResponse` / `ErrorCode`。
- Produces：
  - `ConfigSnapshotService`：`ConfigSnapshot snapshot()`；`long currentVersion()`；`@Transactional(readOnly = true)`。
    - **version（决策 5）**：`max(channel.updated_at, model_route.updated_at, rate_limit_policy.updated_at)` 的 epoch 毫秒；三张表都空时 `0`（用 `select max(updated_at)` 而不是「读全表再在内存里 max」）。
    - **限流策略排序（决策 17）**：`order by tenant_id asc, api_key_id is null desc, id asc` —— 于是 gateway 侧「**在每一维内部**取列表里最后一条」等价于「取该维 `id` 最大的那条」（租户级取最后一条租户级行，key 级取该 `apiKeyId` 的最后一行；决策 7 修订后**两维都参与判定**）。同租户多条**同维度** ACTIVE 策略时打**一次** WARN（租户级与 key 级各判各的）。
    - 渠道与路由：**全部行都组装**（含 `DISABLED`），由 gateway 侧过滤。
    - `defaultModel`：取自 `@Value("${aihub.upstream.default-model:}")`（与 M1/M2 同一个来源）。
  - `DemoChannelSeeder`：`@Component @ConditionalOnProperty(name = "aihub.demo-seed.enabled", havingValue = "true")`，`implements ApplicationRunner`；幂等（按名字判断存在则跳过）；写 2 条渠道 + 2 条路由 + **1 条租户级策略 + 1 条 key 级策略**（决策 7 修订后 key 维必须能被端到端演示：key 级是覆盖、租户级是兜底）；**未配主密钥时启动失败并给出配置指引**。
  - `InternalConfigController`：`@RestController @RequestMapping("/internal/config")`，`@GetMapping("/snapshot")` 返回 `ApiResponse<ConfigSnapshot>`（**直接返回共享类型**，与 `InternalKeyController` 返回 `ApiKeyView` 同款）。

**测试用例清单**（`ConfigSnapshotServiceTest` 6 + `InternalConfigSnapshotIntegrationTest` 5 = 11 条，都需要 Testcontainers）：

| 类 | 用例 | 钉住什么 |
|---|---|---|
| `ConfigSnapshotServiceTest`（6） | `emptyDatabaseYieldsAnEmptySnapshotWithVersionZero` / `versionIsTheMaxUpdatedAtAcrossAllThreeTables` / `channelCipherAndKeyVersionAreExposedVerbatim` / `disabledRowsAreStillPresentInTheSnapshot` / `tenantLevelPoliciesComeBeforeKeyLevelOnesAndBothAreQueryable` / `multipleTenantLevelPoliciesWarnButStillPickTheLast` | 组装、版本单调、决策 17（**两个维度都可取**） |
| `InternalConfigSnapshotIntegrationTest`（5） | `signedRequestReturnsTheSnapshotInTheAdminEnvelope` / `unsignedRequestIsUnauthorized` / `wrongSignatureIsUnauthorized` / `responseCarriesTheSnapshotSections` / `snapshotEndpointIsNotReachableWithAForeignSecret` | 内部接口契约（复用 M1 Task 3 建立的签名手法） |

- [ ] **Step 1: 写 `ConfigSnapshotService` 与它的测试**

创建 `aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigSnapshotService.java`：

```java
package com.aihub.service.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.RatePolicy;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.ModelRouteEntity;
import com.aihub.dao.entity.RateLimitPolicyEntity;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 组装 {@code GET /internal/config/snapshot} 的响应：一次调用把网关需要的**全部**配置给它
 * （渠道 + 密文 + 路由 + 限流策略 + 版本号）。设计文档 §7.2 的明文接口。
 *
 * <p><b>version 是单调时间戳</b>（决策 5）：三张表的 {@code updated_at} 取最大。V1 的三张表都有
 * {@code ON UPDATE CURRENT_TIMESTAMP(3)}，因此任何一次配置写入都会推进它。空库返回 {@code 0}。
 *
 * <p><b>限流策略的排序是契约的一部分</b>（决策 17）：同一租户的租户级策略必须按 {@code id} 升序、
 * 且排在 key 级策略之前 —— 这样 gateway 侧「在每一维内部取最后一条」（租户级取最后一条租户级行、
 * key 级取该 {@code apiKeyId} 的最后一行）就等价于「取该维 {@code id} 最大的那条」。
 * **两维都参与判定**（决策 7，2026-09-26 依控制器 pre-flight 评审修订），因此本查询把
 * {@code api_key_id} 非空的行也一样组装进快照。
 *
 * <p>所有行（含 {@code DISABLED}）都会被组装：过滤是 gateway 的事（这样「停用一条渠道」不需要
 * 改变快照的语义，运维也能从快照里看到全貌）。**本查询不返回任何明文密钥**，只有密文。
 */
@Service
public class ConfigSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ConfigSnapshotService.class);

    private final ChannelMapper channelMapper;
    private final ModelRouteMapper modelRouteMapper;
    private final RateLimitPolicyMapper rateLimitPolicyMapper;
    private final JdbcTemplate jdbcTemplate;
    private final String defaultModel;

    public ConfigSnapshotService(ChannelMapper channelMapper, ModelRouteMapper modelRouteMapper,
                                 RateLimitPolicyMapper rateLimitPolicyMapper, JdbcTemplate jdbcTemplate,
                                 @Value("${aihub.upstream.default-model:}") String defaultModel) {
        this.channelMapper = channelMapper;
        this.modelRouteMapper = modelRouteMapper;
        this.rateLimitPolicyMapper = rateLimitPolicyMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.defaultModel = defaultModel;
    }

    @Transactional(readOnly = true)
    public ConfigSnapshot snapshot() {
        return new ConfigSnapshot(currentVersion(), System.currentTimeMillis(),
                channels(), routes(), ratePolicies(),
                defaultModel == null || defaultModel.isBlank() ? null : defaultModel);
    }

    /** 三张表的 {@code updated_at} 最大值（epoch 毫秒）；空库为 0。 */
    public long currentVersion() {
        long max = 0L;
        for (String table : List.of("channel", "model_route", "rate_limit_policy")) {
            Long candidate = maxUpdatedAt(table);
            if (candidate != null && candidate > max) {
                max = candidate;
            }
        }
        return max;
    }

    private Long maxUpdatedAt(String table) {
        // 表名来自本类的常量列表，不来自任何外部输入（没有注入面）。
        Timestamp max = jdbcTemplate.queryForObject("select max(updated_at) from " + table, Timestamp.class);
        return max == null ? null : max.toInstant().toEpochMilli();
    }

    private List<ChannelDescriptor> channels() {
        List<ChannelDescriptor> channels = new ArrayList<>();
        for (ChannelEntity entity : channelMapper.selectList(new QueryWrapper<>())) {
            channels.add(new ChannelDescriptor(
                    entity.getId() == null ? 0L : entity.getId(),
                    entity.getName(),
                    entity.getBaseUrl(),
                    entity.getApiKeyCipher(),
                    entity.getKeyVersion() == null ? 0 : entity.getKeyVersion(),
                    entity.getTimeoutMs() == null ? 0 : entity.getTimeoutMs(),
                    entity.getStatus(),
                    entity.getWeight() == null ? 0 : entity.getWeight(),
                    entity.getPriority() == null ? 0 : entity.getPriority()));
        }
        return channels;
    }

    private List<ModelRouteDescriptor> routes() {
        List<ModelRouteDescriptor> routes = new ArrayList<>();
        for (ModelRouteEntity entity : modelRouteMapper.selectList(new QueryWrapper<>())) {
            routes.add(new ModelRouteDescriptor(
                    entity.getModelName(),
                    entity.getChannelId() == null ? 0L : entity.getChannelId(),
                    entity.getWeight() == null ? 0 : entity.getWeight(),
                    entity.getPriority() == null ? 0 : entity.getPriority(),
                    entity.getStatus()));
        }
        return routes;
    }

    /**
     * 排序规则见类注释（决策 17）：{@code tenant_id ASC, api_key_id IS NULL DESC, id ASC}。
     * MySQL 里 {@code api_key_id IS NULL} 为真时是 1，因此 {@code DESC} 把租户级排在前面。
     */
    private List<RatePolicy> ratePolicies() {
        QueryWrapper<RateLimitPolicyEntity> query = new QueryWrapper<>();
        query.orderByAsc("tenant_id").orderByDesc("api_key_id is null").orderByAsc("id");
        List<RatePolicy> policies = new ArrayList<>();
        Long previousTenant = null;
        boolean warned = false;
        for (RateLimitPolicyEntity entity : rateLimitPolicyMapper.selectList(query)) {
            policies.add(new RatePolicy(entity.getTenantId(), entity.getApiKeyId(),
                    entity.getQps() == null ? 0 : entity.getQps(),
                    entity.getBurst() == null ? 0 : entity.getBurst()));
            boolean tenantLevel = entity.getApiKeyId() == null
                    && ChannelDescriptor.STATUS_ACTIVE.equals(entity.getStatus());
            if (tenantLevel && entity.getTenantId() != null && entity.getTenantId().equals(previousTenant)
                    && !warned) {
                // V1 没有唯一约束（决策 17）：多条租户级 ACTIVE 策略时取 id 最大的那条，
                // 但必须让人知道数据是脏的。
                log.warn("租户 {} 存在多条租户级 ACTIVE 限流策略（表上没有唯一约束），"
                        + "将按 id 最大的那条生效；请停用多余的行（M4 的控制台会强制单条生效）",
                        entity.getTenantId());
                warned = true;
            }
            if (tenantLevel) {
                previousTenant = entity.getTenantId();
            }
        }
        return policies;
    }
}
```

创建 `aihub-web/src/test/java/com/aihub/admin/config/ConfigSnapshotServiceTest.java`：

```java
package com.aihub.admin.config;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.RatePolicy;
import com.aihub.service.channel.ChannelKeyService;
import com.aihub.service.config.ConfigSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照组装：真实 MySQL（Testcontainers）。**必须真起容器**，因为它要验证的是
 * 「{@code max(updated_at)} 真的随写入推进」与「两维策略各自都能取到、且租户级排在前面」这两件
 * SQL 层面的事实。
 */
class ConfigSnapshotServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ConfigSnapshotService service;

    @Autowired
    private ChannelKeyService keyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from model_route");
        jdbcTemplate.update("delete from rate_limit_policy");
        jdbcTemplate.update("delete from channel");
    }

    @Test
    void emptyDatabaseYieldsAnEmptySnapshotWithVersionZero() {
        ConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.channels()).isEmpty();
        assertThat(snapshot.routes()).isEmpty();
        assertThat(snapshot.ratePolicies()).isEmpty();
        assertThat(snapshot.version()).isZero();
    }

    @Test
    void versionIsTheMaxUpdatedAtAcrossAllThreeTables() throws Exception {
        insertChannel("c1", "https://c1.example.com", 100, 0);
        long afterChannel = service.snapshot().version();
        assertThat(afterChannel).isPositive();

        Thread.sleep(5L);
        insertRoute("m1", channelId("c1"), 100, 0);

        assertThat(service.snapshot().version()).isGreaterThan(afterChannel);
    }

    @Test
    void channelCipherAndKeyVersionAreExposedVerbatim() {
        String cipher = insertChannel("c1", "https://c1.example.com", 100, 0);

        ChannelDescriptor channel = service.snapshot().channels().get(0);

        assertThat(channel.apiKeyCipher()).isEqualTo(cipher);
        assertThat(channel.keyVersion()).isEqualTo(keyService.currentKeyVersion());
        assertThat(channel.timeoutMs()).isEqualTo(60_000);
    }

    @Test
    void disabledRowsAreStillPresentInTheSnapshot() {
        jdbcTemplate.update("insert into channel (name, provider, base_url, api_key_cipher, key_version, "
                        + "weight, priority, timeout_ms, status) values (?,?,?,?,?,?,?,?,?)",
                "off", "test", "https://off.example.com", "v1:QUJD", 1, 1, 0, 1000, "DISABLED");

        assertThat(service.snapshot().channels()).extracting(ChannelDescriptor::name).containsExactly("off");
        assertThat(service.snapshot().channels().get(0).usable()).isFalse();
    }

    @Test
    void tenantLevelPoliciesComeBeforeKeyLevelOnesAndBothAreQueryable() {
        Long tenantId = insertTenant("acme");
        insertPolicy(tenantId, 42L, 100, 200);
        insertPolicy(tenantId, null, 20, 40);

        ConfigSnapshot snapshot = service.snapshot();
        List<RatePolicy> tenantLevel = snapshot.tenantPolicies(tenantId);

        assertThat(tenantLevel).as("租户级必须能取到，且是列表里的最后一条").hasSize(1);
        assertThat(tenantLevel.get(tenantLevel.size() - 1).qps()).isEqualTo(20);
        assertThat(tenantLevel.get(tenantLevel.size() - 1).tenantLevel()).isTrue();

        // 决策 7（修订）：key 级行**也**要能在快照里按 (租户, key) 取到 —— 它现在参与判定，
        // 不再是「读进来但不用」。
        List<RatePolicy> keyLevel = snapshot.keyPolicies(tenantId, 42L);

        assertThat(keyLevel).as("key 级必须能按数值主键取到").hasSize(1);
        assertThat(keyLevel.get(0).qps()).isEqualTo(100);
        assertThat(keyLevel.get(0).tenantLevel()).isFalse();
        assertThat(snapshot.keyPolicies(tenantId, 43L)).as("别的 key 取不到").isEmpty();
    }

    @Test
    void multipleTenantLevelPoliciesWarnButStillPickTheLast() {
        Long tenantId = insertTenant("acme");
        insertPolicy(tenantId, null, 20, 40);
        insertPolicy(tenantId, null, 60, 80);

        List<RatePolicy> policies = service.snapshot().tenantPolicies(tenantId);

        assertThat(policies).hasSize(2);
        assertThat(policies.get(policies.size() - 1).qps()).as("取 id 最大的那条").isEqualTo(60);
    }

    // --- SQL 助手 -------------------------------------------------------

    private Long insertTenant(String name) {
        jdbcTemplate.update("insert into tenant (name, status) values (?,?)", name, "ACTIVE");
        return jdbcTemplate.queryForObject("select id from tenant where name = ?", Long.class, name);
    }

    private void insertPolicy(Long tenantId, Long apiKeyId, int qps, int burst) {
        jdbcTemplate.update("insert into rate_limit_policy (tenant_id, api_key_id, qps, burst, status) "
                + "values (?,?,?,?,?)", tenantId, apiKeyId, qps, burst, "ACTIVE");
    }

    /**
     * 密文：主密钥没配时用一个固定的占位密文。
     * <p>本用例系列验证的是「密文列原样出现在快照里」，而不是密码学（那是 Task 1/12 的事）。
     * 若实施者希望这里也用真密文，**可以**给 {@code AbstractIntegrationTest} 加一行
     * {@code registry.add("aihub.channel.master-key", () -> "v1:" + SYNTHETIC_32_BYTES)}，
     * 并在报告里说明 —— 那是加强，不算偏离。
     */
    private String insertChannel(String name, String baseUrl, int weight, int priority) {
        String cipher = keyService.configured()
                ? keyService.encrypt("sk-channel-plaintext-synthetic")
                : "v1:QUJD";
        jdbcTemplate.update("insert into channel (name, provider, base_url, api_key_cipher, key_version, "
                        + "weight, priority, timeout_ms, status) values (?,?,?,?,?,?,?,?,?)",
                name, "test", baseUrl, cipher, Math.max(keyService.currentKeyVersion(), 1),
                weight, priority, 60_000, "ACTIVE");
        return cipher;
    }

    private void insertRoute(String model, Long channelId, int weight, int priority) {
        jdbcTemplate.update("insert into model_route (model_name, channel_id, weight, priority, status) "
                + "values (?,?,?,?,?)", model, channelId, weight, priority, "ACTIVE");
    }

    private Long channelId(String name) {
        return jdbcTemplate.queryForObject("select id from channel where name = ?", Long.class, name);
    }
}
```

- [ ] **Step 2: 写 `DemoChannelSeeder`**

创建 `aihub-admin/aihub-service/src/main/java/com/aihub/service/channel/DemoChannelSeeder.java`：

```java
package com.aihub.service.channel;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.dao.entity.ApiKeyEntity;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.entity.ModelRouteEntity;
import com.aihub.dao.entity.RateLimitPolicyEntity;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.ApiKeyMapper;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.dao.mapper.RateLimitPolicyMapper;
import com.aihub.dao.mapper.TenantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * **开发专用**的演示数据（两条同模型渠道 + 一条租户级限流策略 + 一条 key 级限流策略），用来做 M3 的验收
 * （多渠道 + 权重 + 故障转移 + 熔断 + **两维限流**）。
 *
 * <p><b>默认关闭</b>（{@code aihub.demo-seed.enabled=false}）。理由：它是一个**写入路径**，
 * 不应该在生产启动时自动改数据；而且真渠道的密钥只能由运维提供，不该有默认值。
 *
 * <p><b>为什么不是 Flyway 迁移</b>（决策 12）：迁移脚本是**一次性、不可回滚、随代码分发的**
 * 数据变更，而演示渠道依赖环境（base-url、密钥）。更硬的约束是
 * {@code SchemaMigrationTest.flywayAppliesExactlyOneMigration} 断言恰好 1 条迁移 ——
 * 加迁移就必须改那条断言，那是在削弱护栏而不是加功能。
 *
 * <p><b>幂等</b>：按名字判断「已存在就跳过」，因此重复启动不会产生重复行。
 * <p><b>明文密钥</b>：默认值是**一眼可辨的合成值**，真值由环境变量提供；落库前一律过
 * {@link ChannelKeyService#encrypt}，因此库里永远只有密文。
 */
@Component
@ConditionalOnProperty(name = "aihub.demo-seed.enabled", havingValue = "true")
public class DemoChannelSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoChannelSeeder.class);

    private final ChannelMapper channelMapper;
    private final ModelRouteMapper modelRouteMapper;
    private final RateLimitPolicyMapper rateLimitPolicyMapper;
    private final TenantMapper tenantMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ChannelKeyService keyService;

    private final String model;
    private final String tenantName;
    private final String primaryBaseUrl;
    private final String standbyBaseUrl;
    private final String primaryApiKey;
    private final String standbyApiKey;
    private final int qps;
    private final int burst;
    private final int keyQps;
    private final int keyBurst;

    public DemoChannelSeeder(ChannelMapper channelMapper, ModelRouteMapper modelRouteMapper,
                             RateLimitPolicyMapper rateLimitPolicyMapper, TenantMapper tenantMapper,
                             ApiKeyMapper apiKeyMapper, ChannelKeyService keyService,
                             @Value("${aihub.demo-seed.model:demo-model}") String model,
                             @Value("${aihub.demo-seed.tenant:demo}") String tenantName,
                             @Value("${aihub.demo-seed.primary-base-url:http://host.docker.internal:11434}")
                             String primaryBaseUrl,
                             @Value("${aihub.demo-seed.standby-base-url:http://host.docker.internal:11434}")
                             String standbyBaseUrl,
                             @Value("${aihub.demo-seed.primary-api-key:sk-channel-plaintext-synthetic-primary}")
                             String primaryApiKey,
                             @Value("${aihub.demo-seed.standby-api-key:sk-channel-plaintext-synthetic-standby}")
                             String standbyApiKey,
                             @Value("${aihub.demo-seed.qps:20}") int qps,
                             @Value("${aihub.demo-seed.burst:40}") int burst,
                             @Value("${aihub.demo-seed.key-qps:5}") int keyQps,
                             @Value("${aihub.demo-seed.key-burst:10}") int keyBurst) {
        this.channelMapper = channelMapper;
        this.modelRouteMapper = modelRouteMapper;
        this.rateLimitPolicyMapper = rateLimitPolicyMapper;
        this.tenantMapper = tenantMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.keyService = keyService;
        this.model = model;
        this.tenantName = tenantName;
        this.primaryBaseUrl = primaryBaseUrl;
        this.standbyBaseUrl = standbyBaseUrl;
        this.primaryApiKey = primaryApiKey;
        this.standbyApiKey = standbyApiKey;
        this.qps = qps;
        this.burst = burst;
        this.keyQps = keyQps;
        this.keyBurst = keyBurst;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!keyService.configured()) {
            // 主密钥没配就无法加密渠道密钥 → 直接失败并给出配置指引（而不是写一条不可解的密文）。
            throw new IllegalStateException(
                    "aihub.demo-seed.enabled=true 需要先配置 aihub.channel.master-key（AIHUB_CHANNEL_MASTER_KEY）");
        }
        Long primary = ensureChannel("demo-primary", primaryBaseUrl, primaryApiKey, 100);
        Long standby = ensureChannel("demo-standby", standbyBaseUrl, standbyApiKey, 1);
        ensureRoute(model, primary, 100, 0);
        ensureRoute(model, standby, 1, 0);
        ensureTenantPolicy(tenantName, qps, burst);
        // 决策 7（修订）：**key 级策略也要能被端到端演示** —— 它比租户级更严格（5/10 vs 20/40），
        // 因此「换了 key 以后限流数字变了」这件事在演示里是可观察的，而不是只能靠单元测试相信。
        ensureKeyLevelPolicy(tenantName, keyQps, keyBurst);
        log.info("演示数据就绪：模型 {} 有两条候选渠道（primary weight=100 / standby weight=1），"
                + "租户 {} 限流 {}qps burst={}，该租户的第一把 key 覆盖为 {}qps burst={}",
                model, tenantName, qps, burst, keyQps, keyBurst);
    }

    private Long ensureChannel(String name, String baseUrl, String plaintextKey, int weight) {
        ChannelEntity existing = channelMapper.selectOne(
                new LambdaQueryWrapper<ChannelEntity>().eq(ChannelEntity::getName, name));
        if (existing != null) {
            return existing.getId();
        }
        ChannelEntity entity = new ChannelEntity();
        entity.setName(name);
        entity.setProvider("openai-compatible");
        entity.setBaseUrl(baseUrl);
        entity.setApiKeyCipher(keyService.encrypt(plaintextKey));
        entity.setKeyVersion(keyService.currentKeyVersion());
        entity.setWeight(weight);
        entity.setPriority(0);
        entity.setTimeoutMs(30_000);
        entity.setStatus(ChannelDescriptor.STATUS_ACTIVE);
        channelMapper.insert(entity);
        log.info("已写入演示渠道 {}（id={}，权重 {}）", name, entity.getId(), weight);
        return entity.getId();
    }

    private void ensureRoute(String modelName, Long channelId, int weight, int priority) {
        Long existing = modelRouteMapper.selectCount(new LambdaQueryWrapper<ModelRouteEntity>()
                .eq(ModelRouteEntity::getModelName, modelName)
                .eq(ModelRouteEntity::getChannelId, channelId));
        if (existing != null && existing > 0) {
            return;
        }
        ModelRouteEntity entity = new ModelRouteEntity();
        entity.setModelName(modelName);
        entity.setChannelId(channelId);
        entity.setWeight(weight);
        entity.setPriority(priority);
        entity.setStatus(ModelRouteDescriptor.STATUS_ACTIVE);
        modelRouteMapper.insert(entity);
    }

    private void ensureTenantPolicy(String name, int tenantQps, int tenantBurst) {
        TenantEntity tenant = tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getName, name));
        if (tenant == null) {
            log.warn("演示租户 {} 不存在（先铸一把 API Key 会自动创建它），跳过限流策略", name);
            return;
        }
        Long existing = rateLimitPolicyMapper.selectCount(new LambdaQueryWrapper<RateLimitPolicyEntity>()
                .eq(RateLimitPolicyEntity::getTenantId, tenant.getId())
                .isNull(RateLimitPolicyEntity::getApiKeyId));
        if (existing != null && existing > 0) {
            return;
        }
        RateLimitPolicyEntity entity = new RateLimitPolicyEntity();
        entity.setTenantId(tenant.getId());
        entity.setApiKeyId(null);
        entity.setQps(tenantQps);
        entity.setBurst(tenantBurst);
        entity.setStatus("ACTIVE");
        rateLimitPolicyMapper.insert(entity);
        log.info("已写入演示限流策略：租户 {} {}qps burst={}", name, tenantQps, tenantBurst);
    }

    /**
     * **key 级**限流策略（决策 7 修订）：作用于该租户的**第一把** API Key（`api_key.id` 最小的那条，
     * 与 V1 的建表顺序一致，因此演示时「先铸的那把 key」就是被覆盖的那把）。
     *
     * <p>找不到租户或该租户还没有 API Key 时**只 WARN 并跳过**（与 {@link #ensureTenantPolicy} 同款）：
     * 演示数据不全不能让 admin 启动失败；先铸一把 key 再重启即可。
     */
    private void ensureKeyLevelPolicy(String name, int keyQps, int keyBurst) {
        TenantEntity tenant = tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getName, name));
        if (tenant == null) {
            log.warn("演示租户 {} 不存在（先铸一把 API Key 会自动创建它），跳过 key 级限流策略", name);
            return;
        }
        ApiKeyEntity apiKey = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKeyEntity>()
                .eq(ApiKeyEntity::getTenantId, tenant.getId())
                .orderByAsc(ApiKeyEntity::getId)
                .last("limit 1"));
        if (apiKey == null) {
            log.warn("演示租户 {} 还没有 API Key，跳过 key 级限流策略（铸一把 key 后重启即可写入）", name);
            return;
        }
        Long existing = rateLimitPolicyMapper.selectCount(new LambdaQueryWrapper<RateLimitPolicyEntity>()
                .eq(RateLimitPolicyEntity::getTenantId, tenant.getId())
                .eq(RateLimitPolicyEntity::getApiKeyId, apiKey.getId()));
        if (existing != null && existing > 0) {
            return;
        }
        RateLimitPolicyEntity entity = new RateLimitPolicyEntity();
        entity.setTenantId(tenant.getId());
        entity.setApiKeyId(apiKey.getId());
        entity.setQps(keyQps);
        entity.setBurst(keyBurst);
        entity.setStatus("ACTIVE");
        rateLimitPolicyMapper.insert(entity);
        log.info("已写入演示 key 级限流策略：租户 {} 的 api_key_id={} 覆盖为 {}qps burst={}",
                name, apiKey.getId(), keyQps, keyBurst);
    }
}
```

- [ ] **Step 3: 写 `InternalConfigController`**

创建 `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/internal/InternalConfigController.java`：

```java
package com.aihub.admin.web.internal;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.service.config.ConfigSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关拉配置快照的内部接口（设计文档 §7.2）。
 *
 * <p>调用方必须带合法内部签名（{@link InternalAuthFilter} 守 {@code /internal/**}，
 * 用 {@code UrlPathHelper.getPathWithinApplication} 取路径 —— 因此被签名的路径是
 * {@code /internal/config/snapshot}，不含 context path）。
 *
 * <p>**直接返回共享的 {@link ConfigSnapshot}**，与 {@code InternalKeyController} 返回
 * {@code ApiKeyView} 同款：网关侧有同一个类型，再定义一层只有 admin 认识的 DTO 只会让两侧漂移。
 * 响应里**只有密文**，没有任何明文渠道密钥。
 */
@RestController
@RequestMapping("/internal/config")
public class InternalConfigController {

    private final ConfigSnapshotService configSnapshotService;

    public InternalConfigController(ConfigSnapshotService configSnapshotService) {
        this.configSnapshotService = configSnapshotService;
    }

    @GetMapping("/snapshot")
    public ApiResponse<ConfigSnapshot> snapshot() {
        return ApiResponse.ok(configSnapshotService.snapshot());
    }
}
```

- [ ] **Step 4: 配置 demo-seed 开关**

在 `aihub-admin/aihub-web/src/main/resources/application.yml` 的 `aihub:` 块里加：

```yaml
  # 开发专用演示数据（两条同模型渠道 + 一条租户级限流策略 + 一条 key 级限流策略）。默认关闭：它是写入路径，
  # 不该在生产启动时自动改数据；真渠道的 base-url / 密钥必须由运维提供。
  demo-seed:
    enabled: ${AIHUB_DEMO_SEED_ENABLED:false}
    model: ${AIHUB_DEMO_SEED_MODEL:demo-model}
    tenant: ${AIHUB_DEMO_SEED_TENANT:demo}
    primary-base-url: ${AIHUB_DEMO_SEED_PRIMARY_BASE_URL:http://host.docker.internal:11434}
    standby-base-url: ${AIHUB_DEMO_SEED_STANDBY_BASE_URL:http://host.docker.internal:11434}
    qps: 20
    burst: 40
    # 决策 7（修订）：key 级策略（覆盖租户级）也要能被端到端演示，因此比租户级更严格。
    key-qps: 5
    key-burst: 10
```

- [ ] **Step 5: 写 `InternalConfigSnapshotIntegrationTest`**

创建 `aihub-web/src/test/java/com/aihub/admin/config/InternalConfigSnapshotIntegrationTest.java`：

```java
package com.aihub.admin.config;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.internal.InternalHmac;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部接口的端到端契约：**真容器 + 真 HMAC 签名**。未签名必须 401（fail-closed），
 * 签名内容与 {@code InternalAuthFilter} 的验签口径必须一致（应用内路径 + GET + 时间戳）。
 *
 * <p>与 M1 的 {@code InternalKeyController} 用同一套手法（{@code AbstractIntegrationTest}
 * 里的 secret 是 {@code test-internal-secret-test-internal-secret}）。
 */
class InternalConfigSnapshotIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET = "test-internal-secret-test-internal-secret";
    private static final String PATH = "/internal/config/snapshot";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void signedRequestReturnsTheSnapshotInTheAdminEnvelope() {
        ResponseEntity<String> response = get(signed());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"code\":\"OK\"").contains("\"data\"");
        assertThat(response.getBody()).contains("\"version\"").contains("\"channels\"")
                .contains("\"routes\"").contains("\"ratePolicies\"");
    }

    @Test
    void unsignedRequestIsUnauthorized() {
        assertThat(get(new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongSignatureIsUnauthorized() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Timestamp", timestamp);
        headers.set("X-Internal-Signature", "deadbeef");

        assertThat(get(headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void responseCarriesTheSnapshotSections() {
        ResponseEntity<String> response = get(signed());

        assertThat(response.getBody()).contains("\"generatedAtEpochMilli\"");
    }

    @Test
    void snapshotEndpointIsNotReachableWithAForeignSecret() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Timestamp", timestamp);
        headers.set("X-Internal-Signature", InternalHmac.sign("另一个密钥", timestamp, "GET", PATH));

        assertThat(get(headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> get(HttpHeaders headers) {
        return restTemplate.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static HttpHeaders signed() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Timestamp", timestamp);
        headers.set("X-Internal-Signature", InternalHmac.sign(SECRET, timestamp, "GET", PATH));
        return headers;
    }
}
```

- [ ] **Step 6: 在 `docker-compose.yml` 与 `.env.example` 里接上主密钥**

`docker-compose.yml`：
- `admin` 的 `environment:` 里加（放在 `AIHUB_INTERNAL_SECRET` 之后）：

```yaml
      AIHUB_CHANNEL_MASTER_KEY: ${AIHUB_CHANNEL_MASTER_KEY:?set AIHUB_CHANNEL_MASTER_KEY in .env}
      AIHUB_DEMO_SEED_ENABLED: ${AIHUB_DEMO_SEED_ENABLED:-false}
```

- `gateway` 的 `environment:` 里加：

```yaml
      AIHUB_CHANNEL_MASTER_KEY: ${AIHUB_CHANNEL_MASTER_KEY:?set AIHUB_CHANNEL_MASTER_KEY in .env}
```

**不要**动 `redis` 服务的端口映射，也不要动任何既有的插值。

`.env.example` 末尾加（**占位符 + 生成方法，绝不放真实密钥**）：

```text

# 渠道密钥的 AES-256 主密钥（设计文档 §6.1）：admin 用它加密，gateway 用它解密。
# 必须两侧一致；**只来自环境变量**，不落库、不进镜像、不要提交真值（.env 已在 .gitignore 里）。
# 格式：v1:<base64 的 32 字节>[,v2:<base64 的 32 字节>]。轮换时同时放旧、新两把：
# 先解密旧版本重加密为新版本，全部完成后（确认库里没有 key_version=旧版本的行）再删掉旧的那把。
# 生成一把（任选其一，输出粘到下面）：
#   pwsh: $k=[byte[]]::new(32); (New-Object Security.Cryptography.RNGCryptoServiceProvider).GetBytes($k); 'v1:' + [Convert]::ToBase64String($k)
#   bash: echo "v1:$(openssl rand -base64 32)"
AIHUB_CHANNEL_MASTER_KEY=change-me-please-generate-your-own-v1-base64-32-bytes
```

- [ ] **Step 7: 运行测试**

```powershell
$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test
```

预期：`aihub-web 64`（Task 12 的 53 + 6 服务测试 + 5 接口测试）。容器真的起来了。

- [ ] **Step 8: 提交**

```powershell
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/config aihub-admin/aihub-service/src/main/java/com/aihub/service/channel/DemoChannelSeeder.java aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/internal/InternalConfigController.java aihub-admin/aihub-web/src/main/resources/application.yml aihub-admin/aihub-web/src/test/java/com/aihub/admin/config docker-compose.yml .env.example
git commit -m "feat: expose the hmac signed config snapshot endpoint and a dev-only channel seeder"
```

**验收标准**
1. `GET /internal/config/snapshot` 带合法 HMAC（`GET` + 应用内路径 + 时间戳）时返回 `{"code":"OK","data":{version,generatedAtEpochMilli,defaultModel,channels[],routes[],ratePolicies[]}}`；未签名 / 错签名 / 用别的密钥签名一律 **401 + admin 信封**。
2. 响应里**只有密文**（`apiKeyCipher` 形如 `v{n}:…`）与 `keyVersion`，没有任何明文密钥。
3. `version` = 三张表 `updated_at` 的最大值，随任何一次配置写入推进；空库为 0。
4. 同一租户的策略按 `id` 升序、且租户级排在 key 级之前；**两个维度都能从快照里取到**（决策 7 修订：key 级参与判定，不再「读进来但不用」）；同维度多条时有 WARN。
5. `DemoChannelSeeder` 默认关闭；打开且配了主密钥时写入 2 条渠道 + 2 条路由 + **1 条租户级策略 + 1 条 key 级策略**（key 级作用于该租户第一把 API Key，因此「两维限流」可以被端到端演示），幂等；未配主密钥时**启动失败并给出配置指引**。
6. `docker-compose.yml` 的 `redis` 端口绑定**未改**；`.env.example` 只有占位符与生成命令。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test` | `aihub-web Tests run: 64, Failures: 0, Errors: 0` + `BUILD SUCCESS` |

> **注意**：`AiHubAdminApplication` 的组件扫描根是 `com.aihub`（M1 Task 3 加的），因此
> `DemoChannelSeeder`（`com.aihub.service.channel`）与 `ConfigSnapshotService` 会被扫到。
> 若启动时看到 `No qualifying bean of type 'TenantMapper'`，检查 `MybatisMapperConfig` 的
> `@MapperScan` 是否覆盖了 `com.aihub.dao.mapper`（M1 已经覆盖，本任务只新增接口）。

---

## Task 14: Redis 令牌桶的真容器证据（admin 侧集成测试）

**Files:**
- Test: `aihub-web/src/test/java/com/aihub/admin/ratelimit/RedisTokenBucketIntegrationTest.java`

**Interfaces:**
- Consumes：Task 3 的 `com.aihub.common.ratelimit.RateLimitScript`（脚本与键布局的唯一真相）；`AbstractIntegrationTest` 的 `REDIS` 容器。
- Produces：**对「Lua 原子性」这一面试深挖点的真实证据**（§11 第 2 题）。这条测试放在 admin 侧，因为网关测试不允许依赖 Docker；它读的就是网关跑的那份脚本（同一个共享常量）。

**测试用例清单**（7 条）：

| 用例 | 钉住什么 |
|---|---|
| `freshBucketAllowsBurstThenDenies` | 脚本行为与 `TokenBucket` 的纯算术一致（`qps=0` 时退避有上界） |
| `tokensRefillAccordingToQps` | 传一个「未来」的时间戳 → 立刻补充（把时钟作为参数传入的好处：不必 sleep） |
| `remainingIsReportedAndCappedAtBurst` | `remaining` 不超过 burst |
| `bucketKeyCarriesAnIdleTtl` | `PTTL` 落在 `idleTtlMillis` 附近 |
| `concurrentRequestsNeverExceedBurst` | **20 线程 × 20 次并发打同一个 key，`burst=25` 恰好只放行 25 次** —— 原子性的可证伪证据 |
| `differentKeysAreIsolated` | 两个 key 各自独立 |
| `scriptIsIdempotentForTheSameTimestamp` | 同一毫秒重复调用只减少令牌、不重复补充 |

- [ ] **Step 1: 写测试并运行**

创建 `aihub-web/src/test/java/com/aihub/admin/ratelimit/RedisTokenBucketIntegrationTest.java`：

```java
package com.aihub.admin.ratelimit;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.ratelimit.RateLimitScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **Lua 令牌桶的真实证据**：真 Redis 容器（Testcontainers，由 {@code AbstractIntegrationTest} 提供）。
 *
 * <p>放在 admin 侧的唯一原因是纪律：{@code aihub-gateway} 的测试**不允许依赖 Docker**。
 * 但被测的脚本与调用约定就是网关跑的那一份（{@link RateLimitScript} 是共享模块里的同一个常量），
 * 因此这里的结论对生产路径成立。
 *
 * <p>{@code concurrentRequestsNeverExceedBurst} 是这一节的核心：它证明「补充 + 判定 + 扣减」
 * 在服务器端是**一个**原子操作。把脚本拆成多次客户端调用（或去掉 Lua）之后，本用例会失败 ——
 * 这正是设计文档 §11 第 2 题「Redis + Lua 令牌桶的原子性」要求我们拿出的东西。
 */
class RedisTokenBucketIntegrationTest extends AbstractIntegrationTest {

    private static final String BUCKET = RateLimitScript.KEY_PREFIX + "test:integration";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(RateLimitScript.SCRIPT, List.class);

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearBuckets() {
        Set<String> keys = redis.keys(RateLimitScript.KEY_PREFIX + "test:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> eval(String key, long nowMillis, int qps, int burst) {
        return redis.execute(SCRIPT, List.of(key),
                String.valueOf(nowMillis), String.valueOf(qps), String.valueOf(burst),
                String.valueOf(RateLimitScript.idleTtlMillis(qps, burst)));
    }

    @Test
    void freshBucketAllowsBurstThenDenies() {
        long now = System.currentTimeMillis();

        for (int i = 0; i < 5; i++) {
            assertThat(eval(BUCKET, now, 0, 5).get(0)).as("第 %s 次突发", i + 1).isEqualTo(1L);
        }
        List<Long> denied = eval(BUCKET, now, 0, 5);

        assertThat(denied.get(0)).isZero();
        assertThat(denied.get(1)).isZero();
        assertThat(denied.get(2)).as("qps=0 时退避时间有明确上界").isEqualTo(3_600_000L);
    }

    /** 时间戳是参数 → 用一个「未来」的时刻就能立刻观察到补充，**不需要 sleep**。 */
    @Test
    void tokensRefillAccordingToQps() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            eval(BUCKET, now, 10, 3);
        }
        assertThat(eval(BUCKET, now, 10, 3).get(0)).isZero();

        // 向前 200ms：qps=10 → 补 2 个令牌。
        assertThat(eval(BUCKET, now + 200L, 10, 3).get(0)).isEqualTo(1L);
        assertThat(eval(BUCKET, now + 200L, 10, 3).get(0)).isEqualTo(1L);
        assertThat(eval(BUCKET, now + 200L, 10, 3).get(0)).isZero();
    }

    @Test
    void remainingIsReportedAndCappedAtBurst() {
        long now = System.currentTimeMillis();

        List<Long> first = eval(BUCKET, now, 100, 10);
        assertThat(first.get(0)).isEqualTo(1L);
        assertThat(first.get(1)).isEqualTo(9L);

        List<Long> afterHugeGap = eval(BUCKET, now + 3_600_000L, 100, 10);
        assertThat(afterHugeGap.get(1)).as("过了一小时也只补到 burst").isEqualTo(9L);
    }

    @Test
    void bucketKeyCarriesAnIdleTtl() {
        eval(BUCKET, System.currentTimeMillis(), 10, 20);

        Long ttl = redis.getExpire(BUCKET, TimeUnit.MILLISECONDS);

        assertThat(ttl).isNotNull().isPositive();
        assertThat(ttl).isLessThanOrEqualTo(RateLimitScript.idleTtlMillis(10, 20));
        assertThat(ttl).isGreaterThan(RateLimitScript.idleTtlMillis(10, 20)
                - Duration.ofSeconds(20).toMillis());
    }

    /**
     * **原子性的可证伪证据**：20 个线程各尝试 20 次（共 400 次），桶容量 25、
     * qps=0（不补充）→ 恰好 25 次被放行。分步实现（GET 之后 SET）会显著超过 25。
     */
    @Test
    void concurrentRequestsNeverExceedBurst() throws Exception {
        int threads = 20;
        int attempts = 20;
        int burst = 25;
        long now = System.currentTimeMillis();
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < attempts; j++) {
                        if (eval(BUCKET, now, 0, burst).get(0) == 1L) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).as("Lua 原子性：绝不超过 burst").isEqualTo(burst);
    }

    @Test
    void differentKeysAreIsolated() {
        long now = System.currentTimeMillis();
        String other = RateLimitScript.KEY_PREFIX + "test:other";

        eval(BUCKET, now, 0, 1);

        assertThat(eval(BUCKET, now, 0, 1).get(0)).isZero();
        assertThat(eval(other, now, 0, 1).get(0)).as("另一个 key 不受影响").isEqualTo(1L);
    }

    @Test
    void scriptIsIdempotentForTheSameTimestamp() {
        long now = System.currentTimeMillis();

        assertThat(eval(BUCKET, now, 10, 3).get(0)).isEqualTo(1L);
        assertThat(eval(BUCKET, now, 10, 3).get(0)).isEqualTo(1L);
        assertThat(eval(BUCKET, now, 10, 3).get(0)).isEqualTo(1L);

        // 同一毫秒内第四次：没有补充，也没有重复补充。
        assertThat(eval(BUCKET, now, 10, 3).get(0)).isZero();
    }
}
```

```powershell
$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=RedisTokenBucketIntegrationTest"
```

预期：`Tests run: 7, Failures: 0, Errors: 0` + `BUILD SUCCESS`。**容器真的起来了**。

若 `concurrentRequestsNeverExceedBurst` 变红且实际放行数 **大于** 25 → 你的脚本被拆成了多次调用（原子性丢了）；若**小于** 25 → 检查 `eval` 的 `burst` 参数或 `qps=0` 分支。

- [ ] **Step 2: 跑两个模块的全量**

```powershell
mvn -B -pl aihub-admin/aihub-common test
$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test
mvn -B -pl aihub-gateway test
```

预期：`aihub-common 48`、`aihub-web 71`、`aihub-gateway 246`。

- [ ] **Step 3: 提交**

```powershell
git add aihub-admin/aihub-web/src/test/java/com/aihub/admin/ratelimit
git commit -m "test: prove the token bucket lua script is atomic against a real redis"
```

**验收标准**
1. 真 Redis 上：突发恰好 `burst` 次、按 qps 补充、`remaining` 封顶、键有 TTL。
2. **20 线程 × 20 次并发打同一个 key、`burst=25` 恰好放行 25 次**（原子性的可证伪证据）。
3. 被测脚本就是网关跑的那一份（`RateLimitScript.SCRIPT`，共享模块里的同一个常量）。
4. 该测试放在 admin 侧（需要 Docker），网关侧的测试仍然零 Docker 依赖。

**必须运行的命令与期望输出**

| 命令 | 期望 |
|---|---|
| `$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=RedisTokenBucketIntegrationTest"` | `Tests run: 7, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-admin/aihub-common test` | `Tests run: 48, Failures: 0, Errors: 0` |
| `$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B -pl aihub-admin/aihub-web -am test` | `Tests run: 71, Failures: 0, Errors: 0` |
| `mvn -B -pl aihub-gateway test` | `Tests run: 246, Failures: 0, Errors: 0` |

---

## Task 15: 文档收口、全量回归、里程碑验收与 `m3` 标签

**Files:**
- Modify: `docs/CONVENTIONS.md`
- Modify: `README.md`
- （`docker-compose.yml` / `.env.example` 已在 Task 13 改完，本任务只核对）

**Interfaces:**
- Consumes: Task 1–14 的全部产出。
- Produces: 更新后的 M3 文档；`m3` 标签（**由控制器在验收通过后打，不由实施者打**）。

- [ ] **Step 1: 更新 `docs/CONVENTIONS.md`**

在**第 4 节「数据面错误契约」**的表里补两行：

```markdown
| `rate_limit_exceeded` | `429` | `rate_limit_error` | 租户超过限流策略（`rate_limit_policy` 的 qps/burst）。**降级到本机令牌桶时同样回 429**（降级 ≠ 放行） | `RateLimitFilter` |
| `model_not_found` | `404` | `invalid_request_error` | 请求的 `model` 在配置快照的 `model_route` 里没有任何可用候选（且没有遗留单渠道可回落） | `ChatRelayController` |
```

并在该节末尾补一条：

```markdown
- **设计文档 §9 的「流开始前失败 → 统一错误体 `{"code","message"}`」已被取代**：`/v1/**` 的错误体**一律**是上面的
  OpenAI 形状。M1 用官方 OpenAI Python SDK 验收过这条契约，给数据面套 admin 信封会让所有 SDK 的
  `error.message` 取值路径同时失效 —— 那不是「按 spec 实现」，是回归。admin 与 `/internal/**`
  仍然是 `{"code","message","data"}`。
```

新增一节 **6.6 渠道密钥与配置快照**（放在 6.5 之后）：

```markdown
## 6.6 渠道密钥与配置快照（admin → gateway）

- **渠道密钥是 AES-GCM 密文**：`channel.api_key_cipher` 存 `v{n}:{base64(nonce‖ciphertext+tag)}`，
  **自描述版本**。主密钥来自环境变量 `AIHUB_CHANNEL_MASTER_KEY`（格式 `v1:<base64 32 字节>[,v2:…]`），
  **不落库、不进镜像、不打日志**。加解密实现只有一份：`com.aihub.common.crypto`（JDK `javax.crypto`，
  仍然零第三方依赖）。
- **明文只在网关本地出现**：admin 只下发密文；gateway 拿到密文后用本地主密钥解密，再把明文注入到
  该渠道的上游请求头。明文**不跨越网络**、不进 admin 的任何日志/指标。轮换 = 「环境变量里同时放旧新两把
  → 解密旧版本重加密为新版本 → 确认没有行还指着旧版本 → 删掉旧密钥」。密文自描述版本让**回退**安全：
  把新密钥去掉，旧密文照样可解。
- **主密钥缺失两侧行为不同（有意）**：admin 是**写入路径**，`encrypt` 直接抛异常（静默写坏数据更糟）；
  gateway 是**请求路径**，解不开只返回空（该渠道被跳过），**照常启动**。
- **快照接口**：`GET /internal/config/snapshot`（HMAC 签名，`GET` + 应用内路径 + 时间戳；契约见第 5 节）。
  一次返回 `{version, generatedAtEpochMilli, defaultModel, channels[], routes[], ratePolicies[]}`。
  `version` 是**三张表 `updated_at` 的最大值**（epoch 毫秒），任何配置写入都会推进它。
- **路由的候选来自 `model_route`**：`priority` **数字小的组优先**，组内按 `model_route.weight`
  **权重随机**（权重非正数按 1）；`channel.status != ACTIVE` 或渠道不可用的行被排除。
  熔断渠道在组内**排到最后**（不是删除）；所有候选都熔断时仍然放行最高优先级那一组（best-effort + WARN）。
- **故障转移只看两件事**：① 上游结果是否属于「可切换」（**429 与 5xx**；4xx 不切换、原样透传）；
  ② 响应是否**尚未提交**（`response.isCommitted()` 为假）。第二个条件是铁律：一旦有字节写回客户端，
  再切换就会把半截响应拼成脏数据 —— 这就是「仅在未输出任何 token 时允许切换」的机器形式。
- **熔断只由 429 触发**（Redis key `aihub:channel:circuit:{id}`，TTL **30 秒**，跨实例共享）；
  5xx 只触发**当次**切换。Redis 不可用时退化为**本机**熔断表（单机近似）。
- **限流按 `tenant + api_key` 两个维度选策略**（与设计文档 §8.1 ② 的维度一致）：先取与本次请求
  `apiKeyId` 匹配的 key 级行（`rate_limit_policy.api_key_id = api_key.id`），没有才用该租户的租户级行
  （`api_key_id IS NULL`），都没有则用内置默认 `qps=10 / burst=20`。**每一维内部**多条 ACTIVE 时取
  `id` 最大的那条（表上没有唯一约束，M4 的控制台会强制单条生效）。非正的 qps/burst 一律回落到默认值。
  **桶的状态维度**是 `aihub:ratelimit:{tenantId}:{sha256(secret)}`（与策略维度是两件事，别混）。
  鉴权关闭时没有数值主键 → `apiKeyId` 为 `null`，此时只按租户级判定（不是「不限流」）。
- **降级链（数据面永不因控制面故障整体不可用）**：Redis 不可用 → 限流退化为**本机令牌桶**
  （单机近似；多实例下实际放行量约为「策略 × 实例数」）**且照常拒绝**；熔断退化为**本机**熔断表；
  admin 不可达 → 继续用**陈旧快照**（Redis 或本地），完全没有快照时才回落到 `aihub.upstream.*`
  合成的**遗留单渠道**（其渠道 id 是 `Long.MIN_VALUE` 哨兵，会在 `request_log.channel_id` 里可见）。
- **限流与配额是两件事**：`RateLimitFilter` 管 QPS/burst（丢弃是暂时的、下个窗口自动恢复）；
  配额（§6.2）管余额（扣减是持久的）。**不要把 429 `rate_limit_exceeded` 与未来的 `QUOTA_EXCEEDED`
  混为一谈**；配额整体属 M4，M3 不碰 `quota` 表。
- **API Key 的吊销 / 停用延迟是显式接受的**：本机 Caffeine ≤30s、集群 Redis ≤5m。M3 **不加**
  吊销广播，也**不写** Pub/Sub 监听器：§6.3 的 Pub/Sub 失效只针对**配置快照**，而 M3 没有配置写入方
  （发布端不存在），为一个不存在的发布端写监听器只会得到一条永远不触发的代码路径。真正的收敛手段是
  M4 的吊销接口 + 显式 `DEL`。
```

并在第 6.5 节里把「`api_key_id` 与 `channel_id` 恒为 NULL」那条替换为：

```markdown
- **`api_key_id` 与 `channel_id` 自 M3 起有值**：前者来自共享 `ApiKeyView` 新增的数值主键
  （`ApiKeyCacheCodec` 的载荷因此是 **6 段**，旧载荷会被判为畸形 → 缓存未命中 → 回源重写，
  这是收敛而非故障），后者来自路由结果。走**遗留单渠道兜底**时 `channel_id` 是 `Long.MIN_VALUE`
  哨兵（不是 NULL），因此「走了兜底路径」在 `request_log` 里可查。超长 `model`（>128 字符）**只在
  计量事件里被截断**，转发给上游的请求体逐字节不变。
```

- [ ] **Step 2: 更新 `README.md`**

- 「当前进度」把 M3 勾上，并加一段「M3 到底做了什么」：Redis + Lua 令牌桶限流（**策略按 `tenant + api_key` 两维选取**：key 级覆盖 → 租户级回落 → 内置默认；Redis 挂了降级本机桶且**照常拒绝**）、多渠道路由（`priority` 分组 + 权重随机 + 跳过熔断）、
  故障转移（429/5xx/超时换下一候选，**仅在尚未向客户端转发任何字节时**；4xx 不换）、
  跨实例熔断（Redis 30s TTL）、渠道密钥 AES-GCM（admin 加密 / 网关本地解密 / 双版本轮换）、
  HMAC 签名的配置快照 + 三级读取两级缓存（Caffeine 30s → Redis → admin，singleflight + 版本比对），
  以及 M2 的三处遗留（白名单补 `retry-after-ms` / IETF `RateLimit-*`、超长 `model` 截断、
  `api_key_id`/`channel_id` 落库）。
  **多渠道故障注入的验收**用 WireMock（test 作用域、进程内）在 `WireMockChannelFaultInjectionTest` 里做
  —— 那是设计文档 §10 / §12 的原文口径。
- 「网关不做限流 / 多渠道」那两条**已知边界要删掉**（已实现），换成新的边界：
  - **配额（预扣 / 校正 / 对账）整体属 M4**，M3 做的是**限流**（QPS/burst，丢弃是暂时的）而不是
    **余额记账**（token 余额，扣减是持久的）。429 `rate_limit_exceeded` 与未来的 `QUOTA_EXCEEDED`
    是两回事。
  - 限流的**降级近似**：Redis 不可用时的本机桶只看得见本进程流量（多实例下实际放行量 ≈ 策略 × 实例数）。
  - 熔断的**触发面只有 429**：5xx 只做当次切换，不熔断。
  - 所有候选都在熔断中时**仍然会尝试**最高优先级的那一组（best-effort，会打 WARN）。
  - 配置变更的**热生效延迟**：M3 没有 Pub/Sub 发布方（配置 CRUD 在 M4），因此生效靠
    「本地 TTL 30 秒 + 快照 `version` 比对」；`version` 是**毫秒级**时间戳，同一毫秒内改两行会撞车。
  - **`channel.models_json` 不参与路由**（路由只用 `model_route`）；`GET /v1/models` 的模型集合
    来自「`model_route` 出现过的模型名 ∪ 遗留默认模型」。
  - 限流的**指标**（`aihub.ratelimit.rejected` / `aihub.ratelimit.degraded`）与熔断日志**不对外暴露**
    （`/actuator/metrics` 仍是 `health,info`，属 M6）。
  - **Redis 仍然没有密码**（`requirepass` 是加固项，未在 M3 做）。
  - **`api_key_id` / `channel_id` 的语义**：走兜底路径时 `channel_id` 是 `Long.MIN_VALUE` 哨兵；
    `api_key_id` 在鉴权关闭时为 `NULL`。
- 「测试」一节的实测数字改成**你跑出来的数字**（M3 结束的预期见下，**以实测为准**）。
- 「技术栈」一行**要改一处**：网关测试新增 `org.wiremock:wiremock:3.9.1`（**仅 test 作用域、仅
  `aihub-gateway`**，进程内起 stub，不需要 Docker；生产依赖零新增）。这是设计文档 §4.2
  「WireMock 在 M3 引入时再锁定版本」那一句的落点，**必须写进 README**（否则下一轮评审还会问
  「为什么多了一个依赖」）。

- [ ] **Step 3: 全量回归**

```powershell
$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B clean test
```

预期：每个模块 `Failures: 0, Errors: 0, Skipped: 0`，最后 `BUILD SUCCESS`。
**Maven 的退出码不可信**（本机见过 `BUILD SUCCESS` + `[exit code: 1]`）：以各模块的 surefire 汇总行与
`BUILD SUCCESS` 为准。把三个数字抄进 README。

顺手核对依赖（决策 11 的验收项）：**生产依赖零新增**，唯一变更是 `aihub-gateway` 的
**test 作用域** WireMock。

```powershell
git diff m2 -- aihub-admin/aihub-common/pom.xml
git diff m2 -- aihub-gateway/pom.xml
mvn -B -pl aihub-gateway dependency:tree "-Dincludes=org.wiremock:wiremock"
```

预期：
1. `aihub-common/pom.xml` **无输出**（M2 之后逐字节未变）；若 `m2` 标签不在解析范围内，用
   `git diff $(git rev-list -n1 m2) HEAD -- ...`。
2. `aihub-gateway/pom.xml` 的 diff **只有**一段 `org.wiremock:wiremock:3.9.1` + `<scope>test</scope>`
   与它的注释（**没有别的依赖、没有版本号被顺手升级**）。
3. `dependency:tree` 输出里 `org.wiremock:wiremock:jar:3.9.1:test`（**`:test` 结尾**，不是 `:compile`）。
   若它出现在 `compile`/`runtime`，说明 scope 写错了 —— 那是必须修的（会把测试依赖带进生产镜像）。

- [ ] **Step 4: compose 全栈冒烟 + 多渠道验收（由控制器执行）**

```powershell
docker -H tcp://127.0.0.1:2375 compose up -d --build
```

预期：五个服务起来；admin（8081）与 gateway（8080）的 `/healthz` 都 200 `"status":"UP"`；
RabbitMQ 管理台能看到 `aihub.metering.queue` 与 `aihub.metering.dlq`。

> **禁止**：不要执行 `docker compose config` 或任何会把 `.env` 插值打印出来的命令。要看容器内变量时用
> `docker compose exec <svc> printenv <NAME>`（只打你指定的那一个）。

- [ ] **Step 5: 里程碑验收（多渠道故障转移 + 限流）——由控制器执行**

**这是 M3 的验收标准**：`WireMock 注入 429/超时，能自动切换`。它由**两层**证据共同满足，两层都要如实报告：

1. **进程内的 spec 原文口径**（必做、无外部依赖）：`WireMockChannelFaultInjectionTest`（Task 10）用
   WireMock 的命名 stub 扮多条渠道，分别注入 429 / 挂住超时 / 中途断流，并验证自动切换与「已开始回写
   就不切换」。跑 `mvn -B -pl aihub-gateway test -am "-Dtest=WireMockChannelFaultInjectionTest"`，
   把 `Tests run: 5, ...` 的汇总行抄进验收记录 —— 这就是 spec §12 那句话在**测试层**的落点，
   **不需要 Docker**（WireMock 与网关在同一个 JVM 里）。
2. **compose 全栈上的数据面验收**（下面 1–7 步，由控制器执行）：证明同一套语义在**真实部署形态**
   （admin + gateway + MySQL + Redis + RabbitMQ）下也成立。**必须在发布的 compose 全栈上做**（不是只在测试里）：

1. 起一个「坏上游」夹具（只回 429 的小 HTTP 服务）与一个「好上游」（本机 Ollama，或另一个只回正常 JSON
   的小服务）。**不要**把任何真实密钥写进文件；用环境变量传。
2. 让 admin 里有两条同模型渠道：`demo-bad`（指向 429 夹具）与 `demo-good`（指向好上游）。用 seeder
   （`AIHUB_DEMO_SEED_ENABLED=true` + 两个 base-url 环境变量）或直接在容器里 `insert`：

```powershell
docker -H tcp://127.0.0.1:2375 compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -D aihub -e "select id,name,base_url,key_version,weight,priority,status from channel; select model_name,channel_id,weight,priority,status from model_route;"'
```

3. 铸一把 key，然后连续发两次请求并记录响应：
   - 第 1 次：客户端应拿到**好上游的 200**（说明从 429 渠道切走了）；
   - 第 2 次：**应该直接走好上游**（说明 429 渠道已被熔断，不再被打）；
   - 查 Redis：
     `docker -H tcp://127.0.0.1:2375 compose exec -T redis redis-cli keys 'aihub:channel:circuit:*'`
     与 `... redis-cli keys 'aihub:ratelimit:*'` —— 前者应能看到被熔断的渠道 id（30 秒后消失），
     后者应能看到限流桶 Hash（`hgetall` 可看 `t`/`k` 两个字段）。
   - 查 `request_log`：两次的 `channel_id` 都应是**好渠道**的 id（证明计量记的是实际服务的那条）。
4. **限流验收（两维）**：把该租户的策略临时改成 `qps=1 / burst=2`（或直接用 seeder 写的 20/40 连发
   50 次），应看到 `429` + `{"error":{"code":"rate_limit_exceeded",...}}` +
   `RateLimit-Remaining`/`Retry-After`/`Retry-After-MS` 头。**并且做一次 key 级覆盖的对照**（决策 7 修订）：
   seeder 给该租户的**第一把 key** 写的是更严格的 `key-qps=5 / key-burst=10`，因此
   `docker ... compose exec -T mysql sh -c '... select tenant_id,api_key_id,qps,burst,status from rate_limit_policy ...'`
   应能看到两行（一行 `api_key_id IS NULL`，一行指向那把 key），且用**那把 key** 发请求时日志/响应头里的
   `RateLimit-Limit` 是 **`5, 10`**（key 级生效），换一把新铸的 key 则是 **`20, 40`**（回落租户级）。
5. **限流降级验收**：`docker compose stop redis` 后继续发请求 —— **必须继续被限流**（不是 503、也不是
   无限放行），网关日志里出现「已降级为本地令牌桶」。`docker compose start redis` 后恢复共享桶。
6. **admin 挂掉验收**：`docker compose stop admin` 后发请求 —— 网关**继续按缓存的快照路由**（不 503）；
   冷缓存的新 key 仍然是 401（fail-closed，M1 的既有行为）。
7. 把上述命令与真实输出（**删掉 token 明文**）写进 `.superpowers/sdd/m3-acceptance.md`。

若控制器无法提供「坏上游」夹具（例如本机没有任何上游），**报告 BLOCKED 并说明缺什么**，不要伪造结果。
此时进程内的 `WireMockChannelFaultInjectionTest`（spec 原文的 WireMock 口径）与 `FailoverRelayTest`
（JDK 夹具的细粒度口径）仍然给过等价证据，可以以它们为准，但**必须如实说明**
「compose 全栈上的多渠道切换本轮未做」，并写进 README 的已知边界。

- [ ] **Step 6: 打标签（仅控制器，验收通过后）**

```powershell
git tag -a m3 -m "M3 流量治理完成：Redis+Lua 令牌桶限流（含本机降级）+ 多渠道路由（优先级/权重/熔断）+ 故障转移（仅在未输出任何 token 时切换）+ AES-GCM 渠道密钥本地解密 + HMAC 配置快照与两级缓存。"
```

> 提醒（M2 的实测坑）：`m3` 标签名与分支名 `m3` 相同时，`git` 会打印 `refname m3 is ambiguous`
> —— 引用标签时写 `refs/tags/m3`。

---

## 附：M3 不做的事（写进文档，避免范围蔓延）

- **配额预扣 / 实际校正 / 异步对账**（§6.2）—— **M4**。M3 做的是**限流**（QPS/burst），不是**余额记账**。
- **Redis Pub/Sub 失效的发布方与订阅方**（配置 CRUD 在 M4）。
- **管理台、`/api/channels` CRUD、`/api/api-keys`、租户 / API Key 管理、审计、账单、`GET /api/logs`** —— M4。
- **上游主动健康探测**（`/api/channels/{id}/probe`）—— M4。
- **`channel.models_json` 的路由语义** —— M4（与渠道 CRUD 一起定）。
- **`GET /v1/embeddings`、文档流水线** —— M5。
- **压测报告、故障注入报告、`/actuator/metrics` 暴露** —— M6。
- **Redis `requirepass` 与网络隔离加固** —— 未在本里程碑做（README 继续披露）。

---

## 附：本计划的实证依据与已知坑（实施者不必重试）

1. **`-Dtest=A+B` 在 Surefire 3.5.6 上不选中两个类**（实测：`BUILD SUCCESS` 却静默跳过），
   必须用 `-Dtest=A,B`；pwsh 里逗号要被引号包住。单独构建一个模块要加 `-am`
   （`.m2repo` 里有空的 `aihub-common` jar，会报假的 "package does not exist"）。
2. **Maven 退出码不可信**：`BUILD SUCCESS` + `[exit code: 1]` 实测多次出现过。
3. **Surefire 对含 `@Nested` 的外层类打印 `Tests run: 0`**：以 `target/surefire-reports/*.xml` 为准。
4. **Docker 只能走 TCP**（`-H tcp://127.0.0.1:2375` / `$env:DOCKER_HOST`）；`~/.testcontainers.properties`
   **不要动、不要写 BOM**（写坏过一次，全部容器测试变红）。
5. **本机 ANSI 代码页是 GBK（cp936）**：`Get-Content` / `Set-Content` / `[System.IO.File]::WriteAllLines`
   会把 UTF-8 中文文档整篇变成乱码（本计划编写时踩过一次，只能整篇重写）。中文文档一律用
   `write` / `edit` 工具处理。
6. **JDK 的 `com.sun.net.httpserver.HttpServer` 足以构造 M3 需要的全部上游故障**
   （429/5xx 用 `enqueueError`，连上不回响应头用 `enqueueStall`，中途断流用 M2 已有的握手夹具），
   因此它**继续承担细粒度用例**；但 spec §10 / §12 的验收口径写的是 **WireMock 模拟多渠道**，
   所以**多渠道矩阵**那一层改用 WireMock 承担（决策 11）—— 两者并存，不是替代关系。
7. **WireMock 3.9.1 在本机可解析、可构建、可**进程内**运行（2026-09-26 实测，实施者不必重试）**：
   - 直连 `https://repo.maven.apache.org/...` **不通**（`Invoke-WebRequest` 报「基础连接已经关闭」），
     但 `mvn -B dependency:get "-Dartifact=org.wiremock:wiremock:3.9.1"` **成功** —— 全局
     `settings.xml` 里配了 `aliyunmaven` 镜像（`mirrorOf=*`），产物落进 `.m2repo`（`_remote.repositories`
     里记的是 `aliyunmaven`）。**因此在这一台机器上，能不能拿到新依赖要看阿里云镜像，不是 Maven Central。**
   - 在**仓库之外**的探针工程里用 `@WireMockTest` + `junit-jupiter 5.12.2` + `surefire 3.5.6` 跑通
     429 stub / `withFixedDelay` 挂住（客户端超时）/ `Fault.MALFORMED_RESPONSE_CHUNK` 中途断流，
     `Tests run: 1, Failures: 0, Errors: 0` + `BUILD SUCCESS`；stub 起在本进程的随机端口（60380），
     **无 Docker、无活 broker**。
   - 探针**不要放进本仓库**（它是临时工程）；把它当成「依赖可用性已证」的记录即可。
   - 注意：探针那次 `mvn` 进程退出码是 `1`，而日志里是 `BUILD SUCCESS` —— 又一次印证第 2 条。
8. **AES-GCM 的 JDK 实现**：`AES/GCM/NoPadding` + 12 字节 nonce + 128 位 tag；`javax.crypto` 是 JDK 的一部分，
   `aihub-common` 的零依赖规则不受影响（`InternalHmac` 已经先用过 `javax.crypto`）。
9. **Spring Data Redis 3.5.13 的 `RedisScript.of(String, Class)`** 存在（本机 `.m2repo` 已确认）；
   返回类型必须是 `List`（脚本返回数组），写成 `Long.class` 会在运行时得到 `null`/转换异常。
10. **`StringRedisTemplate` 是阻塞 API**：Redis 不可达时一次调用会被按住 `spring.data.redis.timeout`
    （2 秒）。M3 的缓解手段是 `RateLimiter` 的**粘性降级**（1 秒内不再重试），而不是每请求撞一次超时。
11. **`ApiKeyCacheCodec` 段数从 5 → 6 是刻意的不兼容**：旧 Redis entry 会被新 `decode` 判为畸形并返回
    `null`（缓存未命中 → 回源 → 重写）。这是**收敛行为**，不需要清 Redis，但 `ApiKeyToolingTest`
    的固定向量必须同步（那是契约测试，改它是正确的）。
12. **`docker compose config` 会把 `.env` 插值打进 stdout**（M1 泄漏过一次真实上游 key）：
    **永远不要执行它**，也不要 `cat` / `type` / `Get-Content` `.env`。
13. **本机 `git push` 不可能成功**（hosts 黑洞 + 出口阻断）：只做本地提交，控制器会在里程碑末尾
    通过 GitHub Git Data API 重放提交。
14. **`ApiKeyView` 从 5 个分量变 6 个是编译期可见的破坏**：所有既有 `new ApiKeyView(...)` 都会被编译器
    点出来（这是好事）。这个破坏**集中在 Task 2 一次发生**（契约 + `ApiKeyCacheCodec` 段数 + admin
    `ApiKeyService` 填充 + gateway `AdminClient.parse` 透传 + 所有既有调用点，同一个提交），
    Task 2 的验收标准里有「全反应堆 `test-compile` 绿」。**Task 7 / 9 / 10 / 11 的代码与测试一律按
    6 个分量写**：契约已经在那里了，不存在「先写 5 个分量、以后再补」的写法。
    **历史上的教训（诚实登记）**：本计划最初把决策 14 放在 **Task 11**，于是 Task 9 的 `RateLimitFilter`
    只能把 `apiKeyId` 写成显式占位 `null`、并注明「Task 11 收口」，Task 7 也留了一条「先不要改 `parse`」
    的注释。那让 Task 9 的提交**不满足「独立可提交、编译即绿」**这条纪律，也留下一个跨任务占位。
    2026-09-26 已把契约前移到 **Task 2** 修掉（见全局约束）。**如果实施者看到任何「占位 `null` +
    以后补」的注释，那是漏改，按契约就在 Task 2 处理，不要沿用它。**
15. **本计划的三处修订（2026-09-26，控制器 pre-flight 评审与结构校正）**，实施者按修订后的正文执行即可，
    但要知道**哪三处被改过**，以免照旧印象做事：
    - **决策 7**：限流策略从「只做 `tenant` 维度」改为 **`tenant + api_key` 两维**（key 级优先 →
      租户级回落 → 内置默认）。原文的 blocker（「拿不到数值 `api_key` 主键」）不成立，因为决策 14
      正好给 `ApiKeyView` 补了 `apiKeyId`，而限流过滤器排在鉴权之后。
    - **决策 11**：从不引入 WireMock 改为 **引入 `org.wiremock:wiremock:3.9.1`（仅 `aihub-gateway`
      的 test 作用域）**，用于 spec §10 / §12 要求的多渠道故障注入验收；JDK `HttpServer` 夹具**保留**。
    - **决策 14 的落点从 Task 11 前移到 Task 2**（结构校正）：`ApiKeyView` 的第 6 个分量、
      `ApiKeyCacheCodec` 的 5 → 6 段、`ApiKeyToolingTest` 的固定向量与两条新用例、admin `ApiKeyService`
      的填充、gateway `AdminClient.parse` 的透传，全部与共享数据契约同在 **Task 2** 的一个提交里；
      Task 7 不再动 `parse`，Task 9 直接读 `view.apiKeyId()`（**占位已删除**），Task 11 只剩下
      `api_key_id` / `channel_id` 落库、超长 `model` 截断与透传白名单三件事。理由：Task 9 的过滤器
      与 Task 4 的策略解析都需要这个数值主键，契约晚于使用方会让 Task 9 无法独立提交（违反全局约束
      的「每个任务的提交必须让整个反应堆编译通过，且不许跨任务占位」）。
