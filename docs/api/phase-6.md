# Phase 6 — Hạn mức, idempotency, audit và người thụ hưởng

## Mục đích

Retry sau mất kết nối không được chuyển tiền lần hai. Các request khác key vẫn
phải tuân hạn mức và quyền sở hữu. Audit giúp tra cứu ai thực hiện thao tác,
kết quả và request ID; beneficiary giữ thời điểm tạo/xóa để làm evidence cho fraud.

## Chuyển tiền và retry

`POST /api/v1/transfers` cần Bearer CUSTOMER và header `Idempotency-Key`.
Key: `[A-Za-z0-9._:-]{1,128}`; UUID ngẫu nhiên là lựa chọn phù hợp. Một ý định
chuyển tiền có một key. Scope unique: `(user ID, POST:/api/v1/transfers:v1, key)`.

Backend chuẩn hóa UUID, amount scale 2, currency và description rồi SHA-256 trên
record có cấu trúc cố định. Thứ tự field JSON và `"1000"`/`"1000.00"` không tạo
yêu cầu khác. Description null/bỏ trống tương đương; chuỗi rỗng khác null.

| Tình huống | Kết quả |
| --- | --- |
| Thiếu/sai key | 400 INVALID_IDEMPOTENCY_KEY |
| Cùng actor/key/payload trong 7 ngày | 201, snapshot/Location gốc; Idempotency-Replayed: true |
| Cùng actor/key, payload khác | 409 IDEMPOTENCY_CONFLICT |
| Key đang bị transaction khác khóa quá thời gian | 503 ACCOUNT_BUSY, Retry-After: 1 |
| Record chưa hoàn tất bị lưu bất thường | 503 IDEMPOTENCY_BUSY, Retry-After: 1 |
| Lỗi nghiệp vụ hoặc lỗi xử lý trước commit | Rollback cả claim; retry được đánh giá lại |
| Key hết hạn | Có thể tạo lần chuyển mới, kể cả cùng payload |

Luồng: kiểm tra auth/ownership → atomic INSERT ON CONFLICT claim/expiry reuse →
khóa account theo UUID tăng dần → đọc lại state/check hạn mức → tiền + transaction
và đúng DEBIT/CREDIT + success audit + snapshot → commit. Unique index PostgreSQL
phân xử request đồng thời. Không catch unique violation rồi query trên transaction
đã aborted. JDBC và JPA dùng chung datasource/JpaTransactionManager; kiểm thử
fault injection xác nhận chúng cùng rollback.

Replay dùng snapshot đã che balance đối tác và kiểm tra lại principal/ownership.
Replay không tạo success audit mới hoặc dùng thêm quota. Nó trả balanceAfter lịch
sử của lần đầu; muốn số dư hiện tại phải đọc Account API.

Cửa sổ demo là **7 ngày kể từ claim**; replay không kéo dài expiry. Không có job
xóa key trong phase này. Expired record được thay snapshot khi tái sử dụng, nhưng
transaction và audit cũ vẫn giữ. Sau expiry hoặc khi người dùng định tạo key khác,
phải tra history và giải quyết kết quả chưa rõ trước khi gửi lại.

## Hạn mức theo tài khoản nguồn

- `GET /api/v1/me/transfer-limits?accountId={uuid}`: CUSTOMER sở hữu nguồn.
- `PUT /api/v1/accounts/{uuid}/transfer-limit`: ADMIN; body dưới đây.

```json
{"perTransferAmount":"100000000","dailyAmount":"500000000"}
```

Chỉ nhận JSON string số VND nguyên dương, tối đa 17 chữ số nguyên, phần lẻ nếu
có chỉ `.0`/`.00`; per-transfer ≤ daily. Không nhận `currency` từ client, chỉ VND.
Nguồn CLOSED không đổi limit; FROZEN có thể cấu hình để áp dụng khi mở lại.

Chưa cấu hình account: mặc định **100.000.000/lần, 500.000.000/ngày**, tham số
`banking.transfer.default-per-transfer` và `default-daily`. Đây là chính sách demo,
không phải hạn mức chính thức của Agribank. Đổi mặc định cần khởi động lại ứng dụng.

Limit scope là **mỗi nguồn**, không phải tổng customer. Ngày theo
`Asia/Ho_Chi_Minh`, khoảng `[00:00,00:00 ngày sau)`. Tổng từ PostgreSQL chỉ gồm
INTERNAL_TRANSFER COMPLETED, loại OPENING. Ngày kiểm tra và transaction.createdAt
dùng cùng Clock.instant sau khi đã có cả hai khóa; request chờ qua nửa đêm dùng ngày
mới. Admin update cũng khóa source. Mọi transfer từ cùng nguồn được serialize nên
không cùng vượt daily quota; không dùng Redis làm quyết định tiền.

GET trả `accountId`, `perTransferAmount`, `dailyAmount`, `dailyUsed`, `dailyRemaining`,
`currency`, `date`, `timezone`, `defaultApplied`. Amount là string scale 2. GET chỉ
là thông tin tại lúc đọc; POST vẫn kiểm tra lại dưới khóa. Nếu admin hạ daily xuống
dưới số đã dùng, remaining trả 0 và không cho thêm transfer trong ngày.

## Người thụ hưởng

Tất cả API sau dành cho CUSTOMER, owner lấy từ principal:

| Method | Route | Body/kết quả |
| --- | --- | --- |
| POST | /api/v1/me/beneficiaries | destinationAccountId, nickname tùy chọn ≤100 ký tự; 201 + Location |
| GET | /api/v1/me/beneficiaries?page=0&size=20 | PageResponse của mình, createdAt/id giảm dần |
| GET | /api/v1/me/beneficiaries/{id} | Detail của mình |
| PATCH | /api/v1/me/beneficiaries/{id} | nickname; null/xóa trắng để bỏ tên gợi nhớ |
| DELETE | /api/v1/me/beneficiaries/{id} | 204, soft delete |

Từ Phase 7, response có `id`, `destinationAccountId`, `destinationAccountNumber`,
`nickname`, `createdAt`; không lộ
balance/identity đối tác. Không cho đổi destination qua PATCH. Owner/destination
active pair trùng trả 409 BENEFICIARY_CONFLICT, kể cả concurrent create.
Ngoài ownership hoặc đã xóa trả 404 BENEFICIARY_NOT_FOUND. Page size 1–100.

Xóa chỉ ghi deleted_at, không xóa account, transaction hoặc bản ghi lịch sử.
Tạo lại cùng destination có ID/createdAt mới. Khi fraud xem giao dịch cũ, dùng
khoảng `[created_at,deleted_at)` của quan hệ, không chỉ nhìn danh sách đang active.
Beneficiary không cấp quyền debit; chuyển tiền vẫn kiểm tra quyền nguồn độc lập.

## Audit và login history

`GET /api/v1/audit-logs?page=0&size=20` dành cho ADMIN (toàn bộ) hoặc BANK_STAFF
(luôn bị giới hạn actor_id là chính mình, cả khi thêm filter khác).
Filter tùy chọn: `actorId`, `action` enum, `outcome=SUCCESS|FAILURE`, `from`, `to`
Instant có timezone theo khoảng `[from,to)`. Sắp createdAt/id giảm dần, size 1–100.
CUSTOMER/RISK không truy cập được. Không có API sửa/xóa audit.

Audit chứa id/actor/action/target/outcome/requestId/details/createdAt. Các mutation
Customer, Account, User/roles/status, limit, beneficiary và transfer thành công
ghi audit trong transaction thay đổi. Ghi audit thất bại khiến mutation rollback.
No-op đổi cùng trạng thái account không tạo sự kiện thay đổi mới.

TransferFacade bắt lỗi **sau khi core proxy rollback**, rồi FailureAuditWriter
REQUIRES_NEW lưu TRANSFER_FAILED với mã lỗi an toàn. Facade không chấp nhận caller
đang có transaction (Propagation.NEVER), tránh ghi failure trước outer rollback.
Request sai JSON/Bean Validation, JWT hoặc quyền role bị chặn trước facade không
được ghi thành transfer business-failure audit.

AuthVerificationService kiểm tra password trong read transaction; AuthService
ghi LOGIN_SUCCEEDED/FAILED và login_history trong transaction riêng sau đó.
Login thành công chỉ trả token sau khi history/audit commit. Login thất bại không
gắn actor đã xác thực: actor/user_id null, vẫn có request ID và fingerprint.
Username chuẩn hóa được HMAC-SHA256 bằng key dẫn xuất riêng miền từ JWT secret;
không lưu username thô vào login_history. Đổi JWT secret làm đổi fingerprint tương lai.

Metadata chỉ dùng enum, ID, code, giá trị limit và cờ thay đổi; không copy password,
hash password, access token, login body, transfer description, nickname hoặc
idempotency key vào audit. Body snapshot idempotency vẫn chứa mô tả giao dịch
vì cần replay response gốc; phải áp dụng chính sách lưu trữ/DB access khi triển khai.

Nếu DB không thể ghi cả failure audit, request vẫn trả lỗi gốc và application log
chỉ ghi loại exception/request ID. Không thể bảo đảm audit bền vững khi chính DB
đang ngừng hoạt động. Audit hiện append-only qua ứng dụng; chưa chống sửa bởi DB
superuser hay có chính sách lưu trữ hạ tầng chuyên dụng.

## Schema và chạy kiểm thử

Năm bảng đã tồn tại trong Flyway V1: transfer_limits, beneficiaries, audit_logs,
login_history, idempotency_keys. Không chỉnh V1 hoặc tạo lại migration V5 tham khảo
trong roadmap. Không cần migration mới cho phase này.

Từ backend chạy `.\scripts\Test-Postgres.ps1`, dùng cluster PostgreSQL riêng và
database banking_test. Bộ test cũ vẫn chạy cùng các test Phase 6. Tiền/fixture
chỉ ở database test. Database banking_db không được seed tiền hoặc customer demo.
Ví dụ request: [phase-6.http](phase-6.http). Kết quả: [báo cáo Phase 6](../reports/phase-6-report.vi.md).
