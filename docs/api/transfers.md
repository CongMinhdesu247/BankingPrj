# Chuyển tiền nội bộ và ledger — Phase 3

## Mục đích và tác dụng

Một lần chuyển tiền phải đồng thời trừ nguồn, cộng đích, ghi giao dịch và ghi
đủ cặp DEBIT/CREDIT. PostgreSQL transaction giữ tính nguyên tử; khóa account
giúp request đồng thời không quyết định dựa trên số dư/trạng thái cũ.
Ledger lưu bằng chứng biến động để đối chiếu với balance hiện tại.

Phạm vi: chuyển nội bộ VND tức thời, không phí/lãi/thấu chi. Đây là ledger đơn
giản cho portfolio. Từ phase 5, POST yêu cầu Bearer JWT role CUSTOMER và chỉ cho
debit nguồn thuộc customer đã đăng nhập. Backend vẫn chạy local.

## POST /api/v1/transfers

Từ Phase 6, bắt buộc header `Idempotency-Key` (1–128 ký tự ASCII chữ/số hoặc
`._:-`). Tạo key cho một ý định chuyển tiền và giữ nguyên khi retry trong 7 ngày.
Cùng actor/key và payload tương đương trả lại HTTP 201, Location và snapshot gốc,
header `Idempotency-Replayed: true`; key cũ khác payload trả 409.
Xem [hạn mức, idempotency, audit và beneficiary](phase-6.md).

```json
{
  "sourceAccountId": "80000000-0000-0000-0000-000000000002",
  "destinationAccountId": "10000000-0000-0000-0000-000000000003",
  "amount": "200000",
  "currency": "VND",
  "description": "Chuyển tiền demo"
}
```

Hai UUID phải khác nhau, tồn tại và có status ACTIVE. Amount bắt buộc là JSON
string; không nhận JSON number, số mũ, dấu âm, khoảng trắng hoặc phần lẻ VND.
Chấp nhận `"1"`, `"1.0"`, `"1.00"`; từ chối `"0"`, `"1.1"`, `"1.000"`.
Tối đa 17 chữ số phần nguyên và 2 chữ số thập phân bằng 0. Không làm tròn input.
Currency bắt buộc `VND`; description tùy chọn, tối đa 255 ký tự.
Không nhận type/status/reference do client chỉ định.

Thành công trả `201 Created`, Location `/api/v1/transactions/{id}`:

```json
{
  "transaction": {
    "id": "f0ea2769-dae3-48f9-93c5-a42bc6a771fd",
    "reference": "TRF-fdf45efc-9b72-49d8-8e21-1cf4ab77b714",
    "sourceAccountId": "80000000-0000-0000-0000-000000000002",
    "destinationAccountId": "10000000-0000-0000-0000-000000000003",
    "amount": "200000.00",
    "currency": "VND",
    "type": "INTERNAL_TRANSFER",
    "status": "COMPLETED",
    "description": "Chuyển tiền demo",
    "createdAt": "2026-10-27T07:00:00.123456Z"
  },
  "entries": [
    {
      "id": "391b11e0-a3e5-4aa5-96ed-08824e183f55",
      "accountId": "80000000-0000-0000-0000-000000000002",
      "direction": "DEBIT",
      "amount": "200000.00",
      "currency": "VND",
      "balanceAfter": "800000.00"
    },
    {
      "id": "04f23a3f-a577-451e-992c-06483b91df3b",
      "accountId": "10000000-0000-0000-0000-000000000003",
      "direction": "CREDIT",
      "amount": "200000.00",
      "currency": "VND",
      "balanceAfter": "200000.00"
    }
  ]
}
```

ID/timestamp ở ví dụ là minh họa. Ví dụ đầy đủ áp dụng khi hai account cùng chủ
hoặc đọc detail với role nội bộ; CUSTOMER nhận balanceAfter NULL ở entry của đối tác.
Amount và balanceAfter (nếu được phép thấy) là chuỗi decimal
scale 2. Timestamp giao dịch được chuẩn hóa microsecond, khớp độ chính xác
TIMESTAMPTZ của PostgreSQL, để POST/GET và bộ lọc thời gian không lệch nhau.

## Đọc lịch sử

- `GET /api/v1/transactions/{id}` trả cùng cấu trúc detail ở trên, DEBIT trước CREDIT.
- `GET /api/v1/accounts/{id}/transactions?page=0&size=20&from=...&to=...`
  trả PageResponse với content là các object transaction (không lồng entries).

Lịch sử bao gồm giao dịch mà account là nguồn hoặc đích, kể cả OPENING. Sắp xếp
`createdAt DESC,id DESC`, page bắt đầu từ 0, size 1–100. From/to là ISO Instant
có timezone, ví dụ `2026-10-27T00:00:00Z`; khoảng thời gian `[from,to)`, mỗi cận
có thể bỏ trống. Nếu có cả hai, from phải nhỏ hơn to. Khi tính ngày Việt Nam,
client/backend phải xác định đầu ngày Asia/Ho_Chi_Minh rồi chuyển sang Instant;
API này không tự hiểu một chuỗi ngày thiếu timezone.

Account tồn tại chưa có giao dịch trả trang rỗng, account không tồn tại trả 404.
Không có API sửa/xóa transaction hoặc entry; hoàn tiền tương lai phải là giao
dịch bù, không sửa ledger cũ. Hiện chưa có reversal.

## Khóa và ranh giới transaction

`TransferController → TransferFacade → TransferService` qua Spring proxy, READ_COMMITTED, timeout
transaction 15 giây. Không tự gọi hàm @Transactional trong cùng bean.

1. Validate input, kiểm tra quyền sở hữu nguồn trước replay, claim idempotency bằng unique index.
2. AccountLockService yêu cầu transaction có sẵn (MANDATORY), cài lock_timeout cục bộ.
3. So sánh UUID chuẩn dạng chuỗi, khóa UUID nhỏ trước rồi UUID lớn bằng hai lần
   refresh(..., PESSIMISTIC_WRITE) riêng, được Hibernate chuyển thành khóa ghi dòng PostgreSQL.
   Thứ tự này tương đương unsigned byte order PostgreSQL;
   không dùng UUID.compareTo của Java vì hàm đó so sánh signed long.
4. Mỗi lần refresh đồng thời lấy khóa và đọc lại state, loại bỏ dữ liệu cũ trong
   persistence context. Không query entity cũ với lock trước rồi mới refresh, vì
   Hibernate có thể kiểm tra @Version cũ và từ chối trước khi đọc lại state.
5. Sau khi có cả hai khóa, kiểm tra ownership, ACTIVE, VND, tiền đủ, trần số dư đích
   và hạn mức lần/ngày theo nguồn. Ngày Việt Nam và createdAt dùng cùng thời điểm sau khóa.
6. Debit/credit qua AccountLockService, ghi COMPLETED transaction, hai entry,
   audit thành công và snapshot idempotency trong cùng transaction.
7. Commit. Bất kỳ exception nào làm rollback toàn bộ balance, version, timestamps,
   transaction và entries. Không giữ account lock để gọi network.

Đổi trạng thái Account cũng gọi AccountLockService. @Version hỗ trợ metadata;
row lock mới là cơ chế phối hợp các đường ghi tiền/trạng thái. Hàm debit/credit
nội bộ xác nhận entity đang có PESSIMISTIC_WRITE; không có setter balance tổng quát.

`banking.transfer.lock-timeout-ms` mặc định 2000 (giá trị 1–60000). Đây là thời
gian chờ mỗi lần lấy khóa, không phải cam kết tổng thời gian HTTP. PostgreSQL
set_config(...,true) chỉ áp dụng trong transaction, không rò sang người dùng
connection pool tiếp theo. Timeout/deadlock/optimistic conflict được trả 503
ACCOUNT_BUSY và Retry-After: 1; không tự retry POST ở server.

## Lỗi

| HTTP | Code | Nguyên nhân |
| --- | --- | --- |
| 400 | VALIDATION_ERROR / INVALID_REQUEST | Sai body/JSON/UUID/amount/currency/phân trang |
| 400 | INVALID_AMOUNT | Amount bằng 0 |
| 400 | SAME_ACCOUNT | Nguồn trùng đích |
| 400 | INVALID_TIME_RANGE | Khoảng thời gian không hợp lệ |
| 404 | ACCOUNT_NOT_FOUND | Thiếu tài khoản |
| 404 | TRANSACTION_NOT_FOUND | Không có giao dịch |
| 409 | ACCOUNT_NOT_ACTIVE | Nguồn hoặc đích FROZEN/CLOSED |
| 409 | CURRENCY_MISMATCH | Currency của tài khoản không phù hợp |
| 409 | INSUFFICIENT_FUNDS | Nguồn thiếu tiền sau khóa |
| 409 | BALANCE_LIMIT_EXCEEDED | Số dư đích vượt precision hỗ trợ |
| 503 | ACCOUNT_BUSY | Không lấy được khóa/timeout/conflict đồng thời |
| 500 | INTERNAL_ERROR | Lỗi bất ngờ, không lộ SQL hoặc stacktrace |

Lỗi nghiệp vụ rollback cả tiền, ledger, success audit và claim key; chưa ghi business
transaction FAILED. Failure audit được ghi transaction riêng sau rollback. Khi mất
response, retry đúng key và payload trong 7 ngày. Sau expiry phải tra lịch sử trước
khi quyết định gửi lại vì key hết hạn có thể được coi là yêu cầu mới.

## Dữ liệu cấp tiền ban đầu và kiểm thử

Fixture mới nằm hoàn toàn trong src/test, được import bằng @TestConfiguration;
không có production failpoint hoặc API nạp tiền. Test chỉ được phép chạy trên
database local tên banking_test và sử dụng cluster riêng.

Chứng từ mở sổ test: treasury UUID 00000000-0000-0000-0000-000000000001 có
reserve ban đầu 10.000.000 VND. Tất cả account được tạo ở balance 0; fixture
lấy khóa treasury trước khi ghi reserve, sau đó phân bổ qua OPENING + DEBIT/CREDIT.
A nhận 1.000.000 VND, B bắt đầu 0. Treasury còn 9.000.000 VND. Tổng tiền 10.000.000.

Đối chiếu: opening reserve (chỉ treasury) + tổng credit − tổng debit = balance.
Mỗi transaction có đúng 2 entry, cùng amount/currency, đúng account nguồn/đích,
debit = credit. Test kiểm tra các invariant này sau success, rollback và concurrency.
Banking_db cá nhân không được seed tiền hoặc ghi fixture trong quá trình test.

Chạy từ backend/: `.\scripts\Test-Postgres.ps1`. Khi đang chạy backend từ JAR,
dừng đúng tiến trình đó trước verify/package trên Windows để tránh khóa file JAR.
Xem `transfers.http` để biết contract request; các UUID fixture chỉ tồn tại
trong database test tạm, không tự tồn tại trong banking_db.
