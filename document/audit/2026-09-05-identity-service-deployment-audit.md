# Biên bản triển khai và audit Identity Service

- Ngày kiểm tra: 2026-09-05
- Phạm vi: `identity-service`, PostgreSQL, Redis lock, JWT/refresh token, Flyway và Docker Compose local
- Mức audit: Domain audit
- Kết luận: **Triển khai local thành công, chưa đủ điều kiện production**

## Kết quả triển khai

- Docker image `coffee-identity-identity-service:latest` build thành công.
- Container chạy bằng user không phải root, có liveness/readiness và graceful shutdown.
- PostgreSQL 17 và Redis 7.4 khởi động healthy; Flyway xác nhận schema ở version 2.
- Login cấp access JWT và refresh token thành công.
- Refresh rotation thành công: token cũ được đánh dấu `used_at`, token mới được tạo và `current_token_jti` được cập nhật trong PostgreSQL.
- Redis lock dùng owner token, TTL, bounded wait và Lua compare-and-delete khi release.
- Lỗi một request tự nhận `REFRESH_BUSY` đã được sửa bằng cách xác minh owner sau `SET NX PX`; kiểm tra runtime xác nhận lock được xóa sau xử lý.
- Refresh token cũ bị dùng lại sẽ tạo audit `REFRESH_REUSE_DETECTED`, revoke toàn bộ session và yêu cầu đăng nhập lại.
- Tạo user có idempotency record; username/email được bảo vệ bởi unique constraint.
- RBAC hỗ trợ permission, global scope và branch scope; thay đổi trạng thái/phân quyền thu hồi refresh session liên quan.

## Bằng chứng kiểm tra

| Kiểm tra | Kết quả |
|---|---|
| Docker build chạy `mvn -B verify` | Thành công |
| Unit test | 8 test, 0 failure, 1 skipped |
| Migration V1/V2 trên PostgreSQL local | Thành công, schema version 2 |
| Login → refresh một lần | Thành công, cấp access token và refresh token mới |
| PostgreSQL refresh rotation | Token cũ có `used_at`, token mới là `current_token_jti` |
| Redis lock sau request | Không còn key lock của session |
| Refresh lại token cũ | Phát hiện `TOKEN_REUSE` và revoke session |
| `.codex/audit/check-project.ps1` | 18 PlantUML, 0 warning, 0 failure |

Test migration dùng Testcontainers bị skip trong Docker build vì build container không được cấp Docker socket. Migration đã được xác minh riêng qua PostgreSQL đang chạy trong Compose.

## Phát hiện còn tồn tại

### High — Khóa ký JWT chỉ tồn tại trong process

- Vị trí: `source/services/identity-service/src/main/java/com/coffee/management/identity/infrastructure/security/JwtService.java`
- Kịch bản: restart hoặc chạy nhiều replica tạo khóa RSA khác nhau, làm access token đã phát hành không còn được xác minh nhất quán.
- Ảnh hưởng: logout ngoài ý muốn sau deploy và không thể scale ngang an toàn.
- Khắc phục tối thiểu: nạp private/public key bền vững từ secret manager hoặc file secret, công bố JWKS theo `kid`, và có quy trình rotation chồng lấn khóa.
- Bằng chứng đóng finding: token phát hành trước restart vẫn xác minh được sau restart và trên replica khác.

### High — Có mật khẩu bootstrap mặc định trong cấu hình nguồn

- Vị trí: `source/services/identity-service/src/main/resources/application.yml`
- Kịch bản: chạy service ngoài Compose mà không truyền biến môi trường có thể tạo tài khoản `superadmin` với credential mặc định đã biết.
- Ảnh hưởng: chiếm quyền quản trị môi trường cấu hình sai.
- Khắc phục tối thiểu: bỏ toàn bộ giá trị mặc định của username/password bootstrap; chỉ chạy bootstrap khi cả hai secret được cung cấp rõ ràng và buộc đổi mật khẩu lần đầu.
- Bằng chứng đóng finding: service không tạo admin khi thiếu secret và startup không ghi credential/token.

### Medium — Hạn token sau rotation có thể vượt hạn session

- Vị trí: `source/services/identity-service/src/main/java/com/coffee/management/identity/application/AuthApplicationService.java`
- Kịch bản: mỗi lần rotate trả `refreshExpiresAt = now + P30D`, trong khi `refresh_sessions.expires_at` không được gia hạn.
- Ảnh hưởng: client nhận thời hạn dài hơn thời hạn thực tế của session và gặp 401 sớm hơn timestamp đã công bố.
- Khắc phục tối thiểu: chọn rõ sliding session hoặc absolute session; nếu absolute, expiry token mới phải là `min(now + refreshTtl, sessionExpiresAt)`.
- Bằng chứng đóng finding: test refresh gần cuối hạn session xác nhận timestamp response và validation nhất quán.

### Medium — Chưa đủ test tích hợp Redis/concurrency trong pipeline

- Vị trí: `source/services/identity-service/src/test/java/com/coffee/management/identity/infrastructure/redis/RedisRefreshLockTest.java`
- Kịch bản: unit test mock không phát hiện khác biệt serializer/driver như lỗi `REFRESH_BUSY` vừa gặp.
- Ảnh hưởng: regression lock có thể lọt qua CI.
- Khắc phục tối thiểu: thêm integration test với Redis thật cho acquire, contention, TTL, owner-safe release, exception release và refresh đồng thời.
- Bằng chứng đóng finding: các test Redis thật chạy bắt buộc trong CI, không bị skip.

### Medium — Chưa có metric contention cho Redis lock

- Vị trí: `source/services/identity-service/src/main/java/com/coffee/management/identity/infrastructure/redis/RedisRefreshLock.java`
- Kịch bản: lock timeout/tần suất retry tăng nhưng vận hành không quan sát được.
- Ảnh hưởng: khó phân biệt client retry, Redis latency và transaction chậm.
- Khắc phục tối thiểu: thêm counter acquire success/timeout, timer wait/hold và tag lock type không chứa session ID.
- Bằng chứng đóng finding: metric xuất hiện ở `/actuator/prometheus` và có test/alert threshold.

### Medium — Kong chưa được kiểm chứng trong stack triển khai này

- Vị trí: `source/services/identity-service/compose.yml`
- Kịch bản: client gọi trực tiếp port `8081`; rate limit, request limit, correlation ID và gateway JWT policy chưa được áp dụng.
- Ảnh hưởng: kết quả hiện tại chỉ chứng minh triển khai service local, không chứng minh đường public qua gateway.
- Khắc phục tối thiểu: bổ sung stack Kong declarative và E2E login/refresh qua route `/api/v1`.
- Bằng chứng đóng finding: contract/E2E qua Kong xác nhận route, timeout, không retry POST refresh và không log token.

## Quyết định phát hành

- Local development/demo: **Đạt**.
- Shared development: **Đạt có điều kiện**, phải inject bootstrap credential riêng và chấp nhận access token mất hiệu lực khi restart.
- Production/multi-replica: **Chưa đạt** cho đến khi đóng hai finding mức High và kiểm chứng Kong, Redis concurrency cùng persistent JWT keys.

## Vận hành

Chạy stack local từ thư mục service:

```powershell
docker compose -f compose.yml up --build -d
docker compose -f compose.yml ps
docker compose -f compose.yml logs -f identity-service
```

Public host port mặc định của service là `8081`; port `8080` là port nội bộ container. Không ghi access token, refresh token, mật khẩu hoặc token hash vào log/audit.
