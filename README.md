# Bank Transaction & Fraud Monitoring System

[![Verify clean checkout](https://github.com/CongMinhdesu247/BankingPrj/actions/workflows/verify.yml/badge.svg)](https://github.com/CongMinhdesu247/BankingPrj/actions/workflows/verify.yml)

Hệ thống mô phỏng giao dịch ngân hàng số và giám sát gian lận theo thời gian thực (Spring Boot + React + PostgreSQL + Redis + Kafka).

---

## 1. Cách chạy chương trình (Chỉ 1 bước)

**Yêu cầu duy nhất:** Cài đặt và bật sẵn **[Docker Desktop](https://www.docker.com/products/docker-desktop/)** (không cần cài Java, Node.js hay PostgreSQL).

- **Trên Windows:** Nhấn đúp vào file **`run.bat`** ở thư mục gốc của dự án.
- **Trên macOS / Linux:** Mở terminal tại thư mục dự án và chạy:
  ```bash
  sh ./run.sh
  ```

Khi chạy **`run.bat`**, chương trình sẽ tự động:
1. Khởi tạo file cấu hình bảo mật `.env` (nếu chưa có).
2. Build và khởi chạy toàn bộ hệ thống qua Docker Compose.
3. Tạo sẵn các tài khoản mẫu cùng giao dịch mẫu.
4. Tự động mở trình duyệt tại địa chỉ **[http://localhost:8090](http://localhost:8090)**.

---

## 2. Thông tin đăng nhập

Sau khi chương trình khởi động xong tại **[http://localhost:8090](http://localhost:8090)**, bạn có thể đăng nhập bằng:

| Nhóm tài khoản | Tên đăng nhập | Nơi xem mật khẩu |
| :--- | :--- | :--- |
| **Khách hàng mẫu** *(có sẵn số dư & giao dịch)* | `mock.an`, `mock.linh`, `mock.minh` | File `backend/config/docker-demo.credentials.txt` |
| **Nhân viên ngân hàng / Rủi ro** | `mock.staff`, `mock.risk` | File `backend/config/docker-demo.credentials.txt` |
| **Quản trị viên (Admin)** | `docker.admin` | Biến `DOCKER_ADMIN_PASSWORD` trong file `.env` |
| **Khách hàng mới** | Nhấn **Tạo tài khoản** trên màn hình đăng nhập | Tự đặt khi đăng ký (số dư ban đầu `0 VND`) |

---

## 3. Các lệnh quản lý nhanh

Mở terminal tại thư mục gốc của dự án khi cần:

```bash
# Tạm dừng ứng dụng (giữ nguyên toàn bộ dữ liệu)
docker compose stop

# Bật lại nhanh (khi không sửa code)
docker compose start

# Xem trạng thái và kiểm tra log khi cần xử lý sự cố
docker compose ps -a
docker compose logs --tail 100
```

**Chia sẻ / Công khai ứng dụng ra Internet (qua ngrok hoặc Cloudflare Tunnel):**
```powershell
# Tự động bật chống giả mạo IP qua Proxy (DOCKER_TRUST_PROXY_HEADERS=true) và mở HTTPS tunnel:
powershell -ExecutionPolicy Bypass -File ./scripts/Start-PublicDemo.ps1 -PublicUrl unselect-rebate-tall.ngrok-free.dev
```
- Nếu triển khai công khai và **không muốn tạo các tài khoản mẫu (`mock.*`)**, chỉ cần đổi `DOCKER_SEED_DEMO=false` trong file `.env` trước khi chạy lần đầu.

> **Lưu ý:** Giữ lại file `.env` để bảo toàn mật khẩu kết nối database giữa các lần chạy. Không chạy `docker compose down -v` nếu bạn muốn giữ lại dữ liệu đã tạo.

---

## 4. Tính năng chính & Cấu trúc dự án

- **Dành cho Khách hàng:** Quản lý tài khoản, chuyển tiền nội bộ (chống trùng lặp giao dịch với Idempotency Key), lưu danh bạ thụ hưởng & mẫu chuyển tiền, xem sao kê tháng, tải biên lai PDF, biểu đồ dòng tiền thu/chi, cài đặt hạn mức cá nhân, bảo mật 2 lớp (TOTP Authenticator) và gửi yêu cầu tra soát.
- **Dành cho Nhân viên & Quản trị viên:** Duyệt hạn mức giao dịch, xử lý yêu cầu hỗ trợ/tra soát, theo dõi cảnh báo gian lận (Fraud Alerts), cấu hình quy tắc chấm điểm rủi ro và tra cứu nhật ký hệ thống (Audit Logs).
- **Trợ lý ảo (AI Chatbot):** Tích hợp sẵn trợ lý hướng dẫn sử dụng; có thể bật thêm phản hồi từ Google Gemini bằng cách điền `GEMINI_API_KEY` vào file `.env`.

```text
BankingPrj/
├── backend/       # API Server (Java 21, Spring Boot 4, Flyway, PostgreSQL, Redis, Kafka)
├── frontend/      # Giao diện người dùng (React 19, TypeScript, Vite)
├── infra/         # Cấu hình Nginx reverse proxy cho Docker
├── scripts/       # Script tự động khởi tạo .env và nạp dữ liệu mẫu
├── docs/          # Tài liệu kiến trúc và hướng dẫn chi tiết
├── run.bat        # Chạy nhanh 1 chạm trên Windows
└── run.sh         # Chạy nhanh trên macOS / Linux
```

### Tài liệu chi tiết
- [Triển khai Docker & chuyển sang máy khác](docs/guides/docker-portable.vi.md)
- [Vận hành bản Demo, sao lưu dữ liệu & chia sẻ qua Cloudflare Tunnel](docs/guides/docker-local.vi.md)
- [Cấu trúc mã nguồn dự án](docs/architecture/project-structure.vi.md)
- [Cấu hình AI Chatbot (Gemini)](docs/guides/ai-chatbot.vi.md)
