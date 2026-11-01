# Kafka local — Phase 10

Kafka 4.1.2 chạy KRaft một node trong WSL Ubuntu, Java 21. Spring Boot 4.1.2
quản lý Spring Kafka 4.1.2/Kafka clients 4.2.2. Đây là cấu hình development:
PLAINTEXT loopback, replication 1; chưa có TLS/SASL/ACL hoặc HA production.
PostgreSQL tiếp tục quyết định tiền, quota và idempotency.

## Cài và chạy

Từ `backend/`, PowerShell:

```powershell
.\scripts\Install-Kafka.ps1
.\scripts\Start-Kafka.ps1
# Khởi động backend ở terminal riêng sau khi broker sẵn sàng
.\mvnw.cmd spring-boot:run
# Dừng đúng broker local do script quản lý
.\scripts\Stop-Kafka.ps1
```

Installer tải archive Apache cố định 4.1.2, đối chiếu SHA512 chính thức rồi giải
nén vào `/opt/kafka_2.13-4.1.2`. Không nâng dependency ứng dụng tự động. Scripts
khởi tạo cluster ID và thư mục riêng `/tmp/banking-kafka-<GUID>`, ghi PID/config;
stop kiểm tra command line PID trỏ đúng config trước khi gửi signal. Không xóa
topic/data khi dừng. Start mới sau stop tạo cluster mới: broker này có dữ liệu
tạm thời, không phải nơi lưu notification bền vững (notification nằm trong PG).

Listener mặc định `127.0.0.1:9092`, controller `127.0.0.1:9093`. Script dùng
`-Djava.net.preferIPv4Stack=true` để Windows truy cập được WSL localhost forward.
State ở `backend/target/kafka-local-runtime.json`; log trong `directory/logs` và
`directory/console.log` ghi trong state. Cổng bị chiếm làm script từ chối chạy.

Biến môi trường backend:

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| KAFKA_BOOTSTRAP_SERVERS | 127.0.0.1:9092 | Broker từ process backend |
| KAFKA_TOPIC_PREFIX | banking.dev | Namespace topic và consumer group |
| BANKING_KAFKA_ENABLED | true | false chỉ dành chế độ regression Phase 1–9 |

Không dùng chế độ false như cơ chế tự động khi broker mất kết nối. Consumer
fraud trực tiếp chỉ tồn tại trong chế độ opt-out rõ ràng; không phát notification.
Demo và test dùng namespace GUID/broker/PG riêng, không seed `banking_db`.
`Start-PortalDemo.ps1` tự chạy broker 19096/controller 19097. `Test-Postgres.ps1`
tự chạy broker cổng ngẫu nhiên, PostgreSQL và Redis rồi dừng trong finally.

## Topic, group và lag

Backend tạo topic lúc khởi động: `<prefix>.transfer.completed.v1`,
`<prefix>.transfer.failed.v1`, `<prefix>.fraud.detected.v1`. Mỗi topic 3 partition,
retention 7 ngày. Key sourceAccountId giữ thứ tự trong một partition; không hứa
thứ tự giữa topic. Groups `<prefix>.fraud.v1` và `<prefix>.notification.v1`.
Xem [envelope, receipts và giới hạn](../../docs/architecture/events.md).

Từ terminal WSL:

```bash
KAFKA=/opt/kafka_2.13-4.1.2
"$KAFKA/bin/kafka-topics.sh" --bootstrap-server 127.0.0.1:9092 --list
"$KAFKA/bin/kafka-consumer-groups.sh" --bootstrap-server 127.0.0.1:9092 \
  --describe --group banking.dev.fraud.v1
"$KAFKA/bin/kafka-consumer-groups.sh" --bootstrap-server 127.0.0.1:9092 \
  --describe --group banking.dev.notification.v1
```

Consumer dùng auto-commit=false/AckMode.RECORD: DB receipt + effect commit trước
offset. Retry mặc định 250ms × 2 sau lần đầu; quá 3 lần gửi DLT và chờ broker ack.
Batch tối đa 10 record; max.poll.interval 5 phút bao phủ các transaction timeout
15s và bounded retry/DLT, tránh rebalance chỉ vì đặt poll interval quá ngắn.
DLT send thất bại giữ offset, không bỏ message. Broker hồi phục thì consumer
tiếp tục từ offset của group; redelivery được dedupe trong PostgreSQL.

## DLT inspection và replay

DLT completed/fraud: `<completed-topic>.fraud.dlt`. Mỗi topic có
`<original-topic>.notification.dlt`. Retention **1 ngày**, cùng partition nguồn.
Header gồm original topic/partition/offset, consumer, failure class; không chép
stack trace vào payload. Record lỗi có thể chứa input tùy ý: chỉ operator local
xem bằng CLI, không hiển thị raw DLT trên UI hoặc chép vào báo cáo công khai.

1. Kiểm tra log metadata (topic/partition/offset/attempt/failureType), DLT và
   committed DB fact. Xác định lỗi schema/key/evidence hay lỗi dịch vụ tạm thời.
2. Sửa nguyên nhân trước. Envelope không hợp lệ không được tự tạo event ID mới
   hoặc sửa recipient/score để bỏ qua validation. Event không khớp DB tiếp tục bị từ chối.
3. Với event hợp lệ, phát lại **nguyên eventId/key/envelope** bằng công cụ local.
   Dưới đây là ví dụ DLT partition 1, offset 0, chạy từ project root trong WSL:

```bash
bash backend/scripts/Replay-KafkaDlt.sh 9092 \
  banking.dev.transfer.completed.v1 fraud 1 0
```

Script chỉ chấp nhận namespace local dev/test/demo, lấy chính xác coordinate
còn retained, UUID key và JSON một dòng. DLT offset có thể khác source offset;
truyền offset trong DLT, không nhầm `banking-original-offset` trong header.
Không replay poison JSON tùy ý bằng script này. Script đã được integration test
với broker thật: lỗi consumer → DLT → sửa lỗi → replay → đúng một effect.

4. Đối chiếu `(consumer_name,event_id)` trong `processed_events` cùng analysis /
   notification tương ứng; kiểm tra group lag và DLT mới. Replay lại vẫn chỉ một
   effect. DLT record cũ không tự bị xóa khi replay; đừng coi sự tồn tại của nó là
   thất bại mới. Không reset offset cả group hoặc xóa receipt để xử lý một record.

## Mất kết nối và giới hạn

Kafka down không được rollback giao dịch tiền đã commit. Publish sau commit có
max.block 500ms/delivery timeout 3s, lỗi chỉ log metadata và đếm failed publish.
**Phase 10 có khoảng trống commit → publish**: broker/process lỗi có thể mất event
và notification. Recovery Phase 9 chỉ quét bù fraud analysis thiếu, không bù mọi
notification. Phase 11 sẽ đưa outbox vào cùng DB transaction. Không tuyên bố
exactly-once toàn hệ thống hoặc phục hồi đầy đủ trước khi có outbox.

Nguồn: [Apache Kafka 4.1](https://kafka.apache.org/41/),
[Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).
