# URL Shortener

A small, production-minded URL shortening service (Spring Boot 4, Java 21): create short links
(random or custom alias, optional expiry), redirect, and per-link click analytics.

## Run

```bash
./mvnw spring-boot:run                   # or: mvn spring-boot:run
# configuration through environment variables, e.g.
SHORTENER_API_KEYS=change-me mvn spring-boot:run
```

Open http://localhost:8000/ for a small UI. The OpenAPI contract is at `/v3/api-docs`.

| Property (env var) | Default | Meaning |
|---|---|---|
| `shortener.api-keys` (`SHORTENER_API_KEYS`) | *(none)* | Keys allowed to manage links; the API fails closed without one |
| `shortener.base-url` (`SHORTENER_BASE_URL`) | `http://localhost:8000` | Prefix for `short_url` |
| `shortener.code-length` | `7` | Random code length (62^7 ≈ 3.5e12) |
| `shortener.rate-limit-per-minute` | `60` | Creates per minute per API key |
| `shortener.allow-private-targets` | `false` | Allow private/internal targets (intranet deployments) |
| `spring.datasource.url` | H2 file `./data/shortener` | Any JDBC database Flyway supports |

## Use

```bash
curl -s -XPOST localhost:8000/api/v1/links -H 'X-API-Key: change-me' \
     -H 'Content-Type: application/json' -d '{"url":"https://example.com/very/long","alias":"demo"}'
curl -si localhost:8000/demo                   # 302 -> https://example.com/very/long
curl -s localhost:8000/api/v1/links/demo/stats -H 'X-API-Key: change-me'
```

See [docs/API.md](docs/API.md) and `openapi.json` for the full contract.

## Design notes

- **Codes** are random base62 (`SecureRandom`), not sequential, so the link space can't be
  enumerated. Collisions are retried; aliases are validated and can't shadow service routes.
- **Target validation** allows only http(s) and rejects embedded credentials, private,
  loopback or internal hosts, and links back to the shortener itself. Targets are never fetched.
- **Redirects** are `302` with `Cache-Control: private, no-store`, so every click is counted.
  A `301` would be cached by browsers and under-count.
- **Analytics** are recorded off the request thread (virtual threads). They store the referrer
  *host* and a coarse user-agent family only, with no IPs and no full referrer URLs.
- **Ownership**: links belong to the hashed API key that created them. Other keys get `404`,
  not `403`, so codes can't be probed.
- **Reliability**: `Idempotency-Key` makes create safe to retry. A token-bucket rate limit
  returns `429` + `Retry-After`. `/healthz` is liveness, `/readyz` checks the database. Every
  response carries `X-Request-ID`, and there is one JSON access-log line per request.
- **Schema** is owned by Flyway. Released migrations are immutable; changes are new `V<n>__` files.

## Test

```bash
mvn verify          # unit + integration tests, JaCoCo report in target/site/jacoco
```
