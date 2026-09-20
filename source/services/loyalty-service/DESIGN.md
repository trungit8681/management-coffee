# Loyalty Service

Owns customer wallets, reservations and append-only point ledger. Balance updates use conditional SQL to prevent overspending; one reservation per order and row-locked finalization prevent duplicate redemption. Credits require elevated `loyalty:adjust_points`. Point earning from a verified completed order, expiry and customer self-service scope are not yet implemented. Local standalone configuration and deployment are in `source/services/loyalty-service/.env.example` and `compose.yml`; integrated configuration is in `source/.env.example` and `source/compose-business.yml`.
