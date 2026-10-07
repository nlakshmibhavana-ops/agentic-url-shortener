# Release 1.1.0

Product ask: safer links, marketing analytics, fast redirects

## Changes

- Q-PERF: bounded TTL read-through cache on the redirect path (p95 < 50 ms)
- Q-SAFETY: configurable domain denylist (incl. subdomains)
- Q-PRIVACY=hashed: unique visitors via daily-rotating HMAC; raw IPs never stored

## Evidence

- [x] all tests pass: 76 passed / 0 failed
- [x] line coverage >= 90.0%: 93.9%
- [x] checkstyle clean: 0 issues
- [x] no security findings: 0 findings
- [x] docs generated: openapi.json, docs/API.md, docs/DESIGN.md, CHANGELOG.md
- [x] no blocking questions open: []
- [x] every requirement clause is covered, a constraint, or explicitly descoped: 2 clauses
- [x] every acceptance criterion is proven: linked code + a passing tagged test: 13 criteria, all proven

## Known risks / follow-ups

- assumption: Q-PERF: assumed 'p95 < 50 ms in-process (read-through cache, no new infrastructure)' (non-blocking; confirm or override)
- assumption: Q-SAFETY: assumed 'Add a configurable domain denylist for known-bad domains' (non-blocking; confirm or override)
