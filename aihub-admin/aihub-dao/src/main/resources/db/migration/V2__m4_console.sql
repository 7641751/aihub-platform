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
