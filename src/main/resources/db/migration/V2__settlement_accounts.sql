-- One settlement account per supported currency, with a fixed id ending in the
-- currency's ISO 4217 numeric code (EUR 978, USD 840, JPY 392).
INSERT INTO accounts (id, currency, kind, overdraft_limit_minor, balance_minor, opened_at)
VALUES (UUID_TO_BIN('00000000-0000-7000-8000-000000000978'), 'EUR', 'SETTLEMENT', NULL, 0, CURRENT_TIMESTAMP(6)),
       (UUID_TO_BIN('00000000-0000-7000-8000-000000000840'), 'USD', 'SETTLEMENT', NULL, 0, CURRENT_TIMESTAMP(6)),
       (UUID_TO_BIN('00000000-0000-7000-8000-000000000392'), 'JPY', 'SETTLEMENT', NULL, 0, CURRENT_TIMESTAMP(6));
