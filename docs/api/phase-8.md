# API Phase 8 — Redis

Không thêm endpoint. Login/transfer giữ payload và response thành công cũ;
transfer replay vẫn HTTP 201, Idempotency-Replayed: true.

POST /api/v1/auth/login: 30 request/IP/60s, bao gồm body invalid. Sau 5 lần sai
trên username chuẩn hóa trong 900s, lần tiếp theo bị chặn. Hết TTL hoặc login
thành công trước ngưỡng reset count. User không tồn tại có cùng policy.

POST /api/v1/transfers: 30 request/user CUSTOMER/60s; token mới cùng user vẫn
chung bucket, user khác bucket riêng. Request lỗi nghiệp vụ và replay cũng tính
rate. Counter Redis không tham gia kiểm tra daily limit.

| Tình huống | HTTP/code | Retry-After |
| --- | --- | --- |
| Vượt rate/lockout | 429 RATE_LIMITED | TTL còn lại làm tròn lên giây, tối thiểu 1 |
| Redis guard không khả dụng | 503 THROTTLE_UNAVAILABLE | 1 |

Body theo ApiError: code/message/fieldErrors/requestId/timestamp/path. Không trả
raw username/IP/key hoặc Redis exception/stack. Guard từ chối không ghi tiền mới.
Client vẫn phải giữ key/payload cũ sau response chưa xác định; không tạo ý định mới
chỉ dựa status HTTP. Frontend hiển thị 503 tiếng Việt và giữ pending transfer.

GET /api/v1/me: user/roles/enabled vẫn query PostgreSQL; chỉ phần customer dùng
cache 60s. Cache lỗi fallback DB. PATCH customer (STAFF/ADMIN) invalidates sau
commit; rollback giữ cache. Không thay scope/quyền của Customer/Account API.

Không tin X-Forwarded-For/Forwarded, dùng IP peer trực tiếp. Chi tiết key/TTL,
outage và runtime trong [Redis guide](../../infra/redis/README.md).
