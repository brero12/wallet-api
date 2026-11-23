ALTER TABLE fraud_flags ADD COLUMN event_id text;

-- the same event can be delivered twice (at-least-once), but may only flag once per rule
CREATE UNIQUE INDEX uq_fraud_flags_event_rule ON fraud_flags (event_id, rule);