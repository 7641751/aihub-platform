# aihub-platform M5（文档入库异步流水线）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「RAG 知识库的**写入侧**」做成一条**可运营、可回滚**的异步流水线：`POST /api/kb/documents` 上传原件 → 落盘 + 建 `kb_document(PENDING)` → MQ 触发**解析切分** → MQ 触发**分批嵌入** → 写 **Chroma** → `READY`；任一阶段最终失败 ⇒ `FAILED` + **按 `doc_id` 清掉已写入的向量**（**全成或全清**）。官方验收判据（设计文档 §12）：**中断上传不留脏数据**。

**Architecture:** 两段 MQ 串联（`kb.parse` → `kb.embed`），**状态机与进度都落在 MySQL**（状态在 `kb_document`、逐段进度在**新增的 `kb_chunk` 表**），Chroma 是**可重建的派生数据**、原件是唯一真相源。上传是**同步半段**（校验 + 算 sha256 + 原子落盘 + 建行 + **提交后**发消息），解析与嵌入是**异步两段**、各自有重试与死信。**"进 DLQ" 与 "置 `FAILED`" 由同一个 `MessageRecoverer` 完成**（避免状态与死信分叉）。检索侧**不做**（设计文档 §6.4 明文划给既有 Python `rag_qa_project`），M5 只交付**写入侧 + 跨语言向量契约**，"可检索"用**测试直接查 Chroma** 证明。

**Tech Stack:** Java 21（编译目标，运行于 JDK 25.0.2）、Spring Boot 3.5.16、Spring MVC、Spring AMQP（RabbitMQ 3.13，**照 `MeteringTopologyConfig` 的"死信三件套"**）、MyBatis-Plus 3.5.17、MySQL 8.4、Flyway、**Chroma（REST，容器化，Testcontainers 起真容器）**、**Apache PDFBox（仅 Task 7 引入，版本显式写死）**、JUnit 5 + AssertJ、Testcontainers、宿主 `com.sun.net.httpserver.HttpServer` 作假 embeddings 上游、Maven 3.9.12。

---

## Global Constraints

> **本节的写法**：**能引用 `docs/CONVENTIONS.md` 的就不抄**（该文件是活的，抄一遍必然过时）。下面只写 M5 **特有**或**必须在本计划里说清**的硬约束。

- 项目根：`D:\PycharmProjects\aihub-platform`。基线：`master` = **`f3cdd2f`**（M4 收口，标签 `m0..m3`；**M5 完成后打 `m5`**）。**本计划不负责建分支**。
- **依赖方向**（`CONVENTIONS` §1）：`aihub-web → aihub-service → aihub-dao`、`aihub-service → aihub-mq`、`aihub-web → aihub-mq`。`aihub-gateway` **只**依赖零依赖的 `aihub-common`，**不得**依赖 admin 的业务模块。`aihub-common` 的 main 作用域**仍然零第三方依赖**。
- **生产依赖新增清单是封闭的，且恰好一条**：`org.apache.pdfbox:pdfbox`（**Task 7** 引入，**版本必须显式写死**——它不在 Spring Boot BOM 里），声明在 `aihub-admin/aihub-service/pom.xml`。**先验证可解析**（走 `aliyunmaven`；判据是 Maven 自己的 `dependency:get` 输出，**不要**用 `curl -I`——M4 已实测本机 `curl` 到 `maven.aliyun.com` 是 `000`）。解析不下来 ⇒ **报 BLOCKED**，**绝不**自己写 PDF 解析器。**其它任何 `pom.xml` 改动都要在报告里单独登记理由。**
- **本里程碑获准新增 Flyway 迁移，且只有一条**：`V3__kb_pipeline.sql`（D1）。`V1`/`V2` **已执行过、禁止修改**。`SchemaMigrationTest` 的「恰好 2 条」断言**必须同步改成「恰好 3 条 + version/description 逐条一致」**（用例名一并改），这不是削弱护栏，是 `CONVENTIONS` §7 写明的闸门。
- **`docker-compose.yml` 与 `.env.example` 必须一起改**（compose 的 `environment:` 是**显式白名单**——没列进去的环境变量在容器里是空的，这是 M4 Task 17 用一次验收失败换来的）：
  ① 新增 `chroma` 服务（**镜像 tag 与测试里的 `GenericContainer` 必须用同一个**，D3）；
  ② admin 增加卷 `admin-files:/app/data/kb`（原件必须**跨重启存活**）与三个新 env：`AIHUB_KB_STORAGE_ROOT`、`AIHUB_KB_EMBEDDING_BASE_URL`、`AIHUB_KB_CHROMA_BASE_URL`；
  ③ `.env.example` 补对应三项（**不放任何真实值**）。
  **除这些，`docker-compose.yml` 不许再动**（尤其**不要**碰 Redis 的宿主映射 `127.0.0.1:6380:6379`）。
- **`spring.servlet.multipart` 上限必须显式配置**（`application.yml`）：`max-file-size` / `max-request-size` = **20MB**。默认 1MB 会**静默拒绝**稍大的 PDF，是本任务最容易"看起来能跑、其实全挂"的一处。
- **跨语言向量契约必须写死在 `CONVENTIONS`**（Task 8），因为**检索侧是另一个仓库的 Python**：collection 名、`vector_id` 形状、metadata 字段名（`doc_id` / `tenant_id` / `seq`）一旦改就是**破坏性变更**。契约见本计划**附录 A**。
- **上下文预算（硬）**：M5 新增的集成测试类**必须复用默认 Spring 上下文** —— 继承 `AbstractIntegrationTest`（或一个**不加任何注解**的非抽象子类），**不许**声明 `@TestPropertySource` / `@Import` / `@WebMvcTest`。M4 收口时全量实测 `Tomcat started on port` = **7**；M5 每个任务跑全量时都要**实测**这个数并写进报告，**不许变成 8**。
- **测试纪律**（`CONVENTIONS` §8，M4 用代价换来的，逐条适用）：断言必须**定向**（按 `docId`/`tenantId`/`requestId` 查，**不许数全表**）；"没发生"类断言必须配**正向对照**；**不许 `sleep`**，用**有界轮询**（照 `MeteringConsumerIntegrationTest.awaitRows`）；`doesNotContain` 之类**不可证伪**的断言一律换成可证伪形状；**边界断言的阈值必须与判据文字一致**。
- **变异实验的"干净"纪律（硬）**：**备份 → 变异 → `clean` → 跑 → 抄红点 → 还原 → 再 `clean` → 跑绿**；**只删 `*.class` 不够**（M4 Task 15 因此误判过一次）；看见"突然出现的红"**先核 `target/` 里的产物是不是上一个变异的残留**，再怀疑产品。
- **每个任务结束时**：全反应堆 `mvn -B -q test-compile -DskipTests` 必须绿；共享契约（队列名与载荷、Chroma 元数据、状态机跃迁、`AuditAction` 常量）必须在**第一个需要它的任务之前**就位，**不允许跨任务占位**。
- **禁止读/打印/echo `.env` 或任何密钥文件**；**不执行** `docker compose config`；判断环境变量只判断**是否存在/是否为空**。
- **不做的事**（按此判断越界）：检索侧（检索/召回/Rerank/编排，属既有 Python 项目）、**取消上传端点**（设计文档 §6.4 提过"取消"，但 §7.3 的接口表**没有**该端点 ⇒ 本里程碑**登记"取消未实现"**）、对象存储（本里程碑落本地卷）、OCR / 扫描版 PDF、多模态（图片里的文字）、文档去重以外的版本管理、单文档重试端点、`/v1/embeddings` 的**网关侧**代理（M5 由 admin 直接调上游）。

### 本机环境前提（**继承 `## Global Constraints` 之外，见 M4 计划的同名小节与 `CONVENTIONS` §8**）

- 每次 shell 调用都是**全新进程**：变量与工作目录都不保留；每条命令显式带工作目录。
- Docker 只能走 TCP：`-H tcp://127.0.0.1:2375`；Maven/Testcontainers 需 `$env:DOCKER_HOST='tcp://127.0.0.1:2375'`（**与 `mvn` 同一次调用**）。
- **`-Dtest=A,B` 必须逗号 + 引号**（`+` 会静默跳过）；单模块聚焦**必须 `-am`**；判据永远是 **surefire 汇总行 + `BUILD SUCCESS`**（退出码不可信）；跑前删 `target/surefire-reports`。
- 先 `$ErrorActionPreference='Continue'`；**删文件不要用管道 `Remove-Item -Force`**（会报 `missing path operand` 并中断整条命令）⇒ 用 `[System.IO.File]::Delete`。
- **中文文档只能用 `write`/`edit` 工具改**（`Get-Content`/`Set-Content` 会把 GBK 写坏）；`ReadAllLines` 传**绝对路径**；**工具脚本一律纯 ASCII**（PowerShell 5.1 按 ANSI 读无 BOM 的 `.ps1`）。
- **`git push` 在本机不可能成功**（只有 `gh` 的 REST API 通）；**只做本地提交**，控制器在里程碑末尾用 Git Data API 重放并逐路径核对远端树。判断远端一律用 `gh api repos/7641751/aihub-platform/git/ref/heads/master`。
- **只 stage 显式路径**，禁止 `git add -A` / `git add .` / **`git add <目录>`**（本项目已四次踩过目录写法）。
- **Docker Hub 直连不可达** ⇒ 用 `--pull never`（本地已有）或镜像；**Chroma 镜像若拉不下来 ⇒ 报 BLOCKED，不许偷偷换成假实现**（D3）。

---

## 决策登记（设计文档沉默处或需要取舍的地方；实施者按此执行，**不要自由发挥**；评审者按此判断越权）

| # | 决策 | 理由 | 影响面 / 证据 |
|---|---|---|---|
| **D1** | **新增唯一一条迁移 `V3__kb_pipeline.sql`，只做一件事**：建 `kb_chunk` 表。`kb_document` **不加列**（V1 已有 `status`/`chunk_count`/`error_msg`，够用）。`SchemaMigrationTest` 改成「恰好 3 条」。 | V1 里 `kb_document` **没有**分片进度列（只有 `chunk_count`），而"全成或全清"与"批次是否到齐"必须有**持久**依据（放 Redis 会在重启后卡在 `EMBEDDING` 永不完成，M4 已登记过"进度不持久"的教训）。**`kb_document` 不加列的额外理由**：它是 V1 的老表，改它要动 `V1`（禁止）或再加一次迁移；而 `kb_chunk` 顺便给了"按 doc 精确清理"的坐标。**残余（诚实登记）**：`kb_chunk` 存了一份 chunk 文本（`MEDIUMTEXT`），与原件重复占空间；换来的是**可重放、可直接查、清理只需一条 SQL**。 | `V3__kb_pipeline.sql`；`SchemaMigrationTest`（`hasSize(3)` + `containsExactly("1","2","3")` + description 列表）。**闸门**：任何人再加迁移必须再改这条断言。 |
| **D2** | **PDF 用 Apache PDFBox `3.0.3`**（**版本显式写死**，Task 7 引入并**先验证可解析**）。**不用 Tika**（依赖面大得多、能力远超需要）。 | PDF 解析是**算法**，不许自实现（与 M4 D2 对 bcrypt 的取向一致）；Tika 会拖进一长串传递依赖，而 M5 只需要"PDF → 文本层"。**⚠️ 2026-10-02 派发前扫描实测订正**：**PDFBox 自己也不是零传递依赖** —— `org.apache.pdfbox:pdfbox:3.0.3` 会拖进 `fontbox` + **三个 BouncyCastle**（`bcprov/bcpkix/bcutil-jdk18on:1.78.1`），合计约 **12.5 MB**（我原先写的"PDFBox 是最小那份"**不准确**；`2.0.30` 同样拖 BC，只是换成 `jdk15to18:1.76` 变体 ⇒ **换旧版并不能避开它**）。**仍然选它**：相对 Tika 仍是小得多的一份，且 BC 只用于签名/加密（M5 不用到）。 | `aihub-admin/aihub-service/pom.xml` **+1 个直接依赖 +3 个传递依赖**（都走 `aliyunmaven`，**已验证可解析**：`mvn …:get -Dartifact=org.apache.pdfbox:pdfbox:3.0.3` ⇒ `Downloaded from aliyunmaven` + `BUILD SUCCESS`）；Task 7 第一步仍做可解析性验证。 |
| **D3** | **向量库 = 真 Chroma 容器**（compose 服务名 `chroma`，测试用同一个 tag 的 `GenericContainer`）。**不许**用假 HTTP 上游替代 Chroma。**⚠️ 2026-10-02 派发前扫描订正：镜像必须走 `docker.m.daocloud.io/chromadb/chroma:<tag>`**，因为 **Docker Hub 直连不通**（实测 `registry-1.docker.io` 超时）且**本机没有 `chromadb/*` 镜像**；而 daocloud 代理的 manifest 探测**通**（实测返回合法 OCI index）。 | "真的写进向量库且能按 `doc_id` 取回"是本里程碑**唯一**能证明写入侧做对了的判据；用假实现会让它退回成**不可证伪的承诺**（本项目已两次为此吃过亏）。Chroma 是**可容器化的真服务**（该真），embeddings 是**易变的外呼**（该假，见 D6）。**残余/风险（必须照办）**：① **manifest 通 ≠ blob 可下** —— 同一次探测里 `docker.m.daocloud.io/library/alpine` 的 **blob 下载就 `TLS handshake timeout`** 了，所以**第一个 Step 必须是真 `docker pull` 并确认镜像在本地**；② 拉不到 ⇒ **BLOCKED 并报告**（**不许**静默换假实现、也不许擅自改用别的向量库 —— 那是设计级决策）。 | `docker-compose.yml` + `chroma` 服务（**image 写 daocloud 全路径 `docker.m.daocloud.io/chromadb/chroma:0.5.23`**）；测试基类里的**单例** `GenericContainer`（**与 compose 同一个镜像串**）。**2026-10-02 补充实测**：该镜像**已拉进本机**（`18e67eecc172`，668MB）；**首次 pull 会在一层上 `TLS handshake timeout`、重试即成功**（层缓存续传）⇒ Task 5 Step 0 的判据是"**重试 3 次仍失败**"，不是"一次失败"。契约已探明见上文表格。 |
| **D4** | **`kb_chunk` 的列**：`id`、`doc_id`、`seq`、`text`(**MEDIUMTEXT, NOT NULL**)、`vector_id`(VARCHAR(128))、`embedded_at`(DATETIME(3) NULL)、`created_at`。**唯一键 `uk_kb_chunk_doc_seq(doc_id, seq)`**；`KEY idx_kb_chunk_doc(doc_id)`。 | **`text` 必须有**：embeddings 消费端要拿文本；用消息传文本会造大消息（RabbitMQ 不鼓励），用"重新解析原件"则让解析做两次。**`embedded_at` 必须有**：`READY` 的判据是"**所有** chunk 都已嵌入"，只靠 `chunk_count` 无法表达"本批已完成"（`chunk_count` 是**期望值**，不是**进度**）。**唯一键**让重放变成覆盖（幂等），**索引**让"按 doc 清理"走前缀。 | `V3__kb_pipeline.sql`；`KbChunkEntity`/`KbChunkMapper`。 |
| **D5** | **重复上传（`uk_kb_document_tenant_sha` 命中）⇒ 返回已存在那一行（幂等），不是 409、更不是 500**；本次写的临时文件**删掉**。实现方式**照 M4 Task 12 的定稿**：`INSERT … ON DUPLICATE KEY UPDATE id = id` + **再读**，**不抛异常、不 catch `DuplicateKeyException`**。 | V1 的唯一键决定了"同租户同内容"天然要去重，而"重复上传"是**用户的正常动作**（点了两次、换了文件名）。M4 已经用一次死锁实测证明：`catch DuplicateKeyException` + `SELECT … FOR UPDATE` 重读会死锁，且**即便不死锁**，REPEATABLE READ 下同一事务的普通重读**看不见**并发提交的行。 | `KbDocumentMapper.insertIfAbsent` + `KbDocumentService.upload`；用例：同内容两次上传 ⇒ **同一个 `id`**、行数不增、临时文件不留。 |
| **D6** | **embeddings 上游 = 配置化的 OpenAI 兼容 `/v1/embeddings`**；测试里用**宿主 `com.sun.net.httpserver.HttpServer`** 作假上游（本项目既有做法），并**能注入"第 N 批才失败"**。**不接真实计费 API**。 | 真 embeddings 需要密钥、网络与计费 ⇒ 验收**不可重复**；而假上游能**精确注入**"第 3 批失败""不响应（超时）"这类**只有失败路径才需要**的形态——这正是本里程碑的验收中心（全成或全清）**必须**能构造的。 | `KbEmbeddingClient`（base_url 来自 `aihub.kb.embedding.base-url`）；测试夹具 `FakeEmbeddingUpstream`（含 `failFromBatch(n)`、`neverRespond()`）。 |
| **D7** | **"进 DLQ" 与 "置 `FAILED`" 必须是同一处**：消费者 `catch` 里**只 log + 抛**（把重试交给容器）；**终态由自定义 `MessageRecoverer` 写**（置 `FAILED` + `error_msg` + `cleanup(docId)` + 审计）。重试策略 **3 次指数退避**（照 `MeteringConsumer` 的既有配置）。 | 若在 `catch` 里置 `FAILED`，第 2 次重试成功后状态就**自相矛盾**；若只在"消息进 DLQ 之后"由别的东西置 `FAILED`，就会出现"**进了 DLQ 但状态还停在 `EMBEDDING`**"。`MessageRecoverer` 是 Spring AMQP 在**放弃并路由到 DLQ 之前**被调用的钩子 ⇒ 把状态写在它里面，"死信"与"失败终态"永远同时发生。 | 自定义 `KbMessageRecoverer` + 用例：断言 `FAILED` **且** 消息**确实在 DLQ 里**（两者一起断言，不许只断言其一）。 |
| **D8** | **`cleanup(docId)` 的顺序**：① 删 Chroma（按 metadata `doc_id`）→ ② 删 `kb_chunk` → ③ 置 `FAILED`。**清理自身失败**⇒ `error_msg` 里**如实写"清理未完成"**并**保留 `kb_chunk`**（可重入），**绝不假装干净**。 | 顺序反了会在"Chroma 删成功但 `kb_chunk` 删失败"时留下**无法定位**的残留（坐标没了）。**全局不变量（可证伪）**：不允许「`READY` 但 Chroma 缺 chunk」，也不允许「`FAILED` 但 Chroma 还留着该 doc 的 chunk」。 | 用例：让假上游第 3 批失败 ⇒ 断言 `FAILED` + **Chroma 里该 `doc_id` 一个 chunk 都没有** + `kb_chunk` 空 + 消息在 DLQ。 |
| **D9** | **不做取消端点**（设计文档 §6.4 提过"取消"，但 §7.3 的接口表只有 `POST`/`GET /api/kb/documents`）⇒ 只做"**最终失败自动清理**"，并在 Task 8 **显式登记"取消未实现"**；但 `cleanup(docId)`/`KbDocumentService.cancel(docId)` 的形状要写成**将来能被取消复用**的样子（幂等、可重入）。 | 以**接口表为准**（它是"对外承诺"），且取消要实现"停掉在飞的消息"，在无 `docId` 级幂等锁的前提下成本远高于收益（YAGNI）。**残余**：用户上传后发现传错文件，只能等它跑完或失败后删行（手工 SQL）。 | Task 8 的"已知边界"必须含这条；README 同步。 |
| **D10** | **分批大小 = 10 段/批**，批次用消息载荷 `{docId, seqFrom, seqTo}` 表达；**解析完成时一次性算好所有批次**（`ceil(chunkCount / batchSize)` 条消息）。参数走配置 `aihub.kb.embed.batch-size`（默认 10）。 | 设计文档 §8.2 明写"10 段/批嵌入"。用**区间**而不是"每批带文本"，让消息体积恒定且可重放（文本从 `kb_chunk` 读）。 | `KbTopology.embedBatches(docId, chunkCount, batchSize)`（纯函数，可单测）。 |
| **D11** | **MQ 拓扑放 `aihub-mq`**（`KbTopology`/`KbMessageCodec`/消费者），**不放 `aihub-common`**。**⚠️ 2026-10-02 派发前扫描订正理由**：`aihub-mq` 的依赖**只有 `aihub-common` + `spring-boot-starter-amqp`，没有 Jackson**（实测 pom）⇒ 用**文本分隔符**编解码不是"风格偏好"，而是**唯一与现有依赖面一致的选择**（引 Jackson 只为编解码是净增依赖）。 | 计量那条之所以在 `aihub-common` 是因为 **gateway 也要发**；M5 的上传与消费**都在 admin 内部**，没必要去污染 `aihub-common` 的"零第三方依赖"面。 | `aihub-mq` 新增四个类；`aihub-common` **不动**。 |
| **D12** | **上传接口用 `multipart/form-data`**（字段名 `file`，可带可选字段 `filename` 覆盖）；`tenantId` **取请求体**（multipart 的文本字段），**不是**令牌里的（`CONVENTIONS` §10 **R2**：写操作平台级，但审计必须记**目标资源的**租户）。 | PDF 是二进制，JSON 装不下；而"写操作从请求体取租户"是 §10 已定死的控制面语义（M4 Task 8/9/10/12 一律照办）。**残余**：multipart 的 `tenantId` 若缺失 ⇒ **400 `INVALID_PARAM`**（与 M4 的 `PUT /api/quotas` 同形）。 | `KbDocumentController.upload(@RequestPart("file") MultipartFile, @RequestParam ...)`。 |
| **D13** | **新增 `AuditAction` 常量三个**：`KB_DOCUMENT_UPLOAD`（上传，写路径）、`KB_DOCUMENT_READY`（流水线成功）、`KB_DOCUMENT_FAILED`（失败终态，**含失败原因的非敏感摘要**）。**审计绝不记**：原件内容、chunk 文本、向量、任何密钥。 | 写操作必须留审计（`CONVENTIONS` §6.6 的纪律 + M4 的 `AuditService`）。**这三个是"该加"的共享常量**（别像 M4 Task 11 那样被"不要新增常量"绊住——那条针对的是"没打算写审计"的场景）。`READY`/`FAILED` 由**系统**写（`actor_type=SYSTEM`），`UPLOAD` 由用户写。 | `AuditAction` +3；每个 Task 的用例断言"产生了对应审计行且**不含**敏感字段"。 |
| **D14** | **0 段 = 失败**：解析后 `chunkCount == 0` ⇒ **`FAILED("无可提取文本")`**（这就是**扫描版/图片型 PDF 的 YAGNI 出口**，**不做 OCR**）。 | 设计文档没写这条，但"上传了一个没有文本层的 PDF"是完全正常的用户动作；静默 `READY` 会让检索侧永远查不到东西却显示成功——**这比失败更糟**。 | 用例：空内容 md ⇒ `FAILED` + `error_msg` 含"无可提取文本" + `chunk_count=0` + 无残留。 |
| **D15** | **`vector_id = "{docId}:{seq}"`**（字符串，稳定可推导），Chroma 侧用**同一值**当 id（upsert）。 | 可推导 ⇒ 重放覆盖天然幂等；可 grep ⇒ 排查"哪个 chunk 没写进去"时不用查两处；且**跨语言契约**只需说清这一个规则（附录 A）。 | `KbChunk.vectorId(docId, seq)`（纯函数 + 固定向量用例）。 |
| **D16** | **`kb.parse` 与 `kb.embed` 各一条队列 + 一个 DLQ**，**逐字照 `MeteringTopology` 的命名形状**（2026-10-02 扫描实测基准：`aihub.metering.exchange` / `aihub.metering.usage`(routing) / `aihub.metering.queue` / `aihub.metering.dlx` / `aihub.metering.dlq` / `MESSAGE_CONTENT_TYPE="text/plain;charset=UTF-8"`）⇒ M5 定为：`EXCHANGE="aihub.kb.exchange"`、`PARSE_QUEUE="aihub.kb.parse"`、`PARSE_ROUTING_KEY="aihub.kb.parse"`、`EMBED_QUEUE="aihub.kb.embed"`、`EMBED_ROUTING_KEY="aihub.kb.embed"`、`DEAD_LETTER_EXCHANGE="aihub.kb.dlx"`、`DEAD_LETTER_ROUTING_KEY="aihub.kb.dlq"`、`DEAD_LETTER_QUEUE="aihub.kb.dlq"`、`MESSAGE_CONTENT_TYPE` 同款。**死信三件套**照抄 `MeteringTopologyConfig`：业务队列声明 `x-dead-letter-exchange` + routing key，DLX 绑 DLQ。 | 两个阶段的**失败代价不同**（解析失败 = 文件问题，嵌入失败 = 上游/向量库问题），分开才能在排查时一眼区分；共用 DLQ 让运维只需盯一个地方。**与既有计量链路同构**（照着抄，别发明新形状）。⚠️ 我原先在正文里直接写 `aihub.kb.parse` 当队列名与 routing key **混用**、也没给出 exchange 名 —— 那是**与既有风格不一致**的写法，已按上表统一。 | `KbTopology` + `KbTopologyConfig`；用例断言 DLQ 深度/消息体。 |

---

## 派发前缺陷扫描（2026-10-02，控制器实测；**结论已折进上表与各 Task**）

| # | 抓到的坑 | 证据（原始输出） | 处置 |
|---|---|---|---|
| 1 | **⚠️ 曾达 BLOCKED 级：Chroma 镜像拿不到** —— `chromadb/chroma` 直连 **Docker Hub 超时**，且本机 `docker images` **没有**任何 `chromadb/*` | `manifest inspect chromadb/chroma:0.5.23` ⇒ `failed to configure transport … registry-1.docker.io … Client.Timeout`；`docker images` 只有 admin/gateway/alpine/curl/temurin/maven/mysql/python/rabbitmq/redis/ryuk | **改走 daocloud 代理**：`manifest inspect docker.m.daocloud.io/chromadb/chroma:0.5.23` **返回合法 OCI index** ⇒ 写入 **D3**（compose 与测试都用该全路径）。**但 manifest 通 ≠ blob 可下**（同一次探测里 `docker.m.daocloud.io/library/alpine` 的 blob 就 `TLS handshake timeout`）⇒ Task 5 Step 0 改成**真 `docker pull` 并确认本地镜像**，失败即 BLOCKED |
| 2 | **PDFBox 不是零传递依赖**（我原先的理由写错了："PDFBox 最小"**不准确**） | `dependency:get pdfbox:3.0.3` ⇒ 拖进 `fontbox` + `bcprov/bcpkix/bcutil-jdk18on:1.78.1`（≈12.5 MB，`BUILD SUCCESS`）；**`2.0.30` 同样拖 BC**（`jdk15to18:1.76`）⇒ 换旧版避不开 | 订正 **D2**（含"清单 +1 直接 +3 传递"与"两版都带 BC"的事实） |
| 3 | **MQ 命名与既有风格不一致**：正文里把 `aihub.kb.parse` 同时当队列名与 routing key，且没给 exchange 名 | 实测 `MeteringTopology`：`aihub.metering.exchange` / `aihub.metering.usage` / `aihub.metering.queue` / `aihub.metering.dlx` / `aihub.metering.dlq` + `MESSAGE_CONTENT_TYPE` | 订正 **D16** 为逐字同构的九个常量 |
| 4 | **D11 的理由是错的**：我写"`aihub-mq` 本来就能用 Jackson" | 实测 `aihub-mq/pom.xml` 只有 `aihub-common` + `spring-boot-starter-amqp` ⇒ **没有 Jackson** | 订正 **D11**（结论不变、理由变强：文本分隔符是**唯一与现有依赖面一致**的选择） |
| 5 | **`application.yml` 未配 multipart**（默认 1MB 会静默拒绝大 PDF） | `Select-String 'multipart' application.yml` ⇒ 0 命中 | 已在 Global Constraints 与 Task 2 写明**必须显式配 20MB**；扫描确认这条前提成立 |

**扫描同时确认成立的前提**：`AuditAction` 是 `String` 常量（不是枚举，注释写明理由）⇒ 新增三个常量形状正确（D13）；
`@EnableScheduling` 存在（M4 Task 17 的教训）；Testcontainers 在 `aihub-web/pom.xml`；进程内假上游是既有做法（`com.sun.net.httpserver.HttpServer`）。

**仍未知、必须在 Task 5 Step 0 实测的**：~~Chroma 的真实 REST 契约~~ → **已于 2026-10-02 实测钉死，见下**。

### Chroma 契约（2026-10-02 实测钉死，**Task 5 照此写，不要再猜**）

镜像：**`docker.m.daocloud.io/chromadb/chroma:0.5.23`**，digest `sha256:18e67eecc172abbcd9413d751bde64983b3d167fe497c98f979083eb24c0c942`，**668 MB**，**已在本地**。
⚠️ **但拉取必须允许重试**：第一次 pull 在某一层 `TLS handshake timeout` 失败（`image-mirror.r2.daocloud.vip`），
**第二次成功**（已下完的层被缓存、实现续传）⇒ **"一次拉取失败"不等于 BLOCKED**，Task 5 Step 0 要写成"**至少重试 3 次、每次留原始输出**；
连续失败才 BLOCKED"。（我原先写的"拉不到即 BLOCKED"**过于武断**，已订正。）

| 操作 | 方法 + 路径 | 实测 |
|---|---|---|
| 心跳/版本 | `GET /api/v1/heartbeat`、`GET /api/v1/version` | `{"nanosecond heartbeat":…}`；`0.5.23`（**v2 也存在** ⇒ 代码里**显式钉 v1**，别依赖默认） |
| 建/取集合 | `POST /api/v1/collections`，体 `{"name":"kb_chunks","get_or_create":true}` | 200，返回 `{id,name,…}`（`id` 是 UUID） |
| 写 | `POST /api/v1/collections/{collection_id}/upsert`，体 `{ids,embeddings,metadatas,documents}` | 200；**重放同一批 ids ⇒ `count` 不变**（★ 幂等，D15 的前提成立） |
| 计数 | **`GET`** `/api/v1/collections/{collection_id}/count` | 返回整数。⚠️ **我实测用 POST 调它得到 `405`** —— 别踩 |
| 按 doc 取回 | `POST /api/v1/collections/{collection_id}/get`，体 `{"where":{"doc_id":1},"include":["metadatas","documents"]}` | 200，`ids`/`metadatas` 与写入**逐字一致** |
| 按 doc 删除 | `POST /api/v1/collections/{collection_id}/delete`，体 `{"where":{"doc_id":1}}` | 200；**count 3→1**（只删该 doc 的两条，别的 doc 不动）⇒ ★ **D8 的清理可用** |
| 集合按名操作 | `GET` / `DELETE` `/api/v1/collections/{collection_name}`（**这个路由吃名字**，其它路由吃 `collection_id`） | 见路由表 |
| 检索 | `POST /api/v1/collections/{collection_id}/query` | **未探通（400）**；**M5 不依赖它**（检索属 Python 侧）。检索侧要用时由那边实测，不要在 M5 里实现 |

原始输出：`.hb2-logs/m5-scan/chroma-routes.txt`（路由表）、`chroma-contract-probe.txt` / `chroma-contract-probe2.txt`（生命周期实测）。

---

## Task 1: V3 迁移 + KB 实体地基

**Files:**
- Create: `aihub-admin/aihub-dao/src/main/resources/db/migration/V3__kb_pipeline.sql`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/KbDocumentEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/KbChunkEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/KbDocumentMapper.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/KbChunkMapper.java`
- Modify: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/dao/SchemaMigrationTest.java`（`hasSize(2)` → `hasSize(3)`、version 列表加 `"3"`、description 列表加 `"kb pipeline"`、用例改名 `flywayAppliesExactlyThreeMigrations`）
  **⚠️ 2026-10-02 补（控制器读文件时发现，计划原文漏了这条）**：同文件还有 **`allTwelveTablesExist`**，它用
  `containsExactlyInAnyOrder` **精确列出 12 张表** ⇒ 加了 `kb_chunk` 之后**它也会红**，而且**用例名本身就错了**。
  ⇒ 必须同时：把 `"kb_chunk"` 加进清单、把用例名改成 `allThirteenTablesExist`、并在注释里说明"表数量由本用例显式钉住"。
  （这就是 CONVENTIONS §7 那条闸门的第二半 —— 我原先只写了迁移数量那一半。）

**Interfaces:**
- Consumes: 既有 `MybatisMapperConfig`（`@MapperScan`）、`AbstractIntegrationTest`。
- Produces: `kb_chunk` 表；`KbDocumentEntity`（**字段以 V1 的 SQL 为准**：`id`/`tenantId`/`filename`/`sizeBytes`/`sha256`/`status`/`chunkCount`/`errorMsg`/`createdAt`/`updatedAt`——
  ⚠️ **设计文档 §5.2 写的 `size` / `uploaded_at` 是错的，实际列是 `size_bytes` / `created_at`**）；`KbChunkEntity`（`id`/`docId`/`seq`/`text`/`vectorId`/`embeddedAt`/`createdAt`）。
- 状态字面量常量：`KbStatus`（`PENDING`/`PARSING`/`EMBEDDING`/`READY`/`FAILED`）—— **放 `aihub-common`**（零依赖的 String 常量），因为 Task 8 的文档与检索侧都要引用同一批字面量。

- [ ] **Step 1: 写失败测试**（`SchemaMigrationTest` 改断言 + 一张 `kb_chunk` 的结构用例）

```java
@Test
void flywayAppliesExactlyThreeMigrations() {
    List<Map<String, Object>> applied = jdbcTemplate.queryForList(
            "select version, description from flyway_schema_history where success = 1 order by installed_rank");
    assertThat(applied).as("迁移数量由本用例显式钉住：加迁移必须同时改这里（CONVENTIONS §7）").hasSize(3);
    assertThat(applied).extracting(r -> String.valueOf(r.get("version"))).containsExactly("1", "2", "3");
}

@Test
void kbChunkHasTheCoordinatesWeCleanUpBy() {
    // 定向断言：只看 kb_chunk 的列与两个键，不数全库表
    List<Map<String, Object>> cols = jdbcTemplate.queryForList(
            "select column_name, is_nullable from information_schema.columns where table_name = 'kb_chunk'");
    assertThat(cols).extracting(r -> String.valueOf(r.get("column_name")))
            .containsExactlyInAnyOrder("id", "doc_id", "seq", "text", "vector_id", "embedded_at", "created_at");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from information_schema.statistics where table_name = 'kb_chunk' and index_name = 'uk_kb_chunk_doc_seq'",
            Integer.class)).as("(doc_id, seq) 唯一 ⇒ 重放即覆盖").isGreaterThan(0);
}
```

- [ ] **Step 2: 跑它确认失败**

Run: `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=SchemaMigrationTest"`
Expected: FAIL（`hasSize(3)` 实得 2；`kb_chunk` 不存在）

- [ ] **Step 3: 实现**

```sql
-- V3__kb_pipeline.sql
-- M5：文档入库流水线的逐段进度表。原件是唯一真相源，向量库是可重建的派生数据，
-- 这张表是「哪些段已经真的写进向量库」的**持久**坐标 —— READY 的判据与失败清理都靠它。
CREATE TABLE kb_chunk (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    doc_id      BIGINT        NOT NULL,
    seq         INT           NOT NULL,
    text        MEDIUMTEXT    NOT NULL,
    vector_id   VARCHAR(128)  NOT NULL,
    embedded_at DATETIME(3)   NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_chunk_doc_seq (doc_id, seq),
    KEY idx_kb_chunk_doc (doc_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

实体按既有 `*Entity` 的形状写（`@TableName`、`@TableId(type = IdType.AUTO)`、时间列 **Java `LocalDateTime`**，见 `CONVENTIONS` §7）。
`KbDocumentMapper extends BaseMapper<KbDocumentEntity>`，并加 **D5 的幂等插入**：

```java
/** 幂等「插或忽略」：命中 uk_kb_document_tenant_sha 时什么都不改，也不抛唯一键异常。 */
@Insert("INSERT INTO kb_document (tenant_id, filename, size_bytes, sha256, status, chunk_count) "
        + "VALUES (#{tenantId}, #{filename}, #{sizeBytes}, #{sha256}, #{status}, 0) "
        + "ON DUPLICATE KEY UPDATE id = id")
int insertIfAbsent(@Param("tenantId") long tenantId, @Param("filename") String filename,
                   @Param("sizeBytes") long sizeBytes, @Param("sha256") String sha256,
                   @Param("status") String status);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=SchemaMigrationTest"`
Expected: PASS（`Tests run: N, Failures: 0, Errors: 0`）

- [ ] **Step 5: 全反应堆编译 + 提交**

```bash
mvn -B -q test-compile -DskipTests
git add aihub-admin/aihub-dao/src/main/resources/db/migration/V3__kb_pipeline.sql \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/KbDocumentEntity.java \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/KbChunkEntity.java \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/KbDocumentMapper.java \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/KbChunkMapper.java \
        aihub-admin/aihub-common/src/main/java/com/aihub/common/kb/KbStatus.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/dao/SchemaMigrationTest.java
git commit -m "feat(kb): add the V3 kb_chunk table and the KB persistence layer"
```

**验收判据：** `V3` 被 Flyway 应用（迁移恰好 3 条且 version 逐条一致）；`kb_chunk` 有 `(doc_id, seq)` 唯一键与 `doc_id` 索引；实体字段与 V1/V3 的 SQL **逐字对应**。
**RED 证据：** 改断言后 `flywayAppliesExactlyThreeMigrations` 红（实得 2）—— 这条红是**闸门本身在工作**，不是断言写错。

---

## Task 2: 上传（同步半段）：原子落盘 + sha256 + 幂等 + 审计

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbFileStore.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentService.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/KbDocumentController.java`
- Modify: `aihub-admin/aihub-web/src/main/resources/application.yml`（multipart 上限 20MB；`aihub.kb.storage.root`）
- Modify: **`aihub-admin/aihub-service/src/main/java/com/aihub/service/audit/AuditAction.java`**（+`KB_DOCUMENT_UPLOAD`，D13）
  **⚠️ 2026-10-02 派发前扫描订正**：我原先写的路径 `aihub-common/.../common/audit/AuditAction.java` **是错的** ——
  实测它在 **`aihub-service`**（包 `com.aihub.service.audit`，与 `AuditService` 同包；`AuditService` 也在那儿）。
  `AuditService.record` 的签名前五参是 **`(Long tenantId, Actor actor, String action, String targetType, String targetId, …)`**，
  `Actor` 是 `record Actor(String type, String id)` —— 写审计前**读全**第 6 个参数。
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbUploadIntegrationTest.java`

**Interfaces:**
- Consumes: `KbDocumentMapper.insertIfAbsent`（Task 1）、`AuditService.record(...)`、`ConsoleAuthFilter`（守门）、`QueryTenant` 语义（§10 R2）。
- Produces:
  - `KbFileStore.store(tenantId, sha256, InputStream) -> Path`（**临时名 + `ATOMIC_MOVE`**；拒绝 `..`；路径 `{root}/{tenantId}/{sha256}`）；
  - `KbDocumentService.upload(long tenantId, String filename, long sizeBytes, InputStream, Actor) -> UploadResult{KbDocumentEntity row, boolean created}`；
  - `POST /api/kb/documents`（multipart：`file` + 文本字段 `tenantId`）返回 `{id, status, chunkCount, duplicate}`；
  - `GET /api/kb/documents?tenantId=&status=&page=&size=`（**R3.1/R3.2 口径**：这是**资源列表**⇒ `tenantId` 缺省 = 令牌里的租户；`page/size` 按 M4 Task 11 的边界纪律：`size` 缺省 20 / 钳 200 / `<1` 400，`page` 缺省 0 / `<0` 400）。

- [ ] **Step 1: 写失败测试**

**⚠️ 三条会直接出错的实现约束（2026-10-02 扫描补，逐条都要照办）**：
1. **存储根目录绝不许用 `@TestPropertySource` / `@DynamicPropertySource` 改** —— 两者都会改变 Spring 上下文缓存键 ⇒ **fork 出第 8 个上下文**，直接违反"上下文仍是 7"的硬约束。
   ⇒ 用 `application.yml` 里的**默认**值（`aihub.kb.storage.root` 指向 `target/` 下的可丢弃目录），测试在 `@BeforeEach` 里**按租户**清自己的子目录。
2. **"无残留文件"的断言必须定向**：检查的是 `{root}/{tenantId}/` 这个子目录，**不是**整个根目录（共享上下文 + 共享根目录 ⇒ 全根断言会变成顺序依赖）。
3. **本项目没有 multipart 测试先例**（实测 `MULTIPART_FORM_DATA|MultiValueMap|ByteArrayResource` 在 test 树 0 命中）⇒ 自己搭：
   `MultiValueMap<String,Object>` + `ByteArrayResource`（**覆写 `getFilename()`**）+ `HttpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA)`，用 `restTemplate.exchange(url, POST, new HttpEntity<>(parts, headers), String.class)`。
   `tenantId` 作为**文本 part** 一起发（`ByteArrayResource` 或 `HttpEntity<String>`）。
   （好消息：实测 `ConsoleAuthFilter` **不读 body** ⇒ 过滤器不会吃掉 multipart 流。）

```java
@Test
void uploadingATextFileLandsOnDiskAndCreatesAPendingRow() {
    var res = postMultipart("/api/kb/documents", TENANT, "notes.md", "# hello\nworld\n".getBytes(UTF_8));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    long id = body(res).path("data").path("id").asLong();
    assertThat(body(res).path("data").path("status").asText()).isEqualTo("PENDING");
    assertThat(activeRowsFor(TENANT)).as("必需那条：定向查").isEqualTo(1);
    assertThat(Files.readString(fileStoreRoot().resolve(TENANT + "/" + sha256Of(fileBytes)))).as("原件必须落盘")
            .isEqualTo("# hello\nworld\n");
    assertThat(orphanFilesUnder(fileStoreRoot())).as("不许留临时文件").isEmpty();
}

@Test
void uploadingTheSameBytesTwiceIsIdempotentAndLeavesNoStrayFile() {
    long first = uploadId(TENANT, "a.md", BYTES);
    long second = uploadId(TENANT, "b.md", BYTES);   // 不同文件名、同内容 ⇒ 命中唯一键
    assertThat(second).as("同租户同内容必须返回同一行（幂等），不是 409/500").isEqualTo(first);
    assertThat(activeRowsFor(TENANT)).isEqualTo(1);
    assertThat(orphanFilesUnder(fileStoreRoot())).as("第二次写的临时文件必须被删掉").isEmpty();
}

@Test
void aTruncatedUploadLeavesNoRowAndNoFile() {
    // 故意只写一半就断开（Content-Length 说 100、实际发 40）
    assertThat(postTruncatedMultipart("/api/kb/documents", TENANT, "half.md", 100, 40).getStatusCode().is4xxClientError()).isTrue();
    assertThat(activeRowsFor(TENANT)).as("M5 的官方验收：中断上传不留脏数据").isZero();
    assertThat(orphanFilesUnder(fileStoreRoot())).as("含临时文件").isEmpty();
}

@Test
void uploadRejectsMissingTenantIdAndUnknownExtension() { /* 400 INVALID_PARAM ×2 */ }
```

- [ ] **Step 2: 跑它确认失败**

Run: `mvn -B -pl aihub-admin/aihub-web -am test "-Dtest=KbUploadIntegrationTest"`
Expected: FAIL（404 端点未映射 / 编译错）——**如实登记形态**：这不是判别力，判别力靠 Task 8 的变异体。

- [ ] **Step 3: 实现**

要点（完整代码按此写，**不要**自由发挥）：

```java
// KbFileStore：临时名 + 原子改名；路径穿越必须拒
public Path store(long tenantId, String sha256, InputStream in) throws IOException {
    Path dir = root.resolve(Long.toString(tenantId)).normalize();
    if (!dir.startsWith(root)) {
        throw new BizException(ErrorCode.INVALID_PARAM, "非法租户路径");   // 防御性，正常不可达
    }
    Files.createDirectories(dir);
    Path target = dir.resolve(sha256).normalize();
    if (!target.startsWith(dir)) {
        throw new BizException(ErrorCode.INVALID_PARAM, "非法路径");
    }
    Path tmp = Files.createTempFile(dir, ".upload-", ".part");      // 同目录 ⇒ 同文件系统 ⇒ ATOMIC_MOVE 可用
    try (OutputStream out = Files.newOutputStream(tmp)) {
        in.transferTo(out);
    }
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    return target;
}
// 失败/被放弃时调用方必须删掉 tmp（用 try/finally），KbUploadIntegrationTest 的 orphanFilesUnder 就是在钉这条
```

Controller 的关键取舍（**逐条都有理由，别改**）：

```java
@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<ApiResponse<UploadView>> upload(
        @RequestPart("file") MultipartFile file,
        @RequestParam(name = "tenantId", required = false) Long tenantId,     // §10 R2：写操作取请求体
        HttpServletRequest http) {
    if (tenantId == null) {
        throw new BizException(ErrorCode.INVALID_PARAM, "tenantId 不能为空");   // 400，与 M4 的 PUT /api/quotas 同形
    }
    // 扩展名白名单 md/txt（Task 7 加 pdf）——未知扩展名 400，不许靠"解析失败"兜
    // sha256 必须**边读边算**：DigestInputStream 包一次，落盘与摘要同一遍 IO
}
```

**提交后**发布（Task 3 才实现 publisher；本任务先只落 DB 与文件，**不要**占位、**不要**留 TODO 注释）。

- [ ] **Step 4: 跑测试确认通过**；- [ ] **Step 5: 全反应堆编译 + 提交**

```bash
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/ \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/KbDocumentController.java \
        aihub-admin/aihub-web/src/main/resources/application.yml \
        aihub-admin/aihub-common/src/main/java/com/aihub/common/audit/AuditAction.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbUploadIntegrationTest.java
git commit -m "feat(kb): accept document uploads with atomic storage, sha256 dedup and audit"
```

**（2026-10-03 控制器接手完成 —— 派发的子代理在被杀前做到 step1（测试类 + `AuditAction` + `application.yml`），
控制器从它留下的 `.m5t2-logs/STATUS.txt` 续做 step3–step6；本节记录与计划的偏差）**

1. **⚠️ Global Constraints 里"集成测试不许声明 `@TestPropertySource`"这条是错的**：`aihub.console.secret`
   在 `application.yml` 里**刻意没有默认值**（D16），而任何要签发控制台令牌的测试都**必须**注入它 ——
   照那条字面执行，测试根本写不出来。**真正的规则是"不许新增第 8 个 Spring 上下文"**，做法是：
   属性与既有测试类（`ConsoleLoginIntegrationTest` / `ApiKeyAdminIntegrationTest`）**逐字相同**、
   且**不加 `@Import`**（`@Import` 会把导入者类算进缓存键 ⇒ 必然 fork）。
   实测全量 `Tomcat started on port` = **7** ✓。完整纪律见 `docs/CONVENTIONS.md` §8。
   **推论**：要控制某个可配置项（存储目录之类）时，**别去加属性** —— 把它做成 `application.yml` 的默认值
   （`aihub.kb.storage.root: target/kb-storage`），测试用默认值 + 自己清理。
2. **自然 RED 被环境吃掉了（诚实登记）**：第一次 RED 报 `Tests run: 5, Errors: 5`，但根因**不是**
   "控制器不存在"，而是 **Testcontainers 找不到 Docker**（`Could not find a valid Docker environment` /
   `Connection refused`）—— 当天 Docker Desktop 已停（WSL2 后端 `docker-desktop` 发行版 Stopped）。
   **那次不算 RED**；控制器把 Docker 拉起后代码已写好，RED 不可复得 ⇒ 判别力改由变异体提供：
   **M-A**（审计改空操作）红在 `KbUploadIntegrationTest.java:143`（`expected: 1L but was: 0L`）、
   **M-B**（去掉 `size<1` 的 400 守卫）红在 `:222`（`expected: 400 BAD_REQUEST but was: 200 OK`，
   变异签名 `"size":0` 出现在响应体里）；两条都按"备份 → 变异 → **`clean`** → 跑 → 还原 → **再 `clean`**"
   执行并三重验证（SHA256 一致 / `MUTANT` 残留 0 / 还原后 `clean` 跑绿 **5/0**）。
3. **覆盖缺口（诚实登记，别当成已覆盖）**：把 `KbFileStore` 的"临时文件 + 原子改名"改成"直接写目标文件"，
   **没有任何用例会红** —— 临时文件只在飞行中存在，黑盒测试看不见它。⇒ 这条纪律目前**只靠代码审查**。
4. **对决定 D5 的一处有意偏离**：重复上传时计划写"删掉本次的临时文件"；实现改成
   "**原件在盘上就删临时文件，原件不在就 promote 补上**" —— 用同样的代价自愈"有行没文件"（运维误删）的破洞；
   对测试仍然只剩 1 个文件。
5. **实测数字**（产物 `.m5t2-logs/`）：聚焦 `KbUploadIntegrationTest` **5/0**；全量 `aihub-common` **68/0**、
   `aihub-web` **270/0**（= M4 基线 264 + Task 1 的 1 条结构用例 + 本任务 5 条）、**`Tomcat` = 7**；
   整反应堆 `test-compile` 8 模块 `SUCCESS`。
6. **提交**：`258b2d4`（6 文件：4 新增 + 2 修改）。**没有**多建计划外文件；未碰 `pom.xml` / 迁移 / compose。

```bash
# （上面那条 commit 命令的收尾）
```

**验收判据：** 上传返回 `PENDING` 且原件落盘（内容逐字可比）；同内容二次上传返回**同一 `id`**；**截断上传不留行、不留文件**；缺 `tenantId`/未知扩展名 ⇒ 400；每次成功上传产生且仅产生一条**不含敏感字段**的 `KB_DOCUMENT_UPLOAD` 审计行。
**RED 证据：** `aTruncatedUploadLeavesNoRowAndNoFile` 在"先建行再落盘"的顺序下红（这正是官方的"中断上传不留脏数据"）。

---

## Task 3: MQ 拓扑 + 提交后发布 `kb.parse`

**Files（2026-10-03 控制器订正：`KbEmbedBatch` 是计划漏写的第 4 个类；编解码测试**不许**放 `aihub-mq`）:**
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbTopology.java`
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbEmbedBatch.java`（**计划漏写**：`embedBatches` 的返回类型，`record KbEmbedBatch(long docId, int seqFrom, int seqTo)`）
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbMessageCodec.java`
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbTopologyConfig.java`（**照 `MeteringTopologyConfig` 的"死信六件套"**，只是这里要**两套**业务队列/绑定）
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentPublisher.java`（`publishParseAfterCommit(docId)`）
- Modify: `KbDocumentService.upload`（建行之后注册 `afterCommit` 发布）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbMessageCodecTest.java`（**纯单测**）+ `.../KbDocumentPublisherTest.java`（**纯单测**，after-commit 判别力）+ `.../KbPublishIntegrationTest.java`

**Interfaces:**
- Consumes: 既有 `RabbitTemplate`、`MeteringTopologyConfig`（**先读它，照抄形状**）、Spring 的 `TransactionSynchronizationManager`（先读既有 `ConfigChangePublisher.publishAfterCommit`）。
- Produces:
  - **`KbTopology` 的九个常量以决策 **D16** 为准（2026-10-03 控制器裁决）**：`EXCHANGE = "aihub.kb.exchange"`、
    `PARSE_QUEUE = "aihub.kb.parse"`、`PARSE_ROUTING_KEY = "aihub.kb.parse"`、`EMBED_QUEUE = "aihub.kb.embed"`、
    `EMBED_ROUTING_KEY = "aihub.kb.embed"`、`DEAD_LETTER_EXCHANGE = "aihub.kb.dlx"`、
    `DEAD_LETTER_ROUTING_KEY = "aihub.kb.dlq"`、`DEAD_LETTER_QUEUE = "aihub.kb.dlq"`、
    `MESSAGE_CONTENT_TYPE = "text/plain;charset=UTF-8"`（与 `MeteringTopology` **逐字同构**）。
    ⚠️ 本行原先写的 `EXCHANGE = "aihub.kb"` 与裸路由键 `kb.parse`/`kb.embed` **与 D16 冲突** ——
    实施者发现并上报，控制器裁决**按 D16 改回**（"照抄既有形状、不发明新形状"是 D16 的全部价值所在）。
    `MESSAGE_CONTENT_TYPE` 由**发布端显式设置**（照计量链路的发布端），并由
    `KbDocumentPublisherTest#theParseMessageDeclaresItsContentType` 钉住 —— 别再让它成为"声明了没人用"的死常量。
  - `KbMessageCodec.parse(docId)` / `embed(docId, seqFrom, seqTo)` 的**线格式**：`parse:{docId}`、`embed:{docId}:{seqFrom}:{seqTo}`（文本分隔符，**无 JSON**，与 `MeteringEventCodec` 同风格）+ 解码 + **畸形载荷必须抛**（让它进 DLQ 而不是被静默丢弃）；
  - `KbTopology.embedBatches(docId, chunkCount, batchSize) -> List<KbEmbedBatch>`（纯函数：`ceil(N/batchSize)` 条，**最后一批可以不满**）。

- **（2026-10-03 控制器派发前扫描 —— 7 条"照字面做就会红 / 会交出假证据"）**
  1. **⚠️ `aihub-mq` 没有 `src/test`、其 `pom.xml` 也没有 `spring-boot-starter-test`** ⇒ 计划把 `KbMessageCodecTest`
     放在 `aihub-mq/src/test/java` **编译不过**。⇒ **改放 `aihub-web/src/test/java/com/aihub/admin/kb/`**
     （纯 JUnit + AssertJ，**不启 Spring** ⇒ 不占上下文预算）；`aihub-web` 经 `aihub-service → aihub-mq` 的传递依赖
     已能看到那些类。**不许**为此改 `aihub-mq/pom.xml`（不在 Files 里 ⇒ 越界）。
  2. **⚠️ 计划给的测试助手在本项目不存在**：`receiveFrom(queue, Duration)`、`activeRowsFor(...)` **全仓不存在**；
     `postMultipart(...)` 是 `KbUploadIntegrationTest` 自己的 `private` 助手。⇒ 收消息用**既有先例**
     `rabbitTemplate.receive(queue, timeoutMs)`（`MeteringConsumerIntegrationTest:399/412`），**轮询到 deadline**
     （不是"收一条就断言相等"—— 队列里会有别的用例的消息）；行数断言照 `KbUploadIntegrationTest.rowsFor` 的形状自己写。
  3. **⚠️ 计划的 RED 证据是错的**（它写"在 Task 8 补的回滚对照上红"）。事实：那种对照**仓库里已经有了**
     （`ChannelAdminIntegrationTest#rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark`），但它靠
     **`@Import` 一个 probe 配置**造回滚 ⇒ **会 fork 上下文**（见 `ChannelAdminIntegrationTest:589`）⇒ Task 3 **不许**照抄。
     ⇒ 换成**零上下文**的真判别力单测 `KbDocumentPublisherTest`：
     `TransactionSynchronizationManager.initSynchronization()` → `publishParseAfterCommit(id)` →
     **`verify(rabbitTemplate, never())`（提交前一条都不许发）** → 手动 `getSynchronizations().forEach(TransactionSynchronization::afterCommit)`
     → `verify(rabbitTemplate).convertAndSend(EXCHANGE, PARSE_ROUTING_KEY, "parse:" + id)`；`finally` 里 `clearSynchronization()`。
     **把实现改成"立即发"这条就必红** —— 这就是本任务的自然 RED（也是它的判别力来源）。
  4. **⚠️ `git add` 又写了目录**（`.../mq/kb/`）—— **第 5 次**同类缺陷。必须逐个展开成**显式文件路径**（含新增的 `KbEmbedBatch.java`）。
  5. **⚠️ 队列里有前任的残留**：Task 2 的上传用例**每次上传都会发一条** `kb.parse` ⇒ `KbPublishIntegrationTest`
     必须先在 `@BeforeEach` **抽干** `kb.parse`（`while (rabbitTemplate.receive(queue, 0) != null) { }`），再上传、再断言。
  6. **⚠️ 计划没写、但必须由本任务定下来的两件事**：
     - `KbTopology` 除队列/DLX/DLQ 外还必须有**路由键**常量（`PARSE_ROUTING_KEY = "kb.parse"`、
       `EMBED_ROUTING_KEY = "kb.embed"`、`DEAD_LETTER_ROUTING_KEY`），且**两个业务队列都要挂 `x-dead-letter-exchange`**
       （照 `MeteringTopologyConfig`：exchange + 两个 queue + 两个 binding + DLX + DLQ + DLQ binding）。
     - **发布失败怎么办**（计划完全没写）：业务写**已经提交**，把"发不出去"升级成"业务失败"只会让用户以为没存上（
       `ConfigChangePublisher.publish` 的类注释就是为这件事写的）。⇒ **吞 `RuntimeException` + WARN + 计数器
       `aihub.kb.publish_failures`**（照 `PUBLISH_FAILURES_METRIC` 的先例把名字暴露成常量）。
       **残余风险如实登记**：消息真丢了 ⇒ 该行**永远 `PENDING`**；Task 6 的 DLQ 只管**消费端**失败，管不到这个 ⇒
       只能靠计数器告警 + 运维重发（Task 8 的边界清单要写上）。
  7. **上下文预算**：`KbPublishIntegrationTest` 必须**逐字复用** Task 2 那把合成密钥（`@TestPropertySource(properties = {"aihub.console.secret=console-it-secret-0123456789abcdefghijklmn"})`）
     且**不加 `@Import`** ⇒ `Tomcat` 保持 **7**；`KbMessageCodecTest`/`KbDocumentPublisherTest` 是**纯单测**（不启 Spring）⇒ 不占预算。
     ⚠️ 后续任务（Task 4 起）的 consumer 会在**同一个 JVM** 里消费 `kb.parse` ⇒ 本任务"从队列收消息"的断言到那时会被消费者抢走；
     Task 4 起要用**数据库状态**（`kb_document.status`/`kb_chunk`）做判据，而不是抢队列。

- [ ] **Step 1: 写失败测试**（编解码的固定向量 + after-commit 判别力 + 端到端发布）

```java
// KbMessageCodecTest（纯单测）
assertThat(KbMessageCodec.parse(7L)).isEqualTo("parse:7");
assertThat(KbMessageCodec.decodeParse("parse:7")).isEqualTo(7L);
assertThatThrownBy(() -> KbMessageCodec.decodeParse("embed:7:0:9")).as("错路由的消息必须响亮失败，才能进 DLQ").isInstanceOf(IllegalArgumentException.class);
assertThat(KbTopology.embedBatches(7L, 25, 10)).extracting(KbEmbedBatch::seqFrom).containsExactly(0, 10, 20);   // 25 段 ⇒ 3 批，末批 5 段
assertThat(KbTopology.embedBatches(7L, 0, 10)).as("0 段不该走到这里（Task 4 会先判 FAILED）").isEmpty();

// KbDocumentPublisherTest（纯单测；控制器 2026-10-03 换掉"回滚对照"，因为它要 @Import、会 fork 上下文）
TransactionSynchronizationManager.initSynchronization();
try {
    publisher.publishParseAfterCommit(7L);
    verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class));   // 提交前一条都不许发
    TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    verify(rabbit).convertAndSend(KbTopology.EXCHANGE, KbTopology.PARSE_ROUTING_KEY, KbMessageCodec.parse(7L));
} finally {
    TransactionSynchronizationManager.clearSynchronization();
}

// KbPublishIntegrationTest：上传后消息真的进了 kb.parse（收消息用既有 RabbitTemplate.receive，别自造 receiveFrom）
var res = postMultipart("/api/kb/documents", TENANT, "a.md", BYTES);
assertThat(receiveParsingMessageFor(idOf(res), Duration.ofSeconds(10)))
        .as("提交后必须发出解析消息").isTrue();
assertThat(rowsFor(TENANT)).isEqualTo(1);
```

- [ ] **Step 2: 跑它确认失败** → 队列不存在 / 收不到消息 / `KbDocumentPublisher` 不存在（**如实登记形态**）。
- [ ] **Step 3: 实现**（拓扑照抄计量链路，两套队列各挂死信；`publishAfterCommit` 用 `TransactionSynchronizationManager.registerSynchronization`，**提交后才发**）
- [ ] **Step 4: 跑测试确认通过**；- [ ] **Step 5: 提交**

```bash
# 2026-10-03 订正：原稿第一行是【目录】（本项目第 5 次同类缺陷）⇒ 逐个显式路径
git add aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbTopology.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbEmbedBatch.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbMessageCodec.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbTopologyConfig.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentPublisher.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentService.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbMessageCodecTest.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbDocumentPublisherTest.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbPublishIntegrationTest.java
git commit -m "feat(kb): declare the parse/embed queues with dead-lettering and publish after commit"
```

**验收判据：** `kb.parse`/`kb.embed`/DLQ 被声明（**两个业务队列都含 `x-dead-letter-exchange`**）；上传事务**提交后**消息才进队列
（判据 = `KbDocumentPublisherTest` 的"提交前 `never()`"）；畸形载荷在编解码处**抛**（不允许静默丢）；`Tomcat` 仍 **7**。
**RED 证据（2026-10-03 订正）：** 把 `publishParseAfterCommit` 改成**立即发布** ⇒ `KbDocumentPublisherTest` 的
"提交前 `verify(never())`" 必红。**原稿那条"回滚对照"作废**：它靠 `@Import` ⇒ 会 fork 第 8 个上下文（仓库里那条既有对照的代价就是这么来的）。

**（2026-10-03 控制器裁决 —— 实施者上报的 D16 命名冲突 + 控制器发现的"卡住行没有补救手段"）**
1. **D16 赢**：`KbTopology` 的九个常量按 D16 与 `MeteringTopology` **逐字同构**（`EXCHANGE = "aihub.kb.exchange"`、
   `PARSE_ROUTING_KEY = "aihub.kb.parse"`、`EMBED_ROUTING_KEY = "aihub.kb.embed"`）；实施者自创的
   `EXCHANGE = "aihub.kb"` + 裸路由键 `kb.parse`/`kb.embed` **已改回**（"照抄既有形状、不发明新形状"是 D16 的全部价值）。
   同时让发布端**显式声明 `MESSAGE_CONTENT_TYPE`**（照计量链路的发布端），并由
   `KbDocumentPublisherTest#theParseMessageDeclaresItsContentType` 钉住 —— 它不再是"声明了没人用"的死常量。
2. **重复上传也要发**（`KbDocumentService.upload` **两条路径都**注册 `publishParseAfterCommit`）：原先只在"新建行"
   路径发，注释给的理由是"运维重发由 DLQ/计数器的出口负责" —— **那句话是假的**：DLQ 只管**消费端**失败、
   计数器只**观测**发布失败，两者都不会把消息重新推下去 ⇒ "发布丢了 ⇒ 行永远 `PENDING`"曾是**没有任何补救手段**
   的死状态。现在**重复上传 = 现成的重试手势**，由
   `KbPublishIntegrationTest#reUploadingTheSameBytesPublishesAgainSoAStuckRowCanBeRetried` 钉住。
   ⇒ **D5 的口径据此更正**："无副作用"指的是**数据**（不新增行、不新增文件），**不是**"不发消息"——
   重复上传**会重新触发一次解析**（代价有界，且 Task 4/5 的写入按设计幂等，重放收敛）。附录 B 第 8 条记了这条边界。
3. **控制器自验（评审子代理第 11 次被外部杀死、未交回报告 ⇒ 按 Task 16 的先例由控制器代验）**：
   D16 九个常量**逐字一致** ✓；`KbMessageCodecTest` 是 `class KbMessageCodecTest {`（**零 Spring**）✓；
   `bothBusinessQueuesDeclareADeadLetterExchange:115` 在位 ✓；**控制器自己重做 M1 变异**（把"提交后发"改成"立即发"）⇒
   `publishesOnlyAfterCommitNeverBefore:73` 报 **"Never wanted here"**、`Tests run: 4, Failures: 3` + `BUILD FAILURE`，
   还原（SHA 一致 / `MUTANT` 残留 0）后 `clean` 回绿 **4/0** ✓。**全量 `Tomcat started on port` = 7、`aihub-web` 287/0** ✓。

---

## Task 4: consumer-parse（解析 → 切分 → 写 `kb_chunk` → 分批发 `kb.embed`）

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbChunker.java`（纯函数）
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbTextExtractor.java`（`md`/`txt`；Task 7 加 `pdf`）
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbParseConsumer.java`
- **Modify（2026-10-03 控制器补，原计划漏写了它）**：`aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/KbChunkMapper.java`
  —— 现在是光秃秃的 `BaseMapper<KbChunkEntity>`，**没有 upsert**，而"重放不产生重复段"必须靠它。
- Modify: `aihub-admin/aihub-web/src/main/resources/application.yml`（`aihub.kb.chunk.size-chars=800`、`overlap-chars=100`、`aihub.kb.embed.batch-size=10`）
- Test: **`aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbChunkerTest.java`**（**2026-10-03 订正**：原写
  `aihub-service/src/test/...`，但该模块**没有测试目录、也没有测试依赖** ⇒ 编译不过）+ `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbParseIntegrationTest.java`
- **（2026-10-03 控制器复核后补进 Files 的 4 个 —— 实施者上报、控制器裁定"属计划漏写、非越界扩张"）**：
  - `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbParseSink.java`（**端口**）+ `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbParseService.java`（**实现**）：
    `aihub-mq` 的 pom 只有 `aihub-common` + `spring-boot-starter-amqp`（实测），**看不到 `dao`/`service`**
    ⇒ 消费者**无法**直接读 `kb_document`/原件、也写不了 `kb_chunk`。这是 mq↔service 的**接缝**，
    照既有 `MeteringSink` 的先例做端口/适配器。**不加这两个文件，本任务无法实现**。
  - `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentPublisher.java`（改）：加 `publishEmbedAfterCommit`/`publishEmbed`
    —— 段必须先随事务提交、消息才可见（Task 5 的消费者会按 `(docId, seq)` 回读段），复用它的 after-commit 与失败计数器。
  - `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbPublishIntegrationTest.java`（改）：把"消息在队列里"的断言换成
    **数据库判据**（`status` 走到 `EMBEDDING`）—— 这是 Task 3 javadoc 与本节风险条**已经写明**的 prescription（消费者会抢队列），
    **属授权改动**，不是"偷改既有断言"。

**Interfaces:**
- Consumes: Task 3 的拓扑与编解码、Task 1 的 mapper、`KbFileStore`（读原件）。
- Produces: `KbChunker.chunk(String text, int sizeChars, int overlapChars) -> List<String>`（**空/全空白 ⇒ 空列表**）；`KbTextExtractor.extract(String extension, Path file) -> String`；消费者把 `PENDING|PARSING` → `PARSING` → 写 chunk（`vector_id = docId:seq`，D15）→ `chunk_count=N` → `EMBEDDING` → 发 N 条 `kb.embed`。

- [ ] **Step 1: 写失败测试**

```java
// KbChunkerTest（纯单测，边界是重点）
assertThat(KbChunker.chunk("", 800, 100)).isEmpty();
assertThat(KbChunker.chunk("   \n\t ", 800, 100)).as("全空白 ⇒ 0 段（Task 5 会判 FAILED）").isEmpty();
assertThat(KbChunker.chunk("abc", 800, 100)).containsExactly("abc");
// 2026-10-03 控制器订正：原写 hasSize(3) 并注 "800+700+700" —— **算术上不可能**：
// 3 段、每段 ≤800、相邻重叠 100 ⇒ 最多覆盖 3*800 − 2*100 = 2200 < 2500，会**静默丢 300 个字符**。
// 无缝隙滑窗的正确下界 = ceil((2500 − 100) / (800 − 100)) = 4。
assertThat(KbChunker.chunk("x".repeat(2500), 800, 100)).hasSize(4);            // 800 + 800 + 800 + 400
assertThat(reconstruct(chunks, 100)).isEqualTo(text);   // 拼接去重后必须逐字还原全文（把"丢字符"变成可证伪的失败）
assertThat(KbChunker.chunk("中".repeat(1000), 800, 100)).allSatisfy(s -> assertThat(s.length()).isLessThanOrEqualTo(800));
assertThatThrownBy(() -> KbChunker.chunk("abc", 100, 100)).as("重叠必须小于窗口，否则死循环").isInstanceOf(IllegalArgumentException.class);

// KbParseIntegrationTest
@Test void aParseMessageTurnsAFileIntoTrackedChunksAndEmitsEmbedBatches() {
    long id = uploadId(TENANT, "doc.md", mdWith(25, "段落"));
    publishParse(id);
    awaitUntil(Duration.ofSeconds(20), () -> statusOf(id).equals("EMBEDDING"));   // 有界轮询，不许 sleep
    assertThat(chunkRowsFor(id)).as("定向：只数这个 doc 的段").isEqualTo(25);
    assertThat(embeddedCountFor(id)).as("解析阶段还没嵌入").isZero();
    assertThat(embedBatchesReceived(KbTopology.EMBED_QUEUE, id)).as("25 段 / 每批 10 ⇒ 3 条消息").isEqualTo(3);
}

@Test void aReplayedParseMessageDoesNotDuplicateChunks() { /* 同一条消息投两次 ⇒ chunkRowsFor 仍 25 */ }
@Test void aMessageForAReadyDocumentIsAckedAndIgnored() { /* 先把状态置 READY，再投 parse ⇒ 状态不变、无新 chunk */ }
```

- [ ] **Step 2: 跑它确认失败**；- [ ] **Step 3: 实现**；- [ ] **Step 4: 跑测试确认通过**；- [ ] **Step 5: 提交**

```bash
# 2026-10-03 订正：补 KbChunkMapper（原计划漏写）；KbChunkerTest 从 aihub-service 挪到 aihub-web（那模块没有测试依赖）
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbChunker.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbTextExtractor.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbParseConsumer.java \
        aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/KbChunkMapper.java \
        aihub-admin/aihub-web/src/main/resources/application.yml \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbChunkerTest.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbParseIntegrationTest.java
git commit -m "feat(kb): parse documents into tracked chunks and fan out embed batches"
```

**验收判据：** 状态走到 `EMBEDDING`；`kb_chunk` 行数 == `chunk_count`；批次条数 == `ceil(N/batchSize)`；重放不产生新 chunk；`READY`/`FAILED` 的消息被 ack 丢弃。
**RED 证据：** 把 `uk_kb_chunk_doc_seq` 换成普通索引（或改成 `insert` 不 upsert）⇒ `aReplayedParseMessageDoesNotDuplicateChunks` 红。

**（2026-10-03 控制器复核 —— 实施结果与三处上报）**
1. **实施者抓出计划的三处缺陷**（都已按其正确行为落地并在用例里写明证明）：① **切分算术错**（见上面那行订正，
   原文的 3 段会**静默丢 300 字符**）；② **mq↔service 接缝漏写**（`aihub-mq` 看不到 dao/service ⇒ 必须加端口/适配器）；
   ③ **`KbChunkMapper` 无 upsert**（已由控制器在派发前补进 Files，实施者按 `VALUES()` 形式落地并写明 MySQL 8.4 的 1064 约束）。
2. **重放用例的"真空"风险已被实施者正面处理**（这是本任务最容易交出假绿的地方）：
   `aReplayedParseMessageDoesNotDuplicateChunks` **先把状态手工退回 `PENDING`** 再重放，否则状态守卫
   （`EMBEDDING ∉ {PENDING, PARSING}`）会在**写段之前**就 ack 丢弃 ⇒ 用例根本覆盖不到 upsert。
   `READY` 那条同理：必须用**真实上传（文件存在）**的行，否则去掉守卫后"缺文件 ⇒ 抛 ⇒ 回滚 ⇒ 状态仍是 READY"，
   断言**无法被最小变异打红**（= 没断言）。两条注释都写明了理由 —— 这是 §8「不可证伪的断言不算断言」的落实。
3. **控制器代办的静态核验**：提交面恰 **11 文件**、无越界（无 `pom.xml`/迁移/compose/`.env`/gateway）、`MUTANT` 残留 **0**、
   工作树**干净**；`KbChunkMapper.upsert` 用 `VALUES()`；`KbChunkerTest` 在 `aihub-web` 且**零 Spring**；
   `KbParseIntegrationTest` 逐字复用合成密钥、**不加 `@Import`** ⇒ 全量 **`Tomcat` = 7**、`aihub-web` **295/0**、`aihub-common` **68/0**。
4. **仍未覆盖（如实登记，**交给 Task 6**）**：`0 段 ⇒ FAILED("无可提取文本")`（D14）的逻辑**已实现但没有任何用例执行它**
   （`KbParseService.markNoText` 只有编译覆盖）；`KB_DOCUMENT_FAILED` 审计的实施者有意留到 Task 6。
   ⇒ **Task 6 必须补**：为"空/全空白文档 ⇒ `FAILED` + `error_msg` + 审计"加集成用例，并端到端验 DLQ
   （本任务只实现了"抛"，没有跑过 `重试 3 次 → RejectAndDontRequeue → kb.dlq` 这条链）。
5. `embedBatchesReceived(EMBED_QUEUE, …)` 是**已知短期判据**（Task 5 的消费者会抢走 `kb.embed`）—— 见 Task 5 段落的警告。

**（2026-10-03 独立评审 = 批准继续，0 Critical / 0 Important / 3 Minor）**
- **三条"防假绿"逐条成立**：① 重放用例**先退 `PENDING`** 再重放是**必要且充分**的（不退回则守卫在写段前就 ack 丢弃，
  用例根本走不到 upsert）；② `READY` 用例用**真实上传的行**才可被最小变异打红（合成行会因"缺文件⇒抛⇒回滚⇒仍 READY"而打不红）；
  ③ `reconstruct(...) == text` **能同时证伪**"步长错/重叠错/丢尾巴"三种变异。
- **事务与状态机无半成品/误终态路径**：单事务 ⇒ 中途异常整体回滚（不会留"半成品"）；`PARSING` **永不落盘**
  （迁移与写真值同事务）⇒ 并发两条 parse 被条件 UPDATE + InnoDB 行锁序列化，后到者 `affected==0` ⇒ ack 丢弃，
  且**与驱动 `useAffectedRows`/`CLIENT_FOUND_ROWS` 语义无关**（因为不存在跨事务的 `PARSING→PARSING` 匹配）。
- **越界 0**：`246e184..17ac2c3` 恰 **12 文件**；`KbPublishIntegrationTest` 的改动**未使断言变弱**
  （缺发布⇒两者都红；载荷写错⇒消费端抛⇒DLQ⇒停 PENDING⇒新断言红；消费端未注册⇒**新断言反而更严**）。
  唯一损失是**故障归因粒度**（不再能区分"发布端坏"vs"消费端坏"），而载荷由 `KbMessageCodecTest`、after-commit 由 `KbDocumentPublisherTest` 各自覆盖。
- **Minor（3 条，如实登记）**：① **⚠️ 实施者的 M1/M2/M3 红证日志采自"中期版本"**（红点行号 `:274`，而冻结提交上是 `:268`；
  那批日志早于它"强化 READY 用例"的编辑）⇒ **证据出处与最终提交不同版本**（评审已在冻结提交上**重做 M3 并拿到逐字相同的红**，
  并贴出根因 `Duplicate entry '1-0' for key 'kb_chunk.uk_kb_chunk_doc_seq'`，所以结论不受影响）；② `KbPublishIntegrationTest` 里
  `…AfterCommit` 这个方法名已不再证明 after-commit（该性质移到单测了）—— 命名味道；③ `KbChunkerTest` 的 reconstruct 用单一字符文本，
  理论上分不出"整体平移且长度多重集不变"（实际被长度断言拦下）。
  ①已写进 `CONVENTIONS.md` §8 作为通用纪律：**变异证据必须落在最终提交的那个修订上**。

**（2026-10-03 控制器派发前扫描 —— 5 条"照字面做就会红 / 会踩雷"）**
1. **⚠️ `KbChunkerTest` 不许放 `aihub-service`**：该模块**没有 `src/test`、`pom.xml` 里也没有任何测试依赖**
   （与 Task 3 的 `aihub-mq` 完全同款）⇒ 计划那条路径**编译不过**。改放
   `aihub-web/src/test/java/com/aihub/admin/kb/KbChunkerTest.java`（纯 JUnit + AssertJ，**不启 Spring** ⇒ 不占上下文预算）。
   **不许**为此改 `aihub-service/pom.xml`（不在 Files 里 ⇒ 越界）。
2. **⚠️ `KbChunkMapper` 必须改，而计划漏写了它**：它现在是光秃秃的
   `public interface KbChunkMapper extends BaseMapper<KbChunkEntity> {}`，**一个 upsert 都没有**；
   而"重放同一条消息不产生重复段"（本任务验收之一）**必须**靠
   `INSERT INTO kb_chunk (...) VALUES (...) ON DUPLICATE KEY UPDATE text = VALUES(text), vector_id = VALUES(vector_id), created_at = VALUES(created_at)`
   —— 唯一键 `uk_kb_chunk_doc_seq (doc_id, seq)` **已存在**（`V3__kb_pipeline.sql` 实测 ✓），所以这条路成立。
   ⇒ **Files 补一条 `Modify: aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/KbChunkMapper.java`**，
   **`git add` 也要含它**（否则要么越界、要么实施者自己发明一个不在清单里的路径 —— 本项目两头都踩过）。
   注意 MySQL 8.4 上**行别名 `AS new` 会 1064 语法错误**，必须用 `VALUES()`（M4 Task 15 已实测，见 CONVENTIONS §8）。
3. **⚠️ 消费端不许吞异常**：照既有 `MeteringConsumer` 的 javadoc（"载荷解不开时**抛异常**（不是 log + return）：
   静默 ACK 一条解不开的消息等于**永久丢数据**"）—— 本任务的编解码异常**必须往外抛**，才能走
   "重试 3 次 → `RejectAndDontRequeueRecoverer` → DLQ"这条既有链路。**`catch (RuntimeException) { log.warn; return; }`
   是禁止写法**（Task 6 会验 DLQ，那种写法会让它永远进不去）。
4. **状态迁移必须是"带条件"的，且 `READY`/`FAILED` 只 ack 不改**：用
   `kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>().eq(id).in(status, PENDING, PARSING).set(status, PARSING))`
   —— **受影响行数 == 0 就意味着"别人已经推进过 / 它已是 READY/FAILED" ⇒ ack 后直接返回，不写 chunk、不发消息**。
   这样重放一条已 `READY` 的消息不会把它打回 `PARSING`（本任务验收里那条"`READY` 的消息被 ack 丢弃"就靠它）。
   **不必**再加 mapper 方法（`LambdaUpdateWrapper` 就够，别扩大 Files）。
5. **两处计划没定、必须自己定死的**：
   - **批次大小从哪来**：`embedBatches(docId, chunkCount, batchSize)` 的 `batchSize` 计划没给 ⇒ 在 `application.yml`
     的 `aihub.kb` 段加 `embed.batch-size=10`（与 `chunk.size-chars=800`/`overlap-chars=100` 一起），
     **用默认值**（测试**不许**用 `@TestPropertySource` 覆盖它 ⇒ 那会 fork 上下文）。
   - **原件路径**：消息里只有 `docId` ⇒ 从 `kb_document` 行读出 **`tenant_id` + `sha256`**，再用
     `KbFileStore.targetFor(tenantId, sha256)` 拿路径（**不要**从消息里带路径，也别扫目录）。文件不存在**先按"抛"处理**
     （Task 6 再决定要不要把它转成 `FAILED`）。

**（2026-10-03 控制器补的两条风险）**
- **⚠️ 消费者一落地就"抢队列"**：本任务的 `@RabbitListener` 会在**每一个** Spring 上下文里订阅 `kb.parse` ⇒
  `KbUploadIntegrationTest`/`KbPublishIntegrationTest` 上传时发的消息会被它吃掉。本任务的断言因此**一律用数据库状态**
  （`kb_document.status` / `kb_chunk` 行数）。**回归必须包含 Task 2/3 的那两类用例**并如实报告有无交互。
- **`embedBatchesReceived(KbTopology.EMBED_QUEUE, id)` 这条断言有保质期**：它抢的是 `kb.embed`，**Task 5 的消费者
  一落地就会失效**（同 Task 3 那次）⇒ 见 Task 5 段落开头的警告。
- **`awaitUntil` 必须有界**（不许 `sleep`），且超时时**把当时的状态打出来**（否则排查只剩"超时了"三个字）。
- **`KbParseIntegrationTest` 必须逐字复用那把合成密钥**（`@TestPropertySource` + **不加 `@Import`**）⇒ `Tomcat` 仍 **7**。

---

## Task 5: consumer-embed + Chroma（**第一次端到端绿**）

> ⚠️ **（2026-10-03 控制器提前登记）本任务一落地，两件事会同时变化，必须一起改**：
> ① 你新加的 `@RabbitListener` 会订阅 `kb.embed`，于是 **`KbParseIntegrationTest` 里那条
> `embedBatchesReceived(KbTopology.EMBED_QUEUE, id)`（Task 4 的判据）会开始被抢走** —— 它必须改成
> **数据库判据**（`kb_chunk.embedded_at` 的行数 / `kb_document.status`），否则退化成 flaky。
> ② 同理，Task 4 的 `KbParseIntegrationTest` 里"上传/发布后等状态"的用例会与你的消费者并发 ⇒
> 断言一律走**状态轮询**（DB），不要抢队列。**改动这两处属于本任务的分内事，不是越界。**

**（2026-10-03 控制器派发前扫描 —— 7 条"照字面做就会红 / 会踩雷"）**
1. **⚠️ `KbIntegrationTestBase` 不许带任何属性来源**：本文件下面那句"只加 Chroma 单例容器与助手 ⇒ 与既有测试共用默认上下文"
   **加不出来**。Chroma 的 URL 与**假 embeddings 上游**的 URL **必须注册在 `TestContainers.registerInfrastructure(registry)`**
   —— 那是 `AbstractIntegrationTest.registerProperties` 与"非 UTC 方言"的第二个上下文**共用**的唯一一处；
   Chroma 容器本身声明成 **`TestContainers.CHROMA` 单例**（照 `REDIS`/`RABBITMQ` 的形状）。
   若在 `KbIntegrationTestBase` 上写 `@DynamicPropertySource` ⇒ **fork 第 8 个上下文**（判据：全量 `Tomcat started on port` 必须仍是 **7**）。
   **而且这不只是预算问题**：**每个**上下文都会跑 embed 消费者（Task 4 的 parse 消费者产出的 `kb.embed` 会在**同一个 JVM** 被消费，
   且多个上下文的消费者**争抢**同一个共享队列）⇒ 只有 Task 5 的上下文知道 Chroma/embeddings 的 URL 时，
   别的上下文里的 embed 消费者会失败 → 重试 → DLQ，制造噪声与假红。⇒ `KbIntegrationTestBase` = **无注解**的助手基类。
2. **⚠️ mq↔service 接缝（Task 4 同款，计划又漏写了）**：`aihub-mq` 的 pom 看不到 `aihub-service` ⇒ `KbEmbedConsumer`
   **不能**直接注入 `KbEmbeddingClient`/`KbVectorStoreClient`。照 `KbParseSink`/`KbParseService` 的先例加
   **端口 `KbEmbedSink`（`aihub-mq`）+ 实现 `KbEmbedService`（`aihub-service`）**（已补进 Files 与 `git add`）。
3. **⚠️ "超时用例走到 FAILED"在本任务做不到，而且会很慢**：写 `FAILED` + `error_msg` + `cleanup` 的是**自定义 `MessageRecoverer`**
   —— 那是 **Task 6** 的产物（D7）；更实际的是容器会**重试 3 次 + 指数退避**，一次 30 秒超时会把集成用例拖到分钟级。
   ⇒ **把"超时有界"折成零上下文单测**（`KbEmbeddingClientTest`：对着 `neverRespond()` 的假上游，断言耗时 `< timeout + 5s` **且抛异常**），
   **集成用例只证 READY 路径**；`FAILED`/`cleanup`/审计**留给 Task 6**（别在这里写"走到 FAILED"，那是一条无法达成的验收）。
4. **⚠️ 假上游要先定好 Task 6 要用的失败注入接口，而且必须能复位**（它是 JVM 级单例，用例之间会互相污染）：
   至少 `respondWithDeterministicVectors(dim)`（**假上游固定 8 维**，与附录 A 一致）、`neverRespond()`、`failOnBatch(n)`，
   以及每个用例 `@BeforeEach` 里的 `reset()`。
5. **⚠️ 命名与元数据逐字照附录 A**（**跨仓库契约，改它就是破坏性变更**）：collection 名 `kb_chunks`
   （由 `aihub.kb.chroma.collection` 配置）；`vector_id = "{docId}:{seq}"`；metadata **必填**
   `doc_id`(long) / `tenant_id`(long) / `seq`(int)；**文本不进 Chroma**（只回查 MySQL）。
   写进 Chroma 的坐标**必须**能从 `vector_id` 推出来（否则 `seqOfEveryRecord` 那类断言无从下手）。
6. **⚠️ `count` 是 `GET`**（`POST` 实测得 **405**）：Chroma 的 `count`/按名取集合走 `GET`，`upsert`/`get`/`delete` 走 `POST`，
   且**除"按集合名"的路由外都吃 `collection_id`（UUID）** —— 别照直觉写。
7. **`EMBEDDING_TIMEOUT_SECONDS` 必须有来源**：定义配置项 `aihub.kb.embedding.timeout-seconds=30`（yml 默认值，**测试不许覆盖**），
   客户端读它；用例断言"**配置读到的值 == 文档里写的那个值**"，否则"阈值与判据文字一致"这句无法证伪。

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbEmbeddingClient.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbVectorStoreClient.java`（Chroma REST）
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbEmbedConsumer.java`
- Create: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/support/KbIntegrationTestBase.java`（**不加任何注解**，只加 Chroma 单例容器与助手 ⇒ 与既有测试**共用默认上下文**）
- Create: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/support/FakeEmbeddingUpstream.java`（JDK `HttpServer`）
- Create（**2026-10-03 控制器补**，mq↔service 接缝）: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbEmbedSink.java` + `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbEmbedService.java`
- **Modify（2026-10-03 控制器补 —— 这条最容易漏）**: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/support/TestContainers.java`
  （加 **`CHROMA` 单例** + 在 `registerInfrastructure` 里注册 Chroma 与假上游的 URL —— 那是**唯一**的共享注册点）
- **Modify（2026-10-03 控制器补）**: `aihub-admin/aihub-web/src/main/resources/application.yml`
  （`aihub.kb.embedding.timeout-seconds=30`、`aihub.kb.chroma.collection=kb_chunks`、以及 `embedding.base-url`/`chroma.base-url` 的环境变量占位默认值）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbEmbedIntegrationTest.java`
- Test（**2026-10-03 控制器补**，零上下文）: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbEmbeddingClientTest.java`（**超时有界**那条搬到这里）
- **Modify（2026-10-03 控制器补）**: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbParseIntegrationTest.java`
  （段首警告 ① 要求把那条 embed 队列断言改成 DB 判据 —— 改既有文件所以必须列进 Files 与 `git add`）

**Interfaces:**
- Consumes: Task 4 的 chunk 行、`aihub.kb.embedding.base-url`、`aihub.kb.chroma.base-url`。
- Produces: `KbEmbeddingClient.embed(List<String> texts) -> List<float[]>`（**显式超时**）；`KbVectorStoreClient.upsert(String collection, List<VectorRecord>)`、`deleteByDocId(long docId)`、`countByDocId(long docId)`（**测试直接用它证明"真的写进去了"**）；`KbEmbedConsumer` 把 `kb_chunk.embedded_at` 打上，**全部非 NULL ⇒ `READY`**。

- [ ] **Step 0：镜像与容器的"现场核对"**（契约**已在派发前探明**，见上文「Chroma 契约」表 —— **照它写，不要再猜**）
  1. `docker -H tcp://127.0.0.1:2375 images docker.m.daocloud.io/chromadb/chroma --format '{{.Repository}}:{{.Tag}} {{.ID}} {{.Size}}'`
     —— 期望看到 **`…:0.5.23` / `18e67eecc172` / 668MB**（**扫描时已拉好**）；
  2. 若不在：`docker -H tcp://127.0.0.1:2375 pull docker.m.daocloud.io/chromadb/chroma:0.5.23`
     **并允许重试最多 3 次**（实测：第一次会在某层 `TLS handshake timeout`，第二次靠层缓存续传成功）——
     **每次的原始输出都要留证**；**连续 3 次失败才 BLOCKED**；
  3. 起一次性容器复核契约（**这一步是"别信文档信现场"**，不通过就不要往下写）：
     `docker run -d --name kb-chroma-probe -p 18000:8000 <镜像>` → 轮询 `GET :18000/api/v1/heartbeat` →
     **按契约表**走一遍 `collections(get_or_create)` → `upsert` → `GET count` → **重放 upsert（count 不变）** →
     `get(where=doc_id)` → `delete(where=doc_id)` → **count 归零** → `docker rm -f kb-chroma-probe`。
  **探不通 ⇒ 报 BLOCKED 并附原始输出**（D3：**不许**换假实现、**不许**擅自改用别的向量库 —— 那要用户拍板）。

- [ ] **Step 1: 写失败测试**（**这就是"可检索"的可证伪形式**）

```java
@Test void uploadingAMarkdownFileEndsUpQueryableInChroma() {
    upstream.respondWithDeterministicVectors(8);                  // 假 embeddings：维度固定、值可推导
    long id = uploadId(TENANT, "notes.md", markdownOf(25, "段落"));
    awaitUntil(Duration.ofSeconds(30), () -> "READY".equals(statusOf(id)));

    assertThat(chunkRowsFor(id)).isEqualTo(25);
    assertThat(embeddedCountFor(id)).as("每一段都要有 embedded_at").isEqualTo(25);
    assertThat(vectorStore.countByDocId(id)).as("★ 直接查 Chroma：真的写进去了").isEqualTo(25);
    assertThat(vectorStore.seqOfEveryRecord(id)).as("seq 必须连续无缺").containsExactlyElementsOf(IntStream.range(0, 25).boxed().toList());
    assertThat(vectorStore.tenantOf(id)).as("metadata 的 tenant_id 必须正确（检索侧靠它隔离）").isEqualTo(TENANT);
}

@Test void aReplayedEmbedBatchDoesNotGrowTheVectorStore() { /* 同一批投两次 ⇒ count 不变（upsert 生效） */ }
// 2026-10-03 订正：这条**从集成用例搬走** —— ① 写 FAILED 的是 Task 6 的 MessageRecoverer（D7），本任务做不到；
// ② 容器重试 3 次 + 指数退避 ⇒ 一次 30 秒超时会把集成用例拖到分钟级。
// ⇒ 有界性用**零上下文单测**（KbEmbeddingClientTest，不启 Spring）证；集成层只证 READY 路径。
@Test void anEmbeddingTimeoutIsBoundedAndFailsFast() {          // ← 放在 KbEmbeddingClientTest
    upstream.neverRespond();
    Instant start = Instant.now();
    assertThatThrownBy(() -> client.embed(List.of("x")))
            .as("超时必须抛，绝不许静默返回空向量（那会把整篇文档写成空向量并置 READY）")
            .isInstanceOf(RuntimeException.class);
    assertThat(Duration.between(start, Instant.now()))
            .as("阈值必须与配置项/文档写的那个值一致（aihub.kb.embedding.timeout-seconds）")
            .isLessThan(Duration.ofSeconds(timeoutSeconds + 5));
}
```

- [ ] **Step 2: 跑它确认失败**（Chroma 客户端未实现 / 状态停在 `EMBEDDING`）——**如实登记形态**。
- [ ] **Step 3: 实现**（Chroma 客户端按 Step 0 探到的真实契约写；embeddings 客户端带显式超时；`READY` 判定 = `count(embedded_at is not null) == chunk_count`）
- [ ] **Step 4: 跑测试确认通过**；- [ ] **Step 5: 提交**

```bash
# 2026-10-03 控制器订正：补 mq 端口 / service 实现 / TestContainers / application.yml / 两个测试文件（全部显式路径）
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbEmbeddingClient.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbVectorStoreClient.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbEmbedService.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbEmbedSink.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbEmbedConsumer.java \
        aihub-admin/aihub-web/src/main/resources/application.yml \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/support/TestContainers.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/support/KbIntegrationTestBase.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/support/FakeEmbeddingUpstream.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbEmbeddingClientTest.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbEmbedIntegrationTest.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbParseIntegrationTest.java
git commit -m "feat(kb): embed chunks into Chroma and finish the pipeline at READY"
```

**验收判据：** 上传 md ⇒ `READY`；**直接查 Chroma**：chunk 数 == `chunk_count`、`seq` 连续、metadata 的 `tenant_id` 正确；重放不增；embeddings 超时**有界**且阈值与判据文字一致。
**RED 证据：** 把 upsert 改成 insert（非幂等）⇒ 重放用例红；把 `vector_id` 改成随机 UUID ⇒ `seqOfEveryRecord` 红（无法从 id 推出坐标）。

**（2026-10-03 控制器接手实现 —— 派发的子代理被闲置超时取消，只留下 Step 0 的完整实证）**
- **接手理由与状态**：子代理跑了 ~2h **没写一行代码**（只在 Step 0 里把 Chroma 契约逐条实测并留证，见 `.m5t5-logs/step0-*.txt`；探针容器由控制器清理）。
  控制器按它的 `STATUS.txt` 直接实现：**13 个文件**（12 个见 Files + `KbPublishIntegrationTest` 的判据订正），全量 **`aihub-web` 303/0、`aihub-common` 68/0、`Tomcat` = 7**。
- **⚠️ 控制器实测到的根因（最值钱的一条）**：JDK `HttpClient` 对**明文 `http://`** 默认协商 HTTP/2，会发 `Upgrade: h2c` 前奏；
  Chroma 的 uvicorn/h11 **解析不了**它 ⇒ 建集合返回 **422 `{"loc":["body"],"msg":"Field required","input":null}`**（有时 400 `Invalid HTTP request received.`）。
  **同一个 URL、同一份 JSON 用 curl（HTTP/1.1）得到 200** ⇒ 症状像"体没写对"，真因是协议协商。
  修法 = 两个客户端都 `.version(HttpClient.Version.HTTP_1_1)`。已写进 `CONVENTIONS.md` §8。
- **⚠️ 第二类：流水线终点后移 ⇒ "等中间态"的断言集体变竞态**。Task 4 时 `awaitStatus(…, "EMBEDDING")` 是有效判据；
  embed 消费者落地后流水线不再停靠 `EMBEDDING` ⇒ 全量里**连红 2 条**（`KbPublishIntegrationTest` 两条，`当时状态=READY`）。
  连改 **3 个文件**（`KbParseIntegrationTest`、`KbPublishIntegrationTest` + 类注释）才全绿；判据统一改为**终态 `READY`** 或**行数不变量**。
  也写进 §8（"换阶段时 grep 一遍中间态字符串"）。
- **变异体（3 条，均三重验证：红点原文 / SHA 一致 / `MUTANT` 残留 0 / 还原后 clean 绿）**：
  **M1** 去掉 HTTP/1.1 钉 ⇒ 复现 422 + **400 `Invalid HTTP request received.`**（根因实证）；
  **M2** metadata 不写 `tenant_id` ⇒ `KbEmbedIntegrationTest:70` 红（租户隔离维度丢失）；
  **M3** `vector_id` 随机 UUID ⇒ `NumberFormatException: "60b1a8ad-…"` @ `:133`（坐实"id 必须可推导"，即计划点名的 RED）。
- **RED 如实登记**：本轮**没有**自然 RED（控制器是先写实现后跑；且一次失败运行会因 3×90 秒等待被工具杀死、日志截断，见 §8 的教训）
  ⇒ 判别力由上面 3 条变异体提供（与 Task 2/16 的先例一致）。
- **控制器自己的两处失误（登记）**：① 测试里三处内层 ASCII 引号未转义（`"超时上限 %d 秒"`）⇒ 编译错；
  ② 三个测试方法漏 `throws Exception`。都是"自己写的代码自己没先编译"的代价（编译校验步骤把它们一次暴露）。
- **仍未覆盖（如实登记，交 Task 6）**：`FAILED`/`cleanup`/审计（`MessageRecoverer`）、DLQ 端到端、`0 段 ⇒ FAILED` 的集成用例。

---

## Task 6: 失败路径与死信（**M5 的官方验收**：全成或全清 / 中断上传不留脏数据）

> ⚠️ **（2026-10-03 控制器提前登记 —— 本任务必须补齐的两件"已实现但没用例钉住"的事）**：
> ① **`0 段 ⇒ FAILED("无可提取文本")`（D14）**：`KbParseService.markNoText` **已实现**，
>    但**没有任何用例执行它**（Task 4 只有编译覆盖）⇒ 本任务要为它加集成用例：上传一份**全空白**的 `.md`
>    ⇒ 等 `kb_document.status` 走到 `FAILED`、`error_msg` 非空、**`kb_chunk` 的段数 == 0**、且**不发** `kb.embed`。
> ② **`KB_DOCUMENT_FAILED` 审计**：Task 4 有意**只做状态迁移、不做审计**（避免越界），并把出口留在这里。
>    还要端到端验 DLQ（`重试 3 次 → RejectAndDontRequeueRecoverer → kb.dlq`）—— Task 4 只实现了"编解码异常往外抛"。

**（2026-10-03 控制器派发前扫描 —— 6 条"做错了整个验收会落空 / 会波及既有链路"）**
1. **⚠️ 恢复器**必须**抛出**，否则消息被 ack 掉、根本进不了 DLQ**（本轮最关键的一条）。Spring AMQP 的语义是：
   `MessageRecoverer.recover(...)` **正常返回 ⇒ 容器 ack 这条消息**（消息消失）；只有**抛** `AmqpRejectAndDontRequeueException`
   才会 requeue=false 拒绝 ⇒ 经业务队列的 `x-dead-letter-exchange` 进 `aihub.kb.dlq`。
   ⇒ 恢复器里 **DB 部分可以 try/catch（记日志）**，但最后**必须** `throw new AmqpRejectAndDontRequeueException(...)`；
   `catch → return` 会让"置 FAILED"与"进 DLQ"**只剩一半**，而 `dlqDepth() > 0` 那条断言会红（那正是它该红的样子）。
2. **⚠️ 恢复器**不许**接到共享的监听容器工厂上（会改掉计量链路的行为）**：本仓**没有任何自定义容器工厂的先例**（实测），
   而 `spring.rabbitmq.listener.simple.retry.*`（`enabled=true / max-attempts=3 / 200ms×2 / max 2s`）与
   `default-requeue-rejected=false` 是**全局**的 ⇒ 若把 `MessageRecoverer` 设到默认工厂，`MeteringConsumer` 的死信行为会被一起改掉（它有自己的 DLQ 用例）。
   ⇒ **做法**：新建一个**专用工厂** `kbListenerContainerFactory`（`SimpleRabbitListenerContainerFactory`，
   **用注入的 `RabbitProperties` 派生出 maxAttempts/interval/multiplier/maxInterval** —— 别把 yml 里的数抄成字面量，那会漂移），
   只给它挂 `RetryInterceptorBuilder.stateless()...recoverer(kbMessageRecoverer)`；然后在**两个 kb 消费者**上写
   `@RabbitListener(queues = ..., containerFactory = "kbListenerContainerFactory")` ⇒ 计量链路**不受影响** ✓。
3. **⚠️ `AuditAction` 必须改，而计划漏写了它**（现只有 `KB_DOCUMENT_UPLOAD`，实测）⇒ 补 `KB_DOCUMENT_FAILED`；
   已加进 Files 与 `git add`（本项目已多次因为"常量不在清单里"而两头踩）。
   审计主体照 D13：READY/FAILED 那种**系统**产生的终态用 `new AuditService.Actor("SYSTEM", ...)`（不是 USER）。
4. **⚠️ `FakeEmbeddingUpstream` 要加 `failFromBatch(n)`**：它现在只有 `failOnCall(n)`（**只失败第 n 次**）——
   那样**重试就会成功**、永远到不了终态。本任务需要的是"**从第 n 次起一直失败**"（重试 3 次全失败 ⇒ 恢复器接管）。
   保留 `failOnCall`（`KbEmbeddingClientTest` 在用），新增 `failFromBatch`，并确保 `reset()` 把它清掉。
5. **DLQ 深度怎么读**：既有先例是 `rabbitTemplate.receive(DEAD_LETTER_QUEUE, 200)` 的**排空式**读法
   （`MeteringConsumerIntegrationTest:399/412`）。本任务用**非破坏性**读法更合适：注入 `AmqpAdmin`，
   `getQueueInfo(KbTopology.DEAD_LETTER_QUEUE).getMessageCount()`；同时**在 `@BeforeEach` 排空 DLQ**（共享 broker ⇒ 别的用例会留消息）。
6. **两处细节**：① `error_msg` 要**有界**地从异常派生（类名 + 摘要，截断），且要**能看出是哪一段**失败的
   —— 测试断言 `contains("embed")` 靠的是 `embeddings 上游返回 500` 里的 `embeddings`（**别改成不含 embed 的措辞**）；
   ② `0 段 ⇒ FAILED`（D14）那条路径走的是 `KbParseService.markNoText`，**不是**异常 ⇒ 它也必须**自己写 `KB_DOCUMENT_FAILED` 审计**
   （只在条件更新**命中了行**时写，否则会重复记）。

**Files（2026-10-03 控制器补：`AuditAction`、两个消费者（挂专用工厂）、假上游（加 `failFromBatch`）—— 共 9 个）:**
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbMessageRecoverer.java`（D7）
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentCleanup.java`（D8：`cleanup(docId)`，幂等可重入）
- Modify: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbTopologyConfig.java`（加**专用** `kbListenerContainerFactory`）
- **Modify（补）**: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbParseConsumer.java` + `KbEmbedConsumer.java`（挂 `containerFactory`）
- Modify: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentService.java`（+`markFailed(docId, reason)`，写 `KB_DOCUMENT_FAILED` 审计）
- **Modify（补）**: `aihub-admin/aihub-service/src/main/java/com/aihub/service/audit/AuditAction.java`（+`KB_DOCUMENT_FAILED`）
- **Modify（补）**: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/support/FakeEmbeddingUpstream.java`（+`failFromBatch`）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbFailureIntegrationTest.java`（`@TestPropertySource` 用那把合成密钥、**不加 `@Import`** ⇒ `Tomcat` 仍 7）

**Interfaces:**
- Consumes: 全部既有件 + DLQ。
- Produces: `KbDocumentCleanup.cleanup(long docId)`（① Chroma delete → ② `kb_chunk` delete → ③ 返回是否干净）；`KbMessageRecoverer.recover(Message, Throwable)`（置 `FAILED` + `cleanup` + 审计 **在进 DLQ 之前**）。

- [ ] **Step 1: 写失败测试**（本任务的三条是**核心判据**）

```java
@Test void aBatchThatKeepsFailingLeavesTheVectorStoreCleanAndTheMessageInTheDlq() {
    upstream.respondWithDeterministicVectors(8);
    upstream.failFromBatch(3);                                  // 前两批成功、第三批永远失败 ⇒ 重试耗尽
    long id = uploadId(TENANT, "doc.md", markdownOf(25, "段落"));

    awaitUntil(Duration.ofSeconds(40), () -> "FAILED".equals(statusOf(id)));
    assertThat(vectorStore.countByDocId(id)).as("★ 全清：Chroma 里一个 chunk 都不许留").isZero();
    assertThat(chunkRowsFor(id)).as("★ 全清：坐标也要清掉").isZero();
    assertThat(errorMsgOf(id)).as("必须能看出是什么错").contains("embed");
    assertThat(dlqDepth()).as("★ 死信与失败终态必须同时发生").isGreaterThan(0);
    assertThat(auditRowsFor(id, "KB_DOCUMENT_FAILED")).as("失败必须留审计").isEqualTo(1);
}

@Test void aDocumentWithNoExtractableTextFailsWithAnExplicitReason() {
    long id = uploadId(TENANT, "empty.md", "   \n\n".getBytes(UTF_8));
    awaitUntil(Duration.ofSeconds(20), () -> "FAILED".equals(statusOf(id)));
    assertThat(errorMsgOf(id)).as("扫描版 PDF 的 YAGNI 出口：如实说没说文本").contains("无可提取文本");
    assertThat(chunkRowsFor(id)).isZero();
    assertThat(statusOf(id)).as("绝不许静默 READY").isNotEqualTo("READY");
}

@Test void cleanupIsIdempotentAndReportsIncompleteCleanupHonestly() {
    // 让 Chroma delete 抛一次 ⇒ 断言：error_msg 含「清理未完成」且 kb_chunk 被**保留**（可重入）
}
```

- [ ] **Step 2: 跑它确认失败**（状态停在 `EMBEDDING`、Chroma 里留着 20 个 chunk —— **这就是 RED 的形态，如实登记**）
- [ ] **Step 3: 实现**（`MessageRecoverer` 必须在**放弃并路由到 DLQ 之前**被调用；`cleanup` 幂等；清理失败时**保留坐标**并如实写 `error_msg`）
- [ ] **Step 4: 跑测试确认通过**；- [ ] **Step 5: 提交**

```bash
# 2026-10-03 控制器订正：补 AuditAction / 两个消费者（挂专用工厂）/ 假上游（failFromBatch）—— 共 9 个显式路径
git add aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbMessageRecoverer.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbTopologyConfig.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbParseConsumer.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbEmbedConsumer.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentCleanup.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentService.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/audit/AuditAction.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/support/FakeEmbeddingUpstream.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbFailureIntegrationTest.java
git commit -m "feat(kb): roll back written chunks on terminal failure and route to the DLQ"
```

**验收判据：** 失败终态时 **Chroma 干净 + `kb_chunk` 干净 + 消息在 DLQ + 一条 `KB_DOCUMENT_FAILED` 审计**（四处**同时**断言，不许只断言其一）；0 段 ⇒ `FAILED("无可提取文本")`；`cleanup` 幂等；清理失败**如实登记**。
**RED 证据：** ① 删 `cleanup` ⇒ Chroma 残留 ⇒ 核心用例红；② 把置 `FAILED` 从 `MessageRecoverer` 挪进 `catch` ⇒ "重试期间状态自相矛盾"（第 2 次重试成功后状态已是 FAILED）⇒ 用例红；③ 把 `cleanup` 顺序改成"先删 `kb_chunk` 再删 Chroma"⇒ 清理失败用例红（坐标丢了、残留无法定位）。

**（2026-10-05 控制器复核与收尾 —— 子代理写完实现后被中止，控制器接手跑验收）**
- 子代理已完成实现（10 文件、`test-compile` **BUILD SUCCESS**、`STATUS.txt` 规范）后**被中止**（`code=10003`），只差"跑测试 → 回归 → 变异 → 提交"；控制器按它的 `STATUS.txt` 接手。
- **Docker 又一次停了**（跨两天），而且第一次"启动成功"是**假象**（端口在听、API 不答）⇒ 头两次运行**全部红在 `Could not find a valid Docker environment`**（不是代码问题）。
  受控重启（`Stop-Process` + 重新 `Start-Process`）后 **20 秒内就绪**。**教训**：Docker 的可用性必须**在跑测试的同一条命令里**验证（见 `CONVENTIONS.md` §8）。
- **⚠️ 测试的前置条件写错了一处（实现是对的，别改实现）**：`cleanupIsIdempotentAndReportsIncompleteCleanupHonestly` 拿一个
  **已经 `READY`** 的行去调 `onTerminalFailure` ⇒ 被 `markFailed` 的**故意守卫**（只允许 `PENDING/PARSING/EMBEDDING → FAILED`，
  免得一条**迟到**的死信消息把已 READY 的文档"救死"）**正确地**拒绝 ⇒ 红在 `expected FAILED but was READY`。
  控制器改为**先把行推回 `EMBEDDING`** 再调，并在用例里写明理由。
- **验收**：`KbFailureIntegrationTest` **3/0**；全量 `aihub-common` **68/0**、`aihub-web` **306/0**、**`Tomcat started on port` = 7**、`BUILD SUCCESS`。
- **变异体 3 条**（干净纪律 + 三重验证，红点原文见 `.m5t6-logs/M{1,2,3}.log`）：**M1** 清理不删 Chroma ⇒ `:116 expected 0 but was 20`（★ 全清）；
  **M2** 恢复器改成"正常返回" ⇒ 红在 `:123` 的等待超时（诊断行 `status=FAILED chunks=0 vectors=0` ⇒ 失败与清理都对了、**只是消息没进 DLQ**，即"必须抛出"那条机制）；
  **M3** 清理未完成仍删坐标 ⇒ `:189 expected 25L but was 0L`（D8）。还原后全仓 `MUTANT` 残留 **0**、`clean` 回绿 **3/0**。
- ⚠️ **工作区里有一处不属于任何任务、且是破坏性的改动**（控制器**未动、未提交**，已上报用户）：
  `aihub-gateway/src/main/java/com/aihub/gateway/relay/ModelsController.java` **删掉了 `@GetMapping("/v1/models")` 整个方法**（12 行）
  —— 那是一个**生产端点**，不是格式化。⇒ 本轮所有 commit 一律用**显式路径**以排除它。

---

## Task 7: PDF 支持（PDFBox）

**（2026-10-05 控制器派发前扫描 —— 2 条"照字面做就红 / 会往库里塞二进制"）**
1. **⚠️ 白名单不在 `KbDocumentController`，而在 `KbDocumentService`**（实测 `:66`：`private static final Set<String> ALLOWED_EXTENSIONS = Set.of("md","txt")`；
   `KbTextExtractor` 的 javadoc 也写着"上传白名单已经在 `KbDocumentService` 拦过一次"）。⇒ 照计划改 `KbDocumentController` 的话，
   **PDF 会在上传那一步就被 400 拒掉**，本任务的验收根本走不到 —— Files/`git add` 已订正为 `KbDocumentService`。
2. **⚠️ 不要往仓库里提交二进制夹具**：计划原本要 `test/resources/kb/sample-text.pdf`。⇒ 改为**在测试里用 PDFBox 现场生成**两份 PDF
   （① 有文本层：写几百行 HELVETICA 文本；② 无文本层：只画一个矩形、不写任何文本算子）。这样夹具是**可读、可复核、可复现**的代码，
   而不是一个来路不明的 blob（本项目一贯不提交非文本产物）。

**Files:**
- Modify: `aihub-admin/aihub-service/pom.xml`（+`org.apache.pdfbox:pdfbox`，**版本显式**）
- Modify: `KbTextExtractor`（+`pdf` 分支）
- **Modify（订正）**: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentService.java`（白名单 +`pdf`）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbPdfIntegrationTest.java`（**夹具在测试里生成，不提交 `.pdf`**）

**Interfaces:**
- Consumes: `KbTextExtractor` 的扩展点。
- Produces: `pdf` 走 PDFBox `PDFTextStripper`；**无文本层 ⇒ 返回空串 ⇒ Task 4 的 `chunkCount==0` 出口给 `FAILED("无可提取文本")`**（D14）。

- [ ] **Step 0：可解析性验证（不再可能 BLOCKED —— 扫描已实测通过，但仍要复跑留证）**：
  `mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=org.apache.pdfbox:pdfbox:3.0.3`
  ⇒ 必须看到 `Downloaded from aliyunmaven` + `BUILD SUCCESS`（**2026-10-02 扫描实测已通过**）。
  ⚠️ **已知会一起进来 4 个 artifact**：`pdfbox` + `fontbox` + **`bcprov/bcpkix/bcutil-jdk18on:1.78.1`**（合计 ≈12.5 MB）。
  报告里要**显式列出这 4 个**（这是我原先"PDFBox 最小依赖"设想的订正，见 D2）。失败 ⇒ **报 BLOCKED**，**绝不**自己写解析器。
- [ ] **Step 1: 写失败测试**：`pdf` 夹具（几 KB、有文本层）⇒ `READY` 且 chunk 数 > 0；把同一夹具的文本流删掉造"无文本层"⇒ `FAILED("无可提取文本")`。
- [ ] **Step 2～4**：红 → 实现 → 绿。
- [ ] **Step 5: 提交**

```bash
# 2026-10-05 订正：白名单在 KbDocumentService（不是 Controller）；夹具在测试里生成（不提交 .pdf）
git add aihub-admin/aihub-service/pom.xml \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbTextExtractor.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentService.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbPdfIntegrationTest.java
git commit -m "feat(kb): extract text from PDFs with PDFBox and fail loudly on empty text layers"
```

**验收判据：** 文本层 PDF ⇒ `READY`；无文本层 ⇒ `FAILED("无可提取文本")`（**不是** `READY`）；`pom.xml` 只多这一条且版本显式。
**RED 证据：** 把 `chunkCount==0` 的判定去掉（默认 `READY`）⇒ "无文本层"用例红（**静默成功是最糟的失败**）。

**（2026-10-05 控制器实施记录）**
- **版本偏差（登记）**：计划写 `pdfbox:3.0.3`，但本机 `.m2` 里只有 **3.0.5**（且 `pdfbox/fontbox` 都是 3.0.5）⇒ 用 **3.0.5**（版本仍**显式**）。
  **Step 0 的传递依赖实测清单**（本地仓库）：`pdfbox` + `fontbox`（皆 3.0.5）+ **bouncycastle** `bcprov-jdk18on` / `bcpkix-jdk18on` / `bcutil-jdk18on`
  ⇒ 证实"**PDFBox 不是零传递依赖**"（与派发前扫描一致，D2 的"最小依赖"设想已订正）。
- **控制器自己的两处失误（登记）**：① `PDRectangle` 写错包（正确是 `org.apache.pdfbox.pdmodel.**common**.PDRectangle`）⇒ 3 处编译错；
  ② 测试里一处**内层 ASCII 引号未转义**（与 Task 5 同一个坑）。
- **连带改动（必须改，不是越界）**：`KbUploadIntegrationTest` 有一条"`.pdf` 必须被 400 拒掉"的旧断言 —— pdf 进白名单后它必然红
  （全量实测 `expected 400 but was 200`）⇒ 反例换成 `.exe`，并在注释里写明原因。
- **验收**：`KbPdfIntegrationTest` **2/0**（有文本层 ⇒ READY 且 `embeddedCount == chunkRows`；无文本层 ⇒ `FAILED("无可提取文本")` + 0 段 + 无向量 + 不是 READY）；
  全量 `aihub-common` **68/0**、`aihub-web` **308/0**、**`Tomcat` = 7**、`BUILD SUCCESS`。
- **变异体 1 条**（计划点名的 RED）：去掉 `chunks.isEmpty() ⇒ markNoText` 的出口 ⇒ "无文本层"用例红，
  诊断行 **`status=PARSING chunks=0`**（文档**永远卡在 PARSING**：既不 FAILED 也不 READY）⇒ 坐实"静默成功/静默卡住是最糟的失败"。还原后 SHA 一致、`MUTANT` 残留 0。

---

## Task 8: 文档收口 + M5 全栈验收

**Files:**
- Modify: `docs/CONVENTIONS.md`（新增 §6.8 **KB 流水线与跨语言向量契约**；§4/§10 若需补一行）
- Modify: `README.md`（M5 段落 + 已知边界）
- Modify: `docs/superpowers/specs/2026-09-23-aihub-platform-design.md`（§6.4 与 §7.3 标注实现状态；订正 §5.2 的 `size`/`uploaded_at` → `size_bytes`/`created_at`）
- Modify: `docker-compose.yml` + `.env.example`（D3 的 chroma 服务与三个 env）
- Create: `.superpowers/sdd/m5-acceptance.md`（**git-ignored**，原始输出）

**Interfaces:** 本任务没有代码接口，只有**文档契约**与**验收现象**。

- [ ] **Step 1: 文档**（每条边界都要能回答"它有没有对应的代码/测试？"，**没有证据的句子不许写**）
- [ ] **Step 2: 全量测试**：`mvn -B clean test` ⇒ 报**每模块**实测数（M4 基线：`common 68` / `web 264` / `gateway 391` = **723**；M5 必然增加），并**实测 `Tomcat started on port`（基线 7，不许变 8）**。
- [ ] **Step 3: 真实 compose 全栈验收**：起 `chroma` + admin，真上传一个 md 与一个 pdf ⇒ `READY` 且**直接查 Chroma** 取回；真删消息/断网造一次失败 ⇒ `FAILED` + Chroma 干净 + DLQ 有消息；**中断上传不留脏数据**（官方判据）。
- [ ] **Step 4: 提交**

```bash
git add docs/CONVENTIONS.md README.md docs/superpowers/specs/2026-09-23-aihub-platform-design.md docker-compose.yml .env.example
git commit -m "docs(m5): record the KB pipeline and the cross-language vector contract"
```

**验收判据：** 设计文档 §12 对 M5 的那句话（**中断上传不留脏数据**）在**真实 compose** 上复现；CONVENTIONS 里的跨语言契约与代码**逐字一致**；README 的已知边界含 D9（取消未实现）、D14（扫描版 PDF 不支持）、D2（PDFBox 版面还原能力的残余）。
**RED 证据：** **没有反证的验收不算验收** —— 第 3 步必须有"关掉/改坏一处 ⇒ 现象消失"的对照（例如把 `cleanup` 关掉后 Chroma 里应留下残留），对照跑不出差异就说明观测点选错了。

**（2026-10-05 控制器派发前扫描 —— 3 条"照字面做就交不出证据 / 会卡死验收"）**
1. **⚠️ compose 里 `AIHUB_KB_EMBEDDING_BASE_URL` 指向哪里？计划没写，而这是 Step 3 的生死线**：
   容器里**没有**假上游（`FakeEmbeddingUpstream` 是 **test 作用域**、跑在测试 JVM 里），网关**也不转发** `/v1/embeddings`
   （README 已实测 `POST /v1/embeddings` ⇒ **404**）⇒ 没有可用上游时流水线必然停在 `FAILED`。
   ⇒ **验收分两档，README 必须如实写清**：
   - **A 档（离线必须做到 = 本任务的正式判据）**：`md` 与 `pdf` 真上传 ⇒ 解析/切分/批次发出
     （可从 `kb_chunk` 行数与日志看到）⇒ 由于**没有可用 embeddings 上游**，终态是
     **`FAILED` + Chroma 干净 + `kb.dlq` 有消息 + 一条 `KB_DOCUMENT_FAILED` 审计** —— 这恰好把 D7/D8 的失败路径
     在**真实 compose** 上验了；再加设计文档 §12 的那句"**中断上传不留脏数据**"。
   - **B 档（需要真实上游，不许编造）**：`READY` + **从 Chroma 取回** 需要**一个可用的 OpenAI 兼容 `/v1/embeddings`**。
     本机**没有** ⇒ **不许在 compose 上声称验过**；该现象由 `KbEmbedIntegrationTest`（**真 Chroma 容器** + 进程内假上游）覆盖。
     **若用户能给出上游 URL**，验收时把 `AIHUB_KB_EMBEDDING_BASE_URL` 指向它补跑 B 档。
2. **⚠️ `depends_on` 不许给 chroma 编 healthcheck**：chroma 镜像里有没有 `curl`/`wget`/`python` **没实测过**，
   写错会让 `service_healthy` **永远不满足** ⇒ admin **永远不启动**（比"晚几秒就绪"糟得多）。
   ⇒ 用 `condition: service_started`，并如实登记"chroma 的就绪是异步的，客户端自己重试"。
3. **⚠️ Step 2 的 `mvn -B clean test` 要拆成两半跑**：整反应堆（admin + **gateway 391**）在一次调用里可能超过命令时限
   被**杀死并截断日志**（Task 5 已实测过一次）。⇒ 拆成 `-pl aihub-admin/aihub-web -am` 与 `-pl aihub-gateway` 两条，
   分别报**每模块**实测数（M4 基线 723 = 68 + 264 + 391，M5 必然增加）。

**（2026-10-05 完成记录 —— Task 8 收口）**
- **Step 1（文档 + 配置）**：`docker-compose.yml`（真 `chroma` 服务 + `admin-files` 卷 + 三个 KB env +
  `chroma: service_started`）、`.env.example`、`CONVENTIONS.md` **§6.8**、`README.md`（M5 段落 + A/B 两档 + 9 条边界）、
  设计文档（§5.2 字段名订正、**§6.4 元数据契约订正**、§7.3、§12）。提交 `2d7984a`。
- **Step 2（全量）**：`aihub-common` **68/0**、`aihub-web` **308/0**、`aihub-gateway` **391/0** = **767**（M4 基线 723 ⇒ +44），
  `BUILD SUCCESS`，**`Tomcat started on port` = 7** ✓。
- **Step 3（真实 compose 全栈验收，A 档）**：`md`（2777 字符）⇒ `200 / id=1` ⇒ 日志 `切出 4 段` ⇒
  `进入终态 FAILED（stage=embed）`（无可用 embeddings 上游）⇒ 实测 `chunk_count=4` / `kb_chunk` **0 行** /
  Chroma count **0** / `kb.dlq` **1** 条 / 审计 `KB_DOCUMENT_UPLOAD` + `KB_DOCUMENT_FAILED` / 存储根只剩 1 个原件；
  **官方判据"中断上传不留脏数据"**：真发 539 字节却声明 5 MB 后断开 ⇒ `EOFException`，**行数不变、盘上不多文件** ✓。
  原始输出见 **`.superpowers/sdd/m5-acceptance.md`**（git-ignored）。
- ⚠️ **真发现（运维相关）**：`docker compose up --build` 在本机**构建失败** ——
  `org.apache.pdfbox:pdfbox:3.0.5` 不在构建容器的 Maven 缓存里，而**构建容器解析不了 DNS**
  （`repo.maven.apache.org: No address associated with hostname`）。绕法：
  `docker build --network=host -t aihub-platform-admin -f aihub-admin/aihub-web/Dockerfile .` + `docker compose up -d --no-build admin chroma`
  （**不改 Dockerfile / compose**）。**任何新增 Maven 依赖的里程碑都会踩这一下** ⇒ 已写进 README。
- **（2026-10-05 补跑 pdf，由用户提供真实文件）**：`12.4 幂级数.pdf`（**813,280 字节**）⇒ `200 / id=2` ⇒
  日志 `解析完成：doc 2 切出 9 段`（**文本层被抽出**，7130 字符）⇒ `文档 2 的向量与分段已清理干净` ⇒
  `进入终态 FAILED（stage=embed）`；`GET /api/kb/documents?tenantId=1` 实测
  `{id:2, sizeBytes:813280, status:"FAILED", chunkCount:9}` 与 `{id:1, chunkCount:4}`，`total:2`；
  `kb.dlq=2`、Chroma count=0、存储根 2 个 sha256 原件、审计 `KB_DOCUMENT_UPLOAD target_id=2`。
  ⇒ **"compose 上传 pdf"这条现在有证据了**。
- ⚠️ **仍如实登记的缺口**：**A 档无法在 Chroma 侧做向量清理的对照**（没有上游 ⇒ 向量从未写入），
  对照改落在 `kb_chunk`（`md` 写过 4 段、`pdf` 写过 9 段，终态都是 0 行）上；向量侧的清理由 `KbEmbedIntegrationTest` 覆盖。
- ⚠️ **工装坑（记下来免得下次再踩）**：PS 5.1 把字符串**管道**给原生进程时会带 **BOM** ⇒ `mysql` 报
  `ERROR 1064 ... near '﻿SELECT ...'`；`$OutputEncoding` 与 `TrimStart([char]0xFEFF)` 都没救，
  改用 **HTTP 接口**（`GET /api/kb/documents`）取状态最省事。另：`mysql -N -B` 与控制台显示中文会乱码，
  **库里的值是好的**（列是 utf8mb4）。
- **（2026-10-05 B 档尝试：被上游配额挡住，非本仓库问题）**：用户给了 DashScope 兼容端点与一把 Key，选"一次性反代注入密钥"
  （JDK `HttpServer` 单文件代理，密钥只经环境变量、不落盘、用完即杀）。实测：
  ① 客户端是 `baseUrl + "/v1/embeddings"` ⇒ 官方 URL 末尾的 `/v1` **必须去掉**（否则 `.../v1/v1/embeddings` ⇒ 404）；
  ② 上游返回 **`400` 而非 `401`** ⇒ **鉴权通过**；③ 但响应体是
  `"Free quota exhausted ... AllocationQuota.FreeTierOnly"`（`text-embedding-v3`/`-v4` 同样）⇒
  **账号免费额度耗尽**，任何模型都调不动 ⇒ **B 档仍未验**，且**不能算作本仓库的缺陷**。
  ④ 同时暴露一处**真缺口（已写进 README）**：`KbEmbeddingClient` **不发 `Authorization` 头**、也没有 api-key 配置项 ⇒
  **任何需要密钥的真实上游目前都接不上**；要接真上游必须补 `aihub.kb.embedding.api-key` + compose env 白名单 + 测试。
  ⑤ 顺带给 compose 补了 `AIHUB_KB_EMBEDDING_MODEL`（白名单，缺它就没法指定上游模型名）。
- **⚠️（2026-10-05 复跑尝试再抓一个真缺陷，纠正 Task 3 的结论）**：用户换成可用模型 `qwen3.7-text-embedding`（探针 `200`、维度 **1024**）后重传同一份 pdf，
  日志为 `KbParseService: 解析跳过：doc 2 不在 PENDING/PARSING（别人已推进 / 已 READY/FAILED），ack 丢弃`
  ⇒ **"重复上传 = 重试手势"只对仍 `PENDING` 的行成立**（Task 3 要救的正是那种），
  **对 `FAILED` 行无效** —— 带条件状态迁移会把重发消息 **ack 丢掉**。
  ⇒ **`FAILED` 是"只能看、不能重试"的终态**（无重试端点、重复上传是空操作）。**绕法**：换租户上传同一文件拿新行（仅验收）；
  **正路**：加"重试"入口或允许 `FAILED` 被重发消息推进。**B 档本次仍未跑成**（用户选择后台执行但未真正启动；
  反代已杀、admin 已复位到 `.env` 配置）。

---

## 附录 A：跨语言向量契约（**检索侧是另一个仓库的 Python，改它就是破坏性变更**）

| 项 | 值 | 备注 |
|---|---|---|
| collection 名 | `kb_chunks`（配置项 `aihub.kb.chroma.collection`） | 单 collection，靠 metadata 隔离租户 |
| `vector_id` | `"{docId}:{seq}"`（D15） | 可推导 ⇒ 重放覆盖幂等、可 grep |
| metadata 必填字段 | `doc_id`(long)、`tenant_id`(long)、`seq`(int) | 检索侧**必须**用 `where={"tenant_id": N}` 过滤 |
| 文本 | 存在 `kb_chunk.text`（**不**进 Chroma 的 metadata） | Chroma 只存向量与坐标；文本回查 MySQL |
| 删除语义 | 按 `where={"doc_id": id}` 全删 | 与 `cleanup(docId)` 同一语义 |
| 嵌入维度 | 由 embeddings 上游决定（假上游固定 8 维，真实值由配置/上游决定） | 换模型 = **重建 collection**（破坏性变更，必须记文档） |

## 附录 B：已知边界与不做事（Task 8 必须逐条写进 README）

1. **取消上传未实现**（D9）：上传只能等它跑完或失败；`cleanup` 已写成可复用形状。
2. **扫描版/图片型 PDF 不支持**（D14）：`FAILED("无可提取文本")`，**不做 OCR**。
3. **PDFBox 的版面还原能力有限**（D2）：多栏/表格可能串行。
4. **解析与嵌入都是"至少一次"**：靠 `uk(doc_id, seq)` + Chroma upsert 做幂等；同一 doc 的**并发**重复上传被唯一键吸收（D5）。
5. **`kb_chunk` 存了一份文本**（D1 残余）：与原件重复占空间，换来可重放与一条 SQL 清理。
6. **嵌入维度变更 = 重建 collection**（附录 A）：本里程碑不提供迁移工具。
7. **Chroma 单实例、无鉴权**（本地演示形态）：生产加固（凭据、网络隔离、多副本）属后续里程碑。
8. **发布端丢消息 ⇒ 该行会停在 `PENDING`**（控制器 2026-10-03 登记）：`kb.parse` 的发布失败只**计数**
   （`aihub.kb.publish_failures`）+ WARN，**不会**让已提交的业务写失败；而 **DLQ 只覆盖消费端失败**、计数器只观测
   ⇒ 这类行**没有自动重试**。**现成的补救 = 重新上传同一份内容**（幂等路径也会重发一条 `parse:{id}`，见 Task 3 的裁决记录）；
   若坚持不动数据，只能**手工向 `aihub.kb.parse` 投一条 `parse:{id}`**。代价：对已 `READY` 的文档重传会多做一次解析（有界、幂等收敛）。
