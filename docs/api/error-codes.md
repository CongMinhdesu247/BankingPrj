# API v1 — Error contract

Mục đích: client phân biệt sai input, xung đột nghiệp vụ, truy cập trái quyền và
lỗi hệ thống mà không cần đọc SQL hoặc suy đoán từ HTTP 200.

Tất cả lỗi có `code`, `message`, `fieldErrors` (array), `requestId`, `timestamp`
(ISO Instant), `path`. FieldError chỉ có `field` và `message`, không echo giá trị
đã gửi. Request ID được trả cả trong header X-Request-Id và dùng trong log.

```json
{
  "code": "INVALID_REQUEST",
  "message": "Request parameter validation failed.",
  "fieldErrors": [{"field": "size", "message": "must be less than or equal to 100"}],
  "requestId": "demo-001",
  "timestamp": "2026-10-27T08:00:00Z",
  "path": "/api/v1/customers"
}
```

Nguồn danh mục: `common/exception/ErrorCode.java`. BusinessException kiểm tra
code và HTTP status khớp nhau; mã lỗi nghiệp vụ không được tự đặt ngoài enum.

| HTTP | Code |
| --- | --- |
| 400 | VALIDATION_ERROR, INVALID_REQUEST, EMPTY_UPDATE, INVALID_TRANSFER, INVALID_AMOUNT, SAME_ACCOUNT, INVALID_TIME_RANGE, INVALID_USER_ROLES, CUSTOMER_LINK_REQUIRED, INVALID_IDEMPOTENCY_KEY, INVALID_LIMIT |
| 401 | UNAUTHENTICATED, INVALID_CREDENTIALS |
| 403 | FORBIDDEN |
| 404 | RESOURCE_NOT_FOUND, CUSTOMER_NOT_FOUND, ACCOUNT_NOT_FOUND, TRANSACTION_NOT_FOUND, USER_NOT_FOUND, BENEFICIARY_NOT_FOUND |
| 405 | METHOD_NOT_ALLOWED |
| 406 | NOT_ACCEPTABLE |
| 409 | CUSTOMER_CONFLICT, DATA_CONFLICT, ACCOUNT_NUMBER_CONFLICT, CUSTOMER_NOT_ACTIVE, ACCOUNT_CLOSED, ACCOUNT_BALANCE_NOT_ZERO, ACCOUNT_NOT_ACTIVE, CURRENCY_MISMATCH, INSUFFICIENT_FUNDS, BALANCE_LIMIT_EXCEEDED, USER_CONFLICT, IDEMPOTENCY_CONFLICT, PER_TRANSFER_LIMIT_EXCEEDED, DAILY_LIMIT_EXCEEDED, BENEFICIARY_CONFLICT |
| 415 | UNSUPPORTED_MEDIA_TYPE |
| 429 | RATE_LIMITED (login/transfer throttle và login lockout) |
| 500 | INTERNAL_ERROR |
| 503 | ACCOUNT_BUSY, IDEMPOTENCY_BUSY, THROTTLE_UNAVAILABLE |

Phase 9: 400 INVALID_FRAUD_RULE, INVALID_ASSIGNEE; 404 FRAUD_RULE_NOT_FOUND,
FRAUD_ALERT_NOT_FOUND; 409 FRAUD_RULE_CONFLICT, FRAUD_ALERT_CONFLICT,
FRAUD_ALERT_STATE_CONFLICT. Xem [API Phase 9](phase-9.md).

Phase 10: 404 NOTIFICATION_NOT_FOUND khi thông báo không tồn tại hoặc thuộc user
khác; không tiết lộ owner. Xem [API Phase 10](phase-10.md).

Body Bean Validation trả VALIDATION_ERROR với fieldErrors. Query Bean Validation
và UUID/date/number sai format trả INVALID_REQUEST với fieldErrors khi xác định
được parameter. JSON hỏng/field không hỗ trợ trả INVALID_REQUEST, không đưa raw
payload vào lỗi. Không che lỗi server bằng HTTP 400.

Đối với transfer: 409 nghiệp vụ không đổi tiền. ACCOUNT_BUSY/IDEMPOTENCY_BUSY có
Retry-After: 1; transaction lỗi rollback. Client retry cùng Idempotency-Key và payload
trong cửa sổ 7 ngày; không tự sinh key khác khi kết quả còn chưa rõ. Sau expiry,
tra lịch sử trước khi quyết định gửi lại. Xem [contract Phase 6](phase-6.md).

Swagger UI: `/swagger-ui/index.html`.
Phase 8: RATE_LIMITED có Retry-After theo TTL thực; Redis guard lỗi trả
THROTTLE_UNAVAILABLE với Retry-After: 1. Cache lỗi fallback PostgreSQL.
Xem [contract Phase 8](phase-8.md).
OpenAPI máy đọc: `/v3/api-docs` và `/v3/api-docs.yaml`.
Contract được sinh từ controller/DTO, annotation validation và OpenApiConfig.
Các test kiểm tra route, schema ApiError, amount string và HTTP 201 thực tế.

Tham khảo chính thống: [springdoc](https://springdoc.org/) (dòng 3.x hỗ trợ Boot 4).
