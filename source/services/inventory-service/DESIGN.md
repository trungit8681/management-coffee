# Inventory Service

Owns ingredient, branch balance and append-only stock ledger. Each receipt, deduction, or saga reversal has a mandatory idempotency key, branch authorization, an atomic balance update and an outbox row in one PostgreSQL transaction. Deduction uses `UPDATE ... WHERE quantity >= requested` so concurrent requests cannot make stock negative. `REVERSAL` is a distinct append-only ledger kind and never deletes the original deduction.
