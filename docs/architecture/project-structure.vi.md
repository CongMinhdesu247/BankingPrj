# Cấu trúc dự án

```text
BankingPrj/
  backend/
    src/main/java/com/minh/banking/  Module nghiệp vụ và cấu hình Spring
    src/main/resources/            Cấu hình ứng dụng và Flyway migrations
    src/test/                      Unit test và integration test
    config/                        Ví dụ cấu hình và cấu hình riêng của máy
    scripts/                       Chạy demo, hạ tầng và kiểm tra backend
    .mvn/                          Maven wrapper
    pom.xml
  frontend/
    src/
      app/                         Khởi tạo ứng dụng, router và layout
      features/                    Mã gom theo nghiệp vụ
      shared/                      API HTTP, UI, hook, kiểu và tiện ích chung
      assets/fonts/                Font PDF và giấy phép
      styles/
      test/                        Setup và kiểm thử qua nhiều nghiệp vụ
      main.tsx
    e2e/
    public/
  scripts/                         Điều phối Docker và demo toàn dự án
  infra/                           Docker, Redis, Kafka, PostgreSQL và giám sát
  docs/
    architecture/
    api/
    database/
    guides/
    reports/
    reviews/
    screenshots/
  .github/                         CI và công cụ hỗ trợ dự án
  .vscode/settings.json            Cấu hình editor dùng chung
  docker-compose.yml
  README.md
```

Backend tổ chức theo module nghiệp vụ. Các module đã phân lớp giữ controller,
service, repository, entity, DTO và mapper trong cùng nghiệp vụ. Package Java và
đường dẫn Maven/Spring/resources giữ nguyên để không ảnh hưởng cơ chế đăng ký bean,
Flyway, script và cấu hình local. Chỉ tạo package khi có mã thực tế.

Frontend gom trang, API, provider và component theo nghiệp vụ. Mã dùng chung ở
`shared/`; router và layout ở `app/`. Không giữ lớp `src/services/` song song với
API thực trong feature. Xem [quy ước frontend](../../frontend/README.md).
Test đơn vị nằm cạnh mã nguồn; kịch bản qua nhiều nghiệp vụ ở `src/test/workflows/`.

## Script điều phối

Chạy từ thư mục gốc repository:

```powershell
.\scripts\Initialize-Docker.ps1
pwsh -NoProfile -File .\scripts\Seed-DockerDemo.ps1
pwsh -File .\scripts\Start-Demo.ps1
.\scripts\Start-PublicDemo.ps1 -PublicUrl 'https://ten-duoc-cap.ngrok-free.app'
```

Chọn script theo hướng dẫn trong [README](../../README.md), không chạy cả bốn liên tiếp.
Initialize chỉ dùng lần đầu; Start-Demo tạo bộ dữ liệu demo riêng. Các script tính
đường dẫn từ vị trí file thay vì phụ thuộc working directory của terminal.

Hai công cụ kiểm thử Docker độc lập nằm trong `backend/scripts/Verify-Cashflow.ps1`
và `backend/scripts/Verify-Registration.ps1`. Chạy bằng PowerShell 7; cần Docker
Desktop đang hoạt động. Chúng tự chọn working directory `backend/`, tạo PostgreSQL/Redis
test riêng và dừng container test khi hoàn tất.

## File sinh tự động và dữ liệu local

`.gitignore` chặn dependency, build output, log, file tạm, coverage, snapshot/ảnh
automation và thông tin cấu hình riêng. Giữ lockfile, migration, font/license và
ảnh tài liệu đã tuyển chọn trong `docs/screenshots/`. Không thêm `.gitkeep` cho
thư mục chưa có mã.

`backend/target/portal-demo/` hiện chứa PostgreSQL data và trạng thái demo.
Không xóa toàn bộ `backend/target/` hoặc chạy `mvnw clean` khi cần giữ demo native.
`.env`, `backend/config/*.credentials.txt` và `frontend/.local/` là dữ liệu local
cần giữ; việc ignore không có nghĩa chúng có thể bị xóa an toàn.

Thêm hướng dẫn vào `docs/guides/`, báo cáo giai đoạn vào `docs/reports/` và báo cáo
rà soát vào `docs/reviews/`. Xem [mục lục tài liệu](../README.md) và
[kế hoạch dọn workspace đã duyệt](workspace-cleanup-plan.vi.md).
