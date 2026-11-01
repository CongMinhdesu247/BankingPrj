# Phase 5 — Authentication, roles và ownership

## Mục đích và tác dụng

JWT xác định user thực hiện request; role xác định thao tác được phép; ownership
xác định dữ liệu Customer/Account/Transaction mà khách hàng được xem hoặc debit.
Ba lớp này được kiểm tra ở backend, không phụ thuộc menu hoặc body userId gửi lên.

## Đăng nhập và tài nguyên của mình

`POST /api/v1/auth/login` là endpoint công khai duy nhất có dữ liệu nghiệp vụ đầu vào:

```json
{"username": "your.username", "password": "your-configured-password"}
```

Username được strip và lowercase; password giữ nguyên, không trim. Thành công 200:

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {
    "id": "<user-uuid>",
    "username": "your.username",
    "enabled": true,
    "customerId": "<customer-uuid-or-null>",
    "roles": ["CUSTOMER"],
    "createdAt": "<ISO timestamp>",
    "updatedAt": "<ISO timestamp>"
  }
}
```

Response login có Cache-Control: no-store, không tạo session cookie. Hash password
không xuất hiện trong response. Sai password, user không có hoặc disabled đều
trả 401 INVALID_CREDENTIALS với cùng message. Không có đăng ký công khai.

Các API tiếp theo dùng `Authorization: Bearer <accessToken>`:

- `GET /api/v1/me`: UserResponse và CustomerResponse của chính user (nếu có link).
- `GET /api/v1/me/accounts?page=0&size=20`: CUSTOMER, tài khoản của mình.
- `GET /api/v1/me/transactions?page=0&size=20&from=...&to=...`: CUSTOMER,
  giao dịch liên quan tài khoản của mình, khoảng `[from,to)`.

customerId/accountId thêm vào query /me không đổi được chủ thể đang đăng nhập.
Token hết hạn phải đăng nhập lại; chưa có refresh token hoặc logout thu hồi token.

## Ma trận quyền hiện tại

| API/thao tác | CUSTOMER | BANK_STAFF | RISK_OFFICER | ADMIN |
| --- | --- | --- | --- | --- |
| /me | Chính mình | Chính mình | Chính mình | Chính mình |
| /me/accounts, /me/transactions | Chính mình | Không | Không | Không |
| Customer search/create/update/detail | Không, dùng /me | Có | Không | Có |
| Mở Account cho Customer | Không | Có | Không | Có |
| Account list theo Customer | Customer của mình | Có | Không | Có |
| Account detail/history | Account của mình | Có | Có | Có |
| Đổi trạng thái Account | Không | Có | Không | Có |
| Transaction detail | Có liên quan, che balance đối tác | Có | Có | Có |
| POST transfer | Chỉ debit account của mình | Không | Không | Không |
| Tra cứu người nhận (projection tối thiểu) | Có | Không | Không | Không |
| Users/roles/status | Không | Không | Không | Có |
| /me/transfer-limits | Nguồn của mình | Không | Không | Không |
| PUT Account transfer-limit | Không | Không | Không | Có |
| /me/beneficiaries CRUD | Của mình | Không | Không | Không |
| GET /audit-logs | Không | Chỉ actor của mình | Không | Toàn bộ, có bộ lọc |

Scope nội bộ của demo: STAFF/ADMIN tra cứu Customer; RISK chỉ có projection
Account/Transaction, không có liên hệ Customer, user admin hoặc quyền sửa tiền.
Chưa có alert/investigation endpoint; phase 9 sẽ bổ sung policy điều tra cụ thể.
ADMIN không tự có quyền debit tài khoản khách hàng.

Các API cũ cũng được bảo vệ; không còn đường /transfers không auth. Filter yêu cầu
xác thực cho /api/v1/**; method security và ownership áp dụng trước khi trả dữ liệu.
API không thuộc danh sách cho phép mặc định bị từ chối. Swagger/OpenAPI công khai
chỉ mô tả schema, không xuất dữ liệu DB hoặc secret, và hỗ trợ nút Authorize Bearer.

## Ownership và dữ liệu giao dịch

Customer truy cập Account/Transaction ngoài quyền nhận 404, với cùng code/message
như tài nguyên không tồn tại. GET theo Customer cũng kiểm tra customerId.
Transfer kiểm tra chủ tài khoản nguồn sau khi AccountLockService lấy khóa và refresh;
không lấy actor/user/customer identity từ body.

Khách hàng tham gia giao dịch được xem hai entry và amount, nhưng `balanceAfter`
của entry thuộc tài khoản đối tác là NULL. Chỉ số dư sau giao dịch của tài khoản
mình được trả. STAFF/RISK/ADMIN có quyền đọc nội bộ vẫn xem đầy đủ ledger DTO.
Redaction không sửa ledger hoặc số dư DB.

## Quản lý user (ADMIN)

- `POST /api/v1/users`: username, password, customerId tùy chọn, roles.
- `GET /api/v1/users?page=0&size=20`, `GET /api/v1/users/{id}`.
- `PATCH /api/v1/users/{id}/roles`: `{"roles":["BANK_STAFF"]}`.
- `PATCH /api/v1/users/{id}/status`: `{"enabled":false}`.
- `GET /api/v1/roles`: bốn role code cố định.

Username 3–100 ký tự `[a-z0-9._-]`, bắt đầu chữ/số, chuẩn hóa lowercase.
Password tạo user dài 12–128 ký tự. Hash dùng DelegatingPasswordEncoder với
PBKDF2 defaults Spring Security v5.8 (salt ngẫu nhiên, thuật toán có prefix phiên bản).
Không tự viết password hashing hoặc JWT crypto.

CUSTOMER phải là role duy nhất và có customerId tồn tại; mỗi Customer chỉ link
một User. User nội bộ không có customerId; có thể kết hợp các role nội bộ.
Link Customer và username không được đổi qua PATCH role/status. Vì vậy chuyển
một user nội bộ thành CUSTOMER không có link hợp lệ bị từ chối; tạo user riêng
cho Customer theo quy trình quản trị. Unknown role, roles rỗng, link thiếu/trùng
bị từ chối, không tự gán link cho Customer đang có sẵn.

## Semantics JWT và thay đổi quyền

Spring Security OAuth2 Resource Server/Nimbus xác minh HS256 bằng secret local,
issuer `banking-local`, audience `banking-api`, expiry/nbf và subject UUID chuẩn.
Các signed claim iss/sub/aud/iat/nbf/exp phải thực sự có trước chuyển đổi mặc định;
không chấp nhận iat do converter tự bổ sung. Clock skew timestamp theo default
Spring Security (60 giây). TTL mặc định 900 giây, cấu hình cho phép 1–3600 giây.

JWT mang user ID; các claim role do token gửi không cấp quyền. Mỗi request đọc
enabled/role hiện tại từ PostgreSQL, không cache authorization. Đổi role hoặc
disable có hiệu lực ở request kế tiếp với token cũ. Request đã được xác thực
và đang chạy có thể hoàn tất theo quyền ở đầu request. Re-enable cho phép dùng
lại token chưa hết hạn; chưa có token-version/blacklist hoặc thu hồi riêng từng token.

## Cấu hình local và bootstrap quản trị

JWT_SECRET là Base64 ít nhất 32 byte ngẫu nhiên. Có thể đặt environment hoặc
`banking.security.jwt.secret` trong backend/config/application-local.properties
đã được Git ignore. Thiếu/sai secret làm startup thất bại, không có secret mặc định
được commit. Với máy hiện tại secret local đã được sinh và lưu riêng.

Bootstrap chỉ chạy với profile dev và `BOOTSTRAP_ADMIN_ENABLED=true`, password
đọc từ `BOOTSTRAP_ADMIN_PASSWORD`, username mặc định admin (hoặc cấu hình riêng).
Chỉ khi bảng users trống mới tạo một ADMIN; có advisory transaction lock để
không seed hai lần giữa nhiều tiến trình. Không reset password hoặc thêm admin
vào DB đã có user. Bootstrap không có HTTP endpoint và mặc định tắt.

Trong lần bàn giao này thông tin quản trị local được lưu ở file credentials riêng
trong backend/config/ và được Git ignore; không có password/token trong tài liệu.
Không đưa file credentials, application-local.properties hoặc JWT secret lên Git.

Chạy lại backend bình thường từ backend/: `.\mvnw.cmd spring-boot:run`.
User đã lưu trong DB nên không cần bật bootstrap lại. Trên Windows dừng backend
đang chạy JAR trước verify/package để tránh khóa file JAR.

## Lỗi, CORS và kiểm thử

401 UNAUTHENTICATED khi thiếu/sai/hết hạn token, header WWW-Authenticate: Bearer.
403 FORBIDDEN khi role không được phép hoặc CORS bị từ chối. Cùng ApiError,
request ID và fieldErrors như lỗi MVC. CORS chỉ cho localhost:5173 và 127.0.0.1:5173,
không wildcard, không cookie credentials. Có thể cấu hình danh sách origin rõ ràng.

Kiểm thử `scripts/Test-Postgres.ps1` sinh signing key riêng cho mỗi cluster test.
Các test tiền cũ dùng user/token thật đi qua decoder và authorization; không có
security-disabled profile. Fault injection vẫn chỉ nằm trong src/test.

Phase 6 đã có audit quản trị, login_history HMAC và transfer idempotency; xem
[contract Phase 6](phase-6.md). Phase 8 đã có Redis login/transfer throttling;
xem [API Phase 8](phase-8.md). Phase 9 thêm route RISK_OFFICER cho hậu kiểm,
ADMIN cho cấu hình rule, BANK_STAFF/RISK_OFFICER/ADMIN cho tra cứu khách hàng nội bộ;
xem [API Phase 9](phase-9.md). ADMIN đơn lẻ không có quyền đọc/kết luận cảnh báo.
Chưa có MFA/OTP, refresh rotation, public registration hoặc SSO.

Phase 10: mọi user authenticated có GET `/api/v1/me/notifications` và PATCH
`/api/v1/me/notifications/{id}/read`, luôn scope recipient từ JWT/current user,
không nhận userId của client. Thông báo của người khác trả 404. Kafka/DLT/replay
không có HTTP endpoint; tools local operator được mô tả ở [Kafka playbook](../../infra/kafka/README.md).

Tài liệu gốc: [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
