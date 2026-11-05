Sau khi bật PC lại, bạn làm 3 bước:
1. Kết nối Internet và mở Docker Desktop, chờ Docker khởi động xong.
2. Mở PowerShell.
3. Chạy:
   cd C:\Users\ADMIN\Downloads\BankingPrj
   .\Start-PublicDemo.ps1 -PublicUrl "https://unselect-rebate-tall.ngrok-free.dev"
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

## Chạy bằng Docker Desktop (Windows)

Đã có stack Docker gồm React/Nginx, Spring Boot, PostgreSQL, Redis và Kafka.
Xem [hướng dẫn Docker trên PC](docs/guides/docker-local.vi.md) để khởi động,
tạo tài khoản mô phỏng và giữ dữ liệu sau khi tắt máy.

```powershell
.\Initialize-Docker.ps1
docker compose up -d --build --wait --wait-timeout 300
pwsh -NoProfile -File .\Seed-DockerDemo.ps1
```

Chỉ chạy Initialize-Docker lần đầu. Website: **http://localhost:8090**.
Database Docker riêng, không dùng database native của các script bên dưới.

## Chạy giao diện hoàn chỉnh sau khi clone (Windows)

Repository này chứa giao diện React đầy đủ và backend thực tế. Để đăng nhập,
chuyển tiền và xem dữ liệu mẫu, chạy demo bên dưới; `npm run dev` riêng chỉ
khởi động frontend và vẫn cần backend cho các nghiệp vụ.

### Chuẩn bị một lần

Cài Git, **PowerShell 7** (`pwsh`), **Node.js >= 22.12**, **JDK 21** và
**PostgreSQL 18** (cần các công cụ trong thư mục `bin`). Cài WSL Ubuntu nếu chưa có:

```powershell
wsl --install -d Ubuntu
```

Khởi động lại máy nếu Windows yêu cầu, mở Ubuntu và hoàn tất tạo người dùng.
Clone repository, mở PowerShell 7 tại thư mục vừa clone:

```powershell
git clone https://github.com/CongMinhdesu247/BankingPrj.git
cd BankingPrj
wsl -d Ubuntu -u root -- apt-get update
wsl -d Ubuntu -u root -- apt-get install -y redis-server redis-tools
pwsh -File ./backend/scripts/Install-Kafka.ps1
```

### Khởi động demo

Từ thư mục gốc repository (không cần đường dẫn máy của tác giả):

```powershell
pwsh -File ./Start-Demo.ps1
```

Script cài thư viện theo lockfile, build frontend/backend và khởi động PostgreSQL,
Redis, Kafka, API cùng giao diện demo. Mở **http://127.0.0.1:5174** sau thông báo
`Isolated portal demo ready`. Cần Internet cho lần tải dependency đầu tiên.

Nếu PostgreSQL nằm ở vị trí khác:

```powershell
pwsh -File ./Start-Demo.ps1 -PostgreSqlBin 'D:\PostgreSQL\18\bin'
```

Tài khoản và mật khẩu được tạo ngẫu nhiên khi chạy, lưu trong
`backend/config/docker-demo.credentials.txt` (không đưa lên Git).
Dùng `username`/`password` để đăng nhập khách hàng; `staffUsername`/`staffPassword`,
`riskUsername`/`riskPassword`, `adminUsername`/`adminPassword` cho các vai trò khác.
Không cần cấu hình `application-local.properties` cho demo này.

Demo tạo database riêng, tài khoản và số dư mô phỏng; không dùng database cá nhân.
Mỗi lần chạy tạo bộ dữ liệu mới. Dừng demo trước khi chạy lại:

```powershell
pwsh -File ./backend/scripts/Stop-PortalDemo.ps1
```

Nếu đã dùng `-PostgreSqlBin` khi khởi động, truyền cùng tham số khi dừng.
Nếu cổng 5174/8081 bị chiếm, dừng demo cũ hoặc truyền `-UiPort`/`-ApiPort` khác.
Không commit `.env`, mật khẩu, database, `node_modules` hay `target`.

### Chỉ khởi động frontend

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```


## Chatbot hỗ trợ AI

Đã tích hợp khung chat khách hàng với Gemini API ở backend. Xem [cách bật Gemini và giới hạn miễn phí](docs/guides/ai-chatbot.vi.md). Khi chưa cấu hình API key, khung chat dùng hướng dẫn có sẵn và ghi rõ trạng thái.

## Đăng ký khách hàng

Chọn **Tạo tài khoản** tại trang đăng nhập. Mỗi đăng ký tạo quyền CUSTOMER và một tài khoản VND số dư 0. Xem [luồng đăng ký](docs/guides/customer-registration.vi.md).

## Biểu đồ tổng quan

Trang Tổng quan có biểu đồ dòng tiền vào/ra và cơ cấu dòng tiền theo 7/30 ngày, dùng số liệu thực của khách hàng. Xem [hướng dẫn biểu đồ](docs/guides/dashboard-charts.vi.md).
