-- Data Migration Ferry — embedded store. Every statement is additive (IF NOT EXISTS),
-- so starting the server never alters or drops existing data.

-- Every action taken in any area: one row per run.
CREATE TABLE IF NOT EXISTS ferry_run (
    run_id        VARCHAR(40) PRIMARY KEY,
    area          VARCHAR(30)  NOT NULL,
    action        VARCHAR(40)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    batch_id      VARCHAR(40),
    environment   VARCHAR(60),
    performed_by  VARCHAR(100),
    started_at    TIMESTAMP    NOT NULL,
    finished_at   TIMESTAMP,
    total_items   INT DEFAULT 0,
    succeeded     INT DEFAULT 0,
    failed        INT DEFAULT 0,
    details       CLOB,
    error_message VARCHAR(2000)
);

-- Source ID -> target ID, per environment. Later steps read IDs from here, and a rerun
-- skips anything already created, so nothing is created twice.
CREATE TABLE IF NOT EXISTS id_register (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    environment  VARCHAR(60)  NOT NULL,
    source_type  VARCHAR(40)  NOT NULL,
    source_ref   VARCHAR(200) NOT NULL,
    target_type  VARCHAR(40),
    target_id    VARCHAR(200) NOT NULL,
    batch_id     VARCHAR(40),
    run_id       VARCHAR(40),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_id_register UNIQUE (environment, source_type, source_ref)
);

-- Who changed what, and when (settings, assumptions, runs).
CREATE TABLE IF NOT EXISTS audit_event (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurred_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    actor        VARCHAR(100),
    action       VARCHAR(60)  NOT NULL,
    subject      VARCHAR(200),
    details      CLOB
);

-- Boarding: a batch is one set of source files that sails together.
CREATE TABLE IF NOT EXISTS ferry_batch (
    batch_id       VARCHAR(40) PRIMARY KEY,
    name           VARCHAR(200),
    source_system  VARCHAR(80),
    status         VARCHAR(20) NOT NULL,
    created_by     VARCHAR(100),
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ferry_file (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id          VARCHAR(40)  NOT NULL,
    record_type       VARCHAR(40)  NOT NULL,
    file_name         VARCHAR(300),
    sheet_name        VARCHAR(200),
    profile_id        VARCHAR(100),
    rows_read         INT,
    resolved_columns  CLOB,
    unmapped_columns  CLOB,
    uploaded_by       VARCHAR(100),
    uploaded_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ferry_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      VARCHAR(40)  NOT NULL,
    record_type   VARCHAR(40)  NOT NULL,
    row_num       INT          NOT NULL,
    record_key    VARCHAR(300),
    contract_ref  VARCHAR(200),
    status        VARCHAR(20)  NOT NULL,
    tags          VARCHAR(400),
    record_data   CLOB,
    raw_data      CLOB
);
CREATE INDEX IF NOT EXISTS ix_ferry_record_batch ON ferry_record (batch_id, record_type);

CREATE TABLE IF NOT EXISTS ferry_finding (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id       VARCHAR(40)  NOT NULL,
    record_type    VARCHAR(40),
    row_num        INT,
    record_key     VARCHAR(300),
    rule_id        VARCHAR(80)  NOT NULL,
    severity       VARCHAR(10)  NOT NULL,
    field          VARCHAR(100),
    message        VARCHAR(1000),
    assumption_id  VARCHAR(20)
);
CREATE INDEX IF NOT EXISTS ix_ferry_finding_batch ON ferry_finding (batch_id);

-- The dry-run plan per contract ("voyage"), read later by Crossing.
CREATE TABLE IF NOT EXISTS ferry_plan (
    batch_id      VARCHAR(40)  NOT NULL,
    contract_ref  VARCHAR(200) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    plan_json     CLOB,
    planned_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (batch_id, contract_ref)
);
