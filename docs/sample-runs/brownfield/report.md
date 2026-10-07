# Run demo-brownfield-sample

**Scenario:** brownfield: linkly v0.4 -> v0.5: security fix, click accuracy fix, link expiry  
**Status:** `succeeded`  
**Provider:** deterministic · **Playbook:** linkly

## 1. Requirement

```text
LINK-142 (security, P1): Pen test found that POST /shorten accepts javascript: and data: URLs, which become stored XSS when the short link is opened. Reject unsafe URLs with 400.
LINK-151 (bug): Marketing reports click counts are under-reported compared with their campaign tool, especially during launches.
LINK-150 (feature): Allow an optional expiry in days when creating a link; expired links must return 410 Gone.
Constraints: existing clients and the production database (v0.4 schema, live data) must keep working.
```

## 2. Requirement understanding

| Capability | Evidence in request | Acceptance criteria |
|---|---|---|
| click_accuracy | LINK-151 (bug): Marketing reports click counts are under-reported compared with their campaign tool, especially during launches. | Concurrent clicks are all counted (atomic increment)<br>Redirects are not permanently cacheable, so repeat clicks reach the service |
| expiry | LINK-150 (feature): Allow an optional expiry in days when creating a link; expired links must return 410 Gone. | Links may carry an optional expiry in the future<br>Expired links return 410 Gone |
| url_safety | LINK-142 (security, P1): Pen test found that POST /shorten accepts javascript: and data: URLs, which become stored XSS when the short link is opened. | Only http(s) targets are accepted; script/data/file schemes are rejected with 400<br>URLs with embedded credentials are rejected |

**Requirement coverage** (every clause of the request has a disposition)

| Clause | Text | Disposition | Covered by |
|---|---|---|---|
| C1 | LINK-142 (security, P1): Pen test found that POST /shorten accepts javascript: and data: URLs, which become stored XSS when the short link is opened. | supported | url_safety |
| C2 | Reject unsafe URLs with 400. | supported | url_safety |
| C3 | LINK-151 (bug): Marketing reports click counts are under-reported compared with their campaign tool, especially during launches. | supported | click_accuracy |
| C4 | LINK-150 (feature): Allow an optional expiry in days when creating a link | supported | expiry |
| C5 | expired links must return 410 Gone. | supported | expiry |
| C6 | Constraints: existing clients and the production database (v0.4 schema, live data) must keep working. | constraint |  |

## 3. Codebase reasoning (brownfield)

- Class dependency graph: `{"com.example.linkly.LinkController":["com.example.linkly.LinkStore"],"com.example.linkly.LinkStore":[],"com.example.linkly.LinklyApplication":[]}`
- Blast radius: ["com.example.linkly.LinkController","com.example.linkly.LinkStore"]
- Data at risk: ["urls"] (schema tables: ["urls"])
- Regression tests selected: ["src/test/java/com/example/linkly/LinkApiTest.java"]

| Capability | Classes | Routes | Tables |
|---|---|---|---|
| click_accuracy | ["com.example.linkly.LinkController","com.example.linkly.LinkStore"] | ["GET /r/{code}"] | ["urls"] |
| expiry | ["com.example.linkly.LinkController","com.example.linkly.LinkStore"] | ["GET /info/{code}","GET /r/{code}"] | ["urls"] |
| url_safety | ["com.example.linkly.LinkController","com.example.linkly.LinkStore"] | ["POST /shorten"] | ["urls"] |

**Latent defects found by static analysis**

- `com.example.linkly.LinkController.go`: cacheable-redirect: 301 is cached by browsers; repeat clicks never reach the server
- `com.example.linkly.LinkStore.create`: enumerable-ids: codes derived from sequential row ids can be enumerated
- `com.example.linkly.LinkStore.hit`: lost-update: reads then writes 'clicks' non-atomically

## 4. Design

Style: layered Spring Boot service: web -> service -> repository (JDBC) -> Flyway-managed schema

| ADR | Decision | Rationale | Alternatives |
|---|---|---|---|
| ADR-01 | Atomic UPDATE ... SET clicks = clicks + 1, and 302 redirects | Fixes lost updates under concurrency and browser-cached 301s that never re-hit the service. | SELECT ... FOR UPDATE row locking (more contention) |
| ADR-02 | Expiry evaluated at read time; 410 Gone | No background job required; expired rows remain for audit and analytics. | Background purge job |
| ADR-03 | Validate targets at the boundary; never fetch them | Blocks script schemes, credential spoofing and internal hosts without network I/O. | Fetch-and-inspect targets (SSRF risk, latency) |

## 5. Plan (v1) and decomposition

| Task | Agent | Depends on | Writes | Verified by | Declared impact |
|---|---|---|---|---|---|
| tests.regression | test_author | - | UrlSafetyTest.java, ClickAccuracyTest.java, ExpiryTest.java, MigrationTest.java | - | low |
| impl.url_validation | implement | tests.regression | UrlValidator.java, LinkController.java | LinkApiTest.java, UrlSafetyTest.java | low |
| impl.click_accuracy | implement | impl.url_validation, tests.regression | LinkStore.java, LinkController.java | ClickAccuracyTest.java, LinkApiTest.java | low |
| impl.expiry | implement | impl.click_accuracy, impl.url_validation, tests.regression | src/main/resources/db/migration/V2__add_expires_at.sql, LinkStore.java, LinkController.java | ExpiryTest.java, LinkApiTest.java, MigrationTest.java | low |

**Traceability (capability → tasks)**

- click_accuracy: ["tests.regression","impl.click_accuracy"]
- expiry: ["tests.regression","impl.expiry"]
- url_safety: ["tests.regression","impl.url_validation"]

## 6. Orchestration graph

```mermaid
flowchart TD
  requirements["requirements<br/><small>succeeded</small>"]:::ok
  analyze["analyze<br/><small>succeeded</small>"]:::ok
  requirements --> analyze
  design["design<br/><small>succeeded</small>"]:::ok
  analyze --> design
  plan["plan<br/><small>succeeded</small>"]:::ok
  design --> plan
  tests_regression["tests.regression<br/><small>succeeded</small>"]:::ok
  plan --> tests_regression
  impl_url_validation["impl.url_validation<br/><small>succeeded</small>"]:::ok
  plan --> impl_url_validation
  tests_regression --> impl_url_validation
  impl_click_accuracy["impl.click_accuracy<br/><small>succeeded</small>"]:::ok
  plan --> impl_click_accuracy
  impl_url_validation --> impl_click_accuracy
  tests_regression --> impl_click_accuracy
  impl_expiry["impl.expiry<br/><small>succeeded</small>"]:::ok
  plan --> impl_expiry
  impl_click_accuracy --> impl_expiry
  impl_url_validation --> impl_expiry
  tests_regression --> impl_expiry
  docs["docs<br/><small>succeeded</small>"]:::ok
  plan --> docs
  impl_click_accuracy --> docs
  impl_expiry --> docs
  impl_url_validation --> docs
  tests_regression --> docs
  review["review<br/><small>succeeded</small>"]:::ok
  plan --> review
  impl_click_accuracy --> review
  impl_expiry --> review
  impl_url_validation --> review
  tests_regression --> review
  test["test<br/><small>succeeded</small>"]:::ok
  plan --> test
  impl_click_accuracy --> test
  impl_expiry --> test
  impl_url_validation --> test
  tests_regression --> test
  readiness["readiness<br/><small>succeeded</small>"]:::ok
  test --> readiness
  review --> readiness
  docs --> readiness
  release{"release<br/><small>succeeded</small>"}:::ok
  readiness --> release
  classDef ok fill:#d4edda,stroke:#2e7d32
  classDef bad fill:#f8d7da,stroke:#c62828
  classDef wait fill:#fff3cd,stroke:#f9a825
  classDef skip fill:#eeeeee,stroke:#9e9e9e
```

Rectangles are stages and tasks; diamonds are high-impact nodes that require human approval.

## 7. Execution

| Node | Kind | Status | Attempts | Runs | Origin | Started | Finished | Note |
|---|---|---|---|---|---|---|---|---|
| requirements | stage | succeeded | 1 | 1 | scenario | 02:37:39 | 02:37:39 |  |
| analyze | stage | succeeded | 1 | 1 | scenario | 02:37:39 | 02:37:39 |  |
| design | stage | succeeded | 1 | 1 | scenario | 02:37:39 | 02:37:39 |  |
| plan | stage | succeeded | 1 | 1 | scenario | 02:37:40 | 02:37:40 |  |
| tests.regression | task | succeeded | 1 | 1 | plan:v1 | 02:37:40 | 02:37:47 |  |
| impl.url_validation | task | succeeded | 1 | 1 | plan:v1 | 02:37:47 | 02:38:01 |  |
| impl.click_accuracy | task | succeeded | 1 | 1 | plan:v1 | 02:38:01 | 02:38:15 |  |
| impl.expiry | task | succeeded | 2 | 1 | plan:v1 | 02:38:15 | 02:38:59 |  |
| docs | stage | succeeded | 1 | 1 | scenario | 02:38:59 | 02:39:31 |  |
| review | stage | succeeded | 1 | 1 | scenario | 02:38:59 | 02:39:31 |  |
| test | stage | succeeded | 1 | 1 | scenario | 02:38:59 | 02:39:31 |  |
| readiness | stage | succeeded | 1 | 1 | scenario | 02:39:31 | 02:39:31 |  |
| release | stage | succeeded | 1 | 1 | scenario | 02:39:31 | 02:39:53 |  |

**Timeline (audit trail excerpts)**

- `02:37:39` **run.session.start**  {"fencing":1,"owner":"host-3da02335:544885:f7ab16c2","sandbox":"bwrap (fs + env + network isolation)","status":"created"}
- `02:37:39` **attempt.start** requirements {"agent":"requirements","attempt":1}
- `02:37:39` **agent.note** requirements {"note":"3 capabilities, 0 ambiguities, 0 blocking"}
- `02:37:39` **attempt.start** analyze {"agent":"codebase","attempt":1}
- `02:37:39` **agent.note** analyze {"note":"blast radius [LinkController, LinkStore], 3 latent defects, regression tests [LinkApiTest]"}
- `02:37:39` **attempt.start** design {"agent":"design","attempt":1}
- `02:37:40` **attempt.start** plan {"agent":"planner","attempt":1}
- `02:37:40` **agent.note** plan {"note":"4 tasks; waiting on []"}
- `02:37:40` **plan.created** plan {"added":["tests.regression","impl.url_validation","impl.click_accuracy","impl.expiry"],"changed":[],"plan_version":1,"removed":[],"reused":[]}
- `02:37:40` **attempt.start** tests.regression {"agent":"test_author","attempt":1}
- `02:37:40` **agent.note** tests.regression {"note":"candidate 1/1: Black-box regression tests for LINK-142/150/151, written before any fix (test-first)"}
- `02:37:40` **change.applied** tests.regression {"added":194,"candidate":0,"digest":"2c26ead2e0faff74","paths":["src/test/java/com/example/linkly/ClickAccuracyTest.java","src/test/java/com/example/linkly/ExpiryTest.java","src/test/java/com/example/linkly/MigrationTest
- `02:37:47` **attempt.start** impl.url_validation {"agent":"implement","attempt":1}
- `02:37:47` **agent.note** impl.url_validation {"note":"candidate 1/1: LINK-142: reject script/data/credential URLs at the API boundary (new validator)"}
- `02:37:47` **change.applied** impl.url_validation {"added":61,"candidate":0,"digest":"5738251bb575b2f6","paths":["src/main/java/com/example/linkly/LinkController.java","src/main/java/com/example/linkly/UrlValidator.java"],"removed":1,"summary":"LINK-142: reject script/d
- `02:38:01` **attempt.start** impl.click_accuracy {"agent":"implement","attempt":1}
- `02:38:01` **agent.note** impl.click_accuracy {"note":"candidate 1/1: LINK-151: atomic click increment; 302 + no-store so browsers stop caching redirects"}
- `02:38:01` **change.applied** impl.click_accuracy {"added":5,"candidate":0,"digest":"596f0d816e1eb9af","paths":["src/main/java/com/example/linkly/LinkController.java","src/main/java/com/example/linkly/LinkStore.java"],"removed":3,"summary":"LINK-151: atomic click increm
- `02:38:15` **attempt.start** impl.expiry {"agent":"implement","attempt":1}
- `02:38:15` **agent.note** impl.expiry {"note":"candidate 1/2: Add expires_at and backfill existing links with a default 30-day expiry"}
- `02:38:15` **change.applied** impl.expiry {"added":34,"candidate":0,"digest":"d8cd7b434d189a97","paths":["src/main/java/com/example/linkly/LinkController.java","src/main/java/com/example/linkly/LinkStore.java","src/main/resources/db/migration/V2__add_expires_at.
- `02:38:30` **change.rolled_back** impl.expiry {"paths":["src/main/java/com/example/linkly/LinkController.java","src/main/java/com/example/linkly/LinkStore.java","src/main/resources/db/migration/V2__add_expires_at.sql"]}
- `02:38:30` **attempt.retry** impl.expiry {"next_attempt":2,"reason":"build: tests failed: Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.497 s <<< FAILURE! -- in com.example.linkly.MigrationTest | com.example.linkly.MigrationTest.upgradesAnEx
- `02:38:30` **attempt.start** impl.expiry {"agent":"implement","attempt":2}
- `02:38:30` **agent.note** impl.expiry {"note":"candidate 2/2: Add a nullable expires_at column (V2 migration), so existing rows stay valid (selected as the reviewed repair for diagnosed tests failure in [MigrationTest.upgradesAnExistingDatabaseWithLiveRows])
- `02:38:30` **change.applied** impl.expiry {"added":33,"candidate":1,"digest":"823c3370822eba74","paths":["src/main/java/com/example/linkly/LinkController.java","src/main/java/com/example/linkly/LinkStore.java","src/main/resources/db/migration/V2__add_expires_at.
- `02:38:44` **approval.requested** impl.expiry {"evidence":{"change_set":"823c3370822eba74","gates":["build=pass"],"plan":"29afa085215444c5@v1","policy_findings":"beb35d8ab8e37780","requirements":"b4c57c2dfc9b4581@v1","workspace_tree":"cc8901f50d4c2197 (15 files)"},"
- `02:38:44` **node.waiting** impl.expiry {"reason":"approval required: DATA-001 src/main/resources/db/migration/V2__add_expires_at.sql: schema change against an existing database"}
- `02:38:44` **run.session.end**  {"status":"waiting","stop_reason":null,"waiting":["impl.expiry"]}
- `02:38:45` **approval.decided** impl.expiry {"authenticated":"token","by":"reviewer","comment":"additive nullable column; v0.4 upgrade test passes","decided_at":1.791340725851E9,"role":"data","status":"approved","subject":"07b3d067b60d5601","waited_s":1.1}
- `02:38:46` **run.session.start**  {"fencing":2,"owner":"host-3da02335:546743:5e3301db","sandbox":"bwrap (fs + env + network isolation)","status":"waiting"}
- `02:38:59` **attempt.start** review {"agent":"reviewer","attempt":1}
- `02:38:59` **attempt.start** docs {"agent":"docs","attempt":1}
- `02:38:59` **attempt.start** test {"agent":"test_runner","attempt":1}
- `02:39:03` **agent.note** review {"note":"0 checkstyle, 0 security, 1 latent defects (reported, non-blocking)"}
- `02:39:21` **agent.note** test {"note":"11 passed, 0 failed, line coverage 96.1%"}
- `02:39:31` **change.applied** docs {"added":159,"candidate":0,"digest":"597f8a7b09c8063a","paths":["CHANGELOG.md","docs/API.md","docs/DESIGN.md","openapi.json"],"removed":0,"summary":"Generate OpenAPI contract, API reference, design record, changelog"}
- `02:39:31` **attempt.start** readiness {"agent":"release_readiness","attempt":1}
- `02:39:31` **agent.note** readiness {"note":"ready=true, 1 residual risks"}
- `02:39:31` **attempt.start** release {"agent":"release","attempt":1}
- `02:39:31` **change.applied** release {"added":29,"candidate":0,"digest":"522a88048e618e5f","paths":["RELEASE_NOTES.md","VERSION","pom.xml"],"removed":1,"summary":"Release 0.5.0"}
- `02:39:41` **approval.requested** release {"evidence":{"change_set":"522a88048e618e5f","docs":"badcb403f84a6dc2@v1","gates":["smoke=pass"],"plan":"29afa085215444c5@v1","policy_findings":"b8793542bad40468","release_readiness":"364c5be919d130b9@v1","requirements":
- `02:39:41` **node.waiting** release {"reason":"approval required: high-impact stage 'release'; CHG-002 pom.xml: modifies a protected file"}
- `02:39:41` **run.session.end**  {"status":"waiting","stop_reason":null,"waiting":["release"]}
- `02:39:42` **approval.decided** release {"authenticated":"token","by":"reviewer","comment":"","decided_at":1.791340782537E9,"role":"release","status":"approved","subject":"6145476ca309d876","waited_s":1.4}
- `02:39:43` **run.session.start**  {"fencing":3,"owner":"host-3da02335:548385:d5cb591a","sandbox":"bwrap (fs + env + network isolation)","status":"waiting"}
- `02:39:53` **run.session.end**  {"status":"succeeded","stop_reason":null,"waiting":[]}

## 8. Validation gates

| Node | Type | Gate | Result | Detail |
|---|---|---|---|---|
| requirements | exit | requirements_valid | pass | 3 capabilities, all with acceptance criteria |
| design | exit | design_covers_requirements | pass | every capability has a design element |
| plan | exit | plan_valid | pass | 4 tasks; every capability traced to at least one task |
| tests.regression | exit | build | pass | compiled, checkstyle clean; sandbox bwrap |
| impl.url_validation | exit | build | pass | compiled, checkstyle clean, executed: LinkApiTest 3/3, UrlSafetyTest 1/1; sandbox bwrap |
| impl.click_accuracy | exit | build | pass | compiled, checkstyle clean, executed: ClickAccuracyTest 2/2, LinkApiTest 3/3; sandbox bwrap |
| impl.expiry | exit | build | FAIL | tests failed: Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.497 s <<< FAILURE! -- in com.example.linkly.MigrationTest \| com.exampl |
| impl.expiry | exit | build | pass | compiled, checkstyle clean, executed: ExpiryTest 4/4, LinkApiTest 3/3, MigrationTest 1/1; sandbox bwrap |
| impl.expiry | exit | build | pass | compiled, checkstyle clean, executed: ExpiryTest 4/4, LinkApiTest 3/3, MigrationTest 1/1; sandbox bwrap |
| review | exit | lint_clean | pass | no checkstyle violations |
| review | exit | security_clean | pass | no security findings |
| test | exit | tests_pass | pass | 11 passed, 0 failed, 0 errors |
| test | exit | coverage_min | pass | line coverage 96.1% (min 85.0%) |
| docs | exit | openapi_valid | pass | 3 operations; all designed endpoints present |
| docs | exit | docs_complete | pass | 4 docs written |
| readiness | exit | readiness | pass | all release checks pass |
| release | exit | smoke | pass | packaged JAR started; POST /shorten -> 200; GET /r/1 -> 302; POST /shorten -> 400; GET /info/1 -> 200 |
| release | exit | smoke | pass | packaged JAR started; POST /shorten -> 200; GET /r/X -> 302; POST /shorten -> 400; GET /info/X -> 200 |

## 9. Policy guardrails

| Node | Rule | Severity | Path | Message |
|---|---|---|---|---|
| impl.expiry | DATA-001 | approve | src/main/resources/db/migration/V2__add_expires_at.sql | schema change against an existing database |
| impl.expiry | DATA-001 | approve | src/main/resources/db/migration/V2__add_expires_at.sql | schema change against an existing database |
| release | CHG-002 | approve | pom.xml | modifies a protected file |

## 10. Human approvals

| Node | Subject | Reasons | Decision | By | Waited (s) |
|---|---|---|---|---|---|
| impl.expiry | 07b3d067b60d5601 | DATA-001 src/main/resources/db/migration/V2__add_expires_at.sql: schema change against an existing database | approved | reviewer | 1.1 |
| release | 6145476ca309d876 | high-impact stage 'release'<br>CHG-002 pom.xml: modifies a protected file | approved | reviewer | 1.4 |

## 11. Decision log (lineage)

| Id | Node | By | Decision | Rationale | Inputs |
|---|---|---|---|---|---|
| D001 | analyze | agent | Sequential/enumerable codes noted but left out of scope | Changing code generation alters every future URL format and is not requested by the tickets; raised as a follow-up risk instead. | {requirements=1} |
| D002 | design | agent | Atomic UPDATE ... SET clicks = clicks + 1, and 302 redirects | Fixes lost updates under concurrency and browser-cached 301s that never re-hit the service. | {impact_analysis=1, requirements=1} |
| D003 | design | agent | Expiry evaluated at read time; 410 Gone | No background job required; expired rows remain for audit and analytics. | {impact_analysis=1, requirements=1} |
| D004 | design | agent | Validate targets at the boundary; never fetch them | Blocks script schemes, credential spoofing and internal hosts without network I/O. | {impact_analysis=1, requirements=1} |
| D005 | plan | agent | Serialise impl.click_accuracy after impl.url_validation | Both change [LinkController.java]; running them in parallel could produce conflicting edits, so they are ordered like a merge queue. | {design=1, impact_analysis=1, requirements=1} |

### Feature-completion proof (criterion → code → test)

| Criterion | Text | Code | Passing tests | Status |
|---|---|---|---|---|
| AC-click_accuracy-1 | Concurrent clicks are all counted (atomic increment) | src/main/java/com/example/linkly/LinkController.java<br>src/main/java/com/example/linkly/LinkStore.java | ClickAccuracyTest.concurrentClicksAreNotLost | proven |
| AC-click_accuracy-2 | Redirects are not permanently cacheable, so repeat clicks reach the service | src/main/java/com/example/linkly/LinkController.java<br>src/main/java/com/example/linkly/LinkStore.java | ClickAccuracyTest.redirectIsNotPermanentlyCacheable | proven |
| AC-expiry-1 | Links may carry an optional expiry in the future | src/main/java/com/example/linkly/LinkController.java<br>src/main/java/com/example/linkly/LinkStore.java<br>src/main/resources/db/migration/V2__add_expires_at.sql | ExpiryTest.expiringLinkReportsItsExpiry<br>ExpiryTest.linkWithoutExpiryStillWorks | proven |
| AC-expiry-2 | Expired links return 410 Gone | src/main/java/com/example/linkly/LinkController.java<br>src/main/java/com/example/linkly/LinkStore.java<br>src/main/resources/db/migration/V2__add_expires_at.sql | ExpiryTest.expiredLinkIsGone | proven |
| AC-url_safety-1 | Only http(s) targets are accepted; script/data/file schemes are rejected with 400 | src/main/java/com/example/linkly/LinkController.java<br>src/main/java/com/example/linkly/UrlValidator.java | UrlSafetyTest.unsafeUrlsAreRejectedAndNothingIsStored | proven |
| AC-url_safety-2 | URLs with embedded credentials are rejected | src/main/java/com/example/linkly/LinkController.java<br>src/main/java/com/example/linkly/UrlValidator.java | UrlSafetyTest.unsafeUrlsAreRejectedAndNothingIsStored | proven |

### test_report

```json
{
  "coverage" : 96.1,
  "errors" : 0,
  "failed" : 0,
  "failures" : [ ],
  "ok" : true,
  "passed" : 11,
  "skipped" : 0,
  "total" : 11
}
```

### review_report

```json
{
  "latent_defects" : [ {
    "detail" : "codes derived from sequential row ids can be enumerated",
    "function" : "create",
    "kind" : "enumerable-ids",
    "module" : "com.example.linkly.LinkStore"
  } ],
  "lint" : [ ],
  "security" : [ ]
}
```

### release_readiness

```json
{
  "checks" : [ {
    "check" : "all tests pass",
    "detail" : "11 passed / 0 failed",
    "passed" : true
  }, {
    "check" : "line coverage >= 85.0%",
    "detail" : "96.1%",
    "passed" : true
  }, {
    "check" : "checkstyle clean",
    "detail" : "0 issues",
    "passed" : true
  }, {
    "check" : "no security findings",
    "detail" : "0 findings",
    "passed" : true
  }, {
    "check" : "docs generated",
    "detail" : "openapi.json, docs/API.md, docs/DESIGN.md, CHANGELOG.md",
    "passed" : true
  }, {
    "check" : "no blocking questions open",
    "detail" : "[]",
    "passed" : true
  }, {
    "check" : "every requirement clause is covered, a constraint, or explicitly descoped",
    "detail" : "6 clauses",
    "passed" : true
  }, {
    "check" : "every acceptance criterion is proven: linked code + a passing tagged test",
    "detail" : "6 criteria, all proven",
    "passed" : true
  } ],
  "ready" : true,
  "residual_risks" : [ "LinkStore.create: codes derived from sequential row ids can be enumerated" ]
}
```

## 12. Artifact lineage

Provenance of `release`:

- release v1 ← release
  - plan v1 ← plan
    - design v1 ← design
      - impact_analysis v1 ← analyze
        - requirements v1 ← requirements
          - human_answers v1 ← scenario
          - requirement_text v1 ← scenario
  - release_readiness v1 ← readiness
    - docs v1 ← docs
    - review_report v1 ← review
    - test_report v1 ← test

## 13. Reliability metrics

| Metric | Value |
|---|---|
| nodes_succeeded | 13 |
| nodes_failed | 0 |
| node_success_rate | 1.0 |
| attempts | 12 |
| attempt_success_rate | 0.917 |
| retries | 1 |
| changes_applied | 7 |
| rollbacks | 1 |
| rollback_rate | 0.143 |
| mttr_s | 14.667 |
| recoveries | 1 |
| replans | 0 |
| invalidations | 0 |
| reused_nodes | 0 |
| policy_blocks | 0 |
| approvals | 2 |
| approval_wait_s | 1.3 |
| active_time_s | 130.453 |
| wall_time_s | 134.538 |
| sessions | 3 |
| llm_tokens | 0 |

Audit chain: **verified** (104 records, chain intact).

## 14. Change sets

Every applied change (including rolled-back attempts) is kept in `changes/`:

- `changes/docs.a1.diff`
- `changes/impl.click_accuracy.a1.diff`
- `changes/impl.expiry.a1.diff`
- `changes/impl.expiry.a2.diff`
- `changes/impl.url_validation.a1.diff`
- `changes/release.a1.diff`
- `changes/tests.regression.a1.diff`
