# Biểu đồ dòng tiền

Trang Tổng quan của khách hàng có hai biểu đồ dùng dữ liệu thực:
- Cột tiền vào/tiền ra theo ngày, lọc 7 hoặc 30 ngày gần nhất (gồm hôm nay).
- Vòng tròn thể hiện tỷ trọng tổng tiền vào và tiền ra trong cùng kỳ.

Dữ liệu gộp toàn bộ tài khoản của khách hàng, từ bút toán CREDIT/DEBIT; có cả vốn mở sổ
và chuyển giữa các tài khoản của chính khách hàng. Đây là dòng tiền, không phải phân loại thu nhập/chi tiêu.
Chênh lệch = tổng tiền vào − tổng tiền ra trong kỳ, không phải số dư tài khoản.
Không giới hạn thống kê ở các giao dịch trên trang lịch sử đang mở. Ngày không có phát sinh được điền 0.

Chạm/di chuột vào cột để xem số tiền chính xác. Dùng Tab tới biểu đồ và mũi tên trái/phải để đổi ngày;
hoặc mở Xem số liệu theo ngày. Trên điện thoại có thể vuốt ngang biểu đồ.
Khi ẩn số dư, phần biểu đồ cũng ẩn và ngừng tải số liệu. Làm mới dữ liệu ở cuối trang cũng tải lại thống kê.
Không có giao dịch trong kỳ: hiển thị trạng thái trống, không sinh dữ liệu mẫu.

API: GET /api/v1/me/cashflow?days=30. Chỉ dành cho CUSTOMER; lấy customerId từ phiên đăng nhập.
Tham số days trong khoảng 7–30, múi giờ Asia/Ho_Chi_Minh; lọc đến thời điểm máy chủ tải dữ liệu.
Tính toán tổng bằng BigDecimal ở backend và giữ chuỗi số tiền khi truyền API. Frontend dùng BigInt để
đối chiếu/tính tỷ lệ; trục biểu đồ hiển thị số tiền rút gọn, bảng chi tiết giữ số VND chính xác.
Không thay đổi schema và không ghi hoặc sửa bút toán để tạo biểu đồ.

Kiểm thử: CashflowIT, CashflowControllerTest và CashflowCharts.test.tsx.
