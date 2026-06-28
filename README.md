# Skypec Auto Reader 📖

Ứng dụng hỗ trợ tự động duy trì tiến độ đọc sách và tích lũy thời gian học tập trên hệ thống E-learning của Skypec.

Ứng dụng được thiết kế chạy trực tiếp trên điện thoại Android dưới dạng **Dịch vụ chạy ngầm ưu tiên cao (Foreground Service)**, giúp duy trì trạng thái đọc sách liên tục kể cả khi tắt màn hình hoặc chuyển sang ứng dụng khác mà không sợ bị hệ thống tự động thoát (out) ra ngoài.

---

## 🌟 Tính năng nổi bật

*   **Chạy ngầm vĩnh viễn (Foreground Service)**: Hiển thị thông báo trên thanh trạng thái, ngăn không cho hệ điều hành Android tối ưu hóa hoặc tắt ứng dụng khi khóa màn hình.
*   **Bypass CORS (Bỏ qua chặn bảo mật tên miền)**: Gọi API trực tiếp từ mã nguồn Java Native của điện thoại, không bị lỗi chặn CORS của trình duyệt và không cần sử dụng máy chủ trung gian (VPS).
*   **Tải danh sách tự động**: Tự động lấy danh sách tất cả các lớp học theo từng năm học (2022 - 2026).
*   **Hiển thị tiến độ trực quan**: Hiển thị thời gian đã tích lũy và thanh tiến trình phần trăm thời gian thực.
*   **Tự động dừng thông minh**: Tự động ngừng gửi tín hiệu và phát âm thanh thông báo khi đạt đủ thời gian yêu cầu (430 phút).

---

## 📲 Hướng dẫn tải về và cài đặt trên Điện thoại

Ứng dụng được thiết lập tự động biên dịch sang file cài đặt `.apk` thông qua **GitHub Actions**. Khầy không cần cài đặt bất kỳ công cụ lập trình nào trên máy tính, chỉ cần làm theo các bước sau:

1.  **Truy cập vào kho lưu trữ GitHub** của Khầy trên điện thoại hoặc máy tính.
2.  Bấm vào thẻ **Actions** ở phía trên thanh công cụ của GitHub.
3.  Chọn Workflow tên là **Build Android APK** ở cột bên trái.
4.  Bấm vào lần chạy mới nhất (có dấu tích xanh lá cây ✅ thành công).
5.  Kéo xuống mục **Artifacts** ở dưới cùng và click vào liên kết **`skypec-auto-reader-apk`** để tải file nén về máy.
6.  Giải nén file vừa tải sẽ được file **`app-debug.apk`**.
7.  Mở file `.apk` trên điện thoại Android để cài đặt (nếu điện thoại hỏi quyền, Khầy hãy cho phép *"Cài đặt ứng dụng từ nguồn không xác định"*).

---

## 🛠️ Hướng dẫn sử dụng

1.  **Đăng nhập**: Nhập mã nhân viên và mật khẩu E-learning Skypec của Khầy. Ứng dụng sẽ lưu thông tin đăng nhập an toàn trên máy cho lần sau.
2.  **Chọn lớp học**: Chọn năm học (ví dụ: `2023` để tìm cuốn *"Kỷ nguyên hỗn loạn"*), danh sách lớp học sẽ hiện ra. Click vào lớp học muốn tích lũy thời gian.
3.  **Bắt đầu duy trì**: Bấm nút **"Bắt đầu duy trì ngầm"**. Một thông báo sẽ xuất hiện trên thanh trạng thái điện thoại.
4.  Khầy có thể tắt màn hình điện thoại hoặc mở các ứng dụng khác (Zalo, Facebook, Youtube...) để làm việc bình thường. Hệ thống sẽ tự động gửi tín hiệu duy trì mỗi 60 giây.
5.  **Hoàn thành**: Khi đạt đủ 430 phút (hoặc khi Khầy muốn dừng), bấm **"Dừng duy trì ngầm"** để cập nhật thời gian chính thức lên máy chủ Skypec.

---

## 📂 Cấu trúc mã nguồn

*   `src/`: Chứa giao diện ứng dụng (HTML, CSS, JavaScript).
*   `android/`: Chứa mã nguồn ứng dụng Android Native bằng Java.
*   `.github/workflows/`: Cấu hình tự động biên dịch ra ứng dụng trên GitHub.
