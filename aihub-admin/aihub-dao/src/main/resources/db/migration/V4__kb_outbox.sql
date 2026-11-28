ALTER TABLE kb_document
    ADD COLUMN ingest_generation BIGINT NOT NULL DEFAULT 0;

CREATE TABLE kb_outbox (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id       BIGINT       NOT NULL,
    generation   BIGINT       NOT NULL,
    routing_key  VARCHAR(128) NOT NULL,
    payload      VARCHAR(128) NOT NULL,
    failures     INT          NOT NULL DEFAULT 0,
    available_at DATETIME(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    lease_token  VARCHAR(36)  NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_outbox_doc_generation_payload (doc_id, generation, payload),
    KEY idx_kb_outbox_available (available_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = ascii COLLATE = ascii_bin;
