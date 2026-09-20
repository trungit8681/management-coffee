# Procurement Service

Owns suppliers and branch purchase orders. An active supplier is required to create a positive-quantity PO. Approval requires `procurement:approve_po`, branch scope and an `If-Match` version; a conditional update prevents duplicate approval. Create commands use idempotency keys and local outbox writes. Goods receipt matching with Inventory remains pending. Local standalone configuration and deployment are in `source/services/procurement-service/.env.example` and `compose.yml`; integrated configuration is in `source/.env.example` and `source/compose-business.yml`.
