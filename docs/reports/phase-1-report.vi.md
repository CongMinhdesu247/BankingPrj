# Phase 1 — Flyway và Customer

Ngày thực hiện: 27/10/2026.

## Mục đích của bước này

Biến bộ bảng SQL đã có thành nền tảng backend có thể chạy lại, kiểm tra và mở
rộng. Flyway ghi nhận phiên bản schema; Customer cung cấp dữ liệu chủ thể sở hữu
tài khoản để triển khai Account, chuyển tiền và ownership ở các phase sau.

## Đã triển khai

- Flyway starter và PostgreSQL module theo phiên bản Spring Boot 4.1.2 đang dùng.
- Migration `V1__initial_schema.sql` tái tạo toàn bộ 20 bảng đã tạo thủ công.
- Hibernate chuyển từ `update` sang `validate`; tắt Open Session in View và log SQL.
- Customer entity, enum, repository, service, mapper, request/response DTO, controller.
- POST tạo; GET chi tiết; GET tìm kiếm/phân trang; PATCH thông tin liên hệ.
- Validation, lỗi JSON thống nhất, request ID trên HTTP và log.
- Backend mặc định bind `127.0.0.1`, vì phase này chưa có authentication.
- Unit tests và HTTP integration tests với PostgreSQL riêng, không dùng DB cá nhân.

## Tiếp nhận database đang có

`banking_db` ban đầu có 20 bảng, 4 role; chưa có customer/account/transaction/user.
Đã sao lưu tại `backend/local-backups/before-flyway-20261027/banking_db.dump`
và xác nhận archive có thể đọc bằng `pg_restore --list`.

Schema-only dump của `banking_db` khớp schema tạo từ V1 bằng Flyway trên cluster
test, sau khi bỏ owner/ACL, header dump và bảng history của Flyway. Bản đối chiếu
được giữ cùng thư mục backup. Sau đó mới baseline tường minh version 1 và validate.
Không xóa bảng, không bật baseline tự động và không tạo dữ liệu demo vào banking_db.
Database hiện có thêm `flyway_schema_history`, tức 20 bảng nghiệp vụ + 1 bảng kỹ thuật.

## Bằng chứng kiểm thử

Lệnh `backend/scripts/Test-Postgres.ps1` hoàn thành `mvnw.cmd -P integration verify`
với **4 unit test và 7 integration test**, không failure/error/skipped.
Lần chạy cuối đã bao gồm cấu hình bind loopback và cấu hình log cuối cùng.

Các trường hợp được kiểm tra:

- V1 chạy từ database trống, seed đủ 4 role; migrate lại không thực thi thêm DDL.
- Khởi động đầy đủ Spring context, Hibernate validate thành công.
- Tạo/đọc/cập nhật/xóa thông tin liên hệ; không xóa Customer.
- Chuẩn hóa mã/email; trùng mã/email; PATCH xung đột được rollback.
- Hai POST cùng định danh đồng thời chỉ tạo đúng một Customer.
- Phân trang có tie-break UUID khi created_at bằng nhau; search wildcard được escape.
- Body thiếu/sai field, JSON hỏng, field không hỗ trợ, UUID sai, size/page sai,
  customer không tồn tại, PATCH rỗng, HTTP method không hỗ trợ.
- Constraint email chuẩn hóa và FK Account–Customer trên PostgreSQL thật.

Đã khởi động backend với `banking_db` thật sau baseline: Flyway báo schema v1
đã cập nhật, Hibernate validate thành công. Request chỉ đọc
`GET http://127.0.0.1:8080/api/v1/customers?page=0&size=20` trả HTTP 200,
header request ID đúng và danh sách rỗng. Đây là kết quả đúng vì chưa thêm
khách hàng mẫu. Bộ test sử dụng fixture trong database riêng.

## Cách sử dụng

Backend đã được khởi động nền cho lần bàn giao này. PID được lưu trong
`backend/target/backend-run.pid`; stdout/stderr ở `backend/target/backend-run.*.log`.
Có thể xem PID trong Task Manager trước khi dừng đúng tiến trình Java đó nếu muốn
tự chạy backend trong terminal. Không chạy đồng thời hai backend trên port 8080.

Những lần khởi động sau, từ `backend/`:

```powershell
.\mvnw.cmd spring-boot:run
```

Kiểm thử unit: `.\mvnw.cmd test`.
Kiểm thử đầy đủ trên PostgreSQL tạm: `.\scripts\Test-Postgres.ps1`.

Xem [Customer API](../api/customers.md), [request mẫu](../api/customers.http)
và [quản lý schema](../database/schema.md).

## Phạm vi còn lại

Frontend chưa tích hợp API; chưa có Swagger UI, JWT, Account service, transfer,
Redis, Kafka hoặc xử lý fraud. Bốn role hiện chỉ là dữ liệu cấu hình; chưa có
user đăng nhập. Đây là backend Customer local, chưa phải hệ thống chuyển tiền.

Bước tiếp theo: triển khai Account gắn Customer, số tài khoản unique, balance 0
khi tạo, trạng thái ACTIVE/FROZEN/CLOSED và quy tắc không đóng tài khoản còn tiền.
