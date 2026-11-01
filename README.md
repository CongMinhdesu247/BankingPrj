# Bank Transaction & Fraud Monitoring System

## Chạy giao diện hoàn chỉnh sau khi clone (Windows)

Repository này chứa giao diện React đầy đủ và backend thực tế. Để đăng nhập,
chuyển tiền và xem dữ liệu mẫu, chạy demo bên dưới; `npm run dev` riêng chỉ
khởi động frontend và vẫn cần backend cho các nghiệp vụ.

### Chuẩn bị một lần

Cài Git, **PowerShell 7** (`pwsh`), **Node.js >= 22.12**, **JDK 21** và
**PostgreSQL 18** (cần các công cụ trong thư mục `bin`). Cài WSL Ubuntu nếu chưa có:

```powershell
wsl --install -d Ubuntu
```

Khởi động lại máy nếu Windows yêu cầu, mở Ubuntu và hoàn tất tạo người dùng.
Clone repository, mở PowerShell 7 tại thư mục vừa clone:

```powershell
git clone https://github.com/CongMinhdesu247/BankingPrj.git
cd BankingPrj
wsl -d Ubuntu -u root -- apt-get update
wsl -d Ubuntu -u root -- apt-get install -y redis-server redis-tools
pwsh -File ./backend/scripts/Install-Kafka.ps1
```

### Khởi động demo

Từ thư mục gốc repository (không cần đường dẫn máy của tác giả):

```powershell
pwsh -File ./Start-Demo.ps1
```

Script cài thư viện theo lockfile, build frontend/backend và khởi động PostgreSQL,
Redis, Kafka, API cùng giao diện demo. Mở **http://127.0.0.1:5174** sau thông báo
`Isolated portal demo ready`. Cần Internet cho lần tải dependency đầu tiên.

Nếu PostgreSQL nằm ở vị trí khác:

```powershell
pwsh -File ./Start-Demo.ps1 -PostgreSqlBin 'D:\PostgreSQL\18\bin'
```

Tài khoản và mật khẩu được tạo ngẫu nhiên khi chạy, lưu trong
`backend/config/portal-demo.credentials.txt` (không đưa lên Git).
Dùng `username`/`password` để đăng nhập khách hàng; `staffUsername`/`staffPassword`,
`riskUsername`/`riskPassword`, `adminUsername`/`adminPassword` cho các vai trò khác.
Không cần cấu hình `application-local.properties` cho demo này.

Demo tạo database riêng, tài khoản và số dư mô phỏng; không dùng database cá nhân.
Mỗi lần chạy tạo bộ dữ liệu mới. Dừng demo trước khi chạy lại:

```powershell
pwsh -File ./backend/scripts/Stop-PortalDemo.ps1
```

Nếu đã dùng `-PostgreSqlBin` khi khởi động, truyền cùng tham số khi dừng.
Nếu cổng 5174/8081 bị chiếm, dừng demo cũ hoặc truyền `-UiPort`/`-ApiPort` khác.
Không commit `.env`, mật khẩu, database, `node_modules` hay `target`.

### Chỉ khởi động frontend

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

Mở địa chỉ Vite in ra (mặc định http://127.0.0.1:5173). Chế độ này gọi API cổng
8080; để thử đầy đủ ngay với dữ liệu mẫu, dùng demo cổng 5174 ở trên.
Trên macOS/Linux dùng `npm` thay `npm.cmd`; trình khởi động demo tự động hiện
dành cho Windows/WSL. Hướng dẫn backend thủ công nằm ở mục Run locally bên dưới.

Security review and verification: [report (Vietnamese)](docs/reviews/security-review.vi.md).
The review adds atomic login admission, append-only database evidence, durable
Kafka outbox delivery, configured Kafka TLS/SASL propagation and patched codecs.

A banking portfolio project structured as a **modular monolith**: one Spring Boot
backend, one React frontend, and PostgreSQL. Backend packages group business
modules; frontend features group related pages, APIs and components.

The current checkout is the project root (shown as `banking-transaction-system/`
in architecture examples); no extra nested project directory is required.

| Layer | Technology |
| --- | --- |
| Frontend | React + TypeScript + Vite |
| Backend | Java 21 + Spring Boot |
| Database | PostgreSQL |
| ORM | Spring Data JPA / Hibernate |
| Temporary guards/cache | Redis 7+ |
| Asynchronous events | Apache Kafka 4.1.2 (local KRaft) |

```text
Frontend
   |
HTTP / JSON
   |
Spring Boot Backend -> Redis (guards/cache)
   |       |
   |       +-> Kafka -> fraud/notification consumers -> PostgreSQL
   |
PostgreSQL (money, limits, idempotency, evidence, inbox)
```

The React customer portal now calls the real backend for login, accounts, transfers,
transaction history, beneficiaries and profile. Redis now supports temporary login
guards, request throttling, profile caching and transaction counters.
Phase 9 adds post-commit fraud rules, immutable evidence snapshots, recovery, an
investigation workflow and internal risk/staff/admin pages. See [the Phase 9 report](docs/reports/phase-9-report.vi.md)
and [API contract](docs/api/phase-9.md). Resolving an alert never refunds a transfer.
Phase 10 moves the fraud trigger to Kafka, adds completed/failed/fraud events,
transactional consumer receipts, bounded retry/DLT and a private in-app inbox.
See [the Phase 10 report](docs/reports/phase-10-report.vi.md), [event contract](docs/architecture/events.md)
and [notification API](docs/api/phase-10.md). The DB-commit/publish gap remains
until the Phase 11 outbox; broker outages can lose notifications in Phase 10.

## Run locally

Requirements: JDK 21, Node.js 22.12+ (Node 24 LTS recommended), npm, and the existing
PostgreSQL instance, Redis 7+ for login/transfer, and Kafka for asynchronous effects.
Maven is managed by the Maven wrapper.
See [Redis setup, TTLs and outage policies](infra/redis/README.md).
On Windows/WSL, install Kafka once with `backend/scripts/Install-Kafka.ps1`, then
run `backend/scripts/Start-Kafka.ps1` before starting the backend. See
[Kafka setup, consumer lag and DLT replay](infra/kafka/README.md).

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
password. Also configure a Base64 JWT signing secret of at least 32 random bytes
using `JWT_SECRET` or local `banking.security.jwt.secret`. This machine is already
configured. Never commit that local file. The existing datasource defaults and
Hibernate settings are managed in `application.yaml`; imported local datasource values
override those defaults. The root `.env.example` is not loaded by Spring Boot.
This uses Spring Boot's [external configuration imports](https://docs.spring.io/spring-boot/reference/features/external-config.html#importing-additional-data).

Flyway now owns schema changes and Hibernate uses `ddl-auto: validate`. A new,
empty database is initialized from `V1__initial_schema.sql` on startup. An existing
non-empty database needs an explicit, verified adoption first: see
[database and migration instructions](docs/database/schema.md). Do not enable
automatic baseline or rerun the old framework script.

The backend listens on `127.0.0.1:8080` and requires Bearer JWT for business APIs.
Customer endpoints are implemented at `/api/v1/customers`: create, detail,
search/pagination, and contact updates. See [the API contract](docs/api/customers.md)
and [sample requests](docs/api/customers.http). No customer data is seeded on startup.

Account APIs are also implemented: open an account under a customer, list that
customer's accounts, read account detail, and change account status. Accounts start
with zero VND balance; closed accounts cannot reopen. See [the Account contract](docs/api/accounts.md)
and [sample requests](docs/api/accounts.http). Money is returned as decimal strings.

Phase 3 adds internal VND transfers and read-only transaction/ledger queries.
Transfers and status changes share AccountLockService; both accounts are locked
in canonical UUID order before monetary checks. A transfer writes balances,
transaction and debit/credit entries atomically. See [the transfer contract](docs/api/transfers.md),
[request examples](docs/api/transfers.http), and [read-only ledger checks](docs/database/ledger-checks.sql).
Funded treasury/OPENING fixtures exist only in the isolated test suite; starting
the normal application does not create money or demo records in banking_db.
Phase 6 adds mandatory transfer idempotency (7-day replay), source-account limits,
beneficiaries, durable login history and audit logs. See [the Phase 6 contract](docs/api/phase-6.md).

Swagger UI: `http://127.0.0.1:8080/swagger-ui/index.html`. Login at `/api/v1/auth/login`,
then use **Authorize** with the access token. Specifications are at `/v3/api-docs`,
`/v3/api-docs.yaml`, and [the exported snapshot](docs/api/openapi.yaml). See
[error codes](docs/api/error-codes.md) and [security rules](docs/api/auth-security.md).

One development admin was bootstrapped into the initially empty local users table.
Credentials are in `backend/config/development-admin.credentials.txt` (Git ignored).
Bootstrap requires profile `dev` and explicit enabled/password configuration, only
runs when users is empty, and never resets an existing password. Normal restarts
do not need bootstrap. No customer accounts, money or transactions are seeded.

To run the HTTP/database integration suite on an isolated PostgreSQL instance,
from `backend/`, run `.\scripts\Test-Postgres.ps1`. It uses locally installed
PostgreSQL binaries and stops its temporary cluster when finished. `mvnw.cmd test`
runs only unit tests and does not connect to the personal database. Integration
tests generate a fresh signing key and exercise the real JWT/security chain.
The script also starts isolated WSL Redis and Kafka instances, tests real
shutdown/restart, and stops all disposable servers. Requires redis-server,
redis-tools, Java 21 and the installed Kafka archive in Ubuntu. Phase 1–9
regression uses an explicit Kafka opt-out; Phase 10 tests enable real Kafka.
Run it directly without redirecting the entire PowerShell script; reports are saved
under backend/target. Redis cache errors fall back to PostgreSQL; Redis protection
errors return 503 THROTTLE_UNAVAILABLE. Login/transfer bursts return 429 with Retry-After.
On Windows, stop a backend running from `target/banking-0.0.1-SNAPSHOT.jar` before
running `verify` or the script: packaging needs to replace that JAR. A backend
started with `spring-boot:run` can be stopped with Ctrl+C in its terminal.

Frontend, in a separate terminal from `frontend/`:

```sh
npm install
npm run dev
```

Open the local URL printed by Vite (normally `http://localhost:5173`).
Use `npm run build` to check TypeScript and create a production build.
On Windows PowerShell with script execution disabled, use `npm.cmd` in place of `npm`.

Phase 7 implements the Vietnamese customer portal with desktop/mobile layouts.
The default Vite dev server binds `127.0.0.1:5173` and proxies API calls to
`127.0.0.1:8080`. Access tokens stay in memory: reloading requires login again.
An unresolved transfer keeps its original key/payload in sessionStorage, scoped
to the authenticated user, until resolved or explicitly signed out.

To try a funded customer workflow without seeding the personal database, run
`backend/scripts/Start-PortalDemo.ps1` after the backend JAR has been verified.
It starts an isolated PostgreSQL `banking_test` cluster, Redis on **6381**,
Kafka on **19096** (controller **19097**), API on **8081**, and portal
on **5174**, creates two fictitious customers and seeds a documented opening ledger.
Demo credentials are generated into `backend/config/portal-demo.credentials.txt`
(Git ignored). Stop with `backend/scripts/Stop-PortalDemo.ps1` before rebuilding
the JAR on Windows. See [the Phase 7 guide](docs/reports/phase-7-report.vi.md).

Frontend checks: `npm.cmd test`, `npm.cmd run build`, and `npm.cmd run test:e2e`.
Browser tests require the isolated demo and Playwright Chromium installed via
`npm.cmd exec playwright -- install chromium`; they refuse the normal 8080 backend.

## Repository layout

```text
banking-transaction-system/
  backend/              Maven application, business modules, tests and local scripts
  frontend/             React portal: src/app, src/features and src/shared
  infra/                Redis/Kafka native setup; future container/metrics placeholders
  docs/
    architecture/       Architecture documentation
    database/           Schema reference and Flyway adoption instructions
    api/                Customer/Account/Transfer contracts and sample requests
    guides/             Local data, self-service and customer tools guides
    reports/            Phase implementation reports
    reviews/            Security review reports
    screenshots/        UI verification images
  docker-compose.yml    Empty services placeholder
  .env.example          Non-secret environment example
  .gitignore
  README.md
```

See [the current structure and conventions](docs/architecture/project-structure.vi.md),
[frontend guide](frontend/README.md) and [documentation index](docs/README.md).

See [the 15-phase development roadmap (Vietnamese)](docs/development-roadmap.vi.md)
for learning goals, planned schema and APIs, tests, dependencies, and completion
criteria. The roadmap describes planned work, not implemented features.

## Implemented and next components

- Redis login guards, throttling, profile cache and best-effort counters are implemented (Phase 8).
- Kafka events, receipt deduplication, retry/DLT and in-app notifications are implemented (Phase 10).
- Transactional outbox now stores events before commit and relays after broker acknowledgement.
- Docker Compose for local container orchestration.
- Prometheus for metrics and Grafana for dashboards.

Customer, Account, internal Transfer/ledger, Flyway, OpenAPI, JWT, roles,
ownership, idempotency, limits, beneficiaries, audit, Redis, fraud investigation,
Kafka, notifications and transactional outbox are implemented. The full Compose stack remains future work.
