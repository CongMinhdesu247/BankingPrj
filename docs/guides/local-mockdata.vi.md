# Dữ liệu mô phỏng trong database local

Bộ dữ liệu này được thêm theo yêu cầu vào `banking_db` đang dùng tại
`127.0.0.1:5432`. Giao diện vẫn ở `http://127.0.0.1:5173`.

| Đăng nhập | Vai trò | Khách hàng | Số dư sau khi tạo dữ liệu |
| --- | --- | --- | --- |
| `mock.an` | CUSTOMER | Nguyễn Minh An | 99.000.000 VND |
| `mock.linh` | CUSTOMER | Trần Gia Linh | 250.500.000 VND |
| `mock.minh` | CUSTOMER | Lê Hoàng Minh | 100.500.000 VND |
| `mock.staff` | BANK_STAFF | Nhân viên | Không có tài khoản tiền |
| `mock.risk` | RISK_OFFICER | Kiểm soát rủi ro | Không có tài khoản tiền |

Mật khẩu dùng chung được sinh trên máy và lưu trong
`backend/config/local-mockdata.credentials.txt` (Git ignored). Tài khoản admin
hiện tại vẫn dùng thông tin đăng nhập đã có.

Đăng nhập bằng tài khoản khách hàng để thử chuyển tiền, lịch sử, người thụ hưởng,
hạn mức và thông báo. Mỗi khách hàng có sẵn một người thụ hưởng trong nhóm.
Bốn giao dịch mẫu chạy qua API thật để tạo audit, outbox, phân tích rủi ro và thông báo.
Giao dịch 100.000.000 VND cung cấp tình huống kiểm tra cảnh báo rủi ro.

Chạy lại từ thư mục gốc bằng PowerShell:

```powershell
.\backend\scripts\Seed-LocalMockData.ps1
```

Script chỉ chấp nhận database local nói trên, tái sử dụng danh tính mock, và
không đặt lại số dư, mật khẩu hoặc giao dịch khi chạy lại. Không tự chạy khi
backend khởi động. Các bản ghi không thuộc bộ mock không được cấp tiền hay sửa đổi.

Vốn mở sổ mô phỏng là **500.000.000 VND**, ghi nhận tại tài khoản của khách hàng
`MOCK-RESERVE`; tài khoản này không có user đăng nhập. Ba khoản phân bổ OPENING
lần lượt là 200.000.000, 150.000.000 và 100.000.000 VND, mỗi khoản có đủ bút toán
DEBIT/CREDIT. Quỹ còn 50.000.000 VND. Sự kiện `LOCAL_MOCK_OPENING_V1` trong audit
ghi nhận nguồn vốn và ngăn cấp lại khi chạy script lần nữa.

Khi đối soát `docs/database/ledger-checks.sql`, thay dòng placeholder của
`opening_reserves` bằng ID tài khoản thuộc `MOCK-RESERVE` cùng số tiền 500.000.000.
Tổng số dư của bốn tài khoản mock luôn là 500.000.000 VND nếu chỉ chuyển tiền
trong nhóm; số dư từng khách hàng sẽ thay đổi theo thao tác test.
