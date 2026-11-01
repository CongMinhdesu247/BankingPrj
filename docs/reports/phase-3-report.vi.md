# Phase 3 — Chuyển tiền nội bộ, ledger và locking

Ngày thực hiện: 27/10/2026.

## Mục đích và tác dụng

Đảm bảo một lần chuyển tiền hoặc hoàn tất cả hai phía cùng ledger, hoặc rollback
toàn bộ. Khóa tài khoản giải quyết cạnh tranh giữa nhiều request; ledger giải
thích biến động và cho phép đối chiếu số dư. Đây là phần nền tảng trước security,
idempotency, hạn mức và fraud.

## Đã triển khai

- POST `/api/v1/transfers`: chuyển VND, không phí/thấu chi, amount là JSON string.
- GET `/api/v1/transactions/{id}`: giao dịch và đúng cặp entry.
- GET `/api/v1/accounts/{id}/transactions`: phân trang, cả nguồn/đích, lọc `[from,to)`.
- BankTransaction, TransactionEntry immutable ở ORM, enum, repository, DTO và mapper.
- TransferService chạy qua Spring proxy với READ_COMMITTED, transaction timeout 15 giây.
- TransactionLedgerWriter yêu cầu transaction có sẵn và ghi DEBIT/CREDIT.
- AccountLockService dùng chung cho transfer và đổi trạng thái account.
- Hai lần khóa/refresh riêng theo UUID chuẩn tăng dần, tương đương unsigned order
  của PostgreSQL; không dùng phép so sánh signed long trong UUID.compareTo.
- Refresh trực tiếp với PESSIMISTIC_WRITE để đọc state mới kể cả entity đã được load.
- Debit/credit nội bộ kiểm tra entity đang được giữ write lock; không có setter balance.
- Check ACTIVE, VND, tiền đủ và trần số dư sau khi có cả hai khóa.
- Lock timeout mặc định 2000 ms mỗi lần chờ; lỗi timeout/conflict trả 503 ACCOUNT_BUSY.
- Không làm tròn amount, không nhận JSON number, không nhận phần lẻ VND/scale > 2.
- Timestamp giao dịch được chuẩn hóa microsecond để POST/GET và date filter nhất quán.

## Database và dữ liệu mở sổ

Không sửa V1, không thêm migration mới: transactions và transaction_entries đã
có FK, UNIQUE và CHECK cần thiết. Banking_db vẫn ở baseline version 1.
Hibernate validate đầy đủ bốn entity Customer/Account/BankTransaction/TransactionEntry.

Fixture cấp tiền nằm hoàn toàn trong src/test và @TestConfiguration. Database
test riêng được guard theo JDBC URL và tên banking_test. Chứng từ mở sổ treasury
là 10.000.000 VND; fixture khóa treasury trước khi ghi reserve, rồi phân bổ cho A
1.000.000 qua OPENING cùng DEBIT/CREDIT. B bắt đầu 0, treasury còn 9.000.000.
Không có API nạp tiền hoặc mã seed/failpoint được bật trong runtime bình thường.

## Kết quả kiểm thử

`backend/scripts/Test-Postgres.ps1` đã hoàn thành `mvnw.cmd -P integration verify`
trên PostgreSQL 18.6 thật, cluster tạm riêng, rồi dừng cluster.

| Bộ test | Số test | Kết quả |
| --- | --- | --- |
| CustomerServiceTest | 4 | Đạt |
| AccountServiceTest | 5 | Đạt |
| AccountLockServiceTest | 2 | Đạt |
| CustomerApiIT | 7 | Đạt |
| AccountApiIT | 10 | Đạt |
| TransferApiIT | 4 | Đạt |
| TransferConcurrencyIT | 6 | Đạt |
| TransferRollbackIT | 2 | Đạt |
| Tổng | 40 | Không failure/error/skipped |

Các bằng chứng chính:

1. Chuyển 200.000 từ A (1.000.000) sang B (0) cho kết quả 800.000/200.000;
   response/DB có đúng DEBIT/CREDIT, amount/currency/account và balanceAfter đúng.
2. Thiếu tiền, tự chuyển, thiếu tài khoản, FROZEN/CLOSED, amount/scale/currency sai
   không làm thay đổi tiền hoặc tạo transaction thành công.
3. Fault injection chỉ trong test: sau debit, flush và query PostgreSQL xác nhận
   nguồn thực sự đã xuống 800.000, rồi mới throw. Sau rollback, balance/version/
   updated_at của tất cả account trở lại snapshot trước request; ledger không dư.
4. Fault injection sau entry đầu: query PostgreSQL trong transaction xác nhận
   entry đã tồn tại, rồi throw. Cả hai balance, transaction và entry đều rollback.
5. Hai worker HTTP có barrier, connection/transaction riêng cùng chuyển 700.000:
   đúng một thành công, một INSUFFICIENT_FUNDS; A=300.000, B=700.000.
6. A→B và B→A cùng lúc qua 3 vòng đều thành công, không deadlock; balance hai bên
   giữ nguyên cuối mỗi cặp, tổng tiền bảo toàn, tất cả entry cân.
7. Close account đích cùng lúc transfer: hoặc CLOSED và transfer bị từ chối,
   hoặc nhận tiền trước và close bị từ chối; không có CLOSED còn tiền.
8. Giữ row lock ở connection riêng: request hết lock timeout và trả 503 trong
   giới hạn; rollback sạch, sau khi nhả khóa request mới chạy được.
9. Worker cố ý load entity trước; transaction khác freeze hoặc chuyển đi 900.000;
   worker lấy khóa sau phải thấy state mới và từ chối đúng ACCOUNT_NOT_ACTIVE
   hoặc INSUFFICIENT_FUNDS. Không quyết định trên persistence context cũ.
10. Sau success/rollback/concurrency, query SQL đối chiếu đủ cặp, debit=credit,
    đúng nguồn/đích, balance=opening reserve+credit−debit, tổng tiền=10.000.000.

Đây là test concurrency có đồng bộ và bounded wait, không phải benchmark tải lớn
hoặc chứng nhận mọi lịch trình đồng thời có thể có. UNIQUE ở DB chặn entry trùng;
việc đủ cặp và khớp transaction hiện được bảo đảm qua service và test, chưa có
deferred constraint trigger chống mọi thao tác SQL ngoài ứng dụng.

## Kiểm tra trên máy hiện tại

Backend đã khởi động lại trên `127.0.0.1:8080` bằng JAR mới; PID lưu ở
`backend/target/backend-run.pid`. Log ở `backend/target/backend-run.*.log`.
Flyway báo schema up to date, Hibernate validate thành công.

- Customer GET trả 200.
- Transaction GET UUID không tồn tại trả 404 TRANSACTION_NOT_FOUND với request ID đúng.
- Transfer POST body rỗng trả 400, xác nhận route và validation đang hoạt động.
- Kiểm tra DB: customers/accounts/transactions/entries vẫn 0; không ghi tiền hoặc
  fixture vào banking_db. Giao dịch thành công được kiểm tra trên database test riêng.

## Tài liệu và bước tiếp theo

Xem [contract API](../api/transfers.md), [request mẫu](../api/transfers.http),
[sequence diagram](../architecture/transfer-sequence.md),
[SQL kiểm tra ledger chỉ đọc](../database/ledger-checks.sql).
UUID fixture trong test không tự tồn tại ở banking_db; account mới ở runtime vẫn 0 tiền.

Chưa triển khai auth/ownership, idempotency, hạn mức, Kafka/outbox, fraud,
reversal hoặc frontend chuyển tiền. POST chưa chống gửi lại sau commit.
Phase 4 sẽ rà soát và chuẩn hóa validation/error/API contract toàn hệ thống,
bổ sung OpenAPI và các trường hợp lỗi còn thiếu; không viết lại luồng tiền đã kiểm thử.
