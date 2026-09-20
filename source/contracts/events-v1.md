# Local outbox events, version 1

Each event is inserted into the owning service's `outbox_event` table in the same PostgreSQL transaction as the business change. The table columns are `id`, `aggregate_id`, `event_type`, `payload`, `created_at`, and nullable `published_at`. `id` is the event identity for future idempotent consumers. The current deployment has no relay or broker; rows are **not** automatically delivered to another service.

| Owner | Event types | Current payload fields |
|---|---|---|
| Catalog | `ProductCreated.v1`, `PricePublished.v1` | productId/variantId; variantId/branchId/channel/unitPriceVnd/version |
| Inventory | `IngredientCreated.v1`, `StockMoved.v1` | ingredientId; movementId |
| Procurement | `SupplierCreated.v1`, `PurchaseOrderCreated.v1`, `PurchaseOrderApproved.v1` | id |
| Order | `OrderCreated.v1`, `OrderCashConfirmed.v1`, `OrderCancellationRequested.v1`, `OrderCashRefunded.v1` | orderId/branchId/totalVnd/channel; orderId/paymentId; orderId/status; orderId/refundId |
| Payment | `CashPaymentRecorded.v1`, `CashRefundRecorded.v1` | paymentId/orderId/branchId/totalVnd; refundId/orderId/amountVnd |
| Promotion | `VoucherCreated.v1`, `VoucherReserved.v1`, `VoucherCommitted.v1`, `VoucherReleased.v1` | voucherId/branchId; voucherId/orderId/discountVnd; voucherId/orderId |
| Loyalty | `CustomerCreated.v1`, `PointsCredited.v1`, `PointsReserved.v1`, `PointsCommitted.v1`, `PointsReleased.v1` | id only in current slice |
| Fulfillment | `DeliveryCreated.v1`, `DeliveryAssigned.v1`, `DeliveryCompleted.v1`, `DeliveryFailed.v1` | deliveryId/orderId/branchId; deliveryId/driverId; deliveryId/orderId; deliveryId |
| Notification | `TemplateCreated.v1`, `NotificationQueued.v1` | templateId; notificationId/recipientId/branchId |

Before enabling a relay, define a transport envelope, consumer inbox table, retry/dead-letter policy, service credentials and PII minimization. Consumers must enforce uniqueness on event ID and tolerate out-of-order arrival. Existing payloads are local draft contracts and must be versioned before external subscription.
