# Phase 5 — JWT, RBAC, ownership và quản lý User

Ngày thực hiện: 27/10/2026.

## Mục đích và tác dụng

Xác định ai đang gọi API, người đó được làm thao tác gì và được truy cập tài nguyên
nào. Customer A không thể đọc/debit account B chỉ bằng cách đổi UUID hoặc gửi thêm
userId trong body. Role nội bộ được tách khỏi quyền chuyển tiền của khách hàng.

Phase 5 chỉ bắt đầu sau khi phase 4 đạt 45 test trên PostgreSQL thật.

## Đã triển khai

- User, Role, user_roles mapping với schema có sẵn; không thêm bảng hoặc sửa V1.
- PasswordEncoder Spring với PBKDF2 có salt và prefix phiên bản, không lưu plaintext.
- Login username/password, access token HS256 thời hạn mặc định 15 phút.
- Spring Security Resource Server/Nimbus xác minh chữ ký, issuer, audience,
  expiry, nbf và subject UUID; yêu cầu signed claim iss/sub/aud/iat/nbf/exp có thật.
- Không dùng iat do converter mặc định tự bổ sung để thỏa điều kiện claim bắt buộc.
- Mỗi request đọc enabled/role từ DB; claim role trong JWT không cấp quyền.
- SecurityFilterChain stateless, không form login/HTTP Basic/session-cookie auth.
- Request ID chạy trước security; 401/403/CORS cũng trả cùng ApiError như MVC.
- Method security trên controller và các thao tác ghi nhạy cảm ở service.
- Ownership trên Account/Transaction, /me theo principal, source ownership sau account lock.
- Che balanceAfter của entry đối tác khi trả transaction detail cho CUSTOMER.
- ADMIN quản lý user, roles, trạng thái; STAFF/RISK/ADMIN không tự được debit tiền.
- Swagger hỗ trợ Authorize Bearer, khai báo 401/403 và password write-only.
- CORS chỉ cho hai origin Vite local, không wildcard/cookie credentials.
- Bootstrap ADMIN opt-in chỉ trong dev, chỉ khi users trống, có advisory transaction lock.

CUSTOMER là role độc lập, bắt buộc có customer_id unique. User nội bộ không có
link Customer; role nội bộ có thể kết hợp. Không tự liên kết user với khách có sẵn.
Username/link Customer không đổi qua PATCH role/status.

## API và policy

API mới: POST /api/v1/auth/login; GET /api/v1/me, /me/accounts, /me/transactions;
POST/GET /api/v1/users; GET /users/{id}; PATCH /users/{id}/roles và /status;
GET /api/v1/roles.

Toàn bộ Customer/Account/Transfer API cũ đã được bảo vệ. Customer đọc account
ngoài quyền nhận cùng 404 code/message như account không tồn tại. Transaction
ngoài quyền tương tự. Transfer chỉ CUSTOMER debit nguồn của mình; nguồn/đích
vẫn được kiểm tra trạng thái/số dư sau cùng cơ chế khóa từ phase 3.

Role change hoặc disable có hiệu lực ở request kế tiếp với token cũ. Request
đang chạy có thể hoàn tất; re-enable cho phép token chưa hết hạn hoạt động lại.
Chưa có token blacklist/version, refresh token hoặc thu hồi từng token khi logout.
Các semantics này được ghi rõ trong [contract security](../api/auth-security.md).

## Kết quả kiểm thử cuối

Đã chạy `backend/scripts/Test-Postgres.ps1` → `mvnw.cmd -P integration verify`
với signing key ngẫu nhiên và PostgreSQL 18.6 cluster riêng:

| Bộ test | Số test | Kết quả |
| --- | --- | --- |
| AccountLockServiceTest | 2 | Đạt |
| AccountServiceTest | 5 | Đạt |
| CustomerServiceTest | 4 | Đạt |
| AccountApiIT | 10 | Đạt |
| CustomerApiIT | 7 | Đạt |
| ApiContractIT | 5 | Đạt |
| SecurityAuthorizationIT | 11 | Đạt |
| BootstrapAdminIT | 2 | Đạt |
| TransferApiIT | 4 | Đạt |
| TransferConcurrencyIT | 6 | Đạt |
| TransferRollbackIT | 2 | Đạt |
| Tổng | 58 | Không failure/error/skipped |

11 unit test + 47 integration test. Các test nghiệp vụ cũ gửi Bearer token hợp lệ
qua decoder/authorization thật; không có cấu hình tắt security để làm test pass.
Worker gọi service trực tiếp trong test concurrency cũng có principal xác thực.

Test security bao gồm:

- API nghiệp vụ cũ thiếu token đều 401; HTTP Basic không thay thế Bearer.
- Login đúng/sai/unknown/disabled, không lộ password/hash và không tạo session cookie.
- Token hết hạn, nbf tương lai, chữ ký/issuer/audience sai, subject sai,
  thiếu từng claim bắt buộc đều 401.
- Customer không đọc/debit tài khoản người khác; query /me không đổi được chủ thể.
- Staff không sửa role; Risk không đọc Customer hoặc đổi trạng thái; Admin không debit.
- Customer tạo transfer hợp lệ vẫn giữ cân ledger; balanceAfter đối tác bị che ở cả hai phía.
- ADMIN tạo user/password hash, chặn username/customer link trùng và role/link sai.
- Token cũ phản ánh role mới và bị chặn ngay ở request sau khi user disabled.
- JWT ký hợp lệ nhưng tự thêm role ADMIN không nâng quyền của Customer.
- CORS origin hợp lệ được phép, origin khác trả 403 theo ApiError.
- Bootstrap chỉ tạo một ADMIN khi users trống, không reset user/password, từ chối mật khẩu yếu.

Sau mỗi test transfer, vẫn đối chiếu debit=credit, đủ cặp entry, balance không âm,
opening+credit−debit=balance và tổng tiền bảo toàn. Rollback sau SQL debit/entry
đã flush vẫn đạt khi security bật.

## Trạng thái trên máy bàn giao

- Backend đang chạy nền tại 127.0.0.1:8080, profile dev; PID lưu target/backend-run.pid.
- Swagger UI và /v3/api-docs trả 200; OpenAPI YAML xuất tại docs/api/openapi.yaml.
- Đăng nhập admin thật trả 200; /me và /users bằng token thật trả 200.
- Request anonymous vào Customer trả 401; ADMIN gọi transfer trả 403.
- Signing key sinh ngẫu nhiên, lưu trong application-local.properties đã Git ignore.
- Mật khẩu admin sinh ngẫu nhiên, lưu tại backend/config/development-admin.credentials.txt
  đã Git ignore. Không đưa password/token vào tool output, code hoặc tài liệu.
- Trước bootstrap đã pg_dump và kiểm tra archive tại
  backend/local-backups/before-phase5-20261027/banking_db.dump, thư mục Git ignore.
- Banking_db hiện có 1 user admin/ADMIN enabled, hash PBKDF2; Customer, Account,
  transaction và entry vẫn 0. Không seed tiền hoặc giao dịch vào DB cá nhân.
- Flyway vẫn baseline version 1; Hibernate validate thành công với User/Role bổ sung.

## Cách dùng và phạm vi còn lại

Mở Swagger UI, POST /auth/login với credentials local, copy accessToken vào nút
Authorize. Xem [auth.http](../api/auth.http), [ma trận quyền](../api/auth-security.md),
[error codes](../api/error-codes.md), [OpenAPI snapshot](../api/openapi.yaml).
Các file request Customer/Account/Transfer đã thêm header Bearer placeholder.

Frontend chưa có login/portal bằng API thật (phase 7). Chưa có MFA, refresh,
rate limit, audit quản trị hoặc idempotency. Bước tiếp theo theo roadmap là
phase 6: transfer limit + audit + idempotency + beneficiary.
