# Năm nhóm chức năng tự phục vụ

Giao diện local: `http://127.0.0.1:5173`. Backend: `127.0.0.1:8080`.
Migration V5 chỉ bổ sung bảng, không cấp lại tiền và không thay đổi giao dịch cũ.
Sau khi cập nhật, đăng nhập lại vì token cũ chưa có phiên đăng nhập trong database.

## Trung tâm bảo mật

Giao diện chia thành ba thao tác: **Đổi mật khẩu**, **Bật bảo vệ hai bước** và
**Thiết bị đang đăng nhập**. Mỗi thao tác có biểu mẫu xác minh riêng.
Bật OTP đi lần lượt qua xác minh mật khẩu, quét QR/nhập khóa thủ công, nhập mã
6 chữ số và lưu mã khôi phục. Không có ô OTP bắt buộc cho người chưa bật tính năng.

OTP lấy trong Google Authenticator hoặc Microsoft Authenticator đã kết nối với
tài khoản Banking; không gửi qua SMS/email. Quên mật khẩu dùng liên kết đặt lại.
Trang quên mật khẩu đọc cấu hình kênh nhận từ server và, ở chế độ local, hiển thị
đường dẫn thư mục hộp thư cùng hướng dẫn mở tệp. Không công khai token reset qua API.

Trạng thái tra soát dùng nhãn có cả chữ và màu: vàng Mới gửi, xanh dương Đang xử
lý, xanh lá Đã phản hồi kết quả, xám Đã đóng; áp dụng chung cho khách hàng và nhân viên.

- Đổi mật khẩu: xác minh mật khẩu hiện tại, tối thiểu 12 ký tự, thu hồi mọi phiên.
- Xem trình duyệt, thời điểm tạo/hết hạn; thu hồi một phiên khác hoặc mọi phiên khác.
- Đăng xuất chủ động thu hồi phiên trên server. Tải lại trang vẫn cần đăng nhập lại.
- TOTP theo RFC 6238: SHA-1, 6 chữ số, chu kỳ 30 giây, cho phép lệch một chu kỳ.
  Khóa thiết lập hết hạn sau 10 phút, chỉ bật sau khi xác nhận mã hợp lệ.
- Khóa TOTP mã hóa AES-GCM, ràng buộc với user; khóa mã hóa được dẫn xuất riêng
  từ JWT secret. Không thay JWT secret mà chưa có kế hoạch chuyển đổi khóa TOTP.
- Một mã OTP đã dùng không thể dùng lại. Khi bật OTP, các phiên khác bị thu hồi.
- Tám mã khôi phục ngẫu nhiên, mỗi mã một lần, chỉ hiển thị lúc bật OTP. Database
  chỉ lưu hash; có thể dùng thay OTP khi đăng nhập hoặc xác minh thao tác bảo mật.
- Redis giới hạn thử mật khẩu/mã xác thực; các thay đổi có audit, không ghi secret.

Quên mật khẩu có form yêu cầu và liên kết đặt lại một lần, hết hạn sau 15 phút.
Luồng đặt lại giữ nguyên MFA, yêu cầu OTP/mã khôi phục nếu đã bật, và thu hồi các
phiên cũ. Token chỉ lưu hash trong database, không trả trong API.

**Bản local dùng hộp thư mô phỏng, chưa gửi email thật.** Khởi động backend với:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--banking.security.local-reset-mailbox-enabled=true"
```

Sau khi gửi form Quên mật khẩu, người kiểm thử đọc tệp `reset-*.txt` mới nhất
trong `backend/target/local-mailbox/` để lấy liên kết. Tệp này chứa token nhạy cảm,
Git ignored, chỉ dùng trên máy phát triển. API luôn trả thông điệp chung cho tài
khoản có/không tồn tại. Hộp thư mặc định tắt và chỉ được bật khi server bind loopback.
Khi triển khai môi trường khác, phải cấu hình kênh khôi phục qua email đã xác minh;
không dùng hộp thư local làm cơ chế gửi email thật.

## Biên lai PDF và mẫu chuyển tiền

Trong chi tiết giao dịch, tải biên lai PDF có mã tham chiếu, thời gian Việt Nam,
số tài khoản, số tiền và nội dung. PDF nhúng font Be Vietnam Pro theo OFL, giữ
tiếng Việt và xuống dòng theo độ rộng. Thư viện PDF tải khi cần; không làm chậm
lần tải trang đầu. Chứng từ ghi rõ là mô phỏng và không ký số.

Với giao dịch chuyển đi của mình, khách hàng lưu mẫu bằng tên gợi nhớ. Menu Mẫu
chuyển tiền cho phép dùng/xóa mẫu. Dùng mẫu chỉ điền form, vẫn phải kiểm tra
người nhận, số dư, hạn mức và xác nhận qua luồng chuyển tiền/idempotency hiện có.

## Tra soát giao dịch

- Khách hàng tạo từ chi tiết giao dịch hoặc menu Tra soát & hỗ trợ; chỉ chọn
  giao dịch chuyển tiền có liên quan tới tài khoản của mình.
- Mỗi user chỉ có một yêu cầu chưa đóng cho cùng giao dịch.
- Nhân viên/ADMIN xem hàng đợi, nhận xử lý, phản hồi, gửi kết quả với nội dung bắt buộc.
- Khách hàng bổ sung thông tin; phản hồi sau kết quả mở lại hồ sơ. Khách hàng
  đồng ý kết quả thì đóng; hồ sơ đã đóng không thể ghi thêm.
- Cập nhật trạng thái kiểm tra version, chống ghi đè kết quả đã thay đổi.
- Mọi bước có audit, hoàn toàn không tự hủy/hoàn tiền.

## Thông báo tra soát

Khi CSKH/ADMIN đổi trạng thái hoặc gửi phản hồi, chủ hồ sơ nhận thông báo
trong web. Thông báo và thay đổi hồ sơ được lưu trong cùng giao dịch database;
nếu lưu thông báo thất bại thì thay đổi hồ sơ cũng được hoàn tác. Mỗi người chỉ
được đọc và đánh dấu đã đọc thông báo của mình.

Chuông và mục Thông báo hiển thị số chưa đọc. Khi web đang mở, hệ thống kiểm
tra cập nhật mỗi 10 giây; khi quay lại tab sẽ kiểm tra ngay. Thông báo mới hiện
trạng thái hoặc nội dung xem trước của CSKH, có liên kết mở đúng cuộc trao đổi.
Danh sách thông báo giữ lịch sử để xem lại sau khi đăng nhập. Chức năng này là
thông báo trong web, chưa gửi email, SMS hoặc push khi đóng trình duyệt.

## Hạn mức cá nhân

Hạn mức hiệu lực bằng mức thấp hơn giữa trần ngân hàng và hạn mức cá nhân.
Khách hàng tự giảm ngay; tăng phải gửi lý do để ADMIN duyệt, không vượt trần.
Mỗi tài khoản có tối đa một yêu cầu đang chờ. Duyệt/từ chối có nhận xét, chỉ
thực hiện một lần; duyệt kiểm tra lại trạng thái tài khoản và trần tại thời điểm đó.
Cùng cơ chế khóa tài khoản với chuyển tiền, hạn mức áp dụng tại backend.
Giảm hạn mức ngày xuống dưới số tiền đã chuyển khiến số còn lại là 0.

## Kiểm thử

```powershell
# frontend/
npm.cmd run build
npm.cmd test
# backend/
.\scripts\Test-Postgres.ps1 -IntegrationTest 'SelfServiceIT,CustomerToolsIT,SecurityAuthorizationIT,TransferLimitIT'
```

Kiểm thử tích hợp dùng PostgreSQL/Redis/Kafka riêng biệt, không seed banking_db.
OTP có test vector RFC 6238; quyền truy cập, mã khôi phục một lần, phiên bị thu hồi,
token reset, quy trình tra soát và hạn mức được kiểm tra qua HTTP/JWT thật.
