---
name: coffee-backend
description: Design, implement, review, or audit backend changes for this coffee-chain DDD microservice project, including Kong, Docker, PostgreSQL, Redis locks, JWT, orders, inventory, cash payments, and related PlantUML contracts. Do not use for unrelated repositories or frontend-only visual changes.
---

# Coffee Backend

Deliver a coherent vertical slice that preserves the documented business scope and service ownership.

## Start with context

Read `document/BangKeHoach.md`, then only the use-case and sequence files relevant to the request. Inspect existing code and migrations before choosing a solution.

Map the change to:

- actor, use case, and branch scope;
- bounded context, aggregate, and owning service;
- command/query and local transaction boundary;
- synchronous API and domain events;
- idempotency key, concurrency risk, and compensation;
- audit events and sensitive fields that must not be logged.

If the mapping is ambiguous and changes service ownership or business scope, ask the user. Otherwise make the smallest reasonable assumption and record it.

## Implement

- Keep domain code framework-free and enforce invariants in aggregates/value objects.
- Use PostgreSQL as the source of truth and keep writes within the owner service.
- Write outbox records in the same transaction as aggregate changes.
- Make commands and consumers idempotent.
- Add Redis locking only for demonstrated cross-instance contention. Follow the lock invariants in `AGENTS.md` and retain a database safeguard.
- Route public contracts through Kong and enforce role/action/branch authorization in the service.
- Preserve cash-only payment scope unless the user explicitly requests the future payment phase.
- Update OpenAPI/events, migrations, tests, sequence/use-case diagrams, and planning notes when behavior changes.

## Choose a workflow

Read [workflows.md](references/workflows.md) for feature delivery, architecture changes, bug fixes, documentation changes, or Docker/Kong work.

Read [audit.md](references/audit.md) when the user asks for review/audit, before a release, or when a change touches authentication, authorization, cash, orders, inventory, vouchers/points, Redis locks, migrations, or external integrations.

## Finish

Run the narrowest relevant tests and the repository audit:

```powershell
powershell -ExecutionPolicy Bypass -File .codex/audit/check-project.ps1
```

Report what changed, evidence run, data/API/event compatibility, operational impact, and unresolved risk. Do not claim a check passed if it was unavailable.

