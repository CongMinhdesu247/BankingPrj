# Cấu trúc dự án

```text
banking/
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
      assets/
      styles/
      test/                        Setup và kiểm thử qua nhiều nghiệp vụ
      main.tsx
    e2e/
    public/
  infra/                           Cài đặt Redis, Kafka, PostgreSQL và giám sát
  docs/
    architecture/
    api/
    database/
    guides/
    reports/
    reviews/
    screenshots/
  .github/                         Công cụ hỗ trợ dự án
  .vscode/settings.json             Ẩn file sinh tự động trong Explorer
  docker-compose.yml
  README.md
```

Backend giữ package nghiệp vụ hiện có để các đường dẫn Maven, Spring, script và
cấu hình local tiếp tục hoạt động. Đã bỏ các `.gitkeep` và thư mục rỗng vốn được
tạo trước cho tính năng tương lai; tạo package mới khi thực sự có mã.

Frontend gom trang, API, provider và component theo nghiệp vụ. Mã dùng chung ở
`shared/`; router và layout ở `app/`. Xem [quy ước frontend](../../frontend/README.md).
Kiểm thử đơn vị vẫn nằm cạnh mã nguồn, còn kịch bản liên quan nhiều nghiệp vụ nằm
trong `src/test/workflows/`.

Các thư mục sinh tự động như `target/`, `node_modules/`, `dist/`, `output/`,
`test-results/`, `playwright-report/`, `.playwright-cli/`, cùng backup local được
ẩn trong Explorer của VS Code. Chúng vẫn có trên đĩa để các công cụ đang dùng
đường dẫn cũ tiếp tục chạy. Có thể xem lại bằng cách tắt quy tắc tương ứng trong
`.vscode/settings.json`. Không đặt mã nguồn trong các thư mục này.

Thêm hướng dẫn sử dụng vào `docs/guides/`, báo cáo giai đoạn vào `docs/reports/`
và báo cáo rà soát vào `docs/reviews/`. Không tạo thư mục trống để giữ chỗ.
Xem [mục lục tài liệu](../README.md).
