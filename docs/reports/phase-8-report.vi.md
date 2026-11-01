# Báo cáo Phase 8 — Redis, 27/10/2026

## Mục đích và tác dụng

Đã triển khai Redis làm trạng thái tạm để giảm brute force/burst request, giảm
lượt đọc hồ sơ và cung cấp counter giao dịch phục vụ thống kê/sàng lọc sau này.
PostgreSQL tiếp tục quyết định balance, ledger, hạn mức theo ngày Việt Nam và
idempotency 7 ngày. Không thêm bảng/migration hoặc đổi đường khóa tiền.

Trước khi triển khai đã đọc development-roadmap.vi.md và phase-7-report.vi.md,
đối chiếu code, cấu hình, test reports và process/port đang chạy. Điểm xuất phát
đúng 87 backend + 47 frontend + 9 browser; Redis chưa có client/runtime, Compose
vẫn placeholder. Có backend local 8080 và portal demo 8081/5174 đang dùng JAR cũ.
Đã giải thích mục đích và policy Redis trước khi sửa code.

## Phạm vi hoàn tất

Spring Boot starter Redis lấy phiên bản do Boot 4.1.2 quản lý, dùng Lettuce,
StringRedisTemplate và Lua. Thêm RedisConfig, RedisProperties, RedisKeyFactory,
RedisStateStore, LoginAttemptService, RateLimitService/Filter, CustomerCacheService,
TransactionCounterService và event sau commit.

| Chức năng | Policy mặc định | Khi Redis lỗi |
| --- | --- | --- |
| Login sai theo username HMAC | 5 lần sai trong TTL 900s; lần tiếp theo 429; success trước ngưỡng reset | 503 THROTTLE_UNAVAILABLE |
| Rate login theo IP HMAC | 30 POST/60s từ request đầu, gồm body invalid | 503, Retry-After: 1 |
| Rate transfer theo user HMAC | 30 POST/60s, token mới cùng user chung bucket | 503 trước transaction tiền |
| Hồ sơ customer trong /me | DTO cache-aside 60s; invalidation AFTER_COMMIT | Đọc DB, log fallback |
| Counter transfer đi | Bucket epoch 60s, TTL 120s; tăng AFTER_COMMIT | Log mất update, không làm rollback tiền |

429 RATE_LIMITED có Retry-After lấy từ PTTL, làm tròn lên giây, tối thiểu 1.
Request bị chặn không kéo dài window. Lua gộp increment/check/TTL nguyên tử và
sửa key thiếu expiry. Namespace có version, dev/test/demo riêng. Username/IP/user
key dùng HMAC tách miền từ JWT secret; không raw PII hoặc JWT trong key/log adapter.

Filter nằm sau JWT/URL authorization trong security chain, không chạy hai lần
như servlet filter. Anonymous transfer vẫn 401; role nội bộ vẫn 403. Chỉ dùng peer
IP, không tin X-Forwarded-For/Forwarded. Sau reverse proxy, IP bucket hiện sẽ theo
proxy; cần thiết kế trusted proxy allowlist trước khi đổi policy này.

## Cache và counter giữ đúng ranh giới

Cache chỉ phần customer DTO hiển thị của /me; không user/roles/enabled, balance,
limit hoặc idempotency. Converter/ownership vẫn query PostgreSQL. API nội bộ
requireActiveCustomerId/get tiếp tục dùng DB. Test warm cache rồi revoke user vẫn 401.

64 khóa cố định trong JVM gộp các miss cùng hồ sơ. Placeholder có token và TTL
tối đa 5s; Lua chỉ fill nếu token còn khớp. Update commit xóa cả cache/placeholder,
nên reader cũ không thể fill lại sau invalidation. Rollback không invalidation.
Test đồng bộ reader với update thật chứng minh điều này. Hit/miss/error có Stats
và DEBUG log; kiểm thử đo được miss đầu, hit sau và miss sau update.

Transfer phát event sau khi đã ghi ledger/audit/idempotency trong transaction;
listener chỉ tăng counter khi commit thành công. OPENING, replay, transfer từ chối
và rollback không tăng. Lỗi counter được giữ trong callback, không biến transfer
đã commit thành response lỗi. Redis không tham gia tiền/quota/idempotency.

Frontend bổ sung thông báo tiếng Việt cho THROTTLE_UNAVAILABLE. Hai test mới
kiểm tra mapping 503 và giữ pending transfer đúng key/payload khi gặp 503. Không
đổi hợp đồng replay cũ: HTTP 201, Idempotency-Replayed: true.

## Runtime và cách chạy

Đã cài Redis 7.0.15/redis-tools trong WSL Ubuntu 24.04.1; PostgreSQL native 18.6,
Java 21.0.12.1. Maven unit tests không cần Redis/PostgreSQL.

| Môi trường đang chạy | Portal | Backend | Redis | PostgreSQL |
| --- | --- | --- | --- | --- |
| Local chính | 127.0.0.1:5173 | 127.0.0.1:8080 | 127.0.0.1:6379 | banking_db cá nhân, không seed |
| Demo | 127.0.0.1:5174 | 127.0.0.1:8081 | 127.0.0.1:6381 | banking_test trong cluster portal-demo riêng |

Demo dùng namespace banking:demo:random, Redis không persistence. Credentials mới
ở backend/config/portal-demo.credentials.txt (Git ignored). Không in secret vào
báo cáo. Start-PortalDemo.ps1/Stop-PortalDemo.ps1 quản lý thêm Redis; kiểm tra runtime
directory trước shutdown, từ chối Redis port đã được dùng. Dữ liệu opening/demo
chỉ tạo trong banking_test có guard database/user như Phase 7.

Script Test-Postgres.ps1 tạo cả PostgreSQL và Redis riêng, port ngẫu nhiên,
banking:test:random, tự dọn khi xong. Test lifecycle tắt/bật đúng instance riêng;
không FLUSHDB/FLUSHALL, không kết nối banking_db để chạy fixture.

Từ backend/:

```powershell
.\scripts\Test-Postgres.ps1
# Chạy riêng Phase 8 cùng unit tests khi cần điều tra:
.\scripts\Test-Postgres.ps1 -IntegrationTest PhaseEightRedisIT
# Sau verify, khởi động lại demo:
.\scripts\Start-PortalDemo.ps1
```

Từ frontend/: npm.cmd test; npm.cmd run build; npm.cmd run test:e2e.
Trước verify/package Windows cần dừng backend local/demo đang đọc JAR. Chạy script
test trực tiếp; redirect toàn script bằng *> có thể làm pg_ctl Windows chờ pipe
kế thừa từ process nền. Reports lưu trong target/surefire-reports và failsafe-reports.

## Kết quả kiểm thử

**163 test đạt, không failure/error/skipped; backend package và frontend build đạt.**

| Lớp | Kết quả |
| --- | ---: |
| Backend unit | 11/11 |
| Backend PostgreSQL/HTTP/Redis integration | 94/94 |
| Tổng backend (87 cũ + 18 Phase 8) | 105/105 |
| Frontend unit/component (47 cũ + 2) | 49/49 |
| Browser Chromium desktop/mobile | 9/9 |
| TypeScript + Vite production build | PASS |

Full backend verify hoàn thành 27/10/2026 lúc 22:59 giờ Việt Nam, BUILD SUCCESS.
Browser chạy sau khi khởi động demo mới với Redis riêng: 9/9 trong 27,9s.

18 ca PhaseEightRedisIT chạy Redis/PostgreSQL thật:

1. 64 increment đồng thời không mất update, key có TTL, username chuẩn hóa/HMAC.
2. 40 request đồng thời chỉ 7 được admit, count không vượt quota và sửa expiry thiếu.
3. TTL login/rate/cache/counter đều hết hạn; deny không gia hạn window.
4. Login 3 sai theo policy test, lần sau 429, hết TTL đăng nhập được; history ở DB.
5. Login đúng trước ngưỡng reset count; lỗi sau đó bắt đầu lại từ 1.
6. User không tồn tại có cùng lockout; key không raw username/IP.
7. Rate login gồm body invalid, đổi forwarded IP không bypass; Retry-After/hết window.
8. Rate transfer theo user qua nhiều token; user khác độc lập; 401/403 còn đúng.
9. Cache miss/hit, commit update invalidates rồi đọc hồ sơ mới.
10. Cache ấm không bypass revoke user hoặc scope hồ sơ.
11. Rollback update giữ cache; rollback transfer không counter/idempotency/ledger mới.
12. Reader DB cũ đang chạy không fill cache lại sau update commit/invalidation.
13. 24 lượt đọc đồng thời được gộp thành một DB loader trong JVM.
14. JSON cache hỏng fallback; lỗi DB không bị che và không gọi loader hai lần.
15. Tắt Redis thật: PATCH/GET hồ sơ vẫn dùng DB; login/transfer 503, tiền/key/history không đổi.
16. Redis tắt ngay trước commit tiền: counter lỗi nhưng commit thành công; restart mất
    key Redis, replay vẫn đúng và daily quota đã dùng vẫn bị PostgreSQL từ chối.
17. Transfer mới tăng counter đúng một; replay/lỗi nghiệp vụ không tăng.
18. OpenAPI mô tả cả 429/503 và Retry-After cho login/transfer.

Regression vẫn kiểm tra locking/concurrency/rollback tiền, idempotency, quota qua
nửa đêm, security, beneficiary và portal. Threshold của regression được tăng để
không làm test phase cũ đo nhầm rate policy; nhóm Phase 8 có ngưỡng nhỏ riêng,
không mock Redis hoặc tắt security.

Đã kiểm tra chỉ đọc banking_db trước/sau restart runtime: **0 customers, 0 accounts,
0 transactions, 0 entries, 1 user**, không thay đổi. PostgreSQL cá nhân tiếp tục
chạy, không bị stop/seed/reset. Không sửa V1 migration; Flyway validate thành công.

## Giới hạn và bước tiếp theo

Phase 9 đã được triển khai tiếp ngày 28/10/2026: xem [báo cáo Phase 9](phase-9-report.vi.md).
Mốc 163 test và runtime/policy demo phía trên là lịch sử hoàn tất Phase 8.
Demo Phase 9 có dữ liệu cảnh báo và policy login fixture riêng, ghi rõ trong báo cáo mới.

Counter là best effort: crash có thể mất update, callback trùng có thể đếm trùng;
Phase 9 phải query PostgreSQL để quyết định velocity chính xác. Callback Redis có
thể thêm độ trễ đến command timeout dù tiền đã commit. Khi invalidation lỗi nhưng
Redis sau đó phục hồi, cache cũ có thể tồn tại đến TTL còn lại, tối đa 60s.

Single-flight chỉ trong JVM; instance khác có thể fallback DB khi gặp placeholder.
Login đã qua precheck có thể hoàn tất đồng thời trước khi count chạm ngưỡng;
lockout có tradeoff DoS theo username. Redis restart/eviction reset trạng thái tạm,
không reset quota/idempotency trong DB. Chưa có Cluster/Sentinel, distributed lock,
MFA, Kafka/outbox, full Compose hoặc metrics endpoint; không mở rộng sang Phase 9.

Chi tiết: [Redis key/TTL/outage và vận hành](../../infra/redis/README.md),
[API Phase 8](../api/phase-8.md), [OpenAPI snapshot](../api/openapi.yaml).
