1. Khởi động Redis và Kafka trong cửa sổ thứ nhất:
cd C:\Users\ADMIN\banking
wsl -d Ubuntu -u root -- service redis-server start
.\backend\scripts\Start-Kafka.ps1

2. Khởi động backend trong cửa sổ thứ hai:
cd C:\Users\ADMIN\banking\backend
.\mvnw.cmd spring-boot:run

3. Khởi động giao diện trong cửa sổ thứ ba:
cd C:\Users\ADMIN\banking\frontend
npm.cmd run dev

http://localhost:5173

# Bank Transaction & Fraud Monitoring System

A banking portfolio project structured as a **modular monolith**: one Spring Boot
backend, one React frontend, and PostgreSQL. Module folders reserve space for future
features; they do not introduce separate services.

The current checkout is the project root (shown as `banking-transaction-system/`
in architecture examples); no extra nested project directory is required.

| Layer | Technology |
| --- | --- |
| Frontend | React + TypeScript + Vite |
| Backend | Java 21 + Spring Boot |
| Database | PostgreSQL |
| ORM | Spring Data JPA / Hibernate |

```text
Frontend
   |
HTTP / JSON
   |
Spring Boot Backend
   |
PostgreSQL

Later:
Spring Boot -> Redis
Spring Boot -> Kafka
Prometheus -> Grafana
```

The diagram describes the intended integration. The placeholder frontend does not
call backend endpoints yet.

## Run locally

Requirements: JDK 21, Node.js 22.12+ (Node 24 LTS recommended), npm, and the existing
PostgreSQL instance. Maven is managed by the Maven wrapper.

Backend, from `backend/`:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

On macOS/Linux use `./mvnw test` and `./mvnw spring-boot:run` instead.
The Windows wrapper handles the PowerShell case where the Maven cache directory
has no symbolic-link target.

`backend/src/main/resources/application.yaml` imports the optional machine-local
file `backend/config/application-local.properties`. This local file contains the
PostgreSQL connection details for this machine and is ignored by Git. Once it is
configured, opening a fresh terminal and running the commands above requires no
`setx` commands. Keep PostgreSQL running and run Maven from `backend/`.

On a fresh checkout, copy `backend/config/application-local.properties.example`
to `backend/config/application-local.properties` and enter your own local database
password. Never commit that local file. The existing datasource defaults and
Hibernate settings remain in `application.yaml`; imported local datasource values
override those defaults. The root `.env.example` is not loaded by Spring Boot.
This uses Spring Boot's [external configuration imports](https://docs.spring.io/spring-boot/reference/features/external-config.html#importing-additional-data).

Frontend, in a separate terminal from `frontend/`:

```sh
npm install
npm run dev
```

Open the local URL printed by Vite (normally `http://localhost:5173`).
Use `npm run build` to check TypeScript and create a production build.
On Windows PowerShell with script execution disabled, use `npm.cmd` in place of `npm`.

## Repository layout

```text
banking-transaction-system/
  backend/              Existing Maven application and future module packages
  frontend/             Minimal React + TypeScript + Vite application
  infra/                Future infrastructure placeholders
  docs/
    architecture/       Architecture documentation
    database/           Future schema and migration documentation
    api/                Future API documentation
  docker-compose.yml    Empty services placeholder
  .env.example          Non-secret environment example
  .gitignore
  README.md
```

See [the restructuring report](docs/architecture/restructuring.md) for the full
file and folder inventory and final directory tree.

## Future components

- Spring Security + JWT for authentication and authorization.
- Redis for caching.
- Kafka for asynchronous messaging.
- Flyway for database migrations.
- Docker Compose for local container orchestration.
- Prometheus for metrics and Grafana for dashboards.

These are placeholders only. This restructuring does not implement transfers,
authentication, fraud rules, Redis, Kafka, or simulated banking behavior.
