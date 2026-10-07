# OpenAPI definition API

Generated from the running application's OpenAPI contract (`openapi.json`).

| Method | Path | Operation | Responses |
|---|---|---|---|
| POST | `/api/v1/links` | create | 200 |
| GET | `/api/v1/links/{code}` | get | 200 |
| DELETE | `/api/v1/links/{code}` | delete | 200 |
| GET | `/api/v1/links/{code}/stats` | stats | 200 |
| GET | `/healthz` | healthz | 200 |
| GET | `/readyz` | readyz | 200 |
| GET | `/{code}` | follow | 200 |
