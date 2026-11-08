CREATE TABLE accounts (
    id                    BINARY(16)                     NOT NULL,
    currency              CHAR(3)                        NOT NULL,
    kind                  ENUM ('REGULAR', 'SETTLEMENT') NOT NULL,
    overdraft_limit_minor BIGINT                         NULL,
    balance_minor         BIGINT                         NOT NULL DEFAULT 0,
    opened_at             TIMESTAMP(6)                   NOT NULL,
    settlement_currency   CHAR(3) GENERATED ALWAYS AS (IF(kind = 'SETTLEMENT', currency, NULL)) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_accounts_settlement_currency (settlement_currency),
    CONSTRAINT chk_accounts_limit CHECK (overdraft_limit_minor IS NULL OR overdraft_limit_minor >= 0),
    CONSTRAINT chk_accounts_settlement_unlimited CHECK (kind <> 'SETTLEMENT' OR overdraft_limit_minor IS NULL)
);

CREATE TABLE transactions (
    id          BINARY(16)   NOT NULL,
    currency    CHAR(3)      NOT NULL,
    description VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    reverses_id BINARY(16)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_transactions_reverses (reverses_id),
    CONSTRAINT fk_transactions_reverses FOREIGN KEY (reverses_id) REFERENCES transactions (id)
);

CREATE TABLE entries (
    id                  BIGINT                   NOT NULL AUTO_INCREMENT,
    transaction_id      BINARY(16)               NOT NULL,
    position            SMALLINT                 NOT NULL,
    account_id          BINARY(16)               NOT NULL,
    direction           ENUM ('DEBIT', 'CREDIT') NOT NULL,
    amount_minor        BIGINT                   NOT NULL,
    balance_after_minor BIGINT                   NOT NULL,
    recorded_at         TIMESTAMP(6)             NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_entries_transaction_position (transaction_id, position),
    KEY ix_entries_account_id (account_id, id),
    KEY ix_entries_account_recorded (account_id, recorded_at, id),
    CONSTRAINT fk_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT chk_entries_amount CHECK (amount_minor > 0)
);

CREATE TABLE idempotency_keys (
    scope               VARCHAR(64)  NOT NULL,
    idem_key            VARCHAR(255) NOT NULL,
    request_fingerprint BINARY(32)   NOT NULL,
    response_status     SMALLINT     NULL,
    response_body       JSON         NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    expires_at          TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (scope, idem_key),
    KEY ix_idempotency_expires (expires_at)
);

CREATE TABLE processed_payments (
    payment_id     VARCHAR(128) NOT NULL,
    event_id       VARCHAR(128) NOT NULL,
    transaction_id BINARY(16)   NOT NULL,
    processed_at   TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (payment_id),
    CONSTRAINT fk_processed_payments_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id)
);

CREATE TABLE outbox_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    event_id      BINARY(16)   NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    aggregate_id  BINARY(16)   NOT NULL,
    partition_key VARCHAR(64)  NOT NULL,
    payload       JSON         NOT NULL,
    headers       JSON         NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    published_at  TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_outbox_event_id (event_id),
    KEY ix_outbox_unpublished (published_at, id)
);

CREATE TABLE relay_lease (
    name       VARCHAR(64)  NOT NULL,
    owner      VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (name)
);
