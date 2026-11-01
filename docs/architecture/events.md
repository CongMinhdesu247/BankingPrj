> Security update 2026-10-28: the best-effort after-commit mechanism below describes historical Phase 10 behavior. Current runtime stores complete envelopes in outbox_events BEFORE_COMMIT in the business transaction. OutboxRelay locks one committed row using FOR UPDATE SKIP LOCKED, waits at most 5 seconds for broker acknowledgement, then marks it PUBLISHED. Failure persists FAILED with a 5-second retry delay. A crash after ACK and before DB commit redelivers the identical envelope; consumer receipts and unique business keys prevent duplicate effects. Account locks are never held during broker I/O. The job polls every 250 ms, at most 20 rows per pass. No historical event backfill is automatic. See [security review](../reviews/security-review.vi.md).

# Event contract — Phase 10

Kafka là adapter trong cùng Spring Boot modular monolith. PostgreSQL giữ money,
quota, idempotency và fraud evidence. Không có API publish/replay public.

## Envelope và topic

```json
{
  "eventId":"98227770-3189-3c35-8c27-e06d2b19f823",
  "eventType":"TRANSFER_COMPLETED",
  "schemaVersion":1,
  "occurredAt":"2026-10-28T05:00:00Z",
  "correlationId":"request-123",
  "aggregateId":"80000000-0000-0000-0000-000000000002",
  "payload":{
    "transactionId":"80000000-0000-0000-0000-000000000002",
    "sourceAccountId":"10000000-0000-0000-0000-000000000003"
  }
}
```

Prefix mặc định banking.dev; test/demo có GUID riêng. Topic đều 3 partition,
replication 1 cho local, retention 7 ngày. Kafka key luôn sourceAccountId.

| Topic suffix | eventType | aggregateId | Payload |
| --- | --- | --- | --- |
| transfer.completed.v1 | TRANSFER_COMPLETED | transaction ID | transactionId, sourceAccountId |
| transfer.failed.v1 | TRANSFER_FAILED | attempt ID | attemptId, actorId, sourceAccountId, outcome, reasonCode |
| fraud.detected.v1 | FRAUD_DETECTED | alert ID | alertId, transactionId, sourceAccountId |

Không credential/JWT, raw username/IP, số tài khoản, amount hoặc balance trong
event. ID là UUID kỹ thuật; consumer query DB để lấy evidence và recipient.
occurredAt lấy timestamp DB, không lấy lúc broker giao message. Correlation ID
truyền qua completed → fraud.detected và log consumer. Schema khác 1, field lạ,
topic/key/aggregate sai hoặc JSON >8192 ký tự bị từ chối trước hiệu ứng DB.

Event ID sinh ổn định từ type/schema v1/aggregate bằng UUID.nameUUIDFromBytes.
Publish lại cùng event phải giữ eventId, occurredAt, correlation và payload.
Không hứa thứ tự toàn hệ thống/giữa topic. DLT replay và recovery có thể tới muộn;
fraud vẫn dùng event-time và PostgreSQL theo Phase 9.

## Transaction, receipt và offset

Transfer mới ghi success attempt trong transaction tiền; rollback không có
completed/success attempt. Replay idempotency không phát completed lần nữa.
Completed chỉ send sau DB commit, producer enable.idempotence=true/acks=all.
Publish không chờ broker ack trên request; max.block 500ms, delivery timeout 3s.
Callback log/đếm lost publish; không biến transfer đã commit thành lỗi HTTP.

Business/system failure hợp lệ được ghi attempt và failure audit trong một
REQUIRES_NEW sau rollback rồi mới phát failed. Auth, JSON/ownership/validation
không tạo business event. Attempt failure không tạo transaction/entry giả.
SYSTEM_FAILURE có thể là kết quả chưa xác định nếu mất kết nối lúc commit;
client phải đối chiếu idempotency/history, không tự gửi lại bằng key mới.

Consumer groups: <prefix>.fraud.v1 (completed), <prefix>.notification.v1 (cả ba
topic). Receipt names bền vững fraud-v1 và notification-v1. Receipt PK là
(consumer_name,event_id); hash canonical envelope phát hiện cùng ID khác nội dung.
Receipt và effect cùng PostgreSQL transaction; exception rollback cả hai.
Kafka AckMode.RECORD/auto-commit=false: chỉ return sau DB commit mới commit offset.
Crash sau DB commit/trước offset có thể redeliver; receipt ngăn effect lần hai.

Fraud giữ unique transaction/analysisVersion nếu hai event ID diễn tả cùng
transaction. Notification cũng dùng business event ID ổn định + recipient/type,
nên không tạo thêm thông báo khi event ID đầu vào bị sinh khác cho cùng fact.
Notification completed gửi actor chuyển tiền; failed gửi actor attempt; fraud
gửi các risk officer enabled. Không gửi email/SMS ra ngoài.

## Retry và dead letter

Mỗi consumer dùng retry blocking cùng partition: mặc định 250ms, 2 retry sau
lần đầu (tối đa 3 lần). Sau đó publish DLT và đợi ack; chỉ coi recovered khi DLT
send thành công. DLT send lỗi giữ offset để thử lại, không silently drop.

DLT: <original-topic>.fraud.dlt cho completed/fraud consumer;
<original-topic>.notification.dlt cho notification consumer. Cùng số partition,
retention 1 ngày. Header tối thiểu: banking-original-topic/partition/offset,
banking-consumer, banking-failure-type; không chép exception message/stack vào DLT.
DLT value giữ nguyên chuỗi record đã deserialize. Poison record có thể chứa input không
hợp lệ: chỉ operator local được xem/replay; không đưa DLT lên UI khách hàng.

## Khoảng trống Phase 10

DB commit rồi process chết/broker unavailable trước publish có thể mất event.
Tương tự alert commit rồi mất fraud.detected có thể mất notification. Kafka
producer idempotence không khép dual-write này. Recovery DB Phase 9 bù analysis
run bị thiếu; không hứa bù mọi notification/event đã mất. Phase 11 dùng outbox.

banking.kafka.enabled=false là chế độ opt-out rõ ràng cho regression Phase 1–9:
trigger fraud trực tiếp sau commit, không Kafka/notification delivery. Không tự
fallback sang chế độ này khi broker lỗi. Runtime/demo và Phase 10 integration
tests luôn bật Kafka thật.

Xem [broker/DLT playbook](../../infra/kafka/README.md) và [API notification](../api/phase-10.md).
