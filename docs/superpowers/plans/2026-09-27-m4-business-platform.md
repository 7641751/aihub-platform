# aihub-platform M4（业务平台）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 M0–M3 建成的「能跑的数据面」变成**可运营的产品**：一张带登录的极简管理台 + 一组受保护的 `/api/**` 控制面接口（租户 / API Key / 渠道 / 模型路由 / 限流策略的 CRUD，**改完必须真的在数据面生效**）、§6.2 的**配额**（Redis 预扣 → 实际校正 → 每日对账）、以及**审计**（谁在什么时候改了什么）。同时把 M3 交接过来的两条**必须先修的前置缺口**收掉：配置变更的**主动失效**（否则「控制面配置 → 数据面生效」这条验收标准演示不出来）与快照 `version` 的**单调性**（否则删掉最新一行会让版本倒退）。

**Architecture:** 控制面仍是 admin 独占 MySQL 真相源（决策 A 延续），gateway 仍然**不连数据库**。新增的三条链路：① **写路径**：`/api/**`（控制台令牌）→ 服务层（`aihub-service`）→ MySQL + **审计行** + **Redis Pub/Sub 失效广播**；② **失效路径**：gateway 订阅 `aihub:config:invalidate`，收到后**同时**清本地 Caffeine、**删共享 Redis 快照条目**、**把写入水位抬到失效消息里的版本（绝不重置）**，然后按需回源 admin —— 这是设计文档 §6.3 明文要求的机制，M3 因为「没有写入方」而按决策 16 推迟到本里程碑；③ **配额路径**：gateway 在鉴权之后、上游之前用 **Redis + Lua 原子预扣**（键 `aihub:quota:{tenantId}:{period}`），余额不足直接 **429 `insufficient_quota`**；拿到真实 `usage` 后**补扣/退回**差额；计量事件照旧经 MQ 落 `request_log`（幂等），每日 02:00 的对账任务按 `request_log` 重算 `billing_daily` 并与 Redis 计数比对（**只报告与告警，不自动改账**）。管理台是**零构建**的静态单页（vanilla JS + `fetch`，放在 `aihub-web` 的 `static/` 下），由 admin 自己伺服 —— 不引前端框架、不引 Spring Security 的过滤器链、不引 JWT 库。

**Tech Stack:** Java 21（编译目标，运行于 JDK 25.0.2）、Spring Boot 3.5.16、Spring MVC（admin）+ WebFlux（gateway）、Spring Data Redis 7（Lettuce 同步 API）+ Lua + **Pub/Sub**、MyBatis-Plus 3.5.17、MySQL 8.4、Flyway、RabbitMQ 3.13、**`org.springframework.security:spring-security-crypto`（生产依赖之一，只为 bcrypt 口令哈希；另一处是 Task 2 裁决的 `io.micrometer:micrometer-core`，见全局约束的修订）**、自研 HS256 控制台令牌（JDK `javax.crypto`，与既有 `InternalHmac` 同一手法）、JUnit 5 + AssertJ + Mockito、Testcontainers（admin 侧）、宿主 `com.sun.net.httpserver.HttpServer` 夹具 + WireMock（gateway 侧）、Maven 3.9.12。

## Global Constraints

- 项目根目录：`D:\PycharmProjects\aihub-platform`。基线：`master` = `30e1ce1`（含标签 `m0`/`m1`/`m2`/`m3`）。**本计划不负责建分支**，由控制器决定在 `master` 上提交还是切 `m4`。
- 编译目标固定 Java 21（`<maven.compiler.release>21</maven.compiler.release>`），本机 JVM 为 JDK 25.0.2。Spring Boot 固定 `3.5.16`，MyBatis-Plus 固定 `3.5.17`。不要改。
- **生产依赖只新增一个**：`org.springframework.security:spring-security-crypto`（**版本走 Spring Boot BOM，不写死版本号**），作用域 `aihub-admin/aihub-service`（登录路径用它做 bcrypt），`aihub-web` 通过依赖传递拿到。**明确不引**：`spring-boot-starter-security`（会引入全局过滤器链与自动配置，与既有 `InternalAuthFilter` 形成两套鉴权，且会改变 `/internal/**`、`/healthz` 的既有行为）、任何 JWT 库（`jjwt` / `java-jwt` / `nimbus`）。`aihub-common` 的 main 作用域**仍然零第三方依赖**（新增的令牌编解码只用 JDK 类型）。
- **`pom.xml` 的改动清单是封闭的，且恰好两条**（**2026-09-28 修订**）：① `aihub-admin/aihub-service/pom.xml` + **`io.micrometer:micrometer-core`（不写版本，走 Boot BOM）** —— Task 2 实测发现 `Counter` **不在** `aihub-service` 的编译类路径上（micrometer-core 只经由 actuator 到达 `aihub-web`/`aihub-gateway`）；而它**本来就在 admin 的运行时类路径上**，所以补声明是把一个已经成立的事实写清楚、运行时不新增任何 artifact，也让后续所有 admin 侧计数器（Task 15 的 `aihub.quota.reconcile.*`）不必各自即兴；② `aihub-service/pom.xml` + `spring-security-crypto`（Task 5，版本同样走 Boot BOM）。任何**其它** `pom.xml` 改动都要在报告里单独登记理由。
- **`docker-compose.yml` 与 `.env.example` 必须一起改，否则 Task 17 的验收根本跑不起来**：compose 的 `services.admin.environment` 与 `services.gateway.environment` 是**显式白名单**，没列进去的环境变量在容器里是空的。因此 Task 17 必须：① 给 `admin` 加 `AIHUB_CONSOLE_SECRET: ${AIHUB_CONSOLE_SECRET:?...}`；② 给 `gateway` 加 `AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION: ${AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION:-true}`（验收第 4 步的**反证**要靠它把订阅关掉）；③ `.env.example` 给 `AIHUB_CONSOLE_SECRET` 的生成方法（两种 PowerShell 写法 + openssl）。**除了这三处，`docker-compose.yml` 不许再动**（尤其不要碰 Redis 的宿主映射）。
- **admin 侧集成测试的既有惯用法**（本计划的测试草图必须落成这个形状，否则新写的类跑不起来）：`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` + **显式 `HttpEntity`**（含 header），装配基类是 `AbstractIntegrationTest`（Testcontainers 单例：MySQL + Redis + RabbitMQ，`@Autowired` 注入 `JdbcTemplate` 与各 Mapper）；它**不提供** `redis`/`upstream`/`container`/`snapshotService` 这类字段，测试自己要什么就自己注入什么。**全文出现的 `post(...)`/`get(...)`/`jsonPath(...)`/`read(...)`/`postBytes(...)`/`stubQuota(...)`/`activeRowsFor(...)`/`createTenantPolicy(...)` 都是「这段测试在断言什么行为」的伪代码**：每个任务在 Step 1 里必须把它们落成真实的 `TestRestTemplate` 调用与真实的注入（`git grep 'TestRestTemplate' aihub-admin/aihub-web/src/test` 有现成例子可抄）。
- **本里程碑获准新增 Flyway 迁移，且只有一条**：`V2__m4_console.sql`（D1）。`V1__init_schema.sql` **已执行过、禁止修改**。`SchemaMigrationTest` 里「恰好 1 条迁移」的断言**有意**改成「恰好 2 条并列出两条文件名」——那是把护栏改成「数量与内容都显式受控」，不是削弱（见 D1 的理由与残余）。
- 端口不变：admin `8081`（**管理台就跑在这里**）、gateway `8080`、MySQL 宿主 `3307`、Redis 宿主 `6380`（容器内 `6379`）、RabbitMQ `5672` / 管理台 `15672`。**不要改 `docker-compose.yml` 里 Redis 的宿主映射**（`127.0.0.1:6380:6379` 是用户刻意设的）。
- **两套响应契约不要混用**：admin（含 `/internal/**` 与**新增的 `/api/**`**）是 `{"code","message","data"}`；gateway `/v1/**` 是 OpenAI 兼容体 `{"error":{"message","type","param","code"}}`。**配额超限走数据面契约**：`429` + `code="insufficient_quota"` + `type="insufficient_quota"`（D6），**不要**用 admin 信封，也**不要**复用 `rate_limit_exceeded`。
- **M1 的字节级透传是铁律**且本里程碑不改动它：上游状态码 / `Content-Type` / 响应体字节在流式与非流式下都原样回写；唯一合法的切换时机仍是「响应尚未提交」。**新增的 `QuotaFilter` 必须排在鉴权之后、限流之后、且在响应提交之前只做一次判定**，绝不能在中途改写已提交的响应。
- **明文密钥的边界**（M1/M3 已确立，本里程碑新增两处约束）：① API Key 明文**只在创建响应里返回一次**，此后任何接口（含列表、审计、日志、管理台页面）都不许再出现它；② 渠道明文密钥只在 seeder / 控制台**写入**时短暂出现在服务层内存里，**绝不**进审计、日志、指标、响应体。
- **禁止读 / 打印 / echo `.env` 或任何密钥文件**；不要执行 `docker compose config` 或任何会把 `.env` 插值打进 stdout 的命令。判断某个环境变量是否存在时**只判断是否为空**。M1 曾因此泄漏过一次真实上游 key。
- **两条「静默给错答案」的数据库陷阱（2026-09-29，Task 7 的独立评审在真容器里实测；Task 8/9/10/11 一律适用；第 1 条已按 2026-09-29 的第二次独立评审更正）**：
  1. **`datetime(3)` 必须映射成 Java `LocalDateTime` 并在应用侧显式按 UTC 写入**
     （`LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)`，照抄 `RequestLogService`），
     **不要用 `Instant`**，也**不要**依赖 `DEFAULT CURRENT_TIMESTAMP(3)`（那写的是数据库会话时区）。
     **连接时区必须显式钉死，而且新代码不许依赖它。** 驱动按哪个时区解释墙上时间取决于 **JDBC 连接时区**：
     **只有连接时区解析成 LOCAL 时**（URL 不带 `serverTimezone` / `connectionTimeZone`）才按 **JVM 默认时区**
     解释，本机（Asia/Shanghai）因此读回**早 8.0 小时**。**但这不是生产上正在发生的错误**：
     发布的两个 URL 都钉了 `serverTimezone=UTC`（`application.yml:8`、`docker-compose.yml:65`），
     第二次独立评审在那条连接上实测**旧写法与新写法逐位相等（`old − new = 0`，两个列都是 0）**，
     而**测试** URL 恰好相反 —— 它是 Testcontainers 返回的**裸 URL**（无参数 ⇒ LOCAL），这个不对称
     当时没有任何地方写下来，正是「RED 看起来像生产缺陷」的原因（现已显式化，见 `docs/CONVENTIONS.md` §8）。
     窗口对照也只在非 UTC 连接上复现：复核实测同一个 ±10 分钟窗口在无参数连接上 `LocalDateTime`(UTC) 绑定
     命中 **1/4** 行、`Timestamp` 绑定命中 **3/4** 行；在 `serverTimezone=UTC` 连接上两种绑定选中的是
     **同一个窗口**（2026-09-30 复测：3/4 与 3/4，两条计数相等）。计数依赖 fixture（4 行、四种绑定方式），
     fixture 与复测数字一起写在 `docs/CONVENTIONS.md` §7 第 2 条 —— 数字会随 fixture 变，**性质不会：
     生产方言下两种绑定等价，无参数（LOCAL）方言下不等价**。
     所以这是一条**纪律**（① 连接时区显式钉死；② 基准写在代码里，不依赖连接参数也不依赖 JVM），
     而不是一条「生产在错」的记录。**Task 11 的日志/审计时间范围查询必须绑 `LocalDateTime`(UTC)。**
  2. **`eq(column, null)` 恒不成立，MyBatis-Plus 不会替你忽略它**：生成 `col = ?`（参数 `null`）⇒ 恒为
     UNKNOWN ⇒ **静默返回 0 行**。按**可选维度**（`tenant_id`、`api_key_id`）过滤**必须**用
     `isNull()`/`isNotNull()`。Task 8/9/10 的列表查询与 Task 11 的日志/审计查询都会遇到。
  两条的详细实测证据与写法都写在 `docs/CONVENTIONS.md` §7。
- **审计表绝不记录**：API Key 明文、渠道明文密钥、`api_key_cipher` 密文原文、主密钥、控制台口令/口令哈希、令牌原文。审计记的是「谁、什么时候、对哪个对象、做了什么、改了哪些**非敏感**字段」。
- 所有时间字段按 UTC 存储（`datetime(3)`）；`quota.period` 用 `YYYYMM`（D13）。控制台令牌的时间基准是 `Instant.now()`（秒），配额/限流的时间基准是**调用方传入的毫秒时间戳**（沿用 M3 决策 9：不用 Redis `TIME`）。
- **`aihub-gateway` 的测试永远不允许依赖 Docker，也不允许要求有活 broker 或活 Redis**：Redis 的故障降级用例一律用「指向不存在的端口 / Mockito 桩」构造，WireMock 必须**进程内**使用。admin 侧集成测试沿用 `AbstractIntegrationTest`（Testcontainers 单例：MySQL + Redis + RabbitMQ 已在基类）。
- 每个 Task 完成后立即 commit（conventional commits），**只 stage 显式路径**，禁止 `git add -A` / `git add .`。
- **每个任务的提交必须让整个反应堆编译通过，且该任务自述的测试全绿**；共享契约（Pub/Sub 频道名与载荷、配额 Lua 与键布局、令牌格式、`/api/**` 的错误码）必须在**第一个需要它的任务之前**就位，**不允许跨任务占位**（不允许「先传 null、后面任务再补」，也不允许「此处待后续任务收口」这类注释）。判定方法：每个任务结束前跑一次全反应堆 `mvn -B -q test-compile -DskipTests`，必须绿。
- 命令一律在项目根目录执行；不用 Maven wrapper，用本机 `mvn`。
- **不做的事**（按此判断越界）：`/v1/embeddings` 与文档流水线（M5）、压测/故障注入报告与指标端点暴露（M6）、Redis `requirepass` 与网络隔离生产加固（M6）、多实例部署编排、账单计费单价（`billing_daily.cost` 本里程碑只写 0 并登记）、RS256/JWKS/refresh token、第三方登录、管理台的多租户 RBAC 细粒度权限（只做 `ADMIN`/`VIEWER` 两级）。
- **控制面（`/api/**`）的租户模型（2026-09-30 定死；Task 10 起一律照办，完整规则、理由与"不许用的方案"见 `docs/CONVENTIONS.md` §10）**：控制台是**平台运营台**，不是租户自助台（依据就是上一条"不做的事"与 Task 11 的 `?tenantId=` 接口）。四条规则：**R1** 全局资源（`tenant` / `channel` / `model_route`，表里**没有** `tenant_id`）的读写是平台级，任何 `ADMIN`；**R2** 租户维度资源（`api_key` / `quota` / `rate_limit_policy`）的**写**同样是平台级（运营必须能对违规租户做**应急吊销**），但审计的 `tenant_id` **必须**记**目标资源的**租户 id（不是操作者的租户、不是 NULL）；**R3** 租户维度资源的**查**分两类 —— **运营查询**（`/api/logs`、`/api/audit`、`/api/billing/daily`）**必须显式 `tenantId`**、缺省 400（防无界扫描），**资源列表**（`/api/api-keys`，将来的 `/api/quotas`、`/api/rate-limits`）**缺省 = 令牌里的 `tenantId`**（least privilege；将来运营要跨租户列举再加**可选**覆盖，今天不做）；**R4** 令牌里的 `tenantId` **不是授权边界**，只在 R3.2 的缺省值上起作用。**不许**用 MyBatis-Plus 的 `TenantLineInnerInterceptor` 之类的全局租户拦截器（控制面不是唯一数据访问方：`ApiKeyService.resolve` 由 gateway 经内部 HTTP 调、`MeteringConsumer` 在 MQ 线程里、`@Scheduled` 的维护/对账任务**必须跨租户**，三者都没有租户上下文）。**升级门槛（硬）**：① 出现**第一个非平台方账号**、② 控制台对**非可信网络**暴露、③ 引入**租户自助** —— 三者任一成立，前提即失效，**必须先落地租户隔离（第三级角色 + 写路径按租户收窄）再继续**。

### 本机环境前提（**每一条都在别的里程碑上真实踩过**，照做）

- 每次 shell 调用都是**全新的 `pwsh` 进程**：变量、工作目录都不保留；每条命令显式带 `workdir`。
- **Docker 只能走 TCP**：`docker` CLI 必须带 `-H tcp://127.0.0.1:2375`；Maven / Testcontainers 需要 `$env:DOCKER_HOST='tcp://127.0.0.1:2375'`（**同一次**调用前 export）。Docker Desktop 冷启动约 160 秒。
- **永远不要修改 `~/.testcontainers.properties`，也永远不要给它写 BOM**。
- `.mvn/maven.config` 是**未跟踪、已 gitignore** 的本机适配文件（`-Dmaven.repo.local=.m2repo`、surefire argLine、`-Dsurefire.failIfNoSpecifiedTests=false`）。不要改、不要 `git add`。
- **`-Dtest=A+B` 在 Surefire 3.5.6 上不会选中两个类**（它被当成一个类名，而 `.mvn/maven.config` 关掉了「没匹配就失败」，于是打印 `BUILD SUCCESS` 却**静默跳过**）。永远用逗号形式 `-Dtest=A,B` 并加引号。
- **单独构建一个模块必须加 `-am`**（`.m2repo` 里有一个**空的** `aihub-common` jar，否则报假的 "package does not exist"）。
- **Maven 的退出码不可信**：判定标准永远是 **surefire 汇总行 + `BUILD SUCCESS`**。含 `@Nested` 的外层类可能打印 `Tests run: 0`，此时以 `target/surefire-reports/*.xml` 为准。
- **Maven 会静默复用陈旧 `.class`**：要信的一次运行前先删 `target/surefire-reports`。**不要**用 `mvn -Dmaven.test.skip` 之类的组合去"省时间"。
- 在 shell 里调外部命令前先 `$ErrorActionPreference='Continue'`（原生 stderr 会变成终止性 `NativeCommandError`）。
- `Set-Content -Encoding UTF8` 在这个 shell 上**会写 BOM**；写仓库文件一律用 `write` / `edit` 工具。**绝不要**用 `Get-Content` / `Set-Content` / `[System.IO.File]::WriteAllLines` 处理本仓库的中文文档（本机 ANSI 代码页是 **GBK**，会把整篇中文变成乱码）。批量改写中文文档只能用 `write` / `edit`。
- `[System.IO.File]::ReadAllText/WriteAllText` 的**相对路径按进程当前目录解析，不认 `Set-Location`**；要用绝对路径（M4 之前踩过）。
- **工作树里有第二个写入者**（用户或他们的编辑器/代理）。每次派发子代理前、以及每个子代理返回后都要 `git status --porcelain`；发现非自己改动的文件**不要**顺手 stage，先报告。
- 磁盘上已有 `mysql:8.4`、`redis:7-alpine`、`rabbitmq:3.13-management-alpine`、`testcontainers/ryuk:0.12.0` 镜像；Docker Hub 直连不可达，用 `--pull never`（本地已有）或走镜像。**Maven 依赖走 `aliyunmaven` 镜像**（直连 `repo.maven.apache.org` 被出口阻断）——新依赖**必须先验证可解析**（D2）。
- **`git push` 在本机不可能成功**（hosts 把 `github.com` 指向 127.0.0.1，真实 IP 也被出口阻断；只有 `gh` 的 REST API 通）。**不要尝试 push**，只做本地提交；控制器在里程碑末尾用 GitHub Git Data API 逐提交重放，并**逐路径核对远端树**。
- **本地 `origin/master` ref 是过期的**（推送走 API，`git fetch` 不通）：判断远端一律用 `gh api repos/7641751/aihub-platform/git/ref/heads/master`，不要相信 `git status` 的 ahead/behind。
- 基线（`30e1ce1` 实测）：`aihub-common 56`、`aihub-web 95`、`aihub-gateway 350` = **501 项通过 / 0 失败 / 0 错误 / 0 跳过**。每个任务报告**实测**数字，不要照抄本计划的估算。

---

## 决策登记（设计文档沉默处或需要取舍的地方，实施者按此执行、**不要自由发挥**；评审者按此判断越权）

| # | 决策 | 理由 | 影响面 / 证据 |
|---|---|---|---|
| 1 | **本里程碑获准新增唯一一条 Flyway 迁移 `V2__m4_console.sql`**，内容严格限定为三件事：① 新建 `audit_log` 表；② 给 `request_log` 加 `channel_id`、`api_key_id` 两个索引；③ 新建 `config_version` 单行表。`SchemaMigrationTest` 的断言从「恰好 1 条」改成「恰好 2 条，且 version 与 description 逐条与预期一致」，**用例名一并改成 `flywayAppliesExactlyTwoMigrations`**（原名与改后的断言相反）。 | M3 禁止新增迁移的理由是「保护 `flywayAppliesExactlyOneMigration` 这条护栏」，而那条护栏要保的是「**没人能悄悄加迁移**」这件事，不是「永远只有 1 条」。M4 的业务确实需要新表（审计没有表就只剩日志）与新索引（已登记：`request_log.channel_id`/`api_key_id` 无索引，按渠道/按 Key 聚合会全表扫描）。把断言改成「恰好 2 条 + 显式文件名」**保留了护栏的全部语义**，并且**有意**地让改动显式可见（而不是绕过）。**残余（诚实登记）**：分区表上加二级索引在 MySQL 上要重建索引且不能在线加分区；生产环境需评估 DDL 窗口，本机演示数据量下无影响。 | 新增 `aihub-admin/aihub-dao/src/main/resources/db/migration/V2__m4_console.sql`；`SchemaMigrationTest` 改断言；`docs/CONVENTIONS.md` 第 7 节补「迁移数量由测试显式钉住」的说明。 |
| 2 | **生产依赖只加 `org.springframework.security:spring-security-crypto`**（版本走 Boot BOM）。**先验证可解析**（走 `aliyunmaven`）再动代码；解析不下来就**报 BLOCKED**，**绝不**改为自己实现 bcrypt。**不引** `spring-boot-starter-security`，**不引**任何 JWT 库。 | 口令哈希是**唯一**不能自己实现的部分（`sys_user.password_hash` 是 `VARCHAR(72)`，恰好容纳 bcrypt 的 60 字符；PBKDF2 的自描述串（含 salt 与 iterations）会长于 72 字符而放不进该列，而设计文档 §5.1 明写「口令 bcrypt」）。反过来，**鉴权链路**（过滤器顺序、401 信封、`/internal/**` 的 HMAC）在本仓库已经有自己的一套且被测试钉住，引入 `spring-boot-starter-security` 会带来第二条过滤器链与全局 auto-config，收益低风险高。JWT 库同理：本控制台是**单签发方、单算法、无 JWKS、无 refresh** 的场景，自签 HS256 用 JDK 就能做完整（见 D3），换来「零新依赖」。 | `aihub-admin/aihub-service/pom.xml` +1；`aihub-admin/aihub-common/pom.xml` **不动**（令牌只用 JDK 类型）。Task 4 的第一步是**可解析性验证**，失败即 BLOCKED。**可解析性已在计划编写阶段实测（2026-09-27）**：Boot `3.5.16` 的 BOM 把 `spring-security.version` 钉在 **`6.5.11`**；`mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=org.springframework.security:spring-security-crypto:6.5.11` → `Downloaded from aliyunmaven: … spring-security-crypto-6.5.11.jar (105 kB)` + `BUILD SUCCESS`，jar/pom 已落进 `.m2repo`。**注意**：本机 `curl` 到 `maven.aliyun.com` 是 `000`（走的是 Maven 的镜像/代理配置），所以**只能用 Maven 自己验证**，不要用 `curl -I` 判断「依赖能不能拉到」。 |
| 3 | **控制台令牌 = 自研 HS256（JWT 形状，不是 JWT 标准实现）**：`base64url(header).base64url(payload).base64url(HMAC-SHA256(前两段))`；header 固定为 `{"alg":"HS256","typ":"JWT"}` 且**校验方完全忽略请求里的 header**（算法由服务端写死 → 不存在 `alg` 混淆面）；claims 只认 `sub`（`sys_user.id`）、`tenantId`、`role`、`iat`、`exp`（**≤ 2 小时，默认 2 小时**）；签名比较用**恒定时间**；密钥来自 `aihub.console.secret`（环境变量 `AIHUB_CONSOLE_SECRET`，**为空则登录接口直接失败**，不生成默认值）。**不做**：RS256 / JWKS / refresh token / 注销黑名单 / 多签发方。 | 设计文档 §7.3 写的是「签发 JWT」，而本场景是「一个 admin 签发、一个 admin 校验、算法固定、生命周期短」，用标准库要引三个新依赖（jjwt-api/impl/jackson）并为不存在的需求买单。自研的**风险必须显式写出来**：它只支持 HS256、不做密钥轮换、没有 `aud`/`iss` 校验 —— 一旦将来出现第二签发方或需要吊销，**必须换成库**（这条要写在类的 javadoc 里）。恒定时间比较与「忽略请求 alg」是防伪必需项，各有一条用例。 | 新增 `aihub-admin/aihub-service/src/main/java/com/aihub/service/console/{ConsoleToken,ConsoleClaims}.java` + 固定向量与篡改用例（**放在 `aihub-service` 而不是零依赖的 `aihub-common`**：只有 admin 用它，而 `aihub-common` 没有 Jackson，手写 JSON 解析只会给令牌引入无谓的脆弱点）；`aihub-web` 的 `ConsoleAuthFilter` 与 `ConsoleTokenService` 用它。 |
| 4 | **配置失效走 Redis Pub/Sub（设计 §6.3 明文）**：频道名 `aihub:config:invalidate`，载荷 `{version}\|{reason}`（分隔符文本，与 `MeteringEventCodec` 同风格，编解码在 `aihub-common`）。admin 在**每一次**成功的配置写事务提交后发布一次；gateway 订阅后执行 `ConfigClient.invalidate()`。**`invalidate(long version)` 必须同时做三件事**：清本地 Caffeine、**删除共享条目 `aihub:config:snapshot`**、**把 `ConfigCache` 的写入水位抬到失效消息里的 `version`** —— **绝不重置成 `NO_VERSION`**（重置会拆掉「挡住在飞的旧回填把刚删掉的陈旧条目写回去」的唯一护栏，等于让这次失效白做；见 E.2-I9）。 | 这正是 M3 决策 16 推迟到 M4 的那一半，也是 M3 已登记缺口的修复：`ConfigClient.invalidate()` 当前**只清本地**，紧接着 `resolve()` 又会读到 Redis 里同样陈旧的共享条目并采用它 —— 于是「配置变了」这个信号对多实例部署**完全无效**，而 `docs/CONVENTIONS.md` §6.6 记录的 10 分钟上界就是这么来的。设计文档 §6.3 原话是「admin 变更配置后通过 Redis Pub/Sub 广播失效消息，各 gateway 实例清理本地缓存；本地 TTL 30 秒作为兜底」，本决策把它落地。**M4 的验收标准（控制面配置 → 数据面生效全链路打通）依赖这一条**。 | 新增 `aihub-common/.../config/ConfigInvalidateTopology.java` + `ConfigInvalidateCodec.java`；`aihub-gateway/.../config/{ConfigSubscriber,ConfigInvalidateSubscriptionConfig,ConfigInvalidateProperties}.java`（**`RedisMessageListenerContainer` 的装配放在专用的 `ConfigInvalidateSubscriptionConfig` 里，不是 `ConfigConfig`** —— 见 N3 的处置）；admin 侧 `ConfigChangePublisher`。Channel 名与载荷格式是**跨服务契约**，两端共用同一常量（与 `MeteringTopology` 同一纪律）。 |
| 5 | **快照 `version` 改成显式单调计数**：新增单行表 `config_version(id=1, version BIGINT, updated_at)`；`ConfigSnapshotService.currentVersion()` 返回 `max(数据库侧的 max(updated_at), config_version.version)`；**水位只在配置写入路径上抬升**（`ConfigChangePublisher.bumpAndPublish`，见 Task 2/8/9/10，用「写入时刻的 epoch 毫秒」）。**读路径必须是纯读**：`snapshot()` 是 `@Transactional(readOnly = true)`，而 MySQL + Connector/J 的 `readOnlyPropagatesToServer` 默认是**开**的（`SET SESSION TRANSACTION READ ONLY`），在里面写库会直接 `ERROR 1792` —— 也就是把 `GET /internal/config/snapshot`（里程碑依赖的那条路）打坏。 | 已登记的缺口：`version = max(updated_at)` 会**倒退**（删掉最新那一行），而网关的比对是严格 `>`，于是更旧的快照会被 lastGood 记住并继续服务；`ConfigSnapshotService` 既不检测也不告警。M3 时「没有写入方」所以是潜在缺口，**M4 有了 CRUD 就必须修**，否则删除一条路由会让数据面永远停在旧快照上。为什么还要保留 `max(updated_at)` 这一半：**手工 SQL / seeder 写入仍然会推进 `updated_at`**。为什么不在读路径抬升：见左列的 `ERROR 1792`（评审实测的驱动语义）。**残余（诚实登记）**：**手工 SQL / raw SQL 的删除仍然可能让版本回退一次**（不经过控制台就不会抬水位）—— M4 的验收（Task 17 第 3 步）因此**必须走控制台/API** 去改配置，不能用 raw SQL；这同时意味着 `config_version` 的水位在纯 SQL 运维路径上是空的。 | `config_version` 表在 V2 里建；`ConfigSnapshotService` 的版本计算改动（**纯读**）+ `ConfigChangePublisher` 里的抬升；真 MySQL 用例（改一行 → 版本严格变大；**删掉最新那行 → 版本不回退**，前提是该行是本进程用 `raiseTo` 写过的）；`ConfigSnapshotServiceTest` 的两条既有断言（空库 version=0）改成**显式 reset `config_version` 后**再断言，理由写在测试里（Testcontainers 是 JVM 级共享的，水位会跨用例存活）。 |
| 6 | **配额超限的数据面错误码用 OpenAI 的 `insufficient_quota`**（`429`，`type="insufficient_quota"`），**取代**设计文档 §6.2 里 `429 QUOTA_EXCEEDED` 的简写。 | 与 M3 决策 13 同一理由：数据面客户端是各种 OpenAI SDK，配额耗尽的标准形状就是 `429` + `error.code="insufficient_quota"`（`error.type` 同名）。用私有码 `QUOTA_EXCEEDED` 会让 SDK 的配额分支失效。**必须与限流严格区分**（CONVENTIONS §6.6 已警告）：`rate_limit_exceeded` 是「每秒发太快」（暂时、下个窗口自动恢复），`insufficient_quota` 是「这个周期的余额用完了」（持久、要充值或等新周期）。 | `docs/CONVENTIONS.md` §4 错误表新增一行，并写明「设计文档 §6.2 的 `QUOTA_EXCEEDED` 按已被取代处理」；`GatewayErrors` 不新增重载。 |
| 7 | **配额在 Redis 不可用时降级为「放行 + 告警」**（fail-open），与限流的「降级仍拒绝」**刻意相反**。 | 设计文档 §9 明文：「Redis 不可用 → 限流降级为本地令牌桶（单机近似），**配额降级为放行 + 告警**，不阻断服务」。理由：限流是保护上游的（宁可错杀），配额是记账的（宁可少记也不能因为记账组件坏了而拒绝付费客户）。这条对照是本项目**面试时最值得讲的一处取舍**，必须写进 CONVENTIONS 与 README。降级时打 `aihub.quota.degraded` 计数器 + 限流过的 WARN；**Redis 侧的预扣丢失由每日对账兜底**（对账按 `request_log` 重算 `billing_daily`）。 | `QuotaFilter` 的降级分支 + 用例 `redisDownAllowsTheRequestAndCountsADegrade`；CONVENTIONS §6.7 新增配额小节。 |
| 8 | **审计在服务层显式写**（`AuditService.record(...)`），**不用 AOP/拦截器**。审计行写在与业务写**同一个事务**里。 | AOP 看着优雅，但「谁做的、对哪个对象、改了哪些字段」这三件事只有服务层知道；用切面去猜注解等于把审计的准确性交给约定。同事务保证「改了但没审计」不可能发生（审计写失败 → 业务回滚）。**审计失败必须让业务失败**（审计是合规要求，不是尽力而为）。 | 新增 `AuditService` + `audit_log` 表（D1）；每个写接口的用例都要断言「产生了一条审计行，且**不含**敏感字段」。 |
| 9 | **管理台是零构建的静态单页**：`aihub-web/src/main/resources/static/console/{index.html,console.js,console.css}`，vanilla JS + `fetch`，令牌放 **`sessionStorage`**（关闭标签页即失效）。**不引**前端框架、不引构建步骤、**不用 cookie**。 | 「极简管理台」的交付物是「能登录、能看渠道/Key/日志、能新建渠道与 Key」。引一个 Vite+React 工程会让本里程碑的构建面翻倍，而收益是零（面试讲的是后端链路）。不用 cookie 是为了**不引入 CSRF 面**（同源 + `Authorization` 头 + `sessionStorage` 的组合下，跨站请求带不上令牌）。代价是 XSS 会拿到令牌 —— 而本页面不加载任何第三方脚本、不渲染用户输入的 HTML，这个代价被登记为**已知边界**而不是假装不存在。 | 静态资源在 `aihub-web` 的 classpath 里；一个 `ConsoleStaticResourceTest` 断言三个文件可被 `GET /console/` 取到且 `index.html` 不含内联脚本。 |
| 10 | **`/api/**` 的授权只分两级**：`ADMIN` 可读写，`VIEWER` 只读（只读方法为 `GET`/`HEAD`/`OPTIONS`，其余方法一律 403 + admin 信封）。`sys_user.role` 是唯一来源。 | `sys_user` 表已经有 `role`，但设计文档没有定义角色集合。**「只读」的口径含 `OPTIONS`**：无副作用，且浏览器 CORS 预检会发它；本条原先只写 GET/HEAD，与 Task 6 正文里的代码片段 `READ_METHODS = Set.of("GET","HEAD","OPTIONS")` 冲突（Task 6 执行期发现），现按代码统一，并补一条 `VIEWER` + `OPTIONS` 的用例把行为钉住。两级是「够用且可验证」的最小集合；细粒度 RBAC 属后续迭代（已登记在 §14 非目标）。**403 必须走 admin 信封**（`{"code":"FORBIDDEN",...}`），不要 Spring 默认错误页。 | `ConsoleAuthFilter` 之后的 `ConsoleRoleFilter`（或同一个过滤器里的两级判定）+ 用例（`VIEWER` GET 200 / `OPTIONS` 非 403 / `POST` 403）。 |
| 11 | **API Key 吊销/停用必须显式 `DEL` 缓存**：控制台改 `api_key.status` 后，除发布配置失效消息外，还要 `DEL aihub:apikey:<key_hash>`（键前缀用 `ApiKeyCacheCodec.CACHE_KEY_PREFIX` 常量）。**网关侧的本地 Caffeine 仍要等 ≤30 秒**（除非该 key 的请求恰好触发本地过期）。 | M3 决策 16 把「真正的收敛手段是 M4 的吊销接口 + 显式 `DEL`」写在这里，本里程碑兑现它。`DEL` 只能清掉**共享**那一层：网关本地 Caffeine 的 30 秒窗口**没有**便宜的失效通道（给每个 key 建 Pub/Sub 主题的成本远大于收益），因此**残余必须写清楚**：吊销后最坏 30 秒内本实例仍可能放行。吊销的运维动作建议是「先停用、观察、再删」。 | `ApiKeyAdminService` + 用例：「停用后共享缓存条目被删除」；CONVENTIONS §6.6 的 ≤30s/≤5m 表述改为「控制台吊销后共享层立即失效，本地层 ≤30s」。 |
| 12 | **对账任务只报告、不自动改账**：每日 02:00（`aihub.quota.reconcile-cron`，与 M3 的分区维护 03:10 **错开**）按 `request_log` 重算当日 `billing_daily`（**幂等 UPSERT**，`uk_billing_daily(tenant_id, stat_date)`），并与 Redis 的预扣计数比对；偏差超过阈值（`aihub.quota.reconcile-tolerance-ratio`，默认 1%）→ WARN + `aihub.quota.reconcile.mismatch` 计数器 + 一条审计行，**但不修改 `quota.token_used`**。 | 设计文档 §6.2 要求「与 Redis 计数比对、修正并告警偏差」。**修正**这一步在这里被降级为「报告」，理由是：对账任务自动改账会在「计量事件因为 DLQ 延迟到达」时把**正确**的账改**错**（计量是至少一次 + 幂等，`request_log` 在 02:00 时可能还没补全）。先报告、人工确认、再执行修正是更安全的顺序，且符合本里程碑「可演示」的验收口径。**残余**：偏差的自动收敛推迟（登记在 README 已知边界）。 | 新增 `QuotaReconciliationJob`（`@Scheduled(cron=...)`）+ 用例（用真 MySQL 造 3 天的 `request_log` → 断言 `billing_daily` 被正确重算 + 偏差被计数）。 |
| 13 | **配额的时间粒度 `quota.period` = `YYYYMM`（UTC）**，Redis 键 `aihub:quota:{tenantId}:{period}`，`PEXPIRE` 设为「到下个周期开始 + 1 天」的毫秒数。 | `quota` 表唯一的唯一键是 `uk_quota_tenant_period(tenant_id, period)`，`period` 是 `VARCHAR(8)` —— `YYYYMM` 正好 6 字符且按字典序可比较（可用于 `period = ?` 精确查、也能做前缀范围查）。TTL 取「周期末 + 1 天」而不是固定值，是为了让跨月的边界请求不会读到一个已经过期又被重建的空桶（+1 天把「月末最后一个请求」与「对账任务的补扣」都罩住）。 | `QuotaKeys`（`aihub-common`）有键布局与 TTL 的固定向量测试；D2 的 Lua 用 `ARGV` 传入 ttlMillis（沿用 M3 决策 9：不用 Redis `TIME`）。 |
| 14 | **`channel.models_json` 的语义定为「该渠道支持的模型清单，仅供展示与运维参考」**；**路由的唯一真相仍是 `model_route`**。控制台的渠道表单里它是可选文本，写入前做 JSON 合法性校验。 | M3 已知边界里登记过这条歧义（「两者同时存在时以哪个为准 spec 没说」），并说「M4 的控制台一并定」。定成「展示用」的理由：`model_route` 已经表达了「这个模型可以走哪些渠道」，反向的「这条渠道支持哪些模型」对路由**没有**增量信息（一个模型即使出现在渠道的 `models_json` 里，没有对应的 `model_route` 行也不会被路由到），把它接进路由只会制造第二个真相源。 | README 已知边界对应条目改为「已定：展示用」；`ChannelAdminService` 校验 JSON 合法性 + 用例。 |
| 15 | **配额「不限」的表示是「没有行」或 `token_limit = 0`（`request_limit = 0` 同理）**：任一维度为 `0` 即该维度不判定。两个维度都非 0 才逐维判定。 | `quota` 表的 DDL 默认值就是 `0`（`token_limit BIGINT NOT NULL DEFAULT 0`），而 M4 上线前**所有既有租户都没有配额行**。若把 `0` 解读为「额度为零」，M4 上线的瞬间每一个既有租户都会开始吃 `429 insufficient_quota` —— 一个纯粹由新功能引入的全量停服。把 `0` 定为「不限」让升级是**惰性**的：不配额度 = 行为与 M3 完全一致，只有显式配了正数才启用配额。代价是「想表达额度为零」需要一个非零的替代（用 `1` 并把已用量记满，或直接停用该 key），已登记。 | `QuotaResolver` 的判定 + 用例 `absentQuotaRowAllowsEverything`、`zeroLimitMeansUnlimitedNotBlocked`、`positiveLimitIsEnforced`。README 写明这条语义。 |
| 16 | **`aihub.console.secret` 为空时 `/api/**` 一律 `401`（fail-closed）**：`ConsoleAuthFilter` **不**放行任何请求，登录接口返回 `500`+`CONFIGURATION_ERROR`（明确告诉运维是配置问题，不是口令错），启动时打一条 WARN。 | 与既有的两处同构纪律一致：`aihub.internal.secret` 为空时 `InternalHmac.verify` 恒返回 `false`（fail-closed）、`InternalAuthFilter` 一律 401；`aihub.channel.master-key` 为空时 admin 是写入路径直接抛异常、gateway 是请求路径返回空。**绝不生成默认密钥**（默认密钥 = 所有人都能签令牌 = 管理台等于没有鉴权）。为什么登录接口回 500 而不是 401：401 会让运维以为「用户名或口令错了」并去翻口令，而真正的故障是缺配置 —— 这一条与本项目对 D1 的裁决同源（**不要把平台故障伪装成凭证错误**）。 | `ConsoleAuthFilter` + `ConsoleAuthService` 各一条用例；`.env.example` 给出生成命令。 |
| 17 | **配额预扣必须读到请求体，因此在 `QuotaFilter` 里自己 join + `ServerHttpRequestDecorator` 缓存请求体**（⚠️ Spring 6.2 **没有** `ServerWebExchangeUtils.cacheRequestBody*`：评审已在 `spring-web`/`spring-webflux` **6.2.19 的 jar** 里核实，连 `web/server/support` 包都不存在。正确写法见 Task 13 的片段：join → 复制进 `byte[]` → **先复制再释放** → `exchange.mutate().request(装饰器).build()` → 把**装饰过的** exchange 传下去），并**必须证明字节透传未被破坏**：上游收到的请求体与客户端发的**逐字节相同**。 | `QuotaFilter` 需要 `max_tokens` 与 prompt 文本来估算（复用 M2 已有的 `TokenEstimator`：1 个汉字 ≈ 0.6 token、1 个非汉字 ≈ 0.3、向上取整），而 WebFlux 的请求体是**一次性**流：谁先订阅谁拿走。要么把预扣搬进已经读了 body 的 `ChatRelayController`（破坏「鉴权→限流→配额→路由」的文档顺序），要么在过滤器里缓存。选缓存，并把 M1 的字节级透传铁律作为**该任务的验收判据**（`AtomicReference` 记下上游收到的原始字节，与客户端发的比较 `isEqualTo`）。 | `QuotaFilter` + 用例 `cachedBodyStillReachesTheUpstreamByteForByte`；`QuotaEstimator` 复用 `com.aihub.gateway.meter.TokenEstimator`，**不新写一份估算器**。 |

### 本里程碑**不做**的事（写进文档，避免范围蔓延）

- **`/v1/embeddings`、文档上传/解析/嵌入/向量库、`kb_document` 状态机** —— M5（`kb_document` 表已在 V1，本里程碑不碰）。
- **计费单价与成本**：`billing_daily.cost` 本里程碑**只写 0**（没有单价表），按 token 出账属后续迭代。
- **压测报告、故障注入报告、`/actuator/metrics` 暴露** —— M6（网关仍然只暴露 `health,info`）。
- **Redis `requirepass`、TLS、网络隔离** 的生产加固 —— 仍未做（README 继续披露）。
- **RS256/JWKS/refresh token/注销黑名单/多签发方** —— 见 D3，明确不做。
- **细粒度 RBAC、租户自助注册、第三方登录、邮件通知** —— 见 §14 非目标。
- **对账偏差的自动修正** —— 见 D12，只报告。
- **主动探活之外的健康度体系**（如按渠道的滑动窗口成功率）—— 只有 `POST /api/channels/{id}/probe` 的一次性探测。
- **网关侧对配额的「本地近似降级」**：Redis 不可用时**放行**（D7），不建本地配额桶 —— 记账数据必须收敛到一个真相源，本地近似只会制造更难对账的偏差。

---

## File Structure

M4 新增/修改的文件（`改` = 修改既有文件；**依赖变更恰好两处**，都在 `aihub-service`、都由 Boot BOM 管版本：`micrometer-core`（Task 2 裁决）与 `spring-security-crypto`（Task 5）；**迁移只有一条**：`V2__m4_console.sql`）：

### aihub-common（main 作用域**仍然零第三方**）

| 文件 | 职责 |
|---|---|
| `.../common/config/ConfigInvalidateTopology.java` | 新增：Pub/Sub 频道名常量（**跨服务契约的唯一真相**） |
| `.../common/config/ConfigInvalidateCodec.java` | 新增：失效消息的分隔符编解码（`version\|reason`），含固定向量 |
| `.../common/config/ConfigInvalidateMessage.java` | 新增：失效消息 record（version / reason） |
| `.../common/quota/QuotaScript.java` | 新增：预扣 Lua 脚本 + `RETURN` 语义的唯一真相（gateway 跑它，admin 集成测试验它） |
| `.../common/quota/QuotaDecision.java` | 新增：预扣判定的纯数据（`allowed` / `remainingTokens` / `remainingRequests`，`-1` = 该维度不限） |
| `.../common/quota/QuotaKeys.java` | 新增：Redis 键布局 `aihub:quota:{tenantId}:{period}` 与 TTL 公式 |
| `.../common/quota/QuotaPeriod.java` | 新增：`period` 的 `YYYYMM` 折算（UTC）与「下个周期开始」的毫秒数 |

### aihub-dao

| 文件 | 职责 |
|---|---|
| `.../db/migration/V2__m4_console.sql` | 新增：`audit_log` 表、`request_log` 两个索引、`config_version` 单行表（D1） |
| `.../entity/AuditLogEntity.java` / `.../entity/ConfigVersionEntity.java` | 新增实体 |
| `.../entity/SysUserEntity.java` | 新增实体（`sys_user` 已存在于 V1，M4 首次使用） |
| `.../entity/QuotaEntity.java` / `.../entity/BillingDailyEntity.java` | 新增实体（两表已存在于 V1，M4 首次使用） |
| `.../mapper/*Mapper.java` | 新增：AuditLog / ConfigVersion / SysUser / Quota / BillingDaily 五个 Mapper |

### aihub-service

| 文件 | 职责 |
|---|---|
| `.../service/console/ConsoleAuthService.java` | 新增：用户名+口令校验（bcrypt）、越权/停用判定、审计 |
| `.../service/console/ConsoleToken.java` | 新增：**JWT 形状的 HS256 令牌**（`base64url(header).base64url(payload).base64url(HMAC-SHA256)`，载荷用 Jackson 读写，D3）。放在 `aihub-service` 而不是零依赖的 `aihub-common`：**只有 admin 用它**（登录签发 + 过滤器校验），而 `aihub-common` 没有 Jackson，手写 JSON 解析只会给令牌引入无谓的脆弱点 |
| `.../service/console/ConsoleClaims.java` | 新增：令牌载荷 record（userId / tenantId / role / issuedAt / expiresAt） |
| `.../service/console/ConsoleTokenService.java` | 新增：签发/校验令牌的薄封装（读 `aihub.console.*` 配置） |
| `.../service/audit/AuditService.java` | 新增：写 `audit_log`（与业务同事务，D8） |
| `.../service/audit/AuditAction.java` | 新增：动作常量（`TENANT_CREATE` / `API_KEY_DISABLE` / `CHANNEL_ROTATE_KEY` / `LOGIN_FAILURE` / `RECONCILE_REPORT` …）—— Task 7 创建（F7c：这张表此前漏了它） |
| `.../service/tenant/TenantAdminService.java` | 新增：租户 CRUD |
| `.../service/apikey/ApiKeyAdminService.java` | 新增：铸造（复用 `ApiKeyHasher`）、列表、吊销/停用 + 显式 `DEL`（D11） |
| `.../service/channel/ChannelAdminService.java` | 新增：渠道 CRUD（写入时 AES-GCM 加密、轮换重加密） |
| `.../service/route/ModelRouteAdminService.java` | 新增：`model_route` CRUD |
| `.../service/ratelimit/RateLimitPolicyAdminService.java` | 新增：限流策略 CRUD（写入时先停用同维度旧行） |
| `.../service/config/ConfigChangePublisher.java` | 新增：配置写成功后「抬水位（D5）+ 发布失效消息（D4）」 |
| `.../service/config/ChannelProbeService.java` | 新增：`POST /api/channels/{id}/probe` 的真实上游探测 |
| `.../service/log/RequestLogQueryService.java` | 新增：`/api/logs` 的**分区裁剪友好**分页查询（**必须**带 `tenant_id` + 时间范围；索引在 V2，见 D1；**不设**无界查询） |
| `.../service/log/AuditQueryService.java` | 新增：`/api/audit` 的分页查询（审计是 M4 交付物之一，**只有写入路径不算交付** —— 见 I10③） |
| `.../service/quota/QuotaAdminService.java` | 新增：额度 CRUD（`quota` 表）+ 周期折算（D13） |
| `.../service/quota/QuotaReconciliationService.java` | 新增：按 `request_log` 重算 `billing_daily` + 与 Redis 比对 + 告警（D12） |
| `.../service/quota/QuotaReconciliationJob.java` | 新增：`@Scheduled` 入口（02:00） |
| `.../service/metering/MeteringSchedulingConfig.java` | **改**：注册对账的 cron（与分区维护错开） |

### aihub-web

| 文件 | 职责 |
|---|---|
| `.../web/console/ConsoleAuthController.java` | 新增：`POST /api/auth/login` |
| `.../web/console/TenantController.java` | 新增：`/api/tenants` |
| `.../web/console/ApiKeyController.java` | 新增：`/api/api-keys`（创建响应含一次性明文） |
| `.../web/console/ChannelController.java` | 新增：`/api/channels` + `/api/channels/{id}/probe` |
| `.../web/console/ModelRouteController.java` | 新增：`/api/routes` |
| `.../web/console/RateLimitPolicyController.java` | 新增：`/api/rate-limits` |
| `.../web/console/QuotaController.java` | 新增：`/api/quotas` |
| `.../web/console/LogQueryController.java` | 新增：`/api/logs` |
| `.../web/console/AuditController.java` | 新增：`/api/audit`（只读；强制 tenant + 时间范围，分页有上界） |
| `.../web/console/BillingController.java` | 新增：`/api/billing/daily` |
| `.../web/console/ConsoleAuthFilter.java` | 新增：守 `/api/**`（令牌 + 两级角色，D3/D10） |
| `.../web/config/ConsoleProperties.java` | 新增：`aihub.console.*`（secret / TTL / 是否启用） |
| `.../web/internal/InternalQuotaController.java` | 新增：`POST /internal/quota/reserve`（HMAC 内部接口） |
| `.../resources/static/console/{index.html,console.js,console.css}` | 新增：极简管理台（D9） |
| `.../resources/application.yml` | **改**：`aihub.console.*`、`aihub.quota.*` |

### aihub-gateway

| 文件 | 职责 |
|---|---|
| `.../config/ConfigSubscriber.java` | 新增：订阅失效频道 → `ConfigClient.invalidate(decoded.version())`（D4） |
| `.../config/ConfigClient.java` | **改**：`invalidate(long)` 清本地 + 删共享条目 + **把水位抬到该版本**（D4 的修复点；**不是**重置） |
| `.../config/ConfigCache.java` | **改**：暴露「删除共享条目 + 把 `observedRedisVersion` **抬到失效消息里的版本**」的方法（**不是**重置） |
| `.../config/ConfigInvalidateSubscriptionConfig.java` | 新增：`RedisMessageListenerContainer` 的装配（`@ConditionalOnProperty(aihub.config.invalidate-subscription)`）；**必须带 `@EnableConfigurationProperties(ConfigInvalidateProperties.class)`**，否则容器拿不到那个 bean |
| `.../config/ConfigInvalidateProperties.java` | 新增：`aihub.config.invalidate-subscription`（默认 `true`）。**独立于 `GatewayConfigProperties`** —— 往那个 record 加分量会打断 8 处 `new` 调用点（E.1-C2） |
| `.../quota/QuotaFilter.java` | 新增：鉴权→限流→**配额**→路由 的过滤器（`@Order(HIGHEST_PRECEDENCE + 175)`） |
| `.../quota/QuotaResolver.java` | 新增：按 **`tenant_id + period`** 选额度行（`quota` 表**没有** `api_key_id` 列，`uk_quota_tenant_period` 就是它的唯一键 —— 配额是租户级的月度预算，**不是**按 key 的，别把它和限流的两维搞混） |
| `.../quota/QuotaLimiter.java` / `RedisQuotaLimiter.java` / `QuotaEstimator.java` | 新增：Lua 预扣、降级为放行、`估算 prompt + max_tokens`（D7） |
| `.../quota/QuotaCorrector.java` | 新增：拿到真实 `usage` 后的**校正钩子**（`ChatRelayController` 在 `doFinally` 里把 `MeteringEvent` 直接喂给它，见 Task 13） |
| `.../quota/QuotaConfigProperties.java` / `QuotaConfig.java` | 新增：`aihub.quota.*` 配置与装配（`QuotaFilter` / `QuotaResolver` / limiter / corrector 的接线都在这里） |
| `.../relay/ChatRelayController.java` | **改**：`doFinally` 里在 `publish` 之后调用 `quotaCorrector.correct(event)`（**它不是"拿到 usage 后校正"那么简单**：controller 看不到 `usage`，见 Task 13 的说明） |

### 文档

| 文件 | 职责 |
|---|---|
| `docs/CONVENTIONS.md` | **改**：§4 错误表 +`insufficient_quota` 行；§5/§6 追加控制台与配额小节；§7 迁移纪律 |
| `README.md` | **改**：M4 做了什么、管理台怎么用、已知边界（配额降级放行、吊销 ≤30s、对账只报告、cost=0、`models_json` 已定） |
| `.env.example` | **改**：`AIHUB_CONSOLE_SECRET`（生成方法给两种 PowerShell 写法 + openssl） |
| `docs/superpowers/specs/...design.md` | **改**：§6.2 的 `QUOTA_EXCEEDED` 标注已被 `insufficient_quota` 取代；§7.3 增补已实现/未实现的标注 |

---

## 任务索引

| # | 任务 | 依赖 | 交付物一句话 |
|---|---|---|---|
| 1 | V2 迁移 + 迁移纪律 | — | `audit_log` / 两个索引 / `config_version` 落地，迁移断言显式化 |
| 2 | 配置失效契约 + admin 发布方 | 1 | `ConfigInvalidateTopology/Codec` + 写后广播 |
| 3 | 版本单调性（水位） | 1 | `config_version` **只在配置写入路径抬升**（读路径**纯读** —— `snapshot()` 是只读事务，写库会 `ERROR 1792`），删行不再回退 |
| 4 | 网关订阅方 + `invalidate(long)` 清本地/共享并**抬水位** | 2, 3 | 配置变更秒级到达数据面（**验收前置**） |
| 5 | bcrypt 依赖 + 控制台令牌 | — | 可解析性验证 + HS256 令牌 + 固定向量 |
| 6 | 登录 + `/api/**` 鉴权过滤器 + 角色 | 5 | `POST /api/auth/login`、401/403 信封 |
| 7 | 审计服务 | 1, 6 | 与业务同事务的 `audit_log` 写入 |
| 8 | 租户 + 渠道 CRUD（含加密写入与轮换） | 6, 7 | 渠道可建可改，密钥加密落库 |
| 9 | API Key CRUD + 吊销显式 DEL | 6, 7, 8 | 明文仅一次返回；停用即失效共享层 |
| 10 | 模型路由 + 限流策略 CRUD | 6, 7 | 两个真相源的写入接口 |
| 11 | 渠道探测 + 请求日志/账单/审计查询接口 | 6, 1 | `probe` 与 `/api/logs`、`/api/billing/daily`、`GET /api/audit` |
| 12 | 配额控制面 + Lua 预扣契约 | 1 | `quota` CRUD + `QuotaScript`/`QuotaKeys`/`QuotaPeriod` |
| 13 | 网关 `QuotaFilter` + 实际校正 | 12 | 429 `insufficient_quota`、降级放行、usage 校正 |
| 14 | `/internal/quota/reserve` 兜底 | 12, 13 | Redis 不可用时的预扣回源路径 |
| 15 | 每日对账任务 | 11, 12 | `billing_daily` 重算 + 偏差告警（只报告） |
| 16 | 极简管理台静态页 | 6, 8, 9, 10, 11 | 登录 + 渠道/Key/日志三个视图 |
| 17 | 文档收口 + M4 全栈验收 | 全部 | 「控制面配置 → 数据面生效」判据达成 |

---
## Task 1: V2 迁移与迁移纪律

**Files:**
- Create: `aihub-admin/aihub-dao/src/main/resources/db/migration/V2__m4_console.sql`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/AuditLogEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ConfigVersionEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/AuditLogMapper.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/ConfigVersionMapper.java`
- Modify: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/dao/SchemaMigrationTest.java`
- Test: 同上（`SchemaMigrationTest` 就是本任务的测试）

**Interfaces:**
- Consumes: `AbstractIntegrationTest`（Testcontainers 单例 MySQL/Redis/RabbitMQ）；MyBatis-Plus `BaseMapper<T>`
- Produces:
  - 表 `audit_log(id, tenant_id, actor_type, actor, action, target_type, target_id, detail, request_id, created_at)`
  - 表 `config_version(id, version, updated_at)`，**并且必须已有一行 `id=1`**
  - 索引 `request_log.idx_request_log_channel(channel_id, created_at)`、`request_log.idx_request_log_api_key(api_key_id, created_at)`
  - `AuditLogEntity`（字段与列同名，`@TableName("audit_log")`、`@TableId(type = IdType.AUTO)`）
  - `ConfigVersionEntity`（`id` / `version` / `updatedAt`）
  - `interface AuditLogMapper extends BaseMapper<AuditLogEntity>`
  - `interface ConfigVersionMapper extends BaseMapper<ConfigVersionEntity>` **外加两个注解方法**（Task 3 与 Task 5 直接调用，签名逐字如下）：
    ```java
    @Select("SELECT version FROM config_version WHERE id = 1")
    Long current();

    @Insert("INSERT INTO config_version (id, version) VALUES (1, #{version}) "
            + "ON DUPLICATE KEY UPDATE version = GREATEST(version, #{version})")
    int raiseTo(@Param("version") long version);
    ```

- [ ] **Step 1: 写迁移脚本与迁移断言（先写会红的断言）**

`V2__m4_console.sql`（**逐字**，只做 D1 允许的三件事；不要顺手加别的列或表）：

```sql
-- M4 控制面所需的**唯一**一条迁移（决策 D1）。
-- 1) 审计表：M4 之前没有审计承载物，写操作只有日志。
-- 2) request_log 的两个索引：按渠道 / 按 Key 聚合此前是全表扫描（M3 已登记）。
-- 3) config_version 单行表：快照 version 的水位（决策 D5，修「删掉最新一行版本会倒退」）。

CREATE TABLE audit_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT        NULL,
    actor_type  VARCHAR(16)   NOT NULL,
    actor       VARCHAR(64)   NOT NULL,
    action      VARCHAR(32)   NOT NULL,
    target_type VARCHAR(32)   NOT NULL,
    target_id   VARCHAR(64)   NULL,
    detail      VARCHAR(1024) NULL,
    request_id  VARCHAR(64)   NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_log_created (created_at),
    KEY idx_audit_log_tenant_created (tenant_id, created_at),
    KEY idx_audit_log_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 分区表上的二级索引：MySQL 会重建各分区的本地索引。列顺序把 created_at 放在第二，
-- 让「按渠道 + 时间范围」的查询能同时用到索引前缀与分区裁剪。
ALTER TABLE request_log ADD KEY idx_request_log_channel (channel_id, created_at);
ALTER TABLE request_log ADD KEY idx_request_log_api_key (api_key_id, created_at);

CREATE TABLE config_version (
    id         BIGINT      NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 必须预先插入这一行：raiseTo 的 upsert 能处理缺失，但 current() 读到 NULL 会让
-- 「水位」这个概念在第一次读时不存在，多一个分支不如一行数据。
INSERT INTO config_version (id, version) VALUES (1, 0);
```

`SchemaMigrationTest`：**先读这个类再改**（评审已核实它的真实形状）—— 它**不用 Flyway API**，而是用 `JdbcTemplate` 查 `flyway_schema_history`；它的 `allTenTablesExist` **断言的是恰好 10 张表**。因此本任务要改它**三处**，不是一处：

1. `flywayAppliesExactlyOneMigration` → **改名为 `flywayAppliesExactlyTwoMigrations`**，断言改成「恰好 2 条，且 version 与 description 都是预期的」（名字与断言必须一致：这是本任务「迁移纪律」交付物的一部分，一个叫 ExactlyOne 却断言 2 的用例名本身就是假话）。**用 JDBC 查历史表**，不要引入 `Flyway`/`MigrationInfo`/`MigrationVersion`（那个类今天没有这些装配，引进来就是额外的接线）：

```java
// D1：M4 有意引入第二条迁移（审计表 / request_log 索引 / config_version）。
// 断言语义从「恰好 1 条」升级为「恰好这 2 条」：护栏要保的是「没人能悄悄加迁移」，
// 而不是「永远只有 1 条」—— 现在任何人再加迁移都必须**显式**改这里。
List<Map<String, Object>> applied = jdbcTemplate.queryForList(
        "SELECT version, description FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
assertThat(applied).hasSize(2);
assertThat(applied).extracting(r -> String.valueOf(r.get("version"))).containsExactly("1", "2");
assertThat(applied).extracting(r -> String.valueOf(r.get("description")))
        .containsExactly("init schema", "m4 console");
```

> **不要**声称这里「钉住了校验和」：历史表里确实有 `checksum` 列，但本任务**只**钉 version + description（钉 checksum 会让任何一次无关的空白调整都变红，收益低于噪音）。

2. `allTenTablesExist` → 表清单 **10 张改成 12 张**（新增 `audit_log`、`config_version`），方法名一并改成 `allTwelveTablesExist`。**不改它，V2 一落地这条就红。**
3. 新增两条用例（见 Step 3）。

**预期用例数**：该类现有 **4** 条，加新增 2 条 = **6** 条（Step 2 的期望值以此为准）。

> ⚠️ **RED 的顺序（否则 Step 2 抓不到红）**：Step 1 里不要把脚本和断言一次写完。按这个次序做：
> ① **先只改 `SchemaMigrationTest`**（迁移断言 + 表清单），**暂不创建** `V2__m4_console.sql`，跑一次，
> 抓下 **`expected size: 2 but was: 1`**；② **然后**再创建 `V2__m4_console.sql`，跑一次，
> 抓下 **`allTwelveTablesExist` 的 `containsExactlyInAnyOrder` 红**（多了 `audit_log` 与 `config_version`）；
> ③ 最后补实体/Mapper 与新用例。两次红都是判别性的，而且顺序反了就没有红可抓（评审核实过这一点）。

- [ ] **Step 2: 跑它确认失败**

Run: `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=SchemaMigrationTest"`
Expected: FAIL —— 先是 `expected size: 2 but was: 1`（V2 还没被创建）；等 V2 加进去、表清单断言还没改时，再红在 `allTwelveTablesExist` 的 `containsExactlyInAnyOrder`（多了 `audit_log` 与 `config_version`）。**两条红都要抓下来**，它们分别是「迁移集合」与「表集合」的判别性证据。

- [ ] **Step 3: 落实体、Mapper 与新用例**

`AuditLogEntity` 的字段（列名与字段名一致，靠 MyBatis-Plus 的驼峰映射）：

```java
@TableName("audit_log")
public class AuditLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String actorType;   // "USER" | "SYSTEM"
    private String actor;       // sys_user.id 的字符串形式，或 "system"
    private String action;      // 见 AuditAction 常量（Task 7）
    private String targetType;  // "TENANT" | "API_KEY" | "CHANNEL" | "ROUTE" | "RATE_LIMIT" | "QUOTA"
    private String targetId;
    private String detail;      // 非敏感字段的变更摘要；**绝不放密钥/口令/密文**
    private String requestId;
    private Instant createdAt;
    // getter / setter 略（与本仓库既有实体同风格）
}
```

`ConfigVersionEntity`：`id`（`@TableId(type = IdType.INPUT)`，因为它是手工写死的 1）、`version`、`updatedAt`。

新增两条用例到 `SchemaMigrationTest`：

```java
@Test
void v2CreatesTheAuditTableAndTheTwoRequestLogIndexes() {
    // 审计表可写可读（顺带证明列名与实体映射一致）
    AuditLogEntity row = new AuditLogEntity();
    row.setActorType("SYSTEM"); row.setActor("system");
    row.setAction("MIGRATION_TEST"); row.setTargetType("CHANNEL");
    auditLogMapper.insert(row);
    assertThat(auditLogMapper.selectById(row.getId()).getAction()).isEqualTo("MIGRATION_TEST");

    // 两个索引真实存在（查 information_schema 而不是读 SQL 文件）
    assertThat(indexNamesOf("request_log"))
            .contains("idx_request_log_channel", "idx_request_log_api_key");
}

@Test
void configVersionRowExistsAndTheUpsertOnlyEverRaisesTheValue() {
    // V2 已经插入了 (id=1, version=0)，所以下面两次走的都是 **UPDATE** 路径。
    // **不要断言 affected rows**：MySQL 的 ON DUPLICATE KEY UPDATE 在「更新成相同值」时返回 0，
    // 而 Connector/J 默认 useAffectedRows=false（即设了 CLIENT_FOUND_ROWS），此时返回 1；
    // 换句话说同一个实现可能给出 0、1 或 2，断言返回值就是在猜驱动。断言**值**的语义。
    // ⚠️ 水位行是**共享容器里的一行**，而 Testcontainers 是 JVM 级单例、`AbstractIntegrationTest`
    // 没有全局清理：Task 2 的 ConfigChangePublisherTest 与 Task 3 都会把它抬到 ~1.76e12。
    // 所以这里**先显式归零**再断言绝对值，否则这条用例在 Task 17 的全量 `mvn clean test` 里
    // 会因为"谁先跑"而红（N11：与 ConfigSnapshotServiceTest 同一类隐患，那里已经加了 @BeforeEach）。
    jdbcTemplate.update("UPDATE config_version SET version = 0 WHERE id = 1");
    assertThat(configVersionMapper.current()).isZero();
    configVersionMapper.raiseTo(1_700_000_000_000L);
    assertThat(configVersionMapper.current()).isEqualTo(1_700_000_000_000L);
    configVersionMapper.raiseTo(1_600_000_000_000L);                 // 更小的值：不改
    assertThat(configVersionMapper.current()).isEqualTo(1_700_000_000_000L);
}
```

> `raiseTo` 的返回值**没有任何调用方依赖**（Task 2/3 都不看它），保留 `int` 只是为了将来能用它做观测；任何地方都不许把它当判据。

`indexNamesOf(String table)` 用一条 `SELECT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ?` 的 `JdbcTemplate` 查询实现（放在测试类里，不要进生产代码）。

- [ ] **Step 4: 跑它确认通过**

Run: `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=SchemaMigrationTest"`
Expected: `Tests run: 6, Failures: 0, Errors: 0` + `BUILD SUCCESS`（**不要看 `[exit code: N]`**）。另外跑一次全反应堆编译：`mvn -B -q test-compile -DskipTests`。

- [ ] **Step 5: 提交**

```bash
git add aihub-admin/aihub-dao/src/main/resources/db/migration/V2__m4_console.sql ^
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/AuditLogEntity.java ^
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ConfigVersionEntity.java ^
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/AuditLogMapper.java ^
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/ConfigVersionMapper.java ^
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/dao/SchemaMigrationTest.java
git commit -m "feat(dao): add V2 migration (audit_log, request_log indexes, config_version watermark)"
```
（上面是 PowerShell 的续行写法；在 bash 里用 `\`。**逐路径 add，禁止 `git add -A`。**）

**验收判据：** 真实 MySQL 上 V2 被应用，`audit_log` 可读写、两个索引在 `information_schema` 里可见、`config_version` 有 `id=1` 的行且 `raiseTo` 的 upsert 语义正确；迁移清单断言**显式**列出两条。
**RED 证据：** 未创建 V2 时 `SchemaMigrationTest` 报 `expected size: 2 but was: 1`；这条红是判别性的（它测的正是「迁移集合」本身），而 Step 3 之后任何新增迁移都会让它再红一次。

---

## Task 2: 配置失效契约 + admin 发布方

**Files:**
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigInvalidateTopology.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigInvalidateMessage.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigInvalidateCodec.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigChangePublisher.java`
- Test: `aihub-admin/aihub-common/src/test/java/com/aihub/common/config/ConfigInvalidateCodecTest.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/config/ConfigChangePublisherTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate`；`ConfigVersionMapper.raiseTo(long)`（Task 1）
- Produces:
  - `ConfigInvalidateTopology.CHANNEL = "aihub:config:invalidate"`（`public static final String`；**跨服务契约的唯一真相**，admin 与 gateway 共用）
  - `public record ConfigInvalidateMessage(long version, String reason)`
  - `ConfigInvalidateCodec.encode(ConfigInvalidateMessage) : String`、`ConfigInvalidateCodec.decode(String) : ConfigInvalidateMessage`（畸形返回 `null`，**不抛异常**）
  - `ConfigChangePublisher.publish(long version, String reason) : void`（**永不抛异常**）；`ConfigChangePublisher.bumpAndPublish(String reason) : long`（先 `raiseTo(now)` 再发布，返回生效版本）

- [ ] **Step 1: 写失败测试**

`ConfigInvalidateCodecTest`：

```java
@Test
void roundTripsVersionAndReason() {
    var msg = new ConfigInvalidateMessage(1_700_000_000_123L, "channel.update");
    assertThat(ConfigInvalidateCodec.decode(ConfigInvalidateCodec.encode(msg))).isEqualTo(msg);
}

@Test
void escapesTheDelimiterAndNewlinesInTheReason() {
    var msg = new ConfigInvalidateMessage(42L, "weird|reason\\with\nnewline\r");
    String wire = ConfigInvalidateCodec.encode(msg);
    // ⚠️ **不能数裸字符 `|`**：转义表把 `|` 变 `\|`，那里面**仍然含一个 `|`** —— 于是
    // `filter(c -> c == '|').count()` 无论做不做转义都是 2，这个断言零判别力（Task 2 实测到并纠正）。
    // 要数的是**未转义的分隔符**（跳过一个字符后遇到的第一个 `|`）：
    assertThat(countUnescapedDelimiters(wire)).as("分隔符必须只出现一次").isEqualTo(1);
    // 并且把线格式**钉成固定向量**（与 MeteringEventCodecTest 的字段序断言同一纪律）：
    assertThat(wire).isEqualTo("42|weird\\|reason\\\\with\\nnewline\\r");
    assertThat(ConfigInvalidateCodec.decode(wire)).isEqualTo(msg);
}

@Test
void malformedPayloadDecodesToNullInsteadOfThrowing() {
    assertThat(ConfigInvalidateCodec.decode(null)).isNull();
    assertThat(ConfigInvalidateCodec.decode("")).isNull();
    assertThat(ConfigInvalidateCodec.decode("not-a-number|x")).isNull();
    assertThat(ConfigInvalidateCodec.decode("1")).as("缺分隔符").isNull();
}

@Test
void channelNameIsTheSharedContractConstant() {
    assertThat(ConfigInvalidateTopology.CHANNEL).isEqualTo("aihub:config:invalidate");
}
```

`ConfigChangePublisherTest`（`@SpringBootTest` + `AbstractIntegrationTest`，用真 Redis 收消息；**只在本任务**允许这样装一条订阅来断言「消息真的发出去了」）：

```java
@Test
void publishesAfterBumpingTheWatermarkAndNeverThrowsOnRedisFailure() throws Exception {
    BlockingQueue<String> received = new LinkedBlockingQueue<>();
    container.addMessageListener((m, ch) -> received.add(new String(m.getBody())),
            new ChannelTopic(ConfigInvalidateTopology.CHANNEL));

    long version = publisher.bumpAndPublish("channel.create");

    assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull()
            .satisfies(body -> assertThat(ConfigInvalidateCodec.decode(body).version()).isEqualTo(version));
    assertThat(configVersionMapper.current()).isGreaterThanOrEqualTo(version);
}
```

- [ ] **Step 2: 跑它确认失败**

Run: `mvn -B -pl aihub-admin/aihub-common -am test "-Dtest=ConfigInvalidateCodecTest"`
Expected: FAIL —— 编译错误 `cannot find symbol: class ConfigInvalidateCodec`（类还不存在）。**这就是本任务的 RED 证据**：契约类型不存在，任何调用方都不可能"先写起来"。

- [ ] **Step 3: 实现**

`ConfigInvalidateCodec`（**与 `MeteringEventCodec` 同一纪律**：分隔符文本、转义 `\` `|` `\n` `\r`、畸形返回 null）：

```java
public final class ConfigInvalidateCodec {

    private static final char DELIMITER = '|';

    private ConfigInvalidateCodec() {
    }

    /** 线格式：{version}|{escaped reason}。reason 是**有限枚举**（如 "channel.update"），不是自由文本。 */
    public static String encode(ConfigInvalidateMessage message) {
        return message.version() + String.valueOf(DELIMITER) + escape(message.reason());
    }

    /** 畸形一律返回 null（**不抛**）：一条坏消息不该让订阅端崩掉，也不该被当成有效失效。 */
    public static ConfigInvalidateMessage decode(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        int split = indexOfUnescapedDelimiter(payload);
        if (split <= 0 || split == payload.length() - 1) {
            return null;
        }
        try {
            long version = Long.parseLong(payload.substring(0, split));
            return new ConfigInvalidateMessage(version, unescape(payload.substring(split + 1)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int indexOfUnescapedDelimiter(String payload) {
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == DELIMITER) { return i; }
        }
        return -1;
    }
    // escape / unescape 逐字照搬 MeteringEventCodec 的实现（同一个转义表）
}
```

`ConfigChangePublisher`：

```java
@Service
public class ConfigChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(ConfigChangePublisher.class);

    private final StringRedisTemplate redis;
    private final ConfigVersionMapper configVersionMapper;
    private final Counter publishFailures;

    /**
     * 抬水位（D5）并广播失效（D4）。返回生效版本。
     *
     * <p><b>发布失败绝不让业务写失败</b>：控制台的写已经提交了，把「广播失败」变成「业务失败」
     * 只会让用户以为没存上 —— 而本地 TTL（30s）+ 版本比对是设计文档 §6.3 写明的兜底。
     * 代价（诚实登记）：广播失败时其他实例只能等 TTL，最长回到 M3 的 10 分钟上界。
     */
    public long bumpAndPublish(String reason) {
        // 抬水位 = max(现在, 水位)：GREATEST 的 upsert 保证并发下不会把水位压低。
        // **不要**用 affected rows 判断"发生了什么"：MySQL 在「更新成相同值」时返回 0，
        // 而 Connector/J 默认的 CLIENT_FOUND_ROWS 语义下又会返回 1 —— 那是在猜驱动（Task 1 已登记）。
        long now = System.currentTimeMillis();
        configVersionMapper.raiseTo(now);
        Long stored = configVersionMapper.current();
        long version = stored == null ? now : Math.max(now, stored);
        publish(version, reason);
        return version;
    }

    public void publish(long version, String reason) {
        try {
            redis.convertAndSend(ConfigInvalidateTopology.CHANNEL,
                    ConfigInvalidateCodec.encode(new ConfigInvalidateMessage(version, reason)));
        } catch (RuntimeException e) {
            publishFailures.increment();
            log.warn("配置失效消息发布失败（业务写已提交，其他实例只能等 TTL 兜底）: {}", e.toString());
        }
    }
}
```

- [ ] **Step 4: 跑它确认通过**

Run: `mvn -B -pl aihub-admin/aihub-common -am test "-Dtest=ConfigInvalidateCodecTest"` → `Tests run: 4, Failures: 0`
Run: `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ConfigChangePublisherTest"`（`DOCKER_HOST=tcp://127.0.0.1:2375`）→ `Tests run: 3, Failures: 0`
> ⚠️ 本条原写 `Tests run: 1`，**从来就不对**：该类在创建它的那一提交（`c93970f`）里就已经有 **2** 条 `@Test`，Task 2 的修复轮（`097dc72`）加到 **3** 条。已按现状改成 3 —— **别改回 1**。教训：把「期望跑出几条用例」写成字面数字会随测试增长而腐坏，评审时这类数字必须与实际重跑核对，不能当成通过判据。
Expected: 两条都 `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
git add aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigInvalidate*.java \
        aihub-admin/aihub-common/src/test/java/com/aihub/common/config/ConfigInvalidateCodecTest.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigChangePublisher.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/config/ConfigChangePublisherTest.java
git commit -m "feat(config): add the config-invalidate Pub/Sub contract and the admin publisher"
```

**验收判据：** 频道名与线格式是**一份**实现（`aihub-common` 的常量 + codec），发布只在写成功后发生，发布失败**不**影响业务结果且有计数器与 WARN。
**RED 证据：** codec 类不存在导致编译失败（契约缺失是硬红）；`escapesTheDelimiterAndNewlinesInTheReason` 的判别力由**变异**证明 —— 把 `escape` 关掉（`encode` 直接拼 reason）后该用例在这一行变红（`expected: 1L but was: 2L`），打开转义即绿。（**更正**：本计划此前把这条写成「若不做转义会红在『分隔符出现了两次』上」—— 那句话对旧断言不成立，旧断言数的是**裸 `|` 字符**，而 `\|` 里也有一个 `|`，所以两种实现下计数都是 2、零判别力。Task 2 实测发现，已改成数**未转义**分隔符 + 钉固定向量。）

---

## Task 3: 版本单调性（`config_version` 水位）

**Files:**
- Modify: `aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigSnapshotService.java`（**只改版本计算**，注入 `ConfigVersionMapper`；**不要动 `snapshot()` 的 `@Transactional(readOnly = true)`**）
- Modify: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/config/ConfigSnapshotServiceTest.java`（**必须一起改**，见 Step 1 的第 3 条）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/config/ConfigSnapshotVersionTest.java`

**Interfaces:**
- Consumes: `ConfigVersionMapper.current()` / `raiseTo(long)`（Task 1）；`ConfigChangePublisher.bumpAndPublish`（Task 2/8/9/10 的抬升入口）
- **构造器从 5 参变 6 参**：`ConfigSnapshotService(ChannelMapper, ModelRouteMapper, RateLimitPolicyMapper, JdbcTemplate, ConfigVersionMapper, String defaultModel)`。（**更正 E.1-C3② 的一处错误**：评审核实仓库里**没有**任何 `new ConfigSnapshotService(` 调用点 —— `ConfigSnapshotServiceTest` 是 `@Autowired` 注入的，加参数对调用点**透明**。本任务真正要改的是**那个测试的断言**，见 Step 1 第 3 条，而不是"逐个跟着改编译错"。）
- Produces: `ConfigSnapshotService.currentVersion() : long`（**纯读**：`max(max(updated_at)，config_version.version)`，**绝不写库** —— `snapshot()` 是 `@Transactional(readOnly = true)`，而 MySQL + Connector/J 的 `readOnlyPropagatesToServer` 默认是**开**的，在里面写会 `ERROR 1792`，正好打在 `GET /internal/config/snapshot` 这条里程碑依赖的路径上）

- [ ] **Step 1: 写失败测试**

```java
@Test
void deletingTheNewestRouteRowDoesNotMakeTheVersionGoBackwards() {
    long before = snapshotService.currentVersion();
    long rowVersion = publisher.bumpAndPublish("route.create");      // 抬水位只能走**写入**路径（Task 2）
    assertThat(rowVersion).isGreaterThan(before);

    insertRoute("m4-version-probe", 1L);
    long withRow = snapshotService.currentVersion();
    assertThat(withRow).isGreaterThanOrEqualTo(rowVersion);

    deleteRoute("m4-version-probe");                                 // 删掉「最新」那一行
    assertThat(snapshotService.currentVersion())
            .as("M4 之前：version = max(updated_at)，删掉最新行会让版本倒退，"
                    + "而网关两侧的比对都是严格 >，于是更旧的快照会被 lastGood 记住并继续服务")
            .isGreaterThanOrEqualTo(withRow);
}

@Test
void theWatermarkSurvivesARecreatedServiceInstance() {
    long v = publisher.bumpAndPublish("channel.update");
    deleteAllRoutes();                                               // 把库里的配置清空

    ConfigSnapshotService fresh = new ConfigSnapshotService(channelMapper, routeMapper, policyMapper,
            jdbcTemplate, configVersionMapper, "default-model");
    assertThat(fresh.currentVersion()).as("水位在库里，不在进程里").isGreaterThanOrEqualTo(v);
}
```

**第 3 条：两条既有断言必须显式处理**（不改就是两条红）。`ConfigSnapshotServiceTest` 里的
`emptyDatabaseYieldsAnEmptySnapshotWithVersionZero` 与 `versionStrictlyIncreasesEveryTimeAnyOfTheThreeTablesChanges`
断言「空库 → version 0」与「版本严格变大」；而 Testcontainers 的 MySQL 是 **JVM 级共享**的，水位会跨用例存活。
所以在**该类**加一个 `@BeforeEach` 把水位显式归零，并把理由写在测试里：

```java
@BeforeEach
void resetVersionWatermark() {
    // 水位是**持久**的（这正是 Task 3 的意义），而容器是 JVM 级共享的：
    // 不归零，「空库 version=0」这类断言就变成了对**用例执行顺序**的断言。
    jdbcTemplate.update("UPDATE config_version SET version = 0 WHERE id = 1");
}
```

顺手把 `emptyDatabaseYieldsAnEmptySnapshotWithVersionZero` 的**用例名**改成
`emptyDatabaseYieldsAnEmptySnapshotWithTheResetVersion`，别让它继续暗示「version 恒为 0」。

- [ ] **Step 2: 跑它确认失败**

Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ConfigSnapshotVersionTest"`
Expected: 两条都 FAIL —— 第一条报 `expecting actual: 1.7...E12 to be greater than or equal to: 1.7...E12`（实际值**小于**期望值，因为版本倒退了）；第二条同样红。**这正是 M3 登记、但一直没有观测手段的那条缺口**，本轮第一次把它变成可执行的事实。

- [ ] **Step 3: 实现**

```java
/**
 * 快照版本 = max(三张配置表的 max(updated_at), config_version 的水位)。
 *
 * <p><b>纯读：本方法绝不写库。</b>它由 {@link #snapshot()} 调用，而后者是
 * {@code @Transactional(readOnly = true)}；MySQL + Connector/J 的 {@code readOnlyPropagatesToServer}
 * 默认是开的（会发 {@code SET SESSION TRANSACTION READ ONLY}），在只读事务里写会直接
 * {@code ERROR 1792} —— 正好打在 {@code GET /internal/config/snapshot} 这条里程碑依赖的路径上。
 * 因此水位**只在配置写入路径**上抬升（{@code ConfigChangePublisher.bumpAndPublish}）。
 *
 * <p>为什么需要水位：{@code max(updated_at)} 会**倒退**（删掉最新那一行），而网关两侧的比对是
 * 严格 {@code >} —— 一旦倒退，更旧的快照会被 lastGood 记住并继续服务（M3 登记、M4 修）。
 */
public long currentVersion() {
    long dbMax = maxUpdatedAtAcrossConfigTables();     // 既有实现保持不变（JdbcTemplate 查三张表）
    Long stored = configVersionMapper.current();
    return Math.max(dbMax, stored == null ? 0L : stored);
}
```

同步更新该类 javadoc 里的版本语义，并把 D5 的**残余**写进去：**手工 SQL 的写入/删除不走控制台，就不会抬水位**，因此一条 raw SQL 的删除仍可能让版本回退一次 —— M4 的验收（Task 17 第 3 步）因此必须走控制台/API 改配置。

- [ ] **Step 4: 跑它确认通过**

Run: 同 Step 2 命令 → `Tests run: 2, Failures: 0` + `BUILD SUCCESS`。
再跑一遍既有的版本用例，确认没被改坏：`mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ConfigSnapshotServiceTest,InternalConfigSnapshotIntegrationTest"`。

- [ ] **Step 5: 提交**

```bash
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigSnapshotService.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/config/ConfigSnapshotVersionTest.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/config/ConfigSnapshotServiceTest.java
git commit -m "fix(config): make the snapshot version monotonic with a persisted high-water mark"
```

**验收判据：** 删掉最新配置行后 `currentVersion()` **不回退**；水位跨实例存活（存在库里）；控制台写入路径由 Task 8/9/10 的 `bumpAndPublish` 显式抬升。
**RED 证据：** 两条用例在实现前都红，且原因是**值真的倒退了**（不是编译错），因此它们是判别性的。

---

## Task 4: 网关订阅方 + `invalidate(long)` 清本地/共享并把水位**抬到消息版本**

> **本任务是 M4 验收标准（控制面配置 → 数据面生效）的前置，必须最先做完。**

> ⚠️ **`ConfigCacheTest.equalVersionIsRewrittenToRedisAfterBothCacheLayersLapse`（约 842 行）是一个先存的真实时钟脆点**，M4 Task 1 的全量跑里实测到了它（第一次全量 `aihub-gateway` 只红这一条，单独重跑该模块 350/350 全绿，那个类三次 FAIL/PASS/PASS）。独立评审核实过：**不是 Task 1 造成的**（该文件在两个提交里 blob 一致，本次提交没碰网关任何文件）；机制是「第 842 行必须观察到一个**刚写入、TTL 只有 80 ms** 的本地条目」（`ConfigCache.java:69` 的 `expireAfterWrite(localTtl)`，测试把 localTtl/cooldown 都设成 80 ms），任何 ≥80 ms 的停顿都会把它翻成空（失败那次该用例耗 3.47 s，通过时约 1 s）。
>
> **本任务要把它变确定**（它正好改 `ConfigCache`/`invalidate` 这一段）：**首选**给 `ConfigCache` 注入 `Ticker`/`Clock` 并在测试里推进它，替掉 `Thread.sleep(150)`；**次选**断言那个**守卫**（版本比对/水位）而不是「一个 80 ms 的条目还在」。**禁止**用「允许重跑一次」或加长 sleep 来"修" —— 那只是把脆点藏起来，而 Task 17 的全量 `mvn clean test` 要引用这条证据。
>
> **第二个先存脆点（admin 侧，2026-09-28 实测）**：RedisTokenBucketIntegrationTest.concurrentRequestsNeverExceedBurst 在全量跑时因 RedisCommandTimeoutException: Command timed out after 500 millisecond(s)（evalSha）红了，单独跑 11/11 绿、全量重跑也绿。独立评审核实：**不是超发**（它的真断言是 llowed == burst / denied / Redis 侧 	okens == 0，而超时在 	ry 块里**先于**那些断言发生，所以报的是「500 ms 内没拿到结果」而不是「多放了」），属负载/时序假红，且与 Task 3 无关。**归属：admin 侧的测试硬化（Task 15 收口时一并处理，或用 per-test 的更大 Redis 命令超时）** —— 不要塞给 Task 4（Task 4 是网关侧）。**两个脆点都不许用「放宽断言」或「允许重跑一次」来修**：isEqualTo(burst)、	okens == 0、以及网关那个 80 ms 新鲜度断言都必须原样保留。

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigSubscriber.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigInvalidateSubscriptionConfig.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigInvalidateProperties.java`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigCache.java`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigClient.java`
- Modify: `aihub-gateway/src/main/resources/application.yml`
- Modify: `aihub-gateway/src/test/resources/application.properties`
- Modify: `aihub-gateway/src/test/java/com/aihub/gateway/config/ConfigCacheTest.java`（**硬化一个先存的真实时钟脆点**，见下面的说明；**只许让它变确定，不许放宽任何断言**）
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/config/ConfigSubscriberTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/config/ConfigInvalidateContractTest.java`

> **不要改 `GatewayConfigProperties`。** 它是 record，加一个分量会让**每一个** `new GatewayConfigProperties(...)` 的调用点编译失败 —— 评审已核实有 **8 处**（`ConfigCacheTest` 7 处 + `ModelsControllerTest` 1 处），而那两个文件不在本任务的 Files 里，Step 4 却要求 `ConfigCacheTest` 绿。新增一个**独立**的小配置类即可：
> `@ConfigurationProperties("aihub.config") public record ConfigInvalidateProperties(@DefaultValue("true") boolean invalidateSubscription) {}`（与既有 `GatewayConfigProperties` 并列，互不影响）。

**Interfaces:**
- Consumes: `ConfigInvalidateTopology` / `ConfigInvalidateCodec` / `ConfigInvalidateMessage`（Task 2）；`ConfigCache`；`AdminClient`
- Produces:
  - `ConfigCache.invalidateAllCaches(long version) : void` —— 清本地 + **删共享条目 `REDIS_KEY`** + **把 `observedRedisVersion` 设为消息里那个 `version`**（见下）
  - `ConfigClient.invalidate(long version) : void` —— 清本地 + `cache.invalidateAllCaches(version)`（**水位抬到 `version`**）+ 放行冷却窗口；`ConfigSubscriber` 把解码出来的版本传进来（**没有第二种写法**，见 N14 的处置）
  - `ConfigSubscriber implements MessageListener`，`onMessage(Message, byte[]) : void`
  - `ConfigInvalidateProperties.invalidateSubscription() : boolean`（`aihub.config.invalidate-subscription`，默认 `true`）
  - `ConfigInvalidateSubscriptionConfig`：`@Bean RedisMessageListenerContainer configInvalidateListenerContainer(RedisConnectionFactory, ConfigSubscriber, ConfigInvalidateProperties)`，`@ConditionalOnProperty(name = "aihub.config.invalidate-subscription", havingValue = "true", matchIfMissing = true)`

- [ ] **Step 1: 写失败测试**

`ConfigInvalidateContractTest`（**不起 Spring 上下文、不碰 socket**：直接 `new ConfigClient(new ConfigCache(mockRedis, props), mockAdmin, props...)`，与既有 `ConfigCacheTest` 同一手法）：

```java
@Test
void invalidationDropsTheSharedEntryAndRaisesTheWatermarkToTheInvalidatedVersion() {
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(ConfigCache.REDIS_KEY)).thenReturn(ConfigSnapshotCodec.encode(snapshotOfVersion(4L)));
    cache.readRedis();                                   // 水位到 4

    cache.invalidateAllCaches(9L);                       // 消息里带的是 9

    verify(redis).delete(ConfigCache.REDIS_KEY);         // ① 共享条目必须被删

    // ② 水位不能被重置成 NO_VERSION：那样一个**在飞的旧回填**（版本 5）会把刚删掉的
    //    陈旧共享条目重新写回去，等于让这次失效白做。水位应当抬到消息里的 9。
    cache.writeRedis(snapshotOfVersion(5L));
    verify(valueOps, never()).set(eq(ConfigCache.REDIS_KEY), anyString(), any(Duration.class));

    cache.writeRedis(snapshotOfVersion(9L));             // 不旧于水位 → 允许
    verify(valueOps).set(eq(ConfigCache.REDIS_KEY), anyString(), any(Duration.class));
}

@Test
void afterInvalidationTheNextReadGoesToAdminInsteadOfServingTheStaleSharedEntry() {
    // 本地为空 + Redis 里有一份旧快照 + admin 有一份新快照
    when(admin.configSnapshot()).thenReturn(Mono.just(Optional.of(newSnapshot)));

    client.invalidate(9L);
    ConfigSnapshot served = client.current();

    assertThat(served.version()).as("M3 的缺口：invalidate() 只清本地，紧接着 resolve() 又会采用 Redis 里的旧条目")
            .isEqualTo(newSnapshot.version());
    verify(admin, times(1)).configSnapshot();
}
```

`ConfigSubscriberTest`（**注意 `ConfigSubscriber` 是 `@Component`，`withUserConfiguration(ConfigConfig.class)` 不会把它注册进来**，而 `RedisMessageListenerContainer` 需要 `RedisConnectionFactory`）：

```java
@Test
void aValidMessageInvalidatesAndAMalformedOneDoesNot() {
    ConfigClient client = mock(ConfigClient.class);
    ConfigSubscriber subscriber = new ConfigSubscriber(client);

    subscriber.onMessage(new Message(null, new byte[0]),
            ConfigInvalidateCodec.encode(new ConfigInvalidateMessage(9L, "channel.update")).getBytes(UTF_8));
    verify(client).invalidate(9L);

    subscriber.onMessage(new Message(null, new byte[0]), "garbage".getBytes(UTF_8));
    verifyNoMoreInteractions(client);                    // 坏消息不触发失效，也不抛
}

@Test
void theSubscriptionBeanIsWiredExactlyWhenTheFlagIsOn() {
    new ApplicationContextRunner()
            .withUserConfiguration(ConfigInvalidateSubscriptionConfig.class)
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
            .withBean(ConfigSubscriber.class, () -> mock(ConfigSubscriber.class))
            .withPropertyValues("aihub.config.invalidate-subscription=true")
            .run(ctx -> assertThat(ctx).hasBean("configInvalidateListenerContainer"));

    new ApplicationContextRunner()
            .withUserConfiguration(ConfigInvalidateSubscriptionConfig.class)
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
            .withBean(ConfigSubscriber.class, () -> mock(ConfigSubscriber.class))
            .withPropertyValues("aihub.config.invalidate-subscription=false")
            .run(ctx -> assertThat(ctx).doesNotHaveBean("configInvalidateListenerContainer"));
}
```

- [ ] **Step 2: 跑它确认失败**

Run: `mvn -B -pl aihub-gateway -am test "-Dtest=ConfigInvalidateContractTest,ConfigSubscriberTest"`
Expected: FAIL —— `cannot find symbol: method invalidateAllCaches(long)`（编译错）+ 第二条用例若只保留今天的 `invalidate()` 实现会红在 `expected: <新版本> but was: <旧版本>`。**第二条的红是判别性的**，它测的正是 M3 登记的缺口。

- [ ] **Step 3: 实现**

```java
// ConfigCache 新增
/**
 * 失效的**完整**含义：本地、共享条目、写入水位三者一起动。
 *
 * <p>只清本地是 M3 登记的缺口：紧接着的 {@code resolve()} 会读到 Redis 里**同样陈旧**的
 * 共享条目并采用它，于是「配置变了」这个信号对多实例部署完全没有效果（实测 101 秒仍不可见）。
 *
 * <p><b>水位要抬到消息里的版本，而不是重置成 {@code NO_VERSION}</b>：重置会拆掉
 * 「挡住在飞的旧回填把刚删掉的陈旧条目写回去」的唯一护栏 —— 那等于让这次失效白做。
 *
 * @param version 失效消息里带的版本（权威的"控制面已经到过这里"的证据）
 */
public void invalidateAllCaches(long version) {
    local.invalidateAll();
    observedRedisVersion.accumulateAndGet(version, Math::max);
    try {
        redis.delete(REDIS_KEY);
    } catch (RuntimeException e) {
        log.warn("删除共享配置快照失败（本地已失效，回源仍会写回新版本）: {}", e.toString());
    }
}
```

`ConfigClient.invalidate(long)` 的形态（**签名定为带版本**：这是唯一能把「水位抬到消息版本」这件事做对的地方 —— N14 的处置）：

```java
/**
 * 收到失效广播时调用。
 *
 * @param version 失效消息里的版本。**它就是新的水位**：清掉共享条目之后，一个在飞的、
 *                更旧的回源结果必须被 {@code writeRedis} 挡住，而唯一的挡板就是这个水位。
 *                用本地的 {@code visibleVersion} 当水位是不够的 —— 它可能比控制面刚推进到的版本更旧。
 */
public void invalidate(long version) {
    cache.invalidateAllCaches(version);
    nextAttemptAt.set(0L);
}
```

```java
// ConfigSubscriber：坏消息一律忽略并 WARN，绝不让监听线程抛（抛出去会被容器反复重投）
@Component
public class ConfigSubscriber implements MessageListener {
    @Override
    public void onMessage(Message message, byte[] pattern) {
        ConfigInvalidateMessage decoded = ConfigInvalidateCodec.decode(new String(message.getBody(), UTF_8));
        if (decoded == null) {
            log.warn("忽略畸形的配置失效消息");
            return;
        }
        log.info("收到配置失效消息（version={}, reason={}），清理本地与共享缓存", decoded.version(), decoded.reason());
        configClient.invalidate(decoded.version());
    }
}
```

`ConfigInvalidateSubscriptionConfig` 里装配（`@ConditionalOnProperty(name = "aihub.config.invalidate-subscription", havingValue = "true", matchIfMissing = true)` **且必须带 `@EnableConfigurationProperties(ConfigInvalidateProperties.class)`** —— 否则那个 `@ConfigurationProperties` record 不会被注册成 bean，`@Bean` 方法注入不到它，上下文起不来；这两点一起被 N3/N13 点名）一个 `RedisMessageListenerContainer` + `ChannelTopic(ConfigInvalidateTopology.CHANNEL)`；**`aihub-gateway/src/test/resources/application.properties` 里显式写 `aihub.config.invalidate-subscription=false`**，理由写在注释里：网关测试环境**没有活 Redis**，容器会在后台无限重试连接并污染日志；订阅逻辑本身由 `ConfigSubscriberTest` 直接驱动，端到端由 Task 17 的 compose 验收覆盖。`application.yml`（生产）写 `true`。**不要**改 `ConfigConfig`（N3：装配的所有权在专用的配置类里）。

- [ ] **Step 4: 跑它确认通过**

Run: `mvn -B -pl aihub-gateway -am test "-Dtest=ConfigInvalidateContractTest,ConfigSubscriberTest,ConfigCacheTest"`
Expected: 全绿 + `BUILD SUCCESS`；再跑一次整个网关模块 `mvn -B -pl aihub-gateway -am test` 确认**没有 Docker、没有活 Redis**也能全绿（基线 350）。
> ⚠️ **`ConfigClientTest` 不存在**（这个模块只有 `ConfigCacheTest`，本任务也没有创建 `ConfigClientTest`）。而且 `.mvn/maven.config` 设了 `-Dsurefire.failIfNoSpecifiedTests=false`，所以把一个不存在的类名写进 `-Dtest` 会**打印 BUILD SUCCESS 却静默跳过** —— 正是全局约束里点名的那口陷阱（F5）。契约用例放在 `ConfigInvalidateContractTest` 里就够，不要再引用不存在的类。

- [ ] **Step 5: 提交**

```bash
git add aihub-gateway/src/main/java/com/aihub/gateway/config/ \
        aihub-gateway/src/main/resources/application.yml \
        aihub-gateway/src/test/java/com/aihub/gateway/config/ \
        aihub-gateway/src/test/resources/application.properties
git commit -m "fix(config): honour the invalidation broadcast, drop local+shared caches and RAISE the water mark"
```

**验收判据：** 收到一条失效消息后，网关的下一次读取**必须回源 admin**，并且共享条目**被删**、写入水位**抬到消息里的版本**（**不是**清掉/重置）；坏消息不触发失效也不抛异常；测试环境不因缺 Redis 而变红。
**RED 证据：** `afterInvalidationTheNextReadGoesToAdminInsteadOfServingTheStaleSharedEntry` 在只保留 M3 的 `invalidate()` 时红在「服务了旧版本」；`invalidationDropsTheSharedEntryAndRaisesTheWatermarkToTheInvalidatedVersion` 红在 `delete` 从未被调用、以及「版本 5 的写入本该被拒」。两条都直接对应用户可见的 10 分钟上界。

---
## Task 5: bcrypt 依赖验证 + 控制台令牌（JWT 形状的 HS256）

**Files:**
- Modify: `aihub-admin/aihub-service/pom.xml`（**+1 依赖，版本不写死**）
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/console/ConsoleClaims.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/console/ConsoleToken.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ConsoleTokenTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`（`aihub-service` 已有）
- Produces:
  - `public record ConsoleClaims(long userId, long tenantId, String role, long issuedAtEpochSecond, long expiresAtEpochSecond)`
  - `ConsoleToken.issue(byte[] secret, ConsoleClaims claims) : String`
  - `ConsoleToken.verify(byte[] secret, String token) : ConsoleClaims`（**任何失败都抛 `IllegalArgumentException`**：格式 / base64 / 签名 / 过期 / claims 缺失或类型不对）
    - ⚠️ **必须把解析段整个包在 `catch` 里并转成 `IllegalArgumentException`，而且 catch 的类型是 `IOException`（不是 `JsonProcessingException`）**：`ObjectMapper.readTree(byte[])` 声明的是受检 `IOException`，而 `JsonProcessingException extends JacksonException extends IOException` —— 写成 `catch (RuntimeException | JsonProcessingException)` **编译不过**（Task 5 实测，`javap` 已证实）。漏了这一步，受检异常会穿透到过滤器外面变成 **500**，而契约要求 **401**（`ConsoleAuthFilter` 只接 `IllegalArgumentException`）。
    - ⚠️ **密钥是配置故障，不是凭证故障**：签名密钥为 `null`/空时 `SecretKeySpec` 抛的是 `IllegalArgumentException`，但 `hmac` 会把它包成 **`IllegalStateException`** —— 这是**刻意**的（与 503-vs-401 同源：绝不把平台故障伪装成凭证错误）。因此：**token 级失败 → IAE；密钥问题 → ISE**，`verify` 的 javadoc 必须写清这个区分，且**两侧都要有测试**。另外密钥来自 `aihub.console.secret`（UTF-8 字节），**空/短密钥那一支由 Task 6 的过滤器与登录接口自己判**（D16），不要指望 `verify` 替它做。
    - ⚠️ **`verify` 不校验 `role`**：它只返回载荷。未知角色必须由 Task 6 **fail-closed**（D10：只认 `ADMIN`/`VIEWER`，其余按无权处理）。同理 **`exp ≤ 2h` 不由 `verify` 保证** —— 没有最大存活期校验，**签发方必须自己封顶 TTL**。
  - 常量 `ConsoleClaims.ROLE_ADMIN = "ADMIN"`、`ConsoleClaims.ROLE_VIEWER = "VIEWER"`

- [ ] **Step 1: 先验证依赖能拉到（**在写任何代码之前**）**

Run: `mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=org.springframework.security:spring-security-crypto:6.5.11`
Expected: `Downloaded from aliyunmaven: … spring-security-crypto-6.5.11.jar` + `BUILD SUCCESS`。
**已在计划编写阶段实测通过（2026-09-27）**，jar 已在 `.m2repo`。若换了机器或镜像失效导致解析失败：**报 BLOCKED 并说明**，**绝不**改为自己实现 bcrypt（D2）。
**不要**用 `curl -I https://maven.aliyun.com/...` 判断（本机 curl 到该域名返回 `000`，走的是 Maven 自己的镜像/代理配置）。

- [ ] **Step 2: 写失败测试**

`ConsoleTokenTest`（纯单元，不需要 Spring）：

```java
private static final byte[] SECRET = "m4-console-test-secret".getBytes(UTF_8);

@Test
void roundTripsClaims() {
    // ⚠️ **夹具必须是固定的「未来」常量**：原稿写的 1_700_000_000/1_700_007_200 是 **2023-11-14/15**，
    // 相对本机时钟（2026）永远过期，`roundTripsClaims` 因此**永远不可能通过**（Task 5 实测：第一次 GREEN
    // 就红在 "expired"）。用 4_000_000_000/4_000_007_200（= 2096-10-02 UTC）。过期用例仍用过去的常量。
    ConsoleClaims claims = new ConsoleClaims(7L, 1L, ConsoleClaims.ROLE_ADMIN, 4_000_000_000L, 4_000_007_200L);
    assertThat(ConsoleToken.verify(SECRET, ConsoleToken.issue(SECRET, claims))).isEqualTo(claims);
}

@Test
void theWireFormIsHeaderDotPayloadDotSignature() {
    String token = ConsoleToken.issue(SECRET, claims());
    assertThat(token.split("\\.")).hasSize(3);
    assertThat(new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), UTF_8))
            .isEqualTo("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
}

@Test
void aTamperedPayloadOrSignatureIsRejected() {
    String token = ConsoleToken.issue(SECRET, claims());
    String[] parts = token.split("\\.");
    assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET,
            parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"sub\":\"999\"}".getBytes(UTF_8)) + "." + parts[2]));
    assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, parts[0] + "." + parts[1] + ".AAAA"));
}

@Test
void anExpiredTokenIsRejected() {
    ConsoleClaims expired = new ConsoleClaims(7L, 1L, ROLE_ADMIN, 1_600_000_000L, 1_600_000_001L);
    assertThatIllegalArgumentException().isThrownBy(
            () -> ConsoleToken.verify(SECRET, ConsoleToken.issue(SECRET, expired)));
}

@Test
void aTokenSignedWithAnotherSecretIsRejected() {
    String token = ConsoleToken.issue("other".getBytes(UTF_8), claims());
    assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, token));
}

@Test
void aThreeSegmentTokenWithAnAlgNoneHeaderIsRejectedBySignatureComparisonNotByShape() {
    // ⚠️ **不要用「签名段为空」的拼法**：`header + "." + payload + "."` 在 Java 的 `String.split` 下
    // 只切出 **2 段**（末尾空串被丢掉），于是它被三段式形状检查拒掉 —— **无论校验方看不看请求 header**。
    // 这条用例因此是**空的**（Task 5 实测：变异体 9 条过 8 条，它照样绿）。真正判别的是下面这种：
    // **三段齐全、header 声明 alg:none、签名段非空但无效（例如 3 字节）、载荷完全合法** ——
    // 只有「看到 alg:none 就跳过签名比较」的实现才会放行它。先断言段数，再断言拒绝。
    String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
    String payload = b64("{\"sub\":\"7\",\"tenantId\":\"1\",\"role\":\"ADMIN\",\"exp\":9999999999}");
    assertThatIllegalArgumentException().isThrownBy(() -> ConsoleToken.verify(SECRET, header + "." + payload + "."));
}
```

- [ ] **Step 3: 跑它确认失败**

Run: `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ConsoleTokenTest"`
Expected: FAIL —— `cannot find symbol: class ConsoleToken`（RED 是编译级的：契约不存在）。

- [ ] **Step 4: 实现**

`aihub-service/pom.xml` 加（**不写版本**，由 Boot BOM 管理）：

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

`ConsoleToken`（JDK `javax.crypto.Mac` + `MessageDigest.isEqual` + Jackson 读写载荷）：

```java
public final class ConsoleToken {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    /** 算法与 header 都是**服务端写死**的：校验方不读请求里的 header，因此不存在 alg 混淆面。 */
    private static final String HEADER = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(UTF_8));

    public static String issue(byte[] secret, ConsoleClaims claims) {
        String payload = ENC.encodeToString(write(claims));
        String signingInput = HEADER + "." + payload;
        return signingInput + "." + ENC.encodeToString(hmac(secret, signingInput));
    }

    /** 任何失败都抛 IllegalArgumentException —— 过滤器据此回 401，不需要 `Optional` 的三态。 */
    public static ConsoleClaims verify(byte[] secret, String token) {
        if (token == null) { throw new IllegalArgumentException("missing token"); }
        String[] parts = token.split("\\.");
        if (parts.length != 3) { throw new IllegalArgumentException("malformed token"); }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(secret, signingInput);
        byte[] actual = DEC.decode(parts[2]);                      // 非法 base64 → IllegalArgumentException
        if (!MessageDigest.isEqual(expected, actual)) {            // 恒定时间比较（D3）
            throw new IllegalArgumentException("bad signature");
        }
        ConsoleClaims claims = read(DEC.decode(parts[1]));
        if (claims.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("expired");
        }
        return claims;
    }
    // hmac(secret, input) = Mac.getInstance("HmacSHA256")，key = new SecretKeySpec(secret, "HmacSHA256")
    // write/read：Jackson 读写**固定五个**字段；注意线上载荷里 `userId` 写作标准的 `sub` 声明，
    // 读取时把 `sub` 映射回 `ConsoleClaims.userId()`（写死这一对，不要"兼容"别的拼法）
    // 其它四个是 tenantId / role / iat / exp（数字或字符串都按 long/String 显式取，缺失即 IAE）
}
```

类的 javadoc 必须写明 D3 的边界：只支持 HS256、不做密钥轮换、不校验 `aud`/`iss`、没有 refresh 与吊销黑名单；**一旦出现第二签发方或需要吊销，必须换成库**。

- [ ] **Step 5: 跑它确认通过并提交**

Run: `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ConsoleTokenTest"` → `Tests run: 6, Failures: 0` + `BUILD SUCCESS`

```bash
git add aihub-admin/aihub-service/pom.xml \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/console/ \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ConsoleTokenTest.java
git commit -m "feat(console): add the HS256 console token with a server-pinned algorithm"
```

**验收判据：** 令牌是 `header.payload.signature`；篡改载荷/签名、换密钥、过期、`alg:none` **全部**被拒；依赖从镜像可解析。
**RED 证据：** 六个用例在类不存在时编译失败（契约缺失是硬红）。判别力**要看哪条杀掉哪种变异**：`roundTripsClaims` 杀掉「签名输入里丢掉 `parts[0]`」的变异；`aThreeSegmentTokenWithAnAlgNoneHeader…` 杀掉「看到 `alg:none` 就跳过签名比较」的变异（Task 5 实测：该变异体 9 条过 8 条，只红在这一条）。（**更正**：本计划此前写「`aTokenWhoseHeaderClaimsAlgNone` 在『校验方读请求 header』的错误实现下会红」—— 那是**假的**：旧拼法只有 2 段，看不看 header 都会被形状检查拒掉，零判别力。已按上面的拼法改写。）

---

## Task 6: 登录 + `/api/**` 鉴权过滤器 + 两级角色

**Files:**
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/SysUserEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/SysUserMapper.java`
- Modify: `aihub-admin/aihub-common/src/main/java/com/aihub/common/api/ErrorCode.java`（**+1 枚举值** `CONFIGURATION_ERROR(500)`，D16 唯一需要的共享改动）
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/console/ConsoleAuthService.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/console/ConsoleTokenService.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/config/ConsoleProperties.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/ConsoleAuthFilter.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/ConsoleAuthController.java`
- Modify: `aihub-admin/aihub-web/src/main/resources/application.yml`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ConsoleAuthFilterTest.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ConsoleLoginIntegrationTest.java`

**执行期追加的文件（Task 6 实际落地时新增，见附录 G；上面那份清单是开工前写的，以下是最小必要扩展）：**
- Modify: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/error/GlobalExceptionHandler.java`（**只改 javadoc**：`ErrorCode` 因本任务的 `CONFIGURATION_ERROR` 从 8 个常量变成 9 个，原注释「只有 8 个常量」失真）
- Modify: `aihub-admin/aihub-service/src/main/java/com/aihub/service/console/ConsoleClaims.java`（**只改 javadoc**：只读口径含 `OPTIONS`，见 D10）
- Modify: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/metering/MeteringConsumerIntegrationTest.java`（去掉「单消费者 FIFO」时序假设，换成有界轮询；见附录 G3）
- Modify: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ConsoleTokenTest.java`（补密钥归一化判别用例 + 恰好 32 字符可用性）。⚠️ **它在 `aihub-web` 的测试树里，不在 `aihub-service`**（`aihub-service/src/test` 整个目录不存在；`ConsoleTokenTest` 与 `ConsoleToken` 分属不同模块，同名包只是巧合）
- Test: 新增一条**真 Spring 上下文**的空密钥集成用例（命名由实现者定，落在 `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/` 下；手工装配的 standalone MockMvc 不算，见附录 G4）

**Interfaces:**
- Consumes: `ConsoleToken`/`ConsoleClaims`（Task 5）；`BCryptPasswordEncoder`（`spring-security-crypto`）；`AuditService`（Task 7 —— **若 Task 7 尚未完成，本任务先不写审计调用**，见 Step 3 的说明）
- Produces:
  - `SysUserEntity`（`id/tenantId/username/passwordHash/role/status`）、`SysUserMapper extends BaseMapper<SysUserEntity>`
  - `ConsoleAuthService.login(String username, String password) : ConsoleClaims`（失败抛 `BizException(UNAUTHORIZED, …)`；secret 为空抛 `BizException(CONFIGURATION_ERROR, …)`）
  - `ConsoleTokenService.issue(ConsoleClaims) : String`、`verify(String) : ConsoleClaims`
  - `ConsoleProperties`：`secret`（`aihub.console.secret`）、`tokenTtl`（默认 `2h`）
  - `ConsoleAuthFilter`：`@Order(Ordered.LOWEST_PRECEDENCE - 100)`，只守 `/api/**`
  - 请求属性 `ConsoleAuthFilter.ATTRIBUTE_CLAIMS = "aihub.consoleClaims"`
  - **`ConsoleAuthController` 同时提供一个 `GET /api/ping`**（返回 `{"code":"OK","message":"success","data":{"userId":…,"tenantId":…,"role":…}}`，取自 `ATTRIBUTE_CLAIMS`）。它存在的唯一理由是**给本任务的鉴权用例一个真实存在的靶子**：`/api/**` 在 Task 6 里只有登录一个映射，而 Task 8 的 `/api/tenants` 还没写出来（F3）
  - ⚠️ **本任务的两个正向用例不许引用 `/api/tenants`**（那是 Task 8 的）：带令牌 200 的那一半打 `GET /api/ping`；`VIEWER` 只读角色的对照也用 `GET /api/ping`（200）与 `POST /api/ping`（403，写方法被拒 —— 拦在过滤器里，和具体控制器无关）
  - ⚠️ **本任务不许假设 `verify` 会替它做三件事**（Task 5 的评审逐条点名，都是「契约之外」的行为）：
    1. **空/短密钥**：`verify` 对 `null`/空密钥抛的是 **`IllegalStateException`（配置故障）而不是 IAE** —— 这是刻意的（与 503-vs-401 同源）。所以**本任务必须自己判 `aihub.console.secret` 为空或 < 32 字符**（D16：`/api/**` 一律 401，登录回 `CONFIGURATION_ERROR`），**不能**指望 `verify` 给 401。密钥用 **UTF-8 字节**，且必须与签发端一致。
    2. **角色**：`verify` **只返回载荷，不校验 `role`**。未知角色（不在 `ADMIN`/`VIEWER` 里）**必须 fail-closed**（D10），由本任务的过滤器判。
    3. **TTL**：`verify` **没有最大存活期校验**（不看 `iat` 与 `exp` 的关系）。**签发端必须自己封顶 `exp − iat ≤ 2h`**（D3），本任务的登录接口负责这件事。

- [ ] **Step 1: 写失败测试**

```java
@Test
void loginReturnsATokenAndTheFilterAcceptsIt() { /* POST /api/auth/login → 200 + data.token；带它 GET /api/ping → 200 */ }

@Test
void aWrongPasswordIs401AndDoesNotRevealWhetherTheUserExists() {
    // 用户名不存在 与 口令错 必须返回**逐字相同**的信封（否则等于一个用户枚举接口）
}

@Test
void aBlankConsoleSecretFailsClosedWithConfigurationError() {
    // aihub.console.secret="" → 登录返回 500 + code=CONFIGURATION_ERROR（D16：不许伪装成凭证错误）
    //                     → 同时任何 /api/** 请求（无令牌）都是 401，绝不放行
}

@Test
void viewerRoleMayReadButNotWrite() {
    // GET  /api/ping → 200（**不是** /api/tenants：那是 Task 8 的，见 Interfaces 里的 F3 说明）
    // POST /api/ping → 403 + code=FORBIDDEN（admin 信封，不是 Spring 默认体）
}

@Test
void internalAndHealthEndpointsAreNotAffected() {
    // /healthz → 200（无令牌）；/internal/api-keys/resolve 无签名 → 401（仍由既有的 InternalAuthFilter 决定）
}
```

> ⚠️ **本任务不要写「`GET /console/index.html` → 200」这条断言**：静态资源文件到 **Task 16** 才存在，在这里写它按字面必然红（这正是 E.3 的 caveat 1，现已升级为正文要求）。登录页的可访问性由 Task 16 的 `ConsoleStaticResourceTest` 覆盖；本任务只需保证 **`ConsoleAuthFilter` 不守 `/api/**` 之外的路径** —— 上面那条 `internalAndHealthEndpointsAreNotAffected` 已经覆盖了这一点（`/healthz` 与 `/internal/**` 不经本过滤器）。

- [ ] **Step 2: 跑它确认失败**

Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ConsoleLoginIntegrationTest,ConsoleAuthFilterTest"`
Expected: FAIL —— 编译错误（`ConsoleAuthFilter` 不存在）；或 404（`/api/auth/login` 没有映射）。

- [ ] **Step 3: 实现**

`ConsoleAuthFilter` 的关键结构（**与既有 `InternalAuthFilter` 同一手法**：自己解析、自己写响应、不引 Spring Security）：

```java
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class ConsoleAuthFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE_CLAIMS = "aihub.consoleClaims";
    private static final PathPattern GUARDED = new PathPatternParser().parse("/api/**");
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!GUARDED.matches(new ServletRequestPathUtils... /* 用 UrlPathHelper.getPathWithinApplication，
                与 InternalAuthFilter 完全一致的取路径方式 */)) {
            chain.doFilter(request, response);
            return;
        }
        if (properties.secret().isBlank()) {
            // D16：平台配置故障**不伪装**成凭证错误 —— 但门必须是关着的，所以仍然 401（fail-closed），
            // 只有登录接口会额外回 500 CONFIGURATION_ERROR 告诉运维真正的原因。
            writeError(response, ErrorCode.UNAUTHORIZED, "控制台未配置（AIHUB_CONSOLE_SECRET 为空）");
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            writeError(response, ErrorCode.UNAUTHORIZED, "缺少控制台令牌");
            return;
        }
        ConsoleClaims claims;
        try {
            claims = tokenService.verify(header.substring(7).strip());
        } catch (IllegalArgumentException e) {
            writeError(response, ErrorCode.UNAUTHORIZED, "控制台令牌无效或已过期");
            return;
        }
        if (!READ_METHODS.contains(request.getMethod())
                && !ConsoleClaims.ROLE_ADMIN.equals(claims.role())) {
            writeError(response, ErrorCode.FORBIDDEN, "只读角色不能执行写操作");   // 403，admin 信封
            return;
        }
        request.setAttribute(ATTRIBUTE_CLAIMS, claims);
        chain.doFilter(request, response);
    }
}
```

`ConsoleAuthService.login` 用 `new BCryptPasswordEncoder().matches(raw, stored)`（**口令哈希是本里程碑唯一不能自己实现的东西**）；`status != ACTIVE` 按 401 处理。**防用户枚举要做完整**：用户名不存在时**也要**对一个固定的假哈希跑一次 `matches`（类里放一个常量 dummy bcrypt hash，例如 `$2a$10$........`），否则两条路径的耗时差约 100 ms —— 响应体一样但**时序**照样能枚举用户（评审点名的一条）。

**`aihub.console.secret` 的长度下限（E.4-(b)，必须在这里落地，不能只写在附录里）**：空串按 D16 fail-closed；**长度 < 32 字符时同样拒绝登录**并回 `CONFIGURATION_ERROR`，启动时打一条 WARN（HMAC 密钥太短 = 可离线爆破 = 管理台等于没有鉴权；与 `AesGcmChannelCipher` 在构造期拒绝 16 字节主密钥是同一纪律）。**必须有一条短密钥的用例**：`secret = "short"` → 登录 500 + `CONFIGURATION_ERROR`，且 `/api/**` 一律 401。

**关于 Task 7 的耦合**：本任务先落地登录与过滤器，**审计调用留到 Task 7**；如果 Task 7 已经完成，就把 `auditService.record(...)` 的调用一起写上。**不允许**写「此处待 Task 7 收口」这类占位注释——要么不写，要么写真实的调用。

`application.yml` 增加：

```yaml
aihub:
  console:
    # 控制台令牌的签名密钥：**只来自环境变量，刻意没有默认值**（空 = 门关着，见 D16）。
    secret: ${AIHUB_CONSOLE_SECRET:}
    token-ttl: 2h
```

- [ ] **Step 4: 跑它确认通过并提交**

Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ConsoleLoginIntegrationTest,ConsoleAuthFilterTest,InternalAuthFilterContextPathTest,InternalConfigSnapshotIntegrationTest"`
Expected: 全绿 + `BUILD SUCCESS`（**最后两个类必须一起跑**：它们是「既有 `/internal/**` 契约没被破坏」的证据）。

⚠️ **还要再跑一遍「顺序反转」，否则这次绿色不算数**（附录 G3 的纪律）：
`mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=InternalAuthFilterContextPathTest,MeteringConsumerIntegrationTest" "-Dsurefire.runOrder=reversealphabetical"`
Expected: 全绿。**「我的用例绿了」只有在类顺序被翻转后依然绿才算证据** —— 顺序条件绿与 `-Dtest=A+B`、`failIfNoSpecifiedTests` 属同一类假绿。另外 `mvn -B clean test -pl aihub-admin/aihub-web -am` 的全量绿色也只在顺序未被打乱时成立，不许把它当成「上下文互不干扰」的证明。

```bash
git add aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/SysUserEntity.java \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/SysUserMapper.java \
        aihub-admin/aihub-common/src/main/java/com/aihub/common/api/ErrorCode.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/console/ \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/config/ConsoleProperties.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/ \
        aihub-admin/aihub-web/src/main/resources/application.yml \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/error/GlobalExceptionHandler.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/metering/MeteringConsumerIntegrationTest.java
git commit -m "feat(console): add console login and the /api/** token filter with two roles"
```

**验收判据：** 登录签发令牌；`/api/**` 无令牌/坏令牌 401、只读角色写操作 403（**都是 admin 信封**）；secret 为空时门是关的且登录回 `CONFIGURATION_ERROR`；`/healthz`、`/internal/**`、`/console/**` 行为不变。
**RED 证据：** D16 的用例在「secret 为空就放行」的实现下红；`viewerRoleMayReadButNotWrite` 在「只验签名不验角色」的实现下红。

---

## Task 7: 审计服务（与业务同事务）

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/audit/AuditAction.java`（常量类）
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/audit/AuditService.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/audit/AuditServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `AuditLogMapper`（Task 1）
- Produces:
  - `AuditService.record(Long tenantId, Actor actor, String action, String targetType, String targetId, Map<String,Object> detail) : void`（`Actor` = record `(String type, String id)`，`USER`/`SYSTEM`）
    - ⚠️ **E.3-3 的裁定（2026-09-29，执行 Task 7 时定死）**：`tenantId` 是装箱 **`Long`** 而不是 `long`。`audit_log.tenant_id` 是 `BIGINT NULL`，`AuditLogEntity.tenantId` 本来也是 `Long` —— 所以这个选择**不需要改实体或迁移**。而 `LOGIN_FAILURE`（用户名不存在）这类事件**真的没有租户上下文**：传 `null` 写 SQL NULL，**不许用 `0` 当哨兵**（`0` 与真实 id 空间无法区分，id 从 1 开始；M2 的 `request_log.tenant_id = 0` 哨兵是那一列 `NOT NULL` 逼出来的，不是更优解）。**必须有一条用例**：传 `null` 落库后读回来**仍然是 NULL**（不是 0）。
    - ⚠️ `record` **不加** `@Transactional(REQUIRES_NEW)`：它必须加入调用方的事务，否则「改了但没审计」与「审计了但业务回滚」都会发生（D8）。
  - `AuditAction` 常量：`TENANT_CREATE/UPDATE`、`API_KEY_CREATE/DISABLE/ENABLE/DELETE`、`CHANNEL_CREATE/UPDATE/DELETE/ROTATE_KEY`、`ROUTE_CREATE/UPDATE/DELETE`、`RATE_LIMIT_CREATE/UPDATE/DEACTIVATE`、`QUOTA_UPDATE`、`LOGIN_SUCCESS/LOGIN_FAILURE`、`RECONCILE_REPORT`

- [ ] **Step 1: 写失败测试**

```java
@Test
void recordWritesAnAuditRowInTheSameTransactionAsTheBusinessWrite() {
    // 用**本任务自己的**一个 @Transactional 测试服务（不要借 Task 8 的 ChannelAdminService ——
    // 那会让 Task 7 依赖 Task 8，而 Task 7 排在前面）：
    // 它先写一行 audit_log，再抛异常；断言业务回滚时审计行也一起消失。
    // ⚠️ `selectCount(null)` 是**全表计数**，而 audit_log 与 testcontainers 容器都是 JVM 级共享的
    // （Task 1 的迁移用例、Task 8/9/10 的写路径都会往里插行）—— 断言「等于 0」会因为"谁先跑"而红（N12）。
    // 所以断言**增量**：先记基线，再断言回滚之后没有新增。
    long before = auditLogMapper.selectCount(null);
    assertThatThrownBy(() -> transactionalProbe.writeAuditThenFail()).isInstanceOf(IllegalStateException.class);
    assertThat(auditLogMapper.selectCount(null)).as("业务回滚，审计必须一起回滚").isEqualTo(before);
}

@Test
void theAuditDetailNeverContainsSecrets() {
    // 用一眼可辨的合成值，直接调 AuditService（不经任何业务服务）：
    auditService.record(1L, new Actor("USER", "1"), AuditAction.CHANNEL_CREATE, "CHANNEL", "42",
            Map.of("name", "probe", "weight", 100, "apiKeyCipher", "v1:cipher-synthetic",
                    "apiKey", "sk-console-plaintext-synthetic"));
    String all = auditLogMapper.selectList(null).stream()
            .map(AuditLogEntity::getDetail).collect(joining("|"));
    assertThat(all).as("审计只记非敏感字段：明文/密文/口令/令牌一律不许出现")
            .doesNotContain("sk-console-plaintext-synthetic").doesNotContain("cipher-synthetic").doesNotContain("v1:");
}
```

> `AuditService.record` 里的 `MAPPER.writeValueAsString` 抛的是**受检** `JsonProcessingException`：必须显式 `catch` 并转成 `IllegalStateException`（审计失败要让业务回滚，不能静默吞掉，也不能让受检异常从签名里漏出去）。

- [ ] **Step 2: 跑它确认失败** → `cannot find symbol: class AuditService`

- [ ] **Step 3: 实现**

```java
@Service
public class AuditService {
    /**
     * 写一条审计行。**必须在调用方的事务里**（不加 @Transactional(REQUIRES_NEW)）：
     * 「改了但没审计」是不可接受的，所以审计失败要让业务一起回滚。
     * detail 只放**非敏感**字段（如渠道名、权重、状态），**绝不放**明文密钥、密文、口令、令牌。
     */
    public void record(Long tenantId, Actor actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        AuditLogEntity row = new AuditLogEntity();
        row.setTenantId(tenantId);
        row.setActorType(actor.type());
        row.setActor(actor.id());
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setDetail(detail == null || detail.isEmpty() ? null : MAPPER.writeValueAsString(detail));
        auditLogMapper.insert(row);
    }
}
```

- [ ] **Step 4: 跑它确认通过并提交**

Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=AuditServiceIntegrationTest"` → `Tests run: 2, Failures: 0`

```bash
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/audit/ \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/audit/
git commit -m "feat(audit): record audit rows in the business transaction"
```

**验收判据：** 业务回滚时审计一起回滚；审计 detail 不含任何密钥/口令/密文/令牌。
**RED 证据：** 用 `REQUIRES_NEW` 实现时第一条红；把明文密钥写进 detail 时第二条红。

---

## Task 8: 租户 + 渠道 CRUD（含加密写入与密钥轮换）

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/tenant/TenantAdminService.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/channel/ChannelAdminService.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/TenantController.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/ChannelController.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ChannelAdminIntegrationTest.java`
- Modify: `aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigChangePublisher.java`（Task 2 建的类，本任务给它加 `publishAfterCommit(String reason)`，见下方机制说明）
  - ⚠️ **这条是 2026-09-30 补的，补的是一个会产出「编译不过的提交」的缺陷**：本任务正文早就写着「本任务的 Files **必须**列上它」，但 Files 清单与下方 `git add` 清单里**都没有**这个路径。若照原样执行，提交里会包含**调用** `publishAfterCommit` 的新文件、却不含**定义**它的改动 ⇒ 提交出来的树编译不过（同一类缺陷在 M4 计划第三轮评审里被记为 F1/F2）。

**Interfaces:**
- Consumes: `ChannelKeyService`（**复用既有加密入口，先读它的签名，不要新写一份加密**）、`ConfigChangePublisher`（Task 2）、`AuditService`（Task 7）、`ChannelMapper`/`TenantMapper`（既有）
- Produces:
  - `POST /api/tenants`、`GET /api/tenants`、`PUT /api/tenants/{id}`
  - `POST /api/channels`、`GET /api/channels`、`GET /api/channels/{id}`、`PUT /api/channels/{id}`、`DELETE /api/channels/{id}`、`POST /api/channels/{id}/rotate-key`
  - 每个写操作：业务写 + 审计 + `configChangePublisher.bumpAndPublish("channel.update")`（**同一个事务提交之后**发布，见 Task 2 的说明）
  - **任何 GET 的响应 DTO 都不含 `apiKeyCipher`**（`ChannelView` 只暴露 `keyVersion` 与 `hasKey`）

- [ ] **Step 1: 写失败测试**

```java
@Test
void creatingAChannelEncryptsTheKeyAndNeverReturnsTheCipher() {
    var res = post("/api/channels", Map.of("name", "m4-a", "provider", "openai-compatible",
            "baseUrl", "http://127.0.0.1:1", "apiKey", "sk-console-plaintext-synthetic"));
    assertThat(res.statusCode()).isEqualTo(200);
    Long id = jsonPath(res, "$.data.id");

    ChannelEntity row = channelMapper.selectById(id);
    assertThat(row.getApiKeyCipher()).startsWith("v");                     // v{n}:{b64}
    assertThat(row.getApiKeyCipher()).doesNotContain("sk-console-plaintext-synthetic");
    assertThat(row.getKeyVersion()).isEqualTo(channelKeyService.currentKeyVersion());
    assertThat(res.body()).doesNotContain("sk-console-plaintext-synthetic").doesNotContain(row.getApiKeyCipher());
    assertThat(get("/api/channels").body()).doesNotContain(row.getApiKeyCipher());
}

@Test
void rotatingTheKeyReEncryptsToTheCurrentMasterKeyVersion() { /* rotate-key → key_version 前进、密文前缀跟着变 */ }

@Test
void aMalformedModelsJsonIsRejectedWithInvalidParam() { /* "not-json" → 400 + code=INVALID_PARAM（D14） */ }

@Test
void everyWritePublishesAnInvalidationMessage() {
    // 用真 Redis 订阅，断言收到 channel.create / channel.update（**正向**）。
    // ⚠️ 只写正向是不够的（Task 2 的独立评审把这一条标成里程碑级的洞）：**必须同时有反向用例**
    // —— 见下面的 rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark。两条一起才构成
    // 「提交后才发布」这个不变量；只有正向时，「发布发生在事务里」也能全绿。
}

@Test
void rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark() {
    // 造一个**必然回滚**的写：服务层方法写完渠道后抛异常 → 事务回滚。
    long watermarkBefore = configVersionMapper.current();
    long channelsBefore = channelMapper.selectCount(null);
    // ⚠️ 2026-09-30 修正：**不许**为测试在生产服务上加 `createThenFail` 这种「只有测试会用」的方法。
    // 改用**测试树里的**探针 bean（嵌套 @TestConfiguration）调用真实的 `ChannelAdminService.create(...)`
    // 之后在同一事务里抛异常。两条纪律（Task 7 的评审逐条换来的）：探针必须是**真的被 Spring 代理**的 bean；
    // 本用例自己**不许**标 `@Transactional` —— 否则测试自己的事务成了边界，回滚什么也证明不了。
    assertThatThrownBy(() -> rollbackProbe.createChannelThenFail(request)).isInstanceOf(IllegalStateException.class);
    // ① 频道上**一条消息都没有**（用真 Redis 订阅 + 有界等待，等不到才是通过）；
    assertThat(received.poll(2, TimeUnit.SECONDS)).as("回滚的写绝不许发布失效消息").isNull();
    // ② 水位**没有被抬高**（发布路径里的 raiseTo 也不许跑）。
    assertThat(configVersionMapper.current()).isEqualTo(watermarkBefore);
    // ③ 渠道也没落库（证明这次写真的回滚了，否则上面两条是假绿）。
    // ⚠️ 2026-09-30 修正：原写 `isZero()` —— 那是**全表计数**，而渠道表与 Testcontainers 容器都是 JVM 级共享的
    // （`DemoChannelSeeder` 与其它用例都会插渠道），断言「等于 0」会因为「谁先跑」而红（N12 的同一课，Task 7 已踩过）。
    // 改成断言**增量**：回滚之后渠道数必须不变。
    assertThat(channelMapper.selectCount(null)).as("渠道数必须保持不变（回滚了才没有新增）").isEqualTo(channelsBefore);
}
```

> ⚠️ **机制必须在这里定死（Task 2 的评审要求「先命名机制」）**：Task 8 给 `ConfigChangePublisher`（Task 2 建的类，**本任务的 Files 必须列上它**）加一个方法
> `void publishAfterCommit(String reason)`：若 `TransactionSynchronizationManager.isSynchronizationActive()`，
> 就 `registerSynchronization(new TransactionSynchronization() { public void afterCommit() { bumpAndPublish(reason); } })`；
> **没有活动事务时直接 `bumpAndPublish(reason)`**（并把这个分支写进 javadoc）。
> 为什么必须 `afterCommit` 而不是在事务里调：`bumpAndPublish` 自己会**写数据库水位**；在事务里调、随后回滚，会让「已广播的版本 V」高于「持久水位」，而 Task 4 收到 V 之后会把网关的水位抬到 V —— 于是**真实但更旧的**快照会被 `writeRedis`/lastGood 双双拒绝，共享条目一直是空的、每个实例每轮 TTL 都要回源 admin，直到某次成功的写超过 V 才自愈（有界、能自愈，但**静默**）。Task 9/10 的写路径一律调 `publishAfterCommit(...)`，不许直接调 `bumpAndPublish`。

- [ ] **Step 2: 跑它确认失败** → 404（`/api/channels` 未映射）

- [ ] **Step 3: 实现**：服务层用 `@Transactional`；控制器只做 DTO ↔ 服务的转换与 `ApiResponse.ok(...)`；`models_json` 用 Jackson 校验（D14：**展示用**，不参与路由）。

- [ ] **Step 4: 跑它确认通过并提交**

Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ChannelAdminIntegrationTest"`

```bash
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/tenant/ \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/channel/ChannelAdminService.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigChangePublisher.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/TenantController.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/ChannelController.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ChannelAdminIntegrationTest.java
git commit -m "feat(console): tenant and channel CRUD with encrypted keys and invalidation"
```

**验收判据：** 渠道密钥以密文落库、任何响应/列表都不含密文或明文；轮换后 `key_version` 前进；每次写都广播失效；`models_json` 非法被 400 拒。
**RED 证据：** `creatingAChannelEncryptsTheKeyAndNeverReturnsTheCipher` 在「原样存明文」或「列表带密文」的实现下红。

---

## Task 9: API Key 管理 + 吊销显式 `DEL`

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyAdminService.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/ApiKeyController.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ApiKeyAdminIntegrationTest.java`
- Modify: `aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyView.java`（**删掉已无生产调用方的 `UNUSABLE`**，见 I10②）
- Modify: `aihub-admin/aihub-common/src/test/java/com/aihub/common/apikey/ApiKeyToolingTest.java`（同步改掉引用它的断言）
- Modify: `aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyService.java`
  - ⚠️ **这条是 2026-09-30 补的，补的是一个「照原样执行必然出错」的缺陷**：正文第 1500 行要求「密钥值的生成只能有一个实现 … 把那段逻辑提升成 `ApiKeyAdminService` 也能调的同一个入口」，但原 Files 清单与 `git add` 清单里**都没有**这个路径 —— 照原样执行只有两种下场：写第二份生成逻辑（违反正文，且会被评审判为重复实现），或者提交出一棵调用不存在入口的树。改法见下方「控制器裁定」第 2 条。
- Modify: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ApiKeyEntity.java`
  - ⚠️ **同样是 2026-09-30 补的**：Step 3 的列表 DTO 带 `lastUsedAt`，而 `ApiKeyEntity` **没有**这个字段（现有字段只有 id/keyId/tenantId/keyHash/name/status/expireAt），原清单漏了它。字段必须是 `LocalDateTime` + 读侧显式按 UTC 折算（`docs/CONVENTIONS.md` §7 第 1 条）。

**Interfaces:**
- Consumes: `ApiKeyHasher`（**共用的唯一哈希实现**）、`ApiKeyCacheCodec.CACHE_KEY_PREFIX`（**复用常量，不要写字面量**）、`StringRedisTemplate`、`AuditService`
- Produces:
  - `POST /api/api-keys`（响应 `data.plaintextKey` **仅此一次**）、`GET /api/api-keys`、`POST /api/api-keys/{id}/disable`、`POST /api/api-keys/{id}/enable`、`DELETE /api/api-keys/{id}`
  - `ApiKeyAdminService.create(ApiKeyCreateRequest, Actor) : ApiKeyCreated`、`list(long tenantId) : List<ApiKeyView>`、`disable(long apiKeyId, Actor)`、`enable(long apiKeyId, Actor)`、`delete(long apiKeyId, Actor)` —— 三个写方法都要：改状态 + 审计 + **`redis.delete(ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash)`**（**不再有 `revoke(long, String)` 这个签名**，见 N6 的处置：以 Step 3 里定死的那一组为准）
    - **每个写方法都要发布失效**（D11：API Key 的停用/启用/删除也必须广播）：调 `configChangePublisher.publishAfterCommit("apikey.disable")` 这一族 reason，**不许直接调 `bumpAndPublish`**（机制与理由见 Task 8 的定死说明）。因此**2026-09-30 更正**：这句原先写「本任务的 Files 必须列出 `aihub-service/.../config/ConfigChangePublisher.java`」——那是 Task 8 之前的口径；`publishAfterCommit(String)` **已由 Task 8（`409c889`）落地**且两个分支各有用例钉住，本任务只是**调用**它，因此**不要**修改、也不要 stage 那个文件。Task 2 的独立评审点名：Task 9 的 Interfaces 此前**完全没提**发布，这就是那条要求漏掉的地方。

- [ ] **Step 1: 写失败测试**

```java
@Test
void creationReturnsThePlaintextExactlyOnceAndNeverAgain() {
    var created = post("/api/api-keys", Map.of("tenantId", 1, "name", "m4-key", "validDays", 30));
    String plaintext = jsonPath(created, "$.data.plaintextKey");
    assertThat(plaintext).matches("ak_[a-z0-9]{16}\\.[A-Za-z0-9_-]{43}");

    assertThat(get("/api/api-keys").body()).doesNotContain(plaintext)
            .doesNotContain(ApiKeyHasher.hash(plaintext.substring(plaintext.indexOf('.') + 1)));
}

@Test
void disablingAKeyDeletesItsSharedCacheEntryImmediately() {
    // 先让网关的共享缓存里有它（直接写一条 aihub:apikey:<hash>），再 disable
    redis.opsForValue().set(ApiKeyCacheCodec.CACHE_KEY_PREFIX + hash, payload);
    post("/api/api-keys/" + id + "/disable", Map.of());

    assertThat(redis.hasKey(ApiKeyCacheCodec.CACHE_KEY_PREFIX + hash))
            .as("D11：吊销必须显式 DEL 共享条目（M4 之前只有 TTL）").isFalse();
}

@Test
void disablingAnUnknownKeyIs404AndTheAuditRowRecordsTheActor() { }
```

- [ ] **Step 2: 跑它确认失败** → 404 / `redis.hasKey` 仍为 true

- [ ] **Step 3: 实现**（**签名先定死**，不要留到实现时再决定）：

```java
public record ApiKeyCreateRequest(long tenantId, String name, Integer validDays) {}
public record ApiKeyCreated(long id, String keyId, String plaintextKey) {}     // 明文**只有这一个出口**
// ⚠️ 2026-09-30 控制器更正：本行原名叫 `ApiKeyView`，与 aihub-common 的**跨服务类型**
// `com.aihub.common.apikey.ApiKeyView`（6 段，被 ApiKeyCacheCodec 与 gateway 共用）**同名**，
// 而本 record 与 `ApiKeyService` **同包** —— 在同一个包里再声明一个 `ApiKeyView` 之后，
// `ApiKeyCacheCodec.encode(...)` 的实参类型就极易混错。**改名 `ApiKeySummary`**；
// service / web 包下**不许**再出现任何叫 `ApiKeyView` 的类型。
public record ApiKeySummary(long id, String keyId, long tenantId, String name, String status,
                            Instant expireAt, Instant lastUsedAt) {}            // 无明文、无哈希

@Service
public class ApiKeyAdminService {
    ApiKeyCreated create(ApiKeyCreateRequest request, Actor actor);
    List<ApiKeySummary> list(long tenantId);
    void disable(long apiKeyId, Actor actor);
    void enable(long apiKeyId, Actor actor);
    void delete(long apiKeyId, Actor actor);
    /** 共享层的即时失效：前缀必须用 `ApiKeyCacheCodec.CACHE_KEY_PREFIX`（**不许写字面量**）。 */
    private void evictSharedCache(String keyHash) { redis.delete(ApiKeyCacheCodec.CACHE_KEY_PREFIX + keyHash); }
}
```

- **密钥值的生成只能有一个实现**：现有格式已被固定向量钉死（`ak_` + 16 位小写字母数字 + `.` + 32 字节 URL-safe base64）。**先读 `ApiKeyMintRunner` / `ApiKeyService` 今天是怎么造的，把那段逻辑提升成 `ApiKeyAdminService` 也能调的同一个入口** —— 不是重写一份，也不是"看起来一样"的第二份。
- **顺带清掉 D4 留下的死代码**：`aihub-common` 的 `ApiKeyView.UNUSABLE` 在 D4 之后**没有任何生产调用方**（过滤器改用 `AdminResolution.unavailable()` 了）。本任务把它**删掉**，并同步改 `ApiKeyToolingTest` 里引用它的断言（那是共享类型上的一块死代码，附录 A8 要求 M4 给个结论）。删不干净就说明还有调用方 —— 那就**保留并说明为什么**，但要在报告里给出结论，不许悬着。

- [ ] **Step 4: 跑它确认通过并提交**

Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ApiKeyAdminIntegrationTest"`

```bash
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyAdminService.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/apikey/ApiKeyService.java \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/ApiKeyEntity.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/ApiKeyController.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ApiKeyAdminIntegrationTest.java \
        aihub-admin/aihub-common/src/main/java/com/aihub/common/apikey/ApiKeyView.java \
        aihub-admin/aihub-common/src/test/java/com/aihub/common/apikey/ApiKeyToolingTest.java
# 2026-09-30：ApiKeyService.java（生成入口单点化）与 ApiKeyEntity.java（lastUsedAt 字段）是控制器补进清单的；
# ConfigChangePublisher.java **不在**清单里 —— publishAfterCommit 已由 Task 8 落地，本任务只调用它。
git commit -m "feat(console): API key management with explicit shared-cache eviction on disable"
```

**验收判据：** 明文只出现一次；列表不含明文与哈希；停用后共享缓存条目**立即**消失；审计记到 actor。
**RED 证据：** `disablingAKeyDeletesItsSharedCacheEntryImmediately` 在只改状态不 `DEL` 的实现下红——这正是 M3 决策 16 交接的那一条。

> **控制器裁定（2026-09-30，开 Task 9 之前通读正文后补；下面 6 条是「照字面执行会出错」的计划缺陷与定死的机制，实施者按此执行）**
>
> 1. **列表 DTO 改名 `ApiKeySummary`**（Step 3 的注释里写了原因）：原 `ApiKeyView` 与 `aihub-common` 的**跨服务类型**同名，而新 record 与 `ApiKeyService` **同包** ⇒ 会让 `ApiKeyCacheCodec.encode(...)` 的实参类型混错。service / web 包下**不许**再出现叫 `ApiKeyView` 的类型；共享类型（`aihub-common`）一个字都不改（除了第 6 条的删常量）。
> 2. **生成入口只能有一个**：在 `ApiKeyService` 上**新增**
>    `public IssuedKey issue(long tenantId, String tenantName, String keyName, Instant expireAt)`，
>    把今天 `mint` 里的「`newKeyId` + `newSecret` + `hash` → 组实体 → `insert` → 回填缓存 → 返回 `IssuedKey`」整段**原样搬进去**；
>    现有的 `mint(tenantName, keyName, expireAt)` 保留签名，先 `findOrCreateTenant(tenantName)` 再**委托**给 `issue(...)`。
>    `ApiKeyAdminService.create` 按 `request.tenantId()` 查 `TenantMapper`（查不到 → 404 `NOT_FOUND`），再调 `apiKeyService.issue(...)`。
>    **不许**在 `ApiKeyAdminService` 里另写一份生成逻辑。密钥格式（`ak_` + 16 位 `[a-z0-9]` + `.` + 43 位 URL-safe base64）已被 `ApiKeyToolingTest` 的固定向量钉死，**一个字节都不许变**。`issue` 要带 `@Transactional`（`ApiKeyAdminService` 经代理调用它，事务才生效）。
> 3. **`lastUsedAt` 的来源与诚实登记**：`api_key.last_used_at` 列存在（`V1__init_schema.sql:36`），但**今天没有任何代码写它**，且 `ApiKeyEntity` 没有该字段 ⇒ Files 新增 `ApiKeyEntity.java`，字段用 `LocalDateTime`，读侧显式 `toInstant(ZoneOffset.UTC)`（`docs/CONVENTIONS.md` §7 第 1 条；**不要**用 `Instant` 字段）。**该值在 M4 里恒为 NULL** —— 不要为了让它有值而在读路径写库；在报告里登记「列存在但无写入方」。
> 4. **三个写方法的语义**：`disable` → `status=DISABLED`；`enable` → `status=ACTIVE`；`delete` → **真删行**（`apiKeyMapper.deleteById`）。三者都要：审计（`target_type="API_KEY"`、`tenant_id` = **该 key 的租户 id**、`target_id` = 数值主键）+ `DEL` 共享缓存条目 + `publishAfterCommit("apikey.disable" / "apikey.enable" / "apikey.delete")`，且都在**同一个 `@Transactional`** 里。状态字面量用服务内的私有常量（共享类型只有 `STATUS_ACTIVE`，**不要**为了 `DISABLED` 去改跨服务契约）。
> 5. **`DEL` 在事务方法体内做，且失败必须吞**：`redis.delete(...)` 放在事务体内（**不是** afterCommit）。方向理由要写进注释：若事务随后回滚，最坏结果只是「多一次缓存未命中、下次从 MySQL 回填」，**不可能**给出错误答案；反过来放在 afterCommit 会让回滚路径留下一条「已 ACTIVE 但缓存已被删」之外的更坏形态（`DEL` 在提交后失败则缓存继续放行一把已停用的 key）。Redis 不可用时 `delete` 会抛 ⇒ 必须 `try/catch` + WARN + 继续提交，**绝不**让控制面的写因为缓存清理失败而失败（网关侧靠 TTL 兜底）。`key_hash` 从实体读，前缀一律用 `ApiKeyCacheCodec.CACHE_KEY_PREFIX`。
> 6. **删 `UNUSABLE` 之前先自证**：repo-wide `grep -rn UNUSABLE` 今天只剩它的声明 + `ApiKeyToolingTest`（控制器已核，**无生产调用方**）。删除时必须同时改三处：`ApiKeyView` 的类 javadoc（`:14` 提到它）、`ApiKeyToolingTest` 的 `unusableSentinelIsASingleSharedInstance`（`:238-241`）与它上面那段 javadoc（`:228`）。**删不干净就保留并在报告里说明原因**，不许悬着。`aihub-common` 的 main 作用域**必须保持零第三方依赖**。
> 7. **测试落地形状**：Step 1 的 `post` / `get` / `jsonPath` / `var created` / `payload` 都是**行为伪代码**，必须落成真实的 `TestRestTemplate` + 显式 `HttpEntity` + `Bearer` 令牌（照抄 `ConsoleLoginIntegrationTest` 与 `ChannelAdminIntegrationTest`）。预置 Redis 的那一条必须用**真的** `ApiKeyCacheCodec.encode(view)`（不能是未定义的 `payload`）。夹具名每条用例唯一、前后各清一次（Testcontainers 容器是 JVM 级共享的）。**优先**只声明 `aihub.console.secret`（值与 `ConsoleLoginIntegrationTest.SECRET` **逐字相同**）且**不加** `@Import`/`@TestConfiguration` —— 这样 Spring 有机会**复用**那个已存在的上下文，而不是再 fork 一个（Task 8 刚刚为多出的上下文代价登记过 `CONVENTIONS` §8 item 6）；若确实 fork 了新的，在报告里如实说明并给出把代价写进 §8 的一句话。
> 8. **RED 的顺序**：测试里若引用 `ApiKeyAdminService` / `ApiKeySummary` 等尚不存在的类型，静态类型下「先写测试 → 编译 → 跑出 404」不成立。**优先**把测试写成只依赖 HTTP + `ApiKeyMapper`/`TenantMapper` + `StringRedisTemplate` + `aihub-common` 的既有类型（这样它在服务层存在之前就能编译、能红）；若确实无法避免，按 Task 8 的先例先落「仅签名骨架」再跑 RED，并在报告里**明确登记这个偏差**。

---

## Task 10: 模型路由 + 限流策略 CRUD

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/route/ModelRouteAdminService.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/ratelimit/RateLimitPolicyAdminService.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/ModelRouteController.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/RateLimitPolicyController.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/RouteAndRateLimitAdminIntegrationTest.java`

**Interfaces:**
- Produces: `/api/routes`（POST/GET/PUT/DELETE）、`/api/rate-limits`（POST/GET/PUT/DELETE）
- `RateLimitPolicyAdminService.upsert(...)`：写入新策略前**先把同一维度的 ACTIVE 行置为 `INACTIVE`**，这样 M3 决策 17 的「同维度取最后一条」在任何时候都只有一条候选
- **（2026-09-30 控制器补，均为"照字面执行会出错"的缺陷或必须先定死的机制）**
  - **四种 HTTP 方法的语义定死**：`POST /api/routes` 建一条（唯一键冲突 → **400** `INVALID_PARAM`）；`GET` 列出（**全局资源**：任何 `ADMIN` 看全部行，见 `docs/CONVENTIONS.md` §10 的 R1）；`PUT /api/routes/{id}` 改 `weight`/`priority`/`status`；`DELETE /api/routes/{id}` **真删行**（与 Task 9 的 api-keys 一致）。
    `POST /api/rate-limits` = `upsert`（**先停用同维度旧 ACTIVE 行、再插新 ACTIVE 行**，两行操作、**一次**广播）；`GET` 列出（**租户维度资源**：缺省 = 令牌里的 `tenantId`，见 §10 的 R3.2；`api_key_id` 为 NULL 表示**租户级**，查它**必须** `isNull()`）；`PUT /api/rate-limits/{id}` 只改 `qps`/`burst`（该行必须仍是 ACTIVE，否则 404）；`DELETE /api/rate-limits/{id}` = **置 `INACTIVE`（软停用，不删行）** —— 依据是 `AuditAction` 里存在的 `RATE_LIMIT_DEACTIVATE`（**实测**，见下）且"取最后一条"规则需要历史行仍在。
  - **广播 reason 枚举（`publishAfterCommit`，不许 `bumpAndPublish`）**：`route.create` / `route.update` / `route.delete` / `rate_limit.create` / `rate_limit.update` / `rate_limit.deactivate`。**一次写 = 一次广播**（upsert 的两行落库只能发一条）。
  - **审计动作直接用 `AuditAction` 里已有的常量**（**实测**：`ROUTE_CREATE` / `ROUTE_UPDATE` / `ROUTE_DELETE` / `RATE_LIMIT_CREATE` / `RATE_LIMIT_UPDATE` / `RATE_LIMIT_DEACTIVATE` 全部已存在）⇒ **不许**新增动作常量、**不许**改 `aihub-common`。审计的 `tenant_id`：路由写记 `NULL`（全局资源，R1）、策略写记**该策略的** `tenant_id`（R2）。
  - **取值校验（顺带堵住 Task 8 的 N5 同类缺口）**：`ModelRouteEntity.status` / `RateLimitPolicyEntity.status` 只接受 `ACTIVE` 与 `INACTIVE`（其它一律 400 `INVALID_PARAM`）；`weight` / `priority` / `qps` / `burst` 必须 **≥ 0**（负数 400）。`status` 字面量用**各自服务内的私有常量**，**不要**为了它改跨服务类型（与 Task 9 裁定 4 同款理由）。
  - **路由引用的渠道必须存在**：`model_route.channel_id` **没有外键约束** ⇒ 建/改路由前必须查 `ChannelMapper`，不存在 → **404 `NOT_FOUND`**（否则会产生悬挂路由，快照带着它、网关静默丢掉）。
  - **命名**：service/web 包下**不许**出现与 `aihub-common` 共享类型同名或近义的类型（`ModelRouteDescriptor`、`RatePolicy`、`ApiKeyView` 都是共享类型，**实测**在 `com.aihub.common.config` / `com.aihub.common.apikey` 里）。列表 DTO 落成**服务内的嵌套 `record`**（`RouteSummary` / `PolicySummary`），不额外建文件（同 Task 11 的 F2 处置）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void creatingARouteTwiceForTheSameModelAndChannelIsAConflict() {
    // 唯一键 uk_model_route(model_name, channel_id) 被违反 ⇒ **400 `INVALID_PARAM`**（ErrorCode 里
    // **没有** 409，2026-09-30 实测），且 message 必须可读；必须把底层唯一键冲突**翻译成** BizException，
    // 否则会落到 GlobalExceptionHandler 的兜底分支变成 **500**。
}

@Test
void updatingATenantPolicyDeactivatesThePreviousActiveRowForThatDimension() {
    long first = createTenantPolicy(1L, 10, 20);
    long second = createTenantPolicy(1L, 5, 10);

    assertThat(policyMapper.selectById(first).getStatus()).isEqualTo("INACTIVE");
    assertThat(policyMapper.selectById(second).getStatus()).isEqualTo("ACTIVE");
    assertThat(activeRowsFor(1L, null)).hasSize(1);
    // ⚠️ `createTenantPolicy` / `activeRowsFor` 必须落地为真实夹具；`activeRowsFor(tenant, null)`
    // 表示「租户级」维度（`api_key_id IS NULL`）—— 查它**必须**用 `isNull()`，
    // `eq(column, null)` 恒不成立、会静默返回 0 行（docs/CONVENTIONS.md §7，2026-09-30 复核）。
}

@Test
void aKeyLevelPolicyAndATenantLevelPolicyCoexist() {
    // ⚠️ **不许留空占位**（这条初版是空 `{ }`，与下一段自己的告诫自相矛盾）。必须落地：
    // 维度 = (`tenant_id`, `api_key_id`)，其中 `api_key_id IS NULL` 是租户级、非空是 key 级。
    // ① 建一条租户级 + 一条 key 级 ⇒ **两条都 ACTIVE**（互不打扰）；
    // ② 再 upsert 一次**租户级** ⇒ 只有租户级那条被置 INACTIVE，**key 级那条仍是 ACTIVE**。
}

@Test
void bothWritesPublishAnInvalidationMessage() {
    // **不许留空占位**（Task 2 的评审点名：空 `{ }` 的用例是假绿）。必须落地（控制器 2026-09-30 定死）：
    // ① 建一条路由 → 频道上收到**恰好一条** reason="route.create"，且 message.version() > 0；
    // ② upsert 一条租户级策略（**先停用旧行、再插新行**，两行两次落库）→ 收到**恰好一条**
    //    reason="rate_limit.create"（**一次写 = 一次广播**）：收到第一条之后，在同一**有界窗口**内
    //    再 poll 一次必须是 null（不是 sleep，也不要断言"绝对没有第二条"却给不出窗口）；
    // ③ 订阅后必须先用哨兵 `awaitSubscription(...)` 确认订阅建立，否则第一条真消息会被静默丢掉；
    // ④ 必须断言消息里的 reason 与 version（而不是只断言"收到了东西"），并断言不出现**别的** reason。
    // 反向那一半（回滚不发布）由 Task 8 的 rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark
    // 覆盖，本任务可以只做正向。
}
```

- [ ] **Step 2–4: 失败 → 实现 → 通过**

Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=RouteAndRateLimitAdminIntegrationTest"`

- [ ] **Step 5: 提交**

```bash
# 2026-09-30 控制器更正：原清单第一行是**目录**（`.../service/route/`），目录形式会连带
# stage 意外文件、也违反「只 stage 显式路径」；改为逐个文件（逐条先 Test-Path）。
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/route/ModelRouteAdminService.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/ratelimit/RateLimitPolicyAdminService.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/ModelRouteController.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/RateLimitPolicyController.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/RouteAndRateLimitAdminIntegrationTest.java
git commit -m "feat(console): model route and rate-limit policy CRUD with dimension-unique activation"
```

**验收判据：** 同维度永远只有一条 ACTIVE 策略；两个维度共存（`api_key_id IS NULL` 与 `api_key_id = ?`）；重复路由被拒（**400** + 可读 message，**不是 500**）并给出可读消息；写操作都广播失效（**恰好一条**）；`status`/数值的非法取值与不存在的 `channelId` 都被显式拒绝（400 / 404）；**新增的集成测试类不得让套件的 Spring 上下文从 7 变成 8**（见 `docs/CONVENTIONS.md` §8 item 5/6）。
**RED 证据：** `updatingATenantPolicyDeactivatesThePreviousActiveRowForThatDimension` 在「只插入不停用」的实现下红（两行同时 ACTIVE → M3 的「取最后一条」规则会变成依赖插入顺序的运气）。
⚠️ **（2026-09-30 控制器补）RED 必须落在被测断言上**：Task 9 的教训是它的 4 条 RED **全部**红在 `404`（端点未映射），那种红**不能**证明断言的判别力（判别力只能由变异体提供）。因此：本任务的测试必须只依赖 **HTTP + 既有 mapper/entity**（这样能在服务层存在之前就编译并跑红），并且**每一条行为都必须配一条自己的变异体**证明它能把对应断言打红。

---

## Task 11: 渠道探测 + 请求日志/账单/审计查询

**Files:**
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/BillingDailyEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/BillingDailyMapper.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ChannelProbeService.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/log/RequestLogQueryService.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/LogQueryController.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/log/AuditQueryService.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/AuditController.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/BillingController.java`
- Modify: `ChannelController`（Task 8 的）增加 `POST /api/channels/{id}/probe`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ProbeAndQueryIntegrationTest.java`
- **Modify（2026-09-30 控制器补）**：
  - `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ChannelAdminIntegrationTest.java` —— **探测用例放这里**（它已经持有 `aihub.channel.master-key` + 渠道夹具，探测要解密渠道密钥；放进新文件会 fork **第 8 个** Spring 上下文，见 Interfaces 第 6 条）。
  - ~~`MybatisMapperConfig.java`~~ —— **不需要修改**（2026-09-30 二次更正）：分页改用**显式 `LIMIT/OFFSET`**，不引入 jsqlparser 依赖，因此**该文件保持 pristine、不进本任务的提交**（Interfaces 第 1 条已据此改写）。

**Interfaces:**
- Consumes: `RequestLogMapper`（既有）、`BillingDailyMapper`（本任务新建）、`ChannelKeyService` 的解密入口
- **（2026-09-30 控制器补 —— 4 条"照字面执行会出错 / 与已定死的规范冲突"）**
  1. **⚠️ 分页会静默失效（高危）—— 2026-09-30 二次更正（我上一个版本的前提错了）**：全仓**实测没有任何** `MybatisPlusInterceptor` / `PaginationInnerInterceptor` 用法；**且该类在本仓库的类路径上根本不存在**：MyBatis-Plus 自 **3.5.9** 起把分页拦截器拆到**独立构件** `com.baomidou:mybatis-plus-jsqlparser`（本项目 `mybatis-plus.version = 3.5.17`，`mybatis-plus-spring-boot3-starter` **不传递**它）。⇒ 我先前写的"在 `MybatisMapperConfig` 里加 `@Bean PaginationInnerInterceptor`"**照字面做不到**（要改 `pom.xml` 加依赖）。
     **定死（控制器裁定，2026-09-30）**：**不引入 jsqlparser 依赖**，改用**等效的显式有界分页** —— `selectCount(过滤条件)` + `selectList(过滤条件 + ORDER BY created_at DESC, id DESC + 显式 LIMIT/OFFSET)`，再手工装进 `Page<>(page, size, total)`（**返回类型仍是 `Page`，契约不变**）。`LIMIT/OFFSET` 的值**只能**来自已经校验过的整数（`size ∈ [1,200]`、`page ≥ 0`），**不许**把任何用户字符串拼进 SQL。
     判别力要求不变：**必须**有一条用例证明 `LIMIT` 真的生效（造 N 行、`size=2` ⇒ **恰好 2 条且 `total=N`**），并由"**去掉 `LIMIT/OFFSET` 段 ⇒ 该用例变红**"提供（实测：`expected: 2 but was: 3`）。
  2. **`GET /api/billing/daily` 必须带 `tenantId`**：原文只写 `?from=&to=`，而 `billing_daily` 有 `tenant_id NOT NULL`，且 `docs/CONVENTIONS.md` §10 的 **R3.1** 明文把 `/api/billing/daily` 归为**运营查询**、要求**必须显式 `tenantId`**（缺省 400）。⇒ 改为 `GET /api/billing/daily?tenantId=&from=&to=`，缺 `tenantId` 或时间范围 → **400**。三个查询端点（logs / audit / billing）语义统一。
  3. **`page`/`size` 的上下界与默认值必须钉死**（否则"上界"只是文档承诺）：`size` 缺省 **20**、`>200` **钳到 200**、`<1` → 400 `INVALID_PARAM`；`page` 缺省 **0**、`<0` → 400；`from`/`to` 必须能解析成带 `Z` 的 `Instant`（解析失败 400）、且 `from <= to`（否则 400）。排序固定 `created_at DESC`（`request_log` / `audit_log` 都是分区表，无 `ORDER BY` 的分页没有意义）。
  4. **`ChannelProbeService` 的边界**：渠道不存在 → **404 `NOT_FOUND`**（渠道停用**仍可探测** —— 它是诊断动作，不是数据面调用）；**单次**请求、**超时上限 3 秒**（必须有界，不许无限等）；响应 `{reachable, httpStatus, latencyMs, message}`，**绝不回显密钥**（明文、密文、主密钥都不许），`message` 只放简短非敏感原因；**不写审计**（`AuditAction` 里**没有** PROBE 常量，**不要新增**共享常量）；`ChannelKeyService.decrypt` 返回 `Optional` 且**永不抛** ⇒ 解不开时按"不可达 + 非敏感原因"处理，不要让异常穿到控制器。
  5. **视图里不许出现敏感材料**：`RequestLogView` / `AuditLogView` 都**不含**任何明文密钥、`key_hash`、`api_key_cipher` 密文、主密钥、控制台口令（`audit_log.detail` 是**非敏感字段的变更摘要** —— 写入侧 Task 7/8/9/10 已各自保证；这里只做只读回显，**但必须有用例**断言查回来的响应体里不含本次夹具的明文与 `key_hash`，否则"不含敏感内容"是一句没有牙齿的承诺）。
  6. **Spring 上下文预算（必须仍是 7）**：探测用例要解密渠道密钥 ⇒ 需要 `aihub.channel.master-key`，而该属性只存在于 `ChannelAdminIntegrationTest` 的 `@TestPropertySource`；`@Import` 会把**导入者类**算进上下文缓存键，所以**任何**新测试类都无法与它共享上下文（新写一个带同样属性的类 ⇒ **第 8 个**上下文）。⇒ 定死：**探测的用例写进 `ChannelAdminIntegrationTest`**（改该文件），**`ProbeAndQueryIntegrationTest` 只声明 `aihub.console.secret` 且不加 `@Import`** ⇒ 它与 `ConsoleLoginIntegrationTest` / `Task 9/10` 的集成测试**共用**同一个上下文，套件总数**仍是 7**。跑完**必须实测** `Tomcat started on port` 的次数并写进报告（基线 7）。
- Produces:
  - `ChannelProbeService.probe(long channelId) : ProbeResult`（record：`reachable`、`httpStatus`、`latencyMs`、`message`；**绝不回显密钥**；单次请求，超时上限 3 秒）
  - `RequestLogQueryService.page(long tenantId, Instant from, Instant to, Long apiKeyId, Long channelId, int page, int size) : Page<RequestLogView>`
  - `GET /api/logs?tenantId=&from=&to=&apiKeyId=&channelId=&page=&size=`
    - ⚠️ **时间范围必须绑 `LocalDateTime`(UTC)，不能绑 `Instant`/`Timestamp`**（见 Global Constraints 与
      `docs/CONVENTIONS.md` §7 的实测）：`from`/`to` 是带 `Z` 的绝对时刻，实现里先解析成 `Instant`，
      但在**绑定参数时**必须转成 `LocalDateTime.ofInstant(instant, ZoneOffset.UTC)`。绑 `Timestamp`/`Instant`
      在**连接时区不是 UTC** 时（测试容器的无参数 URL 就是这种方言）会让驱动把边界整体推后一个时区偏移，
      命中的是**另一个窗口**的行（复核实测同一个 ±10 分钟窗口在该方言下 `LocalDateTime` 绑 1/4 行、
      `Timestamp` 绑 3/4 行；在 `serverTimezone=UTC` 的生产方言下两种绑定选中的是**同一个窗口** ——
      2026-09-30 复测 3/4 与 3/4；这个计数依赖 fixture，fixture 与数字见 `docs/CONVENTIONS.md` §7 第 2 条）。
      `request_log` 与 `audit_log` 的 `created_at` 都是 UTC 墙上时间。
    - ⚠️ **可选维度过滤**（`tenantId`、`apiKeyId`、`channelId` 缺省时）**必须**用 `isNull()`/`isNotNull()`
      或条件式构造，**不许**把 `null` 交给 `eq(...)`：那会生成 `col = ?` 且参数是 `null`、恒为 UNKNOWN、
      **静默返回 0 行**（见 `docs/CONVENTIONS.md` §7）。
  - `GET /api/billing/daily?from=&to=`（按 `tenant_id` + `stat_date` 范围）
  - **`GET /api/audit?tenantId=&from=&to=&page=&size=`** + `AuditQueryService.page(...) : Page<AuditLogView>`
    —— **审计是 §12 的 M4 交付物之一，光有写入路径不算交付**（评审点名）。查询同样强制 tenant + 时间范围、分页有上界；`AuditLogView` **不含** `detail` 里的敏感内容（写入侧已经保证不含，这里只做只读回显）。
  - ⚠️ **`RequestLogView` 与 `AuditLogView` 都是嵌套在各自 service 里的 `record`**（`RequestLogQueryService.RequestLogView` / `AuditQueryService.AuditLogView`），**不额外建文件** —— 所以本任务的 `git add` 不需要再加路径（F2：否则「Files 说要新建两个 view」与「add 清单里没有」会互相矛盾）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void probingAChannelReportsReachabilityWithoutLeakingTheKey() {
    upstream.enqueueJson(200, "{\"object\":\"list\"}");           // 宿主夹具（admin 侧可以用 Testcontainers/夹具）
    var res = post("/api/channels/" + id + "/probe", Map.of());
    assertThat(res.body()).contains("\"reachable\":true").doesNotContain("sk-channel-plaintext-synthetic");
}
// ⚠️ 这条用例按 Interfaces 第 6 条**落在 `ChannelAdminIntegrationTest`**（那里才有 `aihub.channel.master-key`），
//    不是落在 `ProbeAndQueryIntegrationTest`。`upstream` / `id` 必须落成真实夹具：进程内 HTTP 假上游 +
//    一条 baseUrl 指向它的渠道（渠道密钥用合成明文，断言里出现的就是它），并且**必须**同时断言
//    `latencyMs >= 0`、**渠道不存在时 404**、以及**失败路径**（上游不回包 ⇒ 有界超时后 `reachable=false`）。

@Test
void logsQueryRequiresATenantAndATimeRange() {
    assertThat(get("/api/logs?page=0&size=10").statusCode()).as("禁止无界扫描（索引在 V2，见 D1）").isEqualTo(400);
    assertThat(get("/api/logs?tenantId=1&from=2026-09-01T00:00:00Z&to=2026-09-30T00:00:00Z").statusCode()).isEqualTo(200);
}
// ⚠️ 同款"缺参即 400"必须对 **`/api/audit` 与 `/api/billing/daily`** 各来一条（三者语义统一，见 Interfaces 第 2 条：
//    billing 也要 `tenantId`）。正例里的 `tenantId=1` 只是个**存在的**夹具租户（日志/账单查询不需要该租户有数据）。

@Test
void logsPagingIsBoundedAndOrderedByCreatedAtDescending() {
    // ⚠️ **不许留空占位**（空 `{}` 的用例是假绿；Task 10 的 Step 1 犯过同一个错）。必须落地：
    //   ① 造 **3 行**本用例独有的 `request_log`（不同 created_at）⇒ `size=2` 时**恰好返回 2 条**、
    //      且 `total == 3`（这条同时是**分页真的加了 LIMIT** 的判别证据：去掉 `LIMIT/OFFSET` 段之后它必须红
    //      —— 没有 LIMIT 时会返回**全表**，正是"无界扫描"；实测红点 `expected: 2 but was: 3`）；
    //   ② 顺序按 `created_at DESC`（断言三条 id 的先后，而不是只断言"有 3 条"）；
    //   ③ `size=201` 被**钳到 200**（断言实际页大小，不是只断言 200 状态码）；`size=0` 与 `page=-1` → **400**。
}
```

- [ ] **Step 2–4: 失败 → 实现 → 通过**

Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ProbeAndQueryIntegrationTest"`

- [ ] **Step 5: 提交**

```bash
# 2026-09-30 控制器补一处 Modify（原清单缺它 = Task 8/9 的"Files 缺路径"缺陷类）：
#   ChannelAdminIntegrationTest 承载探测用例（保持 7 个上下文）。
#   ⚠️ 不要再加 MybatisMapperConfig —— 分页走显式 LIMIT/OFFSET，那个文件保持 pristine（二次更正）。
git add aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/BillingDailyEntity.java \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/BillingDailyMapper.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ChannelProbeService.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/log/RequestLogQueryService.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/log/AuditQueryService.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/AuditController.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/ChannelController.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/LogQueryController.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/BillingController.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ProbeAndQueryIntegrationTest.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ChannelAdminIntegrationTest.java
git commit -m "feat(console): channel probe, request-log paging and daily billing query"
```

**验收判据：** 探测真实打一次上游（**单次、≤3 秒、有界**）并回报可达性与耗时、**响应与方法内都不出现密钥**、渠道不存在时 404；`/api/logs`、`/api/audit`、`/api/billing/daily` **三者都**强制 `tenantId` + 时间范围（缺一即 400）；**分页真的有 `LIMIT`**（`size=2` 对 3 行 ⇒ 恰好 2 条且 `total=3`）；`size` 钳到 200、非法 `page`/`size` 400；`created_at DESC`；视图里不含明文/`key_hash`；**套件 Spring 上下文仍是 7**（探测用例在 `ChannelAdminIntegrationTest`、查询用例与 `ConsoleLoginIntegrationTest` 共用上下文）。
**RED 证据：** `logsQueryRequiresATenantAndATimeRange` 在「允许无界查询」的实现下红（这正是「分区表上全表扫描」的入口）。
⚠️ **（2026-09-30 控制器补）RED 必须落在被测断言上**：Task 9 与 Task 10 的自然 RED **都**全红在 `404`（端点未映射）—— 那种红**不能**证明断言的判别力。⇒ 本任务的测试必须只依赖 **HTTP + 既有 mapper/entity**（这样能在服务层存在之前编译并跑红），并且**每一条行为都必须配一条自己的变异体**证明它能把对应断言打红（尤其：**去掉 `LIMIT/OFFSET` ⇒ 分页用例必须红**，实测 `expected: 2 but was: 3`）。

---
## Task 12: 配额控制面 + Lua 预扣契约

**Files:**
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/quota/QuotaScript.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/quota/QuotaKeys.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/quota/QuotaPeriod.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/quota/QuotaDecision.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/QuotaEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/QuotaMapper.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/quota/QuotaAdminService.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/QuotaController.java`
- Test: `aihub-admin/aihub-common/src/test/java/com/aihub/common/quota/QuotaContractTest.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/quota/QuotaPreDeductionIntegrationTest.java`
- Test（2026-10-01 修复轮补，**缺路径**缺陷类）：`aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/QuotaAdminIntegrationTest.java`
  —— `QuotaController` 的 HTTP 契约（R2/R3.2 租户语义、400、VIEWER 403、审计）。**放在 `console` 包、
  只声明 `aihub.console.secret`（值引用 `ConsoleLoginIntegrationTest.SECRET`）、不加 `@Import`** ⇒ 与既有集成测试共用上下文，**总数仍是 7**。
  ⚠️ **乐观锁冲突经 HTTP 折成 400 这一项被移除并登记为残余**：确定性构造它需要轮询 `information_schema.innodb_trx`，
  而 **Testcontainers 的 MySQL 用户没有 `PROCESS` 权限**（`Access denied`）；服务层行为与 `INVALID_PARAM`→400 的映射分别有覆盖，
  未覆盖的只剩两者组合，属低风险残余（详见 `QuotaAdminIntegrationTest` 里的注释与 `.hb2-logs/T12F-C1-integration.log`）。

**Interfaces:**
- Consumes: `RateLimitScript` 的既有纪律（脚本 + 键布局 + ARGV 顺序只有一份实现）；`StringRedisTemplate`
- Produces:
  - `QuotaKeys.bucketKey(long tenantId, String period) : String` → `"aihub:quota:" + tenantId + ":" + period`
  - `QuotaKeys.ttlMillis(String period, long nowEpochMillis) : long` → 「下个周期开始 + 1 天」减 now
  - `QuotaPeriod.of(long epochMillis) : String`（UTC 的 `YYYYMM`）、`QuotaPeriod.nextPeriodStartMillis(String period) : long`
  - `QuotaDecision(boolean allowed, long remainingTokens, long remainingRequests)`（`-1` 表示该维度不限）
  - `QuotaScript.parse(List<?>)` **必须用 `((Number) raw.get(i)).longValue()`**（Spring Data Redis 的 `DefaultRedisScript<..., List>` 回来的是 `List<Long>`/`Number`，直接强转 `Long` 在某些驱动/版本下会 `ClassCastException`），并且**返回形状不是 3 个元素时抛 `IllegalStateException`**（不是返回 null —— 那会被当成"Redis 不可用"）
  - **限额值的上界校验（F4：这条要求必须自带用例，不能只写在附录里）**：`QuotaScript` 的 ARGV 走 Lua 的 double，**超过 2^53 会丢整数精度**。因此在 `aihub-common` 里加一个纯函数 `QuotaScript.assertWithinRange(long limit) : void`（`limit > 9_007_199_254_740_992L` 时抛 `IllegalArgumentException`；**等于 2^53 是允许的** —— 边界只在这里定义一次，别在两处各写一个 `>`/`>=`），`QuotaAdminService.update` 调用它并把异常折成 `BizException(INVALID_PARAM, …)`；**用例写在 `QuotaContractTest` 里**（纯函数、不需要数据库），即该类的 `Tests run` 从 3 变成 **4**
  - `QuotaScript.SCRIPT : String`、`QuotaScript.keys(long tenantId, String period) : List<String>`、`QuotaScript.args(long estimatedTokens, long tokenLimit, long requestLimit, long ttlMillis) : List<String>`、`QuotaScript.parse(List<?> raw) : QuotaDecision`
  - `QuotaAdminService.getOrCreate(long tenantId, String period)`、`QuotaAdminService.update(long tenantId, String period, long tokenLimit, long requestLimit)`（用 `quota.version` 做乐观锁）

- **（2026-10-01 控制器补 —— 7 条"照字面执行会出错 / 与既有决定冲突"）**
  1. **⚠️ TTL 用例的期望值写错了（差一个月）**：`:1789` 断言 `now + ttl == 2026-11-02T00:00:00Z`（`now = 2026-09-15`、`period = "202609"`），
     而 Interfaces 的文字是「**下个周期开始 + 1 天**」= `2026-10-01` + 1 天 = **`2026-10-02T00:00:00Z`**。
     ⇒ **以 `2026-10-02T00:00:00Z` 为准**（`11-02` 是笔误）；`ttlMillis` 的实现与断言都按这条写。**别为了让 11-02 成立去改实现语义。**
  2. **⚠️ 断言与 Lua 正文不一致**：`:1796` 断言 `QuotaScript.SCRIPT` 含 **`HINCRBY`**，但 `:1873` 的 Lua 正文用的是 **`HSET`**
     （先 `HMGET` 读、脚本内自算、再 `HSET` 写回）。⇒ **保留 Lua 的 `HSET`，把断言改成 `contains("HSET")`**（`HMGET`/`PEXPIRE` 两条不变）。
  3. **⚠️ 乐观锁必须手写，不能依赖 `@Version`**：`quota.version` 的乐观锁若用 MyBatis-Plus 的 `@Version`，
     需要注册 `MybatisPlusInterceptor` + `OptimisticLockerInnerInterceptor` —— 而 **Task 11 已经定死"本仓库不注册 `MybatisPlusInterceptor`"**
     （分页走显式 `LIMIT/OFFSET`）。⇒ **手写**：`UPDATE quota SET token_limit=?, request_limit=?, version=version+1 WHERE tenant_id=? AND period=? AND version=?`，
     **受影响行数为 0 ⇒ 并发冲突**，在该情形下抛 `BizException`（**不许静默覆盖**），并且**要有一条用例钉住它**（两个并发 `update` 各自基于同一版本 ⇒ 恰好一个成功）。
  4. **`QuotaEntity` 的字段必须与 V1 的 10 列逐字对应**（`V1__init_schema.sql:77-90`；2026-10-01 订正：这里原写"9 列"，与紧随其后的 10 个字段自相矛盾）：`id` / `tenantId` / `period`(VARCHAR(8)) /
     `tokenLimit` / **`tokenUsed`** / `requestLimit` / **`requestUsed`** / `version` / `createdAt` / `updatedAt`
     （`uk_quota_tenant_period(tenant_id, period)`；`token_used`/`request_used` 控制面**不写**，但实体要能读出来）。
     `createdAt`/`updatedAt` 与其他实体同纪律（§7：`LocalDateTime` + 显式 UTC）。
  5. **⚠️ 集成测试必须复用默认上下文（套件仍是 7）**：`QuotaPreDeductionIntegrationTest` **不许声明 `@TestPropertySource`、不许 `@Import`**
     —— 它只用 Redis，不需要任何合成密钥。全仓实测有 **15 个**继承 `AbstractIntegrationTest` 且**无属性集**的类
     （`RedisTokenBucketIntegrationTest`/`RequestLogServiceTest`/`SchemaMigrationTest`…）⇒ 它**共用同一个默认上下文**，**不新增**。
     跑完全量必须**实测** `Tomcat started on port` 次数（基线 **7**）。包名用 `com.aihub.admin.quota`（新包，无副作用）。
  6. **并发用例必须是有界等待，不许裸 `get()`**：`:1826` 的 `futures.stream().map(TestSupport::get)` 里 `TestSupport` **未定义**；
     且裸 `Future.get()` 在脚本挂死时会让用例**永远不返回**（Maven 直接超时，比红更糟）。⇒ 用具名的有界等待
     （`get(30, SECONDS)` 或项目既有的 `TestSupport` 形状），**超时即带原因变红**。50 个任务 / 16 线程 / 每次估 100 / 限额 1000 ⇒ 恰好 **10** 次成功（Lua 原子性下是确定的）。
     断言 `redis.opsForHash().get(bucketKey, "tok")` == `"1000"` 要求用 **`StringRedisTemplate`**（序列化器为 String）。
  7. **`git add` 不许写目录**（本项目已两次踩过）：`:1886` 的 `aihub-common/.../common/quota/` **必须展开成 4 个显式文件路径**
     （`QuotaScript.java`、`QuotaKeys.java`、`QuotaPeriod.java`、`QuotaDecision.java`）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void periodIsYyyymmInUtc() {
    assertThat(QuotaPeriod.of(Instant.parse("2026-09-30T23:59:59Z").toEpochMilli())).isEqualTo("202609");
    assertThat(QuotaPeriod.of(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli())).isEqualTo("202610");
}

@Test
void ttlReachesTheDayAfterTheNextPeriodStarts() {
    long now = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli();
    long ttl = QuotaKeys.ttlMillis("202609", now);
    // 2026-10-01T00:00:00Z 是「下个周期开始」，+1 天 = 2026-10-02T00:00:00Z（2026-10-01 控制器订正：原写 11-02 是笔误）
    assertThat(now + ttl).isEqualTo(Instant.parse("2026-10-02T00:00:00Z").toEpochMilli());
}

@Test
void theLuaScriptUsesThePinnedKeyLayoutAndArgOrder() {
    assertThat(QuotaScript.keys(7L, "202609")).containsExactly("aihub:quota:7:202609");
    assertThat(QuotaScript.args(1_000L, 100_000L, 0L, 60_000L)).containsExactly("1000", "100000", "0", "60000");
    // HSET（不是 HINCRBY）：Lua 先 HMGET 读、脚本内自算、再 HSET 写回（2026-10-01 控制器订正）
    assertThat(QuotaScript.SCRIPT).contains("HMGET").contains("HSET").contains("PEXPIRE");
}
```

```java
// QuotaPreDeductionIntegrationTest（真 Redis，Testcontainers）
// 注意：aihub-common **零第三方依赖**，所以那里只有 Lua 文本 + keys/args/parse；把它包成
// Spring 的 RedisScript 是**每一侧自己的事**（与 RateLimitScript 完全同一纪律）。
// 限额**必须由调用方传入**：写死在 helper 里会让「0 = 不限」那条用例自相矛盾。
private QuotaDecision reserve(long tenantId, long estimatedTokens, long tokenLimit) {
    var script = new DefaultRedisScript<>(QuotaScript.SCRIPT, List.class);
    String period = QuotaPeriod.of(Instant.now().toEpochMilli());
    List<?> raw = redis.execute(script, QuotaScript.keys(tenantId, period),
            QuotaScript.args(estimatedTokens, tokenLimit, 0L,
                    QuotaKeys.ttlMillis(period, Instant.now().toEpochMilli())));
    return QuotaScript.parse(raw);
}

@Test
void concurrentPreDeductionsNeverOversellTheBudget() throws Exception {
    // tokenLimit = 1000，每次估算 100 → 只允许 10 次成功。
    // **每个用例用自己的 tenantId**：桶是按 (tenant, period) 建的，共用租户会让用例互相污染。
    long tenant = 101L;
    ExecutorService pool = Executors.newFixedThreadPool(16);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<QuotaDecision>> futures = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
        futures.add(pool.submit(() -> { start.await(); return reserve(tenant, 100L, 1000L); }));
    }
    start.countDown();
    long allowed = futures.stream().map(TestSupport::get).filter(QuotaDecision::allowed).count();

    assertThat(allowed).as("Lua 必须原子：预扣的总量不可能超过预算").isEqualTo(10);
    assertThat(redis.opsForHash().get(QuotaKeys.bucketKey(tenant, QuotaPeriod.of(Instant.now().toEpochMilli())), "tok"))
            .isEqualTo("1000");
}

@Test
void aZeroLimitMeansUnlimitedAndDoesNotBlockAnything() {   // D15
    assertThat(reserve(102L, 999_999L, 0L).allowed()).isTrue();
}
```

- [ ] **Step 2: 跑它确认失败** → `cannot find symbol: class QuotaScript`

- [ ] **Step 3: 实现（Lua 逐字如下，**配额是周期预算，不是速率，所以没有补充逻辑**）**

```lua
-- 配额预扣：一次往返、服务器端原子（与 RateLimitScript 同一纪律）。
-- KEYS[1] = aihub:quota:{tenantId}:{period}   (Hash: tok = 已用 token 估算累计, req = 已用请求数)
-- ARGV    = {estimatedTokens, tokenLimit, requestLimit, ttlMillis}
-- 返回    = {allowed(1/0), remainingTokens(-1 = 不限), remainingRequests(-1 = 不限)}
-- 0 的限额表示**不限**（决策 D15）：M4 上线前所有租户都没有配额行，把 0 当「额度为零」
-- 会让升级瞬间全员 429。
local est          = tonumber(ARGV[1])
local tokenLimit   = tonumber(ARGV[2])
local requestLimit = tonumber(ARGV[3])
local ttl          = tonumber(ARGV[4])

local used = redis.call('HMGET', KEYS[1], 'tok', 'req')
local tokenUsed   = tonumber(used[1]) or 0
local requestUsed = tonumber(used[2]) or 0

local remainingTokens   = -1
local remainingRequests = -1
if tokenLimit > 0 then remainingTokens = math.max(0, tokenLimit - tokenUsed) end
if requestLimit > 0 then remainingRequests = math.max(0, requestLimit - requestUsed) end

if tokenLimit > 0 and (tokenUsed + est) > tokenLimit then
  return {0, remainingTokens, remainingRequests}
end
if requestLimit > 0 and (requestUsed + 1) > requestLimit then
  return {0, remainingTokens, remainingRequests}
end

tokenUsed = tokenUsed + est
requestUsed = requestUsed + 1
redis.call('HSET', KEYS[1], 'tok', tokenUsed, 'req', requestUsed)
redis.call('PEXPIRE', KEYS[1], ttl)
if tokenLimit > 0 then remainingTokens = math.max(0, tokenLimit - tokenUsed) end
if requestLimit > 0 then remainingRequests = math.max(0, requestLimit - requestUsed) end
return {1, remainingTokens, remainingRequests}
```

- [ ] **Step 4: 跑它确认通过并提交**

Run: `mvn -B -pl aihub-admin/aihub-common -am test "-Dtest=QuotaContractTest"` → `Tests run: 4, Failures: 0`（F4：第 4 条就是 `assertWithinRange` 的边界用例）
Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=QuotaPreDeductionIntegrationTest"` → 全绿

```bash
# 2026-10-01 控制器订正：原清单第一行是【目录】，本项目已两次禁止（逐个显式路径）
git add aihub-admin/aihub-common/src/main/java/com/aihub/common/quota/QuotaScript.java \
        aihub-admin/aihub-common/src/main/java/com/aihub/common/quota/QuotaKeys.java \
        aihub-admin/aihub-common/src/main/java/com/aihub/common/quota/QuotaPeriod.java \
        aihub-admin/aihub-common/src/main/java/com/aihub/common/quota/QuotaDecision.java \
        aihub-admin/aihub-common/src/test/java/com/aihub/common/quota/QuotaContractTest.java \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/QuotaEntity.java \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/QuotaMapper.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/quota/QuotaAdminService.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/QuotaController.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/quota/QuotaPreDeductionIntegrationTest.java
git commit -m "feat(quota): add the atomic pre-deduction contract and the quota admin API"

# 2026-10-01 修复轮（评审 3 Important + 2 Minor；控制器执行）——各路径已在上面列出，此处只记提交：
git add aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/QuotaMapper.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/quota/QuotaAdminService.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/QuotaController.java \
        aihub-admin/aihub-common/src/test/java/com/aihub/common/quota/QuotaContractTest.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/quota/QuotaPreDeductionIntegrationTest.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/QuotaAdminIntegrationTest.java
git commit -m "test(quota): cover the request dimension, the HTTP contract and the concurrent first-create race"
```

**验收判据：** 并发预扣**不超发**（真 Redis、16 线程、**有界等待**）；`0` 限额 = 不限；键布局与 ARGV 顺序被固定向量钉住；TTL 覆盖到「下个周期开始 + 1 天」；乐观锁冲突**报错而非静默覆盖**；**套件 Spring 上下文仍是 7**。
**RED 证据：** 把 Lua 拆成「先 HMGET 再 HSET」两次往返（非原子）时 `concurrentPreDeductionsNeverOversellTheBudget` 会红且 `allowed > 10` —— 判别性极强。

---

## Task 13: 网关 `QuotaFilter` + 实际校正

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaFilter.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaResolver.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaEstimator.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaLimiter.java`、`RedisQuotaLimiter.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaCorrector.java`（校正钩子；`ChatRelayController` 在 `doFinally` 里拿到的那个 `MeteringEvent` 直接喂给它）
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaConfigProperties.java`、`QuotaConfig.java`
- Create: **`aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaReservationRegistry.java`**
  （**2026-10-01 控制器补**：`requestId → {tenantId, period, estimatedTokens, degraded}` 的**有界**关联 ——
  `QuotaCorrector.correct(MeteringEvent)` 靠它才能算出 `actual − estimate`；用 Caffeine + 短 TTL，**禁止无界 Map**。
  过滤器写入、校正器**消费并移除**；缺失 ⇒ 跳过 + 计数。见 Interfaces 的 13b 第 2 条。）
- **Modify（2026-10-01 控制器补）**：`aihub-gateway/src/test/java/com/aihub/gateway/relay/ChatRelayControllerTest.java`、
  `.../relay/SseStreamingTest.java`、`.../relay/RelayMeteringFlowTest.java` —— **给 `ChatRelayController` 的构造器加参数
  （`QuotaCorrector`/`QuotaReservationRegistry`）会破坏直接构造它的既有测试**（`ChatRelayControllerTest` 就是直接 new 的）。
  这些改动**必须逐个列出并说明改了哪条断言**（**不许放宽**）；**若你选择把新依赖做成可选（`@Nullable`/默认空实现）以避免改既有测试，必须说明理由**。
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java`（**在 `doFinally` 里、`meteringPublisher.publish(event)` 之后调用 `quotaCorrector.correct(event)`** —— controller 自己看不到 `usage`，别写成「拿到 usage 后校正」，见本节 Step 3 的说明）
- Modify: `aihub-gateway/src/main/resources/application.yml`、`aihub-gateway/src/test/resources/application.properties`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/quota/QuotaFilterTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/quota/QuotaDegradeTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/quota/QuotaCorrectionTest.java`

- **Modify（2026-10-01 控制器补 —— 「额度取值源」缺失，属"Files 缺路径"缺陷类）**：
  额度**必须经过配置快照**才能到网关。实测（`Select-String`）：`aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshot.java:20-22`
  的快照只有 `version/generatedAtEpochMilli/channels/routes/ratePolicies/defaultModel` —— **没有 quota**；
  而 `QuotaAdminService.java:44-46` 自己写着「`config snapshot` 目前**不携带** `quota`（gateway 不连数据库，
  它怎么拿到额度是 **Task 13 的职责**）… 若 Task 13 把额度接进快照，再回来在这里补
  `ConfigChangePublisher.publishAfterCommit("quota.update")`」。⇒ 本任务**必须**包含下列改动（**`aihub-common` 由控制器显式授权可改**）：
  - `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/QuotaDescriptor.java`（**Create**：`record QuotaDescriptor(long tenantId, String period, long tokenLimit, long requestLimit)`）
  - `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshot.java`（**Modify**：+`List<QuotaDescriptor> quotas` + 一个 `Optional<QuotaDescriptor> quota(long tenantId, String period)` 查找）
  - `aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshotCodec.java`（**Modify**：编解码带上 `quotas`；**快照的**格式纪律与既有字段一致，不许悄悄改形状而不动编解码）
  - `aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigSnapshotService.java`（**Modify**：查 `quota` 表并入快照）
  - `aihub-admin/aihub-service/src/main/java/com/aihub/service/quota/QuotaAdminService.java`（**Modify**：`update` 成功后 `ConfigChangePublisher.publishAfterCommit("quota.update")` —— Task 12 留的显式待办）
  - `aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigCache.java`（**Modify**：暴露快照里的额度查询）
  - **⚠️⚠️ `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java`（**Modify，必须**）** ——
    **2026-10-01 独立评审的 Critical-1**：`AdminClient.Http.parseSnapshot` 是**网关唯一的外部入口**，
    它原先用 **6 参便捷构造器**建快照 ⇒ **`data.quotas` 根本没被读取** ⇒ 生产路径上 admin 发得出额度、
    网关解析成**空表**、`ConfigCache.quota(...)` 恒空 ⇒ 静默落回 D15「不限」。评审用**诊断变异**实测证伪
    （夹具带额度后旧实现 `quotas=[]`，见 `.m4t13review-logs/V6-diagnostic.log`）。⇒ **必须在 `parseSnapshot` 里
    遍历 `data.quotas` 并改用 7 参构造器**；并**把契约测试的夹具换成携带额度**（见下条），否则该边界**不可证伪**。
    ⚠️ **控制器登记（我的疏漏）**：这条路径在我 2026-10-01 的折叠里**被漏掉了**（"Files 缺路径"缺陷类**第 6 次**，
    其中**两次是我自己**的）。而且我在折叠里**错误预测**"`AdminClientSnapshotContractTest` 会因此变红" —— 它当时
    **没红**（两侧都是空表 ⇒ `isEqualTo` 恒绿），**这个误判本身掩盖了缺口**。教训：**"改了共享 record 的形状"必须
    把两端（生产 + 契约夹具）一起看**，只跑聚焦用例与只看"期望变红"都不够。
  - 既有契约测试会因此变红/需要更新：`aihub-gateway/src/test/java/com/aihub/gateway/admin/AdminClientSnapshotContractTest.java`（**夹具必须携带额度** —— 这是 Critical-1 的判别力来源）、`aihub-admin/.../config/ConfigSnapshotVersionTest.java`（**如实报告你改了哪些断言、为什么**；**不许放宽**）、
    以及 **`aihub-admin/aihub-web/src/test/java/com/aihub/admin/config/InternalConfigSnapshotIntegrationTest.java`** ——
    它的 `theResponseBodyCarriesExactlyThePathsTheGatewayParserReads` 断言的 `data` 顶层键集合**恰好**是那六个分量，
    加了 `quotas` 就**必然**变红；这是**正确的信号**（它钉住线上形状）⇒ 必须**把 `quotas` 加进期望集合并补该段的定向断言**
    （`quota` 表是 JVM 级共享的，**不许数组计数**，按 `(tenantId, period)` 定向查）。
    ⚠️ **2026-10-01 控制器登记**：这条路径在上面的初次折叠里**被我漏掉了**（"Files 缺路径"缺陷类第 5 次出现，这次是折叠者自己的疏漏）——
    它是在控制器跑 admin 全量时变红才暴露的，说明"只跑聚焦用例"会漏掉**形状级**既有断言。

**Interfaces:**
- Consumes: `QuotaScript`/`QuotaKeys`/`QuotaPeriod`/`QuotaDecision`（Task 12）；`TokenEstimator`（M2 既有，**复用，不新写**）；`GatewayErrors`；`ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW`（取 `tenantId`）
  - ⚠️ **配额是租户级的**：`quota` 表只有 `(tenant_id, period)`（V1 的 `uk_quota_tenant_period`），**没有 `api_key_id`** —— 不要照抄限流的「两维」结构
- Produces:
  - `QuotaFilter`（`@Order(Ordered.HIGHEST_PRECEDENCE + 175)`，**排在鉴权 `+100`、限流 `+150` 之后**，只守 `/v1/**`）
  - 请求属性 `QuotaFilter.ATTRIBUTE_RESERVATION = "aihub.quotaReservation"`，值 `QuotaReservation(long tenantId, String period, long estimatedTokens, boolean degraded)`
  - `QuotaEstimator.estimate(String bodyOrPrompt, Integer maxTokens) : long`。⚠️ **读体的内存上限（`aihub.quota.max-in-memory-bytes`）必须小于计量侧的 `aihub.metering.max-capture-bytes`** —— 否则配额这一层先失败，计量根本看不到这次请求（E.3 的 caveat 4，执行时确认这两个数字的关系并写进配置注释）
  - **两种"配额没生效"的原因必须分开计数**（E.4-(a)）：`aihub.quota.degraded`（Redis 不可用 → 按 D7 放行）与 **`aihub.quota.script_error`**（Lua 返回了非预期形状 / 解析失败 → 同样是放行，但**这是缺陷不是降级**）。把它们合成一个计数器，等于让"脚本写错了"永远藏在"Redis 挂了"后面
  - 超额响应：`429` + `{"error":{"message":…,"type":"insufficient_quota","param":null,"code":"insufficient_quota"}}`（D6）
  - `RedisQuotaLimiter.reserve(long tenantId, long estimatedTokens) : QuotaDecision`、`adjust(long tenantId, String period, long estimate, long actual) : void`

- **（2026-10-01 控制器补 —— 8 条"照字面执行会出错 / 与既有铁律冲突"）**
  1. **⚠️ 额度取值源 = 配置快照，不是数据库**：`QuotaResolver` **只能**从 `ConfigCache` 的快照里取额度
     （见 Files 的 Modify 段；本任务要把额度接进快照）。**不许**直连 MySQL（网关没有该依赖），
     **也不许**每请求回源 admin —— 那是 **Task 14** 的 Redis 不可用**兜底**路径。
  2. **⚠️ 网关上不许出现"真 Redis"测试**（CONVENTIONS §8 item 3：*网关的测试永远不允许依赖 Docker*；
     既有 `ratelimit/RedisRateLimiterTest` 的做法就是**Mockito mock `StringRedisTemplate`**，并在 javadoc 里写明
     "**行为**（脚本真的在 Redis 里原子地…）由 **admin 侧**的真 Redis 集成测试负责"）。
     ⇒ 计划 Step 1 的 `theRealUsageCorrectsTheEstimateBothWays` **直接读 `redis.opsForHash()` 是违规的**，必须改写成：
     - **网关侧**（`QuotaCorrectionTest`）：用 mock 断言**调用约定** —— `adjust` **每请求恰好一次**、
       `actual` 取自 `MeteringEvent.totalTokens()`、传给 Redis 的增量 = `actual − estimate`（**双向**：actual 小于估算时是负数）；
       "桶里的最终值"**不在这一侧断言**。
     - **admin 侧**（真 Redis）：断言**同一个键布局**下"预扣 + 校正增量"的最终值等于真实用量
       （由 Task 12 的 `QuotaPreDeductionIntegrationTest` 所在模块承担；本任务在报告里给出两侧的**对应关系**）。
     只在一侧伪造、另一侧不写，等于把"桶里记的是真实用量"这条判据**悄悄丢掉**。
  3. **⚠️ `TokenEstimator` 的既有签名是 `static int estimate(String content)`**（实测：只有**内容**一个参数、
     返回 `int`）⇒ `QuotaEstimator.estimate(String bodyOrPrompt, Integer maxTokens)` **必须自己**从请求体里
     取出 prompt 文本、再把 `maxTokens` 加上去（复用是复用它的**估算法**，不是"它已经能估算一次调用"）。别猜签名。
  4. **过滤器只守一个端点，且命名与既有对齐**：Step 3 里 `GUARDED.matches(...) && "/v1/chat/completions".equals(...)`
     是**冗余的双重判定**（`/v1/**` 蕴含后者），且 `GUARDED` 未在 Files/Interfaces 里定义（既有的叫
     `RateLimitFilter.GUARDED_PATH`）。⇒ **只保留** `equals("/v1/chat/completions")` 一个判定，并写明理由
     （A13：只有它计费；`/v1/models` 不落 `request_log`）。
  5. **⚠️ 两个内存上限的关系必须实测并写进配置注释**：`aihub.quota.max-in-memory-bytes` **必须小于**
     `aihub.metering.max-capture-bytes`，否则配额层先失败 ⇒ 计量根本看不到这次请求。
     ⇒ 报告里**必须给出这两个数字的实测值与它们的来源**（属性名 + 文件:行），并在注释里写清为什么是 `<`。
  6. **`script_error` 与 `degraded` 的分离必须**有变异体**证明**：把 `QuotaScript.parse` 的
     `IllegalStateException` 折进那个宽泛的 `catch → degraded++` ⇒ `anUnexpectedScriptShapeCountsScriptErrorNotDegrade`
     **必须变红**。只写不测 = 这条要求没有牙齿。
  7. **`QuotaCorrector` 取实际用量**：用 `MeteringEvent` 的**实测**分量 `totalTokens()`（不是 `promptTokens()+completionTokens()`
     自己加，也不是猜名字）；报告里给出该分量的定义处。
  8. **`git add` 不许写目录**（本项目已三次判定为缺陷）：Step 5 里的
     `aihub-gateway/src/main/java/com/aihub/gateway/quota/` 与 `.../test/java/com/aihub/gateway/quota/`
     **必须展开成逐个文件**。
  9. **网关测试属性默认关掉配额**（与 `metering`/`ratelimit` 同一条纪律：绝大多数测试不想撞"Redis 指向不存在的端口"的超时）：
     `src/test/resources/application.properties` 加 `aihub.quota.enabled=false`，需要它的测试用
     `properties`/`@DynamicPropertySource` **显式打开**（照抄 `aihub.metering.enabled` / `aihub.ratelimit.enabled` 的注释风格）。

- **（2026-10-01 控制器补，13b 专用 —— 4 条"照计划字面做会漏/做不出"）**
  1. **⚠️ 校正必须在**两处**终端发布点都生效，不能只改 `:203`**：实测 `ChatRelayController` 有**两个** `meteringPublisher.publish`
     终端点 —— `:163`（**「一个候选都没有」的提前终止**：`model_not_found`，**在 `doFinally` 之前就 return 了**）
     与 `:203`（`doFinally`，正常/取消/异常收尾）；`:411` 的 `doFinally` **不发布**（只 `onClientDisconnected`）。
     因为 `QuotaFilter`（order +175）**在路由之前**就已预扣，`:163` 这条失败路径**不会**经过 `:203` ⇒ 只挂 `:203` 会让
     **每个 `model_not_found` 请求永久吃掉一份估算配额**（漏，且静默）。⇒ **把两处都改成调用同一个私有助手**
     （如 `publishAndCorrect(event)`，内部先 `meteringPublisher.publish(event)` 再 `quotaCorrector.correct(event)`），
     **并且必须有一条用例钉住"提前终止路径也退回了估算"**（变异：去掉 `:163` 的校正 ⇒ 必须红）。
  2. **⚠️ `correct(MeteringEvent)` 拿不到 estimate —— 必须有**有界**的相关性存储**：`MeteringEvent` 的实测分量是
     `requestId/tenantId/apiKeyId/channelId/model/promptTokens/completionTokens/totalTokens/latencyMs/status/errorCode/createdAtEpochMilli`
     —— **没有**预扣时的估算值。而 `QuotaReservation(tenantId, period, estimatedTokens, degraded)` 是**过滤器**写进 exchange 属性的，
     校正时**拿不到**（那时只有事件）。⇒ 必须显式引入一个 **requestId → (tenantId, period, estimate, degraded) 的关联**：
     过滤器写入、校正器**消费并移除**；**必须有界**（Caffeine + 短 TTL，禁止无界 `Map`）—— 否则这是一处内存泄漏，
     且**跨实例重复调 `adjust` 会双重扣账**（`adjust` 非幂等）。缺失条目 ⇒ **跳过校正 + 计数**（绝不抛到请求路径上）。
     用例必须钉住：**用了注册的 estimate**、**消费后条目被移除**、**缺失条目只计数不崩**。
  3. **两个内存上限的实测值（写进配置注释）**：`aihub.metering.max-capture-bytes` 实测 = **`1048576`**
     （`aihub-gateway/src/main/resources/application.yml:78`，且 `MeteringProperties` 的 `@DefaultValue("1048576")` 一致）；
     `aihub.quota.max-in-memory-bytes` **必须严格小于它**（计划 E.3 caveat 4），并在 `application.yml` 的注释里写明**为什么**。
     报告里给出两个数字与各自来源。
  4. **429 的形状（用既有助手，别手搓 JSON）**：`GatewayErrors.write(ServerHttpResponse, HttpStatus, String type, String code, String message)`
     实测签名；`insufficient_quota` 的 `type` 与 `code` **都是** `"insufficient_quota"`（D6），状态 `TOO_MANY_REQUESTS`（429）；
     **必须**有一条断言证明它**不含** `rate_limit_exceeded`（与限流区分开）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void anOverBudgetRequestIsRejectedWithInsufficientQuotaNotRateLimitExceeded() {
    stubQuota(1L, /* tokenLimit */ 100L);
    var res = post("/v1/chat/completions", largeBody());
    assertThat(res.statusCode()).isEqualTo(429);
    assertThat(res.body()).contains("\"code\":\"insufficient_quota\"").contains("\"type\":\"insufficient_quota\"")
            .doesNotContain("rate_limit_exceeded");
}

@Test
void redisDownAllowsTheRequestAndCountsADegrade() {      // D7：配额 fail-open
    // spring.data.redis.port=1 + 真实过滤器链
    assertThat(post("/v1/chat/completions", smallBody()).statusCode()).isEqualTo(200);
    assertThat(meterRegistry.counter("aihub.quota.degraded").count()).isEqualTo(1.0);
}

@Test
void anUnexpectedScriptShapeCountsScriptErrorNotDegrade() {   // F6 / E.4-(a)
    // 让 Lua 返回一个**非预期形状**（例如 2 个元素，或元素不是数字）：stub 掉 QuotaScript.SCRIPT 的返回，
    // 或直接让 QuotaScript.parse 抛 IllegalStateException。
    // 断言：请求**仍然放行**（配额是记账，不能因为脚本坏了就拒绝付费客户），
    //       但计数落在 aihub.quota.script_error 上，**aihub.quota.degraded 保持 0**。
    assertThat(post("/v1/chat/completions", smallBody()).statusCode()).isEqualTo(200);
    assertThat(meterRegistry.counter("aihub.quota.script_error").count()).isEqualTo(1.0);
    assertThat(meterRegistry.counter("aihub.quota.degraded").count()).isZero();
}

@Test
void cachedBodyStillReachesTheUpstreamByteForByte() {    // D17 + M1 铁律
    upstream.enqueueJson(200, FakeUpstream.completionJson());
    byte[] sent = "{\"model\":\"demo-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":16}"
            .getBytes(UTF_8);
    postBytes("/v1/chat/completions", sent);
    assertThat(upstream.lastRequestBytes()).isEqualTo(sent);   // 逐字节
}

@Test
void theRealUsageCorrectsTheEstimateBothWays() {
    // ⚠️ 2026-10-01 控制器改写：原写法直接读真 Redis（`redis.opsForHash().get(...)`），
    //    与「网关测试永远不允许依赖 Docker」（CONVENTIONS §8 item 3）冲突。
    //    网关侧只断言**调用约定**；「桶里的最终值等于真实用量」由 **admin 侧真 Redis** 用例负责（同键布局）。
    //
    // 夹具回 usage.total_tokens = 3，而估算 = prompt + maxTokens（> 3）⇒ 差额是**负数**（退回）。
    // 双向都要钉住：usage < 估算（退回）与 usage > 估算（补扣）。
    verify(quotaLimiter, times(1)).adjust(1L, PERIOD, ESTIMATE, 3L);   // ① 每请求恰一次、actual 取自 totalTokens()
    verifyNoMoreInteractions(quotaLimiter);

    // ② 差额的**符号与大小**在 Redis 调用这一层钉住（mock StringRedisTemplate，无需 Docker）。
    //    照抄 ratelimit/RedisRateLimiterTest 的 mock 纪律（它同样用 mock 钉调用约定，
    //    把「脚本真的在 Redis 里原子地做」交给 admin 侧的真 Redis 集成测试）。
    new RedisQuotaLimiter(redis).adjust(1L, PERIOD, 100L, 3L);
    verify(redis.opsForHash()).increment(QuotaKeys.bucketKey(1L, PERIOD), "tok", -97L);   // 退回差额
    redis.clearInvocations();
    new RedisQuotaLimiter(redis).adjust(1L, PERIOD, 3L, 100L);
    verify(redis.opsForHash()).increment(QuotaKeys.bucketKey(1L, PERIOD), "tok", 97L);    // 补扣差额
}
```

- [ ] **Step 2: 跑它确认失败** → `cannot find symbol: class QuotaFilter`

- [ ] **Step 3: 实现**

`QuotaFilter` 的关键点（**请求体一次性**，所以必须缓存；且缓存不得改变转发字节）：

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (!properties.enabled() || !GUARDED.matches(exchange.getRequest().getPath().pathWithinApplication())
            || !"/v1/chat/completions".equals(exchange.getRequest().getPath().pathWithinApplication().value())) {
        return chain.filter(exchange);   // 只有 chat/completions 计费（A13：/v1/models 不落 request_log）
    }
    // 请求体是**一次性**流：本过滤器要读它来估算，下游（路由与转发）也必须能读到**同一份字节**。
    // ⚠️ Spring 6.2 **没有** ServerWebExchangeUtils.cacheRequestBody*（评审已在 spring-web /
    // spring-webflux 6.2.19 的 jar 里核实：连 web/server/support 包都不存在），所以自己 join 出来，
    // 再用 ServerHttpRequestDecorator + exchange.mutate() 把**同一份 byte[]** 反复供给下游。
    // ⚠️ 顺序很关键：先复制、**再**释放 join 出来的 buffer，然后把**装饰过的** exchange 传下去。
    //    反过来（释放了还传原 exchange）会让下游读到空 body —— 那正好破坏 M1 的字节级透传铁律。
    //    这一点由 cachedBodyStillReachesTheUpstreamByteForByte 证明，不靠"应该没事"。
    return DataBufferUtils.join(exchange.getRequest().getBody(), properties.maxInMemoryBytes())
            .map(buffer -> {
                byte[] body = new byte[buffer.readableByteCount()];
                buffer.read(body);
                DataBufferUtils.release(buffer);          // 已复制进 byte[]，释放是安全的
                return body;
            })
            .defaultIfEmpty(new byte[0])
            .flatMap(body -> reserveAndContinue(withCachedBody(exchange, body), body, chain))
            // 阻塞的 Redis 调用必须离开 event loop：与 RateLimitFilter 同一条纪律
            // （`LIMITER_SCHEDULER = Schedulers.boundedElastic()`，见 CONVENTIONS §6.6；
            //   网关侧 spring.data.redis.timeout 仍是 2 秒，占住 event loop 就是灾难）
            .subscribeOn(LIMITER_SCHEDULER);
}

/** 把同一份 byte[] 反复供给下游的装饰器：只包 request，其它一概不动。 */
private static ServerWebExchange withCachedBody(ServerWebExchange exchange, byte[] body) {
    ServerHttpRequest decorated = new ServerHttpRequestDecorator(exchange.getRequest()) {
        @Override
        public Flux<DataBuffer> getBody() {
            // 每次订阅都从 byte[] 重新包一个 buffer：下游读几次都拿到完整、独立的字节。
            return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
        }
    };
    return exchange.mutate().request(decorated).build();
}
```

`reserveAndContinue`：取 `ApiKeyAuthFilter.ATTRIBUTE_KEY_VIEW` 里的 `tenantId`/`apiKeyId` → `QuotaResolver` 选额度（`quota` 表按 `tenant_id + period`，**没有行或 `token_limit == 0` 表示不限**，D15）→ `QuotaEstimator.estimate(body, maxTokens)`（**复用 `TokenEstimator`**）→ `RedisQuotaLimiter.reserve(...)` → 拒绝则 `GatewayErrors.write(..., TOO_MANY_REQUESTS, "insufficient_quota", "insufficient_quota", …)`；允许则把 `QuotaReservation` 放进 exchange 属性后 `chain.filter(...)`。Redis 异常时**放行** + `aihub.quota.degraded` 计数 + 限流过的 WARN（D7）。

> ⚠️ **两种「没生效」必须分开计数（F6 / E.4-(a)）**：`QuotaScript.parse` 抛出的 **`IllegalStateException`（脚本返回形状不对）要单独落 `aihub.quota.script_error` + 放行**，**绝不能**被那个宽泛的 `catch (Exception e) → degraded++` 吞掉 —— 合成一个计数器，等于让「Lua 脚本写错了」永远藏在「Redis 挂了」后面，而那两件事的处置完全不同（前者是缺陷，要立刻修；后者是设计好的降级）。

**校正点在哪：`ChatRelayController` 自己看不到 `usage`**（评审已核实：`UsageCapture` 是 `RelayMetering` 的内部物，usage 只在 `metering.toEvent(signal)` 被物化，而那一句在 `doFinally` 里，见 `ChatRelayController:203`）。所以本任务**新增**一个 gateway bean `QuotaCorrector`（`com.aihub.gateway.quota.QuotaCorrector`），并把那一处从：

```java
.doFinally(signal -> meteringPublisher.publish(metering.toEvent(signal)));
```

改成：

```java
.doFinally(signal -> {
    MeteringEvent event = metering.toEvent(signal);
    meteringPublisher.publish(event);       // 既有计量行为一字不变
    quotaCorrector.correct(event);          // 校正：用的就是同一个事件里的真实用量
});
```

`QuotaCorrector.correct(MeteringEvent event)` 取 `event.tenantId()` 与 token 分量算出 `actual`（**实现前先 `grep` 一次 `MeteringEvent` 的分量名并照抄**，别猜），与预扣时记下的 `estimate` 求差，`HINCRBY` 回桶。约束：**不参与响应链路**（失败只记日志 + 计数，02:00 的对账是兜底）；**绝不改写已提交的响应**；`adjust` **不是幂等的**，所以每个请求只调一次，并把这条限制写进 javadoc（重试会重复调整，偏差靠对账发现）。

- [ ] **Step 4: 跑它确认通过并提交**

Run: `mvn -B -pl aihub-gateway -am test "-Dtest=QuotaFilterTest,QuotaDegradeTest,QuotaCorrectionTest,SseStreamingTest,RelayMeteringFlowTest"`
Expected: 全绿 + `BUILD SUCCESS`（后两个既有的类一起跑，证明字节透传与计量没被破坏）。
Run（2026-10-01 控制器补 —— 改动了快照形状，必须一起跑）：
`mvn -B -pl aihub-gateway -am test "-Dtest=AdminClientSnapshotContractTest,ConfigCacheTest,ConfigInvalidateContractTest"`
与 `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ConfigSnapshotVersionTest,ConfigSnapshotServiceTest,QuotaAdminIntegrationTest"`，
且 `mvn -B -pl aihub-admin/aihub-common -am test` 的总数要如实报（**快照 record 加字段会动 `aihub-common` 的既有用例**）。

```bash
# 2026-10-01 控制器订正：原清单有两行是【目录】（本项目已三次判定为缺陷）⇒ 逐个显式路径；
# 并把「额度接进配置快照」那条链路上的 6 个文件补齐（见上方 Files 的 Modify 段）。
git add aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaFilter.java \
        aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaResolver.java \
        aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaEstimator.java \
        aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaLimiter.java \
        aihub-gateway/src/main/java/com/aihub/gateway/quota/RedisQuotaLimiter.java \
        aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaCorrector.java \
        aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaConfigProperties.java \
        aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaConfig.java \
        aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java \
        aihub-gateway/src/main/java/com/aihub/gateway/config/ConfigCache.java \
        aihub-gateway/src/main/resources/application.yml \
        aihub-admin/aihub-common/src/main/java/com/aihub/common/config/QuotaDescriptor.java \
        aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshot.java \
        aihub-admin/aihub-common/src/main/java/com/aihub/common/config/ConfigSnapshotCodec.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/config/ConfigSnapshotService.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/quota/QuotaAdminService.java \
        aihub-gateway/src/test/java/com/aihub/gateway/quota/QuotaFilterTest.java \
        aihub-gateway/src/test/java/com/aihub/gateway/quota/QuotaDegradeTest.java \
        aihub-gateway/src/test/java/com/aihub/gateway/quota/QuotaCorrectionTest.java \
        aihub-gateway/src/test/resources/application.properties
# 若你确实动了既有契约测试（AdminClientSnapshotContractTest / ConfigSnapshotVersionTest），
# 把它们的路径也逐个加进来，并在报告里说明改了哪条断言、为什么（不许放宽）。
git commit -m "feat(gateway): pre-deduct quota atomically, reject with insufficient_quota, correct from real usage"
```

**验收判据：** 超额是 `insufficient_quota`（不是 `rate_limit_exceeded`）；Redis 挂了**放行**且有降级计数，**脚本形状错落独立计数器 `aihub.quota.script_error`（且 `degraded` 保持 0）**；缓存请求体后上游收到的字节**逐字节不变**；真实 usage **双向**校正估算（差额的符号与大小在 Redis 调用层钉住）；**额度来自配置快照**（快照里**有** `quotas`，且 `QuotaAdminService.update` 会发布 `quota.update`）；`quota.max-in-memory-bytes < metering.max-capture-bytes`（**给出两个数字的实测值**）；**网关套件不依赖 Docker**。
**RED 证据：** `cachedBodyStillReachesTheUpstreamByteForByte` 在「缓存把 body 消费掉」的实现下红（上游收到空体）；`anOverBudgetRequestIsRejectedWithInsufficientQuota…` 在复用 `rate_limit_exceeded` 的实现下红。
⚠️ **（2026-10-01 控制器补）RED 必须落在被测断言上**：Task 9/10/11/12 的自然 RED **都**全红在 `404`/`cannot find symbol`（端点或类未存在）—— 那种红**不能**证明断言的判别力。⇒ 仍要求**每一条行为各配一条变异体**，尤其：
① 缓存后**释放顺序颠倒**（先释放 buffer 再传原 exchange）⇒ `cachedBodyStillReachesTheUpstreamByteForByte` 必须红；
② 超额响应复用 `rate_limit_exceeded` ⇒ 第一条必须红；
③ 把 `IllegalStateException` 折进 `catch → degraded++` ⇒ `script_error` 那条必须红；
④ **删掉快照里的 `quotas`**（或让 `ConfigSnapshotService` 不查 `quota` 表）⇒ 额度解析必须红（否则"额度来自快照"只是句空话）；
⑤ `adjust` 每请求调两次 ⇒ "恰一次"必须红；
⑥ `Chained`：`ChatRelayController` 的 `quotaCorrector.correct(event)` 去掉 ⇒ 校正用例必须红。

---

## Task 14: `POST /internal/quota/reserve` 兜底

**Files:**
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/internal/InternalQuotaController.java`
- Modify: `aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java`（+`reserveQuota(...)`，**default 方法返回空**，避免破坏既有函数式替身）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/quota/InternalQuotaReserveTest.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/quota/QuotaReserveFallbackTest.java`

**Interfaces:**
- Consumes: `InternalHmac` + `InternalAuthFilter`（既有内部契约：`POST` + 应用内路径 + `X-Internal-Timestamp`/`X-Internal-Signature`）
- Produces:
  - `POST /internal/quota/reserve` 请求体 `{"tenantId":1,"estimatedTokens":123}` → admin 信封，`data = {"allowed":true,"remainingTokens":…,"remainingRequests":…}`
  - `AdminClient.reserveQuota(long tenantId, long estimatedTokens) : Mono<QuotaDecision>`（**default 实现返回 `Mono.empty()`**，与 `configSnapshot` 同一纪律）

- **（2026-10-02 控制器补 —— 9 条：Files 缺路径 + 兜底的挂点/边界）**
  1. **⚠️ Files 缺路径（本项目第 7 次）**：兜底**必须挂在 `QuotaFilter` 的「Redis 不可用」分支上**，而本任务的 Files 里
     **没有** `QuotaFilter`/`QuotaConfig`。⇒ 必须 Modify：
     `aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaFilter.java`（降级分支改为「先试兜底」）、
     `aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaConfig.java`（把兜底注入过滤器）、
     `aihub-gateway/src/main/resources/application.yml`（新增开关与说明）；
     并 **Create** `aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaFallback.java`
     （一个**窄接口**：`Optional<QuotaDecision> reserveFallback(long tenantId, long estimatedTokens)`，实现里调 `AdminClient.reserveQuota(...).blockOptional(...)`。
     理由：过滤器**不该**直接依赖 `AdminClient` 的 HTTP 细节，否则网关单测得为兜底拉一个假 HTTP 服务；
     但**E2E** 仍要走 `FakeAdminServer` 证明「真 HTTP + 真 HMAC 签名」这一层 —— 两侧都要有。）
  2. **⚠️ 兜底只在「Redis 不可用」时试，另两种情况**不试**：① **本周期不限**（没有预扣，不需要兜底）；
     ② **`script_error`（脚本形状异常）** —— admin 侧跑的是**同一份 Lua** ⇒ 兜底必然也失败，试它只会把「脚本写错了」
     这个**缺陷**藏进「Redis 挂了」这个**降级**里（正是 `SCRIPT_ERROR_METRIC` 那条纪律要防的）。
     ③ `AdminClient.reserveQuota` 的 default 返回 `Mono.empty()` ⇒ **空 = 没配兜底** ⇒ 按 D7 fail-open + `degraded` 计数。
  3. **兜底**共享同一个桶**（不是第二套账）**：admin 端点必须用**同一份 `QuotaScript`**、**同一个键布局**
     （`QuotaKeys.bucketKey(tenantId, QuotaPeriod.of(now))`）跑预扣；**admin 侧真 Redis 用例**必须证明：
     超预算 ⇒ `allowed=false` 且桶值不再增长；且键**逐字等于** `aihub:quota:<tenantId>:<YYYYMM>`。
  4. **兜底调用必须有界**：`AdminClient` 的调用要有超时；**不许**把请求无限挂住（工具：`FakeAdminServer.enqueueStalledJsonForPath(path, status, body, delayMillis)`）。
     超时 ⇒ 与「兜底失败」同路径：fail-open + `degraded`。
  5. **开关** `aihub.quota.fallback-enabled`（**默认 true**，并在 `application.yml` 注释里写明理由：Redis 挂掉时若不回源，
     配额**完全失效** ⇒ 可能超发；回源由 admin 侧的真 Redis 承担同一份账）。**必须有用例钉住 on/off 两侧**。
  6. **兜底失败仍放行 + 计数 `degraded`**（本任务验收判据）：既不许把它升级成业务故障（拒绝请求），也不许**重复计数**。
  7. **内部鉴权不需要登记新路径**：实测 `InternalAuthFilter` 按**前缀** `/internal/` 守卫（`INTERNAL_PREFIX`，无白名单），
     且用 `getPathWithinApplication()` 判定 ⇒ `/internal/quota/reserve` **自动受保护**。用例只需钉住「无签名 ⇒ 401（admin 信封）；
     带签名 ⇒ 200」，并**照抄 `InternalKeyController`/`InternalConfigController` 的注解形状**（含 `POST + 应用内路径` 的签名口径）。
  8. **网关测试不许依赖 Docker**：兜底 E2E 用 `FakeAdminServer`（它已支持 `enqueueJsonForPath` 与 `enqueueStalledJsonForPath`）；
     「桶真的被扣」由**admin 侧真 Redis** 用例负责（与 Task 12/13 同一条分工）。
  9. **`git add` 逐个显式路径**（原清单只有 4 条，**缺上面第 1 条列出的 4 个**）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void theReserveEndpointRequiresTheInternalSignature() { /* 无签名 → 401（admin 信封）；带签名 → 200 */ }

@Test
void whenTheReserveCallItselfFailsTheGatewayStillAllowsTheRequest() {
    // D7 的一致性：兜底路径也失败时仍然 fail-open，并计数
    assertThat(post("/v1/chat/completions", smallBody()).statusCode()).isEqualTo(200);
    assertThat(meterRegistry.counter("aihub.quota.degraded").count()).isGreaterThanOrEqualTo(1.0);
}
```

- [ ] **Step 2–4: 失败 → 实现 → 通过**

Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=InternalQuotaReserveTest"`
Run: `mvn -B -pl aihub-gateway -am test "-Dtest=QuotaReserveFallbackTest"`

- [ ] **Step 5: 提交**

```bash
# 2026-10-02 控制器订正：原清单缺「兜底挂在哪」的 4 个路径（见上方 Interfaces 的裁定 1）
git add aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/internal/InternalQuotaController.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/quota/InternalQuotaReserveTest.java \
        aihub-gateway/src/main/java/com/aihub/gateway/admin/AdminClient.java \
        aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaFallback.java \
        aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaFilter.java \
        aihub-gateway/src/main/java/com/aihub/gateway/quota/QuotaConfig.java \
        aihub-gateway/src/main/resources/application.yml \
        aihub-gateway/src/test/java/com/aihub/gateway/quota/QuotaReserveFallbackTest.java
git commit -m "feat(quota): add the HMAC-signed internal reserve fallback"
```

**验收判据：** 内部接口受签名保护（无签名 401、带签名 200）；**兜底只在「Redis 不可用」时试**（不限 / `script_error` 两条路径**不试**）；
兜底**共享同一个桶与同一份 Lua**（admin 侧真 Redis 证明）；**兜底本身失败/超时时仍然放行**（与 D7 一致）+ `degraded` 计数；
开关 `aihub.quota.fallback-enabled` 默认 true 且两侧都有用例；**网关测试不依赖 Docker**。
**RED 证据：** `whenTheReserveCallItselfFailsTheGatewayStillAllowsTheRequest` 在「兜底失败就拒绝」的实现下红（那是把记账故障升级成业务故障）。

---

## Task 15: 每日对账任务

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/quota/QuotaReconciliationService.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/quota/QuotaReconciliationJob.java`
- Modify: `aihub-admin/aihub-service/src/main/java/com/aihub/service/metering/MeteringSchedulingConfig.java`（注册 `@Scheduled(cron = "${aihub.quota.reconcile-cron:0 0 2 * * *}")`）
- Modify: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/BillingDailyMapper.java`（**加 `recomputeDaily(from, to)`** —— 这个文件是 Task 11 建的，本任务改它；**必须进本任务的 `git add`**，否则 Step 3 的提交里没有那个方法、而服务层已经在调它 = **提交出来的树编译不过**，违反全局约束的「每个任务的提交必须让整个反应堆编译通过」）
- Modify: `aihub-admin/aihub-web/src/main/resources/application.yml`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/quota/QuotaReconciliationTest.java`

**Interfaces:**
- Consumes: `RequestLogMapper`、`BillingDailyMapper`（Task 11）、`QuotaMapper`、`AuditService`
- Produces:
  - `QuotaReconciliationService.reconcile(LocalDate statDate) : ReconciliationReport`（record：`long requests`、`long tokens`、`Map<Long,Double> mismatches`）
  - 计数器 `aihub.quota.reconcile.mismatch`、`aihub.quota.reconcile.runs`
  - `QuotaReconciliationJob.reconcileYesterday()`（`@Scheduled`，**02:00**，与 M3 的分区维护 **03:10** 错开）
  - 配置 `aihub.quota.reconcile-cron`（默认 `0 0 2 * * *`）、`aihub.quota.reconcile-tolerance-ratio`（默认 `0.01`）

- **（2026-10-02 控制器补 —— 9 条"照字面做会算错/漏掉"）**
  1. **⚠️ 语义缺陷：日记账与「月」桶**不可直接比**（先定死口径）**：`reconcile(LocalDate statDate)` 只重算**一天**，
     而 Redis 桶 `aihub:quota:<tenant>:<YYYYMM>` 装的是**当月累计**。把「这一天的 tokens」直接与桶比，
     **每月除最后一天外都会误报偏差**。⇒ **定死**：偏差比较的对象是**周期累计** ——
     `period = QuotaPeriod.of(statDate)`，用 `SUM(billing_daily.tokens WHERE stat_date ∈ [周期第一天, statDate])`
     与桶里的 `tok` 比（对「昨天」而言这就是"周期至今"）。**`ReconciliationReport.tokens` 是那**一天**的重算值，
     `mismatches` 是**周期至今**的口径** —— 两个不同 scope，必须写进 record 的 javadoc。
     **必须有一条用例钉住它**：造一个「当天的量与桶不同、但周期至今的量与桶一致」的夹具 ⇒ **不许报偏差**；
     再一个「周期至今不同」的夹具 ⇒ **必须报**。这是本条唯一有判别力的形式。
  2. **⚠️ `cron` 必须显式 `zone = "UTC"`**：`@Scheduled(cron = ...)` **默认用 JVM 默认时区** —— 本机是 UTC+8，
     "02:00" 会漂到 UTC 18:00。既有 `RequestLogPartitionMaintainer` 的 `@Scheduled(..., zone = "UTC")` 就是这条纪律的先例。
     ⇒ 对账的 `@Scheduled` **必须**写 `zone = "UTC"`（与它错开 03:10 才有意义）。
  3. **⚠️ `statDate` 必须按 UTC 折算，且必须可注入 `Clock`**：用 `LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)`
     （**不许** `LocalDate.now()`，那是 JVM 默认时区 —— CONVENTIONS §7 的原陷阱）。`QuotaReconciliationJob` 的
     "昨天"必须能被用例用固定 `Clock` 钉住；`quota` 的 `period` 同样按 UTC。
  4. **⚠️ 时间参数不许绑 `Instant`**：计划给的 Mapper 签名是 `recomputeDaily(Instant from, Instant to)`，
     而 `request_log.created_at` 是**无时区**的 `DATETIME(3)`、存 UTC 墙上时间。绑 `Instant` 会由驱动按**连接时区**折算，
     在**非 UTC 连接**上把窗口整体推走（Task 11 已为此付过代价，且本套件跑 UTC 方言 ⇒ **测试里静默通过**）。
     ⇒ 签名改 **`LocalDateTime from, LocalDateTime to`**，由调用方 `LocalDateTime.ofInstant(instant, ZoneOffset.UTC)` 显式折算。
  5. **⚠️ 偏差的判据必须钉死（含除零）**：`ratio` 的口径写成 `|周期至今的重算值 − 桶值| / max(周期至今的重算值, 1)`；
     **当重算值为 0 而桶值 > 0 时必须仍能报出**（那是"预扣了但没落账"，最严重的一类），**不许把它当除零静默跳过**。
     `ratio > tolerance` 才算 mismatch（**等于不算**，边界只定义一次）。`mismatches : Map<Long, Double>` = 租户 → ratio。
  6. **⚠️ `git add` 里有**目录**（`.../service/quota/`）**：本项目已四次判定为缺陷 ⇒ 展开成 2 个文件
     （`QuotaReconciliationService.java`、`QuotaReconciliationJob.java`）。注意同一清单里 Mapper 那行**已经**是显式路径 —— 不一致。
  7. **⚠️ 夹具必须**定向**，不许全表计数**：计划 Step 1 的 `billingDailyMapper.selectList(null)` +
     `containsExactlyInAnyOrder(30L, 300L)` 是**全表断言**，而 `billing_daily` 与 `request_log` 都是 **JVM 级共享表**
     （Testcontainers 单例）⇒ 别的用例写过行就会红。⇒ 只按**本用例的 (tenantId, stat_date)** 定向查并断言行数/值。
     （`request_log` 的夹具照抄 `RequestLogPartitionMaintainerTest:118` 的列清单；`2026-09-26` 落在 V1 建的 `p202609` 里，
     分区是存在的 —— 但**别改成没有分区的日期**。）
  8. **⚠️ `runningTwiceIsIdempotentBecauseOfTheUniqueKey` 现在是**空占位**（`{ /* … */ }`）** ——
     本项目已**四次**抓到这种假绿（Task 10/11/12 各一次）。⇒ 必须落地：同一 `stat_date` 连跑两次 ⇒
     ① 定向查到的**行数不变**（恰好 1 行/(tenant,date)）、② `requests`/`tokens` **值不变**。
  9. **`cost` 固定 0** 与 **"已知近似值不区分"**（`error_code = usage_missing` / `client_disconnected`）两条要在代码注释与
     README 边界里写明（计划已提，这里强调：**注释里必须点出它使偏差计数器天然包含近似数据**）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void recomputesBillingDailyForYesterdayFromRequestLog() {
    // 真 MySQL：造该日的 request_log（两个租户、值不同）。
    // ⚠️ 2026-10-02 订正：**不许 `selectList(null)` 全表断言** —— billing_daily/request_log 都是
    //    JVM 级共享表（Testcontainers 单例），别的用例写过行就会红。只按本用例的 tenant/stat_date 定向查。
    long t1 = 880_001L, t2 = 880_002L;
    insertRequestLog(t1, LocalDateTime.of(2026, 9, 26, 10, 0), 30);   // 列清单照抄 RequestLogPartitionMaintainerTest:118
    insertRequestLog(t2, LocalDateTime.of(2026, 9, 26, 11, 0), 300);
    service.reconcile(LocalDate.of(2026, 9, 26));

    assertThat(billingDailyFor(t1, LocalDate.of(2026, 9, 26)).getTokens()).isEqualTo(30L);
    assertThat(billingDailyFor(t2, LocalDate.of(2026, 9, 26)).getTokens()).isEqualTo(300L);
    assertThat(billingDailyFor(t1, LocalDate.of(2026, 9, 26)).getRequests()).isEqualTo(1L);
}

@Test
void runningTwiceIsIdempotentBecauseOfTheUniqueKey() {
    // ⚠️ 2026-10-02 订正：原计划这里是【空占位】（本项目已四次抓到这类假绿）⇒ 必须落地：
    //    同一 stat_date 连跑两次 ⇒ ① 该租户在该日的行数**恰好 1**（uk_billing_daily 是幂等的锚点）
    //    ② requests/tokens 的值与第一次相同。
    long t = 880_003L;
    insertRequestLog(t, LocalDateTime.of(2026, 9, 26, 12, 0), 42);
    service.reconcile(LocalDate.of(2026, 9, 26));
    BillingDailyEntity first = billingDailyFor(t, LocalDate.of(2026, 9, 26));
    service.reconcile(LocalDate.of(2026, 9, 26));
    List<BillingDailyEntity> rows = billingDailyRows(t, LocalDate.of(2026, 9, 26));
    assertThat(rows).as("幂等：不许新增行（unique key 是锚点）").hasSize(1);
    assertThat(rows.get(0).getTokens()).isEqualTo(first.getTokens()).isEqualTo(42L);
    assertThat(rows.get(0).getRequests()).isEqualTo(1L);
}

@Test
void aDeviationBeyondTheToleranceIsCountedAndAuditedButDoesNotChangeTheQuota() {
    // 租户的 period 累计（billing_daily）与 Redis 桶的 tok 明显不同 → 计数器 +1、审计行 +1、
    // quota.token_used **不变**（D12：对账只**检测**，不改账）。
    assertThat(meterRegistry.counter("aihub.quota.reconcile.mismatch").count()).isEqualTo(1.0);
    assertThat(quotaMapper.selectById(quotaId).getTokenUsed()).as("D12：对账绝不改账").isEqualTo(before);
}

@Test
void aMatchingPeriodToDateIsNotReportedEvenWhenTheDayItselfDiffers() {
    // ⚠️ 2026-10-02 新增（裁定 1 的唯一有判别力形式）：
    //    当天的量与桶不同，但**周期至今**的量与桶一致 ⇒ **不许报偏差**（否则每月除最后一天外全误报）。
    //    反向：周期至今不同 ⇒ 必须报（上一条用例覆盖）。
}
```

- [ ] **Step 2–3: 失败 → 实现**

重算用一条幂等 UPSERT（`uk_billing_daily(tenant_id, stat_date)` 是幂等的锚点）。**它必须是一个有名字的 Mapper 方法**（加在 Task 11 建的 `BillingDailyMapper` 上），不许"顺手写在服务层的字符串里"：

```java
// BillingDailyMapper（Task 11 建的那个）新增这一个方法。
// 2026-10-02 控制器订正：from/to **必须是 LocalDateTime(UTC 墙钟)**，不是 Instant ——
// request_log.created_at 是无时区 DATETIME(3)，绑 Instant 会按**连接时区**折算（Task 11 已为此付过代价，
// 且本套件跑 UTC 方言 ⇒ 这种错在测试里**静默通过**）。由调用方 LocalDateTime.ofInstant(i, ZoneOffset.UTC) 折算。
@Insert("""
        INSERT INTO billing_daily (tenant_id, stat_date, requests, tokens, cost)
        SELECT tenant_id, DATE(created_at), COUNT(*), SUM(total_tokens), 0
        FROM request_log
        WHERE created_at >= #{from} AND created_at < #{to}
        GROUP BY tenant_id, DATE(created_at)
        ON DUPLICATE KEY UPDATE requests = VALUES(requests), tokens = VALUES(tokens), cost = VALUES(cost)
        """)
int recomputeDaily(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
```

- **`VALUES()` 在 MySQL 8.4 上是 deprecated**（会有 deprecation warning，功能正常）。8.0.19+ 推荐的替代是**行别名**，但它对 `INSERT ... SELECT` 有语法限制：先在真库上试 `... SELECT ... AS new ON DUPLICATE KEY UPDATE requests = new.requests, ...`，**能过就用它**；过不了就保留上面的 `VALUES()` 形式，并在报告里**登记实际采用哪一种**（不要在这里猜语法，以真库为准 —— Task 1 已经证明这个环境有真 MySQL）。
- `cost` 固定写 `0` 并在 README 登记（没有单价表，见「不做的事」）。
- **口径提醒**：`request_log` 里 `error_code = usage_missing` / `client_disconnected` 的行是**已知的近似值**（CONVENTIONS §6.5），重算不去区分它们 —— 所以偏差计数器天然包含这部分近似数据，README 的已知边界要写这句。

- [ ] **Step 4: 跑它确认通过并提交**

Run: `DOCKER_HOST=tcp://127.0.0.1:2375; mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=QuotaReconciliationTest"`

```bash
# 2026-10-02 控制器订正：原清单第一行是【目录】（本项目已四次判定为缺陷）⇒ 展开成显式文件
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/quota/QuotaReconciliationService.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/quota/QuotaReconciliationJob.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/metering/MeteringSchedulingConfig.java \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/BillingDailyMapper.java \
        aihub-admin/aihub-web/src/main/resources/application.yml \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/quota/QuotaReconciliationTest.java
git commit -m "feat(quota): add the 02:00 reconciliation job that recomputes billing_daily and reports deviations"
```

**验收判据：** `billing_daily` 按 `request_log` 被**幂等**重算（同一 `stat_date` 连跑两次行数与值不变）；偏差按 **`ratio = |周期至今重算值 − 桶值| / max(周期至今重算值, 1)`** 判定、`> tolerance` 才计（**等于不算**）、**重算值为 0 而桶值 > 0 也必须报**；**检测**≠**改账**（`quota.token_used` 不变，D12）；**cron 显式 `zone = "UTC"`** 且与分区维护 03:10 错开；`statDate`/`period`/`from`/`to` 全部按 **UTC** 折算（`LocalDateTime` 绑定）；**夹具定向、无全表计数**。
**RED 证据：** `runningTwiceIsIdempotentBecauseOfTheUniqueKey` 在「累加」这类实现下红；`aDeviationBeyondTheToleranceIsCountedAndAuditedButDoesNotChangeTheQuota` 在「自动改账」的实现下红。
⚠️ **（2026-10-02 控制器补）变异体要求（每条行为各配一条，否则"绿"没有判别力）**：
① **累加**（把 UPSERT 改成 `requests = requests + VALUES(requests)`）⇒ 幂等用例必须红；
② **比较对象改成"当天"而不是"周期至今"** ⇒ 可比性用例必须红（裁定 1）；
③ **`statDate` 改成 `LocalDate.now()`（JVM 默认时区）** ⇒ 用固定 `Clock` 的用例必须红（裁定 3，§7 的时区陷阱）；
④ **`from`/`to` 改成绑 `Instant`** ⇒ 在**非 UTC 方言**下必须红 —— 本套件是 UTC 方言 ⇒ 这条**大概率打不红**：**打不红就如实登记**（这正是 §7 已登记的既有覆盖边界，别伪造确定性）；
⑤ **去掉 `zone = "UTC"`** ⇒ 有没有用例钉住？（若没有 ⇒ 至少要在报告里说明它靠什么保证）；
⑥ **自动改 `quota.token_used`** ⇒ D12 用例必须红；
⑦ **重算值为 0 而桶值 > 0 时静默跳过** ⇒ 必须有用例红。

---

## Task 16: 极简管理台静态页

**Files:**
- Create: `aihub-admin/aihub-web/src/main/resources/static/console/index.html`
- Create: `aihub-admin/aihub-web/src/main/resources/static/console/console.js`
- Create: `aihub-admin/aihub-web/src/main/resources/static/console/console.css`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ConsoleStaticResourceTest.java`

**Interfaces:**
- Consumes: `/api/auth/login`、`/api/channels`、`/api/api-keys`、`/api/logs`（Task 6/8/9/11）
  **＋ `GET /api/ping`（2026-10-02 控制器补，原来是**致命遗漏**）**：`/api/logs`（以及 `/api/audit`、`/api/billing/daily`）
  在 `docs/CONVENTIONS.md` §10 **R3.1** 下**必须显式 `tenantId`**，而 **`LoginResponse` 只回 `{token, role, expiresAtEpochSecond}`——没有 `tenantId`**
  （`ConsoleAuthController:53`）⇒ 照字面实现的话日志视图**必然 400**。`tenantId` 只能来自 **`/api/ping` 的 `PingResponse(userId, tenantId, role)`**（`:57`）。
  ⇒ 页面登录后必须再调一次 `/api/ping` 取 `tenantId`（它也是 Task 6 就为"带令牌的正向探针"而存在的）。
- Produces: ~~`GET /console/`~~ **`GET /console/index.html`** 可访问的三个静态文件（**2026-10-02 控制器订正**：Spring Boot
  **不为子目录提供 welcome page**，`/console/` 很可能 404 —— **不要**把它写成必须 200 的验收；若实测为 200 就**额外记录**，
  否则页面入口就是 `/console/index.html`）；页面用 `sessionStorage` 存令牌（D9）

- **（2026-10-02 控制器补 —— 8 条"照字面执行会出错 / 会让你交出假证据"）**
  1. **⚠️ 计划给的测试代码在本项目里编译不过（照抄必红在编译）**：`get(String)` **不是**基类提供的，而是**每个测试类各自 `private` 定义**
     （`ChannelAdminIntegrationTest:854`、`ProbeAndQueryIntegrationTest:602`…），返回 `ResponseEntity<String>` ⇒ 它有 `getStatusCode()`
     与 `getBody()`，**没有** `.statusCode()` / `.body()`；`read(String)` **全仓不存在**。⇒ 你的新测试类必须**自己定义**这两个助手：
     `get(String)`（项目同款）+ `read(String name)`（用 `new ClassPathResource("static/console/" + name)` 读 **classpath 上的真实产物**，
     而不是去读源码目录），并一律用 `getStatusCode()` / `getBody()`。
  2. **RED 形态如实登记**：文件不存在 ⇒ 三条 200 与 `read(...)` 都红在"**资源不存在 / 404**"，
     **没有判别力**（与 Task 9/10/11 的 404 同源）。判别力**必须**由变异体提供（见第 5 条），**不许**把 404 式的红当成"断言有效"。
  3. **⚠️ 验收判据里的"登录后能完成三件事"本任务**无法**由 JUnit 覆盖**（没有 JS 引擎，页面逻辑是客户端代码）。
     ⇒ **不许**把它写成"已验证"。可测的部分**必须**折成**可证伪的结构断言**（第 5 条），不可测的部分**在报告里明写"未由测试覆盖、只能人工核对"**。
  4. **Hygiene 断言只查了 js、漏了 html**：`innerHTML` 的检查**必须覆盖 `index.html` 与 `console.js` 两个文件**；
     `<script>` 的检查**必须**能同时抓住**裸 `<script>`**（无 `src`）与**内联事件处理器**（`onclick=`/`onload=`）与 `javascript:` 伪协议
     （原断言只抓 `<script>` 这一种，给它加个空格就能溜过去）；并断言**不出现任何外部 URL**（`http://`/`https://`/`//cdn`）。
  5. **必须有判别力的结构断言（`.js` 内容级，删掉任一视图即变红）**：`console.js` **必须包含**四个端点字面量
     `/api/auth/login`、`/api/channels`、`/api/api-keys`、`/api/logs`**加 `/api/ping`**，**必须包含** `sessionStorage` 与 `textContent`，
     **必须不包含** `innerHTML`、`eval(`、`document.write`、`localStorage`、`outerHTML`。
     （`localStorage` 是 D9 的反面：令牌只许在 `sessionStorage`。）
  6. **明文只出现一次**：`POST /api/api-keys` 的 `data.plaintextKey` 是**唯一**一次明文（Task 8）⇒ 页面必须**一次性展示**并提示，
     **不许**把它写进 `sessionStorage`/URL/日志。断言：`.js` 里 `plaintextKey` 出现，且**不出现** `localStorage`（与第 5 条合并）。
  7. **上下文预算（必须仍是 7）**：`ConsoleStaticResourceTest` **必须继承 `AbstractIntegrationTest`**（`.../support/AbstractIntegrationTest.java`，
     `@SpringBootTest(RANDOM_PORT)`）、**不许**声明 `@TestPropertySource`/`@Import`、**不许**用 `@WebMvcTest`（那会 fork 新上下文）。
     ⇒ 它与既有集成测试**共用默认上下文**，套件总数**仍是 7**。跑完全量必须**实测** `Tomcat started on port` 次数（基线 **7**）。
  8. **`git add` 不许写目录**（本项目已**三次**踩过）：`:2530` 的 `.../static/console/` **必须展开成 3 个显式文件路径**。

- [ ] **Step 1: 写失败测试**

```java
// 2026-10-02 控制器订正：原稿用了本项目不存在的形状（`.statusCode()`/`.body()`/`read(...)` 编译不过）。
// 本类**自己**定义 get(String)（项目同款，返回 ResponseEntity<String>）与 read(String)（读 classpath 上的真实产物）。
@Test
void theConsoleAssetsAreServed() {
    assertThat(get("/console/index.html").getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/console/console.js").getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/console/console.css").getStatusCode()).isEqualTo(HttpStatus.OK);
    // ⚠️ 不要断言 `/console/`（Spring Boot 不为子目录做 welcome page）—— 若实测为 200，额外记录，不进验收。
}

@Test
void theConsoleIsSelfContainedAndCarriesNoInlineCode() {
    String html = get("/console/index.html").getBody();
    assertThat(html).as("不引第三方脚本、不用内联脚本（CSP 友好，也少一个 XSS 面）")
            .contains("src=\"/console/console.js\"")
            .doesNotContain("<script>")          // 裸标签（无 src）＝内联脚本
            .doesNotContain("onclick=").doesNotContain("onload=")   // 内联事件处理器同样是内联脚本
            .doesNotContain("javascript:")
            .doesNotContain("http://").doesNotContain("https://");  // 无外部 URL / CDN
}

@Test
void theConsoleNeverRendersUntrustedHtmlAndKeepsTheTokenInSessionStorage() {
    // ⚠️ innerHTML 两个文件都要查（原稿只查了 js）
    for (String asset : new String[] {"index.html", "console.js"}) {
        assertThat(read(asset)).as("%s：所有服务端文本都必须走 textContent，不许 innerHTML", asset)
                .doesNotContain("innerHTML")
                .doesNotContain("outerHTML")
                .doesNotContain("eval(")
                .doesNotContain("document.write")
                .doesNotContain("localStorage");          // D9：令牌只许在 sessionStorage
    }
    // 结构引用（删掉任一视图即红 —— 这是本任务唯一有判别力的部分，见裁定 2/5）
    assertThat(read("console.js")).as("五个端点必须都在（含 /api/ping：日志视图要靠它拿 tenantId）")
            .contains("/api/auth/login").contains("/api/channels").contains("/api/api-keys")
            .contains("/api/logs").contains("/api/ping")
            .contains("sessionStorage").contains("textContent").contains("plaintextKey");
}
// ⚠️ RED 形态：文件不存在 ⇒ 上面三条都红在 404/资源不存在，**没有判别力**；判别力由变异体提供。
// ⚠️ 「登录后能完成建渠道 / 建 Key / 查日志」**没有 JS 引擎就测不了** ⇒ 报告里必须写"未由测试覆盖"。
```

- [ ] **Step 2: 跑它确认失败** → 404（文件还不存在）

- [ ] **Step 3: 实现**：登录表单 → `sessionStorage.setItem("aihub.console.token", token)`；三个视图（渠道列表 + 新建、Key 列表 + 新建并**一次性展示明文**、请求日志查询）；所有网络调用走一个 `api(path, options)` 包装，统一把非 `OK` 的 `data.code` 显示给用户；`401` 时清令牌并回登录视图。**全部用 `textContent`**。

- [ ] **Step 4: 跑它确认通过并提交**

Run: `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=ConsoleStaticResourceTest"`

```bash
# 2026-10-02 控制器订正：原清单第一行是【目录】，本项目已三次禁止（逐个显式路径）
git add aihub-admin/aihub-web/src/main/resources/static/console/index.html \
        aihub-admin/aihub-web/src/main/resources/static/console/console.js \
        aihub-admin/aihub-web/src/main/resources/static/console/console.css \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/console/ConsoleStaticResourceTest.java
git commit -m "feat(console): add the zero-build static admin console"
```

**验收判据：** 三个静态文件可访问（**入口是 `/console/index.html`**）；无内联脚本/内联事件处理器、无第三方 URL、无 `innerHTML`/`eval(`/`document.write`、无 `localStorage`（令牌只进 `sessionStorage`）；`.js` 里五个端点与 `plaintextKey` 都在；**套件 Spring 上下文仍是 7**。
⚠️ **本任务唯一无法由测试覆盖的验收**：`登录后能完成「建渠道 / 建 Key / 查日志」三件事` —— 页面逻辑是**客户端 JS**，套件里**没有 JS 引擎**
⇒ 报告里**必须明写"未由测试覆盖、只能人工核对"**，**不许**写成"已验证"（可覆盖的部分已折成结构断言与变异体）。
**RED 证据：** 任何一次用 `innerHTML` 渲染服务端文本、或把令牌放进 `localStorage` 时，`theConsoleNeverRendersUntrustedHtmlAndKeepsTheTokenInSessionStorage` 红
（这是本页面唯一的 XSS 入口，D9 已把代价登记为已知边界）。**但注意 RED 的自然形态是"文件不存在"（404）**—— 没有判别力，
**判别力必须由变异体提供**：① 给 `.js` 加 `innerHTML` ⇒ 该用例红；② 删掉 `/api/ping` 引用 ⇒ 结构断言红；③ 加一行 `localStorage.setItem(...)` ⇒ 该用例红；
④ 把 `<script src=...>` 改成内联 `<script>…</script>` ⇒ hygiene 用例红；⑤ 引入一个 `https://cdn…` ⇒ hygiene 用例红。**至少做 4 条。**


**（2026-10-02 控制器修复轮 —— 独立评审抓出的 1 条 Important 已修）**
- **B-1（Important，本任务交付物里的真实缺陷）**：`console.js` 的 `submitLogs` **只在输入非空时**才带 `from`/`to`，
  而服务端 `LogQueryController` 对 `from`/`to` **缺省即 400**（Task 11 裁定 3：必须能解析成带 `Z` 的 `Instant`，解析失败 400）；
  更重的是 `<input type="datetime-local">` 的值形如 `2026-10-02T15:30`（**无秒、无时区**）⇒ **填了也 400**。
  ⇒ 修法：新增 `utcInstant(value, fallbackMillis)`（补秒 + 补 `Z`；为空时用「最近 24 小时」窗口），**无条件**带 `from`/`to`。
- **同时加强测试**（评审 C-1/C-2/D）：协议相对 URL `//cdn` 也禁；`<script` 的**计数不变量**（大小写/空白变体与额外内联脚本都躲不过）；
  两文件 `read()` 加 `isNotEmpty()` **正向对照**；并给 B-1 加**回归钉** `theLogsQueryAlwaysCarriesAWellFormedFromAndTo`
  （断言 `utcInstant(` 存在 + 旧的「按需省略」形状不许回来）。
- **判别力**：控制器变异 R2（把旧的条件式形状放回去）⇒ 该钉**精确红在 `:105`**；还原后 `clean` 回绿 **4/0**。
  （原先的 R1：给 `console.js` 加 `innerHTML` ⇒ 红在 `:73`。）
- **提交**（2 个显式路径）：`git add .../static/console/console.js .../test/.../ConsoleStaticResourceTest.java`
  → `git commit -m "fix(console): always send a well-formed from/to in the log query"`

---

## Task 17: 文档收口 + M4 全栈验收

**Files:**
- Modify: `docs/CONVENTIONS.md`、`README.md`、`.env.example`、`docker-compose.yml`（**admin 加 `AIHUB_CONSOLE_SECRET`、gateway 加 `AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION`** —— 不改它，第 2–7 步在容器里全是 500/401，见 C5）、`docs/superpowers/specs/2026-09-23-aihub-platform-design.md`
- Modify（**2026-10-02 用户裁定**：原先悬在工作树里的那处改动**跟本任务一起提交**）：
  `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/TokenBucket.java` —— **纯 javadoc 格式**（单行 → 多行，无语义变化），
  2026-10-02 16:22:57 出现，疑似 IDE 自动格式化，与 M4 任何任务无关。⇒ 让它随本任务的提交一起落到 master（见 Step 4 的 `git add`），
  **不要**单开一个提交、也**不要** `git checkout` 还原它（`core.autocrlf` 会把 LF 换成 CRLF）。
- Create: `.superpowers/sdd/m4-acceptance.md`（**git-ignored**，原始输出，token 只留前缀）

**Interfaces:**（本任务**没有代码接口**；下面是它必须逐条写清的**文档契约**，以及验收时必须能观察到的现象）
- CONVENTIONS §4 错误表 + 一行：`insufficient_quota` / `429` / `insufficient_quota` / 触发=周期配额用尽 / 产出=`QuotaFilter`；并在同一处写明**与 `rate_limit_exceeded` 的区别**以及「设计文档 §6.2 的 `QUOTA_EXCEEDED` 已被取代」（注意：**admin 信封**里的 `ErrorCode.QUOTA_EXCEEDED` 仍然存在且是 admin 侧的码，别混）。
- CONVENTIONS §6.7（新增）：配额小节 —— 预扣/校正/对账三段、`0` = 不限（D15）、Redis 不可用时**放行**（D7，与限流的「降级仍拒绝」对照）、`adjust` 非幂等与 02:00 对账是兜底。
- CONVENTIONS §7：迁移纪律 —— 「迁移数量由 `SchemaMigrationTest` 显式钉住；M4 加入第二条（`V2__m4_console.sql`）是有意的，任何人再加必须改那条断言」。
- CONVENTIONS §6.6：吊销生效延迟改为「控制台吊销后**共享层立即失效**（显式 `DEL`），本机 Caffeine 仍 ≤30s」。
- README：M4 段落（做了什么 + 管理台怎么用 + 三个视图）、已知边界新增（配额 fail-open、吊销 ≤30s、对账只报告、`cost = 0`、`localStorage` 的 XSS 代价、D17 的请求体缓存、A3 的 2 秒 Redis 超时、A6 未鉴权不限流、A7 的 404/405 形状）。
- `.env.example`：`AIHUB_CONSOLE_SECRET`（**两种 PowerShell 写法 + openssl**，并说明「必须与 admin 一致、轮换会让已签发的令牌立刻失效」）。
- 设计文档：§6.2 的 `QUOTA_EXCEEDED` 标注为已被 `insufficient_quota` 取代；§7.3 的接口表标注已实现/未实现（`/api/kb/**` 属 M5）。

- **（2026-10-02 控制器补 —— 8 条"照字面执行会出错 / 会交出假证据"）**
  1. **⚠️ Step 2 的基线数字已经过时**：原文写 `common 56 / web 95 / gateway 350 = 501`；**实测现为 `aihub-common` 68 / `aihub-web` 263 / `aihub-gateway` 391 ＝ 722**
     （产物：Task 13–16 的 `.m4t*-logs/`、控制器 `.hb2-logs/C3-admin-full.log`、Task 13 网关 391/0）。
     ⇒ **以实测为准**，报告里**逐个模块报实测数**，**不许**抄这条旧基线（这正是 README 里那些"过时数字"的来源）。
     另：`mvn -B clean test` 是**整反应堆**（gateway 侧要 Docker）⇒ 与其他 Maven **互斥**，且**判据看每模块汇总行**，不看 `[exit code: N]`。
  2. **本任务是 M4 里唯一被授权修改 `docker-compose.yml` 与 `.env.example` 的任务**（其它任务一律禁止改它）⇒ **不算越界**；
     但**只许加这两个变量**：admin 的 `AIHUB_CONSOLE_SECRET`、gateway 的 `AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION`（第 4 步反向对照的开关）。
     **不许**把真实密钥写进任何被提交的文件（`.env.example` 只放占位写法）。
  3. ~~**⚠️ 工作树里有一处不属于本任务的未提交改动**~~ **（已被 2026-10-02 用户裁定取代：跟本任务一起提交，见 Files 第 2 条）**：
     `aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/TokenBucket.java` 是**纯 javadoc 单行→多行**的改动
     （2026-10-02 16:22:57 出现，疑似 **IDE 自动格式化**）。它不是子代理残留变异、也没有语义变化；
     ⇒ **随本任务一起提交**（Step 4 的 `git add` 已含它），**不要**单开提交、**不要** `git checkout` 还原
     （本机 `core.autocrlf` 会把 LF 换成 CRLF）。**它是已知且无害的** —— §8「中止后查工作树四处」命中它时**不要**当成事故处理。
  4. **章节号先核对再写**：`§4`（数据面错误契约）、`§6.6`（渠道密钥与配置快照）都已存在；**`§6.7` 是本次新增**，要**接在 §6.6 之后、`§7` 之前**。
     ⇒ 动笔前用 `Select-String -Pattern '^#{2,3} '` 列出真实章节号 —— 本项目已发生过"引用了一个不存在的 §12"。
  5. **Step 1 的硬规矩**：对每一条新写的边界问「它有没有对应的代码/测试？」；**没有对应证据的句子不许写**，
     并且每条边界要**指向具体产物**（类名/测试名/日志路径），不要写"已实现/已加固"这类无锚点的形容。
  6. **验收记录口径**：`.superpowers/sdd/m4-acceptance.md` 已被 `.gitignore:30`（`.superpowers/`）忽略 ✓ ⇒ **不要试图提交它**。
     脱敏清单**比"token 只留前缀"更宽**：token、`sys_user` 的**口令与 bcrypt 哈希**、`POST /api/api-keys` 返回的**一次性明文 key**、
     `AIHUB_CONSOLE_SECRET`、渠道明文密钥、`api_key_cipher` 密文 —— **都不许**出现在记录或命令输出里；**合成口令也要脱敏**。
  7. **★第 3 步与第 4 步是"结论 + 反证"的一对**：只许在**两条都实测**之后才写"秒级"的结论；若第 4 步（关掉订阅）**也快** ⇒
     **第 3 步结论作废**，必须查出真正原因再声称达成（这就是本任务的 RED 证据）。
     并按附录 **A3** 的口径：Redis 相关降级**必须并发压测**（顺序压测会被 2 秒超时放大成 ≈7 秒/请求而误判）。
  8. **Docker 纪律**：`docker -H tcp://127.0.0.1:2375`；`$env:BUILDX_CONFIG` 指向工作区内；**永不读 `.env`、永不执行 `docker compose config`**；
     `build` 之后用**镜像 ID × 运行容器**逐一核对（M3 的教训：盘上镜像可能还是旧代码），并核对 `compose ps` 与两侧 `/healthz`。

- [ ] **Step 1: 文档改动 + 可证的断言**

对每一条新写的边界，问一句「它有没有对应的代码/测试？」。没有对应证据的句子**不许写**（本仓库历史上出现过文档承诺了不存在的 WARN，被评审打回）。

- [ ] **Step 2: 全量测试**

Run: `$env:DOCKER_HOST='tcp://127.0.0.1:2375'; mvn -B clean test`
Expected: `BUILD SUCCESS`，报告**每个模块**的 `Tests run`（基线 common 56 / web 95 / gateway 350 = 501，M4 之后必然增加）。先删 `target/surefire-reports`，**不要**看 `[exit code: N]`。

- [ ] **Step 3: 真实 compose 全栈验收（**本任务的中心**）**

前置：`docker -H tcp://127.0.0.1:2375 compose build`（`$env:BUILDX_CONFIG` 指向工作区内）→ `up -d --pull never` → 用**镜像 ID 与运行容器逐一核对**（M3 的教训：盘上镜像可能还是旧代码）。**永不**读 `.env`、**永不**执行 `docker compose config`。**脱敏清单（比"token 只留前缀"更宽）**：token、`sys_user` 的**口令与 bcrypt 哈希**、`POST /api/api-keys` 返回的**一次性明文 key**、`AIHUB_CONSOLE_SECRET`、渠道明文密钥、`api_key_cipher` 密文 —— 这些**都不许**出现在 `.superpowers/sdd/m4-acceptance.md` 或任何命令输出里（合成口令也要脱敏：它不是真秘密，但把"口令明文可以进记录"变成习惯才是真正的风险）。

1. **容器与健康**：`compose ps` + `admin/gateway /healthz` 都是 200。
2. **登录**：`POST /api/auth/login`（合成的 `sys_user` 行由本步骤前用一次性 SQL 插入，口令 bcrypt 哈希也由一次性工具生成）→ 拿到令牌 → 带它 `GET /api/channels` 200；不带令牌 401；`VIEWER` 角色 POST → 403。
3. **★ M4 验收标准：控制面配置 → 数据面生效**：用 `/api/channels` **改一条渠道的 `base_url`**（或新建一条路由），然后**只观察数据面**（不做任何人工 `DEL`）：`GET /v1/models` / 发一次 chat 请求，记录「变更时刻 → 数据面可见时刻」。**判据：秒级（≤ 5 秒）**，并且与 M3 实测的 **101 秒 / 删共享条目后 15 秒**形成直接对照。顺带打印 `aihub:config:snapshot` 的 TTL 与网关日志里的「收到配置失效消息」行。
4. **反向对照**：把订阅关掉（`aihub.config.invalidate-subscription=false` 重启网关）→ 同样的变更**不再是秒级**（应回到等 TTL 的行为）。这一步是判据的**判别性证明**：证明第 3 步的快是订阅带来的，不是别的路径。
5. **配额**：通过 `/api/quotas` 给演示租户设一个很小的 `token_limit`（例如 50）→ 连续发请求 → **必须**看到 `429` + `code=insufficient_quota`（**不是** `rate_limit_exceeded`）→ 把配额调回 `0`（不限）→ 请求恢复 200。
6. **审计**：`select action, actor, target_type, target_id, detail from audit_log order by id desc limit 10`，人工确认**没有**任何明文密钥/密文/口令/令牌。
7. **对账**：手工触发一次 `reconcile(LocalDate.now(UTC).minusDays(1))`（或把 cron 临时改成下一次整分）→ 打印 `billing_daily` 行与偏差计数；确认 `quota.token_used` **未**被改动。
8. **回归对照**（M1/M2/M3 的关键契约不能坏）：`GET /v1/models`、流式 `data: [DONE]`、429 限流形状、`request_log` 行（`channel_id`/`api_key_id`）、未注册 404/405 的 Spring 默认体（A7）。
9. **收尾**：把演示改动还原（渠道 `base_url`、配额、路由），停宿主夹具，把真实输出（**删掉 token 明文**）写进 `.superpowers/sdd/m4-acceptance.md`，并把结论同步进 README。

- [ ] **Step 4: 提交**

```bash
# 2026-10-02 用户裁定补一条：那处悬着的纯 javadoc 格式改动（TokenBucket）随本任务一起提交
git add docs/CONVENTIONS.md README.md .env.example docker-compose.yml \
        docs/superpowers/specs/2026-09-23-aihub-platform-design.md \
        aihub-gateway/src/main/java/com/aihub/gateway/ratelimit/TokenBucket.java
git commit -m "docs(m4): record the console, quota and invalidation contracts plus the M4 acceptance"
```

**验收判据：** 第 3 步**秒级**生效且第 4 步（关掉订阅）不秒级；配额超限是 `insufficient_quota`；审计无敏感字段；对账只报告；M1/M2/M3 的关键契约逐条复现。
**RED 证据：** 第 4 步就是第 3 步的反证 —— 如果两条都"快"，说明快的原因不是 Pub/Sub，第 3 步的结论作废（必须查出真正原因再声称达成）。

---
---

## 附录 A：M3 → M4 交接清单（**每一条都有证据**，M4 必须逐条处理或显式登记为不做）

本附录由 M3 的收口轮与 D1/D4 修复轮实际测量得出。**它不是背景介绍，是 M4 的输入**：其中 A1/A2 不修，M4 的验收标准就无法演示。

| # | 交接项 | 证据（在哪能看到） | M4 怎么处理 |
|---|---|---|---|
| A1 | **配置变更不会主动到达数据面**：改 `channel.base_url` 后 **101 秒**新快照仍不可见；只有删掉 `aihub:config:snapshot` 才在 **15 秒**内收敛。原因是本地 30 秒 TTL 过期后，网关从 Redis 读到的还是**同版本的旧快照**，于是根本不回源。 | README「已知边界」、`docs/CONVENTIONS.md` §6.6、`.superpowers/sdd/m3-acceptance.md` §7 发现 2 | **Task 2 + Task 4**（Pub/Sub 发布方 + 订阅方 + `invalidate()` 同时清共享条目与水位）。**M4 的验收标准「控制面配置 → 数据面生效全链路打通」就靠这条**。 |
| A2 | **快照 `version` 会倒退**：`version = max(updated_at)`，删掉最新那一行版本就变小，而网关与 Redis 两侧的比对都是严格 `>`，于是更旧的快照被 lastGood 记住并继续服务；`ConfigSnapshotService` 既不检测也不告警。**M3 时零缓解措施**。 | `docs/CONVENTIONS.md` §6.6 末尾、README 已知边界；D1/D4 轮的验收**意外触发过它**（`.superpowers/sdd/m3-acceptance.md` §11.8） | **Task 3**（`config_version` 水位 + 读时抬升）。 |
| A3 | **网关自己的 Redis 命令超时仍是 2 秒**：Redis 停机时一个请求要赔多次超时 —— 实测 **16 个顺序请求花了 110 秒（≈7 秒/请求）**；只有把请求**并发**发才能观察到限流降级后的 429。 | `.superpowers/sdd/m3-acceptance.md` §11.5 | **本计划不改**（做成粘性短路会破坏既有断言 `ApiKeyFilterContractTest.redisOutageStillResolvesThroughAdminWithBothRedisCallsOffTheCallersThread`）。**登记在 README 已知边界**，并把「Redis 停机时必须并发压测」写进 Task 17 的验收口径。 |
| A4 | **API Key 吊销的生效延迟**：本机 Caffeine ≤30s、集群 Redis ≤5m；M3 决策 16 明确把「吊销接口 + 显式 DEL」交给 M4。 | `docs/CONVENTIONS.md` §6.6、M3 计划决策 16 | **Task 9**（显式 `DEL` 共享键）+ 残余登记（本地 30 秒窗口仍在）。 |
| A5 | **`request_log.channel_id` / `api_key_id` 没有索引**：按渠道/按 Key 聚合会全表扫描；M3 不加迁移，留给 M4。 | `docs/CONVENTIONS.md` §6.5 末、README | **Task 1**（V2 里加两个索引）+ **Task 11**（`/api/logs` 的分区裁剪友好查询）。 |
| A6 | **未鉴权的 `/v1/**` 请求永远不会被限流**（鉴权 `+100` 在限流 `+150` 之前）。 | `docs/CONVENTIONS.md` §6.6 | M4 **不改**（配额同理排在鉴权之后）。**登记**在 README：未鉴权洪峰不在保护范围内。 |
| A7 | **`/v1/**` 上没有处理器的 404/405 返回 Spring 默认体**，不是 OpenAI 形状。D1/D4 轮**逐条复现**，形状与 M3 那次逐字相同。 | `docs/CONVENTIONS.md` §4 开头的引用块、`.superpowers/sdd/m3-acceptance.md` §11.7 | M4 **不改**（属打磨，登记在 README）。Task 17 的验收里**保留**这条复现（它现在是一条「已知且未变」的对照）。 |
| A8 | **`ApiKeyView.UNUSABLE` 已无任何生产调用方**（D4 把过滤器的空 Mono 兜底换成了 `AdminResolution.unavailable()`）。 | D4 修复轮的窄口径复审记录、`aihub-common` 的 javadoc | M4 **二选一**：给 `DEL` 路径一个用途（Task 9 的「已吊销」视图语义可以复用它）或删掉它。**不要把死代码留在共享类型上**。 |
| A9 | **控制面故障 ⇒ 503 `service_unavailable`，且故障结论不被缓存**（D4 裁的）。M4 新增的 `/api/**` 必须遵守同一条纪律：**不要把平台故障伪装成凭证错误**（D16 就是这条纪律的直接应用）。 | `docs/CONVENTIONS.md` §4/§5、`.superpowers/sdd/m3-acceptance.md` §11.6 | Task 6 的 D16 分支 + Task 17 的文档收口。 |
| A10 | **限流与配额是两件事**，M3 只做限流（QPS/burst），配额整体属 M4；**不要把 429 `rate_limit_exceeded` 与配额混为一谈**。 | `docs/CONVENTIONS.md` §6.6 末、M3 计划决策 12 | **Task 12/13**（D6 定了配额的数据面错误码是 `insufficient_quota`）。 |
| A11 | **`channel.models_json` 的语义未定**（M3 登记为「M4 的控制台一并定」）。 | M3 计划「不做的事」、README | **D14：定为展示用**，路由仍以 `model_route` 为唯一真相。 |
| A12 | **主动探活属 M4**（M3 只做「失败后标记」）。 | M3 计划「不做的事」 | **Task 11**（`POST /api/channels/{id}/probe`）。 |
| A13 | **网关的 meter 只覆盖 `POST /v1/chat/completions` 一个端点**：`GET /v1/models`、未鉴权/未注册的响应都不落 `request_log`。 | README 已知边界、CONVENTIONS §6.5 | M4 **不改**（配额按「请求」计数时要知道这一点：`/v1/models` 不计费也不扣配额，Task 13 的配额过滤器只作用于 chat/completions）。 |
| A14 | **没有配置写入方**（M3 决策 16）：`aihub:config:snapshot` 只能靠 TTL 或人工 `DEL` 失效；`ConfigClient.invalidate()` 今天**只清本地**，紧接着仍会采用 Redis 里的旧条目。 | `ConfigClient.invalidate()` 的 javadoc（自己就登记了这条缺口） | Task 4 的修复点。 |
| A15 | **Docker/Maven 的既有陷阱清单**（`-Dtest=A,B` 逗号、`-am`、退出码不可信、陈旧 `.class`、`--pull never`、`BUILDX_CONFIG` 必须重定向进工作区、`[System.IO.File]` 不认 `Set-Location`、中文文档禁 `Get-Content`）。 | 全局约束的「本机环境前提」；M3 计划同一节 | 每条都已在全局约束里复述，**不要重新踩**。 |

---

## 附录 B：执行顺序、并行边界与共享契约的落地顺序

**必须串行的前置链**（共享契约的存在性）：

```
Task 1 (V2: audit_log / config_version / 两个索引)
  ├─> Task 3 (版本水位)  ──┐
  ├─> Task 2 (失效契约)  ──┴─> Task 4 (网关订阅 + invalidate 修复)   ← M4 验收标准的前置，**最先做完**
  └─> Task 7 (审计服务, 需要 audit_log) ─> Task 8/9/10 (CRUD 写路径都要审计)
Task 5 (bcrypt 验证 + 令牌契约) ─> Task 6 (登录 + 过滤器) ─> Task 8/9/10/11
Task 12 (配额契约 QuotaScript/Keys/Period) ─> Task 13 (网关 QuotaFilter) ─> Task 14 (internal reserve)
Task 12 ─> Task 15 (对账)
Task 6/8/9/10/11 ─> Task 16 (管理台静态页)
全部 ─> Task 17 (文档收口 + 全栈验收)
```

**可以并行的**（互不共享文件、也互不共享契约：
`Task 2 ∥ Task 3`、`Task 5 ∥ Task 1`、`Task 8 ∥ Task 9 ∥ Task 10`、`Task 12 ∥ Task 16 的静态资源骨架`。
**不允许并行**：任何两个都要改同一个 `ConfigSnapshotService` / `application.yml` / `CONVENTIONS.md` 的任务 —— 那些文件在多个任务里出现，必须串行（工作树里还有一个第二写入者）。

**契约落地顺序（违反就是「跨任务占位」，评审会打回）**：

1. `V2__m4_console.sql` 的**列与索引**必须在 Task 7/9/11 之前存在（Task 1）。
2. `ConfigInvalidateTopology.CHANNEL` 与 `ConfigInvalidateCodec` 的**载荷格式**必须在 Task 2 落地，Task 4 才能订阅 —— 两端共用同一常量，**不许各写一份字面量**。
3. `ConsoleToken`/`ConsoleClaims` 的**字段与角色常量**必须在 Task 5 落地，Task 6 才能签发/校验；Task 16 的前端只读 `role` 字符串。
4. `QuotaScript`/`QuotaKeys`/`QuotaPeriod`/`QuotaDecision` 的**签名与 Lua 的 ARGV 顺序**必须在 Task 12 落地，Task 13/14/15 才能用；**Lua 脚本与键布局只有一份实现**（与 `RateLimitScript` 同一纪律）。
5. `/api/**` 的**错误码与信封**（`UNAUTHORIZED` / `FORBIDDEN` / `CONFIGURATION_ERROR` / `INVALID_PARAM` / `NOT_FOUND`）在 Task 6 定型，后续所有控制器复用同一套 `ErrorCode`，**不要新增私有错误体**。（`ErrorCode` 现有成员：`INVALID_PARAM(400)`、`UNAUTHORIZED(401)`、`FORBIDDEN(403)`、`NOT_FOUND(404)`、`RATE_LIMITED(429)`、`QUOTA_EXCEEDED(429)`、`UPSTREAM_ERROR(502)`、`INTERNAL_ERROR(500)`；M4 **只新增** `CONFIGURATION_ERROR(500)`。注意 `QUOTA_EXCEEDED` 是 **admin 信封**里的码，数据面用的是 `insufficient_quota`，两者别混。）

---

## 附录 C：M4 结束时「完成」的定义

1. 全反应堆 `mvn -B clean test` 绿，且报告**实测**数字（基线 501，M4 之后必然更多）；`aihub-gateway` 的测试仍然不依赖 Docker / 活 Redis / 活 broker。
2. 一条迁移（V2）、一个依赖（`spring-security-crypto`）之外，**没有任何越界改动**；`SchemaMigrationTest` 的迁移清单断言是**显式**的。
3. 「控制面配置 → 数据面生效全链路打通」在真实 compose 全栈上**可重复演示**：通过控制台/API 新建或修改一条渠道/路由，数据面在**秒级**内可见（对比 A1 的 101 秒），**并且**过程中不需要人工删 `aihub:config:snapshot`。
4. 配额链路可演示：设一个小的 `token_limit` → 超额请求拿到 **429 `insufficient_quota`**（数据面 OpenAI 形状，**不是** `rate_limit_exceeded`）→ 对账任务按 `request_log` 重算 `billing_daily` 并报告偏差。
5. 审计可演示：每一次写操作都有 `audit_log` 行，且**任何一行都不含**明文密钥 / 口令 / 密文 / 令牌。
6. 文档三处同步：`docs/CONVENTIONS.md`（新错误码 + 控制台/配额小节 + 迁移纪律）、`README.md`（做了什么 + 怎么用管理台 + 已知边界）、设计文档（§6.2 的 `QUOTA_EXCEEDED` 标注为已被 `insufficient_quota` 取代）。
7. 所有**残余**都写进 README 的已知边界（配额 fail-open、吊销本地 ≤30s、对账只报告不自动改账、`billing_daily.cost = 0`、`localStorage` 令牌的 XSS 代价、A3 的 2 秒 Redis 超时、A6 未鉴权不限流、A7 的 404/405 形状）。
---

## 附录 E：独立评审的处置记录（**逐条**，含未修的）

本计划在提交后经过一次独立评审（只读、逐条对代码与 jar 核验），结论是 **Needs work before execution：7 Critical / ~15 Important**。下面把每一条都记下来 —— **包括没有修的**。评审的意义在于「没有一个发现被静默丢掉」。

### E.1 Critical（7 条，全部已修）

| # | 发现 | 处置 |
|---|---|---|
| C1 | `ServerWebExchangeUtils.cacheRequestBodyAndRequest` **在 Spring 6.2.19 里不存在**（`spring-web` 连 `web/server/support/` 包都没有），D17 与 Task 13 的片段编译不过；而且原片段的顺序是「释放 join 出来的 buffer，再把**原** exchange 传下去」——正好会把请求体在转发前 free 掉 | 改成 `DataBufferUtils.join` → 复制进 `byte[]` → **先复制再释放** → `ServerHttpRequestDecorator` + `exchange.mutate().request(...).build()` → 把**装饰过的** exchange 传下去。Task 13 的片段与 D17 一起改 |
| C2 | 给 `GatewayConfigProperties`（record）加第 5 个分量会打断 **8 处** `new GatewayConfigProperties(...)`（`ConfigCacheTest` 7 + `ModelsControllerTest` 1），而那两个文件不在 Task 4 的 Files 里，Step 4 却要求 `ConfigCacheTest` 绿 | 新增独立的 `ConfigInvalidateProperties`（`aihub.config.invalidate-subscription`）+ `ConfigInvalidateSubscriptionConfig`，**不碰** `GatewayConfigProperties` |
| C3 | Task 3 三重失败：① 水位持久化后 `ConfigSnapshotServiceTest` 的「空库 version=0」会红；② 测试里的构造器签名（5 参）与既有的 5 参不同、与计划的 6 参也不符；③ **在 `@Transactional(readOnly = true)` 的 `snapshot()` 里写库** → MySQL + Connector/J 的 `readOnlyPropagatesToServer` 默认开 → `ERROR 1792`，打坏的正是 `GET /internal/config/snapshot` | ① 新增 `ConfigSnapshotServiceTest` 的 `@BeforeEach` 显式归零水位 + 用例更名，理由写在测试里；② 明确声明构造器变 6 参并列出必须跟改的调用方；③ **`currentVersion()` 变纯读**，水位只在写入路径（`bumpAndPublish`）抬升 —— D5 一并改写，并登记「raw SQL 删除仍可能回退一次」的残余 |
| C4 | Task 1 会让 `SchemaMigrationTest` 留下红：`allTenTablesExist` 断言**恰好 10 张表**；期望的 `Tests run: 3` 也错（会是 6）；还说「保留 Flyway 调用不变」——那个类**根本没有** Flyway API，它用 `JdbcTemplate` 数历史表 | 三处一起改：断言改成 JDBC 查 `flyway_schema_history`（不引 Flyway API）、`allTenTablesExist` → `allTwelveTablesExist`（10→12）、期望值改 6；并撤掉「钉住校验和」的说法（只钉 version + description） |
| C5 | Task 17 的 compose 验收**跑不起来**：`docker-compose.yml` 的 env 是**显式白名单**，没有 `AIHUB_CONSOLE_SECRET` → 容器里 secret 为空 → D16 fail-closed → 登录 500，第 2–7 步全废 | 全局约束新增一条：Task 17 **必须**改 `docker-compose.yml`（admin 加 `AIHUB_CONSOLE_SECRET`；gateway 加 `AIHUB_CONFIG_INVALIDATE_SUBSCRIPTION`，验收第 4 步的反证要用它）+ `.env.example` 加生成方法；除这三处外不许再动 compose |
| C6 | Task 13 的校正点**不存在**：`ChatRelayController` 看不到 `usage`（`UsageCapture` 是 `RelayMetering` 内部物，usage 只在 `metering.toEvent(signal)` 被物化，那句在 `doFinally`） | 新增 `QuotaCorrector` 并**明确改法**：把 `doFinally` 那一行展开成「`toEvent` → `publish`（既有行为不变）→ `quotaCorrector.correct(event)`」；File Structure 与 Task 13 的 Files 一并补上 |
| C7 | `raiseTo` 的返回值断言**不可能同时成立**（ODKU 更新已存在行返回 2；Connector/J 默认 `useAffectedRows=false` 即置了 `CLIENT_FOUND_ROWS`，更新成相同值又返回 1）；`bumpAndPublish` 里用 affected rows 判断"发生了什么"的写法同样无意义 | Task 1 的用例改成**只断言值**的语义（并写明为什么不断言 affected rows）；`bumpAndPublish` 改成「先 `raiseTo(now)`、再读一次 `current()` 取 max」 |

### E.2 Important（15 条）

| # | 发现 | 处置 |
|---|---|---|
| I1 | Task 12 的 `reserve()` 把 `tokenLimit` 写死 1000，于是「0 = 不限」那条用例自相矛盾；两条用例还共用同一个 `(tenant, period)` 桶 | **已修**：`reserve(tenantId, estimatedTokens, tokenLimit)`；两个用例各用独立 `tenantId`（101 / 102） |
| I2 | `QuotaFilter` 的阻塞 Redis 调用没有 `subscribeOn`，违反 `RateLimitFilter` 与 CONVENTIONS §6.6 的「判定整体在 LIMITER_SCHEDULER 上」 | **已修**：片段末尾加 `.subscribeOn(LIMITER_SCHEDULER)` 并写明理由（网关侧 Redis 超时仍是 2 秒） |
| I3 | `QuotaResolver` 说「按 `tenant + api_key`」——但 `quota` 表**没有** `api_key_id` 列，D13 自己也说桶是租户级 | **已修**：File Structure 与 Task 13 的 Consumes 都改成 **`tenant_id + period`**，并加一句「别把配额和限流的两维搞混」 |
| I4 | `channelKeyService.currentVersion()` 方法名不存在（真名 `currentKeyVersion()`） | **已修** |
| I5 | 附录 B 列的 `VALIDATION_ERROR` 在 `ErrorCode` 里不存在（真名 `INVALID_PARAM`） | **已修**：改成 `INVALID_PARAM`，并顺手把 `ErrorCode` 现有成员与「admin 的 `QUOTA_EXCEEDED` ≠ 数据面的 `insufficient_quota`」写在同一条里 |
| I6 | 登录的防枚举只做了一半：用户名不存在时**不跑** bcrypt，约 100 ms 的时序照样能枚举用户 | **已修**：Task 6 明确要求不存在时也对一个常量 dummy bcrypt 哈希跑一次 `matches` |
| I7 | `ConsoleToken.verify` 声称「任何失败都抛 `IllegalArgumentException`」，但片段里的 Jackson `read(...)` 抛的是**受检**异常，漏出来就是 500 而不是 401；`sub` 与 `userId` 的映射也没写 | **已修**：Produces 里加「必须 `catch (RuntimeException \| JsonProcessingException)` 转 IAE」；载荷注释改成「固定五个字段，`sub` ↔ `userId` 写死这一对」 |
| I8 | 两条测试在自己那个任务里不可能通过：Task 6 断言 `GET /console/index.html` 200（那些文件 Task 16 才有）；Task 4 的 `ApplicationContextRunner` 只喂了 `StringRedisTemplate`+`ConfigClient`，而 `RedisMessageListenerContainer` 需要 `RedisConnectionFactory`，且 `ConfigSubscriber` 是 `@Component` 不会被 `withUserConfiguration(ConfigConfig.class)` 注册 | **部分修**：Task 4 的接线测试改成**专用** `ConfigInvalidateSubscriptionConfig` + mock 的 `RedisConnectionFactory`/`ConfigSubscriber`，并加「关掉开关就没有 bean」的反向断言。**未修**：Task 6 的那条静态资源断言 —— 见 E.3 |
| I9 | `invalidateAllCaches()` 把水位重置成 `NO_VERSION`，等于拆掉了「挡住在飞的旧回填重新毒化共享条目」的唯一护栏；而失效消息里的 `version` 只用于打日志 | **已修**：签名改成 `invalidateAllCaches(long version)`，水位**抬到消息里的版本**；用例改成「版本 5 的写入被拒、版本 9 的写入放行」 |
| I10 | 三处已登记但无归属：① README 里「区分 503 与 key-not-found 的计数器」是 M4 待办；② 附录 A8 要求 M4 给 `ApiKeyView.UNUSABLE` 一个结论；③ **审计没有读取路径**（而审计是 M4 交付物） | ① **未做，登记为仍未做**（理由：网关到 M6 才开放 `/actuator/metrics`，今天没有暴露面；运维信号是 ERROR 日志 + 新增的 503 状态码）——README 的已知边界要保留这一条；② **已修**：Task 9 Step 3 明确删除该常量并同步 `ApiKeyToolingTest`（若删不掉就说明还有调用方，要在报告里给结论）；③ **已修**：Task 11 新增 `AuditQueryService` + `GET /api/audit`（强制 tenant + 时间范围、分页有上界） |
| I11 | Task 15 的 UPSERT 没有任何 Mapper 方法承载；`VALUES()` 在 8.4 已 deprecated；`RequestLogQueryService.page` 的 mapper 方法与 `RequestLogView` 也没定义 | **已修**：UPSERT 定为 `BillingDailyMapper.recomputeDaily(from, to)`（并写明「行别名对 `INSERT ... SELECT` 有语法限制，先在真库上试，采用哪种要在报告里登记」）；`RequestLogView`/`AuditLogView` 与分页上界写进 Task 11；并补了「`usage_missing`/`client_disconnected` 是已知近似值，偏差计数天然包含它们」的口径提醒 |
| I12 | Task 7 的 RED 测试用了 Task 8 才有的 `ChannelCreateRequest`/`ChannelView`，而 Task 7 排在前面；`AuditService.record` 里的 `MAPPER.writeValueAsString` 抛受检异常且未处理 | **已修**：Task 7 改用自己的事务探针（不依赖 Task 8），并显式要求 catch 受检异常 → `IllegalStateException` |
| I13 | Task 9 把接口签名留到实现时决定（「先读代码再决定」），违反本计划自己的「无占位符」规则 | **已修**：Task 9 Step 3 给出 `ApiKeyCreateRequest`/`ApiKeyCreated`/`ApiKeyView` 与 `ApiKeyAdminService` 的完整签名，并把「生成逻辑只许有一份」写成硬约束 |
| I14 | 相对 M3 计划（9078 行、每步都给完整代码），M4 把若干任务的 Step 2–4 收成一句话，测试草图里还出现 `post/get/jsonPath/read/postBytes/stubQuota/activeRowsFor/createTenantPolicy/TestSupport::get` 等未定义 helper，以及 `AbstractIntegrationTest` 并不提供的字段 | **部分修**：全局约束新增一条，写清 admin 集成测试的既有惯用法（`@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate` + 显式 `HttpEntity`，基类是 `AbstractIntegrationTest`，它**不提供** `redis`/`upstream`/`container`/各 Mapper 字段），并明确「全文那些 helper 是**行为伪代码**，每个任务的 Step 1 必须落成真实的 `TestRestTemplate` 调用」。**残余（诚实说）**：M4 仍然刻意比 M3 更依赖实施者去写代码细节 —— 这是**有意的取舍**（把体积花在决策与验收判据上），但它要求实施者与评审者都更严格；如果执行时发现某个任务的接口不够定死，**按「跨任务占位」处理：停下来把它补进计划再继续**，不要临场发挥 |
| I15 | `QuotaDecision.java` 没进顶层 File Structure；某行把「日志索引」标成 D11（D11 是 API Key 的显式 DEL）；Task 15 的依赖漏了 Task 11 | **已修**：三处都改（`QuotaDecision` 补进表里、索引改标 D1/索引在 V2、任务索引的 Task 15 依赖改成 `11, 12`） |

### E.3 仍未修（**明确登记**，不要当成已经解决）

1. ✅ **已于 Task 6 执行期（2026-09-29）解决** —— 原条目：Task 6 的 `theConsoleAssetsAreNotBehindTheTokenFilter`（I8 的后半）断言 `GET /console/index.html` 200，而那些文件到 Task 16 才存在，**按字面执行不了**。二选一里选了「**删掉该断言**」：Task 6 的正文现在明确写「本任务**不要写** `GET /console/index.html` → 200；它属 Task 16」，登录页可访问性由 Task 16 的 `ConsoleStaticResourceTest` 覆盖。**编号保留**是为不打乱 E.3-2…E.3-10 在别处（F.4/F.5、附录 G）的引用。**本条不再是未修项。**
2. **I10①（503 vs key-not-found 的计数器）**：M4 不做，README 保留为 M4 之后仍未做项。
3. ✅ **已于 Task 7 执行期裁定（2026-09-29）** —— 原条目：`audit_log.tenant_id` 可空，而 `AuditService.record(long tenantId, …)` 是原始类型，`LOGIN_FAILURE` 这类事件会写 `0` 而不是 `NULL`。**选了「参数改成 `Long`、无租户事件传 `null`」**：`audit_log.tenant_id` 是 `BIGINT NULL` 且 `AuditLogEntity.tenantId` 本来就是装箱 `Long`，所以这个选择**不动实体、不动迁移**；而 `0` 与真实 id 空间无法区分（id 从 1 开始），M2 那个 `request_log.tenant_id = 0` 哨兵是列 `NOT NULL` 逼出来的、不是更优解。已写进 Task 7 的 Interfaces，并要求一条用例断言「`null` 落库后读回来仍是 NULL，不是 0」。**本条不再是未修项。**
4. **`QuotaEstimator` 的 `maxInMemoryBytes`**：它读请求体，而网关还有计量侧的捕获上限（`aihub.metering.max-capture-bytes`）。**上限必须小于计量侧上限**，否则控制器先失败、计量根本看不到这次请求。执行 Task 13 时确认这两个数字的关系并写进配置注释。
5. **`MeteringSchedulingConfig`**：评审指出它可能只是 `@EnableScheduling`、`@Scheduled` 应当直接标在 job 上 —— Task 15 的 Files 里列了它；执行时若确实只是 `@EnableScheduling`，**不要**为了「让 Files 清单成立」而制造一次无意义改动（改动清单以实际需要为准，报告里说明即可）。
6. **§10 的测试覆盖率目标（核心链路 ≥70%）** 在本计划里**没有被测量**：M4 收口时若时间允许，用 JaCoCo 量一次并写进 README；不允许在没有测量数据的情况下声称达成。
7. **Task 6 的登录接口没有任何限流/锁定**（`/api/auth/login` 可无限次尝试）：这是 M4 已知边界里**最大的安全残余** —— 它把"口令错与用户不存在响应不可区分"重新变成"可统计采样"（多次采样后，假哈希与真实哈希的 cost 档位差可能从噪声里分离出来）。M4 未要求限流，**不阻塞**，但 Task 17 收口时必须写进 README 的已知边界，不许当成已解决。
8. **非 ASCII 密钥的运维链路（环境变量 → `aihub.console.secret` → UTF-8 字节）没有任何用例覆盖**：结构上正确（`ConsoleTokenService.secretBytes()` 是全仓库唯一一处把密钥转字节的地方，UTF-8，签发/校验共用），但"未验证"不等于"对"——Windows 上非 ASCII 环境变量是已知坑。属登记项，不是缺陷。
9. **`ConsoleAuthFilter` 的 `@Order(Ordered.LOWEST_PRECEDENCE - 100)` 与 `InternalAuthFilter`（无 `@Order`）的相对顺序既没有用例、也没有写进 javadoc**：今天两个守卫的前缀不相交（`/api/**` 与 `/internal/**`），所以顺序不可观测，既有 `/internal/**` 契约未被破坏（有 3 个类的用例钉着）。风险是纯未来的：将来有人给 `InternalAuthFilter` 加 `@Order` 时不会有任何东西报警。
10. **Task 6 有三条断言在"功能被删掉"时依然会绿**：`pathsOutsideTheApiPrefixAreNotGuardedEvenWhenTheGateIsClosed`（把整个过滤器删掉也绿）、`anAdminWriteIsPassedThroughToTheMvcLayer`（一个"永远放行"的 fail-open 过滤器也绿）、以及 `aBlankConsoleSecretFailsClosedWithConfigurationError` 的正文断言（它分不清 D16 的配置分支与过滤器里的 `IllegalStateException` 兜底分支，两者共用同一句文案 —— 变异 A2 存活就是这条）。前两条是**设计如此**的负向/边界用例，其真正的判别力由集成用例补足（`internalAndHealthEndpointsAreNotAffected`、`InternalAuthFilterContextPathTest`），不是缺陷；第三条的两个分支都是 fail-closed、在响应上**观测不可区分**，因此**不追**（用一个观测不到的差异去换判别力只会得到脆弱用例）。
11. **`AuditAction.LOGIN_SUCCESS` / `LOGIN_FAILURE` 目前没有生产方** —— Task 6 的正文写着「审计调用**留到 Task 7**；如果 Task 7 已经完成，就把 `auditService.record(...)` 的调用一起写上」（Task 6 的 Interfaces/Step 3 附近）。Task 6 排在 Task 7 之前，所以这条条件式要求**现在到期了**，但它没有被折进 Task 7 的 Files —— 已裁定**不塞进 Task 7 的第一版**。理由：它要改的是 Task 6 刚评审过的**防枚举路径**，而 `ConsoleAuthService.login` 当前**没有 `@Transactional`**；往失败路径加一次 DB 写会引入事务边界并改变该路径的观测面，属于必须自带 RED→GREEN 证据的改动，不适合搭在「审计服务本体」这一任务上。**归属：Task 7 之后的一个专门提交**（并在 Task 17 收口时确认它已落地）。**硬要求**：① 每一次登录尝试（成功与失败）**恰好**写一行；② `LOGIN_FAILURE` 在「用户名不存在」与「口令错」两条路径上产生的审计行**必须结构相同**（action/targetType/actor 形状一致、`tenantId` 均为 `null`）—— 否则审计的写入**次数或内容**本身就成了用户枚举旁路；③ **绝不**把口令或 bcrypt 哈希写进 `detail`；④ Task 6 既有的防枚举用例必须继续绿，并新增用例钉住「两条失败路径各恰好一行且结构相同」（用「只在找到用户之后才审计」的变异体打红它）。
12. **`ConfigSnapshotService.maxUpdatedAt()` 的时间换算早了 8 小时**（2026-09-29，Task 7 修复轮的有界排查实测）：它对 `channel`/`model_route`/`rate_limit_policy` 用 `queryForObject(..., Timestamp.class).toInstant().toEpochMilli()`，实测表达式 `1790654157526` vs 数据库时钟真值 `1790682957526` = **−8.0 小时**（**量的是测试容器的无参数 URL 方言 —— 见下方的更正块，发布 URL 上这个差值是 0**）；水位为 0 时 `currentVersion()` 因此比 `Instant.now()` 落后 2880 万毫秒。**今天被掩盖**：`ConfigChangePublisher` 用 `System.currentTimeMillis()` 抬水位，所以正常写路径的水位是对的。**残余**：纯 SQL / seeder 路径（水位为 0、`max(updated_at)` 是唯一来源）会得到一个静默的、约 8 小时不收敛的窗口。**归属**：已由 `a14e209` 的专门硬化提交**落地**（改法照 `RequestLogService`：读成 `LocalDateTime` 再按 UTC 折算），Task 17 验收时确认。**Task 7 本身没有改它。**
    > **2026-09-29 第二次独立评审的更正（触发条件）**：上面那组数字是在**测试容器的连接方言**上量到的 —— Testcontainers 把 `spring.datasource.url` 给成**无参数**的裸 URL，驱动因此按 **JVM 默认时区**解释 `datetime`（本机 Asia/Shanghai）。**发布的两个 URL 都钉了 `serverTimezone=UTC`**（`application.yml:8`、`docker-compose.yml:65`），在那条连接上评审实测**旧写法与新写法逐位相等（`old − new = 0`）** ⇒ **这不是生产上正在发生的缺陷**。所以本条的准确表述是「**代码依赖连接时区**」，触发条件就是「**连接时区不是 UTC**」；**JVM 默认时区只在连接时区解析成 LOCAL 时才起作用**（URL 不带 `serverTimezone` / `connectionTimeZone`），一个**显式钉死的非 UTC 区**（`connectionTimeZone=Asia/Shanghai`）在**任何** JVM 时区下都触发 —— 2026-09-30 复测：`-Duser.timezone=UTC` 下裸 URL（LOCAL）`delta = 0`，而同一次运行里 `connectionTimeZone=Asia/Shanghai` 仍是 `delta = +28800000 ms`。**已在 `a14e209` 硬化**（读 `LocalDateTime` + 显式 `toInstant(ZoneOffset.UTC)`）：价值是**不再依赖那个连接参数**，而不是修掉一个线上错误。方言现在是显式选择并被断言（`AbstractIntegrationTest` 钉生产方言 + `ConnectionTimeZoneFlavourTest` + 故意钉**非 UTC 连接时区**的独立上下文 `TimeBasisIsConnectionFlavourIndependentTest`，见 `docs/CONVENTIONS.md` §8）。
    > **更正记录（2026-09-30）**：上面这段的前一版把触发条件写成「连接时区解析成 LOCAL（或不带该参数）**且** JVM 默认时区不是 UTC」，并把 `TimeBasisIsConnectionFlavourIndependentTest` 误标成「**LOCAL 方言的**」类 —— 两句都被本轮复测推翻（前者排除了本轮自己的判别上下文，后者与 `docs/CONVENTIONS.md` §8 记载的方言相反）：**以本段文字为准**。已推送的 `43a5502` 提交信息里仍带着那个过窄的措辞（本仓库不改写已推送的历史），**那条提交信息已被本段取代**。
13. **`ApiKeyEntity.expireAt` 的原始列与兄弟列不同基准**：在**连接时区不是 UTC** 的方言下，驱动把 `Instant` 字段折成**本地墙钟**写进 `api_key.expire_at`，而同一张表里数据库生成的 `created_at`/`updated_at` 是 UTC 墙上时间 —— 任何把 `expire_at` 与 `now()`/`utc_timestamp()` 放进**同一条 SQL** 的比较都会偏一个时区偏移，裸 SQL 写入方（seeder / 运维）看到的就是这一格。**（原文此处写「它参与判定（`ApiKeyView.usable()`），方向是 fail-closed（提前 8 小时过期）」—— 这一句已被 2026-09-29 第二次独立评审推翻，见下方更正块。）** 归属同第 12 条（**已由 `a14e209` 落地**）。
    > **2026-09-29 第二次独立评审的更正（「提前 8 小时过期」被推翻）**：`ApiKeyView.usable()` **没有**被判错 —— 修复前的实体往返是**自洽**的（写入与读回走同一次连接时区换算、互相抵消），评审实测往返偏差 `231600 ns`、`usable()=true`，实现者自己的变异日志也是「按 JVM 默认时区折回才是期望值，**差值 −1 ms**」而**不是 8 小时**。因此本条正确的表述是：**原始列的基准与兄弟列不一致**（在连接时区不是 UTC 的环境里，列里存的是本地墙钟），受影响的是裸 SQL 写入方与 `now()`/`utc_timestamp()` 的跨列比较；**不是**「key 提前 8 小时过期」。已在 `a14e209` 硬化（字段改 `LocalDateTime` + 两端显式 UTC 换算）。**另有一条数据含义变更必须记住（独立评审 I-4；方向按 2026-09-30 复测改正）**：把已存的 `expire_at` 从「连接时区墙钟」改读成 UTC，位移量 `new − old` **等于那条连接的偏移**，方向由它的符号决定 —— 偏移为正（如 `Asia/Shanghai`，复测 `+28800000 ms`）瞬时**后移**、已经过期的 key 还会多活**一个偏移**那么久（fail-open）；偏移为负（如 `America/New_York`，复测 `−18000000 ms`）瞬时**前移**、key **提前**过期（fail-closed）。位移最多**一个连接偏移**、方向不定，**不是固定的 8 小时**（8 小时只是本机 Asia/Shanghai 的观测值）。发布 URL 是 UTC（偏移 0），所以发布配置下不存在这种行，但覆盖过 `SPRING_DATASOURCE_URL` 的**非 UTC** 环境需要一次性迁移（办法方向相关：按带符号的偏移 `UPDATE`，见 `docs/CONVENTIONS.md` §7 第 4 条）或重新签发；同一个符号也决定 `updated_at` 的版本跳变方向 —— 为负时版本**向后**跳，那是**已登记的 M3 版本回退缺口**（网关严格 `>` 比版本），**不是「无害」**。归属同第 12 条（**已由 `a14e209` 落地**）。
14. **`ConfigVersionEntity.updatedAt` 实体读回早 8 小时，但全仓库没有读者**（水位只用 `version` 列）。登记，不修；若将来有人读它，按第 12 条的办法改。
15. **Task 7 明确不修、不许当成已解决的残余**：① **形状不可辨的密钥用作 map key 仍会落库** —— 启发式做不到「key 文本永不进库」，`[REDACTED_KEY]` 只覆盖能被值形状识别的 key；② F1 **没有**对整个调用方 map 调 `valueToTree`（自引用 detail 会在深度上限生效**之前** StackOverflow）；Map/Iterable 由实现自己带深度上限遍历，只有未知类型交给 Jackson；③ 评审的 Minor 4/5（`action` 的长度校验、`null`/空 map → SQL NULL）**没有用例**；④ `[REDACTED]` 是**替换值**，因此 `tokenCount` 这类合法键会被误伤（已在类 javadoc 写明并有用例钉住，属刻意接受的代价）。
16. **变异测试的环境陷阱（Task 7 修复轮踩到，值得全项目记住）**：跑完变异后 `target/classes` 里留着的是**变异体字节码**，还原源文件**不会**把它换回来 —— 必须显式重新编译；更糟的是，当「修复 diff vs HEAD」本身非空时 `git diff` **根本无法证明**已还原。可靠做法是「与备份文件比 SHA256 + 扫描 `MUTANT` 标记 + `javap` 看真实字节码」。Task 7 修复轮里第一次自以为干净的 `javap` 实际又是一份变异体副本。

### E.4 六条承重技术论断的评审结论（记录，供执行时参照）

| # | 论断 | 结论 | 计划里的处置 |
|---|---|---|---|
| 1 | 分区表上 `ALTER TABLE request_log ADD KEY` | **对**（8.4 对二级索引是 in-place、不重建表、允许并发 DML）；但原caveat 里「不能在线加分区」那句是无关且错的 | 已改：caveat 只说「分区表加二级索引仍需 DDL 窗口，本机演示数据量下无影响」 |
| 2 | Flyway 描述串是 `m4 console`、把断言改成 2 条是**加强** | **对**（count + version + description 是原断言的超集）；但**不**等于钉住校验和 | 已改：撤掉「校验和」的说法（E.1-C4） |
| 3 | `cacheRequestBodyAndRequest` | **错**（不存在） | 已改成 `ServerHttpRequestDecorator`（E.1-C1） |
| 4 | 配额 Lua 的原子性与「0 = 不限」 | **对**；返回形状可解析但**欠规格**（`List<Long>` 需 `Number.longValue()`；>2^53 的限额在 Lua double 下会丢精度；畸形 ARGV 会让脚本报错 → 过滤器 fail-open 静默失效） | 计划要求 `parse` 用 `Number.longValue()`；**执行时**：限额值做上界校验（例如 `<= 2^53`），并对「脚本返回非预期形状」单独计数（不要与「Redis 不可用」共用同一个降级计数器） |
| 5 | HS256 控制台令牌 | **在其声明范围内成立**（算法服务端写死、不读请求 header、恒定时间比较、查 `exp`）；但**没有**密钥长度/熵下限（空串有处理，6 字符的弱密钥没有） | 计划要求：`aihub.console.secret` **长度 < 32 字符即启动 WARN 并拒绝登录**（与 `AesGcmChannelCipher` 在构造期拒绝 16 字节主密钥同一纪律）——执行任务 5/6 时落地，并各有一条用例 |
| 6 | 对账 UPSERT 与 `uk_billing_daily` + UTC `DATE()` | **对**（幂等键正确、UTC 存储下 `DATE()` 就是 UTC 日期、WHERE 范围保留分区裁剪）；`VALUES()` 已 deprecated；近似行（`usage_missing`/`client_disconnected`）会被计入偏差 | 已改：mapper 方法 + 行别名/`VALUES()` 的处置 + 近似行口径（E.2-I11） |

### E.5 评审提出的两条范围问题（登记，不偷偷扩也不偷偷砍）

- **有 spec 依据但本计划未交付**：§6.2 的「修正并告警偏差」被 D12 降级为「只报告」（**已登记**，理由是对账任务自动改账会把因 DLQ 延迟而尚未补全的账改坏）；§10 的覆盖率目标未测量（E.3-6）。
- **本计划构建了 §7.3 没列的端点**：`PUT /api/tenants`、`/api/routes` CRUD、`/api/rate-limits` CRUD、`/api/quotas` CRUD、`POST /api/channels/{id}/rotate-key`、`/api/api-keys/{id}/enable`、`DELETE /api/api-keys/{id}`。它们**有** §12（渠道/Key/租户管理）与 §6.1（密钥轮换）的依据，但评审指出其中 **`/api/api-keys/{id}/enable`、`DELETE` 与 `PUT /api/tenants` 对本里程碑的验收标准（控制面配置 → 数据面生效）价值最低**。**保留**它们（删除接口是密钥管理的常识性配套，且 Task 16 的界面不一定用到），但执行时**优先级排在能演示验收标准的链路之后**：如果时间不够，先交付 8/9/10/13/17，把 enable/DELETE 与 `PUT /api/tenants` 放到最后（并在报告里说明）。

---

## 附录 F：第二轮（复评后）的处置记录 —— 以及**仍未修**的部分

第二轮是**窄口径复评**（只查：22 条修正是否真落进正文、这轮修改有没有引入新矛盾、E.3 的未修项是否登记得可接受、E.4 两条加强项是否落成任务要求）。结论是 **Needs another correction round**。
它最有价值的发现是一类**我自己的模式性错误**：修正只写进了**任务正文**，而**决策表 / 架构段 / File Structure / 任务标题 / `git add` 清单**里留着旧话 —— 于是同一个事实在文档里有两种说法，而实施者会先读决策表（「实施者按此执行、不要自由发挥」）。

### F.1 第二轮已修（逐条对应复评的编号）

| 编号 | 问题 | 处置 |
|---|---|---|
| N1 | **架构（第 7 行）、D4 决策行、File Structure 的 `ConfigCache` 行、Task 4 标题与验收判据**仍写「**重置**写入水位」，而改后的正文要求「**抬到消息版本，绝不重置**」—— 决策表与实现自相矛盾 | 四处全部改口为「抬到失效消息里的版本（**绝不重置成 `NO_VERSION`**）」，D4 里补上理由（重置会拆掉挡在飞旧回填的唯一护栏） |
| N2 | Task 4 的 RED 证据引用了**不存在**的用例名（旧版遗留） | 改成真实用例名 `invalidationDropsTheSharedEntryAndRaisesTheWatermarkToTheInvalidatedVersion`，并写清它红在哪两点 |
| N3 | 装配的所有权三方冲突：D4/File Structure/Task 4 Step 3 说 `ConfigConfig`，而 Files/Interfaces/两个 context-runner 用例要求 **`ConfigInvalidateSubscriptionConfig`** | D4 的影响面、File Structure（换成两个新文件的行）、Step 3 的散文统一到 `ConfigInvalidateSubscriptionConfig`，并显式写「**不要**改 `ConfigConfig`」 |
| N4 | `QuotaCorrector`（以及 `QuotaConfigProperties`/`QuotaConfig`）不在 gateway 的 File Structure 表里 —— 而附录 E 声称"一并补上" | **部分修**：`ConfigInvalidate*` 两个新文件已补进表里；**`QuotaCorrector`/`QuotaConfigProperties`/`QuotaConfig` 仍未补**（见 F.2） |
| N5 | `GET /api/audit` 的两条链路不在 File Structure、不在任务索引、不在 Task 11 的 `git add` 里 → **两个新文件永远不会被提交** | **部分修**：Task 11 的 `git add` 已补上 `AuditQueryService.java` + `AuditController.java`；**File Structure 两张表与任务索引那一行仍未补**（见 F.2） |
| N6 | Task 9 的 Interfaces 仍写着被取代的 `revoke(long apiKeyId, String reason)` | 改成 Step 3 里定死的那一组（`create/list/disable/enable/delete`），并注明「**不再有 `revoke(long, String)` 这个签名**」 |
| N7 | Step 3 要求删 `ApiKeyView.UNUSABLE` 并改 `ApiKeyToolingTest`，但两者既不在 Files 也不在 `git add` 里 | 两处都补上 |
| N8 | Task 3 要求改 `ConfigSnapshotServiceTest.java`，但 Step 5 的 `git add` 只 stage 了两个文件 | 已补 |
| N9 | 全局约束要求改 `docker-compose.yml`，但 Task 17 的 Files 与 `git add` 都没有它 | 两处都补上（并写明不改它的后果：第 2–7 步在容器里全是 500/401） |
| N10 | `禁止无界扫描（D11）` —— D11 是 API Key 的显式 DEL，标签错 | 改成「索引在 V2，见 D1」 |
| N11 | Task 1 的水位用例断言**绝对值** `0`，而水位行活在 JVM 级共享容器里（Task 2/3 都会把它抬到 ~1.76e12）→ 全量跑必然因执行顺序变红 | 断言前**显式归零** `config_version`，并把隐患写进注释（与 `ConfigSnapshotServiceTest` 的 `@BeforeEach` 同一处理） |
| N12 | Task 7 的 `auditLogMapper.selectCount(null)).isZero()` 是全表计数断言，共享容器 + Task 1/8/9/10 的审计写入让它依赖执行顺序 | 加注释说明正确做法（`@BeforeEach` 清空，或断言**增量**）—— 这条是**注释级**处置，见 F.2 的说明 |
| N13 | Task 4 的接线测试给不出 `ConfigInvalidateProperties` bean，而 `@Bean` 需要它；`@ConfigurationProperties` record 必须被显式启用 | File Structure 行 + Step 3 散文都写明**必须带 `@EnableConfigurationProperties(ConfigInvalidateProperties.class)`** |
| N14 | 「两种都行…只能选一种」的岔口，与钉死的 `invalidate()` 签名冲突 | **去掉岔口**：把签名定成 `invalidate(long version)`（Interfaces、两个测试调用、`ConfigSubscriber` 片段、`ConfigClient` 片段、File Structure 两行、任务索引一行，共 7 处一起改），并写明为什么水位必须来自消息里的版本 |
| C3 ② 的一处错误陈述 | E.1-C3② 声称要"列出必须跟改的调用点"，但仓库里**没有**任何 `new ConfigSnapshotService(` 调用点（测试是 `@Autowired` 的），前提本身是假的 | Task 3 的 Interfaces 里加了**更正**，改成「加参数对调用点透明，真正要改的是那个测试的断言」 |

### F.2 第二轮**仍未修**（必须明确，不许当成已解决）

1. **N4 残余**：gateway 的 File Structure 表里没有 `QuotaCorrector` / `QuotaConfigProperties` / `QuotaConfig`（Task 13 的 Files 里有）。**执行 Task 13 时**：以 Task 13 的 Files 为准，并顺手把三行补进 File Structure 表（或接受"File Structure 是索引、Files 才是权威"这条口径，在报告里说明）。
2. **N5 残余**：`AuditQueryService` / `AuditController` / `GET /api/audit` 还没进 File Structure 两张表与任务索引的 Task 11 那一行（`git add` 已补，所以**不会丢提交**）。
3. **E.4 的两条加强项仍只在附录里，没有落成任务要求** —— 复评明确说这算缺陷（附录是记录，不是实施者找需求的地方）：
   - **(a) 配额限额的上界校验**（Lua double 在 >2^53 会丢整数精度）**与「脚本返回形状异常」要单独计数**（不要和 `aihub.quota.degraded` 共用）：Task 12/13 正文里**没有**这条要求。
   - **(b) `aihub.console.secret` 的最小长度/熵要求**（空串有 D16 处理，6 字符的弱密钥没有）：Task 5/6 正文里**没有**这条要求，也**没有**短密钥的测试用例。
4. **E.3 的两条 caveat**：
   - **Item 1**（Task 6 的 `theConsoleAssetsAreNotBehindTheTokenFilter` 在 Task 16 之前不可能通过）：**仍然只写在 E.3 里**，Task 6 的 Step 1 还列着它、Step 4 还要求它绿 —— 所以 Task 6 按字面**执行不了**。执行时按 E.3 的二选一处理，但**它本该写进 Task 6 的步骤**。
   - **Item 4**（配额读体的上限必须小于 `aihub.metering.max-capture-bytes`）：同样只在 E.3 里，Task 13 的配置文字里没有。
5. **N12 只是注释级处置**：Task 7 的断言本身仍是全表计数，执行时要么加 `@BeforeEach` 清理、要么改成增量断言 —— 目前只有注释在提醒。

### F.3 结论与下一步

第二轮把 **12 项新矛盾里的 9 项**（N1/N2/N3/N6/N7/N8/N9/N10/N11/N13/N14 + C3 的更正）真正落到了正文，剩下的 **5 条**（N4 残余、N5 残余、E.4 的两条、E.3 的两条 caveat、N12 的断言本体）都**明确列在上面**，没有一条是"以为改了其实没改"。

**因此这份计划现在的状态是：Critical 全部落实、Important 全部落实或显式登记；剩下的都是「附录里有要求、任务正文里没有」这一类，需要第三轮把它们搬进正文。** 按本仓库的纪律，**第三轮窄口径复评（只查 F.2 这 5 条）之后才动手执行 Task 1** —— 因为 F.2 的第 3 条（配额上界与密钥长度）是**安全/正确性**要求，只写在附录里等于没有。

### F.4 第三轮开工前的收口：F.2 那 5 条已全部搬进正文

在开第三轮复评**之前**，F.2 列的 5 条已经逐条落到任务正文里（否则第三轮只会把 F.2 原样念一遍）：

| F.2 条目 | 落到哪里 |
|---|---|
| 1. N4 残余：`QuotaCorrector` / `QuotaConfigProperties` / `QuotaConfig` 不在 File Structure | 已补进 gateway 的 File Structure 表；顺带把那句不准确的「`ChatRelayController` 拿到 `usage` 后校正」改成「`doFinally` 里 `publish` 之后调 `quotaCorrector.correct(event)`」 |
| 2. N5 残余：审计读取链路不在表里/索引里 | `AuditQueryService`（aihub-service 表）、`AuditController`（aihub-web 表）、任务索引 Task 11 的交付物加 `GET /api/audit` |
| 3a. E.4-(a)：配额限额上界 + 脚本形状异常独立计数 | **Task 12** 的 Produces 写明：`QuotaScript.parse` 用 `((Number) …).longValue()`、形状不对抛 `IllegalStateException`、限额 > **2^53** 被 `QuotaAdminService.update` 以 `INVALID_PARAM` 拒绝（含用例）；**Task 13** 的 Produces 写明 `aihub.quota.degraded`（Redis 不可用）与 **`aihub.quota.script_error`**（脚本形状异常）**必须分开计数** |
| 3b. E.4-(b)：控制台密钥长度下限 | **Task 6** 写明：空串按 D16，**长度 < 32 字符同样 `CONFIGURATION_ERROR` + 启动 WARN**，并**必须有一条短密钥用例**（与 AES 主密钥构造期拒绝 16 字节同一纪律） |
| 4a. E.3 caveat 1：Task 6 那条不可能通过的静态资源断言 | Task 6 的 Step 1 里**删掉**该用例，改成一条明确的要求（「本任务不要写 `/console/index.html` → 200；它属 Task 16」） |
| 4b. E.3 caveat 4：读体上限与计量捕获上限的关系 | **Task 13** 的 Produces 写明 `aihub.quota.max-in-memory-bytes` **必须小于** `aihub.metering.max-capture-bytes` |
| 5. N12：Task 7 的全表计数断言 | 断言改成**增量**（先记基线、再断言回滚后没有新增），不再依赖执行顺序 |

于是第三轮复评的检查面变成「这 7 处是否真的在正文里 + 有没有引入新矛盾」。**注意：F.2 与 F.4 本身不构成"需求所在地"** —— 如果第三轮发现某条又只落在附录里，那就是同一类缺陷的重犯。

### F.5 第三轮复评的处置（发现编号 F1–F7 指**第三轮**的编号，不是本附录的字母）

第三轮（窄口径）的结论是 **Ready to execute Task 1**，同时指出「Task 1 之后并不 execution-clear」：
**7 条里 6 条是"要求已经在正文、坏的是暂存清单/测试顺序/没写的用例"**。已逐条修：

| 编号 | 问题 | 处置 |
|---|---|---|
| F1 | Task 15 要给 Task 11 建的 `BillingDailyMapper` 加 `recomputeDaily`，但那个文件既不在 Task 15 的 Files 也不在 `git add` 里 → **提交出来的树编译不过**（服务层已在调它） | Files 加 `Modify: BillingDailyMapper.java`（并写明不 stage 的后果），`git add` 补该路径 |
| F2 | Task 11 声明 Modify `ChannelController.java`（加 `probe`）但没 stage；`RequestLogView`/`AuditLogView` 的归属也没说 | `git add` 补 `ChannelController.java`；并明确**两个 view 是嵌套在各自 service 里的 record、不额外建文件**，所以 add 清单不需要新路径 |
| F3 | Task 6 的两个正向用例打不存在的 `GET /api/ping` 与 Task 8 才有的 `GET /api/tenants` → **Task 6 按字面执行不了**（与已修的 `/console/index.html` 属同一类） | `ConsoleAuthController` **增加一个 `GET /api/ping`**（返回 claims），两个用例都改打它（`VIEWER` 的 403 用 `POST /api/ping`，拦在过滤器里），并显式写「不许引用 `/api/tenants`」 |
| F4 | 「限额 > 2^53 → `INVALID_PARAM`」要求自带用例，但用例不存在、`Tests run: 3` 也把它排除在外 | 上界规则做成 `aihub-common` 的纯函数 **`QuotaScript.assertWithinRange(long)`**（等于 2^53 允许，边界只定义一次），用例进 `QuotaContractTest` → `Tests run: 4` |
| F5 | Task 4 的 `-Dtest` 里写了**不存在**的 `ConfigClientTest`，而 Surefire 被配置成"没匹配也成功" → **假绿** | 从命令里删掉它，并把这条陷阱写在原地（F5 就是全局约束里点名的那一口） |
| F6 | `aihub.quota.script_error` 只有要求、没有 Step 3 的实现句子与测试 → 实现者自然的 `catch (Exception) → degraded++` 会把两个计数器合并（正是 E.4-(a) 警告的事） | Step 1 加用例 `anUnexpectedScriptShapeCountsScriptErrorNotDegrade`（放行 + `script_error=1` + `degraded=0`）；Step 3 明确 `parse` 抛 `IllegalStateException` 走 `script_error`，**不许被宽泛 catch 吞掉** |
| F7 | 三处小疵：Task 4 的提交信息写 "drop … the water mark"（读起来像"把水位删了"，与正文相反）；Task 11 的 H1 漏了 `/api/audit`；aihub-service 的 File Structure 表漏了 `AuditAction.java` | 三处都改（提交信息改成 "RAISE the water mark"；H1 补 `/audit`；表里补 `AuditAction.java`） |
| Task 1 的 RED 顺序 | Step 1 若把脚本与断言一次写完，Step 2 就**没有红可抓**（2 条迁移 ✓、12 张表 ✓） | Step 1 末尾加一段**三步次序**：先只改测试抓 `expected size: 2 but was: 1` → 再建 V2 抓表清单红 → 最后补实体/新用例 |

第三轮另外**独立复核了 Task 1 依赖的全部现实**（`SchemaMigrationTest` 确实 4 条用例且用 `JdbcTemplate` 而非 Flyway API；`allTenTablesExist` 恰好 10 张表；`request_log` 的两个列与 `RANGE COLUMNS(created_at)` 分区使两条 `ADD KEY` 合法且不撞名；实体写法与仓库一致；`AbstractIntegrationTest` 无全局清理因此"先归零"的警告是必要的），并确认 **Task 1 的 Files/Interfaces/V2 SQL/`git add` 完整**。

**至此：Task 1 可以直接开工**（上述 RED 次序已写进正文），其后的任务按各自小节里的修正执行即可。

---

## 附录 G：Task 6 执行期的处置记录（代码已改，此处只记**计划/纪律**层面的事实）

Task 6（登录 + `/api/**` 鉴权过滤器 + 两级角色）实现于 `8a7795a`，独立评审结论 **Ready to push**（0 Critical / 1 Important / 10 Minor）。执行期发现的四件事，逐条处置：

| # | 发现 | 处置 |
|---|---|---|
| G1 | **真缺陷**：`ConsoleTokenService` 用 `strip()` 之后的长度做合规判定，却用**未 strip 的原值**做 HMAC 密钥 —— 被校验的值与实际使用的密钥不是同一个字符串。末尾带一个换行/空格的密钥（`echo`、`docker --env-file`、K8s Secret 的经典事故）会被判为合规，而密钥把那个空白算了进去 | **已修**：构造函数里**只归一化一次**（`normalizeSecret`），让"被校验的值"与"密钥"在构造上就是同一份；`ConsoleProperties` 复用同一个归一化器，避免启动 WARN 报的长度与合规判定互相矛盾。判别用例：用 `SECRET + "\n"` 签发的令牌必须能被 `SECRET` 校验（对未修代码是红的） |
| G2 | **本计划内部不自洽**：D10 正文写"非 GET/HEAD 一律 403"，与 Task 6 的代码片段 `Set.of("GET","HEAD","OPTIONS")` 冲突，且 `OPTIONS` 没有任何用例 | **已按代码统一**（见正文 D10）：只读口径含 `OPTIONS`（无副作用 + CORS 预检），补一条 `VIEWER` + `OPTIONS` 的用例 |
| G3 | **承重的假绿（纪律层面最严重的一条）**：`MeteringConsumerIntegrationTest.theSameEventTwiceInsertsExactlyOneRow` 的绿**依赖 surefire 的类顺序**。它把"队列是单消费者 FIFO ⇒ 栅栏行出现时重复消息必然已整条处理完"当作时序证明；但 Testcontainers 的 broker/queue 是 **JVM 单例**、Spring 上下文缓存不关，任何第二个带 `@RabbitListener` 的上下文都会在同一队列上放第二个消费者，而 prefetch 下栅栏消息可能被另一个消费者**先**处理完 → 读 appender 太早 → 因与幂等无关的原因变红。独立评审用**既有类** + `-Dsurefire.runOrder=reversealphabetical` 复现 **3/3 红** | **已修**：**保留**"重复消息必须真的到达消费者并在消费端被幂等丢弃"这条断言，把顺序推断换成**有界轮询**该 INFO 出现（appender 挂在全局消费者 logger 上，任何实例打的这条 INFO 都能被看到，错的只是时机推断）。**这不是"允许重跑/放宽断言"**：轮询只解决异步观测的时机，重复消息若真的没被重投递，用例仍然红。同时撤掉 Task 6 集成测试里为绕开它而临时加的 `spring.rabbitmq.listener.simple.auto-startup=false`（那会让该上下文不再是生产接线的完整复制）。⚠️ **纪律**：任何"我的用例绿了"的结论都必须能通过 `-Dsurefire.runOrder=reversealphabetical` 或改变类名顺序的检验 —— 顺序条件绿与 `-Dtest=A+B`、`failIfNoSpecifiedTests` 属同一类假绿 |
| G4 | **证据缺口**：空/短密钥的 `500 CONFIGURATION_ERROR` 只在手工装配的 `MockMvcBuilders.standaloneSetup(...)` 里被断言过，真 Spring 上下文路径从未跑过这条映射 | **已补**：新增一条真上下文（空密钥）的集成用例，断言登录回 500 + `CONFIGURATION_ERROR`、`/api/**` 仍 401、文案含 `AIHUB_CONSOLE_SECRET` |

另有一条**控制器自身的错误**记录在此：Task 6 的任务简报把"Docker 正在运行"写成任务开始时的既成事实，而当时引擎并未启动（上一次 Docker 会话已于 2026-09-28 23:45 结束）。实现者没有照着假设走，而是**先测这个前提**并带着原始证据回报矛盾 —— 这正是本项目要的行为。**纪律**：简报里的环境前提是"待复测的断言"，不是事实；任何简报都不许把可测量的前提写成陈述句。
