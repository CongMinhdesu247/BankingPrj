# Rà soát chất lượng sản phẩm — 31/10/2026

## Phạm vi và kiểm chứng

Rà soát mã backend/frontend, cấu hình build, các bộ test, hạ tầng và tài liệu hiện có. Đây là đánh giá mã nguồn và khả năng vận hành; chưa phải kiểm thử trải nghiệm trực tiếp trên trình duyệt hay đánh giá hệ thống production. Không thay đổi mã nghiệp vụ hoặc dữ liệu.

Đã chạy trong phiên này:

- Frontend `npm.cmd test`: **13 file, 75 test pass**.
- Frontend `npm.cmd run build`: **pass**; Vite cảnh báo chunk lớn. Bundle ứng dụng khoảng 433 kB, gzip 128 kB; chunk PDF khoảng 1.148 kB, gzip 509 kB.
- Backend `mvnw.cmd test`: **53 test pass**, không failure/error/skip. Lệnh này chỉ chạy unit test, không chạy các lớp `*IT`.

Chưa chạy lại PostgreSQL/Redis/Kafka integration, Playwright E2E, kiểm thử tải hoặc phục hồi backup. Kết quả integration trong báo cáo ngày 28/10 là lịch sử, không được tính là kiểm chứng cho mã ngày hôm nay. Chưa kiểm tra advisory dependency mới trong phiên này.

## Nền tảng nên giữ

- Chuyển tiền có transaction, khóa hai account theo thứ tự, kiểm tra ownership, hạn mức, ledger và idempotency.
- Có outbox, receipt deduplication, retry/DLT và fraud recovery.
- Đã có MFA/TOTP, recovery codes, quản lý/revoke phiên và thay đổi mật khẩu; báo cáo cũ chưa cập nhật các tính năng này.
- Frontend giữ access token trong memory, lưu pending transfer theo owner, hỗ trợ retry cùng key và dùng chuỗi/BigInt cho tiền.
- Có unit, integration và E2E, bao gồm concurrency và rollback. Nhu cầu chính là tự động hóa việc chạy và bổ sung các tình huống còn thiếu.

## Các việc cần ưu tiên

### 1. P1 — Tách khóa mã hóa MFA khỏi khóa ký JWT

**Bằng chứng:** `backend/src/main/java/com/minh/banking/auth/service/Totp.java:17–18` tạo khóa AES từ `banking.security.jwt.secret`. Ciphertext hiện chỉ chứa IV và dữ liệu mã hóa, không có key version.

**Tác động:** nếu operator đổi JWT secret để thu hồi token, khóa giải mã TOTP cũng đổi. Secret MFA đã lưu không còn giải mã được; `verifyOtp` giải mã trước khi kiểm tra recovery code nên đường recovery code cũng có thể bị chặn bởi lỗi này. Đây là hệ quả đọc từ mã, chưa thực hiện đổi khóa trên dữ liệu thật.

**Cải thiện:** khóa MFA riêng, lưu key version, hỗ trợ đọc khóa cũ trong thời gian chuyển đổi và re-encrypt có kiểm soát. Không chỉ đổi tên cấu hình rồi bỏ khóa cũ.

**Nghiệm thu:** test với secret MFA đã mã hóa, đổi khóa JWT vẫn đăng nhập bằng OTP/recovery code được; đổi khóa MFA theo quy trình vẫn đọc được dữ liệu cũ.

### 2. P1 — Hoàn thiện kênh khôi phục mật khẩu dùng ngoài local

**Bằng chứng:** `PasswordRecoveryController` chỉ tạo token trong nhánh `if(localMailbox)`; mặc định tắt, và chỉ cho phép bật khi bind loopback. `/recovery-options` trả `UNAVAILABLE` khi chưa bật.

**Tác động:** backend có xử lý reset token nhưng chưa có kênh gửi hướng dẫn khôi phục cho người dùng triển khai thực tế. Frontend đã mô tả trạng thái này; đây là chức năng chưa hoàn thiện, không phải khẳng định UI báo gửi email thành công.

**Cải thiện:** delivery adapter tới kênh đã xác minh, gửi bền vững, URL frontend theo môi trường, audit và giới hạn tần suất theo chính sách.

**Nghiệm thu:** luồng quên mật khẩu → nhận hướng dẫn → đổi mật khẩu → phiên cũ bị từ chối chạy xuyên suốt trong staging; token hết hạn/chỉ dùng một lần và MFA vẫn được kiểm tra.

### 3. P1 — Đưa kiểm thử vào CI và làm môi trường chạy tái lập được

**Bằng chứng:** chưa có `.github/workflows`; `docker-compose.yml` là `services: {}`. `Test-Postgres.ps1` phụ thuộc PostgreSQL cài trên Windows và Redis/Kafka trong WSL Ubuntu. `package.json` chưa có lint script.

**Tác động:** test có sẵn nhưng chất lượng phụ thuộc người chạy tay và cấu hình từng máy; một lượt `mvn test` xanh không kiểm chứng integration.

**Cải thiện:** pipeline build/unit/lint; job integration với PostgreSQL/Redis/Kafka tạm; job E2E cho demo cách ly, giữ guard tránh database cá nhân. Bổ sung browser test cho MFA/reset/revoke phiên, sao kê và duyệt hạn mức: các file E2E hiện tập trung portal, fraud và notifications.

**Nghiệm thu:** checkout sạch chạy được pipeline; lỗi ledger, phân quyền hoặc migration khiến pipeline fail; có artifact/log đủ chẩn đoán.

### 4. P1 — Có giám sát và quy trình phục hồi có thể kiểm chứng

**Bằng chứng:** `infra/prometheus/README.md`, `infra/grafana/README.md` đều là placeholder; POM chưa có Actuator/Prometheus. Có `docs/database/ledger-checks.sql`, nhưng chưa thấy job đối soát định kỳ hay script backup/restore trong phạm vi đã rà soát.

**Tác động:** khó phát hiện backlog outbox, fraud chưa phân tích, consumer lag, pool DB cạn hoặc sai lệch ledger. Tài liệu nhắc backup chưa chứng minh phục hồi được.

**Cải thiện:** metric API latency/error, DB pool, outbox pending/failed/tuổi event, consumer lag/DLT, fraud backlog; cảnh báo có người xử lý. Tự động hóa đối soát chỉ đọc, backup và diễn tập restore; xác định RPO/RTO theo mục tiêu sản phẩm.

**Nghiệm thu:** chủ động dừng broker trên staging và nhận cảnh báo; sau bật lại backlog giảm; restore sang DB mới rồi kiểm tra ledger/balance thành công.

### 5. P2 — Giảm tác động của broker chậm và chuẩn bị tăng trưởng dữ liệu

**Bằng chứng:** `OutboxRelay.relayOne` giữ transaction/row lock trong lúc chờ Kafka ACK; publisher chờ tối đa 5 giây, retry đặt lại sau 5 giây cố định. `OutboxJob` gọi tuần tự tối đa 20 lần mỗi lượt. `SupportTicketController.detail` tải toàn bộ tin nhắn một ticket. Chưa thấy job cleanup dữ liệu outbox published/session/reset token hết hạn.

**Tác động:** khi broker lỗi kéo dài, kết nối DB bị giữ trong lúc chờ; ticket dài và bảng tích lũy có thể làm response/query chậm. Chưa đo tải nên chưa kết luận có nghẽn thực tế hoặc cần thay kiến trúc.

**Cải thiện:** đo tải với broker chậm; cân nhắc claim/lease ngắn và worker giới hạn, backoff có jitter; giữ nguyên event ID và consumer dedup. Phân trang messages, retention rõ ràng cho dữ liệu kỹ thuật; không xóa ledger/audit theo cleanup chung.

**Nghiệm thu:** đo p95/p99, backlog và DB pool dưới tải đã thống nhất; phục hồi sau outage không mất event hoặc tạo hiệu ứng nghiệp vụ trùng.

### 6. P2 — Giúp người chuyển tiền xác nhận người nhận rõ hơn

**Bằng chứng:** `RecipientQueryService.Recipient` chỉ có id, accountNumber, currency, status. `TransferPage` hiển thị nickname do người dùng đặt hoặc “Tài khoản trong hệ thống”; hộp xác nhận không có tên người nhận do backend xác minh.

**Tác động:** người dùng khó phát hiện nhập nhầm một số tài khoản hợp lệ; nickname không chứng minh chủ tài khoản.

**Cải thiện:** sau khi xác định chính sách quyền riêng tư, cung cấp tên hiển thị được phép tiết lộ từ backend và phân biệt rõ với tên gợi nhớ. Cân nhắc xác thực bổ sung cho giao dịch theo mức rủi ro; MFA đăng nhập hiện không đồng nghĩa với xác nhận riêng nội dung chuyển tiền.

**Nghiệm thu:** kiểm tra UI desktop/mobile, tài khoản bị khóa, đổi số sau lookup và thông tin người nhận trong bước xác nhận.

### 7. P2 — Cải thiện trải nghiệm hết phiên và độ tin cậy của đăng xuất

**Bằng chứng:** `AuthProvider` đặt timeout theo TTL, reload mất phiên; không có bước cảnh báo hết phiên hoặc refresh flow. `finishLogout` gửi revoke theo kiểu không chờ và bỏ qua lỗi.

**Tác động:** người dùng có thể mất nội dung form khi hết phiên. Nếu request logout thất bại, UI đã thoát nhưng phiên server vẫn còn hiệu lực đến hạn; backend đã có revoke phiên, vì vậy không nên mô tả dự án là hoàn toàn chưa có server-side logout.

**Cải thiện:** cảnh báo trước hết phiên, bảo toàn bản nháp phù hợp, tái xác thực rồi quay lại luồng đang làm; xác định và thể hiện rõ kết quả revoke khi mất mạng. Chỉ thêm refresh rotation nếu yêu cầu trải nghiệm cần nó, không lưu access token lâu dài chỉ để tránh đăng nhập lại.

**Nghiệm thu:** test hết hạn khi nhập form, đang chuyển tiền, logout mất mạng và token của phiên đã revoke bị từ chối.

### 8. P2 — Sao kê đầy đủ và tối ưu hiệu năng theo số đo

**Bằng chứng:** `StatementPage` xuất CSV/in chỉ trang đang hiển thị và đã có chú thích rõ. API cho phép phân trang. Build cảnh báo chunk PDF khoảng 1,15 MB; `ReceiptActions` đã dùng dynamic import nên chunk này không phải toàn bộ chi phí tải trang đầu.

**Cải thiện:** thêm xuất toàn kỳ với snapshot nhất quán, giới hạn kích thước/thời gian phù hợp và ghi rõ phạm vi kỳ. Đo thời gian mở trang, xuất PDF trên thiết bị yếu trước khi chọn tối ưu font/PDF hoặc route splitting.

**Nghiệm thu:** tháng có hơn 100 bút toán xuất đủ, tổng thu/chi khớp sao kê; không trùng/mất dòng khi có giao dịch đồng thời; đo được thời gian xuất trên thiết bị mục tiêu.

### 9. P2/P3 — Đồng bộ tài liệu và giảm chi phí bảo trì

**Bằng chứng:** README vẫn có đoạn nói còn gap commit/publish trước outbox trong khi các phần khác xác nhận outbox đã có. Báo cáo bảo mật 28/10 mô tả chưa có MFA/reset/revoke phiên, khác mã hiện tại. Các controller mới như `PersonalLimitController`, `SupportTicketController` gộp DTO, SQL, workflow, authorization và audit; nhiều phương thức viết dồn một dòng.

**Cải thiện:** ghi rõ báo cáo lịch sử, thêm trạng thái hiện tại và hướng dẫn cấu hình các tính năng mới. Tách service nghiệp vụ/repository khi sửa các module này, định dạng mã và thêm lint; giữ ranh giới transaction/lock và test rollback hiện có. Chưa có lý do từ lần rà soát này để chuyển sang microservices.

**Nghiệm thu:** người mới chạy được bằng hướng dẫn hiện tại; tài liệu không còn nhầm giữa planned/implemented/verified; refactor giữ nguyên hợp đồng API và toàn bộ test liên quan.

## Thứ tự triển khai đề xuất

1. Khắc phục phụ thuộc khóa MFA/JWT, thêm test rotation; cập nhật tài liệu sai hiện trạng.
2. CI + môi trường cách ly tái lập được; chốt regression cho các luồng mới.
3. Hoàn thiện recovery delivery, metrics/cảnh báo, backup/restore và đối soát.
4. Nâng UX người nhận, hết phiên, sao kê toàn kỳ.
5. Đo tải/outage để quyết định tối ưu outbox, query, bundle và retention; refactor có mục tiêu.

P1 là ưu tiên xử lý trước khi mở rộng sử dụng ngoài demo; P2 là cải thiện độ tin cậy/trải nghiệm tiếp theo; P3 là bảo trì. Các nhãn này là ưu tiên sản phẩm trong báo cáo, không phải điểm CVSS hoặc kết luận đã khai thác lỗ hổng.
