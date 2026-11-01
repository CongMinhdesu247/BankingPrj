-- V1 already contains the fraud tables. Extend it without rewriting applied history.
ALTER TABLE transactions ADD COLUMN initiated_by UUID REFERENCES users(id) ON DELETE RESTRICT;
-- Preserve actor evidence beyond the 7-day idempotency-key lifecycle; no monetary writes.
UPDATE transactions t SET initiated_by=k.actor_id FROM idempotency_keys k
    WHERE k.transaction_id=t.id AND k.operation='POST:/api/v1/transfers:v1';
UPDATE transactions t SET initiated_by=a.actor_id FROM audit_logs a
    WHERE t.initiated_by IS NULL AND a.action='TRANSFER_COMPLETED' AND a.outcome='SUCCESS'
      AND a.target_type='TRANSACTION' AND a.target_id=t.id::text AND a.actor_id IS NOT NULL;
ALTER TABLE fraud_analysis_runs ADD COLUMN rule_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(rule_snapshot) = 'array');
ALTER TABLE fraud_analysis_runs ADD COLUMN context_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(context_snapshot) = 'object');
CREATE INDEX idx_transactions_fraud_recovery ON transactions(created_at,id)
    WHERE type='INTERNAL_TRANSFER' AND status='COMPLETED';
-- No demo records or default rule rows: defaults are versioned application configuration.
