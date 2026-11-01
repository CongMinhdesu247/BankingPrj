# Báo cáo Phase 10 — Kafka và thông báo, 28/10/2026

## Mục đích và phạm vi

Phase 10 đưa fraud/notification ra khỏi transaction tiền qua Kafka, vẫn trong
một Spring Boot modular monolith. Người dùng có hộp thư cá nhân; risk officer
đi từ thông báo đến evidence điều tra. PostgreSQL tiếp tục quyết định số dư,
ledger, quota, idempotency và hiệu ứng consumer. Không tách microservice.

Trước triển khai đã đọc roadmap/báo cáo Phase 9 và đối chiếu runtime/schema.
Mốc Phase 9 là 153 backend + 57 frontend + 15 browser (225 test). Các bảng
processed_events, notifications, transfer_attempts đã có ở V1; không tạo lại
bảng theo tên migration đề xuất ban đầu trong roadmap.

## Luồng và invariant

- Transfer commit balance/ledger/idempotency/audit/success attempt rồi mới phát
  TRANSFER_COMPLETED. Rollback không phát completed; replay cùng key không phát lại.
- Business/system failure hợp lệ được ghi attempt và audit trong transaction
  REQUIRES_NEW sau rollback, rồi phát TRANSFER_FAILED. Không tạo ledger/transaction
  giả; auth, JSON, ownership và validation không phát business event.
- Consumer fraud nhận completed, kiểm tra committed DB fact/event-time, ghi
  receipt + analysis/alert/matches cùng transaction, rồi phát FRAUD_DETECTED sau commit.
  Recovery DB Phase 9 vẫn quét bù analysis thiếu.
- Consumer notification kiểm tra DB fact/actor/recipient, ghi receipt và notification
  nguyên tử. Completed gửi actor chuyển tiền, failed gửi actor attempt, fraud gửi
  các risk officer enabled. Không gửi email/SMS.

Envelope v1 có eventId ổn định theo type/aggregate, occurredAt từ DB, correlationId,
aggregateId, payload tối thiểu; key sourceAccountId. Không có credential/JWT,
amount/balance, số tài khoản hoặc raw username/IP. Consumer xác minh topic/key/
schema/aggregate và evidence DB. Correlation truyền qua log xử lý và fraud event.

Receipt PK `(consumer_name,event_id)`, hash canonical phát hiện cùng ID khác nội
dung. Receipt và effect cùng PostgreSQL transaction; lỗi trước commit rollback cả
hai. AckMode.RECORD/auto-commit=false chỉ commit offset sau listener/DB commit.
Crash sau DB commit trước offset gây redelivery nhưng không nhân đôi effect.
Unique transaction/analysisVersion và business event ID notification còn bảo vệ
khi hai event ID khác nhau diễn tả cùng một fact.

Retry blocking 250ms, tối đa 3 lần gồm lần đầu; hết retry chuyển DLT riêng consumer,
chờ broker ack trước khi recovered. DLT send lỗi giữ offset. DLT giữ chuỗi record
và metadata tối thiểu, retention 1 ngày; main topic 7 ngày/3 partition/local replica 1.
Không hứa ordering toàn hệ thống hoặc giữa topic. Event trễ dùng thời điểm DB.

## API và UI

GET `/api/v1/me/notifications` có phân trang/unreadOnly; PATCH
`/api/v1/me/notifications/{id}/read` trả 204, idempotent và giữ readAt lần đầu.
Mọi role authenticated chỉ xem recipient của chính mình; ID người khác trả 404.
Không có endpoint publish/DLT/replay public.

Trang `/notifications` có menu customer/nội bộ, lọc chưa đọc, phân trang, làm mới,
đánh dấu đọc và link chứng từ/điều tra theo quyền. SYSTEM_FAILURE hiển thị kết quả
chưa xác định: đối chiếu lịch sử và giữ nguyên key khi retry, không khẳng định tiền
chưa chuyển khi lỗi xảy ra quanh commit.

## Hạ tầng và migration

Kafka 4.1.1 KRaft chạy thật trong WSL Ubuntu/Java 21; archive được xác minh SHA512
Apache. Boot 4.1.2 quản lý Spring Kafka 4.1.2 và Kafka clients 4.2.2. Scripts có
namespace GUID, loopback, PID/config guard khi stop; IPv4 được đặt rõ để Windows
truy cập WSL localhost. Test/demo dùng broker và PostgreSQL/Redis riêng.

V3 bổ sung receipt event_type/payload_hash, source_account_id cho attempts,
unique success transaction và index unread notifications. Không sửa V1/V2, không
seed dữ liệu. Backup banking_db trước V3 ở backend/local-backups (Git ignored).

## Kiểm chứng

Đã hoàn thành Phase 10. Tổng **258 kiểm thử đạt**, không failure/error/skipped:

| Nhóm | Kết quả |
| --- | ---: |
| Backend unit | 50/50 |
| Backend integration PostgreSQL/Redis/Kafka thật | 129/129 |
| Frontend Vitest | 61/61 |
| Browser Chromium API/DB thật | 18/18 |
| Frontend production build | Đạt |

Sau bộ backend đầy đủ, chạy lại 16 Kafka integration với bản đóng gói cuối để
xác nhận poll config và OpenAPI 204. Browser thêm 3 ca notification, giữ 15 ca
portal/fraud cũ, có kiểm tra mobile 390px không tràn ngang. Ảnh thực tế:
[desktop](../screenshots/phase-10-notifications-risk.png),
[mobile](../screenshots/phase-10-notifications-mobile.png).

Lệnh từ backend: `.\scripts\Test-Postgres.ps1` chạy toàn bộ unit/integration trên
PostgreSQL 18.6, Redis và Kafka thật, không dùng banking_db. Có thể chạy riêng
Kafka bằng `-IntegrationTest KafkaWorkflowIT`; nhóm này có 16 ca. Từ frontend:
`npm.cmd test`, `npm.cmd run build`, `npm.cmd run test:e2e` (cần demo tách biệt).

Đã sửa vòng đời context test Kafka bằng AFTER_CLASS để đóng consumer/producer/
pool sau nhóm test: bộ đầy đủ không còn chạm max_connections của PostgreSQL test.
Producer bỏ default listener có thể in payload, chỉ callback log metadata. Đặt
max.poll.interval 5 phút cho batch 10 record và transaction timeout 15s. Read API
khai báo rõ 204 để OpenAPI khớp HTTP; integration kiểm tra cả hai.

Nhóm KafkaWorkflowIT chạy PostgreSQL, Redis và broker thật, không dùng embedded
Kafka hay mock broker. Fault injection chỉ dùng spy để tạo ranh giới lỗi:

| Ca | Điều được chứng minh |
| --- | --- |
| Full flow | Transfer → Kafka → fraud/alert → notification, receipt từng consumer |
| Duplicate / ID khác cùng fact | Một analysis/alert/notification |
| Rollback / giữ transaction chưa commit | Không completed/success attempt trước commit |
| Failed / auth / ownership / JSON | Failed không tạo entry/chạy fraud; request không hợp lệ không phát business event |
| Consumer lỗi trước commit | Receipt + effect rollback rồi retry thành công |
| Lỗi sau DB commit trước offset | Redelivery không nhân đôi effect |
| Dừng/start listener | Group tiếp tục đọc các record chưa xử lý |
| Malformed / schema khác / ID đổi nội dung | Retry hữu hạn → DLT; record tiếp theo vẫn chạy |
| DLT operator replay | CLI thật giữ envelope/key, sửa lỗi rồi tạo đúng một effect |
| Event trễ | Rule dùng event-time và committed PostgreSQL evidence |
| Tắt/bật broker | Ledger/idempotency đúng; publish có thể mất; DB recovery bù analysis |
| Inbox | Phân trang/unread, owner isolation, read idempotent |

## Runtime và dữ liệu sau kiểm thử

API chính `127.0.0.1:8080`, portal `127.0.0.1:5173`, Kafka `127.0.0.1:9092`.
Demo ở `http://127.0.0.1:5174`, API 8081, Redis 6381, Kafka 19096/controller 19097,
PostgreSQL banking_test cổng riêng. Credentials demo trong
`backend/config/portal-demo.credentials.txt` đã Git ignore; không đưa password vào báo cáo.

Demo sau 18 browser test: 19 transfer/19 analysis, 15 alert, 50 notification,
54 consumer receipt, 20 attempt (19 success + 1 business failure). Hai consumer
group có lag 0 ở partition có record. Read-only SQL xác nhận 0 lỗi cặp ledger,
0 lệch balance so với opening reserve + entries; tổng balance giữ 500 triệu VND
mô phỏng. Đây là fixture trong banking_test, không phải tiền/dữ liệu banking_db.

banking_db cá nhân sau V3: customer/account/transaction/entry/analysis/alert/
notification/attempt/receipt/outbox đều **0**, user vẫn **1** như trước Phase 10.
Không seed dữ liệu cá nhân. Backup trước migration và verification summaries
được giữ trong backend/local-backups và backend/target (Git ignored).
OpenAPI snapshot đã xuất lại từ API chạy bản cuối; PATCH read khai báo 204.

## Giới hạn và bước tiếp theo

**Chưa có outbox:** DB commit rồi process chết/broker down trước publish có thể
mất completed/fraud/failed event và notification. Producer idempotence không
giải quyết dual write. Recovery Phase 9 chỉ bù analysis thiếu, không bù mọi thông
báo. Failure request chết trước transaction attempt riêng cũng có thể không có
attempt. Không tuyên bố exactly-once giữa PostgreSQL/Kafka.

Broker là local single node/PLAINTEXT, không có HA, TLS/SASL/ACL, Schema Registry,
CDC hoặc production metrics. Scripts test/demo tạo runtime tạm; stop/start local
mới có thể tạo broker trống. Không dùng dữ liệu broker tạm làm nguồn sự thật tiền.

Phase 11 tiếp theo: ghi outbox cùng transaction completed/fraud/failure, claim
batch bằng SKIP LOCKED/lease/token, chờ broker ack rồi mark published; thử crash
sau claim/ack, lease takeover và nhiều poller. Giữ dedupe consumer Phase 10.

Tài liệu: [event contract](../architecture/events.md), [API](../api/phase-10.md),
[Kafka/DLT playbook](../../infra/kafka/README.md), [roadmap](../development-roadmap.vi.md).
