-- Keep the announced bytes exactly as serialized: a JSON column normalises key order
-- and whitespace, so consumers would not see what the ledger wrote.
ALTER TABLE outbox_events MODIFY COLUMN payload MEDIUMTEXT NOT NULL;
