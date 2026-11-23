-- 1. Ledger is append-only
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only (% not allowed)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

-- 2. Every transaction must sum to zero, checked at COMMIT time
CREATE FUNCTION check_txn_balanced() RETURNS trigger AS $$
DECLARE
total bigint;
BEGIN
SELECT COALESCE(SUM(amount), 0) INTO total
FROM ledger_entries WHERE txn_id = NEW.txn_id;

IF total <> 0 THEN
        RAISE EXCEPTION 'Transaction % is unbalanced (sum = %)', NEW.txn_id, total;
END IF;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_txn_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_txn_balanced();