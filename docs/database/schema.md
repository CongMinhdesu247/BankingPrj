# Database và Flyway

## Mục đích và tác dụng

Migration là lịch sử thay đổi schema có phiên bản, giúp máy khác dựng lại cùng
database và phát hiện script đã bị sửa. Hibernate `validate` chỉ kiểm tra mapping
entity khi khởi động; Flyway là nơi quản lý DDL.

## Điểm xuất phát thực tế

Ngày 27/10/2026, file tham khảo `database-framework.sql` đã được chạy thủ công,
tạo 20 bảng cùng 4 role trong `banking_db.public`. Vì tất cả các bảng đã tồn tại,
migration khởi đầu thực tế là **V1__initial_schema.sql**, chứa toàn bộ schema này.
Các nhãn V1–V8 trong file tham khảo/roadmap cũ thể hiện nhóm chức năng dự kiến,
không phải phiên bản migration thực tế đã áp dụng.

Sau V1, mọi thay đổi dùng **V2, V3, ...** theo thứ tự thực hiện. Không tạo lại
Account/User/Fraud table trong các phase sau nếu V1 đã có bảng phù hợp; chỉ bổ
sung migration khi cần thay đổi thật. Việc bảng đã tồn tại không đồng nghĩa các
nghiệp vụ tương ứng đã được triển khai.

Nguồn schema chính thức: `backend/src/main/resources/db/migration/`.
`database-framework.sql` chỉ giữ làm tham khảo; không chạy lại trên DB đã quản lý.
Không tự tạo hoặc sửa dữ liệu trong `flyway_schema_history` bằng SQL thủ công.

**Phase 9, 28/10/2026:** migration tiếp theo thực tế là
`V2__fraud_analysis_snapshots.sql`. V1 đã có fraud_rules, fraud_analysis_runs,
fraud_alerts và matches nên không tạo lại các bảng theo nhãn V6 đề xuất.
V2 thêm transactions.initiated_by (backfill từ idempotency/audit khi có),
rule_snapshot/context_snapshot của analysis_run và index quét recovery.
Không sửa tiền/ledger, không chèn rule hoặc dữ liệu demo. Bốn rule mặc định
ở code, chỉ lưu override khi ADMIN cập nhật. Tại Phase 9, database trống chạy
V1 rồi V2; database đã tiếp nhận V1 chỉ chạy V2. Xem [API Phase 9](../api/phase-9.md).

**Phase 10, 28/10/2026:** `V3__event_receipts_and_notification_metadata.sql`
bổ sung metadata receipts/attempts và index, không seed dữ liệu. Database trống
chạy V1–V3; database V2 chỉ áp dụng V3. Receipt consumer và notification dùng các
bảng có sẵn. Xem [báo cáo Phase 10](../reports/phase-10-report.vi.md).

## Hai cách khởi tạo

### Database trống

Trỏ datasource vào một database PostgreSQL dev mới đã được tạo. Khởi động backend:
Flyway chạy V1 và ghi checksum; sau đó Hibernate validate entity Customer.
Không cần chạy file framework trước. Flyway không tự tạo database PostgreSQL.

### Database có sẵn 20 bảng

Quy trình đã dùng để tiếp nhận `banking_db`:

1. Kiểm tra schema và dữ liệu; không xóa/reset database.
2. Sao lưu toàn bộ DB bằng `pg_dump -Fc`, kiểm tra archive bằng `pg_restore --list`.
3. Dựng database PostgreSQL riêng từ V1 bằng Flyway.
4. So sánh schema-only dump (bỏ owner/ACL, metadata header và history Flyway).
5. Chỉ khi không có khác biệt, chạy baseline tường minh ở version 1.
6. Validate và khởi động ứng dụng để kiểm tra mapping.

Baseline ghi nhận schema hiện có ở V1; không thực thi lại SQL V1, không tạo lại
bảng hay seed role. Dòng baseline không mang checksum của SQL V1; vì vậy bước
đối chiếu trước baseline là cần thiết. Với database tạo mới, V1 có checksum SQL.

Plugin Flyway Maven lấy `DB_URL` (JDBC URL), `DB_USERNAME`, `DB_PASSWORD` từ
environment của process; không tự đọc file local Spring. Chỉ sau khi hoàn tất
các bước đối chiếu/sao lưu trên mới dùng lệnh quản trị một lần, từ `backend/`:

```powershell
.\mvnw.cmd '-Dflyway.baselineVersion=1' flyway:baseline
.\mvnw.cmd flyway:validate
```

Không thêm mật khẩu vào command line hoặc commit file có thông tin kết nối.
`baseline-on-migrate` luôn false, `clean-disabled` luôn true. Không bật baseline
tự động để bỏ qua lỗi schema không rỗng. Backup local nằm trong
`backend/local-backups/` đã được loại khỏi Git.

## Phạm vi mapping hiện tại

Customer là entity đầu tiên. Các cột: UUID id; customer_code unique; full_name;
email nullable, chuẩn hóa chữ thường, unique; phone; status ACTIVE/INACTIVE;
created_at/updated_at TIMESTAMPTZ. Index `(created_at,id)` phục vụ phân trang.
JPA cập nhật timestamps trong lifecycle callback, không có trigger tự cập nhật.

Account đã được map ở phase 2: ManyToOne lazy một chiều tới Customer, không cascade;
balance BigDecimal precision 19 scale 2; trạng thái ACTIVE/FROZEN/CLOSED; @Version
cho metadata và khóa PESSIMISTIC_WRITE khi đổi trạng thái. Không thêm migration
vì accounts trong V1 đã khớp. API trả balance dạng chuỗi; không nhận balance từ client.

BankTransaction và TransactionEntry đã được map ở phase 3, dữ liệu ghi qua
TransferService trong cùng transaction với balance. Hai bảng đã có trong V1;
phase 3 không sửa V1 hoặc thêm migration rỗng. AccountLockService dùng chung
cho chuyển tiền và đổi trạng thái; xem [Transfer API](../api/transfers.md).

User và Role đã được map ở phase 5, liên kết user_roles không cascade; customer_id
nullable unique, role/ownership kiểm tra ở backend, password lưu hash PBKDF2.
Không sửa V1 vì ba bảng đã tồn tại. Bootstrap dev chỉ tạo ADMIN khi users trống.

Phase 9 thêm V2 cho fraud analysis/snapshot và initiated_by bền vững. Phase 10
thêm V3 cho processed_events.event_type/payload_hash, transfer_attempts.source_account_id,
unique success attempt theo transaction và index notification chưa đọc. Ba bảng
processed_events, transfer_attempts, notifications đã có trong V1; V3 không tạo
lại bảng hoặc seed dữ liệu. Receipt và consumer effect commit cùng transaction.
Outbox chưa triển khai (Phase 11). Hibernate chỉ validate entity
được map; nó không chứng minh toàn bộ 20 bảng hoặc invariant nghiệp vụ đều đúng.

## Kiểm thử

Từ `backend/`:

```powershell
.\mvnw.cmd test
.\scripts\Test-Postgres.ps1
```

Lệnh đầu chạy unit tests, không cần database. Lệnh thứ hai tạo PostgreSQL cluster
tạm ở cổng loopback ngẫu nhiên bằng các binary PostgreSQL cài sẵn, tạo
`banking_test`, chạy `-P integration verify`, rồi dừng cluster trong finally.
Mặc định dùng `C:\Program Files\PostgreSQL\18\bin`; có thể truyền
`-PostgreSqlBin` nếu cài ở nơi khác. Không cần Docker cho bộ test phase này.

Database test dùng trust authentication trên loopback chỉ trong cluster tạm,
không thay đổi cấu hình instance PostgreSQL cá nhân. Artifacts test nằm trong
`backend/target/` và được loại khỏi Git. Test HTTP từ chối chạy nếu TEST_DB_URL
không trỏ tới database local tên `banking_test`. Fixtures không ghi vào banking_db.
