# Organization Service Design

## Scope and ownership

`organization-service` is the Organization bounded context from `BangKeHoach.md`. It owns branches, employee business profiles, branch assignments, published schedules, attendance and register work shifts. `identityUserId` is an external Identity identifier and deliberately has no database foreign key.

The initial vertical slice implements use cases from sequences 02 and 03: branch maintenance, employee creation/assignment, schedule publication with conflict detection, check-in/check-out, and cash-shift open/close reconciliation. Delivery areas, availability, leave and shift-swap workflows remain later increments.

## Invariants and transactions

- Branch code and employee code are unique; employee identity is one-to-one.
- An employee assignment cannot overlap another assignment for the same branch.
- Published schedule entries for an employee cannot overlap, even across concurrent requests. PostgreSQL exclusion constraints are the final safeguard.
- Check-in requires a current branch assignment; missing/mismatched schedules require explicit exception approval. Only one open attendance per employee is allowed.
- Only one `OPEN` shift exists for a branch/register. Close uses a row lock plus `If-Match` version and calculates expected cash as opening + sales + cash-in - cash-out - refunds.
- A variance greater than VND 100,000 requires an explanation and approver.
- Command writes, audit record, outbox event and idempotency completion share one PostgreSQL transaction.
- Each successful mutation writes one audit record with the authenticated actor and the affected aggregate. Attendance check-in/out records use the attendance ID as subject and include the branch. Shift closure audit includes cash sales, cash in/out, refunds, expected cash, counted cash, variance, approver ID, and whether an explanation was supplied; it does not copy the free-text explanation into audit metadata.

There are no external network calls inside transactions and no Redis lock: database constraints and row locks cover the demonstrated contention points.

## Authorization

RS256 access tokens are verified with public keys discovered from `ORGANIZATION_JWKS_URI`. Organization caches JWKS for five minutes and refreshes immediately for an unknown `kid`. Issuer, audience, expiry, algorithm, signature, permissions, and branch scopes are checked; the service never receives Identity's private key or trusts client-supplied actor/scope headers.

Permissions used by this slice are `organization:manage_branch`, `organization:view_branch`, `organization:manage_employee`, `organization:assign_employee`, `organization:manage_schedule`, `organization:self_attendance`, `organization:record_attendance`, `organization:open_shift`, `organization:close_shift`, and `organization:approve_shift_variance`.

## Local and Docker run

Local test (Docker is required for the migration integration test):

```powershell
mvn -B test
```

Start `identity-service` first, then copy `.env.example` to an uncommitted `.env`, set the database password and the Identity JWKS URL, then:

```powershell
docker compose -f compose.yml up --build -d
docker compose -f compose.yml ps
docker compose -f compose.yml logs organization-service
```

Host defaults are PostgreSQL `5434`, service `8081`, and readiness `/actuator/health/readiness`. Removing the named volume destroys local organization data and is intentionally not part of normal shutdown.
