# API Phase 10 — Notification và event hậu kiểm

Không có API publish/replay Kafka. Transfer contract, số dư, quota, PostgreSQL
idempotency và HTTP replay 201 giữ nguyên. Kafka chỉ nhận completed sau commit;
fraud/notification xử lý bất đồng bộ. Broker lỗi không làm rollback tiền đã commit.

Mọi user đã xác thực có hộp thông báo riêng:

- GET /api/v1/me/notifications?page=0&size=20&unreadOnly=false
- PATCH /api/v1/me/notifications/{id}/read → 204

size 1–100; sort createdAt DESC,id DESC; PageResponse hiện có. Notification gồm
id, eventId, type, content tối thiểu, status UNREAD/READ, readAt, createdAt.
PATCH read idempotent, không đổi readAt của lần đọc đầu. ID của user khác hoặc
không tồn tại trả 404 NOTIFICATION_NOT_FOUND, không tiết lộ recipient. Thiếu JWT 401.

| Type | Recipient | Content |
| --- | --- | --- |
| TRANSFER_COMPLETED | Actor của transaction | transactionId |
| TRANSFER_FAILED | Actor của attempt | attemptId, reasonCode, outcome |
| FRAUD_DETECTED | Các RISK_OFFICER enabled khi xử lý | alertId, transactionId, riskLevel, score |

Không email/SMS, token, PII hoặc balance trong notification. Link detail vẫn kiểm
tra quyền hiện tại, notification không cấp quyền truy cập mới. SYSTEM_FAILURE
có thể là kết quả chưa xác định; UI yêu cầu đối chiếu history/giữ nguyên key.
Business failure không được giả thành một transaction COMPLETED.

Trang /notifications có filter chưa đọc, phân trang, đánh dấu đã đọc và link tới
chứng từ/điều tra. Làm mới để xem cập nhật đã được consumer xử lý.

Xem [event contract](../architecture/events.md) và [Kafka/DLT playbook](../../infra/kafka/README.md).
