# Chạy Coffee Platform trên Kubernetes của Docker Desktop

Hướng dẫn này dành cho lần cài mới trên máy local. Helm chart nằm tại `source/deploy/helm/coffee-platform` và triển khai 11 service, 11 PostgreSQL, 2 Redis, RabbitMQ, 5 outbox relay và Kong.

Nên cấp Docker Desktop tối thiểu 12–16 GB RAM và 6–8 CPU. Chạy các lệnh dưới đây bằng PowerShell tại thư mục gốc repository.

## 1. Kiểm tra môi trường

```powershell
Set-Location D:\work\VIBE-AI\management-coffee
kubectl config use-context docker-desktop
kubectl cluster-info
kubectl get nodes
helm version
docker version
```

Cluster cần Kubernetes 1.28 trở lên. Helm 4 đã deprecate `--atomic`; hướng dẫn này dùng `--wait` cho lần cài đầu và chỉ dùng `--rollback-on-failure` sau khi đã có ít nhất một revision `deployed`.

## 2. Build image local

Không cần push registry vì Kubernetes của Docker Desktop dùng chung image store với Docker Desktop.

```powershell
$tag = "local"
powershell -ExecutionPolicy Bypass `
  -File source/deploy/helm/coffee-platform/Build-Images.ps1 `
  -Registry coffee-local `
  -Tag $tag
```

Kiểm tra đủ 12 image:

```powershell
docker image ls "coffee-local/*:local"
```

Kết quả phải gồm 11 service và `coffee-local/outbox-relay:local`. Khi build lại cùng tag `local`, cần restart workload sau build để pod dùng image mới; với quy trình lặp lại thường xuyên nên dùng tag mới và truyền tag đó vào Helm.

## 3. Kiểm tra values local

File `source/deploy/helm/coffee-platform/values-local.yaml` phải có tối thiểu các giá trị sau:

```yaml
global:
  imageRegistry: coffee-local
  imageTag: local
  frontendOrigin: http://localhost:3000
  replicas: 1

storageClassName: ""

autoscaling:
  enabled: false

pdb:
  enabled: false

networkPolicy:
  enabled: true

kong:
  replicas: 1
  serviceType: ClusterIP
  workerProcesses: "2"

ingress:
  enabled: false
```

HPA được tắt vì Docker Desktop có thể chưa cài Metrics Server. PDB được tắt vì local chỉ có một node. Giới hạn Kong còn hai worker để tránh Kong tạo worker theo toàn bộ CPU host và bị OOMKilled.

## 4. Tạo namespace có metadata Helm

Chart quản lý resource Namespace. Nếu cần tạo namespace trước để chứa Secret, phải gắn đúng ownership metadata ngay từ đầu:

```powershell
kubectl create namespace coffee-platform --dry-run=client -o yaml | kubectl apply -f -
kubectl label namespace coffee-platform app.kubernetes.io/managed-by=Helm --overwrite
kubectl annotate namespace coffee-platform meta.helm.sh/release-name=coffee-platform --overwrite
kubectl annotate namespace coffee-platform meta.helm.sh/release-namespace=coffee-platform --overwrite
```

Thiếu label hoặc hai annotation này sẽ gây lỗi `invalid ownership metadata` khi Helm cài chart.

## 5. Chuẩn bị Secret ứng dụng

Tạo thư mục ngoài repository và copy file mẫu:

```powershell
New-Item -ItemType Directory -Force C:\secure | Out-Null
Copy-Item source/deploy/helm/coffee-platform/secret.env.example C:\secure\coffee-platform.env
```

Sửa `D:\work\VIBE-AI\management-coffee\source\deploy\helm\coffee-platform\coffee-platform.env` và thay toàn bộ `replace-me`:

- `IDENTITY_BOOTSTRAP_ADMIN_PASSWORD` phải dài ít nhất 12 ký tự.
- Mật khẩu PostgreSQL trong `*_DB_URL` phải khớp với `*_DB_PASSWORD` tương ứng.
- Mật khẩu trong URL PostgreSQL và RabbitMQ phải được URL-encode nếu chứa `@`, `:`, `/`, `#`, `%` hoặc ký tự dành riêng khác.
- `RABBITMQ_ERLANG_COOKIE` phải là chuỗi ngẫu nhiên dài ít nhất 32 ký tự.

Tạo hoặc cập nhật Secret theo cách idempotent:

```powershell
kubectl -n coffee-platform create secret generic coffee-platform-secrets `
  --from-env-file=D:\work\VIBE-AI\management-coffee\source\deploy\helm\coffee-platform\coffee-platform.env `
  --dry-run=client -o yaml | kubectl apply -f -
```

Kiểm tra sự tồn tại và độ dài mật khẩu bootstrap mà không in giá trị:

```powershell
$encoded = kubectl get secret coffee-platform-secrets -n coffee-platform `
  -o jsonpath="{.data.IDENTITY_BOOTSTRAP_ADMIN_PASSWORD}"
$length = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)).Length
if ($length -lt 12) { throw "IDENTITY_BOOTSTRAP_ADMIN_PASSWORD phải dài ít nhất 12 ký tự; hiện tại: $length" }
```

Cảnh báo thiếu `kubectl.kubernetes.io/last-applied-configuration` ở lần `kubectl apply` đầu tiên không phải lỗi; kubectl sẽ tự bổ sung annotation đó.

## 6. Tạo JWT signing key

Trong PowerShell, dùng đường dẫn Windows:

```powershell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 `
  -out C:\secure\jwt-private.pem
openssl rsa -pubout `
  -in C:\secure\jwt-private.pem `
  -out C:\secure\jwt-public.pem
```

Nếu chạy trong Git Bash, dùng `/c/secure/...`; không dùng đường dẫn Windows với dấu `\` vì Bash sẽ coi chúng là ký tự escape.

Tạo hoặc cập nhật Secret JWT:

```powershell
kubectl -n coffee-platform create secret generic identity-jwt-keys `
  --from-file=jwt-private.pem=C:\secure\jwt-private.pem `
  --from-file=jwt-public.pem=C:\secure\jwt-public.pem `
  --dry-run=client -o yaml | kubectl apply -f -
```

Không đặt private key hoặc file Secret hoàn chỉnh trong repository.

## 7. Validate chart

```powershell
helm lint source/deploy/helm/coffee-platform --strict
helm template coffee-platform `
  source/deploy/helm/coffee-platform `
  -f source/deploy/helm/coffee-platform/values-local.yaml `
  | kubectl apply --dry-run=server -f -
```

Có thể chạy thêm validation chuẩn của repository:

```powershell
powershell -ExecutionPolicy Bypass `
  -File source/deploy/helm/coffee-platform/Test-Chart.ps1
```

## 8. Cài lần đầu

Không dùng `--rollback-on-failure` cho đến khi có một revision thành công để Helm rollback về.

```powershell
helm upgrade --install coffee-platform `
  source/deploy/helm/coffee-platform `
  --namespace coffee-platform `
  -f source/deploy/helm/coffee-platform/values-local.yaml `
  --force-conflicts `
  --wait `
  --timeout 20m
```

`--force-conflicts` xử lý ownership `.spec.replicas` còn lại nếu cluster từng có HPA quản lý Deployment. Với cluster hoàn toàn mới, cờ này thường không cần nhưng an toàn cho cấu hình local này.

Không ngắt lệnh Helm bằng `Ctrl+C`. Nếu cài thất bại, để lệnh kết thúc và ghi nhận revision `failed`; ngắt giữa chừng có thể để release ở `pending-install` hoặc `pending-upgrade`.

## 9. Theo dõi và xác nhận

Mở terminal khác:

```powershell
kubectl -n coffee-platform get pods -w
```

Khi Helm kết thúc:

```powershell
helm status coffee-platform -n coffee-platform
kubectl get deploy,statefulset,pods,pvc -n coffee-platform
```

Yêu cầu tối thiểu:

- Helm có `STATUS: deployed`.
- Deployment/StatefulSet đạt số replica Ready mong muốn.
- Pod không còn `CrashLoopBackOff`, `CreateContainerConfigError` hoặc `ImagePullBackOff`.
- HPA không tồn tại trong cấu hình local.

## 10. Nâng cấp sau revision thành công

Sau khi đã có revision `deployed`, có thể dùng rollback-on-failure:

```powershell
helm upgrade coffee-platform `
  source/deploy/helm/coffee-platform `
  --namespace coffee-platform `
  -f source/deploy/helm/coffee-platform/values-local.yaml `
  --force-conflicts `
  --rollback-on-failure `
  --wait `
  --timeout 20m
```

Nếu chỉ thay Secret, pod đang chạy không tự nạp lại environment variable. Cần restart workload liên quan, ví dụ:

```powershell
kubectl rollout restart deployment/identity-service -n coffee-platform
kubectl rollout status deployment/identity-service -n coffee-platform --timeout=5m
```

## 11. Chẩn đoán lỗi

```powershell
kubectl get pods -n coffee-platform
kubectl get events -n coffee-platform --sort-by=.lastTimestamp
kubectl describe pod POD_NAME -n coffee-platform
kubectl logs POD_NAME -n coffee-platform --tail=200
kubectl logs POD_NAME -n coffee-platform --previous --tail=200
helm status coffee-platform -n coffee-platform
helm history coffee-platform -n coffee-platform
```

Các lỗi đã biết:

- `invalid ownership metadata`: namespace thiếu label/annotation ở bước 4.
- `pods.metrics.k8s.io not found`: `autoscaling.enabled` vẫn đang là `true` hoặc HPA cũ chưa được dọn.
- `image has non-numeric user`: chart/image cũ chưa có numeric UID/GID; dùng chart hiện tại.
- PostgreSQL báo `could not change permissions`: chart cũ chưa có init container chuẩn bị ownership PVC; dùng chart hiện tại.
- Sau khi dừng container thủ công, init container báo `chown ... Permission denied`: chart cũ chạy lại `chown -R` trên PVC đã có dữ liệu. Chart hiện tại chỉ gán ownership khi volume chưa khởi tạo. Không quản lý pod Kubernetes bằng nút Stop container của Docker Desktop; dùng `kubectl` hoặc Helm.
- Identity báo password dưới 12 ký tự: sửa đúng file Secret, apply lại và xác nhận độ dài trước khi restart.
- Rollout báo `old replicas are pending termination`: thường pod mới chưa Ready; xem log pod mới thay vì xóa pod cũ ngay.
- Kong bị `OOMKilled` hoặc startup probe timeout: xác nhận `kong.workerProcesses: "2"` trong values local.

## 12. Truy cập qua Kong

Khi toàn bộ workload đã Ready:

```powershell
kubectl -n coffee-platform port-forward service/kong 8000:8000
```

API gateway local nằm tại `http://localhost:8000`. Kong Admin API không được expose.

Lệnh trên chạy foreground và phải giữ terminal mở. Để chạy port-forward detached trên Windows, khởi động `kubectl` dưới nền và lưu PID:

```powershell
$process = Start-Process `
  -FilePath kubectl `
  -ArgumentList @(
    '-n', 'coffee-platform',
    'port-forward',
    'service/kong',
    '8000:8000',
    '--address=127.0.0.1'
  ) `
  -WindowStyle Hidden `
  -PassThru

$process.Id | Set-Content "$env:TEMP\coffee-kong-port-forward.pid"
```

Chỉ bind `127.0.0.1` để Kong không bị expose ra các máy khác trong mạng local. Có thể đóng terminal sau khi process đã khởi động.

Kiểm tra port-forward:

```powershell
Test-NetConnection localhost -Port 8000
```

Dừng đúng process đã lưu:

```powershell
$processId = Get-Content "$env:TEMP\coffee-kong-port-forward.pid"
Stop-Process -Id ([int]$processId)
Remove-Item "$env:TEMP\coffee-kong-port-forward.pid"
```

Kiểm tra process còn chạy:

```powershell
$processId = Get-Content "$env:TEMP\coffee-kong-port-forward.pid"
Get-Process -Id ([int]$processId)
```

Port-forward cần được chạy lại sau khi máy, Docker Desktop hoặc Kubernetes restart. Nếu PID file còn nhưng process đã mất, xóa PID file rồi chạy lại lệnh start.

Để có endpoint local ổn định mà không cần process `kubectl`, có thể đặt `kong.serviceType: LoadBalancer` trong `values-local.yaml` rồi Helm upgrade. Chỉ dùng cách này khi Docker Desktop hỗ trợ LoadBalancer và vẫn phải giữ Kong Admin API tắt; port-forward bind localhost là lựa chọn mặc định an toàn hơn.

## 13. Kết nối PostgreSQL của từng service

Mỗi service sở hữu database riêng. Không cấu hình service này truy vấn trực tiếp database của service khác. Các kết nối dưới đây chỉ dành cho debug, migration verification và quản trị local.

| Service | Host | Local port | Database | Username | Password key trong `coffee-platform.env` |
|---|---|---:|---|---|---|
| Identity | `localhost` | 15432 | `identity` | `identity_app` | `IDENTITY_DB_PASSWORD` |
| Organization | `localhost` | 15433 | `organization` | `organization_app` | `ORGANIZATION_DB_PASSWORD` |
| Catalog | `localhost` | 15434 | `catalog` | `catalog_app` | `CATALOG_DB_PASSWORD` |
| Inventory | `localhost` | 15435 | `inventory` | `inventory_app` | `INVENTORY_DB_PASSWORD` |
| Procurement | `localhost` | 15436 | `procurement` | `procurement_app` | `PROCUREMENT_DB_PASSWORD` |
| Order | `localhost` | 15437 | `orders` | `order_app` | `ORDER_DB_PASSWORD` |
| Payment | `localhost` | 15438 | `payment` | `payment_app` | `PAYMENT_DB_PASSWORD` |
| Loyalty | `localhost` | 15439 | `loyalty` | `loyalty_app` | `LOYALTY_DB_PASSWORD` |
| Promotion | `localhost` | 15440 | `promotion` | `promotion_app` | `PROMOTION_DB_PASSWORD` |
| Fulfillment | `localhost` | 15441 | `fulfillment` | `fulfillment_app` | `FULFILLMENT_DB_PASSWORD` |
| Notification | `localhost` | 15442 | `notification` | `notification_app` | `NOTIFICATION_DB_PASSWORD` |

Mở port-forward cho toàn bộ database:

```powershell
powershell -ExecutionPolicy Bypass `
  -File source/scripts/port-forward-databases.ps1
```

Giữ terminal này mở. Nhấn `Ctrl+C` để đóng toàn bộ port-forward.

Chỉ mở một database, ví dụ Order:

```powershell
powershell -ExecutionPolicy Bypass `
  -File source/scripts/port-forward-databases.ps1 `
  -Database order
```

Cấu hình DBeaver/pgAdmin cho Order:

```text
Host: localhost
Port: 15437
Database: orders
Username: order_app
Password: giá trị ORDER_DB_PASSWORD trong C:\secure\coffee-platform.env
```

Kết nối trực tiếp bên trong cluster không cần port-forward, ví dụ:

```powershell
kubectl -n coffee-platform exec -it order-db-0 -- psql -U order_app -d orders
```

## 14. Cập nhật hệ thống về sau

Chọn đúng quy trình theo loại thay đổi:

| Thay đổi | Build image | Helm upgrade | Restart thủ công |
|---|---:|---:|---:|
| Chỉ sửa code một service, vẫn dùng tag `local` | Image service đó | Không bắt buộc | Có, restart service đó |
| Sửa code nhiều service | Build toàn bộ | Không bắt buộc nếu chart không đổi | Có, restart các service đã sửa |
| Đổi chart, values, probe, resource hoặc NetworkPolicy | Nếu code không đổi thì không | Có | Helm tự rollout khi pod template đổi |
| Đổi Secret | Không | Không bắt buộc | Có, restart workload đọc Secret qua env |
| Thêm migration | Build service sở hữu DB | Có nếu tag/chart đổi | Helm rollout service |
| Thêm service mới | Build toàn bộ | Có | Helm tạo workload mới |

### 14.1. Sửa code của một service

Ví dụ chỉ sửa Order và tiếp tục dùng tag `local`:

```powershell
docker build --pull `
  -t coffee-local/order-service:local `
  source/services/order-service

kubectl rollout restart deployment/order-service -n coffee-platform
kubectl rollout status deployment/order-service -n coffee-platform --timeout=5m
kubectl logs deployment/order-service -n coffee-platform --tail=100
```

Thay `order` bằng tên service tương ứng. Không restart PostgreSQL, Redis hoặc RabbitMQ nếu chúng không thay đổi.

### 14.2. Sửa code nhiều service

Build lại toàn bộ image cùng tag `local`:

```powershell
powershell -ExecutionPolicy Bypass `
  -File source/deploy/helm/coffee-platform/Build-Images.ps1 `
  -Registry coffee-local `
  -Tag local
```

Chỉ restart các deployment đã thay đổi. Nếu thực sự đã thay đổi gần như toàn bộ ứng dụng:

```powershell
kubectl rollout restart deployment -n coffee-platform
kubectl rollout status deployment -n coffee-platform --timeout=10m
```

### 14.3. Sửa Helm chart hoặc values

Validate trước:

```powershell
helm lint source/deploy/helm/coffee-platform --strict
helm template coffee-platform `
  source/deploy/helm/coffee-platform `
  -f source/deploy/helm/coffee-platform/values-local.yaml `
  | kubectl apply --dry-run=server -f -
```

Nếu đã cài plugin `helm-diff`, xem thay đổi trước rollout:

```powershell
helm diff upgrade coffee-platform `
  source/deploy/helm/coffee-platform `
  -n coffee-platform `
  -f source/deploy/helm/coffee-platform/values-local.yaml
```

Triển khai sau khi đã có ít nhất một revision `deployed`:

```powershell
helm upgrade coffee-platform `
  source/deploy/helm/coffee-platform `
  --namespace coffee-platform `
  -f source/deploy/helm/coffee-platform/values-local.yaml `
  --force-conflicts `
  --rollback-on-failure `
  --wait `
  --timeout 20m
```

### 14.4. Dùng tag riêng cho mỗi lần rollout

Tag `local` nhanh nhưng khó phân biệt phiên bản. Khi cần biết chính xác revision đang chạy, dùng tag timestamp:

```powershell
$tag = Get-Date -Format "yyyyMMdd-HHmmss"
powershell -ExecutionPolicy Bypass `
  -File source/deploy/helm/coffee-platform/Build-Images.ps1 `
  -Registry coffee-local `
  -Tag $tag

helm upgrade coffee-platform `
  source/deploy/helm/coffee-platform `
  -n coffee-platform `
  -f source/deploy/helm/coffee-platform/values-local.yaml `
  --set-string global.imageTag=$tag `
  --force-conflicts `
  --rollback-on-failure `
  --wait `
  --timeout 20m
```

Chart hiện dùng một `global.imageTag` cho tất cả service và relay. Vì vậy khi dùng tag mới, phải build đủ 11 service và `outbox-relay` với cùng tag. Nếu chỉ build một service bằng tag mới rồi đổi `global.imageTag`, các workload còn lại sẽ gặp `ImagePullBackOff`.

### 14.5. Đổi Secret

Sửa file ngoài repository tại `C:\secure\coffee-platform.env`, sau đó:

```powershell
kubectl -n coffee-platform create secret generic coffee-platform-secrets `
  --from-env-file=C:\secure\coffee-platform.env `
  --dry-run=client -o yaml | kubectl apply -f -
```

Secret được mount qua environment variable không tự cập nhật trong process đang chạy. Restart đúng workload đọc key đã đổi, ví dụ:

```powershell
kubectl rollout restart deployment/identity-service -n coffee-platform
kubectl rollout status deployment/identity-service -n coffee-platform --timeout=5m
```

Không restart tất cả service khi chỉ một service dùng Secret vừa đổi.

### 14.6. Thêm database migration

1. Backup database của service sở hữu dữ liệu.
2. Dùng migration forward-only và ưu tiên thay đổi additive.
3. Build image mới của service.
4. Rollout service và kiểm tra lịch sử migration.

Ví dụ Order:

```powershell
kubectl -n coffee-platform exec order-db-0 -- `
  psql -U order_app -d orders -c `
  "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10"
```

Helm rollback workload không rollback database migration. Không xóa cột/bảng trong cùng rollout nếu phiên bản ứng dụng cũ vẫn có thể chạy.

### 14.7. Thêm service mới

Thêm service mới là thay đổi kiến trúc và phải được duyệt bounded context, owner dữ liệu và contract trước khi triển khai. Checklist tối thiểu:

1. Tạo `source/services/<name>-service/` với Dockerfile multi-stage, runtime non-root, health/readiness và graceful shutdown.
2. Thêm tên service vào mảng `$services` trong `source/deploy/helm/coffee-platform/Build-Images.ps1`.
3. Nếu sở hữu PostgreSQL, thêm service vào `databases` trong `values.yaml`, `values-local.yaml` và values production.
4. Thêm cấu hình workload vào `services`, gồm runtime, probe và environment variable.
5. Thêm `*_DB_PASSWORD`, `*_DB_URL` vào `secret.env.example` và Secret manager/local secret file.
6. Thêm route public vào `files/kong.yml`; API public vẫn phải đi qua Kong.
7. Thêm NetworkPolicy chỉ cho đúng caller/service được truy cập DB, Redis hoặc broker.
8. Nếu phát domain event, cập nhật event contract, outbox/consumer idempotency và relay khi cần.
9. Cập nhật OpenAPI, sequence/use case, tài liệu ownership và migration.
10. Build toàn bộ image cùng tag, lint/template chart, Helm upgrade và chạy acceptance test.

Không cấp cho service mới credential database của service khác và không thêm shared business database.

### 14.8. Kiểm tra sau mọi lần cập nhật

```powershell
helm status coffee-platform -n coffee-platform
helm history coffee-platform -n coffee-platform
kubectl get deploy,statefulset,pods -n coffee-platform
kubectl get events -n coffee-platform --sort-by=.lastTimestamp
```

Chỉ coi rollout hoàn tất khi Helm ở trạng thái `deployed`, workload đạt Ready, migration thành công và các smoke test liên quan đã chạy.
