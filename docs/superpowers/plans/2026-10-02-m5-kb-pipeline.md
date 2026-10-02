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
| **D2** | **PDF 用 Apache PDFBox**（`org.apache.pdfbox:pdfbox`，**版本显式写死**，Task 7 引入并**先验证可解析**）。**不用 Tika**（依赖面大得多、能力远超需要）。 | PDF 解析是**算法**，不许自实现（与 M4 D2 对 bcrypt 的取向一致）；Tika 会拖进一长串传递依赖，而 M5 只需要"PDF → 文本层"。PDFBox 是那份依赖里最小、最直接的。**残余**：PDFBox 的版面还原能力弱于商业方案，多栏/表格会串行——已登记为已知边界。 | `aihub-admin/aihub-service/pom.xml` +1（版本写死）；Task 7 第一步是**可解析性验证**，失败即 BLOCKED。 |
| **D3** | **向量库 = 真 Chroma 容器**（`chromadb/chroma`，**tag 写死**，compose 服务名 `chroma`，测试用同一个 tag 的 `GenericContainer`）。**不许**用假 HTTP 上游替代 Chroma。 | "真的写进向量库且能按 `doc_id` 取回"是本里程碑**唯一**能证明写入侧做对了的判据；用假实现会让它退回成**不可证伪的承诺**（本项目已两次为此吃过亏）。Chroma 是**可容器化的真服务**（该真），而 embeddings 是**易变的外呼**（该假，见 D6）。**残余/风险**：Docker Hub 不可达时镜像可能拉不到 ⇒ **第一个 Step 就是探测镜像可获取性**，拉不到即 **BLOCKED 并报告**（**不许**静默换假实现）。 | `docker-compose.yml` + `chroma` 服务；测试基类里的**单例** `GenericContainer`（与 compose 同 tag）。 |
| **D4** | **`kb_chunk` 的列**：`id`、`doc_id`、`seq`、`text`(**MEDIUMTEXT, NOT NULL**)、`vector_id`(VARCHAR(128))、`embedded_at`(DATETIME(3) NULL)、`created_at`。**唯一键 `uk_kb_chunk_doc_seq(doc_id, seq)`**；`KEY idx_kb_chunk_doc(doc_id)`。 | **`text` 必须有**：embeddings 消费端要拿文本；用消息传文本会造大消息（RabbitMQ 不鼓励），用"重新解析原件"则让解析做两次。**`embedded_at` 必须有**：`READY` 的判据是"**所有** chunk 都已嵌入"，只靠 `chunk_count` 无法表达"本批已完成"（`chunk_count` 是**期望值**，不是**进度**）。**唯一键**让重放变成覆盖（幂等），**索引**让"按 doc 清理"走前缀。 | `V3__kb_pipeline.sql`；`KbChunkEntity`/`KbChunkMapper`。 |
| **D5** | **重复上传（`uk_kb_document_tenant_sha` 命中）⇒ 返回已存在那一行（幂等），不是 409、更不是 500**；本次写的临时文件**删掉**。实现方式**照 M4 Task 12 的定稿**：`INSERT … ON DUPLICATE KEY UPDATE id = id` + **再读**，**不抛异常、不 catch `DuplicateKeyException`**。 | V1 的唯一键决定了"同租户同内容"天然要去重，而"重复上传"是**用户的正常动作**（点了两次、换了文件名）。M4 已经用一次死锁实测证明：`catch DuplicateKeyException` + `SELECT … FOR UPDATE` 重读会死锁，且**即便不死锁**，REPEATABLE READ 下同一事务的普通重读**看不见**并发提交的行。 | `KbDocumentMapper.insertIfAbsent` + `KbDocumentService.upload`；用例：同内容两次上传 ⇒ **同一个 `id`**、行数不增、临时文件不留。 |
| **D6** | **embeddings 上游 = 配置化的 OpenAI 兼容 `/v1/embeddings`**；测试里用**宿主 `com.sun.net.httpserver.HttpServer`** 作假上游（本项目既有做法），并**能注入"第 N 批才失败"**。**不接真实计费 API**。 | 真 embeddings 需要密钥、网络与计费 ⇒ 验收**不可重复**；而假上游能**精确注入**"第 3 批失败""不响应（超时）"这类**只有失败路径才需要**的形态——这正是本里程碑的验收中心（全成或全清）**必须**能构造的。 | `KbEmbeddingClient`（base_url 来自 `aihub.kb.embedding.base-url`）；测试夹具 `FakeEmbeddingUpstream`（含 `failFromBatch(n)`、`neverRespond()`）。 |
| **D7** | **"进 DLQ" 与 "置 `FAILED`" 必须是同一处**：消费者 `catch` 里**只 log + 抛**（把重试交给容器）；**终态由自定义 `MessageRecoverer` 写**（置 `FAILED` + `error_msg` + `cleanup(docId)` + 审计）。重试策略 **3 次指数退避**（照 `MeteringConsumer` 的既有配置）。 | 若在 `catch` 里置 `FAILED`，第 2 次重试成功后状态就**自相矛盾**；若只在"消息进 DLQ 之后"由别的东西置 `FAILED`，就会出现"**进了 DLQ 但状态还停在 `EMBEDDING`**"。`MessageRecoverer` 是 Spring AMQP 在**放弃并路由到 DLQ 之前**被调用的钩子 ⇒ 把状态写在它里面，"死信"与"失败终态"永远同时发生。 | 自定义 `KbMessageRecoverer` + 用例：断言 `FAILED` **且** 消息**确实在 DLQ 里**（两者一起断言，不许只断言其一）。 |
| **D8** | **`cleanup(docId)` 的顺序**：① 删 Chroma（按 metadata `doc_id`）→ ② 删 `kb_chunk` → ③ 置 `FAILED`。**清理自身失败**⇒ `error_msg` 里**如实写"清理未完成"**并**保留 `kb_chunk`**（可重入），**绝不假装干净**。 | 顺序反了会在"Chroma 删成功但 `kb_chunk` 删失败"时留下**无法定位**的残留（坐标没了）。**全局不变量（可证伪）**：不允许「`READY` 但 Chroma 缺 chunk」，也不允许「`FAILED` 但 Chroma 还留着该 doc 的 chunk」。 | 用例：让假上游第 3 批失败 ⇒ 断言 `FAILED` + **Chroma 里该 `doc_id` 一个 chunk 都没有** + `kb_chunk` 空 + 消息在 DLQ。 |
| **D9** | **不做取消端点**（设计文档 §6.4 提过"取消"，但 §7.3 的接口表只有 `POST`/`GET /api/kb/documents`）⇒ 只做"**最终失败自动清理**"，并在 Task 8 **显式登记"取消未实现"**；但 `cleanup(docId)`/`KbDocumentService.cancel(docId)` 的形状要写成**将来能被取消复用**的样子（幂等、可重入）。 | 以**接口表为准**（它是"对外承诺"），且取消要实现"停掉在飞的消息"，在无 `docId` 级幂等锁的前提下成本远高于收益（YAGNI）。**残余**：用户上传后发现传错文件，只能等它跑完或失败后删行（手工 SQL）。 | Task 8 的"已知边界"必须含这条；README 同步。 |
| **D10** | **分批大小 = 10 段/批**，批次用消息载荷 `{docId, seqFrom, seqTo}` 表达；**解析完成时一次性算好所有批次**（`ceil(chunkCount / batchSize)` 条消息）。参数走配置 `aihub.kb.embed.batch-size`（默认 10）。 | 设计文档 §8.2 明写"10 段/批嵌入"。用**区间**而不是"每批带文本"，让消息体积恒定且可重放（文本从 `kb_chunk` 读）。 | `KbTopology.embedBatches(docId, chunkCount, batchSize)`（纯函数，可单测）。 |
| **D11** | **MQ 拓扑放 `aihub-mq`**（`KbTopology`/`KbMessageCodec`/消费者），**不放 `aihub-common`**。 | 计量那条之所以在 `aihub-common` 是因为 **gateway 也要发**；M5 的上传与消费**都在 admin 内部**，没必要去污染 `aihub-common` 的"零第三方依赖"面（`aihub-mq` 本来就能用 Jackson，但**编解码仍照 `MeteringEventCodec` 的文本分隔符风格**，与既有纪律一致）。 | `aihub-mq` 新增四个类；`aihub-common` **不动**。 |
| **D12** | **上传接口用 `multipart/form-data`**（字段名 `file`，可带可选字段 `filename` 覆盖）；`tenantId` **取请求体**（multipart 的文本字段），**不是**令牌里的（`CONVENTIONS` §10 **R2**：写操作平台级，但审计必须记**目标资源的**租户）。 | PDF 是二进制，JSON 装不下；而"写操作从请求体取租户"是 §10 已定死的控制面语义（M4 Task 8/9/10/12 一律照办）。**残余**：multipart 的 `tenantId` 若缺失 ⇒ **400 `INVALID_PARAM`**（与 M4 的 `PUT /api/quotas` 同形）。 | `KbDocumentController.upload(@RequestPart("file") MultipartFile, @RequestParam ...)`。 |
| **D13** | **新增 `AuditAction` 常量三个**：`KB_DOCUMENT_UPLOAD`（上传，写路径）、`KB_DOCUMENT_READY`（流水线成功）、`KB_DOCUMENT_FAILED`（失败终态，**含失败原因的非敏感摘要**）。**审计绝不记**：原件内容、chunk 文本、向量、任何密钥。 | 写操作必须留审计（`CONVENTIONS` §6.6 的纪律 + M4 的 `AuditService`）。**这三个是"该加"的共享常量**（别像 M4 Task 11 那样被"不要新增常量"绊住——那条针对的是"没打算写审计"的场景）。`READY`/`FAILED` 由**系统**写（`actor_type=SYSTEM`），`UPLOAD` 由用户写。 | `AuditAction` +3；每个 Task 的用例断言"产生了对应审计行且**不含**敏感字段"。 |
| **D14** | **0 段 = 失败**：解析后 `chunkCount == 0` ⇒ **`FAILED("无可提取文本")`**（这就是**扫描版/图片型 PDF 的 YAGNI 出口**，**不做 OCR**）。 | 设计文档没写这条，但"上传了一个没有文本层的 PDF"是完全正常的用户动作；静默 `READY` 会让检索侧永远查不到东西却显示成功——**这比失败更糟**。 | 用例：空内容 md ⇒ `FAILED` + `error_msg` 含"无可提取文本" + `chunk_count=0` + 无残留。 |
| **D15** | **`vector_id = "{docId}:{seq}"`**（字符串，稳定可推导），Chroma 侧用**同一值**当 id（upsert）。 | 可推导 ⇒ 重放覆盖天然幂等；可 grep ⇒ 排查"哪个 chunk 没写进去"时不用查两处；且**跨语言契约**只需说清这一个规则（附录 A）。 | `KbChunk.vectorId(docId, seq)`（纯函数 + 固定向量用例）。 |
| **D16** | **`kb.parse` 与 `kb.embed` 各一条队列 + 一个 DLQ**（`aihub.kb.parse` / `aihub.kb.embed` / `aihub.kb.dlq`，DLX `aihub.kb.dlx`），**照 `MeteringTopologyConfig` 的"死信三件套"**：业务队列声明 `x-dead-letter-exchange` + routing key，DLX 绑 DLQ。 | 两个阶段的**失败代价不同**（解析失败 = 文件问题，嵌入失败 = 上游/向量库问题），分开才能在排查时一眼区分；共用 DLQ 让运维只需盯一个地方。与既有计量链路**同构**（照着抄，别发明新形状）。 | `KbTopology` + `KbTopologyConfig`；用例断言 DLQ 深度/消息体。 |

---

## Task 1: V3 迁移 + KB 实体地基

**Files:**
- Create: `aihub-admin/aihub-dao/src/main/resources/db/migration/V3__kb_pipeline.sql`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/KbDocumentEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/entity/KbChunkEntity.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/KbDocumentMapper.java`
- Create: `aihub-admin/aihub-dao/src/main/java/com/aihub/dao/mapper/KbChunkMapper.java`
- Modify: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/dao/SchemaMigrationTest.java`（`hasSize(2)` → `hasSize(3)`、version 列表加 `"3"`、用例改名 `flywayAppliesExactlyThreeMigrations`）

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
- Modify: `aihub-admin/aihub-common/src/main/java/com/aihub/common/audit/AuditAction.java`（+`KB_DOCUMENT_UPLOAD`，D13）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbUploadIntegrationTest.java`

**Interfaces:**
- Consumes: `KbDocumentMapper.insertIfAbsent`（Task 1）、`AuditService.record(...)`、`ConsoleAuthFilter`（守门）、`QueryTenant` 语义（§10 R2）。
- Produces:
  - `KbFileStore.store(tenantId, sha256, InputStream) -> Path`（**临时名 + `ATOMIC_MOVE`**；拒绝 `..`；路径 `{root}/{tenantId}/{sha256}`）；
  - `KbDocumentService.upload(long tenantId, String filename, long sizeBytes, InputStream, Actor) -> UploadResult{KbDocumentEntity row, boolean created}`；
  - `POST /api/kb/documents`（multipart：`file` + 文本字段 `tenantId`）返回 `{id, status, chunkCount, duplicate}`；
  - `GET /api/kb/documents?tenantId=&status=&page=&size=`（**R3.1/R3.2 口径**：这是**资源列表**⇒ `tenantId` 缺省 = 令牌里的租户；`page/size` 按 M4 Task 11 的边界纪律：`size` 缺省 20 / 钳 200 / `<1` 400，`page` 缺省 0 / `<0` 400）。

- [ ] **Step 1: 写失败测试**

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

**验收判据：** 上传返回 `PENDING` 且原件落盘（内容逐字可比）；同内容二次上传返回**同一 `id`**；**截断上传不留行、不留文件**；缺 `tenantId`/未知扩展名 ⇒ 400；每次成功上传产生且仅产生一条**不含敏感字段**的 `KB_DOCUMENT_UPLOAD` 审计行。
**RED 证据：** `aTruncatedUploadLeavesNoRowAndNoFile` 在"先建行再落盘"的顺序下红（这正是官方的"中断上传不留脏数据"）。

---

## Task 3: MQ 拓扑 + 提交后发布 `kb.parse`

**Files:**
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbTopology.java`
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbMessageCodec.java`
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbTopologyConfig.java`（**照 `MeteringTopologyConfig` 的"死信三件套"**）
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentPublisher.java`（`publishParseAfterCommit(docId)`）
- Modify: `KbDocumentService.upload`（建行之后注册 `afterCommit` 发布）
- Test: `aihub-admin/aihub-mq/src/test/java/com/aihub/mq/kb/KbMessageCodecTest.java` + `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbPublishIntegrationTest.java`

**Interfaces:**
- Consumes: 既有 `RabbitTemplate`、`MeteringTopologyConfig`（**先读它，照抄形状**）、Spring 的 `TransactionSynchronizationManager`（先读既有 `ConfigChangePublisher.publishAfterCommit`）。
- Produces:
  - `KbTopology.PARSE_QUEUE = "aihub.kb.parse"`、`EMBED_QUEUE = "aihub.kb.embed"`、`DLQ = "aihub.kb.dlq"`、`DLX = "aihub.kb.dlx"`、`EXCHANGE = "aihub.kb"`；
  - `KbMessageCodec.parse(docId)` / `embed(docId, seqFrom, seqTo)` 的**线格式**：`parse:{docId}`、`embed:{docId}:{seqFrom}:{seqTo}`（文本分隔符，**无 JSON**，与 `MeteringEventCodec` 同风格）+ 解码 + **畸形载荷必须抛**（让它进 DLQ 而不是被静默丢弃）；
  - `KbTopology.embedBatches(docId, chunkCount, batchSize) -> List<KbEmbedBatch>`（纯函数：`ceil(N/batchSize)` 条，**最后一批可以不满**）。

- [ ] **Step 1: 写失败测试**（编解码的固定向量 + 一批一个队列的端到端发布）

```java
// KbMessageCodecTest（纯单测）
assertThat(KbMessageCodec.parse(7L)).isEqualTo("parse:7");
assertThat(KbMessageCodec.decodeParse("parse:7")).isEqualTo(7L);
assertThatThrownBy(() -> KbMessageCodec.decodeParse("embed:7:0:9")).as("错路由的消息必须响亮失败，才能进 DLQ").isInstanceOf(IllegalArgumentException.class);
assertThat(KbTopology.embedBatches(7L, 25, 10)).extracting(KbEmbedBatch::seqFrom).containsExactly(0, 10, 20);   // 25 段 ⇒ 3 批，末批 5 段
assertThat(KbTopology.embedBatches(7L, 0, 10)).as("0 段不该走到这里（Task 4 会先判 FAILED）").isEmpty();

// KbPublishIntegrationTest：上传后消息真的进了 kb.parse
var res = postMultipart("/api/kb/documents", TENANT, "a.md", BYTES);
assertThat(receiveFrom(KbTopology.PARSE_QUEUE, Duration.ofSeconds(10)))
        .as("提交后必须发出解析消息").isEqualTo(KbMessageCodec.parse(idOf(res)));
assertThat(activeRowsFor(TENANT)).isEqualTo(1);
```

- [ ] **Step 2: 跑它确认失败** → 队列不存在 / 收不到消息。
- [ ] **Step 3: 实现**（拓扑照抄计量链路；`publishAfterCommit` 用 `TransactionSynchronizationManager.registerSynchronization`，**提交后才发**）
- [ ] **Step 4: 跑测试确认通过**；- [ ] **Step 5: 提交**

```bash
git add aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/ \
        aihub-admin/aihub-mq/src/test/java/com/aihub/mq/kb/KbMessageCodecTest.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentPublisher.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbPublishIntegrationTest.java
git commit -m "feat(kb): declare the parse/embed queues with dead-lettering and publish after commit"
```

**验收判据：** `kb.parse`/`kb.embed`/DLQ 三件套被声明（含 `x-dead-letter-exchange`）；上传事务**提交后**消息才进队列；畸形载荷在编解码处**抛**（将来会进 DLQ，不允许静默丢）。
**RED 证据：** 把 `publishAfterCommit` 改成**提交前**发布 ⇒ `KbPublishIntegrationTest` 在"事务回滚也要看到没有消息"的对照用例上红（Task 8 补这条对照）。

---

## Task 4: consumer-parse（解析 → 切分 → 写 `kb_chunk` → 分批发 `kb.embed`）

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbChunker.java`（纯函数）
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbTextExtractor.java`（`md`/`txt`；Task 7 加 `pdf`）
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbParseConsumer.java`
- Modify: `aihub-admin/aihub-web/src/main/resources/application.yml`（`aihub.kb.chunk.size-chars=800`、`overlap-chars=100`）
- Test: `aihub-admin/aihub-service/src/test/java/com/aihub/service/kb/KbChunkerTest.java` + `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbParseIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 的拓扑与编解码、Task 1 的 mapper、`KbFileStore`（读原件）。
- Produces: `KbChunker.chunk(String text, int sizeChars, int overlapChars) -> List<String>`（**空/全空白 ⇒ 空列表**）；`KbTextExtractor.extract(String extension, Path file) -> String`；消费者把 `PENDING|PARSING` → `PARSING` → 写 chunk（`vector_id = docId:seq`，D15）→ `chunk_count=N` → `EMBEDDING` → 发 N 条 `kb.embed`。

- [ ] **Step 1: 写失败测试**

```java
// KbChunkerTest（纯单测，边界是重点）
assertThat(KbChunker.chunk("", 800, 100)).isEmpty();
assertThat(KbChunker.chunk("   \n\t ", 800, 100)).as("全空白 ⇒ 0 段（Task 5 会判 FAILED）").isEmpty();
assertThat(KbChunker.chunk("abc", 800, 100)).containsExactly("abc");
assertThat(KbChunker.chunk("x".repeat(2500), 800, 100)).hasSize(3);            // 800 + 700 + 700（带 100 重叠）
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
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbChunker.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbTextExtractor.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbParseConsumer.java \
        aihub-admin/aihub-web/src/main/resources/application.yml \
        aihub-admin/aihub-service/src/test/java/com/aihub/service/kb/KbChunkerTest.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbParseIntegrationTest.java
git commit -m "feat(kb): parse documents into tracked chunks and fan out embed batches"
```

**验收判据：** 状态走到 `EMBEDDING`；`kb_chunk` 行数 == `chunk_count`；批次条数 == `ceil(N/batchSize)`；重放不产生新 chunk；`READY`/`FAILED` 的消息被 ack 丢弃。
**RED 证据：** 把 `uk_kb_chunk_doc_seq` 换成普通索引（或改成 `insert` 不 upsert）⇒ `aReplayedParseMessageDoesNotDuplicateChunks` 红。

---

## Task 5: consumer-embed + Chroma（**第一次端到端绿**）

**Files:**
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbEmbeddingClient.java`
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbVectorStoreClient.java`（Chroma REST）
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbEmbedConsumer.java`
- Create: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/support/KbIntegrationTestBase.java`（**不加任何注解**，只加 Chroma 单例容器与助手 ⇒ 与既有测试**共用默认上下文**）
- Create: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/support/FakeEmbeddingUpstream.java`（JDK `HttpServer`）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbEmbedIntegrationTest.java`

**Interfaces:**
- Consumes: Task 4 的 chunk 行、`aihub.kb.embedding.base-url`、`aihub.kb.chroma.base-url`。
- Produces: `KbEmbeddingClient.embed(List<String> texts) -> List<float[]>`（**显式超时**）；`KbVectorStoreClient.upsert(String collection, List<VectorRecord>)`、`deleteByDocId(long docId)`、`countByDocId(long docId)`（**测试直接用它证明"真的写进去了"**）；`KbEmbedConsumer` 把 `kb_chunk.embedded_at` 打上，**全部非 NULL ⇒ `READY`**。

- [ ] **Step 0（**前置探测，可能 BLOCKED**）**：确认 Chroma 镜像可获取与**真实 REST 契约**
  1. `docker -H tcp://127.0.0.1:2375 images --filter reference=chromadb/*` —— 本地是否已有；
  2. 若没有，试 `docker -H tcp://127.0.0.1:2375 pull chromadb/chroma:<tag>`；
  3. 拿到镜像后**起一个临时容器**，用 `curl` 探清 `POST /api/v1/collections`、`upsert`、`delete`、`count` 的**真实路径与请求体**（**不许照记忆写代码**）。
  **拉不到镜像 ⇒ 报 BLOCKED 并附原始输出**（D3：不许换假实现）。

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
@Test void anEmbeddingTimeoutIsBoundedAndFailsFast() {
    upstream.neverRespond();
    long start = System.nanoTime();
    /* ... 走到 FAILED ... */
    assertThat(Duration.ofNanos(System.nanoTime() - start))
            .as("判据文字写的是「超时上限 %d 秒」，阈值必须与之一致", EMBEDDING_TIMEOUT_SECONDS)
            .isLessThan(Duration.ofSeconds(EMBEDDING_TIMEOUT_SECONDS + 5));
}
```

- [ ] **Step 2: 跑它确认失败**（Chroma 客户端未实现 / 状态停在 `EMBEDDING`）——**如实登记形态**。
- [ ] **Step 3: 实现**（Chroma 客户端按 Step 0 探到的真实契约写；embeddings 客户端带显式超时；`READY` 判定 = `count(embedded_at is not null) == chunk_count`）
- [ ] **Step 4: 跑测试确认通过**；- [ ] **Step 5: 提交**

```bash
git add aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbEmbeddingClient.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbVectorStoreClient.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbEmbedConsumer.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/support/KbIntegrationTestBase.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/support/FakeEmbeddingUpstream.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbEmbedIntegrationTest.java
git commit -m "feat(kb): embed chunks into Chroma and finish the pipeline at READY"
```

**验收判据：** 上传 md ⇒ `READY`；**直接查 Chroma**：chunk 数 == `chunk_count`、`seq` 连续、metadata 的 `tenant_id` 正确；重放不增；embeddings 超时**有界**且阈值与判据文字一致。
**RED 证据：** 把 upsert 改成 insert（非幂等）⇒ 重放用例红；把 `vector_id` 改成随机 UUID ⇒ `seqOfEveryRecord` 红（无法从 id 推出坐标）。

---

## Task 6: 失败路径与死信（**M5 的官方验收**：全成或全清 / 中断上传不留脏数据）

**Files:**
- Create: `aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbMessageRecoverer.java`（D7）
- Create: `aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentCleanup.java`（D8：`cleanup(docId)`，幂等可重入）
- Modify: `KbTopologyConfig`（把 `MessageRecoverer` 接到监听容器工厂；重试 3 次指数退避）
- Modify: `KbDocumentService`（+`markFailed(docId, reason)`，写 `KB_DOCUMENT_FAILED` 审计）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbFailureIntegrationTest.java`

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
git add aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbMessageRecoverer.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentCleanup.java \
        aihub-admin/aihub-mq/src/main/java/com/aihub/mq/kb/KbTopologyConfig.java \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbDocumentService.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbFailureIntegrationTest.java
git commit -m "feat(kb): roll back written chunks on terminal failure and route to the DLQ"
```

**验收判据：** 失败终态时 **Chroma 干净 + `kb_chunk` 干净 + 消息在 DLQ + 一条 `KB_DOCUMENT_FAILED` 审计**（四处**同时**断言，不许只断言其一）；0 段 ⇒ `FAILED("无可提取文本")`；`cleanup` 幂等；清理失败**如实登记**。
**RED 证据：** ① 删 `cleanup` ⇒ Chroma 残留 ⇒ 核心用例红；② 把置 `FAILED` 从 `MessageRecoverer` 挪进 `catch` ⇒ "重试期间状态自相矛盾"（第 2 次重试成功后状态已是 FAILED）⇒ 用例红；③ 把 `cleanup` 顺序改成"先删 `kb_chunk` 再删 Chroma"⇒ 清理失败用例红（坐标丢了、残留无法定位）。

---

## Task 7: PDF 支持（PDFBox）

**Files:**
- Modify: `aihub-admin/aihub-service/pom.xml`（+`org.apache.pdfbox:pdfbox`，**版本显式**）
- Modify: `KbTextExtractor`（+`pdf` 分支）
- Modify: `KbDocumentController`（扩展名白名单 +`pdf`）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbPdfIntegrationTest.java` + 夹具 `aihub-admin/aihub-web/src/test/resources/kb/sample-text.pdf`

**Interfaces:**
- Consumes: `KbTextExtractor` 的扩展点。
- Produces: `pdf` 走 PDFBox `PDFTextStripper`；**无文本层 ⇒ 返回空串 ⇒ Task 4 的 `chunkCount==0` 出口给 `FAILED("无可提取文本")`**（D14）。

- [ ] **Step 0：可解析性验证（可能 BLOCKED）**：`mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=org.apache.pdfbox:pdfbox:<版本>` ⇒ 必须看到 `Downloaded from aliyunmaven` + `BUILD SUCCESS`。失败 ⇒ **报 BLOCKED**，**绝不**自己写解析器。
- [ ] **Step 1: 写失败测试**：`pdf` 夹具（几 KB、有文本层）⇒ `READY` 且 chunk 数 > 0；把同一夹具的文本流删掉造"无文本层"⇒ `FAILED("无可提取文本")`。
- [ ] **Step 2～4**：红 → 实现 → 绿。
- [ ] **Step 5: 提交**

```bash
git add aihub-admin/aihub-service/pom.xml \
        aihub-admin/aihub-service/src/main/java/com/aihub/service/kb/KbTextExtractor.java \
        aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/console/KbDocumentController.java \
        aihub-admin/aihub-web/src/test/java/com/aihub/admin/kb/KbPdfIntegrationTest.java \
        aihub-admin/aihub-web/src/test/resources/kb/sample-text.pdf
git commit -m "feat(kb): extract text from PDFs with PDFBox and fail loudly on empty text layers"
```

**验收判据：** 文本层 PDF ⇒ `READY`；无文本层 ⇒ `FAILED("无可提取文本")`（**不是** `READY`）；`pom.xml` 只多这一条且版本显式。
**RED 证据：** 把 `chunkCount==0` 的判定去掉（默认 `READY`）⇒ "无文本层"用例红（**静默成功是最糟的失败**）。

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
