# Project workflows

## Feature delivery

1. Trace the feature from use case to sequence and owner service.
2. Define acceptance cases, aggregate invariants, authorization, idempotency, transaction boundary, event effects, and failure behavior.
3. Update contract first when API/event behavior changes.
4. Implement domain, application, and infrastructure layers without leaking framework types into domain code.
5. Add migration and backward-compatibility handling when persistence changes.
6. Test domain behavior, database/Redis integration, contract compatibility, duplicates, and meaningful concurrency.
7. Update the affected PlantUML and plan in the same change.

## Bug fix

1. Reproduce or establish the failure with evidence.
2. Identify the violated invariant and owning layer.
3. Add a failing regression test.
4. Apply the smallest fix at the invariant boundary.
5. Run related tests and inspect adjacent flows for the same defect pattern.

## Architecture or service-boundary change

1. Compare the proposal with bounded contexts in `document/BangKeHoach.md`.
2. Record alternatives and consequences in `document/adr/NNNN-title.md`.
3. Identify data ownership, migration, API/event compatibility, deployment order, rollback, and observability.
4. Do not create a new deployable service solely to mirror a table or UI page.
5. Update diagrams and the service catalog after approval.

## Docker and Kong

1. Keep Dockerfiles multi-stage and runtime images non-root.
2. Add health/readiness endpoints and graceful shutdown before orchestration wiring.
3. Validate Compose configuration without starting services when possible.
4. Keep Kong configuration declarative and versioned. Specify route, upstream, auth, rate limit, request limits, timeout, and safe retry behavior.
5. Never place secrets in Dockerfiles, Compose defaults, Kong declarative files, or logs.

## Documentation-only change

1. Check all use cases, sequences, README summaries, and `BangKeHoach.md` for conflicting language.
2. Preserve actor/service aliases and balanced PlantUML control blocks.
3. Run the repository audit even when no source code exists.

## Cash payment workflow

- At-counter orders become `CONFIRMED` only after received cash covers the total and `CashPayment` is recorded.
- Online/delivery orders may be `CONFIRMED` with `paymentStatus=PENDING_CASH`; record payment at handover.
- Cash refunds require authorization and an append-only refund record.
- Use an idempotency key and database uniqueness for payment/refund commands. Redis lock by `orderId` may reduce contention but is not the final safeguard.
- Transfer, QR payment, wallets, acquiring gateways, and card terminals stay outside current scope.

