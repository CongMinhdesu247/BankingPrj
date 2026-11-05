# Docker: phân tích dự án và chuyển sang máy mới

## Công nghệ và cách chạy xác định từ mã nguồn

| Thành phần | Phiên bản / cấu hình | Cài và chạy trực tiếp |
| --- | --- | --- |
| Backend Java | Java 21, Spring Boot 4.1.2 (backend/pom.xml); Maven Wrapper 3.10.0 | Trong backend: ./mvnw -B verify để tải dependencies, test và đóng gói; ./mvnw spring-boot:run để chạy (Windows: mvnw.cmd) |
| Frontend TypeScript/React | Lockfile: React/React DOM 19.3.1, React Router 8.5.0, TypeScript 5.9.3, Vite 7.3.7; Node >=22.12, Docker/CI dùng Node 24 | Trong frontend: npm ci; npm run dev. Build: npm run build |
| PostgreSQL | Docker image postgres:18; JPA và Flyway migrations V1–V5 | Docker tự khởi tạo DB banking_docker_demo và role banking_demo; backend tự chạy migrations |
| Redis | redis:7-alpine | Guard đăng nhập, giới hạn request, cache; bật AOF và volume /data |
| Kafka | apache/kafka:4.1.2, KRaft một broker | Event/outbox, fraud và notifications; volume lưu log |
| Web server | Nginx stable-alpine | Phục vụ frontend đã build và proxy /api/ tới backend |
| AI tùy chọn | Gemini, gọi từ backend | Không có key thì chatbot dùng nội dung hướng dẫn dự phòng |

Version trong package.json là khoảng cho phép (React ^19.1.1, Vite ^7.1.7);
version ở trên là version thực tế từ package-lock.json. npm ci dùng lockfile.
Không phát hiện dịch vụ MySQL, MongoDB hoặc Prometheus đang được cấu hình chạy;
thư mục infra/prometheus mới là chỗ giữ sẵn.

## Cổng và cấu hình môi trường

| Thành phần | Chạy trực tiếp | Docker |
| --- | --- | --- |
| Frontend | 127.0.0.1:5173 (vite.config.ts) | http://localhost:8090, chuyển vào Nginx :80 |
| API | 127.0.0.1:8080 (mặc định Spring Boot, có thể đặt SERVER_PORT) | backend:8080, bind 0.0.0.0, không publish ra host |
| PostgreSQL | localhost:5432 | postgres:5432, chỉ mạng nội bộ |
| Redis | 127.0.0.1:6379 | redis:6379, chỉ mạng nội bộ |
| Kafka | 127.0.0.1:9092 | kafka:9092; controller :9093 nội bộ |

Compose tự đọc .env. Hai script sinh mật khẩu ngẫu nhiên khi chưa có file này,
không ghi đè cấu hình cũ. Không cần cài Java/Node/Maven/DB riêng trên máy mới.

- Bắt buộc cho Compose: DOCKER_DB_PASSWORD, DOCKER_JWT_SECRET, DOCKER_ADMIN_PASSWORD.
  JWT phải là Base64 của ít nhất 32 byte ngẫu nhiên; script sinh 64 byte.
- Tùy chọn: DOCKER_ALLOWED_ORIGINS (mặc định localhost/127.0.0.1:8090),
  GEMINI_API_KEY và GEMINI_MODEL. API key chỉ được truyền cho backend.
- Khi chạy backend trực tiếp: DB_USERNAME, DB_PASSWORD, JWT_SECRET; DB_URL mặc định
  jdbc:postgresql://localhost:5432/banking_db. Có thể tách MIGRATION_DB_USERNAME /
  MIGRATION_DB_PASSWORD cho Flyway. Root .env không tự được Spring Boot nạp.
- Biến kết nối khác: REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, REDIS_NAMESPACE,
  KAFKA_BOOTSTRAP_SERVERS, KAFKA_TOPIC_PREFIX, SERVER_ADDRESS, SERVER_PORT.
  Compose cấp địa chỉ nội bộ tương ứng. BANKING_KAFKA_ENABLED điều khiển Kafka.
- Bootstrap admin: cần profile dev, BOOTSTRAP_ADMIN_ENABLED=true,
  BOOTSTRAP_ADMIN_USERNAME và BOOTSTRAP_ADMIN_PASSWORD. Compose đã thiết lập.
- CORS backend: BANKING_SECURITY_ALLOWED_ORIGINS. Frontend dev:
  API_PROXY_TARGET; VITE_API_BASE_URL là cấu hình build nếu API khác origin.
  Docker mặc định gọi cùng origin qua /api/v1 nên không cần VITE_API_BASE_URL.

## Ba bước sử dụng trên máy mới

1. Cài và mở Docker Desktop (Linux có thể dùng Docker Engine + Compose hỗ trợ --wait).
   Chờ Docker sẵn sàng, có Internet để tải image và dependency lần đầu.
2. Clone/copy toàn bộ source dự án vào máy mới. Với bản cài mới, để script tạo .env.
   Nếu copy có .env cũ thì giữ nguyên các DOCKER_*; nếu file thiếu chúng, bổ sung
   trước khi chạy. Không copy .env.example thành .env với mật khẩu rỗng.
3. Windows: nhấp đúp run.bat. macOS/Linux: mở terminal ở thư mục dự án và chạy
   sh ./run.sh (hoặc chmod +x run.sh rồi ./run.sh). Khi báo DONE, mở
   http://localhost:8090. Admin docker.admin dùng DOCKER_ADMIN_PASSWORD trong .env;
   khách hàng có thể chọn Tạo tài khoản trên trang đăng nhập.

Script chạy docker compose up -d --build --wait --wait-timeout 300, chỉ báo hoàn tất
sau khi dịch vụ healthy; báo lỗi khi Docker chưa bật, thiếu cấu hình hoặc build/start lỗi.
macOS/Linux có thể cần cho phép chạy file hoặc chọn mở bằng Terminal tùy môi trường desktop.
Hai script chỉ khởi động ứng dụng, không tự seed số dư hay tài khoản mô phỏng.
Muốn dữ liệu demo trên Windows, dùng PowerShell 7 chạy scripts/Seed-DockerDemo.ps1
sau khi app healthy; xem hướng dẫn docker-local.vi.md.

## File Docker và dữ liệu

Dockerfile ở gốc có hai target backend/frontend. Backend build/test bằng JDK 21,
image chạy chỉ chứa JRE 21 và JAR, chạy bằng user banking. Frontend build bằng Node 24,
image chạy chỉ chứa Nginx và file tĩnh. Cache BuildKit giữ Maven/npm dependency giữa
các lần build. .dockerignore loại thư viện local, build output, Git, file .env,
credentials, backup và cache khỏi build context. Dockerfile riêng trong backend/frontend
vẫn dùng được với build context tương ứng; Compose dùng Dockerfile ở gốc.

Giữ nguyên tên project banking-local và các volume postgres_demo_data, redis_data,
kafka_data để dùng tiếp dữ liệu có sẵn. Postgres 18 mount /var/lib/postgresql.
Kafka-init kết thúc với Exited (0) là bình thường. SQL init chỉ chạy trên volume
PostgreSQL mới; đổi DOCKER_DB_PASSWORD trong .env không tự đổi mật khẩu DB cũ.

Dừng giữ dữ liệu: docker compose stop. Khởi động lại: chạy script tương ứng.
Xem lỗi: docker compose ps -a và docker compose logs --tail 100.
docker compose down giữ volumes; down -v xóa dữ liệu.
Copy source không mang theo dữ liệu trong Docker volumes. Nếu cần lịch sử cũ trên
máy mới, backup/restore PostgreSQL theo docker-local.vi.md và chuyển .env qua kênh riêng;
không đưa mật khẩu lên Git. Giữ bản backup gốc cho đến khi kiểm tra restore xong.

Stack này giữ mô hình demo hiện có: profile dev, bootstrap admin, một broker Kafka,
role DB sở hữu schema cho Flyway. Website chỉ publish trên loopback của máy chạy.

## Kiểm tra đã thực hiện

- docker compose config --quiet: đạt.
- sh -n run.sh trong container Linux: đạt.
- docker compose build: cả hai image backend/web build thành công.
- Backend mvn verify trong image: 63 unit test, 0 failure/error/skipped.
- Frontend: npm ci và npm run build (TypeScript + Vite) thành công.
- Chưa chạy toàn stack mới hoặc integration test và chưa thực thi run.bat trên Windows / run.sh trên macOS.
