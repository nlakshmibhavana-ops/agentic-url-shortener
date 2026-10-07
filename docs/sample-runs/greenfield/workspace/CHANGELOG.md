# Changelog

## [Unreleased] - URL shortener service, from scratch

- Maven project, configuration properties and application entry point
- Domain model: links, clicks, stats and API error types
- Random base62 code generator and custom-alias rules (with unit tests)
- Target-URL validation: scheme allowlist, credentials, private hosts, loops
- Token-bucket rate limiter with bounded memory; clock and click executor beans
- Flyway schema and JDBC repository for links and click events
- Link service: create/resolve/delete/stats, idempotency, expiry, ownership
- HTTP API: auth, error mapping, request ids, health/readiness, home page
