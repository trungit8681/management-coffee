# Business services: local vertical slices

The nine services under `services/` own separate PostgreSQL databases. `compose-business.yml` runs them behind Kong. Identity remains a separate deployment and issues the RS256 access tokens that each service verifies through JWKS. No service has another service's database credentials.

| Service | Implemented command/query slice | Owned data |
|---|---|---|
| catalog | Product/variant creation, branch/channel price publication, sellable price lookup | Product, variant, price history |
| inventory | Ingredient creation, stock receipt/deduction, balance lookup | Ingredient, stock balance, append-only ledger |
| procurement | Supplier, purchase order creation and approval | Supplier, purchase order |
| order | POS/pickup/delivery order creation using catalog price, cash quote, paid confirmation, cancellation/refund state | Order, order item |
| payment | Verified cash collection/change and approved full cash refund | Cash payment, refund |
| promotion | Voucher creation, bounded reservation, commit/release | Voucher, reservation |
| loyalty | Customer wallet, point credit, reservation, commit/release | Wallet, reservation, point ledger |
| fulfillment | Delivery creation/assignment, failed or paid handover | Delivery, delivery attempt |
| notification | Template, queued in-app message, inbox worker | Template, notification, delivery log |

All command services write a local outbox row with the business change. This increment does **not** include an event broker or outbox relay. Promotion, loyalty, inventory and notification actions are available as explicit authenticated commands; order does not yet orchestrate them. Supplier purchase-order receipt validation, recipe-based automatic stock deduction, stocktake/transfer, partial refunds, external delivery partners, and email/SMS/push adapters also remain separate increments. These APIs must not be treated as a complete production checkout saga.

## Local run: one service

Each business service now has its own `services/<name>-service/compose.yml` and `.env.example`, following Identity and Organization. Its Compose project starts only that service and its PostgreSQL database, with a separate named volume from the integrated stack. Start Identity first. For example, from the repository root:

```powershell
Copy-Item source/services/catalog-service/.env.example source/services/catalog-service/.env
# Set CATALOG_DB_PASSWORD and adjust CATALOG_JWKS_URI in the uncommitted .env.
docker compose --env-file source/services/catalog-service/.env -f source/services/catalog-service/compose.yml up --build -d
```

Repeat with the desired service name. Default HTTP ports are Identity 8080, Organization 8081, Catalog 8082, Inventory 8083, Procurement 8084, Payment 8085, Order 8086, Loyalty 8087, Promotion 8088, Notification 8089, and Fulfillment 8090. Each `.env.example` also lists its configurable PostgreSQL host port. Order, Payment, and Fulfillment use the published HTTP ports for cross-service calls, so start their dependencies first and update the corresponding `*_BASE_URL` values when ports differ. Identity's JWKS URL must be reachable from the containers. Standalone host ports support local development; route public traffic through Kong in a deployment.

## Local run: integrated stack

1. Start `identity-service` using its own `compose.yml`. Configure a local bootstrap administrator and apply Flyway V4–V6.
2. Copy `.env.example` to uncommitted `.env` in this directory. Set all database passwords and `IDENTITY_JWKS_URI` to an Identity URL reachable from containers. The default local Identity JWKS URL is `http://host.docker.internal:8080/.well-known/jwks.json`.
3. Run `docker compose --env-file source/.env -f source/compose-business.yml config --quiet` from the repository root, then `docker compose --env-file source/.env -f source/compose-business.yml up --build -d`.
4. Kong listens on `BUSINESS_GATEWAY_PORT` (default 8000). Service containers have no host ports. Public requests use Kong routes; service-to-service requests use the private Compose network and still verify the forwarded JWT.
5. Set `IDENTITY_BOOTSTRAP_ADMIN_USERNAME` and `IDENTITY_BOOTSTRAP_ADMIN_PASSWORD` in the shell only for local smoke tests. Run `powershell -ExecutionPolicy Bypass -File source/scripts/smoke-pos.ps1`, `smoke-operations.ps1`, `smoke-refund.ps1`, and `smoke-concurrency.ps1`.

Compose does not remove named PostgreSQL volumes on normal shutdown. Test data persists between restarts. Flyway migrations are forward-only; back up a database before any production rollout. Deploy Identity permissions before issuing new tokens and starting business services. Then deploy catalog/order/payment and the remaining services. Monitor each service's health endpoint and local outbox backlog.
