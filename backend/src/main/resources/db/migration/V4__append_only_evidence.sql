-- Runtime DML must not rewrite evidence. Migration/DB owners still require
-- separate credentials and off-host backups: an owner can disable triggers.
CREATE FUNCTION reject_evidence_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Evidence is append-only' USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER audit_logs_append_only BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER login_history_append_only BEFORE UPDATE OR DELETE ON login_history
    FOR EACH STATEMENT EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER transactions_append_only BEFORE UPDATE OR DELETE ON transactions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER transaction_entries_append_only BEFORE UPDATE OR DELETE ON transaction_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_evidence_mutation();
