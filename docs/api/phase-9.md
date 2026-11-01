# API Phase 9 — Fraud hậu kiểm

Phân tích INTERNAL_TRANSFER đã commit, không sửa balance/ledger/COMPLETED hoặc
tự hoàn tiền. Redis không quyết định evidence; score quy tắc không phải xác suất.

## Quyền và route

Mọi route dưới /api/v1, cần JWT. Security chain và service đều kiểm tra quyền.

| Quyền | Endpoint |
| --- | --- |
| RISK_OFFICER | GET /fraud/summary, GET /fraud/officers |
| RISK_OFFICER | GET /fraud-alerts?page=&size=&status=&riskLevel=&transactionId= |
| RISK_OFFICER | GET /fraud-alerts/{id} |
| RISK_OFFICER | PATCH /fraud-alerts/{id}/assignment, PATCH /fraud-alerts/{id}/resolution |
| ADMIN | GET /fraud-rules, PATCH /fraud-rules/{id} |
| BANK_STAFF, RISK_OFFICER, ADMIN | GET /staff/customers?search=&page=&size= |
| BANK_STAFF, RISK_OFFICER, ADMIN | GET /staff/customers/{id}, GET /staff/customers/{id}/accounts?page=&size= |

ADMIN đơn lẻ không có quyền điều tra; user nội bộ có thể được gán thêm role.
CUSTOMER không xem alert/evidence hoặc tra cứu staff. Account detail/history,
transaction detail dùng API đọc hiện có; không mở quyền sửa cho RISK_OFFICER.

List mặc định size=20, tối đa 100, sort createdAt DESC,id DESC. status: OPEN,
IN_REVIEW, CONFIRMED_FRAUD, FALSE_POSITIVE; riskLevel: LOW, MEDIUM, HIGH, CRITICAL.
Detail gồm alert, matches, ruleSnapshot, contextSnapshot, history. Tiền trả string.

## Phân công và kết luận

PATCH assignment:

```json
{"expectedVersion":0,"assignedTo":"uuid-of-enabled-risk-officer"}
```

Gán officer enabled → IN_REVIEW; assignedTo=null → OPEN. Mỗi thay đổi tăng version.
Alert terminal không sửa lại. PATCH resolution:

```json
{"expectedVersion":1,"resolution":"FALSE_POSITIVE","notes":"Đã đối chiếu chứng từ."}
```

Chỉ người được phân công kết luận alert IN_REVIEW. Resolution nhận CONFIRMED_FRAUD
hoặc FALSE_POSITIVE; notes bắt buộc sau trim, tối đa 2000 ký tự. Thay đổi/audit cùng
transaction. Audit giữ actor/version/assignee/resolution, không nhân bản notes.

409 FRAUD_ALERT_CONFLICT cho stale version; FRAUD_ALERT_STATE_CONFLICT cho trạng
thái/người phụ trách không phù hợp. Client tải bản mới, không ghi đè tự động.
400 INVALID_ASSIGNEE cho target sai quyền/disabled; 404 FRAUD_ALERT_NOT_FOUND.

## Rule và phiên bản

Mặc định từ code, không seed fraud_rules. GET trả ID ổn định, version=1,
updatedBy=null, updatedAt=1970-01-01T00:00:00Z (chưa sửa). PATCH lần đầu lưu
override version=2. Optimistic version chống cập nhật trùng; mọi thay đổi có audit.

```json
{"expectedVersion":1,"threshold":{"amount":"100000000","count":null,"windowSeconds":null},"weight":40,"enabled":true}
```

| Code | Threshold mặc định | Weight |
| --- | --- | ---: |
| HIGH_AMOUNT | amount="100000000" | 40 |
| VELOCITY | count=5, windowSeconds=600 | 30 |
| NEW_BENEFICIARY | amount="20000000", windowSeconds=86400 | 30 |
| FAILED_LOGIN_CONTEXT | count=5, windowSeconds=900 | 20 |

Chỉ nhận field threshold đúng code; field không dùng null/không có. Amount phải là
string nguyên dương tối đa 17 chữ số; count 1–100000, window 1–604800 giây, weight
0–100. Count/weight/version phải nguyên, không tự cắt số thập phân.
400 INVALID_FRAUD_RULE/VALIDATION_ERROR/INVALID_REQUEST; 409 FRAUD_RULE_CONFLICT;
404 FRAUD_RULE_NOT_FOUND. Tắt/sửa rule không đổi evidence đã lưu.

## Event-time và recovery

- Velocity: committed transfer đi trong (T-window,T], gồm giao dịch hiện tại;
  timestamp bằng T chỉ tính UUID <= UUID hiện tại. Không OPENING hoặc tương lai.
- Beneficiary: createdAt <= T và deletedAt null hoặc > T. Mới khi tuổi <24h;
  đúng 24h là cũ. Tạo sau T không được coi đã lưu ở T. Người chưa lưu dùng cùng
  ngưỡng tiền, reason riêng UNSAVED_RECIPIENT_AND_HIGH_AMOUNT.
- Login sai: đúng actor trong (T-window,T], theo user_id hoặc legacy HMAC khớp
  key hiện tại. Không gán login của user không tồn tại sang actor khác.
  transactions.initiated_by giữ actor ngoài vòng đời idempotency key.
- Cap score 100: LOW 0–29, MEDIUM 30–59, HIGH 60–79, CRITICAL 80–100. Có match
  thì tạo alert, kể cả LOW/weight=0. Không match vẫn lưu analysis_run.
- analysisVersion=1 là engine, độc lập rule.version. Lần phân tích thành công
  đầu tiên đóng snapshot tất cả rule và context. Recovery dùng cấu hình lúc
  thành công, evidence theo T. Không tự phân tích lại run cũ khi sửa config.
- Unique transaction/analysisVersion chống trùng. Run/alert/matches cùng
  transaction REQUIRES_NEW; lỗi rollback evidence, tiền đã commit giữ nguyên.
  Trigger sau commit; recovery tối đa 100/lượt, mặc định mỗi 30s, initial delay
  10s. Lỗi từng giao dịch có log và được thử lại ở lượt sau.

Không có API tạo alert, sửa evidence hoặc chạy phân tích tùy ý. Kafka/outbox,
ML, tự phong tỏa và blocking trước debit chưa thuộc Phase 9.
