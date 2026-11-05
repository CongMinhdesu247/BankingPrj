# Kế hoạch dọn dẹp & Sơ đồ thư mục mới

Ngày rà soát: 05/11/2026. ĐÃ ĐƯỢC DUYỆT VÀ ÁP DỤNG ngày 05/11/2026.

Nội dung bên dưới lưu kế hoạch trước triển khai. Xem [báo cáo kết quả và kiểm chứng](../reports/workspace-cleanup-result.vi.md) để biết trạng thái thực tế.

## Phạm vi và bằng chứng

- Kiểm kê 608 file ngoài .git, node_modules, dist và target; đọc mã nguồn, config, test, script và tài liệu. Rà riêng danh mục target và hai script viết tay bên trong. Không đưa nội dung .env hoặc credential local vào báo cáo.
- Stack theo manifest: Java 21/Spring Boot 4.1.2, PostgreSQL/Flyway, Redis, Kafka; React 19/TypeScript/Vite, Vitest, Playwright. Backend có 168 file Java production.
- Phân tích import tĩnh từ main.tsx, gồm from/import()/require()/new URL, CSS import. Loại test, setup và declaration khỏi danh sách runtime. Cả 10 module production ngoài đồ thị là placeholder export {}. Font import có ?url là tài nguyên Vite, phải giữ.
- Không có import trỏ tới frontend/src/services trong mã nguồn đã quét. Nhắc tên trong tài liệu lịch sử không phải dependency runtime.
- Có 145 .gitkeep: 108 nằm trong thư mục chỉ có .gitkeep; 37 nằm trong thư mục đã có nội dung khác.
- Không tìm thấy file .bak/.tmp/.old/.orig/.rej/~ hoặc tên copy theo mẫu đã quét trong nhóm file ngoài dependency/build.
- Ở lần rà soát ban đầu, môi trường chặn tạo tiến trình với Access denied/EPERM; đã dùng filesystem Node để đọc và phân tích. Khi triển khai, đã chạy được Git/build/test bằng quyền thực thi phù hợp; kết quả được ghi trong báo cáo triển khai.

## Đánh giá và cấu trúc đích

Dự án đã là monorepo backend/frontend/infra/docs. Frontend app/features/shared là nền tảng phù hợp. Backend là modular monolith theo nghiệp vụ, một số module đã chia controller/service/repository/entity. Đợt này chuẩn hóa cấu trúc hiện hữu; chuyển toàn bộ sang ports/adapters cần refactor và kiểm thử riêng.
Vấn đề chính: scaffold cũ còn cạnh mã thực, script điều phối ở root, script viết tay trong target, log/capture và tài liệu lệch hiện trạng. project-structure.vi.md nói đã bỏ placeholder nhưng checkout vẫn còn 145 file. restructuring.md là lịch sử cần giữ bối cảnh.

```text
BankingPrj/
├── backend/
│   ├── .mvn/wrapper/
│   ├── config/                  # ví dụ và cấu hình local ignored
│   ├── scripts/                 # công cụ backend + Verify-*.ps1
│   ├── src/main/
│   │   ├── java/com/minh/banking/
│   │   │   ├── BankingApplication.java
│   │   │   ├── account/, auth/, customer/, transaction/, user/
│   │   │   ├── analytics/, audit/, beneficiary/, fraud/, limit/
│   │   │   ├── notification/, support/, idempotency/
│   │   │   └── common/, config/, security/, kafka/, redis/
│   │   └── resources/          # application.yaml + db/migration
│   ├── src/test/java/com/minh/banking/
│   └── pom.xml, mvnw, mvnw.cmd, Dockerfile
├── frontend/
│   ├── src/
│   │   ├── app/                # App, layout, routes
│   │   ├── features/           # api/components/pages/providers/lib theo nghiệp vụ
│   │   ├── shared/             # api/components/hooks/lib/types dùng chung
│   │   ├── assets/fonts/       # font PDF + license
│   │   ├── styles/
│   │   ├── test/               # setup + workflow tests
│   │   └── main.tsx, vite-env.d.ts
│   ├── e2e/, public/
│   └── package*.json, tsconfig*.json, *config.ts, Dockerfile, nginx.conf
├── scripts/                    # bốn script điều phối từ root
├── infra/
├── docs/                       # architecture/api/database/guides/reports/reviews/screenshots
├── .github/
├── .vscode/settings.json
└── README.md, docker-compose.yml, .env.example, .gitignore, .gitattributes
```

Cây hiển thị mã/cấu hình. Dependency, output và dữ liệu local ignored vẫn ở vị trí hiện tại. Không tạo sẵn thư mục rỗng hoặc lớp services/utils/components song song với features/shared. Giữ cấu trúc package Java và vị trí frontend production trong đợt này.

## Đề xuất xóa sau duyệt

### 10 placeholder TypeScript

Mỗi file dưới đây chỉ có comment Reserved for future và export {}; không triển khai API, không có import vào file. API thực ở features/*/api hoặc shared/api.

- frontend/src/features/security/api/auditApi.ts
- frontend/src/services/accountApi.ts
- frontend/src/services/auditApi.ts
- frontend/src/services/authApi.ts
- frontend/src/services/beneficiaryApi.ts
- frontend/src/services/customerApi.ts
- frontend/src/services/fraudApi.ts
- frontend/src/services/httpClient.ts
- frontend/src/services/transactionApi.ts
- frontend/src/services/transferApi.ts

### Log ngoài target

Log demo/build/verification hoặc JVM crash, không phải input ứng dụng. Chỉ xóa khi không còn cần chẩn đoán và file không bị tiến trình sử dụng.

- .playwright-cli/console-2026-11-02T09-19-10-698Z.log
- backend/hs_err_pid11280.log
- backend/hs_err_pid29356.log
- backend/hs_err_pid32704.log
- backend/hs_err_pid9792.log
- backend/verify-local.log
- demo-docker-restart.log
- demo-docker-start.log
- demo-docker-web-build.log
- demo-runtime-verification.log
- demo-verification.log
- frontend/demo-docker-login-fix-build.log

### Snapshot và ảnh automation

Kết quả kiểm tra thủ công; giữ ảnh tài liệu docs/screenshots. Hai snapshot 08-43-08-325Z và 09-22-06-182Z còn trùng SHA-256.

- .playwright-cli/page-2026-11-02T08-40-55-743Z.yml
- .playwright-cli/page-2026-11-02T08-43-08-325Z.yml
- .playwright-cli/page-2026-11-02T09-19-11-100Z.yml
- .playwright-cli/page-2026-11-02T09-22-06-182Z.yml
- output/playwright/cashflow-desktop.png
- output/playwright/cashflow-mobile.png
- output/playwright/cashflow-production.png
- output/playwright/chatbot-desktop.png
- output/playwright/chatbot-mobile-360.png
- output/playwright/chatbot-mobile-390.png
- output/playwright/chatbot-readable-desktop.png
- output/playwright/chatbot-readable-expanded.png
- output/playwright/chatbot-readable-mobile-360.png
- output/playwright/chatbot-readable-mobile-390.png
- output/playwright/registration-desktop.png
- output/playwright/registration-mobile.png
- output/playwright/registration-success.png
- output/playwright/vnd-fixed-production.png
- output/playwright/vnd-input-desktop.png
- output/playwright/vnd-input-mobile.png
- output/playwright/vnd-mobile-inspection.png

### Placeholder thư mục

Xóa 145 file .gitkeep theo manifest cuối báo cáo. Chỉ dọn thư mục thực sự rỗng sau đó, không xóa đệ quy thư mục chứa code.

## Nhóm giữ nguyên hoặc cần điều kiện riêng

- backend/target/portal-demo/** chứa PostgreSQL data và server-state.json, cần cho demo và thao tác dừng. Không xóa target toàn bộ, không chạy mvnw clean trong đợt này.
- backend/target/verify-cashflow.ps1 và verify-registration.ps1 là script viết tay: chuyển ra backend/scripts, không xóa.
- frontend/dist và các thư mục classes/test-classes/generated-sources/generated-test-sources/maven-archiver/maven-status/surefire-reports trong backend/target là output tái tạo. Giữ mặc định; chỉ dọn sau khi kiểm tra tiến trình và khả năng build lại.
- JAR và .jar.original trong backend/target có thể build lại nhưng Start-PortalDemo phụ thuộc JAR. Giữ mặc định để không làm gián đoạn demo.
- frontend/node_modules giữ mặc định, không phải code thừa.
- .env, backend/config/*.credentials.txt, frontend/.local/portal-demo.json giữ tại vị trí hiện tại và tiếp tục ignore.
- Giữ migrations V1–V5, lockfile, wrapper, font/license, SQL hỗ trợ, docs và tests. backend/HELP.md giữ đến khi đối chiếu thông tin cần thiết với README.
- Không xóa Java chỉ vì không được gọi trực tiếp. RecipientController, AuditController, TimeConfig, DirectFraudTrigger có annotation để Spring đăng ký/gọi runtime.
- .github/modernize hooks và .vscode/settings.json là công cụ hỗ trợ; thiếu import ứng dụng không chứng minh thừa.

## Bảng di chuyển đầy đủ

| Cũ | Mới |
| --- | --- |
| Initialize-Docker.ps1 | scripts/Initialize-Docker.ps1 |
| Seed-DockerDemo.ps1 | scripts/Seed-DockerDemo.ps1 |
| Start-Demo.ps1 | scripts/Start-Demo.ps1 |
| Start-PublicDemo.ps1 | scripts/Start-PublicDemo.ps1 |
| backend/target/verify-cashflow.ps1 | backend/scripts/Verify-Cashflow.ps1 |
| backend/target/verify-registration.ps1 | backend/scripts/Verify-Registration.ps1 |
 
### Cập nhật đường dẫn cùng đợt di chuyển

- Bốn script mới trong scripts/: tính projectRoot là thư mục cha của PSScriptRoot. Đổi mọi truy cập frontend/backend/.env và working directory của docker compose sang projectRoot.
- Start-Demo: giữ đúng backend/scripts/Start-PortalDemo.ps1; sửa comment/thông báo ví dụ lệnh. Initialize-Docker/Seed-DockerDemo tiếp tục dùng .env tại root và backend/config, không chuyển credential.
- Start-PublicDemo: .env và compose ở root; sửa thông báo Initialize-Docker. Không khởi chạy public tunnel trong bước kiểm thử cấu trúc.
- Hai Verify-*.ps1: thêm working directory backend bằng Push-Location/Pop-Location trong finally, giữ output target, test selection và cleanup container. Hiện script phụ thuộc terminal đang ở backend.
- README.md, docs/guides/docker-local.vi.md, docs/guides/ai-chatbot.vi.md: sửa lệnh sang ./scripts/*.ps1.
- docker-compose.yml: sửa thông báo gọi Initialize-Docker; build contexts ./backend, ./frontend và mount ./infra/docker/init-postgres.sh giữ nguyên.
- docs/architecture/project-structure.vi.md và frontend/README.md: đồng bộ cây thật. Giữ restructuring.md là lịch sử; không thay đường dẫn lịch sử máy móc.
- verify.yml, pom.xml, application.yaml, Vite/TS/Vitest/Playwright config không dự kiến đổi vì module và resources giữ vị trí; kiểm tra lại sau thay đổi.
- Không cần đổi import/require ứng dụng theo kế hoạch này: chỉ xóa module không có người dùng. Rà lại import động, asset URL, HTML entrypoint và đường dẫn script trước nghiệm thu.
- Caller/shortcut ngoài workspace tới script root không thể phát hiện qua scan; cần đổi sang scripts/ nếu có.

## .gitignore dự kiến

Giữ các rule hiện có cho node_modules, dist, target, .env, log, credential và output trình duyệt. Gom rule trùng, thêm nhóm sau sau khi duyệt:

```gitignore
# Temporary / editor backup
*.tmp
*.temp
*.bak
*.orig
*.rej
*~
*.swp
*.swo

# JVM crash / memory dump
hs_err_pid*
replay_pid*
*.hprof

# Test / Python helper output
**/coverage/
**/.nyc_output/
**/__pycache__/
*.py[cod]
.pytest_cache/

# Reserved local runtime directory
/.local/
```

Không ignore rộng *copy*, *.sql, *.png, *.json hay toàn bộ config vì có thể che source hợp lệ. Giữ exception .env.example. .gitignore không tự bỏ tracked file: cần git ls-files và git check-ignore trước khi xử lý index, không git rm --cached diện rộng.

## Trình tự và kiểm chứng sau duyệt

1. Kiểm tra git status, tracked/untracked và thay đổi có sẵn. Đối chiếu lại manifest với trạng thái hiện tại; kiểm tra file đang dùng trước khi dọn.
2. Chuyển sáu script, sửa đường dẫn và tài liệu trong cùng đợt. Xóa đúng placeholder/log/capture được duyệt. Giữ runtime data và nhóm artifacts có điều kiện.
3. Cập nhật .gitignore; tìm lại đường dẫn cũ, cho phép các nhắc lịch sử có chú thích; rà import/require/import động/CSS URL/HTML/script/Docker contexts.
4. Frontend: npm test và npm run build. Backend: mvnw.cmd --batch-mode verify. Integration cần profile integration với môi trường test phù hợp theo backend/scripts/Test-Postgres.ps1; verify mặc định không thay thế toàn bộ *IT.
5. Parse PowerShell, kiểm tra Compose config --quiet để không in secrets. Chạy hai Verify trên container test khi môi trường cho phép. Không Initialize/Seed dữ liệu hiện hữu chỉ để thử path.
6. Smoke test login, tài khoản, chuyển tiền/lịch sử, fraud/notification và PDF/font; E2E desktop/mobile trên demo test phù hợp. Không tự dừng dịch vụ hiện hữu hay xuất bản public tunnel.
7. Chỉ xác nhận không làm gãy ứng dụng khi kiểm tra liên quan đạt. Nếu process vẫn bị chặn, báo rõ giới hạn chưa xác minh.

## Manifest 145 .gitkeep đề xuất xóa

- backend/src/main/java/com/minh/banking/account/controller/.gitkeep
- backend/src/main/java/com/minh/banking/account/dto/request/.gitkeep
- backend/src/main/java/com/minh/banking/account/dto/response/.gitkeep
- backend/src/main/java/com/minh/banking/account/entity/.gitkeep
- backend/src/main/java/com/minh/banking/account/enums/.gitkeep
- backend/src/main/java/com/minh/banking/account/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/account/repository/.gitkeep
- backend/src/main/java/com/minh/banking/account/service/.gitkeep
- backend/src/main/java/com/minh/banking/account/validator/.gitkeep
- backend/src/main/java/com/minh/banking/audit/controller/.gitkeep
- backend/src/main/java/com/minh/banking/audit/dto/request/.gitkeep
- backend/src/main/java/com/minh/banking/audit/dto/response/.gitkeep
- backend/src/main/java/com/minh/banking/audit/entity/.gitkeep
- backend/src/main/java/com/minh/banking/audit/enums/.gitkeep
- backend/src/main/java/com/minh/banking/audit/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/audit/repository/.gitkeep
- backend/src/main/java/com/minh/banking/audit/service/.gitkeep
- backend/src/main/java/com/minh/banking/audit/validator/.gitkeep
- backend/src/main/java/com/minh/banking/auth/controller/.gitkeep
- backend/src/main/java/com/minh/banking/auth/dto/request/.gitkeep
- backend/src/main/java/com/minh/banking/auth/dto/response/.gitkeep
- backend/src/main/java/com/minh/banking/auth/entity/.gitkeep
- backend/src/main/java/com/minh/banking/auth/enums/.gitkeep
- backend/src/main/java/com/minh/banking/auth/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/auth/repository/.gitkeep
- backend/src/main/java/com/minh/banking/auth/service/.gitkeep
- backend/src/main/java/com/minh/banking/auth/validator/.gitkeep
- backend/src/main/java/com/minh/banking/beneficiary/controller/.gitkeep
- backend/src/main/java/com/minh/banking/beneficiary/dto/request/.gitkeep
- backend/src/main/java/com/minh/banking/beneficiary/dto/response/.gitkeep
- backend/src/main/java/com/minh/banking/beneficiary/entity/.gitkeep
- backend/src/main/java/com/minh/banking/beneficiary/enums/.gitkeep
- backend/src/main/java/com/minh/banking/beneficiary/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/beneficiary/repository/.gitkeep
- backend/src/main/java/com/minh/banking/beneficiary/service/.gitkeep
- backend/src/main/java/com/minh/banking/beneficiary/validator/.gitkeep
- backend/src/main/java/com/minh/banking/common/constants/.gitkeep
- backend/src/main/java/com/minh/banking/common/exception/.gitkeep
- backend/src/main/java/com/minh/banking/common/response/.gitkeep
- backend/src/main/java/com/minh/banking/common/util/.gitkeep
- backend/src/main/java/com/minh/banking/common/validation/.gitkeep
- backend/src/main/java/com/minh/banking/config/.gitkeep
- backend/src/main/java/com/minh/banking/customer/controller/.gitkeep
- backend/src/main/java/com/minh/banking/customer/dto/request/.gitkeep
- backend/src/main/java/com/minh/banking/customer/dto/response/.gitkeep
- backend/src/main/java/com/minh/banking/customer/entity/.gitkeep
- backend/src/main/java/com/minh/banking/customer/enums/.gitkeep
- backend/src/main/java/com/minh/banking/customer/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/customer/repository/.gitkeep
- backend/src/main/java/com/minh/banking/customer/service/.gitkeep
- backend/src/main/java/com/minh/banking/customer/validator/.gitkeep
- backend/src/main/java/com/minh/banking/fraud/controller/.gitkeep
- backend/src/main/java/com/minh/banking/fraud/dto/.gitkeep
- backend/src/main/java/com/minh/banking/fraud/entity/.gitkeep
- backend/src/main/java/com/minh/banking/fraud/enums/.gitkeep
- backend/src/main/java/com/minh/banking/fraud/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/fraud/repository/.gitkeep
- backend/src/main/java/com/minh/banking/fraud/rules/.gitkeep
- backend/src/main/java/com/minh/banking/fraud/service/.gitkeep
- backend/src/main/java/com/minh/banking/idempotency/controller/.gitkeep
- backend/src/main/java/com/minh/banking/idempotency/dto/request/.gitkeep
- backend/src/main/java/com/minh/banking/idempotency/dto/response/.gitkeep
- backend/src/main/java/com/minh/banking/idempotency/entity/.gitkeep
- backend/src/main/java/com/minh/banking/idempotency/enums/.gitkeep
- backend/src/main/java/com/minh/banking/idempotency/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/idempotency/repository/.gitkeep
- backend/src/main/java/com/minh/banking/idempotency/service/.gitkeep
- backend/src/main/java/com/minh/banking/idempotency/validator/.gitkeep
- backend/src/main/java/com/minh/banking/kafka/config/.gitkeep
- backend/src/main/java/com/minh/banking/kafka/consumer/.gitkeep
- backend/src/main/java/com/minh/banking/kafka/event/.gitkeep
- backend/src/main/java/com/minh/banking/kafka/producer/.gitkeep
- backend/src/main/java/com/minh/banking/kafka/topic/.gitkeep
- backend/src/main/java/com/minh/banking/limit/controller/.gitkeep
- backend/src/main/java/com/minh/banking/limit/dto/request/.gitkeep
- backend/src/main/java/com/minh/banking/limit/dto/response/.gitkeep
- backend/src/main/java/com/minh/banking/limit/entity/.gitkeep
- backend/src/main/java/com/minh/banking/limit/enums/.gitkeep
- backend/src/main/java/com/minh/banking/limit/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/limit/repository/.gitkeep
- backend/src/main/java/com/minh/banking/limit/service/.gitkeep
- backend/src/main/java/com/minh/banking/limit/validator/.gitkeep
- backend/src/main/java/com/minh/banking/notification/controller/.gitkeep
- backend/src/main/java/com/minh/banking/notification/dto/request/.gitkeep
- backend/src/main/java/com/minh/banking/notification/dto/response/.gitkeep
- backend/src/main/java/com/minh/banking/notification/entity/.gitkeep
- backend/src/main/java/com/minh/banking/notification/enums/.gitkeep
- backend/src/main/java/com/minh/banking/notification/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/notification/repository/.gitkeep
- backend/src/main/java/com/minh/banking/notification/service/.gitkeep
- backend/src/main/java/com/minh/banking/notification/validator/.gitkeep
- backend/src/main/java/com/minh/banking/outbox/entity/.gitkeep
- backend/src/main/java/com/minh/banking/outbox/enums/.gitkeep
- backend/src/main/java/com/minh/banking/outbox/repository/.gitkeep
- backend/src/main/java/com/minh/banking/outbox/scheduler/.gitkeep
- backend/src/main/java/com/minh/banking/outbox/service/.gitkeep
- backend/src/main/java/com/minh/banking/redis/config/.gitkeep
- backend/src/main/java/com/minh/banking/redis/keys/.gitkeep
- backend/src/main/java/com/minh/banking/redis/service/.gitkeep
- backend/src/main/java/com/minh/banking/security/config/.gitkeep
- backend/src/main/java/com/minh/banking/security/handler/.gitkeep
- backend/src/main/java/com/minh/banking/security/jwt/.gitkeep
- backend/src/main/java/com/minh/banking/security/service/.gitkeep
- backend/src/main/java/com/minh/banking/transaction/controller/.gitkeep
- backend/src/main/java/com/minh/banking/transaction/dto/request/.gitkeep
- backend/src/main/java/com/minh/banking/transaction/dto/response/.gitkeep
- backend/src/main/java/com/minh/banking/transaction/entity/.gitkeep
- backend/src/main/java/com/minh/banking/transaction/enums/.gitkeep
- backend/src/main/java/com/minh/banking/transaction/lock/.gitkeep
- backend/src/main/java/com/minh/banking/transaction/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/transaction/repository/.gitkeep
- backend/src/main/java/com/minh/banking/transaction/service/.gitkeep
- backend/src/main/java/com/minh/banking/transaction/validator/.gitkeep
- backend/src/main/java/com/minh/banking/user/controller/.gitkeep
- backend/src/main/java/com/minh/banking/user/dto/request/.gitkeep
- backend/src/main/java/com/minh/banking/user/dto/response/.gitkeep
- backend/src/main/java/com/minh/banking/user/entity/.gitkeep
- backend/src/main/java/com/minh/banking/user/enums/.gitkeep
- backend/src/main/java/com/minh/banking/user/mapper/.gitkeep
- backend/src/main/java/com/minh/banking/user/repository/.gitkeep
- backend/src/main/java/com/minh/banking/user/service/.gitkeep
- backend/src/main/java/com/minh/banking/user/validator/.gitkeep
- docs/api/.gitkeep
- docs/architecture/.gitkeep
- docs/database/.gitkeep
- frontend/src/assets/icons/.gitkeep
- frontend/src/assets/images/.gitkeep
- frontend/src/components/account/.gitkeep
- frontend/src/components/common/.gitkeep
- frontend/src/components/fraud/.gitkeep
- frontend/src/components/layout/.gitkeep
- frontend/src/components/transaction/.gitkeep
- frontend/src/config/.gitkeep
- frontend/src/constants/.gitkeep
- frontend/src/hooks/.gitkeep
- frontend/src/pages/admin/.gitkeep
- frontend/src/pages/auth/.gitkeep
- frontend/src/pages/customer/.gitkeep
- frontend/src/pages/risk/.gitkeep
- frontend/src/pages/staff/.gitkeep
- frontend/src/routes/.gitkeep
- frontend/src/schemas/.gitkeep
- frontend/src/store/.gitkeep
- frontend/src/types/.gitkeep
- frontend/src/utils/.gitkeep
