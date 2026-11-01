# Tiện ích khách hàng và rủi ro tài khoản

Khách hàng có thêm menu **Sao kê & thu chi** và **Lịch sử đăng nhập**. Trong
**Thông tin cá nhân**, có thể cập nhật email/số điện thoại; các thông tin này là
liên hệ chưa xác minh OTP, không được dùng làm bằng chứng xác thực.

Sao kê tính theo tháng trong múi giờ `Asia/Ho_Chi_Minh`, bao gồm vốn mở sổ và
chuyển giữa các tài khoản. Tổng tiền vào/ra là tổng toàn tháng, không phải chỉ
trang đang xem. Số dư của tháng hiện tại tính đến thời điểm tải. CSV và bản in
chỉ xuất trang hiện tại, được ghi rõ trên màn hình; CSV bảo vệ nội dung khỏi
được diễn giải thành công thức. Số tiền truyền dưới dạng chuỗi, tính bằng
NUMERIC/BigDecimal tại backend và định dạng bằng BigInt tại frontend.

Admin mặc định mở **Rủi ro tài khoản** sau đăng nhập. Menu này dành cho ADMIN
và RISK_OFFICER, có tìm theo số tài khoản/tên/mã khách hàng, lọc mức độ và phân
trang. Chi tiết tài khoản trong tra cứu nội bộ cũng có khối rủi ro với hai vai
trò này. BANK_STAFF không có quyền xem bảng chấm điểm.

Chỉ báo tài khoản dùng giao dịch **chuyển đi trong 30 ngày gần nhất**:

- Điểm cao nhất từ cảnh báo OPEN, IN_REVIEW hoặc CONFIRMED_FRAUD.
- Bỏ cảnh báo FALSE_POSITIVE, không gán rủi ro cho bên nhận chỉ vì nhận tiền.
- Không có phân tích: UNASSESSED (Chưa đánh giá), không hiển thị điểm 0/100.
- Đã phân tích nhưng không còn cảnh báo hợp lệ: LOW, điểm 0.
- LOW: 0–29; MEDIUM: 30–59; HIGH: 60–79; CRITICAL: 80–100.
- Số giao dịch chờ phân tích hiển thị riêng, giúp nhận biết kết quả chưa đầy đủ.

Đây là chỉ báo hậu kiểm theo quy tắc, không phải xác suất gian lận hoặc kết luận
về chủ tài khoản. Chỉ báo thay đổi khi cảnh báo được xử lý hoặc giao dịch ra khỏi
cửa sổ 30 ngày. Không tự khóa tài khoản, chuyển tiền hoặc hoàn tiền.

API mới:

| API | Quyền | Nội dung |
| --- | --- | --- |
| `GET /api/v1/me/statement?accountId=...&month=2026-10&page=0&size=20` | CUSTOMER, chính chủ | Tổng hợp toàn tháng và bút toán phân trang |
| `GET /api/v1/me/login-history?page=0&size=20` | Đã đăng nhập, chính mình | Thời gian và kết quả; không trả fingerprint hay mật khẩu |
| `PATCH /api/v1/me/contact` | CUSTOMER, chính chủ | `{ "email": "...", "phone": "..." }`; chuỗi rỗng xóa trường, null giữ nguyên |
| `GET /api/v1/staff/account-risk?search=...&riskLevel=HIGH&page=0&size=20&accountId=...` | ADMIN, RISK_OFFICER | Tổng hợp rủi ro tài khoản |

Sao kê của người khác trả 404. Việc cập nhật liên hệ có audit và xóa cache hồ sơ
sau commit. Các truy vấn sao kê và rủi ro dùng snapshot REPEATABLE_READ để tổng
và các dòng dữ liệu khớp nhau trong cùng một request. Không cần migration hay
cấp lại số dư mẫu.

Kiểm thử: `npm.cmd run build`, `npm.cmd test`, `backend/mvnw.cmd test` và
`backend/scripts/Test-Postgres.ps1 -IntegrationTest CustomerToolsIT`.
