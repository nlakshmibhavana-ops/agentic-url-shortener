# Release 1.0.0

URL shortener service, from scratch

## Changes

- Maven project, configuration properties and application entry point
- Domain model: links, clicks, stats and API error types
- Random base62 code generator and custom-alias rules (with unit tests)
- Target-URL validation: scheme allowlist, credentials, private hosts, loops
- Token-bucket rate limiter with bounded memory; clock and click executor beans
- Flyway schema and JDBC repository for links and click events
- Link service: create/resolve/delete/stats, idempotency, expiry, ownership
- HTTP API: auth, error mapping, request ids, health/readiness, home page

## Evidence

- [x] all tests pass: 59 passed / 0 failed
- [x] line coverage >= 90.0%: 93.2%
- [x] checkstyle clean: 0 issues
- [x] no security findings: 0 findings
- [x] docs generated: openapi.json, docs/API.md, docs/DESIGN.md, CHANGELOG.md, README.md
- [x] no blocking questions open: []
