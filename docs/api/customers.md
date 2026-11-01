# Customer API — Phase 1

## Mục đích và tác dụng

Customer lưu định danh khách hàng và thông tin liên hệ. Account sẽ tham chiếu
Customer ở phase tiếp theo; User sẽ liên kết với Customer khi triển khai quyền
truy cập. Entity chỉ dùng trong backend, HTTP trả DTO để tránh lộ cấu trúc nội bộ.

Từ phase 5, các endpoint dưới đây yêu cầu Bearer JWT và role BANK_STAFF hoặc ADMIN.
CUSTOMER xem hồ sơ mình qua `/api/v1/me`. Backend bind local `127.0.0.1`, dùng dữ
liệu giả lập. Xem [quyền và ownership](auth-security.md).

Base URL: `http://127.0.0.1:8080/api/v1/customers`.

## Tạo khách hàng

`POST /api/v1/customers`

```json
{
  "customerCode": "KH001",
  "fullName": "Nguyễn Văn An",
  "email": "an@example.com",
  "phone": "0912345678"
}
```

Trả `201 Created`, header `Location` trỏ tới chi tiết khách hàng. Ví dụ cấu trúc:

```json
{
  "id": "4d0b1a9a-9575-4d04-b243-90cbb7369c56",
  "customerCode": "KH001",
  "fullName": "Nguyễn Văn An",
  "email": "an@example.com",
  "phone": "0912345678",
  "status": "ACTIVE",
  "createdAt": "2026-10-27T06:00:00Z",
  "updatedAt": "2026-10-27T06:00:00Z"
}
```

ID và thời gian trong ví dụ chỉ minh họa. Mã khách dài tối đa 30 ký tự, bắt đầu
bằng chữ/số, tiếp theo cho phép chữ/số/gạch dưới/gạch ngang; được chuẩn hóa thành
chữ hoa. Họ tên bắt buộc, tối đa 150 ký tự và được bỏ khoảng trắng ở hai đầu.
Email tùy chọn, tối đa 254 ký tự, đúng định dạng và được chuyển chữ thường.
Phone tùy chọn, gồm 8–15 chữ số và có thể có dấu `+` đầu chuỗi.
Email/phone rỗng khi tạo được lưu thành NULL. Mã/email trùng bị từ chối kể cả
khi hai request đồng thời. Status, ID và timestamp do backend quản lý.

## Đọc và tìm kiếm

- `GET /api/v1/customers/{id}`: chi tiết, trả `200` hoặc `404`.
- `GET /api/v1/customers?page=0&size=20&search=an`: danh sách.

Page bắt đầu từ 0; size từ 1 đến 100, mặc định 20. Search tối đa 150 ký tự,
tìm chuỗi con trong mã/họ tên/email, không phân biệt hoa thường; `%` và `_`
được hiểu là ký tự thường. Chưa tìm kiếm bỏ dấu tiếng Việt.
Sắp xếp cố định theo `createdAt ASC, id ASC` để phân trang ổn định khi dữ liệu
không thay đổi; chưa cung cấp snapshot xuyên nhiều request khi có ghi đồng thời.

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```

## Cập nhật liên hệ

`PATCH /api/v1/customers/{id}`

```json
{"email": "an.moi@example.com", "phone": "+84912345678"}
```

Trả `200` và CustomerResponse mới. Field không gửi hoặc NULL được giữ nguyên;
chuỗi rỗng `""` xóa thông tin liên hệ về NULL. Phải gửi ít nhất một field khác
NULL. Không cho sửa tên, mã, status hoặc timestamp qua endpoint này. Không có
API hard delete. Field JSON không được hỗ trợ bị từ chối thay vì bỏ qua.

## Lỗi và request ID

```json
{
  "code": "CUSTOMER_NOT_FOUND",
  "message": "Customer was not found.",
  "fieldErrors": [],
  "requestId": "customer-demo-001",
  "timestamp": "2026-10-27T06:00:00Z",
  "path": "/api/v1/customers/4d0b1a9a-9575-4d04-b243-90cbb7369c56"
}
```

| HTTP | Code | Ý nghĩa |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | Field body không hợp lệ; xem fieldErrors |
| 400 | INVALID_REQUEST | JSON/UUID/query sai hoặc field không được hỗ trợ |
| 400 | EMPTY_UPDATE | PATCH không có thông tin cần thay đổi |
| 404 | CUSTOMER_NOT_FOUND | Không có khách hàng tương ứng |
| 409 | CUSTOMER_CONFLICT | Trùng mã/email |
| 409 | DATA_CONFLICT | Vi phạm ràng buộc dữ liệu khác |
| 405 | METHOD_NOT_ALLOWED | Phương thức HTTP chưa hỗ trợ |
| 415 | UNSUPPORTED_MEDIA_TYPE | Kiểu nội dung không hỗ trợ |
| 500 | INTERNAL_ERROR | Lỗi bất ngờ; response không lộ SQL/stacktrace |

Có thể gửi `X-Request-Id` từ 1–100 ký tự chữ/số/dấu chấm/gạch ngang/gạch dưới.
Backend sinh ID nếu header thiếu hoặc sai; response luôn có header tương ứng.
Log có requestId để đối chiếu. Không log body hoặc giá trị tham số SQL.

Request có thể chạy từ file `customers.http` bên cạnh. Thay customerId bằng ID
nhận được sau POST. Frontend chưa tích hợp các API này.
