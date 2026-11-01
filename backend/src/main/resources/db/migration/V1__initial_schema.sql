-- Initial schema captured after the manual database framework was created.
-- All 20 existing tables belong to V1; future changes use V2 and later.
-- Do not edit this migration after it has been applied.

-- Original roadmap group: Customer
CREATE TABLE customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_code VARCHAR(30) NOT NULL UNIQUE,
    full_name VARCHAR(150) NOT NULL CHECK (btrim(full_name) <> ''),
    email VARCHAR(254) UNIQUE,
    phone VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (btrim(customer_code) <> ''),
    CHECK (email IS NULL OR (email = lower(btrim(email)) AND email <> ''))
);
CREATE INDEX idx_customers_page ON customers (created_at, id);

-- Original roadmap group: Account. Account number la chuoi, khong phai UUID noi bo.
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    account_number VARCHAR(30) NOT NULL UNIQUE CHECK (btrim(account_number) <> ''),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    balance NUMERIC(19,2) NOT NULL DEFAULT 0
        CHECK (balance BETWEEN 0 AND 99999999999999999 AND balance = trunc(balance)),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (status <> 'CLOSED' OR balance = 0)
);
CREATE INDEX idx_accounts_customer_page ON accounts (customer_id, created_at, id);

-- Original roadmap group: Transfer + ledger. OPENING la phan bo tu treasury sang account demo;
-- von mo so cua treasury phai duoc ghi rieng trong fixture dev/test.
CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference VARCHAR(50) NOT NULL UNIQUE CHECK (btrim(reference) <> ''),
    source_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    destination_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    amount NUMERIC(19,2) NOT NULL
        CHECK (amount BETWEEN 1 AND 99999999999999999 AND amount = trunc(amount)),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    type VARCHAR(30) NOT NULL DEFAULT 'INTERNAL_TRANSFER'
        CHECK (type IN ('INTERNAL_TRANSFER', 'OPENING')),
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED' CHECK (status = 'COMPLETED'),
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (source_account_id <> destination_account_id)
);
CREATE INDEX idx_transactions_source_page ON transactions (source_account_id, created_at, id);
CREATE INDEX idx_transactions_destination_page ON transactions (destination_account_id, created_at, id);

CREATE TABLE transaction_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19,2) NOT NULL
        CHECK (amount BETWEEN 1 AND 99999999999999999 AND amount = trunc(amount)),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    balance_after NUMERIC(19,2) NOT NULL
        CHECK (balance_after BETWEEN 0 AND 99999999999999999
               AND balance_after = trunc(balance_after)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (transaction_id, direction)
);
CREATE INDEX idx_entries_account_page ON transaction_entries (account_id, created_at, id);
-- UNIQUE chi chan entry lap. Service phai ghi DU hai entry, dung account,
-- cung amount/currency voi transaction, cap nhat balance trong cung transaction DB.
-- Khong cung cap API UPDATE/DELETE ledger; script chua cau hinh DB role/GRANT.

-- Original roadmap group: Authentication + RBAC
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID UNIQUE REFERENCES customers(id) ON DELETE RESTRICT,
    username VARCHAR(100) NOT NULL UNIQUE
        CHECK (username = lower(btrim(username)) AND username <> ''),
    password_hash VARCHAR(255) NOT NULL CHECK (btrim(password_hash) <> ''),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(30) NOT NULL UNIQUE,
    description VARCHAR(255),
    CHECK (code IN ('CUSTOMER', 'BANK_STAFF', 'RISK_OFFICER', 'ADMIN'))
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_role ON user_roles (role_id);
INSERT INTO roles (code, description) VALUES
    ('CUSTOMER', 'Khach hang'),
    ('BANK_STAFF', 'Nhan vien ngan hang'),
    ('RISK_OFFICER', 'Can bo quan ly rui ro'),
    ('ADMIN', 'Quan tri he thong');
-- Service phai bat buoc user co role CUSTOMER lien ket dung customer.

-- Original roadmap group: Limits + beneficiary + audit + idempotency
CREATE TABLE transfer_limits (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE RESTRICT,
    per_transfer_amount NUMERIC(19,2) NOT NULL
        CHECK (per_transfer_amount BETWEEN 1 AND 99999999999999999
               AND per_transfer_amount = trunc(per_transfer_amount)),
    daily_amount NUMERIC(19,2) NOT NULL
        CHECK (daily_amount BETWEEN 1 AND 99999999999999999 AND daily_amount = trunc(daily_amount)),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    updated_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (per_transfer_amount <= daily_amount)
);
-- Service quyet dinh default/tu choi neu account chua co limit.
-- Tinh tong ngay tu transactions sau khi khoa source; ngay theo Asia/Ho_Chi_Minh.

CREATE TABLE beneficiaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    destination_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    nickname VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);
-- Soft delete giu timestamp de phan tich fraud theo thoi diem giao dich.
CREATE UNIQUE INDEX uq_beneficiaries_active_pair
    ON beneficiaries (owner_customer_id, destination_account_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_beneficiaries_destination ON beneficiaries (destination_account_id);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(100),
    outcome VARCHAR(10) NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    request_id VARCHAR(100),
    details JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_actor_page ON audit_logs (actor_id, created_at, id);
CREATE INDEX idx_audit_target_page ON audit_logs (target_type, target_id, created_at, id);
CREATE INDEX idx_audit_page ON audit_logs (created_at, id);

CREATE TABLE login_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    username_fingerprint VARCHAR(128) NOT NULL,
    success BOOLEAN NOT NULL,
    failure_reason VARCHAR(100),
    request_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (NOT success OR user_id IS NOT NULL)
);
CREATE INDEX idx_login_user_time ON login_history (user_id, created_at, id);
CREATE INDEX idx_login_fingerprint_time ON login_history (username_fingerprint, created_at, id);
-- Fingerprint dung HMAC o backend; khong ghi password/token/raw login payload.

CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    operation VARCHAR(100) NOT NULL,
    key VARCHAR(128) NOT NULL CHECK (btrim(key) <> ''),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    transaction_id UUID REFERENCES transactions(id) ON DELETE RESTRICT,
    response_status SMALLINT,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '7 days'),
    UNIQUE (actor_id, operation, key),
    CHECK (expires_at > created_at),
    CHECK (
        (transaction_id IS NULL AND response_status IS NULL AND response_body IS NULL)
        OR (transaction_id IS NOT NULL AND response_status IS NOT NULL
            AND response_status BETWEEN 200 AND 299 AND response_body IS NOT NULL)
    )
);
CREATE INDEX idx_idempotency_expiry ON idempotency_keys (expires_at);
CREATE INDEX idx_idempotency_transaction ON idempotency_keys (transaction_id);
-- Claim chua co response chi ton tai ben trong transaction chua commit.
-- Backend phai hoan tat snapshot truoc commit; failure rollback ca claim.

-- Original roadmap group: Fraud hau kiem
CREATE TABLE fraud_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    threshold JSONB NOT NULL CHECK (jsonb_typeof(threshold) = 'object'),
    weight INTEGER NOT NULL CHECK (weight BETWEEN 0 AND 100),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- threshold JSONB vi amount/count/window khac nhau; DTO backend validate theo code.

CREATE TABLE fraud_analysis_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    analysis_version INTEGER NOT NULL CHECK (analysis_version > 0),
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (transaction_id, analysis_version)
);

CREATE TABLE fraud_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    analysis_version INTEGER NOT NULL,
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    risk_level VARCHAR(10) NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'IN_REVIEW', 'CONFIRMED_FRAUD', 'FALSE_POSITIVE')),
    assigned_to UUID REFERENCES users(id) ON DELETE RESTRICT,
    notes TEXT,
    resolved_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (transaction_id, analysis_version),
    FOREIGN KEY (transaction_id, analysis_version)
        REFERENCES fraud_analysis_runs(transaction_id, analysis_version) ON DELETE RESTRICT,
    CHECK (
        (score BETWEEN 0 AND 29 AND risk_level = 'LOW') OR
        (score BETWEEN 30 AND 59 AND risk_level = 'MEDIUM') OR
        (score BETWEEN 60 AND 79 AND risk_level = 'HIGH') OR
        (score BETWEEN 80 AND 100 AND risk_level = 'CRITICAL')
    ),
    CHECK (
        (status IN ('OPEN', 'IN_REVIEW') AND resolved_at IS NULL) OR
        (status IN ('CONFIRMED_FRAUD', 'FALSE_POSITIVE')
            AND resolved_at IS NOT NULL AND notes IS NOT NULL AND btrim(notes) <> '')
    )
);
CREATE INDEX idx_alerts_status_level_page ON fraud_alerts (status, risk_level, created_at, id);
CREATE INDEX idx_alerts_assignee_page ON fraud_alerts (assigned_to, created_at, id);

CREATE TABLE fraud_alert_rule_matches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_id UUID NOT NULL REFERENCES fraud_alerts(id) ON DELETE RESTRICT,
    rule_code VARCHAR(50) NOT NULL,
    rule_version INTEGER NOT NULL CHECK (rule_version > 0),
    score_contribution INTEGER NOT NULL CHECK (score_contribution BETWEEN 0 AND 100),
    observed_value JSONB NOT NULL,
    threshold_snapshot JSONB NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (alert_id, rule_code, rule_version)
);
-- Snapshot khong FK vao rule hien tai: thay cau hinh khong thay bang chung cu.
-- Service ghi run + alert + matches cung transaction, ke ca run khong co alert.

-- Original roadmap group: Kafka consumer deduplication + in-app notification + failure attempts
CREATE TABLE processed_events (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    event_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    content JSONB NOT NULL CHECK (jsonb_typeof(content) = 'object'),
    status VARCHAR(10) NOT NULL DEFAULT 'UNREAD' CHECK (status IN ('UNREAD', 'READ')),
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (recipient_id, event_id, type),
    CHECK ((status = 'UNREAD' AND read_at IS NULL) OR (status = 'READ' AND read_at IS NOT NULL))
);
CREATE INDEX idx_notifications_recipient_page ON notifications (recipient_id, created_at, id);

CREATE TABLE transfer_attempts (
    attempt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    transaction_id UUID REFERENCES transactions(id) ON DELETE RESTRICT,
    request_id VARCHAR(100),
    correlation_id VARCHAR(100),
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('SUCCESS', 'BUSINESS_FAILURE', 'SYSTEM_FAILURE')),
    reason_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((outcome = 'SUCCESS' AND transaction_id IS NOT NULL)
        OR (outcome IN ('BUSINESS_FAILURE', 'SYSTEM_FAILURE')
            AND transaction_id IS NULL AND reason_code IS NOT NULL))
);
CREATE INDEX idx_attempts_actor_page ON transfer_attempts (actor_id, created_at, attempt_id);
CREATE INDEX idx_attempts_transaction ON transfer_attempts (transaction_id);

-- Original roadmap group: Transactional Outbox. id dong thoi la eventId, giu nguyen khi retry.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    correlation_id VARCHAR(100),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until TIMESTAMPTZ,
    claim_token UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    CHECK (
        (status = 'PROCESSING' AND locked_until IS NOT NULL AND claim_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND locked_until IS NULL AND claim_token IS NULL)
    ),
    CHECK ((status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL))
);
CREATE INDEX idx_outbox_pending ON outbox_events (status, next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_outbox_expired_lease ON outbox_events (locked_until, id)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_outbox_published_cleanup ON outbox_events (published_at, id)
    WHERE status = 'PUBLISHED';
-- event_id trong processed_events/notifications khong FK vao outbox:
-- event co the den tu broker va outbox da publish co the duoc cleanup.

