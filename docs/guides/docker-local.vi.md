# Chạy BankingPrj trên PC bằng Docker Desktop

## Địa chỉ và vị trí dữ liệu

- Website: http://localhost:8090 (hoặc http://127.0.0.1:8090).
- Code: thư mục BankingPrj trên PC của bạn.
- React được build thành file tĩnh và phục vụ bằng Nginx.
- API Java, PostgreSQL, Redis và Kafka chạy trong Docker Desktop trên chính PC.
- PostgreSQL: database `banking_docker_demo`, volume `banking-local_postgres_demo_data`.
- Redis và Kafka có volume riêng. Xem chúng trong Docker Desktop > Volumes.
- Database, Redis, Kafka và API không mở cổng trực tiếp ra Windows/Internet.
- Dữ liệu nằm trong đĩa ảo Docker/WSL, không nằm trong thư mục source code.
  Xem vị trí đĩa ảo trong Settings của Docker Desktop. Volume không thay thế backup.

## Chuẩn bị lần đầu

Mở Docker Desktop, sau đó mở terminal tại thư mục gốc dự án:

```powershell
.\scripts\Initialize-Docker.ps1
docker compose up -d --build --wait --wait-timeout 300
pwsh -NoProfile -File .\scripts\Seed-DockerDemo.ps1
```

Chỉ chạy Initialize-Docker một lần. Script tạo `.env` với khóa JWT và mật khẩu ngẫu nhiên,
không ghi đè file có sẵn. Nếu có `.env` từ cấu hình khác, giữ bản sao riêng và bổ sung các
biến DOCKER_* tương ứng; không xóa mật khẩu khi volume đã có dữ liệu.
PowerShell 7 (`pwsh`) được dùng cho script seed để giữ đúng dữ liệu tiếng Việt.

Build backend chạy `mvn verify` (unit tests; không chạy integration profile).
Frontend dùng `npm ci` theo lockfile rồi build. Không cần cài Java, Node, PostgreSQL,
Redis hoặc Kafka trực tiếp trên Windows để chạy stack Docker.
Kafka-init chỉ là tác vụ cấp quyền volume: trạng thái Exited (0) là bình thường.

## Đăng nhập

- Khách hàng: `mock.an`, `mock.linh`, `mock.minh`.
- Nhân viên: `mock.staff`; quản lý rủi ro: `mock.risk`.
- Mật khẩu các tài khoản mô phỏng nằm ở `backend/config/docker-demo.credentials.txt`.
- Admin: `docker.admin`; mật khẩu là DOCKER_ADMIN_PASSWORD trong `.env`.

Script seed chỉ kết nối database Docker riêng, tạo dữ liệu hư cấu và vốn mở sổ mô phỏng.
Có thể chạy lại; marker trong database ngăn cộng vốn hoặc chuyển tiền mẫu lần thứ hai.
Không đưa `.env` hoặc file credentials lên Git. Không dùng tài khoản admin làm tài khoản demo công khai.

## Dùng hằng ngày

Mở Docker Desktop, rồi chạy tại thư mục gốc dự án:

```powershell
docker compose up -d --wait --wait-timeout 300
```

Dừng nhưng giữ dữ liệu:

```powershell
docker compose stop
```

Build lại sau khi sửa code:

```powershell
docker compose up -d --build --wait --wait-timeout 300
```

Xem tình trạng/log:

```powershell
docker compose ps -a
docker compose logs --tail 100 backend
```

`docker compose down` xóa container/network nhưng giữ named volumes.
Không thêm `-v` nếu cần giữ dữ liệu; tùy chọn đó xóa các volume của dự án.
Giữ `.env` và credentials cũ để đăng nhập được sau restart.
Các dịch vụ có restart policy; vẫn cần Docker Desktop hoạt động. Khi PC sleep/tắt, web offline.

## Backup PostgreSQL

```powershell
docker compose exec -T postgres pg_dump -U banking_demo -d banking_docker_demo -Fc -f /tmp/banking-demo.dump
docker compose cp postgres:/tmp/banking-demo.dump ./backend/local-backups/banking-demo.dump
```

Tạo thư mục `backend/local-backups` trước khi chạy và sao chép bản backup sang thiết bị khác.
Không pipe định dạng dump nhị phân qua PowerShell. Kiểm tra phục hồi trên database riêng trước khi cần dùng thật.

## Link công khai cố định miễn phí bằng ngrok

Ngrok đã được cài trên máy này. Tạo tài khoản Free tại https://dashboard.ngrok.com/signup.
Tự nhập authtoken ở PowerShell trên máy, không gửi vào chat:

```powershell
ngrok config add-authtoken TOKEN_CUA_BAN
```

Lấy dev domain cố định trong https://dashboard.ngrok.com/domains, sau đó chạy
tại thư mục gốc BankingPrj (thay URL ví dụ bằng domain thực tế của bạn):

```powershell
.\scripts\Start-PublicDemo.ps1 -PublicUrl 'https://ten-duoc-cap.ngrok-free.app'
```

Script cập nhật chính xác origin được phép trong `.env`, chờ Docker healthy và mở tunnel.
Giữ terminal mở; Ctrl+C dừng chia sẻ nhưng website localhost vẫn chạy.
Free có một dev domain do ngrok cấp, không đổi khi restart; giới hạn lưu lượng/request
và có trang thông báo trước khi vào web. Kiểm tra giới hạn hiện tại:
https://ngrok.com/docs/pricing-limits/free-plan-limits.
Không cần mua tên miền để dùng cách này.

## Tùy chọn sau: tên miền riêng với Cloudflare

Hiện chỉ phục vụ localhost. Khi có tên miền, thêm tên miền vào Cloudflare, cài cloudflared
trên Windows và cấu hình tunnel trỏ tới `http://127.0.0.1:8090`.
Thêm origin HTTPS thực tế vào DOCKER_ALLOWED_ORIGINS trong `.env` (phân cách bằng dấu phẩy),
rồi chạy `docker compose up -d --wait --wait-timeout 300` để áp dụng.
Không dùng origin wildcard. Nginx chỉ chuyển `/api/` tới backend và giữ cùng origin với UI.

Đây là bản demo cá nhân: một broker Kafka, app DB role sở hữu schema để chạy Flyway,
profile dev dùng bootstrap admin. Trước khi dùng dữ liệu thật cần cấu hình triển khai phù hợp,
tách tài khoản migration/runtime, backup/restore và kiểm tra quyền truy cập theo môi trường.
