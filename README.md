# Tuyến đường – APK Android

App Android bọc trang https://tuyenquang.streamlit.app (lộ trình tối ưu xe máy).

## Tính năng trong app
- Mở thẳng trang Streamlit, toàn màn hình, giữ màn hình sáng khi chạy
- Định vị GPS + la bàn cho bản đồ (xin quyền vị trí lúc mở app)
- Nút Import Excel/CSV mở trình chọn file của điện thoại
- Nút "Tải file mẫu" lưu Template.xlsx vào thư mục Download
- Nút "Mở Google Maps" mở bằng app Google Maps (chế độ xe máy)
- Phím Back quay lại trang trước; mất mạng hiện trang "Thử lại"

## Build APK bằng GitHub (làm trên điện thoại được)
1. Tạo repo mới trên GitHub (vd. `TuyenDuong`), tải toàn bộ file trong thư mục này lên,
   giữ đúng cấu trúc thư mục (kể cả `.github/workflows/build.yml` và `app/release.jks`).
2. Mỗi lần push lên nhánh `main`, GitHub Actions tự build (~3–5 phút).
   Có thể bấm tay: tab **Actions → Build APK → Run workflow**.
3. Tải APK: tab **Releases → latest → TuyenDuong.apk** (hoặc Actions → lần chạy → Artifacts).

APK ký bằng `app/release.jks` cố định nên các bản sau cài đè lên bản cũ được.
Đổi tên app: sửa `app/src/main/res/values/strings.xml`. Đổi trang web: sửa `HOME` trong `MainActivity.java`.
