-- M5：文档入库流水线的**逐段进度表**。
--
-- 定位（决策 D1/D4）：原件是**唯一真相源**，向量库是**可重建的派生数据**；这张表存的是
-- 「哪些段已经真的写进向量库」的**持久**坐标。READY 的判据不是 kb_document.chunk_count
-- （那是**期望值**），而是「本 doc 的**所有** kb_chunk 行都已 embedded_at 非空」；失败清理
-- 也只需按 doc_id 精确删除。把进度放 Redis 会在重启后卡在 EMBEDDING 永不完成——
-- 所以进度必须持久。
--
-- 唯一键 uk_kb_chunk_doc_seq 让「重放同一段」变成覆盖（幂等，不产生重复段）；
-- KEY idx_kb_chunk_doc 让「按 doc 清理 / 按 doc 取全部段」走前缀。
--
-- 残余（诚实登记）：text 存了一份 chunk 文本（MEDIUMTEXT），与原件重复占空间；
-- 换来的是**可重放、可直接查、清理只需一条 SQL**。
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
