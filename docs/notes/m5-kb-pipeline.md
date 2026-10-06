# M5 技术笔记：文档入库异步流水线

> 素材：计划 `docs/superpowers/plans/2026-10-02-m5-kb-pipeline.md`；验收 `.superpowers/sdd/m5-acceptance.md`；
> 契约 `docs/CONVENTIONS.md` **§6.8**。tag：**`m5`** → `b167b3b`。

## 目标

只做知识库的**写入侧**（检索侧属另一个 Python 仓库）：上传 → 解析 → 切分 → 嵌入 → 向量库，全程异步，
**要么全成、要么库里干净**；失败进死信；**中断上传不留脏数据**（设计 §12 对 M5 的验收原话）。

## 做了什么（Task 1–8）

上传（multipart，**原子落盘**：临时文件 + 改名，边读边算 sha256；同内容幂等去重 + 审计）→
**事务提交后**发 `aihub.kb.parse` → 解析（`md`/`txt`/**PDF 文本层**，PDFBox）→ 滑窗切分（默认 800/100，无缝隙）→
`kb_chunk`（`uk(doc_id, seq)`，upsert 幂等）→ 分批 `aihub.kb.embed` → 调 embeddings 上游 → 写 **真 Chroma**
（`vector_id="{docId}:{seq}"`，metadata `doc_id`/`tenant_id`/`seq`）→ 逐段 `embedded_at` ⇒ **全部打满 ⇒ `READY`**；
任一阶段重试耗尽 ⇒ **清 Chroma → 删 `kb_chunk` → `FAILED` + 审计**，消息进 `aihub.kb.dlq`。

## 实测数字

- **A 档（真实 compose，离线可复现）**：`md`（2777 字符）⇒ 切 4 段；真实 **PDF 813,280 字节** ⇒ 切 9 段；
  两者终态 `FAILED`（离线无 embeddings 上游）+ **全清**（`kb_chunk` 0 行、Chroma count 0）+ DLQ 有消息 + 审计两行；
  **中断上传（发 539 字节却声明 5 MB 后断开）⇒ `EOFException`，行数不变、盘上不多文件** ✓
- **B 档（接真上游 DashScope，原生鉴权）**：同一份 PDF ⇒ **`t=5s READY`（9 段）**，
  Chroma 直查 `count=9`、`ids=3:0..3:8`、`metadata tenant_id=3` ✓
- **回归**：`aihub-common` 68 / `aihub-web` 310 / `aihub-gateway` 391，**`Tomcat started on port` = 7**（上下文预算守住）。

## 踩坑与裁定

1. **"不许新增第 8 个 Spring 上下文" ≠ "不许写 `@TestPropertySource`"**：`aihub.console.secret` 刻意无默认值（D16）
   ⇒ 集成测试**必须**注入它。正确规则是**属性与既有测试类逐字相同、且不加 `@Import`**（`@Import` 会进缓存键 ⇒ 必然 fork）。
   判据：全量后数 `Tomcat started on port` **仍是 7**。
2. **发布端丢消息是"无补救手段的死状态"**（我的第一版裁决）：`FAILED` 行**无法**被重放消息推进（带条件迁移会 ack 丢弃）
   ⇒ 后来**放宽迁移为 `PENDING|PARSING|FAILED` + 重入清 `error_msg`**，让"重传同一份内容"对所有非 `READY` 状态都成立。
3. **接真上游必须支持鉴权**：客户端原本**一个鉴权头都不发** ⇒ 只能靠一次性反代；补 `aihub.kb.embedding.api-key`
   （**非空才加** `Authorization`，空值行为与加特性前逐字相同）后即可直接填 env。
4. **跨语言契约要写死在文档里**（§6.8）：记录 id 可反推、metadata 必填、**`count` 是 GET**、`upsert` 幂等、
   JDK HttpClient **必须钉 HTTP/1.1**（否则明文 http 的 h2c 前奏会让上游 422）。
5. **部署面**：`docker compose up --build` 可能因**构建容器解析不了 DNS** 失败（新增 Maven 依赖不在缓存里）⇒
   用 `docker build --network=host` + `up --no-build`；**改完代码必须重建镜像**，否则验收验的是旧行为。
