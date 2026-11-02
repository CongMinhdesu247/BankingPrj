#!/bin/sh
set -eu
# App owns only this isolated demo database, never a cluster superuser.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=app_password="$POSTGRES_PASSWORD" <<'SQL'
CREATE ROLE banking_demo LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'app_password';
ALTER DATABASE banking_docker_demo OWNER TO banking_demo;
SQL
