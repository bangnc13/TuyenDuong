# Tuyến đường – app Android gốc (native)

App Android tự chạy, không mở trang web: giao diện, bản đồ, GPS và tính lộ trình đều nằm trong app.

## Tính năng (giống bản Streamlit)
- Đọc sheet "Tọa độ" từ Google Sheet; lưu sẵn để dùng khi mất mạng
- Import Excel (.xlsx) / CSV theo cột "Tên đối tượng"; báo tên không tìm thấy
- Tải file mẫu Template.xlsx vào thư mục Download
- Chọn từ danh sách, có ô tìm kiếm không dấu
- Lấy GPS làm điểm xuất phát; la bàn hiện tia hướng
- Tính lộ trình tối ưu theo đường bộ: Google Routes API chế độ xe máy (nhập key trong ⚙ Cài đặt), nếu không có key thì dùng OSRM miễn phí
- Bản đồ toàn màn hình, nền Đường phố / Vệ tinh, xoay bằng 2 ngón, nút về hướng Bắc, nút về vị trí của tôi
- Ghim đánh số kèm tên, số km / phút / điểm dừng, nút mở Google Maps (xe máy, 10 điểm mỗi link)

## Build bằng GitHub
1. Tải toàn bộ file lên repo, giữ nguyên thư mục (kể cả `.github/workflows/build.yml` và `app/release.jks`).
2. Mỗi lần push, Actions tự build. Tải APK ở Releases → latest → TuyenDuong.apk.
