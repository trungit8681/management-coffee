# Coffee Management — Codex Guidance

## Source of truth

- Read `document/BangKeHoach.md` and the relevant files in `document/usecase/` and `document/sequence/` before changing behavior.
- Treat approved use cases as business scope and sequence diagrams as interaction contracts. Update documentation with behavior changes.
- Current payment scope is cash only. “POS” means the sales application, not a card terminal. Bank transfer and card-terminal integration are future work unless the user explicitly brings them into scope.

## Architecture

- Build bounded contexts using DDD. Keep `domain` independent of frameworks, databases, HTTP, Redis, and brokers.
- A service owns its PostgreSQL data. Never query or write another service's schema directly.
- Keep ACID transactions local to one service. Use Outbox, idempotent consumers, and Saga compensation across services.
- Redis locks coordinate concurrent instances; they never replace PostgreSQL constraints, transactions, row/version checks, or idempotency.
- Every Redis lock needs an owner token, TTL, bounded acquisition timeout, safe compare-and-delete release, and contention metrics. Do not hold a lock during an external network call.
- Public APIs enter through Kong. Enforce authorization by action and branch scope inside the owning service.
- Access tokens are short-lived JWTs. Refresh tokens rotate and reuse revokes the token family/session.

## Implementation conventions

- Use Java 21 + Spring Boot for identity, order, inventory, payment, and transaction-heavy domains.
- Use Node.js LTS + TypeScript for catalog/query, promotions, notifications, delivery adapters, and reporting.
- Do not mix Java and Node.js inside one service.
- Place service code under `source/services/<service-name>/` and shared contracts under `source/contracts/`. Share schemas and generated clients, not domain models.
- Keep secrets out of source, images, logs, examples, and committed environment files.
- All containers use multi-stage builds, pinned base versions, non-root runtime users, health endpoints, and graceful shutdown.

## Change workflow

1. Map the request to a use case, sequence, bounded context, aggregate, and owner service.
2. State invariants, authorization/branch scope, transaction boundary, idempotency behavior, events, and failure compensation.
3. Implement the smallest coherent vertical slice: contract, application use case, domain rules, adapter, migration, tests, and docs.
4. Run the narrowest relevant tests, then `powershell -ExecutionPolicy Bypass -File .codex/audit/check-project.ps1`.
5. Report changed behavior, verification evidence, migration/operations impact, and remaining risks.

For detailed workflows and audit levels, use the repo skill `$coffee-backend` in `.agents/skills/coffee-backend/`.

## Review gates

- Never silently add a new microservice, external dependency, payment method, or shared database.
- Require explicit user approval before destructive migrations, production operations, secret rotation, deployment, or changes that expand payment scope.
- Reject code paths that can confirm an order after insufficient cash, double-spend voucher/points, double-deduct inventory, open duplicate shifts, or process duplicate cash payment/refund commands.
- For code changes, tests must cover success, validation failure, authorization failure, duplicate request, and concurrency/failure behavior where applicable.

