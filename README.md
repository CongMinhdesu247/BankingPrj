Sau khi bật PC lại, bạn làm 3 bước:
1. Kết nối Internet và mở Docker Desktop, chờ Docker khởi động xong.
2. Mở PowerShell.
3. Chạy:
   cd C:\Users\ADMIN\Downloads\BankingPrj
   .\scripts\Start-PublicDemo.ps1 -PublicUrl "https://unselect-rebate-tall.ngrok-free.dev"
Script sẽ tự khởi động các dịch vụ Docker, chờ chúng sẵn sàng rồi mở ngrok. Không cần tạo lại tài khoản demo hay nhập lại authtoken mỗi lần.

Người ở xa truy cập bằng link cũ:

https://unselect-rebate-tall.ngrok-free.dev

Bạn có thể tiếp tục làm việc bình thường, nhưng cần giữ Docker Desktop và cửa sổ PowerShell đang chạy, đồng thời để PC có mạng và không chuyển sang chế độ Sleep.

Khi muốn ngừng server:
- Nhấn Ctrl+C trong PowerShell để ngắt truy cập công khai.
- Chạy lệnh dưới đây để dừng ứng dụng và giữ dữ liệu:
  docker compose stop
Sau đó tắt máy bình thường.


Sau khi sửa ứng dụng, để cập nhật bản đang chạy trên PC, bạn cần build lại:
cd C:\Users\ADMIN\Downloads\BankingPrj
docker compose up -d --build


# Bank Transaction & Fraud Monitoring System

## Mở ứng dụng trên localhost

Ứng dụng chạy tại **[http://localhost:8090](http://localhost:8090)**. Docker khởi động đầy đủ giao diện, API và các dịch vụ đi kèm.

### Lần đầu sử dụng

1. Cài và mở **Docker Desktop**, chờ Docker sẵn sàng.
2. Clone hoặc copy dự án về máy, rồi mở thư mục `BankingPrj`.
3. Chạy file tương ứng với hệ điều hành:

| Hệ điều hành | Cách khởi động |
| --- | --- |
| Windows | Nhấp đúp **`run.bat`** |
| macOS / Linux | Mở terminal tại thư mục dự án và chạy **`sh ./run.sh`** |

Chờ thông báo **`[DONE]`**, sau đó mở **http://localhost:8090** trên trình duyệt. Lần đầu có thể mất vài phút để tải dependencies và build ứng dụng.

Script tự tạo `.env` khi chưa có và chờ các dịch vụ sẵn sàng. Không cần cài Java, Node.js hay PostgreSQL riêng.

### Đăng nhập

- **Khách hàng mới:** chọn **Tạo tài khoản** trên trang đăng nhập. Tài khoản được tạo với số dư ban đầu bằng 0.
- **Quản trị viên:** tên đăng nhập `docker.admin`; mật khẩu là giá trị `DOCKER_ADMIN_PASSWORD` trong file `.env`.
- **Tài khoản demo đã có:** dùng thông tin trong `backend/config/docker-demo.credentials.txt`, nếu trước đó đã tạo dữ liệu mẫu.

Để tạo dữ liệu mẫu trên Windows, mở PowerShell 7 tại thư mục dự án sau khi ứng dụng đã sẵn sàng và chạy:

```powershell
pwsh -NoProfile -File ./scripts/Seed-DockerDemo.ps1
```

### Những lần mở tiếp theo

Mở Docker Desktop, rồi chạy lại **`run.bat`** hoặc **`sh ./run.sh`**. Tài khoản và dữ liệu trong Docker volumes được giữ lại.

Nếu không có thay đổi mã nguồn, có thể khởi động nhanh từ terminal tại thư mục dự án:

```bash
docker compose up -d --wait --wait-timeout 300
```

### Dừng ứng dụng và xem lỗi

Dừng ứng dụng, giữ dữ liệu:

```bash
docker compose stop
```

Nếu không mở được localhost, kiểm tra Docker Desktop đang chạy và xem trạng thái, log:

```bash
docker compose ps -a
docker compose logs --tail 100
```

Giữ file `.env` để sử dụng lại cấu hình đăng nhập. Không chạy `docker compose down -v` nếu cần giữ dữ liệu. Khi chuyển máy, dữ liệu trong Docker volumes cần được backup/restore riêng.

## Giới thiệu chương trình

**Bank Transaction & Fraud Monitoring System** là ứng dụng mô phỏng giao dịch ngân hàng và giám sát gian lận, dành cho việc học tập và trình diễn nghiệp vụ. Các giao dịch sử dụng dữ liệu và số dư mô phỏng.

Chương trình hỗ trợ:

- Đăng ký, đăng nhập và quản lý tài khoản khách hàng.
- Chuyển tiền nội bộ, lưu người thụ hưởng và mẫu chuyển tiền.
- Tra cứu lịch sử giao dịch, tải biên lai PDF và xem biểu đồ dòng tiền.
- Theo dõi cảnh báo rủi ro, hỗ trợ nhân viên và quản trị viên xử lý nghiệp vụ.
- Gửi yêu cầu tra soát, nhận thông báo và sử dụng chatbot hỗ trợ; Gemini là tích hợp tùy chọn.

Giao diện sử dụng **React và TypeScript**; backend sử dụng **Java 21 và Spring Boot**. **PostgreSQL** lưu dữ liệu, **Redis** hỗ trợ cache và giới hạn request, **Kafka** xử lý sự kiện; **Nginx** phục vụ giao diện trong Docker.

## Tài liệu thêm

- [Cấu hình Docker, biến môi trường và chuyển sang máy mới](docs/guides/docker-portable.vi.md)
- [Dữ liệu demo, backup và chia sẻ ứng dụng qua Internet](docs/guides/docker-local.vi.md)
- [Đăng ký khách hàng](docs/guides/customer-registration.vi.md)
- [Chatbot AI](docs/guides/ai-chatbot.vi.md)
- [Biểu đồ tổng quan](docs/guides/dashboard-charts.vi.md)
