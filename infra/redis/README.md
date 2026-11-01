> Security update 2026-10-28: login admission now reserves a username attempt using the atomic CONSUME Lua script before password verification. In-flight requests count toward the threshold; a successful login clears the counter. This closes the concurrent CHECK/INCREMENT race across client IPs.

# Redis — Phase 8

Redis giữ trạng thái tạm. PostgreSQL quyết định balance, ledger, daily limit và
idempotency. Không thêm Redis endpoint, distributed money lock hoặc migration.

## Runtime local Windows/WSL

Đã kiểm thử bằng Redis **7.0.15** trong WSL Ubuntu 24.04.1, PostgreSQL **18.6**
native và Java 21. Máy này đã cài redis-server/redis-tools trong Ubuntu.
Chuẩn bị máy khác:

```powershell
wsl -d Ubuntu -u root -- sh -lc 'apt-get update && apt-get install -y redis-server redis-tools'
wsl -d Ubuntu -u root -- service redis-server start
wsl -d Ubuntu -- redis-cli -h 127.0.0.1 -p 6379 ping
```

Backend mặc định dùng 127.0.0.1:6379. WSL cần localhost forwarding. Chỉ bind Redis
loopback, không mở 6379 ra mạng. Runtime local dùng config package Ubuntu;
ứng dụng không phụ thuộc persistence. Có thể thay bằng Redis 7+ trong container
với port loopback. Compose toàn hệ thống vẫn để Phase 13.

Spring Boot đọc REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, REDIS_NAMESPACE. Không
commit password thật. Cấu hình tương đương:

```properties
spring.data.redis.host=127.0.0.1
spring.data.redis.port=6379
spring.data.redis.connect-timeout=500ms
spring.data.redis.timeout=500ms
banking.redis.namespace=banking:dev
```

backend/config/application-local.properties được import, có thể ghi đè cấu hình.
Root .env không tự nạp. Redis tắt không ngăn backend khởi động; login/transfer
trả 503 cho đến khi kết nối phục hồi.

## Key, TTL và owner

Mọi key bắt đầu `<namespace>:v1:`. Mỗi môi trường cần namespace riêng.

| Hậu tố key | Owner | TTL mặc định | Nội dung/policy |
| --- | --- | ---: | --- |
| login-fail:userHmac | auth/LoginAttemptService | 900s từ lỗi đầu | Sau 5 login sai, lần tiếp theo 429; success trước ngưỡng xóa count |
| rate:login:ipHmac | RateLimitService/filter | 60s từ request đầu | 30 POST login/IP, kể cả body invalid |
| rate:transfer:userHmac | RateLimitService/filter | 60s từ request đầu | 30 POST transfer/user CUSTOMER, gồm replay/lỗi nghiệp vụ |
| customer:customerUuid | customer/CustomerCacheService | 60s từ fill | DTO hiển thị; không balance, user status, roles |
| tx-count:sourceUuid:epochWindow | transaction/TransactionCounterService | 120s từ increment đầu | Transfer đi thành công trong bucket UTC 60s, best effort |

Username/IP/user key dùng HMAC-SHA256 với key tách miền từ JWT secret, không raw
username/email/IP/JWT. UUID là định danh kỹ thuật. Xoay JWT secret đổi fingerprint
mới và làm token cũ hết hiệu lực; key cũ tự hết TTL.

Tham số banking.redis.*: login-max-failures, login-ttl-seconds, login-rate-limit,
transfer-rate-limit, rate-window-seconds, cache-ttl-seconds, counter-window-seconds,
counter-ttl-seconds. Tất cả phải dương; đây là policy demo.

Lua thực hiện INCR/check/PEXPIRE nguyên tử, sửa expiry thiếu. Request bị chặn
không gia hạn TTL. Rate window bắt đầu ở request đầu; counter epoch bucket không
phải rolling velocity chính xác.

Login count tăng sau xác minh thất bại. Những xác minh đã qua check trước khi
chạm ngưỡng có thể hoàn tất đồng thời; chưa tuần tự hóa từng username. Lockout
có rủi ro bị lợi dụng gây DoS; IP throttle không thay MFA/chống tấn công phân tán.

## Cache và commit

Chỉ /me dùng cache phần customer. User/roles/enabled và ownership luôn đọc DB.
CustomerService.requireActiveCustomerId/get dùng DB cho nghiệp vụ nội bộ.
Không cache số dư, hạn mức, idempotency hoặc quyết định quyền.

Cache-aside dùng JSON DTO và kiểm tra ID. 64 khóa cố định trong JVM gộp miss cùng
hồ sơ. Redis placeholder loading: có TTL tối đa 5s và token random; Lua chỉ fill
khi token còn khớp. Sau DB commit, event xóa cache/placeholder. Reader cũ đang
chạy không thể fill trở lại sau invalidation; reader đó vẫn có thể nhận snapshot
đã đọc trước update. Instance khác thấy placeholder có thể đọc DB; chưa có
distributed single-flight. Không cache list/query khác.

Rollback không invalidation. Nếu invalidation lỗi, bản cũ có thể còn tối đa TTL
của nó (60s); Redis read lỗi chuyển về DB. JSON hỏng cũng fallback; lỗi DB giữ
nguyên, không load hai lần. Hit/miss/error đo bằng Stats trong process và DEBUG
log; không mở API metrics mới. Lỗi adapter log operation/type/requestId, không
log key/value/credentials.

Counter đăng ký event trong transaction và tăng AFTER_COMMIT, chỉ source của
INTERNAL_TRANSFER mới, không OPENING/replay/rollback. Redis lỗi được log, không
làm response chuyển tiền thất bại. Callback có thể làm response chậm đến timeout;
không thể rollback tiền đã commit. Crash có thể mất count; callback trùng có thể
tăng trùng. Phase 9 phải query PostgreSQL cho rule velocity chính xác; event/outbox
sau này cần dedup/rebuild.

## Lỗi và HTTP

- Cache lỗi → DB; invalidation/counter lỗi → WARN, best effort.
- Login/transfer throttle hoặc login-attempt adapter lỗi → **503
  THROTTLE_UNAVAILABLE**, Retry-After: 1; không giả 429, không fallback guard memory.
  Không phát token nếu reset login guard lỗi dù password đúng.
- Vượt ngưỡng → **429 RATE_LIMITED**, Retry-After là PTTL làm tròn lên giây, tối thiểu 1.
- Filter sau JWT/URL authorization, chỉ nằm trong security chain. Anonymous
  transfer vẫn 401; role nội bộ vẫn 403. Redis check trước transaction tiền.
- server.forward-headers-strategy=none: chỉ dùng peer trực tiếp, bỏ qua
  X-Forwarded-For/Forwarded. Sau reverse proxy hiện tại các user chia sẻ IP proxy;
  muốn đổi cần trusted proxy allowlist, không bật tin mọi header.
- Retry transfer giữ đúng key/payload trong PostgreSQL. Throttle có thể chặn cả
  replay đến khi Redis phục hồi/hết window.

Restart/eviction Redis có thể mất guard/cache/counter. Lịch sử login, tiền, quota
ngày và idempotency tiếp tục còn trong PostgreSQL.

## Test/demo tách biệt

Từ backend/:

```powershell
.\scripts\Test-Postgres.ps1
.\scripts\Test-Postgres.ps1 -IntegrationTest PhaseEightRedisIT
```

Script tạo Redis WSL trên port ngẫu nhiên, namespace banking:test:random,
directory /tmp/banking-redis-random, không persistence; PostgreSQL riêng trong
target/postgres-test, database banking_test. Test kiểm tra namespace, port khác
6379 và directory trước shutdown; không FLUSHDB/FLUSHALL, chỉ xóa namespace test.
Outage/restart tắt/bật Redis thật. Finally dọn hai server.

Chạy script trực tiếp, không redirect toàn script bằng *> vì pg_ctl Windows có
thể giữ pipe kế thừa từ process nền. Kết quả ở target/surefire-reports và
target/failsafe-reports. Regression dùng rate threshold cao; PhaseEightRedisIT
đặt policy riêng: 3 fail, 4 login/3 transfer mỗi 2s, TTL 2–3s. Không tắt security,
không mock Redis. Script hiện hỗ trợ Windows/WSL; CI Linux/Testcontainers để sau,
không tự skip test khi thiếu runtime.

Start-PortalDemo.ps1 tạo Redis riêng 6381, banking:demo:random; Stop-PortalDemo.ps1
kiểm tra directory trước dừng Redis. Không dùng Redis local 6379 hoặc PostgreSQL
cá nhân để seed demo. Script từ chối port Redis đã có server. Trước verify/package
Windows, dừng backend local và demo đang đọc cùng JAR.

Tham khảo: [Redis INCR](https://redis.io/docs/latest/commands/incr/),
[Redis scripting](https://redis.io/docs/latest/develop/programmability/),
[Spring Data Redis scripting](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html).
