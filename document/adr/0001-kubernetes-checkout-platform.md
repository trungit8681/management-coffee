# ADR 0001: Kubernetes topology for checkout saga

- Status: accepted
- Date: 2026-09-27

## Context

Cash checkout coordinates Order, Promotion, Loyalty, Inventory and Payment. The system needs durable saga recovery, at-least-once event delivery, duplicate-safe consumption and a rate-limit counter shared by multiple Kong instances. Existing Kubernetes manifests are not stored in this repository.

## Decision

Use only the Helm chart under `source/deploy/helm/coffee-platform` as the deployment source for all services, owned PostgreSQL databases and shared infrastructure. Parallel Kustomize manifests are intentionally not retained because two package managers owning resources with the same names can overwrite labels, fields and rollout history. Order remains the saga owner. RabbitMQ carries versioned outbox events, while one relay deployment per owner database uses only that database's credentials. Order stores consumer event IDs in its own inbox. Kong uses a dedicated persistent Redis instance for shared counters. Business services and Kong run multiple replicas with PDB/HPA safeguards; saga execution is serialized by a PostgreSQL lease and protected by downstream idempotency/database constraints.

Service-owned PostgreSQL databases remain external to this package. Secrets are injected through a pre-created Kubernetes Secret and never committed. Database migrations are forward-only and run before full scale-out.

## Consequences

- A relay crash after publish confirmation but before marking the row may redeliver; inbox uniqueness is mandatory.
- RabbitMQ and Redis become production dependencies and require backup, availability and alerting appropriate to the environment.
- Single-replica manifests are suitable only as a bootstrap. Production may replace them with managed or clustered equivalents without changing business ownership.
- Existing installations must align their Service DNS names or supply an environment overlay.
- Application metrics for business services are not yet complete; Kubernetes health probes alone are insufficient for production observability.

