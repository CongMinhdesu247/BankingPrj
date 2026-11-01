# Customer Portal — API bổ sung Phase 7

Portal dùng các API bảo vệ đã có: `/auth/login`, `/me`, `/me/accounts`,
`/accounts/{id}`, `/me/transactions`, `/accounts/{id}/transactions`, `/transfers`,
`/transactions/{id}`, `/me/beneficiaries`, `/me/transfer-limits`.

## Tra cứu người nhận

CUSTOMER có thể tra cứu tài khoản nhận trước khi xác nhận chuyển tiền:

- `GET /api/v1/transfer-recipients?accountNumber={number}`
- `GET /api/v1/transfer-recipients/{id}`

```json
{"id":"<account UUID>","accountNumber":"1048000000001","currency":"VND","status":"ACTIVE"}
```

Chỉ bốn field: id, accountNumber, currency, status. Không trả balance, owner UUID,
tên chủ tài khoản, email, phone hoặc hồ sơ Customer. Tài khoản nhận có thể thuộc
khách khác nhưng endpoint full `/accounts/{id}` vẫn giữ ownership/404 như phase 5.
Tra cứu không cấp quyền debit và không bảo đảm trạng thái ở lúc chuyển; backend
vẫn kiểm tra lại sau khóa. Không có public endpoint tra cứu hoặc danh sách tất cả
account; ADMIN/STAFF/RISK cũng không được dùng endpoint CUSTOMER này.

Account number là chuỗi không rỗng tối đa 30 ký tự, khớp schema hiện tại; không
ép numeric vì dữ liệu legacy/test có thể chứa prefix. Số tài khoản mới của demo
do AccountNumberGenerator cấp gồm 13 chữ số, không khẳng định là chuẩn Agribank.
Query number rỗng/quá dài hoặc UUID sai trả 400; không tồn tại trả 404
ACCOUNT_NOT_FOUND. Thiếu token 401, sai role 403, theo cùng ApiError contract.

## Beneficiary projection

Response beneficiary bổ sung `destinationAccountNumber` từ join Accounts, giữ
nguyên field cũ. List/detail/create/update đều trả field này; không thêm dữ liệu
private của đối tác. List dùng một join, tránh frontend gọi một API cho mỗi dòng.
Khi PATCH/DELETE chỉ khóa dòng beneficiary (`FOR UPDATE OF b`), không vô tình khóa
account đích do join. Các đường ghi balance/status vẫn dùng AccountLockService.

Không thêm bảng hoặc migration. Account number đã có unique index trong V1.

## Hành vi phía frontend

Token chỉ ở memory; HTTP dùng Bearer, không cookie, không localStorage token.
Reload/expiry cần login lại rồi quay về đúng pathname/query trước đó. Lỗi 401 của
request cũ không được làm hết phiên mới. Logout có pending request sẽ cảnh báo;
explicit logout xóa payload tạm, còn expiry giữ để cùng user đăng nhập lại kiểm tra.

Pending transfer sessionStorage chỉ có owner UUID, key, payload canonical, createdAt
và version. Ghi bản lưu thành công trước POST; không lưu được thì không gửi tiền.
Sau success hoặc lỗi nghiệp vụ chắc chắn, xóa đúng record owner/key. Network,
timeout, malformed response, 5xx, 429 hoặc conflict key giữ yêu cầu để đối chiếu/retry.
Sau 7 ngày UI chặn retry và yêu cầu xem history. Đóng bản lưu không hủy giao dịch
server; UI yêu cầu xác nhận đã đối chiếu. Không tự retry hoặc sinh key mới khi
kết quả chưa rõ. Một response cũ không được xóa pending intent mới của user khác.

Tiền VND giữ dạng chuỗi ở HTTP và BigInt khi so sánh/định dạng; không dùng Number
cho balance/amount. UI hiển thị tối đa precision schema; server quyết định cuối
cùng sau khóa. Hạn mức UI là thông tin tham khảo, không thay thế policy backend.

Ngày lọc lịch sử là ngày Việt Nam, “đến hết ngày” chuyển thành `[from,to)` với
to là 00:00 ngày tiếp theo. Dữ liệu empty/loading/error/403/409/429/500 có trạng
thái riêng, lỗi hiển thị thông báo tiếng Việt và request ID khi có.
