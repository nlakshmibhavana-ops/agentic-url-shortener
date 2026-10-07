# Design record

Architecture: layered Spring Boot service: web -> service -> repository (JDBC) -> Flyway-managed schema

## Capabilities

- **click_accuracy**: Concurrent clicks are all counted (atomic increment); Redirects are not permanently cacheable, so repeat clicks reach the service
- **expiry**: Links may carry an optional expiry in the future; Expired links return 410 Gone
- **url_safety**: Only http(s) targets are accepted; script/data/file schemes are rejected with 400; URLs with embedded credentials are rejected

## Decisions (ADRs)

### ADR-01: Atomic UPDATE ... SET clicks = clicks + 1, and 302 redirects

Fixes lost updates under concurrency and browser-cached 301s that never re-hit the service.

Alternatives considered: SELECT ... FOR UPDATE row locking (more contention)

### ADR-02: Expiry evaluated at read time; 410 Gone

No background job required; expired rows remain for audit and analytics.

Alternatives considered: Background purge job

### ADR-03: Validate targets at the boundary; never fetch them

Blocks script schemes, credential spoofing and internal hosts without network I/O.

Alternatives considered: Fetch-and-inspect targets (SSRF risk, latency)

