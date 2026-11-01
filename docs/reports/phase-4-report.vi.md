# Phase 4 — Validation, error contract và OpenAPI

Ngày thực hiện: 27/10/2026.

Mục đích: chuẩn hóa cách API nhận input, trả lỗi và mô tả contract, để frontend
không phải suy đoán từ message hoặc HTTP 200. Luồng ghi tiền của phase 3 được giữ
làm kiểm thử hồi quy.

Đã bổ sung ErrorCode enum với HTTP status, kiểm tra mã lỗi trong BusinessException,
ApiErrorFactory dùng chung, fieldErrors cho query/path, phân biệt lỗi server 5xx,
request ID có thứ tự filter trước security, springdoc 3.2.0 tương thích Boot 4.
Swagger UI ở /swagger-ui/index.html, OpenAPI ở /v3/api-docs và /v3/api-docs.yaml.
Tài liệu có schema ApiError, request amount dạng string, response 201 đúng API.

Trước khi bắt đầu phase 5, đã chạy `scripts/Test-Postgres.ps1` với PostgreSQL thật:
11 unit test + 34 integration test = **45 test đạt**, không failure/error/skipped.
ApiContractIT có 5 ca mới kiểm tra schema/UI, field query/path, framework errors,
body validation, 500 không lộ dữ liệu và request ID an toàn. Test Customer/Account,
rollback và concurrency của transfer vẫn đạt.

Không thêm bảng hoặc sửa migration. Các lỗi/ràng buộc được mô tả trong
[error-codes.md](../api/error-codes.md). Phase 5 được triển khai tiếp sau cổng kiểm thử này.
