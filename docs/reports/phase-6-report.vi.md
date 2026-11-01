# Báo cáo Phase 6 — 27/10/2026

## Mục đích và tác dụng trong dự án

Phase 3 bảo vệ tính đúng của một lần chuyển tiền. Phase 6 bổ sung bảo vệ khi
client retry, khi nhiều request cùng sử dụng hạn mức và khi cần truy vết thao tác.
Beneficiary và login history tạo dữ liệu có thời điểm để Phase 9 phân tích fraud.

## Kết quả triển khai

- **Idempotency:** bắt buộc Idempotency-Key cho transfer, scope actor/operation/key,
  canonical SHA-256 payload, PostgreSQL atomic upsert/unique index, replay snapshot
  gốc trong 7 ngày, 409 nếu payload khác. Ownership kiểm tra trước replay và sau khóa.
- **Hạn mức:** theo nguồn, per-transfer/daily, ngày Asia/Ho_Chi_Minh; mặc định demo
  100 triệu/lần và 500 triệu/ngày. ADMIN update dùng cùng khóa account với transfer.
- **Atomicity:** tiền, transaction, đúng cặp ledger, success audit và snapshot
  chống trùng cùng commit/rollback. Không lưu success claim riêng bằng filter.
- **Audit:** Customer, Account, User/roles/status, limit, beneficiary, transfer;
  business failure qua writer transaction riêng sau rollback. STAFF chỉ xem log
  có actor là mình; ADMIN xem/filter toàn bộ.
- **Login history:** đăng nhập thành công/thất bại ghi bền vững; fingerprint
  HMAC-SHA256 thay username thô, không ghi password/token vào audit/history.
- **Beneficiary:** CRUD thuộc principal, partial unique active owner/destination,
  soft delete giữ created_at/deleted_at; tạo lại có ID/thời điểm mới.

Các bảng đã có trong V1, không cần DDL mới. Không chỉnh V1, không tạo lại V5
tham khảo trong roadmap. Hibernate tiếp tục validate; Flyway tiếp tục quản lý schema.

## Luồng và ranh giới khóa

TransferController → TransferFacade (không cho outer transaction) → TransferService
READ_COMMITTED qua proxy → claim idempotency → khóa hai account theo canonical
UUID tăng dần → đọc lại state/ownership/limit → tiền/ledger/audit/snapshot → commit.

Không query tiếp trên transaction PostgreSQL đã aborted. Timeout rollback ra
ngoài rồi trả 503/Retry-After; client giữ nguyên key. Ngày limit và createdAt dùng
cùng Instant lấy sau khóa, tránh request chờ qua nửa đêm tính nhầm ngày.

JDBC dùng chung datasource và JpaTransactionManager với Hibernate. Kiểm thử gây
lỗi sau INSERT audit và UPDATE snapshot chứng minh cả SQL JDBC cùng balance/JPA
ledger rollback, không chỉ thay đổi trong bộ nhớ.

## Kiểm thử thực tế

Lệnh từ backend: `.\scripts\Test-Postgres.ps1`.
Java 21, Maven wrapper, PostgreSQL **18.6 thật**, cluster tạm độc lập,
database banking_test; áp V1 từ trống, đi qua HTTP/JWT/security thật.

**BUILD SUCCESS: 83 test — 11 unit + 72 integration; 0 failed/error/skipped.**

| Nhóm | Số test | Bằng chứng chính |
| --- | ---: | --- |
| Hồi quy phase 1–5 | 58 | Khách hàng, tài khoản, UUID lock order, tiền/ledger, rollback, JWT/roles/ownership, API contract |
| IdempotencyIT | 9 | Thiếu/sai key, canonical replay sau response bị bỏ, 4 duplicate đồng thời, conflict đồng thời, scope actor, rollback/retry, expiry, timeout unique claim |
| TransferLimitIT | 7 | Default/ownership, sát/vượt quota, nguồn dùng chung với hai đích khác, 23:59/00:00, request chờ khóa qua nửa đêm, ADMIN validation và khóa account |
| BeneficiaryAuditIT | 7 | CRUD/soft delete/recreate, ownership, concurrent duplicate, audit metadata/scope, login HMAC và audit quản trị user |
| PhaseSixRollbackIT | 2 | Fault sau audit SQL và sau snapshot SQL, rollback toàn bộ, retry cùng key thành công |

Hai test rollback cũ cũng bổ sung kiểm tra không giữ key/success audit sau lỗi
đã flush debit hoặc entry đầu tiên. Sau từng luồng tiền, kiểm tra invariant:
không âm; đúng hai entry/đúng account/amount/currency; debit=credit; số dư khớp
reserve mở sổ + ledger; tổng tiền test không đổi.

Kết quả máy nằm ở backend/target/surefire-reports và failsafe-reports (Git ignored).
Các dòng ERROR từ fault injection là lỗi được test chủ động gây ra, không phải
test thất bại. Cảnh báo metadata 401 ở repository phụ không chặn BUILD SUCCESS.

## Database local và cách xem

Đã sao lưu banking_db trước khi chạy bản Phase 6 tại
`backend/local-backups/banking_db-pre-phase6-20261027-204735.dump` (Git ignored).
Cluster integration đã dừng sau khi test. Dữ liệu tiền/fixture không ghi vào
banking_db: Customer/Account/Transaction/Entry vẫn rỗng; development admin cũ được giữ.

Backend bản Phase 6 đang chạy PID **13936**, bind `127.0.0.1:8080`, Swagger UI tại
`http://127.0.0.1:8080/swagger-ui/index.html`. Đăng nhập dùng credentials local trong
backend/config/development-admin.credentials.txt (Git ignored), rồi Authorize.
Không đưa password hoặc JWT vào báo cáo.

Smoke test local đã đạt: đăng nhập 200, audit có LOGIN_SUCCEEDED đúng request ID,
đọc audit không có token trả 401, ADMIN cập nhật limit của account không tồn tại
trả 404 ACCOUNT_NOT_FOUND, CORS preflight chấp nhận Idempotency-Key. Swagger
khai báo header bắt buộc, đủ năm route Phase 6 và đã xuất YAML mới.

Sau smoke test: **audit_logs=1, login_history=1**; Customer/Account/Transaction/Entry,
limit/beneficiary/idempotency đều 0. Trong DBeaver refresh
public → Tables → audit_logs/login_history để xem bản ghi đăng nhập thật.
Các bảng limit/beneficiary/idempotency chỉ có dữ liệu khi sử dụng nghiệp vụ;
phase này không tự seed customer, tài khoản hay tiền vào database cá nhân.

Xem [contract và giới hạn thiết kế](../api/phase-6.md), [request mẫu](../api/phase-6.http),
[OpenAPI snapshot](../api/openapi.yaml), [ma trận quyền](../api/auth-security.md).

## Phạm vi còn lại và bước tiếp

Replay bảo đảm trong 7 ngày, không vô hạn; key hết hạn có thể tạo transfer mới.
Failure audit không thể ghi nếu chính PostgreSQL ngừng hoạt động; log fallback
chỉ lưu loại lỗi/request ID. Audit hiện bảo vệ qua ứng dụng, chưa chống sửa bởi
DB superuser. Chưa có job purge key/history, Redis/rate limiting, hạn mức tổng
customer, quy trình phê duyệt nhiều người hoặc MFA.

Bước tiếp theo là **Phase 7: frontend dùng API thật** — login, danh sách tài khoản,
chuyển tiền với key gắn ý định người dùng, retry giữ key/payload, lịch sử, beneficiary
và hiển thị lỗi/hạn mức. Phase 7 chưa triển khai trong lần này.
