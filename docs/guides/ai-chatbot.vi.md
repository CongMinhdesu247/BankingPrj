# Chatbot hỗ trợ khách hàng

Chatbot xuất hiện ở góc dưới bên phải sau khi đăng nhập bằng vai trò CUSTOMER.
Hỗ trợ hướng dẫn chuyển tiền, tra soát, hạn mức, bảo mật và sao kê dựa trên chức năng của dự án.
Không tự thực hiện giao dịch và không truy vấn dữ liệu tài khoản cho AI.

## Bật Gemini API qua Docker

1. Tạo API key tại https://aistudio.google.com/api-keys bằng tài khoản của bạn.
2. Kiểm tra model và hạn mức Free Tier của dự án Google AI Studio. Mặc định dùng gemini-3.5-flash-lite; có thể đổi GEMINI_MODEL theo model tài khoản được phép dùng.
3. Thêm vào file .env ở thư mục gốc (giữ nguyên các cấu hình hiện có):

```dotenv
GEMINI_API_KEY=YOUR_API_KEY
GEMINI_MODEL=gemini-3.5-flash-lite
```

4. Cập nhật ứng dụng từ thư mục gốc:

```powershell
docker compose up -d --build backend web
```

5. Đăng nhập khách hàng, mở Hỏi trợ lý. Tiêu đề hiện Hỏi đáp cùng Gemini AI khi backend có key.
   Chọn đồng ý gửi nội dung rồi đặt câu hỏi không chứa dữ liệu cá nhân.
   Nhãn Gemini AI dưới câu trả lời xác nhận câu trả lời đến từ Gemini.
   Có key không bảo đảm key hợp lệ hoặc còn hạn mức; lỗi sẽ hiển thị khi gửi.

Không gửi API key qua chat, không đưa key vào frontend hoặc biến VITE_, không commit .env.
Spring Boot chạy trực tiếp cần GEMINI_API_KEY/GEMINI_MODEL trong môi trường tiến trình;
file .env gốc chỉ được Docker Compose đọc, không được Spring tự động nạp.
Không có key: hệ thống dùng hướng dẫn theo chủ đề, ghi rõ Hướng dẫn có sẵn; đây không phải câu trả lời AI.

## Giới hạn và quyền riêng tư

- ChatGPT Free là tài khoản sử dụng ứng dụng; tích hợp website cần OpenAI API với cơ chế tính phí riêng.
- Gemini có Free Tier tùy model, khu vực, dự án và quota; không cam kết miễn phí không giới hạn.
  Không tự bật billing hoặc chuyển sang nhà cung cấp trả phí khi lỗi. Nếu bạn đã bật billing ở Google,
  request API có thể tính phí theo tài khoản; ứng dụng không thể xác định Free Tier còn lại.
- Người dùng đồng ý trước khi gửi. Gemini nhận nội dung câu hỏi và tối đa 10 tin nhắn trước đó;
  không nhận JWT, hồ sơ, số dư, dữ liệu giao dịch hoặc URL trang đang mở từ ứng dụng.
- Free Tier có thể dùng nội dung để cải thiện sản phẩm. Chỉ dùng câu hỏi về thao tác, không gửi
  dữ liệu ngân hàng thật, mật khẩu, OTP, PIN hay thông tin cá nhân. Bộ lọc email/dãy số chỉ giảm rủi ro,
  không thể bảo đảm nhận diện mọi dữ liệu nhạy cảm.
- Hội thoại chỉ ở bộ nhớ giao diện, không lưu database/localStorage; tải lại trang hoặc đăng xuất sẽ xóa.
  Khung chat giới hạn 40 tin nhắn hiển thị, gửi 10 tin nhắn gần nhất làm ngữ cảnh.
- API yêu cầu vai trò CUSTOMER, tối đa 2.000 ký tự/tin nhắn và 10 tin ngữ cảnh.
  Redis giới hạn 5 câu hỏi/phút/khách hàng; khi bật AI có giới hạn chung 20 câu hỏi/phút.
  Quota Google được áp dụng thêm; không tự thử lại request lỗi. Redis lỗi thì từ chối request.
- Khi AI lỗi/hết quota: giữ câu hỏi, hiển thị lỗi và đường dẫn mở tra soát.
- AI được hướng dẫn trả lời trực tiếp trong 1–3 câu (khoảng 70 từ), không chào lại hoặc lặp lời mời hỏi thêm.
- Giao diện hỗ trợ chữ đậm và tên chức năng nếu model vẫn trả Markdown. Chỉ các đường dẫn chức năng được định sẵn mới trở thành liên kết; không thực thi HTML hoặc liên kết tùy ý.
- Nút phóng to/thu nhỏ nằm trên đầu khung chat. A−/A+ chỉnh cỡ chữ từ 14–22px, mặc định 16px.

## Điều chỉnh kiến thức

Cập nhật GUIDE trong backend/src/main/java/com/minh/banking/support/chat/ChatbotService.java
khi đổi menu hoặc quy trình. localGuide là hướng dẫn dự phòng khi chưa bật Gemini.
Trợ lý không có công cụ gọi API nghiệp vụ, thay đổi dữ liệu hay xác nhận kết quả giao dịch.

Tài liệu chính thức:
- https://ai.google.dev/gemini-api/docs/pricing
- https://ai.google.dev/api/generate-content
- https://developers.openai.com/api/docs/pricing

## Kiểm tra

```powershell
cd frontend
npm.cmd run build
npm.cmd test -- src/features/support/chat/ChatbotWidget.test.tsx
cd ../backend
.\mvnw.cmd -q -Dtest=ChatbotServiceTest,ChatbotControllerTest test
```

Kiểm thử nhà cung cấp dùng HTTP giả lập, không tiêu tốn quota Gemini. Cần key thật để kiểm tra
phản hồi live và quyền truy cập model của dự án Google.

## Không gửi được tin nhắn

- Dùng Docker: mở http://localhost:8090. Bản demo cũ ở cổng 5174 có thể đang trỏ tới backend 8081 chưa có API chatbot. Không chạy lại Start-Demo.ps1 chỉ để sửa chatbot vì script tạo bộ dữ liệu demo mới.
- Nhập key vào .env, không phải .env.example. Giá trị gen-lang-client-… là project ID, không phải API key; vào mục API Keys của Google AI Studio để sao chép key thật.
- Sau khi sửa .env, chạy docker compose up -d --build backend web để nạp lại môi trường. Không cần xóa volume hoặc tạo lại tài khoản.
