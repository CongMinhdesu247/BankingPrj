# PostgreSQL

The backend already connects to PostgreSQL through its existing configuration in
`backend/src/main/resources/application.yaml`. That configuration is preserved.

`runtime-role.sql` provisions an explicit, least-privilege NOLOGIN group after
Flyway migrations. Run it as the owner in the dedicated banking database, then
grant `banking_runtime` to a separate service login provisioned through your
secret manager. The service login must not own the schema or inherit other roles.
Never use `postgres` as the application login.

Use `DB_USERNAME` / `DB_PASSWORD` for the service, and independent
`MIGRATION_DB_USERNAME` / `MIGRATION_DB_PASSWORD` for Flyway, or run migrations
as a separate deployment step and disable Flyway in the runtime process.
The script grants no TRUNCATE, DDL, evidence UPDATE/DELETE or migration-history
access. Flyway V4 also rejects audit/login/ledger UPDATE and DELETE through
triggers. Owners and superusers can bypass these protections; retain separate
owner credentials and off-host, access-controlled backups.

No production database or role is changed by this repository review.
