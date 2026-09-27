# Local outbox events, version 1

Each event is inserted into the owning service's `outbox_event` table in the same PostgreSQL transaction as the business change. The relay publishes a persistent envelope to the durable `coffee.events` RabbitMQ topic exchange and marks `published_at` only after publisher confirmation. A relay crash between publish and mark can redeliver, so consumers claim `eventId` in their local `inbox_event` table in the same transaction as their state change.

| Owner | Event types | Current payload fields |
|---|---|---|
| Catalog | `ProductCreated.v1`, `PricePublished.v1`, `RecipePublished.v1` | productId/variantId; variantId/branchId/channel/unitPriceVnd/version; variantId/version/ingredients |
| Inventory | `IngredientCreated.v1`, `StockMoved.v1` | ingredientId; movementId |
| Procurement | `SupplierCreated.v1`, `PurchaseOrderCreated.v1`, `PurchaseOrderApproved.v1` | id |
| Order | `OrderCreated.v1`, `OrderCashConfirmed.v1`, `OrderCancellationRequested.v1`, `OrderCashRefunded.v1` | orderId/branchId/totalVnd/channel; orderId/paymentId; orderId/status; orderId/refundId |
| Payment | `CashPaymentRecorded.v1`, `CashRefundRecorded.v1` | paymentId/orderId/branchId/totalVnd; refundId/orderId/amountVnd |
| Promotion | `VoucherCreated.v1`, `VoucherReserved.v1`, `VoucherCommitted.v1`, `VoucherReleased.v1` | voucherId/branchId; voucherId/orderId/discountVnd; voucherId/orderId |
| Loyalty | `CustomerCreated.v1`, `PointsCredited.v1`, `PointsReserved.v1`, `PointsCommitted.v1`, `PointsReleased.v1` | id only in current slice |
| Fulfillment | `DeliveryCreated.v1`, `DeliveryAssigned.v1`, `DeliveryCompleted.v1`, `DeliveryFailed.v1` | deliveryId/orderId/branchId; deliveryId/driverId; deliveryId/orderId; deliveryId |
| Notification | `TemplateCreated.v1`, `NotificationQueued.v1` | templateId; notificationId/recipientId/branchId |

The envelope fields are `eventId`, `eventType`, `source`, `aggregateId`, `occurredAt`, and `payload`. Order consumes `CashPaymentRecorded.v1` through a durable queue, retries five times with bounded backoff, then dead-letters to `order.cash-payment-recorded.v1.dlq`. Consumers enforce uniqueness on event ID, tolerate out-of-order arrival, and must not publish credentials or unnecessary PII.
