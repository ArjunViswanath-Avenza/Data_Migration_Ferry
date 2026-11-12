-- SAMPLE for Dry Dock: the schema of a new product release, written as a base script followed by an
-- upgrade script, the way vendors often ship them. All names are invented.
-- Product changes made here: email renamed to email_address, a preferred language, a KYC level,
-- a consent table, a dormant account status, a wider balance, a renamed audit table, a status index.

CREATE DATABASE IF NOT EXISTS bank_next DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE bank_next;

CREATE TABLE customer (
  id INT NOT NULL AUTO_INCREMENT,
  customer_id VARCHAR(50) NOT NULL,
  first_name VARCHAR(50) NOT NULL,
  last_name VARCHAR(50) NOT NULL,
  email_address VARCHAR(100) NULL,
  phone VARCHAR(20) NOT NULL,
  status_id VARCHAR(50) NOT NULL,
  created_ts TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_customer_id (customer_id),
  KEY idx_customer_status (status_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE account (
  account_id VARCHAR(20) NOT NULL,
  customer_ref INT NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'EUR',
  balance DECIMAL(20,4) NOT NULL DEFAULT 0,
  overdraft_limit DECIMAL(18,2) NULL,
  status ENUM('OPEN','CLOSED','DORMANT') NOT NULL DEFAULT 'OPEN',
  notes VARCHAR(500) NULL,
  PRIMARY KEY (account_id),
  KEY ix_account_customer (customer_ref),
  CONSTRAINT account_ibfk_1 FOREIGN KEY (customer_ref) REFERENCES customer (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE customer_audit_log (
  log_id BIGINT NOT NULL AUTO_INCREMENT,
  customer_ref INT NOT NULL,
  action VARCHAR(30) NOT NULL,
  changed_by VARCHAR(50) NULL,
  changed_ts DATETIME NOT NULL,
  PRIMARY KEY (log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE consent_record (
  consent_id BIGINT NOT NULL AUTO_INCREMENT,
  customer_ref INT NOT NULL,
  consent_type VARCHAR(40) NOT NULL,
  granted_ts DATETIME NOT NULL,
  PRIMARY KEY (consent_id),
  CONSTRAINT fk_consent_customer FOREIGN KEY (customer_ref) REFERENCES customer (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE OR REPLACE VIEW v_active_customer AS
  SELECT customer.id AS id, customer.customer_id AS customer_id FROM customer WHERE customer.status_id = 'ACTIVE';

-- ---------------------------------------------------------------- upgrade script 2.1
ALTER TABLE customer
  ADD COLUMN preferred_language VARCHAR(10) NOT NULL DEFAULT 'en' AFTER phone,
  ADD COLUMN kyc_level TINYINT NOT NULL AFTER status_id;
