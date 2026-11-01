-- V1 already owns processed_events, notifications and transfer_attempts.
ALTER TABLE processed_events ADD COLUMN event_type VARCHAR(50);
ALTER TABLE processed_events ADD COLUMN payload_hash VARCHAR(64)
    CHECK (payload_hash IS NULL OR payload_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE transfer_attempts ADD COLUMN source_account_id UUID;
CREATE UNIQUE INDEX uq_attempts_success_transaction ON transfer_attempts(transaction_id) WHERE outcome='SUCCESS';
CREATE INDEX idx_notifications_unread ON notifications(recipient_id,created_at,id) WHERE status='UNREAD';
-- No outbox implementation or demo rows in Phase 10.
