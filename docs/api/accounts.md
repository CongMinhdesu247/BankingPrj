# Account API — Phase 2

## Mục đích và tác dụng

Account đại diện tài khoản thuộc một Customer. Quan hệ một khách hàng–nhiều
tài khoản là nền tảng để xác định nguồn/đích chuyển tiền và ownership về sau.
UUID là khóa nội bộ; accountNumber là chuỗi số tài khoản để hiển thị/giao dịch.

Từ phase 5, API yêu cầu Bearer JWT và kiểm tra role/ownership. CUSTOMER chỉ đọc
tài khoản của mình; STAFF/ADMIN mới được mở hoặc đổi trạng thái. Backend bind
local `127.0.0.1:8080`. Xem [ma trận quyền](auth-security.md).

## Endpoint

| Method | URL | Kết quả |
| --- | --- | --- |
| POST | `/api/v1/customers/{customerId}/accounts` | Tạo tài khoản, 201 + Location |
| GET | `/api/v1/customers/{customerId}/accounts?page=0&size=20` | Danh sách phân trang |
| GET | `/api/v1/accounts/{id}` | Chi tiết tài khoản |
| PATCH | `/api/v1/accounts/{id}/status` | Đổi trạng thái |

POST nhận duy nhất:

```json
{"currency": "VND"}
```

Backend kiểm tra Customer tồn tại và ACTIVE qua CustomerService. Số tài khoản
do backend sinh, định dạng demo 13 chữ số bắt đầu bằng `10`; không tuyên bố đây
là định dạng thật của Agribank. Database unique constraint phân xử số trùng.
Nếu generator va chạm, request rollback và trả 409 ACCOUNT_NUMBER_CONFLICT;
không thử lại trong transaction đã lỗi. Không bảo đảm idempotency cho POST ở
phase này: gửi lại POST thành công có thể tạo thêm tài khoản.

Không nhận balance, accountNumber, status, customerId hoặc timestamp trong body.
Tài khoản luôn được tạo với currency VND, status ACTIVE và balance `"0.00"`.
Field không hỗ trợ bị từ chối 400, không bị bỏ qua.

Response mẫu (ID/thời gian chỉ minh họa):

```json
{
  "id": "2ceeb8a4-3565-47d6-9c19-a314ff2a169d",
  "customerId": "a245e2de-b2f3-49df-a999-55ba28a9a58b",
  "accountNumber": "1000000000001",
  "currency": "VND",
  "balance": "0.00",
  "status": "ACTIVE",
  "createdAt": "2026-10-27T06:00:00Z",
  "updatedAt": "2026-10-27T06:00:00Z"
}
```

Tiền dùng BigDecimal/NUMERIC(19,2) và được gửi qua HTTP dưới dạng chuỗi thập phân,
kể cả số dư 0, để frontend không mất độ chính xác. Không có API sửa số dư,
nạp/rút tiền, sửa chủ tài khoản hoặc xóa tài khoản.

GET danh sách dùng PageResponse giống Customer: content, page, size, totalElements,
totalPages, first, last. Page từ 0, size 1–100; sort `createdAt ASC,id ASC`.
Customer tồn tại nhưng chưa có tài khoản trả danh sách rỗng; Customer không tồn
tại trả 404. Customer inactive vẫn được tra cứu lịch sử tài khoản.

## Trạng thái

PATCH nhận:

```json
{"status": "FROZEN"}
```

| Trạng thái hiện tại | Đích cho phép |
| --- | --- |
| ACTIVE | ACTIVE, FROZEN, CLOSED nếu balance = 0 |
| FROZEN | FROZEN, ACTIVE, CLOSED nếu balance = 0 |
| CLOSED | CLOSED; không được ACTIVE/FROZEN trở lại |

Gửi lại cùng trạng thái trả 200, không ghi thêm metadata/version. Trạng thái mới
được kiểm tra sau khi khóa và refresh account qua AccountLockService bằng PESSIMISTIC_WRITE, trong cùng
database transaction. Hibernate `@Version` bổ sung phát hiện cập nhật metadata
trên entity cũ; nó không thay thế row lock. Phase 3 dùng cùng service khóa cho
chuyển tiền và đổi trạng thái, có lock_timeout cục bộ. Chưa có API optimistic concurrency
qua If-Match. Khi triển khai chuyển tiền, mọi đường ghi balance phải khóa cùng
các dòng account trước khi kiểm tra trạng thái và cập nhật tiền.

## Lỗi

Dùng ApiError và X-Request-Id như Customer API.

| HTTP | Code | Nguyên nhân |
| --- | --- | --- |
| 404 | CUSTOMER_NOT_FOUND | Không có Customer |
| 409 | CUSTOMER_NOT_ACTIVE | Không được mở tài khoản cho Customer inactive |
| 404 | ACCOUNT_NOT_FOUND | Không có Account |
| 409 | ACCOUNT_NUMBER_CONFLICT | Generator sinh trùng số tài khoản |
| 409 | ACCOUNT_CLOSED | Muốn mở lại/thay đổi tài khoản đã đóng |
| 409 | ACCOUNT_BALANCE_NOT_ZERO | Muốn đóng tài khoản đang còn tiền |
| 409 | DATA_CONFLICT | Vi phạm ràng buộc DB khác |
| 400 | VALIDATION_ERROR | Currency thiếu/khác VND, status thiếu |
| 400 | INVALID_REQUEST | UUID, enum, JSON, phân trang sai; field ngoài contract |

## Cấu trúc và truy vấn

Account → Customer là quan hệ lazy một chiều, không cascade. DTO chỉ lấy
customerId từ proxy, không serialize entity hoặc đọc thêm Customer cho từng
account. Kiểm thử PostgreSQL xác nhận trang 10 tài khoản có tối đa 4 câu SQL khi bật auth (1 lookup user/role + 3 query nghiệp vụ)
(kiểm tra Customer, lấy trang Account, đếm tổng); không có N+1 theo số Account.

Schema accounts đã có trong V1, nên phase 2 không tạo migration rỗng hoặc sửa V1.
Khi thêm API đổi Customer status sau này, cần phối hợp với luồng mở Account để
định nghĩa kết quả khi hai thao tác diễn ra đồng thời.
