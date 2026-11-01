# Luồng chuyển tiền nội bộ

Mục đích: minh họa ranh giới giữ tiền/ledger nguyên tử và điểm phối hợp giữa
transfer với thay đổi trạng thái account. Đây là modular monolith, một PostgreSQL.

```mermaid
sequenceDiagram
    actor Client
    participant API as TransferController
    participant TX as Spring transaction proxy
    participant Service as TransferService
    participant Locks as AccountLockService
    participant DB as PostgreSQL
    Client->>API: POST /api/v1/transfers
    API->>TX: transfer(request)
    TX->>DB: BEGIN (READ COMMITTED)
    TX->>Service: transfer(request)
    Service->>Service: Validate amount/currency/source != destination
    Service->>Locks: lockPair(sourceId, destinationId)
    Locks->>DB: SET LOCAL lock_timeout
    Locks->>DB: Refresh + PESSIMISTIC_WRITE UUID nhỏ
    Locks->>DB: Refresh + PESSIMISTIC_WRITE UUID lớn
    DB-->>Locks: State hiện tại, giữ cả hai row lock
    Locks-->>Service: Source/destination đã khóa và refresh
    Service->>Service: Check ACTIVE, VND, funds, destination capacity
    Service->>Locks: debit(source), credit(destination)
    Service->>DB: Flush balances + COMPLETED transaction
    Service->>DB: TransactionLedgerWriter: flush DEBIT
    Service->>DB: TransactionLedgerWriter: flush CREDIT
    Service-->>TX: TransactionDetailResponse
    alt Không có lỗi
        TX->>DB: COMMIT + nhả khóa
        TX-->>API: Response sau commit
        API-->>Client: 201 + Location + transaction/entries
    else Exception tại bất kỳ bước nào trong transaction
        TX->>DB: ROLLBACK + nhả khóa
        TX-->>API: Exception
        API-->>Client: ApiError 400/404/409/503/500
    end
```

Rollback có thể xảy ra trước khi service tạo response, khi đang flush hoặc khi
commit. Controller chỉ nhận kết quả thành công sau khi transaction proxy commit.
Diagram rút gọn nhánh lỗi để minh họa; không tạo record COMPLETED khi chuyển thất bại.

AccountService.changeStatus cũng đi qua AccountLockService.lockOne, rồi kiểm tra
trạng thái và balance sau refresh trước khi ghi. Nếu transfer đang giữ khóa,
status update phải chờ; nếu status update commit trước, transfer đọc thấy trạng
thái mới. Không giữ row lock trong lúc gọi Kafka/network.

Không có tự retry POST, idempotency, daily limit, Kafka/outbox hoặc fraud ở phase 3.
Entry/transaction không có API cập nhật/xóa. Chi tiết contract và test fixture ở
[Transfer API](../api/transfers.md).
