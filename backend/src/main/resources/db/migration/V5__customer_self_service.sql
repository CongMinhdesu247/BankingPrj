CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ, device_label VARCHAR(200) NOT NULL DEFAULT 'Unknown device',
    CHECK (expires_at>created_at)
);
CREATE INDEX idx_auth_sessions_user ON auth_sessions(user_id,expires_at);
CREATE TABLE user_security (
    user_id UUID PRIMARY KEY REFERENCES users(id), totp_enabled BOOLEAN NOT NULL DEFAULT false,
    totp_secret TEXT, pending_secret TEXT, pending_expires_at TIMESTAMPTZ, last_totp_step BIGINT NOT NULL DEFAULT -1,
    CHECK (NOT totp_enabled OR totp_secret IS NOT NULL)
);
CREATE TABLE recovery_codes (
    user_id UUID NOT NULL REFERENCES users(id), code_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY(user_id,code_hash)
);
CREATE TABLE password_reset_tokens (
    token_hash VARCHAR(64) PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id),
    expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ
);
CREATE TABLE transfer_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(80) NOT NULL CHECK (btrim(name)<>''),
    source_account_id UUID NOT NULL REFERENCES accounts(id), destination_account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19,2) NOT NULL CHECK(amount>0 AND amount=trunc(amount)), description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(user_id,name), CHECK(source_account_id<>destination_account_id)
);
CREATE TABLE support_tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    category VARCHAR(30) NOT NULL CHECK(category IN ('WRONG_RECIPIENT','UNRECOGNIZED','OTHER')),
    subject VARCHAR(120) NOT NULL CHECK(btrim(subject)<>''),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED')),
    assigned_to UUID REFERENCES users(id), version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_support_active ON support_tickets(user_id,transaction_id) WHERE status IN ('OPEN','IN_PROGRESS','RESOLVED');
CREATE INDEX idx_support_user ON support_tickets(user_id,created_at DESC);
CREATE TABLE support_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), ticket_id UUID NOT NULL REFERENCES support_tickets(id),
    author_id UUID NOT NULL REFERENCES users(id), body VARCHAR(2000) NOT NULL CHECK(btrim(body)<>''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE personal_transfer_limits (
    account_id UUID PRIMARY KEY REFERENCES accounts(id), per_transfer_amount NUMERIC(19,2) NOT NULL,
    daily_amount NUMERIC(19,2) NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK(per_transfer_amount>0 AND per_transfer_amount=trunc(per_transfer_amount)),
    CHECK(daily_amount>=per_transfer_amount AND daily_amount=trunc(daily_amount))
);
CREATE TABLE limit_change_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), account_id UUID NOT NULL REFERENCES accounts(id),
    user_id UUID NOT NULL REFERENCES users(id), per_transfer_amount NUMERIC(19,2) NOT NULL,
    daily_amount NUMERIC(19,2) NOT NULL, reason VARCHAR(1000) NOT NULL CHECK(btrim(reason)<>''),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')),
    reviewed_by UUID REFERENCES users(id), review_note VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), reviewed_at TIMESTAMPTZ,
    CHECK(per_transfer_amount>0 AND per_transfer_amount=trunc(per_transfer_amount)),
    CHECK(daily_amount>=per_transfer_amount AND daily_amount=trunc(daily_amount)),
    CHECK((status='PENDING' AND reviewed_at IS NULL AND reviewed_by IS NULL) OR
          (status<>'PENDING' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL AND btrim(review_note)<>''))
);
CREATE UNIQUE INDEX uq_limit_request_pending ON limit_change_requests(account_id) WHERE status='PENDING';
CREATE INDEX idx_limit_requests_user ON limit_change_requests(user_id,created_at DESC);
