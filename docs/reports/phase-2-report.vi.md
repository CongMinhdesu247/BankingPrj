# Phase 2 — Account gắn Customer

Ngày thực hiện: 27/10/2026.

## Mục đích và tác dụng

Account bổ sung tài khoản thuộc khách hàng, tạo nền tảng để xác định nguồn/đích
chuyển tiền và kiểm soát quyền sở hữu. Số tài khoản tách khỏi UUID nội bộ; số dư
và trạng thái có quy tắc trước khi triển khai các đường ghi tiền ở phase 3.

## Đã triển khai

- Account entity ánh xạ bảng accounts hiện có; ManyToOne lazy một chiều tới Customer,
  không cascade và không có collection ngược trên Customer.
- AccountStatus ACTIVE/FROZEN/CLOSED, repository, service, controller, mapper, DTO.
- API tạo tài khoản VND dưới Customer, xem chi tiết và phân trang theo Customer.
- CustomerService công bố kiểm tra Customer ACTIVE; Account không truy cập CustomerRepository.
- AccountNumberGenerator sinh số demo 13 chữ số; unique constraint ngăn trùng.
  Va chạm được rollback và trả lỗi riêng 409, đã được mô phỏng trong test.
- Mọi tài khoản mới có balance 0.00 VND và status ACTIVE. Không nhận balance,
  accountNumber hoặc trạng thái khởi tạo tùy ý từ client.
- Response trả balance dạng chuỗi, không đi qua double/float.
- Đổi trạng thái có PESSIMISTIC_WRITE trước kiểm tra nghiệp vụ, trong cùng transaction.
- Không đóng tài khoản có tiền, không mở lại CLOSED; request cùng trạng thái là no-op.
- @Version bảo vệ cập nhật metadata trên entity cũ; không thay thế row lock.
- Error handler phân biệt CUSTOMER_CONFLICT, ACCOUNT_NUMBER_CONFLICT và DATA_CONFLICT.

## Database

Không thay đổi V1 và không thêm migration mới: schema accounts đã có các cột,
FK, index, version và CHECK cần thiết. Backend khởi động với Hibernate validate
thành công. Flyway trên banking_db vẫn ở baseline version 1, không chạy thêm DDL.

Sau kiểm tra chỉ đọc trên banking_db: Customer và Account vẫn có 0 bản ghi, không
tự tạo dữ liệu demo trong DB cá nhân. Những fixture có tiền và Customer inactive
chỉ được tạo trong cluster PostgreSQL test riêng, đã được dừng sau kiểm thử.

## Bằng chứng kiểm thử

Lệnh `backend/scripts/Test-Postgres.ps1` đã hoàn thành `mvnw.cmd -P integration verify`:

| Bộ test | Số test | Kết quả |
| --- | --- | --- |
| CustomerServiceTest | 4 | Đạt |
| AccountServiceTest | 5 | Đạt |
| CustomerApiIT | 7 | Đạt |
| AccountApiIT | 10 | Đạt |
| Tổng | 26 | Không failure/error/skipped |

Các test Account bao gồm:

- Nhiều tài khoản cho một Customer, số tài khoản khác nhau, số dư 0, DTO không đệ quy.
- Đúng chủ tài khoản, phân trang ổn định khi created_at bằng nhau; trang 10 account
  chỉ dùng tối đa 3 SQL, không tăng truy vấn theo số account trong trang.
- Customer không tồn tại/inactive; Customer tồn tại chưa có account trả trang rỗng.
- ACTIVE ↔ FROZEN, đóng account 0 tiền, CLOSED không mở lại, no-op không tăng version.
- Từ chối đóng tài khoản có tiền; bảo toàn chuỗi số tiền lớn vượt độ chính xác JS Number.
- Body chứa balance/accountNumber, currency khác VND, enum/UUID/phân trang sai.
- Cố ý ép generator trùng số, kiểm tra 409 và không có tài khoản dư sau rollback.
- CHECK balance không âm, CHECK đóng tài khoản hết tiền và FK không cascade xóa Customer.
- Giữ row lock ở connection riêng, quan sát request PATCH thật chờ Lock trong
  pg_stat_activity; sau khi nhả khóa, request hoàn thành đúng.
- Hai request close/freeze đồng thời cuối cùng vẫn CLOSED, không phục hồi trạng thái cũ.

Đã khởi động lại backend với banking_db thật trên `127.0.0.1:8080`.
GET Customer trả 200; GET Account bằng UUID không tồn tại trả 404 ACCOUNT_NOT_FOUND
với request ID đúng. Đây là kết quả có chủ đích, xác nhận route Account đang hoạt động.

## Cách thử

Xem [Account API](../api/accounts.md) và [request mẫu](../api/accounts.http).
Tạo Customer → copy customerId → mở Account → copy accountId → đọc và đổi trạng thái.
Gửi POST tạo Account lần nữa có thể mở thêm tài khoản; idempotency chưa thuộc phase này.

Backend đang chạy nền; PID hiện tại lưu trong `backend/target/backend-run.pid`,
log ở `backend/target/backend-run.*.log`. Trên Windows cần dừng backend đang dùng
file JAR trước khi chạy lại verify/package, vì bước đóng gói phải thay file đó.
Sau khi dừng, có thể tự chạy từ backend/ bằng `.\mvnw.cmd spring-boot:run`.

## Phần tiếp theo

Phase 3: chuyển tiền nội bộ, khóa hai tài khoản theo UUID tăng dần, kiểm tra
trạng thái/số dư sau khóa, ghi transaction + đúng cặp debit/credit và kiểm thử
rollback/concurrency trên PostgreSQL thật. Mọi đường đổi trạng thái/ghi số dư
phải phối hợp cùng cơ chế khóa account.

Chưa triển khai chuyển tiền, nạp/rút tiền qua HTTP, JWT/ownership, frontend Account,
Redis, Kafka hoặc xử lý fraud. Khi thêm API đổi trạng thái Customer, cần thiết kế
phối hợp với mở Account trong trường hợp hai request đồng thời.
