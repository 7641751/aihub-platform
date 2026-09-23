-- aihub-platform 初始表结构
-- 约定：字符集 utf8mb4；时间统一 datetime(3)，按 UTC 存储

CREATE TABLE tenant (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(72)  NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'ADMIN',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE api_key (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    key_id       VARCHAR(32)  NOT NULL,
    tenant_id    BIGINT       NOT NULL,
    key_hash     CHAR(64)     NOT NULL,
    name         VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    expire_at    DATETIME(3)  NULL,
    last_used_at DATETIME(3)  NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_key_key_id (key_id),
    UNIQUE KEY uk_api_key_key_hash (key_hash),
    KEY idx_api_key_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE channel (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    name           VARCHAR(128)  NOT NULL,
    provider       VARCHAR(32)   NOT NULL,
    base_url       VARCHAR(255)  NOT NULL,
    api_key_cipher VARCHAR(1024) NOT NULL,
    key_version    INT           NOT NULL DEFAULT 1,
    models_json    JSON          NULL,
    weight         INT           NOT NULL DEFAULT 100,
    priority       INT           NOT NULL DEFAULT 0,
    timeout_ms     INT           NOT NULL DEFAULT 60000,
    status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE model_route (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    model_name VARCHAR(128) NOT NULL,
    channel_id BIGINT       NOT NULL,
    weight     INT          NOT NULL DEFAULT 100,
    priority   INT          NOT NULL DEFAULT 0,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_route (model_name, channel_id),
    KEY idx_model_route_model (model_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE quota (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    period        VARCHAR(8)  NOT NULL,
    token_limit   BIGINT      NOT NULL DEFAULT 0,
    token_used    BIGINT      NOT NULL DEFAULT 0,
    request_limit BIGINT      NOT NULL DEFAULT 0,
    request_used  BIGINT      NOT NULL DEFAULT 0,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_quota_tenant_period (tenant_id, period)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE rate_limit_policy (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    api_key_id BIGINT      NULL,
    qps        INT         NOT NULL DEFAULT 10,
    burst      INT         NOT NULL DEFAULT 20,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_rate_limit_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- request_log 按月分区。
-- 注意：MySQL 要求分区列必须出现在表的每一个唯一索引中，因此主键与 request_id
-- 唯一键都带上了 created_at。结果是「同一个 request_id 且同一个 created_at」才构成
-- 幂等冲突 —— 这正好是计量事件重投时的形态。
CREATE TABLE request_log (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    request_id        VARCHAR(64)  NOT NULL,
    tenant_id         BIGINT       NOT NULL,
    api_key_id        BIGINT       NULL,
    channel_id        BIGINT       NULL,
    model             VARCHAR(128) NULL,
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    latency_ms        INT          NOT NULL DEFAULT 0,
    ttft_ms           INT          NULL,
    status            VARCHAR(16)  NOT NULL,
    error_code        VARCHAR(64)  NULL,
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id, created_at),
    UNIQUE KEY uk_request_log_request_id (request_id, created_at),
    KEY idx_request_log_tenant_created (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
PARTITION BY RANGE COLUMNS (created_at) (
    PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
    PARTITION p202610 VALUES LESS THAN ('2026-11-01'),
    PARTITION p202611 VALUES LESS THAN ('2026-12-01'),
    PARTITION pmax    VALUES LESS THAN (MAXVALUE)
);

CREATE TABLE kb_document (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT        NOT NULL,
    filename    VARCHAR(255)  NOT NULL,
    size_bytes  BIGINT        NOT NULL DEFAULT 0,
    sha256      CHAR(64)      NOT NULL,
    status      VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    chunk_count INT           NOT NULL DEFAULT 0,
    error_msg   VARCHAR(1024) NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_document_tenant_sha (tenant_id, sha256),
    KEY idx_kb_document_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE billing_daily (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT         NOT NULL,
    stat_date  DATE           NOT NULL,
    requests   BIGINT         NOT NULL DEFAULT 0,
    tokens     BIGINT         NOT NULL DEFAULT 0,
    cost       DECIMAL(18, 6) NOT NULL DEFAULT 0,
    created_at DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_daily (tenant_id, stat_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
