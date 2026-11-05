# Đăng ký tài khoản khách hàng

Ở trang Đăng nhập, chọn **Tạo tài khoản**, hoặc mở /register.
Nhập họ tên, email, tên đăng nhập, mật khẩu và nhập lại mật khẩu; số điện thoại không bắt buộc.
Tên đăng nhập gồm 3–100 ký tự chữ không dấu/số/dấu chấm/gạch dưới/gạch ngang, được chuẩn hóa về chữ thường.
Mật khẩu dài 12–128 ký tự. Email được chuẩn hóa về chữ thường, không được trùng hồ sơ có sẵn.

Đăng ký thành công tạo trong cùng một transaction:
- Hồ sơ khách hàng ACTIVE với mã do server sinh.
- Tài khoản đăng nhập enabled, chỉ có quyền CUSTOMER; mật khẩu được băm bằng encoder hiện có.
- Một tài khoản VND ACTIVE với số dư 0 và số tài khoản do server sinh.
- Nhật ký tạo hồ sơ, user và tài khoản; không ghi mật khẩu hoặc nội dung biểu mẫu vào audit.

Sau khi hoàn tất, chọn **Đăng nhập ngay**. Tên đăng nhập được điền sẵn; nhập mật khẩu để đăng nhập.
Không tự cấp tiền hoặc tạo giao dịch ghi sổ. Không gắn người đăng ký với hồ sơ cũ dựa trên email.
Không có bước xác minh email/SMS trong luồng đăng ký mô phỏng này.

API: POST /api/v1/auth/register, không yêu cầu đăng nhập, trả HTTP 201 với username và accountNumber.
Chấp nhận duy nhất fullName, email, phone (tùy chọn), username, password.
Client không được gửi roles, customerId, balance hoặc enabled. Dữ liệu thiếu/sai trả 400;
thông tin trùng trả REGISTRATION_CONFLICT (409). Mọi bản ghi đăng ký được rollback nếu có lỗi.
Database hiện có được giữ nguyên, không cần migration. API dùng bảo vệ tần suất Redis của nhóm /auth/.

Cập nhật Docker từ thư mục gốc:

```powershell
docker compose up -d --build --no-deps backend web
```

Kiểm thử: RegisterPage.test.tsx, LoginPage.test.tsx và backend RegistrationIT.
RegistrationIT chỉ chạy với database banking_test riêng theo TransferIntegrationTestSupport;
không chạy các fixture test trên database demo đang dùng.
