# Notification Service

Owns templates, queued in-app notifications and delivery logs. Enqueue is idempotent, validates template variables, and writes an outbox row locally. A background worker processes queued rows with `FOR UPDATE SKIP LOCKED` and exposes a recipient inbox. This is an in-app channel only; email, SMS and push providers are not configured. Local standalone configuration and deployment are in `source/services/notification-service/.env.example` and `compose.yml`; integrated configuration is in `source/.env.example` and `source/compose-business.yml`.
