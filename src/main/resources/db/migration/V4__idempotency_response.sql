ALTER TABLE idempotency_keys
    MODIFY COLUMN response_body MEDIUMTEXT NULL,
    ADD COLUMN response_location VARCHAR(512) NULL AFTER response_body;
