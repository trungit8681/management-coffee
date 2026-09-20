# Audit protocol

## Select depth

- **Change audit:** inspect the changed files and directly affected contracts.
- **Domain audit:** inspect one bounded context end to end, including API, domain, persistence, events, Redis, tests, and diagrams.
- **Release audit:** inspect all changed services plus Kong, Docker, migrations, security, observability, backup/rollback, and cross-service compatibility.

Use the smallest depth that covers the risk. Authentication, authorization, payment, refund, inventory, voucher/points, and destructive migrations require at least a domain audit.

## Required checks

### Domain and data

- Aggregate invariants cannot be bypassed by controllers or consumers.
- Each write targets only the owner service's PostgreSQL database.
- Migration is safe for existing data, has an explicit rollout order, and has a rollback/forward-fix strategy.
- Ledger-like cash, point, and stock records are append-only and reconcilable.

### Distributed behavior

- Commands, callbacks, and event consumers tolerate duplicates.
- Outbox write is atomic with business data and failed publications are observable.
- Saga steps define timeout, retry, and compensation.
- Redis locks use TTL, owner-safe release, bounded wait, stable lock ordering, metrics, and database safeguards.

### Security and privacy

- JWT validation checks issuer, audience, expiry, signature, and key identifier.
- Refresh rotation rejects reuse and revokes the affected family/session.
- Authorization covers action and branch scope; client-supplied scope is not trusted.
- Logs and audit payloads exclude passwords, tokens, secrets, personal data not needed for audit, and future card data.
- Kong routes apply appropriate authentication, rate limits, request limits, and trusted callback controls.

### Operations and evidence

- Containers run non-root with health/readiness and graceful shutdown.
- Metrics cover error/latency, pool pressure, queue/outbox lag, lock contention, duplicates, and reconciliation failures.
- Tests cover success, invalid input, forbidden scope, duplicate request, concurrency, and dependency failure where relevant.
- Use cases, sequences, OpenAPI/event contracts, and planning docs agree.

## Finding format

Record findings by severity: `critical`, `high`, `medium`, or `low`.

For each finding include:

- file and line;
- violated invariant or realistic failure scenario;
- impact and affected bounded context;
- minimal remediation;
- evidence or test needed to close it.

Lead with findings. If none are found, state that explicitly and list residual risks or checks that could not run.

