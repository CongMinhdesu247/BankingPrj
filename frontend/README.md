# Frontend

Chạy các lệnh trong thư mục `frontend/`: `npm.cmd run dev`, `npm.cmd run build`,
`npm.cmd test`. Trên macOS/Linux dùng `npm` thay cho `npm.cmd`.

```text
src/
  app/          App, router, route guards và layout tổng thể
  features/     Mã theo nghiệp vụ
  shared/       Thành phần và tiện ích dùng chung
  assets/       Font và tài nguyên tĩnh
  styles/       CSS toàn ứng dụng
  test/         Thiết lập test và các kịch bản qua nhiều nghiệp vụ
  main.tsx      Điểm khởi động
e2e/            Kiểm thử trình duyệt
```

Trong `features/`, tìm theo nghiệp vụ: `accounts`, `auth`, `beneficiaries`,
`dashboard`, `fraud`, `limits`, `notifications`, `profile`, `security`, `staff`,
`support`, `transfers`. Mỗi nghiệp vụ chỉ tạo các thư mục cần dùng: `pages/`,
`api/`, `components/`, `providers/`, `lib/`.

- Trang, API, provider và component chỉ phục vụ một nghiệp vụ nằm cùng feature.
- Test đơn vị đặt cạnh file được kiểm thử; test qua nhiều nghiệp vụ ở `test/workflows/`.
- `shared/api/httpClient.ts` xử lý HTTP chung; `shared/api/selfServiceApi.ts`
  hiện phục vụ nhiều nghiệp vụ như bảo mật, hạn mức và hỗ trợ.
- UI cơ bản, định dạng, CSV, hook và kiểu API dùng chung nằm trong `shared/`.
- API nghiệp vụ nằm trong `features/*/api/`; không tạo lớp `src/services/` trùng vai trò.
- Import trực tiếp file cần dùng; không thêm file gom export chỉ để rút ngắn import.
- Chỉ tạo thư mục khi có mã thực tế, không thêm `.gitkeep` cho tính năng dự kiến.

Hướng dẫn chạy backend và demo: [README dự án](../README.md).
