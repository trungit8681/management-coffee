# Hướng dẫn Kubernetes và vận hành Coffee Management

Tài liệu này là nguồn hướng dẫn chính để build, triển khai, nâng cấp và vận hành toàn bộ hệ thống trên Kubernetes. Helm chart chuẩn nằm tại `source/deploy/helm/coffee-platform`.

## 1. Thành phần được triển khai

Chart triển khai 11 backend service:

1. identity-service
2. organization-service
3. catalog-service
4. inventory-service
5. procurement-service
6. order-service
7. payment-service
8. loyalty-service
9. promotion-service
10. fulfillment-service
11. notification-service

Hạ tầng đi kèm:

- 11 PostgreSQL StatefulSet, mỗi service sở hữu một database/user riêng.
- Identity Redis cho session/token security state.
- Gateway Redis cho rate-limit dùng chung giữa nhiều Kong pod.
- RabbitMQ cho domain event.
- 5 outbox relay cho Order, Payment, Inventory, Loyalty và Promotion.
- Kong DB-less, mặc định 2 replica.
- HPA, PDB, readiness/liveness/startup probes, resource requests/limits và NetworkPolicy.

Production lớn nên thay PostgreSQL, Redis và RabbitMQ trong chart bằng dịch vụ managed/clustered. Không chạy nhiều PostgreSQL replica bằng cách chỉ tăng `StatefulSet.replicas`; cần operator hoặc managed database hỗ trợ replication/failover.

## 2. Công cụ cần có

```powershell
docker version
kubectl version --client
kubectl config current-context
kubectl cluster-info
```

Helm không bắt buộc cài trên máy vì script validation dùng container Helm. Để thao tác trực tiếp, có thể cài Helm 3.17 trở lên.

Cluster cần:

- Kubernetes 1.28 trở lên.
- StorageClass hỗ trợ `ReadWriteOnce`.
- Metrics Server nếu bật HPA.
- Ingress Controller hoặc Gateway API để expose Kong.
- Container registry mà worker node có thể pull.

## 3. Cấu trúc triển khai

```text
source/deploy/helm/coffee-platform/
├── Chart.yaml
├── values.yaml
├── values-production.example.yaml
├── secret.env.example
├── Build-Images.ps1
├── Test-Chart.ps1
├── files/kong.yml
└── templates/
```

`values.yaml` là cấu hình mặc định. Production nên copy `values-production.example.yaml` thành một file values riêng được quản lý qua GitOps, nhưng không chứa secret.

## 4. Build và push toàn bộ image

Đăng nhập registry trước:

```powershell
docker login ghcr.io
```

Build 11 service và outbox relay, dùng Git SHA làm tag bất biến:

```powershell
$tag = git rev-parse --short HEAD
powershell -ExecutionPolicy Bypass `
  -File source/deploy/helm/coffee-platform/Build-Images.ps1 `
  -Registry ghcr.io/your-organization/coffee `
  -Tag $tag `
  -Push
```

Không dùng `latest` trong production. Nếu registry hỗ trợ digest, pin digest trong quy trình GitOps sau khi push.

## 5. Tạo values production

Copy file mẫu:

```powershell
Copy-Item `
  source/deploy/helm/coffee-platform/values-production.example.yaml `
  C:\secure\coffee-values-production.yaml
```

Sửa tối thiểu:

```yaml
global:
  imageRegistry: ghcr.io/your-organization/coffee
  imageTag: "<git-sha>"
  frontendOrigin: https://coffee.example.com
storageClassName: premium-rwo
```

Nếu registry private:

```powershell
kubectl create namespace coffee-platform --dry-run=client -o yaml | kubectl apply -f -
kubectl -n coffee-platform create secret docker-registry registry-credentials `
  --docker-server=ghcr.io `
  --docker-username=YOUR_USER `
  --docker-password=YOUR_TOKEN
```

Và khai báo:

```yaml
imagePullSecrets:
  - name: registry-credentials
```

### Cấu hình Docker Desktop local

Dùng `source/deploy/helm/coffee-platform/values-local.yaml` khi chạy trên Kubernetes của Docker Desktop. File này dùng image `coffee-local/*:local`, một replica, tắt HPA vì Docker Desktop có thể chưa cài Metrics Server, tắt PDB vì local chỉ có một node và giới hạn Kong worker để tránh OOM. Các container ứng dụng và hạ tầng vẫn chạy non-root; init container có quyền tối thiểu chỉ dùng để gán ownership cho PVC mới.

Quy trình local đầy đủ, gồm namespace ownership, Secret/JWT, first install và xử lý các lỗi thường gặp, nằm tại [KUBERNETES-LOCAL.md](KUBERNETES-LOCAL.md).

## 6. Chuẩn bị secret

Copy `secret.env.example` ra thư mục ngoài Git:

```powershell
Copy-Item `
  source/deploy/helm/coffee-platform/secret.env.example `
  C:\secure\coffee-platform.env
```

Thay toàn bộ `replace-me`. Mật khẩu nằm trong URL PostgreSQL/RabbitMQ phải được URL-encode nếu chứa `@`, `:`, `/`, `#`, `%` hoặc ký tự dành riêng.

Tạo Secret mà không in giá trị ra terminal:

```powershell
kubectl -n coffee-platform create secret generic coffee-platform-secrets `
  --from-env-file=C:\secure\coffee-platform.env `
  --dry-run=client -o yaml | kubectl apply -f -
```

Kiểm tra tên key, không đọc giá trị:

```powershell
kubectl -n coffee-platform describe secret coffee-platform-secrets
```

## 7. Chuẩn bị JWT signing key

Production không được bật `IDENTITY_JWT_GENERATE_IF_MISSING`. Tạo RSA key ngoài cluster:

```powershell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out C:\secure\jwt-private.pem
openssl rsa -pubout -in C:\secure\jwt-private.pem -out C:\secure\jwt-public.pem
```

Tạo secret:

```powershell
kubectl -n coffee-platform create secret generic identity-jwt-keys `
  --from-file=jwt-private.pem=C:\secure\jwt-private.pem `
  --from-file=jwt-public.pem=C:\secure\jwt-public.pem `
  --dry-run=client -o yaml | kubectl apply -f -
```

Giữ private key trong Vault/KMS/secret manager và có kế hoạch rotation theo `kid`.

## 8. Validate trước khi triển khai

Chạy Helm lint và kubeconform:

```powershell
powershell -ExecutionPolicy Bypass `
  -File source/deploy/helm/coffee-platform/Test-Chart.ps1
```

Render với values production:

```powershell
docker run --rm `
  -v "${PWD}:/workspace" -w /workspace `
  alpine/helm:3.17.3 template coffee `
  source/deploy/helm/coffee-platform `
  -f C:/secure/coffee-values-production.yaml
```

Nếu Helm đã được cài:

```powershell
helm lint source/deploy/helm/coffee-platform --strict
helm template coffee source/deploy/helm/coffee-platform `
  -f C:\secure\coffee-values-production.yaml |
  kubectl apply --dry-run=server -f -
```

Server-side dry-run cần kubeconfig hợp lệ và quyền truy cập cluster.

## 9. Backup trước rollout

Trước khi nâng cấp Order/Inventory hoặc migration mới:

- Snapshot volume/database.
- Kiểm tra khả năng restore trên môi trường riêng.
- Ghi lại image digest đang chạy.
- Ghi lại version Flyway hiện tại.

Ví dụ backup PostgreSQL đơn giản:

```powershell
kubectl -n coffee-platform exec order-db-0 -- `
  pg_dump -U order_app -Fc orders > C:\backup\orders.dump
```

Production nên dùng backup operator hoặc managed database PITR thay vì chỉ dựa vào lệnh thủ công.

## 10. Cài mới bằng Helm

```powershell
helm upgrade --install coffee-platform `
  source/deploy/helm/coffee-platform `
  --namespace coffee-platform `
  --create-namespace `
  -f C:\secure\coffee-values-production.yaml `
  --atomic `
  --timeout 15m
```

`--atomic` rollback Kubernetes resources nếu install/upgrade không ready trong timeout. Nó không rollback database migration; migration là forward-only.

Theo dõi:

```powershell
kubectl -n coffee-platform get pods -w
kubectl -n coffee-platform get deploy,sts,svc,hpa,pdb,pvc
```

## 11. Thứ tự readiness và migration

PostgreSQL/Redis/RabbitMQ phải ready trước service. Java service chạy Flyway khi khởi động; Node service chạy SQL migration của chính service. Migration dùng database owner riêng và không truy cập schema service khác.

Version quan trọng hiện tại:

- Inventory Flyway: `2`.
- Order Flyway: `6`.

Kiểm tra:

```powershell
kubectl -n coffee-platform exec order-db-0 -- `
  psql -U order_app -d orders -c `
  "SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
```

## 12. Expose Kong

Kong mặc định là `ClusterIP`. Kiểm tra nhanh bằng port-forward:

```powershell
kubectl -n coffee-platform port-forward service/kong 8000:8000
```

Production expose Kong qua Ingress/Gateway có TLS. Không expose Kong Admin API; chart đặt `KONG_ADMIN_LISTEN=off`.

Ví dụ Ingress:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: coffee-api
  namespace: coffee-platform
spec:
  tls:
    - hosts: [api.coffee.example.com]
      secretName: coffee-api-tls
  rules:
    - host: api.coffee.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service: {name: kong, port: {number: 8000}}
```

## 13. Acceptance test sau rollout

Kiểm tra health:

```powershell
kubectl -n coffee-platform get pods
kubectl -n coffee-platform get events --sort-by=.lastTimestamp
```

Checkout tiền mặt:

```powershell
$key=[guid]::NewGuid().ToString()
curl.exe -X POST `
  "https://api.coffee.example.com/api/v1/orders/ORDER_ID/checkout-cash" `
  -H "Authorization: Bearer ACCESS_TOKEN" `
  -H "Idempotency-Key: $key" `
  -H "Content-Type: application/json" `
  -d '{"points":0,"cashReceivedVnd":50000}'
```

Nếu nhận `202`, retry đúng body và đúng idempotency key. Không tạo key mới cho cùng lần checkout.

Acceptance bắt buộc:

- Đăng nhập/JWKS hoạt động.
- Tạo catalog price/recipe và order.
- Checkout không voucher/điểm.
- Checkout có voucher.
- Checkout có điểm và customerId.
- Sáu request đồng thời cùng key chỉ tạo một payment và một stock deduction.
- Thiếu ingredient sau khi đã trừ ingredient trước phải ghi `REVERSAL` và không thu tiền.
- Outbox backlog trở về 0.
- Duplicate event chỉ có một inbox result.
- Poison message vào DLQ sau năm lần thử.
- Nhiều Kong pod dùng chung Redis counter.

## 14. RabbitMQ, relay và DLQ

```powershell
kubectl -n coffee-platform exec rabbitmq-0 -- `
  rabbitmqctl list_queues name messages consumers
kubectl -n coffee-platform logs deployment/order-outbox-relay --tail=200
kubectl -n coffee-platform logs deployment/payment-outbox-relay --tail=200
```

Cảnh báo khi:

- Outbox chưa publish tăng liên tục.
- Relay restart hoặc health fail.
- Queue depth tăng mà consumer không tăng.
- DLQ có message.
- Saga ở `STARTED`, `STOCK_DEDUCTED`, `COMPENSATING` hoặc `PAID_PENDING` quá SLA.

Không purge DLQ trước khi lưu event ID, payload tối thiểu, lỗi và quyết định replay/discard.

## 15. Redis và Kong rate-limit

```powershell
kubectl -n coffee-platform exec gateway-redis-0 -- redis-cli -n 1 dbsize
kubectl -n coffee-platform get pods -l app.kubernetes.io/name=kong
```

Gửi request qua nhiều Kong pod và xác nhận counter Redis dùng chung. Redis lock/rate limit không thay thế PostgreSQL uniqueness và idempotency.

## 16. Theo dõi hằng ngày

```powershell
kubectl -n coffee-platform get pods
kubectl -n coffee-platform top pods
kubectl -n coffee-platform get hpa
kubectl -n coffee-platform get pvc
kubectl -n coffee-platform get events --sort-by=.lastTimestamp
kubectl -n coffee-platform logs deployment/order-service --tail=200
kubectl -n coffee-platform describe pod POD_NAME
```

Theo dõi tối thiểu:

- HTTP error rate, p95/p99 latency.
- JVM/Node memory và restart count.
- PostgreSQL connections, locks, replication/backup nếu managed.
- Outbox age/count, inbox duplicate/failure count.
- RabbitMQ queue/DLQ depth.
- Saga non-terminal age.
- Kong 4xx/5xx và Redis errors.

Identity và Organization đã có Prometheus endpoint. Business-service metrics đầy đủ vẫn cần bổ sung trong increment quan sát hệ thống; probes không thay thế metrics/alerting.

## 17. Nâng cấp phiên bản

1. Build/test/push image bằng Git SHA mới.
2. Cập nhật `global.imageTag` trong values GitOps.
3. Chạy `Test-Chart.ps1`.
4. Backup database nếu có migration.
5. Xem diff.
6. Upgrade atomic.
7. Chạy acceptance test.

```powershell
helm diff upgrade coffee-platform source/deploy/helm/coffee-platform `
  -n coffee-platform -f C:\secure\coffee-values-production.yaml

helm upgrade coffee-platform source/deploy/helm/coffee-platform `
  -n coffee-platform -f C:\secure\coffee-values-production.yaml `
  --atomic --timeout 15m
```

`helm diff` cần plugin `helm-diff`.

## 18. Rollback

Xem revision:

```powershell
helm history coffee-platform -n coffee-platform
```

Rollback Kubernetes workload:

```powershell
helm rollback coffee-platform REVISION -n coffee-platform --wait --timeout 15m
```

Không xóa cột/bảng để rollback migration. Order V3-V6 và Inventory V2 là additive; giữ schema và rollback image, sau đó forward-fix nếu cần.

Không xóa PVC khi rollback:

```powershell
kubectl -n coffee-platform get pvc
```

## 19. GitOps với Argo CD

File mẫu nằm tại `source/deploy/argocd/coffee-platform-application.yaml`.

Trước khi apply:

- Thay `repoURL`.
- Commit một file `values-production.yaml` không chứa secret.
- Quản lý secret bằng External Secrets/Vault/Sealed Secrets.
- Giữ production manual sync hoặc approval trong pipeline.

```powershell
kubectl apply -f source/deploy/argocd/coffee-platform-application.yaml
argocd app diff coffee-platform
argocd app sync coffee-platform
argocd app wait coffee-platform --health --timeout 900
```

Argo CD deploy, không build Docker image. Build nên dùng CI hoặc Argo Workflows + BuildKit/Kaniko, push image SHA rồi commit tag/digest vào values GitOps.

## 20. Xử lý sự cố nhanh

`ImagePullBackOff`:

- Kiểm tra registry/tag.
- Kiểm tra `imagePullSecrets`.
- Thử pull image bằng credential tương ứng.

`CrashLoopBackOff`:

```powershell
kubectl -n coffee-platform logs POD --previous
kubectl -n coffee-platform describe pod POD
```

Order không ready:

- Kiểm tra Order DB và Flyway.
- Kiểm tra Identity JWKS DNS.
- Kiểm tra RabbitMQ credentials/DNS.

Checkout `PAID_PENDING`:

- Không compensation tiền/kho thủ công.
- Kiểm tra Payment receipt và RabbitMQ.
- Retry cùng idempotency key.

`INVALID_DISCOUNT` trên order đã thanh toán:

- Kiểm tra `customer_order.payment_status`.
- Không checkout lại order `PAID`; tạo order mới.

PVC Pending:

- Kiểm tra StorageClass/access mode/quota.
- Không xóa PVC có dữ liệu production khi chưa có backup và kế hoạch restore.

## 21. Quy tắc an toàn production

- Không commit Secret, kubeconfig, JWT private key hoặc registry token.
- Không expose PostgreSQL, Redis, RabbitMQ management hoặc Kong Admin ra Internet.
- Mỗi service/relay chỉ dùng credential database của bounded context đó.
- Bật TLS ngoài cluster và ưu tiên mTLS/service mesh cho traffic nội bộ nhạy cảm.
- Dùng managed HA database/broker cho production quan trọng.
- Test restore định kỳ; backup chưa được restore-test không được xem là an toàn.
- Mọi payment/refund/stock/points command phải giữ idempotency và append-only ledger.

