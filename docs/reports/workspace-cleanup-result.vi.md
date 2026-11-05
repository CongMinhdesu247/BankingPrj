# Kết quả dọn dẹp workspace

Ngày thực hiện: 05/11/2026. Người dùng đã duyệt kế hoạch và yêu cầu giữ ứng dụng hoạt động.

## Thay đổi đã thực hiện

- Di chuyển bốn script điều phối từ root vào scripts/; tính projectRoot từ vị trí script và cập nhật mọi đường dẫn frontend/backend/.env/Compose liên quan.
- Di chuyển hai script viết tay khỏi backend/target vào backend/scripts/Verify-Cashflow.ps1 và Verify-Registration.ps1; neo working directory vào backend, tạo thư mục output nếu thiếu và phục hồi working directory trong finally.
- Xóa 188 file đúng manifest đã duyệt: 10 placeholder TypeScript, 145 .gitkeep, 12 log và 21 snapshot/ảnh automation. Dọn 121 thư mục rỗng, không xóa đệ quy các cây chứa mã/dữ liệu.
- Cập nhật README, hướng dẫn Docker/chatbot, tài liệu kiến trúc và quy ước frontend.
- Gom ba rule ignore trùng; thêm file tạm/editor backup, JVM dump, coverage, Python cache và thư mục runtime local. Không sửa Git index, không commit.

## Bảo toàn dữ liệu và mã nguồn

- Git trước khi triển khai chỉ có báo cáo kế hoạch untracked; không có thay đổi mã nguồn có sẵn cần tránh ghi đè.
- Đối chiếu SHA-256 cho 319 file được bảo vệ còn lại: mã production/test, resource/migration, manifest/lockfile, font/license, ảnh tài liệu và cấu hình/credential local không thay đổi. Không ghi nội dung credential ra báo cáo.
- Giữ backend/target/portal-demo/**, .env, backend/config/*.credentials.txt và frontend/.local/portal-demo.json. Không chạy clean, seed hoặc khởi tạo lại database.
- Giữ node_modules và artifacts build. Các lệnh verify/build đã tái tạo JAR và frontend/dist theo quy trình bình thường.

## Kiểm chứng đã đạt

| Kiểm tra | Kết quả |
| --- | --- |
| Frontend npm.cmd test | 19 file test, 105 test đạt |
| Frontend npm.cmd run build | TypeScript + Vite production build thành công |
| Backend mvnw.cmd --batch-mode verify | 63 test đạt, đóng gói executable JAR thành công |
| Parse PowerShell trong scripts/ và backend/scripts/ | Không có lỗi cú pháp |
| docker compose config --quiet | Hợp lệ, không xuất secrets |
| Rà import tương đối frontend, gồm asset ?url | Không có tham chiếu không resolve được |
| Sáu lượt di chuyển và liên kết Markdown tài liệu liên quan | Đường dẫn đích tồn tại, đường dẫn nguồn đã bỏ |
| git check-ignore --no-index | Chặn file local/generated; giữ .env.example, source, migration và script mới |
| git diff --check | Đạt |

## Giới hạn kiểm chứng

Docker Desktop chưa chạy; docker compose ps không kết nối được pipe dockerDesktopLinuxEngine. Chưa chạy integration profile, hai script kiểm thử Docker hoặc E2E/smoke test đăng nhập/chuyển tiền trên toàn stack. Không tự bật tunnel hoặc thao tác dữ liệu demo để kiểm chứng. Backend verify mặc định không bao gồm toàn bộ *IT.

Vite có cảnh báo chunk PDF lớn hơn 500 kB; build vẫn thành công. Maven có cảnh báo metadata 401 ở repository phụ; dependency đã resolve và verify thành công. Các cảnh báo này không phải lỗi do di chuyển source.

## Lệnh chạy sau thay đổi

Từ thư mục gốc BankingPrj, khởi động public demo bằng lệnh đã cập nhật:

```powershell
.\scripts\Start-PublicDemo.ps1 -PublicUrl "https://unselect-rebate-tall.ngrok-free.dev"
```

Cần mở Docker Desktop trước. Các lệnh Initialize-Docker và Seed-DockerDemo cũng đã chuyển vào scripts/; không cần chạy lại chúng chỉ vì dọn workspace. Xem [README](../../README.md), [cấu trúc hiện tại](../architecture/project-structure.vi.md) và [manifest kế hoạch](../architecture/workspace-cleanup-plan.vi.md).
