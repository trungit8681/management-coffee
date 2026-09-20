# Codex workflow cho dự án Coffee Management

Repository đã cấu hình Codex theo bốn lớp:

| Thành phần | Vị trí | Mục đích |
|---|---|---|
| Project guidance | `AGENTS.md` | Quy tắc kiến trúc và cách làm việc luôn được nạp |
| Repo skill | `.agents/skills/coffee-backend/` | Workflow triển khai và audit chuyên biệt cho dự án |
| Execution rules | `.codex/rules/project.rules` | Cho phép lệnh đọc an toàn, hỏi trước khi mutate, chặn cleanup phá hủy |
| Audit | `.codex/audit/check-project.ps1` | Kiểm tra tài liệu, PlantUML, phạm vi thanh toán, secret và Docker |

## Cách dùng

Khởi động lại Codex từ thư mục repository để nhận `AGENTS.md`, skill và rules mới. Project-local rules chỉ hoạt động khi `.codex` của repository đã được đánh dấu trusted.

Một số yêu cầu mẫu:

```text
$coffee-backend triển khai luồng mở ca theo sequence 03
$coffee-backend thiết kế order-service và migration đầu tiên
$coffee-backend audit domain thanh toán tiền mặt
$coffee-backend review Redis lock của inventory-service
```

Chạy audit thủ công:

```powershell
powershell -ExecutionPolicy Bypass -File .codex/audit/check-project.ps1
```

Chế độ nghiêm ngặt biến cảnh báo Docker thành lỗi:

```powershell
powershell -ExecutionPolicy Bypass -File .codex/audit/check-project.ps1 -Strict
```

Kiểm tra một execution rule:

```powershell
codex execpolicy check --pretty --rules .codex/rules/project.rules -- git status --short
```

Rules và skill được Codex tự phát hiện trong phiên mới. Nếu chưa xuất hiện, restart Codex và kiểm tra repository trust.

