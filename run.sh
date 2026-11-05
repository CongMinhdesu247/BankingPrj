#!/bin/sh
set -eu
cd -- "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
fail() { printf '%s\n' "[ERROR] $*" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || fail 'Install Docker with Docker Compose first.'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required.'
docker info >/dev/null 2>&1 || fail 'Start Docker Desktop or the Docker daemon first.'
if [ ! -e .env ]; then
    # Standard macOS/Linux utilities; no Node, Java or OpenSSL installation needed.
    random_secret() { head -c "$1" /dev/urandom | base64 | tr -d '\r\n'; }
    db_password=$(random_secret 48)
    jwt_secret=$(random_secret 64)
    admin_password=$(random_secret 48)
    (umask 077; set -C
        printf '%s\n' \
            '# Local Docker secrets. Do not commit this file.' \
            "DOCKER_DB_PASSWORD=$db_password" \
            "DOCKER_JWT_SECRET=$jwt_secret" \
            "DOCKER_ADMIN_PASSWORD=$admin_password" \
            'DOCKER_ALLOWED_ORIGINS=http://localhost:8090,http://127.0.0.1:8090' > .env
    ) || fail 'Could not create .env; existing configuration was preserved.'
    unset db_password jwt_secret admin_password
fi
docker compose config --quiet || fail 'Check the required DOCKER_* variables in .env.'
printf '%s\n' 'Building and starting BankingPrj. The first build may take several minutes...'
docker compose up -d --build --wait --wait-timeout 300 || fail 'Startup failed. Run: docker compose logs --tail 100'
printf '%s\n' \
    '[DONE] Application is ready: http://localhost:8090' \
    'Admin: docker.admin - password: DOCKER_ADMIN_PASSWORD in .env' \
    'Keep .env for future starts. Data is stored in Docker volumes.'
