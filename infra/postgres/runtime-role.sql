-- Run deliberately as the database owner in the dedicated banking database,
-- AFTER Flyway migrations. This script creates no login/password and grants no
-- membership. Provision an independent service login using your secret manager,
-- then GRANT banking_runtime TO that login. Never grant it to a migration owner.
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='banking_runtime') THEN
        CREATE ROLE banking_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO banking_runtime;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM banking_runtime;
GRANT SELECT ON customers, accounts, users, roles, user_roles, transactions,
    transaction_entries, transfer_limits, beneficiaries, audit_logs, login_history,
    idempotency_keys, fraud_rules, fraud_analysis_runs, fraud_alerts,
    fraud_alert_rule_matches, processed_events, notifications, transfer_attempts,
    outbox_events TO banking_runtime;
GRANT INSERT ON customers, accounts, users, user_roles, transactions,
    transaction_entries, transfer_limits, beneficiaries, audit_logs, login_history,
    idempotency_keys, fraud_rules, fraud_analysis_runs, fraud_alerts,
    fraud_alert_rule_matches, processed_events, notifications, transfer_attempts,
    outbox_events TO banking_runtime;
GRANT UPDATE ON customers, accounts, users, transfer_limits, beneficiaries,
    idempotency_keys, fraud_rules, fraud_analysis_runs, fraud_alerts, notifications,
    outbox_events TO banking_runtime;
GRANT DELETE ON user_roles TO banking_runtime;
-- No TRUNCATE, DDL, trigger ownership, evidence UPDATE/DELETE or schema history
-- access. Do not run Flyway with this role; use separate migration credentials.
