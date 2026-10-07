# Scenarios

All three are reproducible with `./scripts/demo.sh`. The numbers below come from the committed
sample runs in [sample-runs/](sample-runs/) (`report.md`, `metrics.json`). Times are wall clock on
a 16-core laptop; "active" time excludes time spent waiting for a human. Every gate is a real
Maven build of a real Spring Boot project, which is where most of the time goes.

| | Greenfield | Brownfield | Ambiguous |
|---|---|---|---|
| Input | numbered, testable spec | three tickets against a legacy codebase | one vague sentence plus "fast" |
| Planned tasks | 8 | 4 | 2 → 3 after clarification |
| Nodes succeeded | 16 | 13 | 12 (1 checkpoint superseded) |
| Retries / rollbacks | 0 / 0 | 1 / 1 | 0 / 0 |
| MTTR | - | 16 s | - |
| Re-plans / reused nodes | 0 / 0 | 0 / 0 | 1 / 2 |
| Human actions | 1 approval | 2 approvals | 1 answer + 2 approvals |
| Product tests (line coverage) | 59 (93.2%) | 11 (96.1%) | 74 (93.9%) |
| Active time | 137 s | 143 s | 128 s |
| Release | 1.0.0 | 0.4.2 → 0.5.0 | 1.0.0 → 1.1.0 |

---

## 1. Greenfield: URL shortener from a well-defined spec

**Input** (`scenarios/greenfield.yaml`): versioned REST API, API-key auth, custom aliases, expiry,
302/404/410 semantics, owner-scoped analytics with no personal data, unsafe-target rejection,
60/min rate limit, idempotent create, health and readiness probes, request IDs.

**Requirement understanding.** Eight capabilities, each with the sentence that evidences it and
its acceptance criteria. **Zero ambiguity questions**: "60 per minute" is measurable, and "store
no IP addresses" pre-answers the privacy question the ambiguous scenario has to ask.

**Decomposition** (8 tasks). Java compiles a module as one unit, so each task delivers its classes
together with their tests:

| Task | Depends on | Verified by |
|---|---|---|
| `scaffold` (pom, properties, entry point) | - | compile + Checkstyle |
| `domain` (records, error types) | scaffold | compile + Checkstyle |
| `impl.codes`, `impl.validation`, `impl.ratelimit` | domain | each class's unit tests |
| `impl.storage` (Flyway V1, JDBC repository) | domain | compile + Checkstyle |
| `impl.service` | storage, codes, validation, ratelimit | `LinkServiceTest` (Spring Boot, H2) |
| `impl.api` (controllers, filter, error mapping, home page) | service | `ApiIntegrationTest` (real HTTP), `OpenApiExportTest` |

**Orchestration.** `codes`, `validation`, `ratelimit` and `storage` are **parallel branches**
(their agents run concurrently; their builds queue on the workspace lock). `service` and `api`
are synchronisation points; `test`, `review` and `docs` run as the join; `readiness` gathers the
evidence; `release` is the human-approved diamond.

**Validation.** Per task: one Maven build (compile, Checkstyle, the task's tests). At the join:
the full suite with JaCoCo (59 passed, 93.2% line coverage), project-wide Checkstyle, a security
scan on the syntax tree, the OpenAPI contract fetched from **the packaged JAR running on a free
port** (every designed endpoint must exist), and docs completeness. Readiness checks six criteria;
release smoke-tests the packaged JAR (health → create → redirect → stats) before asking for approval.

**Output:** a Spring Boot 4 Maven project (config, domain, service, JDBC repository, Flyway
migration, web layer, home page), 59 tests, `openapi.json`, `docs/API.md`, `docs/DESIGN.md`
(seven ADRs), README, CHANGELOG, RELEASE_NOTES and VERSION.

---

## 2. Brownfield: linkly v0.4 → v0.5

**Input** (`scenarios/brownfield.yaml`): LINK-142 (P1: `javascript:` and `data:` URLs become
stored XSS), LINK-151 (click counts under-reported), LINK-150 (optional expiry, 410 Gone), and a
constraint: existing clients and the live v0.4 database must keep working.

**Requirement understanding.** Three capabilities. "POST /shorten" (in the security ticket) and
"click counts" (in the bug) are correctly *not* treated as requests for new link creation or
analytics: specific capabilities refine generic ones.

**Codebase reasoning** (`analyze`, JavaParser over the existing sources):

* class dependency graph `LinkController → LinkStore`, blast radius `{LinkController, LinkStore}`,
  data at risk `urls`
* routes per capability, e.g. `url_safety → POST /shorten`, `expiry → GET /r/{code}, GET /info/{code}`
* regression test `LinkApiTest`, found because it is a `@SpringBootTest` black-box test, which
  exercises the app through its controllers
* latent defects found before any test ran:
  * `LinkStore.hit` reads then writes `clicks`: a **lost update**, the real cause of LINK-151
  * `LinkController.go` returns a cacheable **301**: the second cause; browsers never re-hit the service
  * `LinkStore.create` derives **enumerable codes** from generated row ids: out of scope, recorded
    as a decision and carried into the release notes as a follow-up risk

**Decomposition:** `tests.regression` (test-first) → `impl.url_validation` → `impl.click_accuracy`
→ `impl.expiry`. The regression tests are **black-box** (HTTP and a Flyway migration test), so they
compile against the legacy code and fail at runtime until each fix lands; against the untouched
legacy app all eight fail, including the concurrent-click test, which reproduces the lost update.
`click_accuracy` is serialised after `url_validation` because both edit `LinkController`.

**Orchestration: failure, rollback, retry, approval.**

1. `impl.expiry`, candidate 1, adds `expires_at` in a new Flyway migration **and backfills existing
   links with a default 30-day expiry**. Every fresh-database test passes, but
   `MigrationTest.upgradesAnExistingDatabaseWithLiveRows` (a v0.4 database with a real row) fails:
   the old link would silently die after 30 days. The change is **rolled back** and the failure
   becomes the next attempt's feedback.
2. Candidate 2 adds the column as nullable, with no backfill, and passes all gates. `DATA-001` (a
   new migration against an existing database) then requires **human approval**; the approver sees
   the digest and the passing build evidence.
3. On `resume`, the gates are re-checked, then the change is committed.
4. `release` bumps `pom.xml` to 0.5.0 (`CHG-002`, protected file), smoke-tests the packaged JAR
   (including "a `javascript:` URL is rejected with 400"), is approved, and ships.

Metrics: 1 retry, 1 rollback (rate 1/7), MTTR 16 s. The defective attempt never reached a human.

---

## 3. Ambiguous: "Make short links safer and add some analytics so marketing can see how links perform. Redirects should also be fast."

The base is the released v1 service: an existing system with a live database.

**Ambiguity becomes explicit questions:**

| Id | Trigger | Blocking? | Options | Before the answer |
|---|---|---|---|---|
| Q-PRIVACY | "analytics … how links perform" | **yes**: personal data is never assumed | hashed / raw / none | open, dependent work gated |
| Q-PERF | "fast" (no number) | no | p95 < 50 ms in-process / p95 < 10 ms (CDN) | assumed p95 < 50 ms |
| Q-SAFETY | "safer" | no | baseline / denylist / reputation API | assumed denylist |

The assumptions appear in the report, the design record and the **release notes**. `url_safety`,
`analytics` and `redirect` are recognised as **already implemented** ("No new work for …").

**Orchestration: partial progress, pause, re-plan.**

* **Plan v1:** `perf.redirect_cache` → `safety.domain_denylist` (serialised: both edit
  `ShortenerProperties` and `LinkService`) and a checkpoint `clarify.Q-PRIVACY` with the human
  gate `clarified:Q-PRIVACY`.
* Session 1: both unblocked tasks are implemented and verified, including a p95 < 50 ms redirect
  performance test. Then the run **pauses** on the question.
* `answer Q-PRIVACY hashed`: `human_answers v2` invalidates `requirements`, which cascades to
  `analyze`, `design` and `plan`.
* **Plan v2** (`plan.revised`): added `analytics.unique_visitors`, removed `clarify.Q-PRIVACY`,
  **reused `perf.redirect_cache` and `safety.domain_denylist` without re-running them**.
* `analytics.unique_visitors` (HMAC-SHA256 of day, IP and user agent with a daily-rotating key;
  the raw IP never leaves the method) passes its gates, including a test that dumps the click rows
  and asserts no IP appears, then waits for **approval** under `DATA-001` (Flyway V2 on the live
  database).
* After approval the join stages re-run on the combined change: 74 tests at 93.9%, docs
  regenerated from the running JAR, 1.1.0 approved.

**If the human answers "raw"**, the plan selects the raw-IP task and **PII-001** blocks its
`client_ip VARCHAR` column on every attempt: the node fails, the run safe-stops, the release is
blocked, and no migration reaches the workspace (`EngineScenarioTest.choosingRawIpStorageIsStoppedByThePrivacyGuardrail`).
A human can't approve their way past a block rule.

---

## 4. The brownfield scenario with a live local model

`./agentflow run brownfield --provider ollama`, with `qwen2.5-coder:7b` served by Ollama on a
16-core laptop CPU (about 13 tokens/s). Evidence: [sample-runs/llm-brownfield](sample-runs/llm-brownfield/report.md).

| Step | What the model produced | What the governance did |
|---|---|---|
| Requirements | `url_safety`, `expiry` (27 s) | accepted after validation against the vocabulary; `click_accuracy` added by the deterministic rules |
| `impl.url_validation` | a validator using classes it never imported | build gate: **compile failed** (`cannot find symbol`) → rolled back → fallback passed |
| `impl.click_accuracy` | the atomic `clicks = clicks + 1` fix, correctly, but it kept the cacheable **301** | build gate: `redirectIsNotPermanentlyCacheable` failed → rolled back → fallback passed |
| `impl.expiry` | generated without closing its JSON answer | stopped by the **output-token cap** after about 5 minutes ("output truncated"); nothing was applied → fallback passed, then human approval of the migration |

Result: released 0.5.0, 11 tests, audit chain intact. 6,485 tokens, 3 retries, 2 rollbacks,
MTTR 16 s, attempt success rate 0.79 (1.0 deterministic). Model time was about 10 minutes; the
recorded active time is longer because the laptop running the orchestrator was suspended for
about 13 minutes mid-run (the span durations, measured on the monotonic clock, exclude it).

Runs vary. In an earlier recording the expiry change was returned in about 3 minutes but did not
compile; in this one the model ran away. The token cap was added after diagnosing that risk, and
caught it here on its first real occurrence.

What it shows: a 7B local model analyses requirements well and gets parts of the fixes right (the
atomic increment), but its multi-file Java changes don't yet survive a real build. The
orchestrator makes that safe: every model attempt was compiled and tested in the sandbox, rolled
back or stopped when wrong, and preserved in `changes/` and the audit trail; nothing unverified
reached the release. First-attempt success per model is now a measurable property to track as
models improve.
