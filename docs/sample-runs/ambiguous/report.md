# Run demo-ambiguous-sample

**Scenario:** ambiguous: Product ask: safer links, marketing analytics, fast redirects  
**Status:** `succeeded`  
**Provider:** deterministic · **Playbook:** shortener-v2

## 1. Requirement

```text
Make short links safer and add some analytics so marketing can see how links perform.
Redirects should also be fast.
```

## 2. Requirement understanding

| Capability | Evidence in request | Acceptance criteria |
|---|---|---|
| analytics | Make short links safer and add some analytics so marketing can see how links perform. | Per-link total clicks, daily breakdown and top referrer hosts<br>Click recording never delays the redirect response |
| domain_denylist | Q-SAFETY -> denylist | Targets on a configurable domain denylist (incl. subdomains) are rejected with 400 |
| performance | Redirects should also be fast. | Redirect path meets the agreed latency target |
| redirect | Redirects should also be fast. | GET /{code} returns 302 to the target<br>Unknown codes return 404; deleted or expired codes return 410 |
| redirect_cache | Q-PERF -> p95-50ms | Hot redirects are served from a bounded in-process TTL cache<br>Deleting a link invalidates its cache entry immediately<br>Redirect p95 < 50 ms in-process |
| unique_visitors | Q-PRIVACY -> hashed | Stats report unique visitors per link<br>No raw IP address is ever persisted: visitors are a keyed, daily-rotating hash |
| url_safety | Make short links safer and add some analytics so marketing can see how links perform. | Only http(s) targets are accepted; script/data/file schemes are rejected with 400<br>URLs with embedded credentials are rejected |

**Ambiguities detected**

| Id | Trigger | Question | Options | Resolution |
|---|---|---|---|---|
| Q-PRIVACY | Make short links safer and add some analytics so marketing can see how links perform. | Marketing will likely want unique visitors, which needs a per-visitor identifier (IP address). What may we store? | hashed, none, raw | hashed (human:reviewer) |
| Q-PERF | Redirects should also be fast. | 'Fast' has no target. Which latency objective should the redirect path meet? | p95-10ms, p95-50ms | p95-50ms (assumption) |
| Q-SAFETY | Make short links safer and add some analytics so marketing can see how links perform. | 'Safer' could mean several threat models. Which should this change cover? | baseline, denylist, reputation | denylist (assumption) |

**Assumptions**

- Q-PERF: assumed 'p95 < 50 ms in-process (read-through cache, no new infrastructure)' (non-blocking; confirm or override)
- Q-SAFETY: assumed 'Add a configurable domain denylist for known-bad domains' (non-blocking; confirm or override)

## 3. Codebase reasoning (brownfield)

- Class dependency graph: `{"com.example.shortener.ShortenerApplication":[],"com.example.shortener.config.AppConfig":["com.example.shortener.config.ShortenerProperties","com.example.shortener.service.TokenBucketRateLimiter"],"com.example.shortener.config.ShortenerProperties":[],"com.example.shortener.domain.Click":[],"com.example.shortener.domain.Errors":[],"com.example.shortener.domain.Link":[],"com.example.shortener.domain.LinkStats":[],"com.example.shortener.service.CodeGenerator":["com.example.shortener.domain.Errors"],"com.example.shortener.service.LinkRepository":["com.example.shortener.domain.Click","com.example.shortener.domain.Link"],"com.example.shortener.service.LinkService":["com.example.shortener.config.ShortenerProperties","com.example.shortener.domain.Click","com.example.shortener.domain.Errors","com.example.shortener.domain.Link","com.example.shortener.domain.LinkStats","com.example.shortener.service.CodeGenerator","com.example.shortener.service.LinkRepository","com.example.shortener.service.TtlCache","com.example.shortener.service.UrlValidator"],"com.example.shortener.service.TokenBucketRateLimiter":[],"com.example.shortener.service.TtlCache":[],"com.example.shortener.service.UrlValidator":["com.example.shortener.domain.Errors"],"com.example.shortener.web.ApiExceptionHandler":["com.example.shortener.domain.Errors","com.example.shortener.web.RequestIdFilter"],"com.example.shortener.web.ApiKeyAuth":["com.example.shortener.config.ShortenerProperties","com.example.shortener.domain.Errors","com.example.shortener.service.LinkService"],"com.example.shortener.web.Dtos":["com.example.shortener.domain.Link"],"com.example.shortener.web.HealthController":["com.example.shortener.service.LinkRepository"],"com.example.shortener.web.LinkController":["com.example.shortener.config.ShortenerProperties","com.example.shortener.domain.Errors","com.example.shortener.domain.LinkStats","com.example.shortener.service.LinkService","com.example.shortener.service.TokenBucketRateLimiter","com.example.shortener.web.ApiKeyAuth"],"com.example.shortener.web.RedirectController":["com.example.shortener.domain.Link","com.example.shortener.service.LinkService"],"com.example.shortener.web.RequestIdFilter":[]}`
- Blast radius: ["com.example.shortener.ShortenerApplication","com.example.shortener.config.AppConfig","com.example.shortener.config.ShortenerProperties","com.example.shortener.domain.Click","com.example.shortener.domain.Link","com.example.shortener.domain.LinkStats","com.example.shortener.service.CodeGenerator","com.example.shortener.service.LinkRepository","com.example.shortener.service.LinkService","com.example.shortener.service.TtlCache","com.example.shortener.service.UrlValidator","com.example.shortener.web.ApiKeyAuth","com.example.shortener.web.Dtos","com.example.shortener.web.HealthController","com.example.shortener.web.LinkController","com.example.shortener.web.RedirectController"]
- Data at risk: ["clicks","idempotency_keys","links"] (schema tables: ["clicks","idempotency_keys","links"])
- Regression tests selected: ["src/test/java/com/example/shortener/integration/LinkServiceTest.java","src/test/java/com/example/shortener/unit/CodeGeneratorTest.java","src/test/java/com/example/shortener/unit/UrlValidatorTest.java"]

| Capability | Classes | Routes | Tables |
|---|---|---|---|
| analytics | ["com.example.shortener.config.AppConfig","com.example.shortener.domain.Click","com.example.shortener.domain.LinkStats","com.example.shortener.service.LinkRepository","com.example.shortener.service.LinkService","com.example.shortener.web.LinkController"] | ["GET /api/v1/links/{code}/stats"] | ["clicks","idempotency_keys","links"] |
| domain_denylist | ["com.example.shortener.service.CodeGenerator","com.example.shortener.service.TtlCache","com.example.shortener.service.UrlValidator"] | [] | [] |
| performance | [] | [] | [] |
| redirect | ["com.example.shortener.domain.Link","com.example.shortener.service.LinkService","com.example.shortener.web.RedirectController"] | ["GET /{code}"] | [] |
| redirect_cache | ["com.example.shortener.service.LinkService","com.example.shortener.web.RedirectController"] | ["GET /{code}"] | [] |
| unique_visitors | ["com.example.shortener.domain.LinkStats","com.example.shortener.service.LinkRepository","com.example.shortener.service.LinkService","com.example.shortener.web.LinkController"] | ["GET /api/v1/links/{code}/stats"] | ["clicks","idempotency_keys","links"] |
| url_safety | ["com.example.shortener.ShortenerApplication","com.example.shortener.config.ShortenerProperties","com.example.shortener.service.CodeGenerator","com.example.shortener.service.TtlCache","com.example.shortener.service.UrlValidator"] | [] | [] |

## 4. Design

Style: layered Spring Boot service: web -> service -> repository (JDBC) -> Flyway-managed schema

| ADR | Decision | Rationale | Alternatives |
|---|---|---|---|
| ADR-01 | Record clicks off the request thread; store referrer host + user-agent family only | Keeps redirect latency independent of analytics writes and minimises personal data. | Synchronous write (simpler, slower); Event queue such as Kafka (scales, more infrastructure) |
| ADR-02 | Configurable domain denylist, matched on domain suffix | Cheap, deterministic, auditable; reputation APIs add a vendor dependency. | Google Safe Browsing lookup (external dependency, latency) |
| ADR-03 | 302 + Cache-Control: no-store for redirects | Every click must reach the service for analytics; 301 is cached by browsers. | 301 Moved Permanently (cheaper, but under-counts) |
| ADR-04 | Bounded in-process TTL cache on the redirect path | Meets p95 < 50 ms with no new infrastructure; the TTL bounds staleness and delete invalidates. | Redis or CDN edge cache (needed only for p95 < 10 ms or multi-instance) |
| ADR-05 | Unique visitors via HMAC-SHA256(key, day \| IP \| user agent), truncated | Counts uniques per day without storing personal data; the daily key prevents tracking a visitor across days. | Store raw IPs (personal data, needs a legal basis); Cookies (consent banner needed) |
| ADR-06 | Validate targets at the boundary; never fetch them | Blocks script schemes, credential spoofing and internal hosts without network I/O. | Fetch-and-inspect targets (SSRF risk, latency) |

## 5. Plan (v2) and decomposition

| Task | Agent | Depends on | Writes | Verified by | Declared impact |
|---|---|---|---|---|---|
| perf.redirect_cache | implement | - | TtlCache.java, ShortenerProperties.java, LinkService.java, TtlCacheTest.java, RedirectPerformanceTest.java | LinkServiceTest.java, RedirectPerformanceTest.java, TtlCacheTest.java | low |
| safety.domain_denylist | implement | perf.redirect_cache | ShortenerProperties.java, UrlValidator.java, LinkService.java, IntegrationTest.java, DenylistTest.java, DenylistApiTest.java | DenylistApiTest.java, LinkServiceTest.java, DenylistTest.java, UrlValidatorTest.java | low |
| analytics.unique_visitors | implement | perf.redirect_cache, safety.domain_denylist | src/main/resources/db/migration/V2__visitor_hash.sql, ShortenerProperties.java, Click.java, LinkStats.java, LinkRepository.java, LinkService.java, Dtos.java, LinkController.java, RedirectController.java, VisitorAnalyticsTest.java | ApiIntegrationTest.java, LinkServiceTest.java, VisitorAnalyticsTest.java | low |

**Traceability (capability → tasks)**

- analytics: ["existing:AppConfig","existing:Click","existing:LinkStats","existing:LinkRepository","existing:LinkService","existing:LinkController"]
- domain_denylist: ["safety.domain_denylist"]
- performance: not covered
- redirect: ["existing:Link","existing:LinkService","existing:RedirectController"]
- redirect_cache: ["perf.redirect_cache"]
- unique_visitors: ["analytics.unique_visitors"]
- url_safety: ["existing:ShortenerApplication","existing:ShortenerProperties","existing:CodeGenerator","existing:TtlCache","existing:UrlValidator"]

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
  clarify_Q_PRIVACY["clarify.Q-PRIVACY<br/><small>skipped</small>"]:::skip
  plan --> clarify_Q_PRIVACY
  perf_redirect_cache["perf.redirect_cache<br/><small>succeeded</small>"]:::ok
  plan --> perf_redirect_cache
  safety_domain_denylist["safety.domain_denylist<br/><small>succeeded</small>"]:::ok
  plan --> safety_domain_denylist
  perf_redirect_cache --> safety_domain_denylist
  analytics_unique_visitors["analytics.unique_visitors<br/><small>succeeded</small>"]:::ok
  plan --> analytics_unique_visitors
  perf_redirect_cache --> analytics_unique_visitors
  safety_domain_denylist --> analytics_unique_visitors
  docs["docs<br/><small>succeeded</small>"]:::ok
  plan --> docs
  analytics_unique_visitors --> docs
  perf_redirect_cache --> docs
  safety_domain_denylist --> docs
  review["review<br/><small>succeeded</small>"]:::ok
  plan --> review
  analytics_unique_visitors --> review
  perf_redirect_cache --> review
  safety_domain_denylist --> review
  test["test<br/><small>succeeded</small>"]:::ok
  plan --> test
  analytics_unique_visitors --> test
  perf_redirect_cache --> test
  safety_domain_denylist --> test
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
| requirements | stage | succeeded | 1 | 2 | scenario | 23:13:11 | 23:13:49 |  |
| analyze | stage | succeeded | 1 | 2 | scenario | 23:13:12 | 23:13:50 |  |
| design | stage | succeeded | 1 | 2 | scenario | 23:13:12 | 23:13:50 |  |
| plan | stage | succeeded | 1 | 2 | scenario | 23:13:13 | 23:13:50 |  |
| clarify.Q-PRIVACY | checkpoint | skipped | 0 | 0 | plan:v1 | 23:13:13 |  | waiting for a human answer to Q-PRIVACY: Marketing will likely want unique visitors, which needs a per-visitor identifier (IP address). What may we store? |
| perf.redirect_cache | task | succeeded | 1 | 1 | plan:v1 | 23:13:13 | 23:13:31 |  |
| safety.domain_denylist | task | succeeded | 1 | 1 | plan:v1 | 23:13:31 | 23:13:47 |  |
| analytics.unique_visitors | task | succeeded | 1 | 1 | plan:v2 | 23:13:50 | 23:14:24 |  |
| docs | stage | succeeded | 1 | 1 | scenario | 23:14:24 | 23:15:00 |  |
| review | stage | succeeded | 1 | 1 | scenario | 23:14:24 | 23:15:00 |  |
| test | stage | succeeded | 1 | 1 | scenario | 23:14:24 | 23:15:00 |  |
| readiness | stage | succeeded | 1 | 1 | scenario | 23:15:00 | 23:15:00 |  |
| release | stage | succeeded | 1 | 1 | scenario | 23:15:00 | 23:15:25 |  |

**Timeline (audit trail excerpts)**

- `23:13:11` **run.session.start**  {"status":"created"}
- `23:13:11` **attempt.start** requirements {"agent":"requirements","attempt":1}
- `23:13:12` **agent.note** requirements {"note":"6 capabilities, 3 ambiguities, 1 blocking"}
- `23:13:12` **attempt.start** analyze {"agent":"codebase","attempt":1}
- `23:13:12` **agent.note** analyze {"note":"blast radius [ShortenerApplication, AppConfig, ShortenerProperties, Click, Link, LinkStats, CodeGenerator, LinkRepository, LinkService, UrlValidator, ApiKeyAuth, Dtos, HealthController, LinkController, RedirectC
- `23:13:12` **attempt.start** design {"agent":"design","attempt":1}
- `23:13:13` **attempt.start** plan {"agent":"planner","attempt":1}
- `23:13:13` **agent.note** plan {"note":"3 tasks; waiting on [Q-PRIVACY]"}
- `23:13:13` **plan.created** plan {"added":["perf.redirect_cache","safety.domain_denylist","clarify.Q-PRIVACY"],"changed":[],"plan_version":1,"removed":[],"reused":[]}
- `23:13:13` **attempt.start** perf.redirect_cache {"agent":"implement","attempt":1}
- `23:13:13` **agent.note** perf.redirect_cache {"note":"candidate 1/1: Q-PERF: bounded TTL read-through cache on the redirect path (p95 < 50 ms)"}
- `23:13:13` **node.waiting** clarify.Q-PRIVACY {"reason":"waiting for a human answer to Q-PRIVACY: Marketing will likely want unique visitors, which needs a per-visitor identifier (IP address). What may we store?"}
- `23:13:13` **change.applied** perf.redirect_cache {"added":195,"candidate":0,"digest":"7dcf06ea6a5b9c0f","paths":["src/main/java/com/example/shortener/config/ShortenerProperties.java","src/main/java/com/example/shortener/service/LinkService.java","src/main/java/com/exam
- `23:13:31` **attempt.start** safety.domain_denylist {"agent":"implement","attempt":1}
- `23:13:31` **agent.note** safety.domain_denylist {"note":"candidate 1/1: Q-SAFETY: configurable domain denylist (incl. subdomains)"}
- `23:13:31` **change.applied** safety.domain_denylist {"added":74,"candidate":0,"digest":"f7395f2e025c4471","paths":["src/main/java/com/example/shortener/config/ShortenerProperties.java","src/main/java/com/example/shortener/service/LinkService.java","src/main/java/com/examp
- `23:13:47` **run.session.end**  {"status":"waiting","stop_reason":null,"waiting":["clarify.Q-PRIVACY"]}
- `23:13:48` **human.answered**  {"option":"hashed","question":"Q-PRIVACY"}
- `23:13:48` **node.invalidated** requirements {"reason":"input 'human_answers' changed to v2"}
- `23:13:49` **run.session.start**  {"status":"waiting"}
- `23:13:49` **attempt.start** requirements {"agent":"requirements","attempt":1}
- `23:13:49` **node.waiting** clarify.Q-PRIVACY {"reason":"waiting for a human answer to Q-PRIVACY: Marketing will likely want unique visitors, which needs a per-visitor identifier (IP address). What may we store?"}
- `23:13:49` **agent.note** requirements {"note":"7 capabilities, 3 ambiguities, 0 blocking"}
- `23:13:49` **node.invalidated** analyze {"reason":"input 'requirements' changed to v2"}
- `23:13:49` **node.invalidated** design {"reason":"input 'requirements' changed to v2"}
- `23:13:49` **node.invalidated** plan {"reason":"input 'requirements' changed to v2"}
- `23:13:49` **attempt.start** analyze {"agent":"codebase","attempt":1}
- `23:13:50` **agent.note** analyze {"note":"blast radius [ShortenerApplication, AppConfig, ShortenerProperties, Click, Link, LinkStats, CodeGenerator, LinkRepository, LinkService, TtlCache, UrlValidator, ApiKeyAuth, Dtos, HealthController, LinkController,
- `23:13:50` **attempt.start** design {"agent":"design","attempt":1}
- `23:13:50` **attempt.start** plan {"agent":"planner","attempt":1}
- `23:13:50` **agent.note** plan {"note":"3 tasks; waiting on []"}
- `23:13:50` **node.reused** perf.redirect_cache {"plan_version":2}
- `23:13:50` **node.reused** safety.domain_denylist {"plan_version":2}
- `23:13:50` **node.superseded** clarify.Q-PRIVACY {"plan_version":2}
- `23:13:50` **plan.revised** plan {"added":["analytics.unique_visitors"],"changed":[],"plan_version":2,"removed":["clarify.Q-PRIVACY"],"reused":["perf.redirect_cache","safety.domain_denylist"]}
- `23:13:50` **attempt.start** analytics.unique_visitors {"agent":"implement","attempt":1}
- `23:13:50` **agent.note** analytics.unique_visitors {"note":"candidate 1/1: Q-PRIVACY=hashed: unique visitors via daily-rotating HMAC; raw IPs never stored"}
- `23:13:51` **change.applied** analytics.unique_visitors {"added":168,"candidate":0,"digest":"130430797cc067c9","paths":["src/main/java/com/example/shortener/config/ShortenerProperties.java","src/main/java/com/example/shortener/domain/Click.java","src/main/java/com/example/sho
- `23:14:08` **approval.requested** analytics.unique_visitors {"reasons":["DATA-001 src/main/resources/db/migration/V2__visitor_hash.sql: schema change against an existing database"],"subject":"130430797cc067c9"}
- `23:14:08` **node.waiting** analytics.unique_visitors {"reason":"approval required: DATA-001 src/main/resources/db/migration/V2__visitor_hash.sql: schema change against an existing database"}
- `23:14:08` **run.session.end**  {"status":"waiting","stop_reason":null,"waiting":["analytics.unique_visitors"]}
- `23:14:09` **approval.decided** analytics.unique_visitors {"by":"reviewer","comment":"no raw IPs; additive migration","decided_at":1.791328449798E9,"status":"approved","subject":"130430797cc067c9","waited_s":1.1}
- `23:14:10` **run.session.start**  {"status":"waiting"}
- `23:14:24` **attempt.start** review {"agent":"reviewer","attempt":1}
- `23:14:24` **attempt.start** test {"agent":"test_runner","attempt":1}
- `23:14:24` **attempt.start** docs {"agent":"docs","attempt":1}
- `23:14:29` **agent.note** review {"note":"0 checkstyle, 0 security, 0 latent defects (reported, non-blocking)"}
- `23:14:49` **agent.note** test {"note":"74 passed, 0 failed, line coverage 93.9%"}
- `23:15:00` **change.applied** docs {"added":413,"candidate":0,"digest":"56aa14f3852eed94","paths":["CHANGELOG.md","docs/API.md","docs/DESIGN.md","openapi.json"],"removed":0,"summary":"Generate OpenAPI contract, API reference, design record, changelog"}
- `23:15:00` **attempt.start** readiness {"agent":"release_readiness","attempt":1}
- `23:15:00` **agent.note** readiness {"note":"ready=true, 2 residual risks"}
- `23:15:00` **attempt.start** release {"agent":"release","attempt":1}
- `23:15:00` **change.applied** release {"added":27,"candidate":0,"digest":"9a93e54da178b3f7","paths":["RELEASE_NOTES.md","VERSION","pom.xml"],"removed":1,"summary":"Release 1.1.0"}
- `23:15:11` **approval.requested** release {"reasons":["high-impact stage 'release'","CHG-002 pom.xml: modifies a protected file"],"subject":"9a93e54da178b3f7"}
- `23:15:11` **node.waiting** release {"reason":"approval required: high-impact stage 'release'; CHG-002 pom.xml: modifies a protected file"}
- `23:15:11` **run.session.end**  {"status":"waiting","stop_reason":null,"waiting":["release"]}
- `23:15:13` **approval.decided** release {"by":"reviewer","comment":"","decided_at":1.791328513047E9,"status":"approved","subject":"9a93e54da178b3f7","waited_s":1.2}
- `23:15:13` **run.session.start**  {"status":"waiting"}
- `23:15:25` **run.session.end**  {"status":"succeeded","stop_reason":null,"waiting":[]}

## 8. Validation gates

| Node | Type | Gate | Result | Detail |
|---|---|---|---|---|
| requirements | exit | requirements_valid | pass | 6 capabilities, all with acceptance criteria |
| design | exit | design_covers_requirements | pass | every capability has a design element |
| plan | exit | plan_valid | pass | 3 tasks; every capability traced to at least one task |
| clarify.Q-PRIVACY | entry | clarified:Q-PRIVACY | FAIL | waiting for a human answer to Q-PRIVACY: Marketing will likely want unique visitors, which needs a per-visitor identifier (IP address). What may we st |
| perf.redirect_cache | exit | build | pass | compiled, checkstyle clean, [LinkServiceTest, RedirectPerformanceTest, TtlCacheTest] passed |
| safety.domain_denylist | exit | build | pass | compiled, checkstyle clean, [DenylistApiTest, LinkServiceTest, DenylistTest, UrlValidatorTest] passed |
| clarify.Q-PRIVACY | entry | clarified:Q-PRIVACY | FAIL | waiting for a human answer to Q-PRIVACY: Marketing will likely want unique visitors, which needs a per-visitor identifier (IP address). What may we st |
| requirements | exit | requirements_valid | pass | 7 capabilities, all with acceptance criteria |
| design | exit | design_covers_requirements | pass | every capability has a design element |
| plan | exit | plan_valid | pass | 3 tasks; every capability traced to at least one task |
| analytics.unique_visitors | exit | build | pass | compiled, checkstyle clean, [ApiIntegrationTest, LinkServiceTest, VisitorAnalyticsTest] passed |
| analytics.unique_visitors | exit | build | pass | compiled, checkstyle clean, [ApiIntegrationTest, LinkServiceTest, VisitorAnalyticsTest] passed |
| review | exit | lint_clean | pass | no checkstyle violations |
| review | exit | security_clean | pass | no security findings |
| test | exit | tests_pass | pass | 74 passed, 0 failed, 0 errors |
| test | exit | coverage_min | pass | line coverage 93.9% (min 90.0%) |
| docs | exit | openapi_valid | pass | 7 operations; all designed endpoints present |
| docs | exit | docs_complete | pass | 4 docs written |
| readiness | exit | readiness | pass | all release checks pass |
| release | exit | smoke | pass | packaged JAR started; GET /readyz -> 200; POST /api/v1/links -> 201; GET /gvbQCaq -> 302; GET /api/v1/links/gvbQCaq/stats -> 200 |
| release | exit | smoke | pass | packaged JAR started; GET /readyz -> 200; POST /api/v1/links -> 201; GET /YQ3pXWJ -> 302; GET /api/v1/links/YQ3pXWJ/stats -> 200 |

## 9. Policy guardrails

| Node | Rule | Severity | Path | Message |
|---|---|---|---|---|
| analytics.unique_visitors | DATA-001 | approve | src/main/resources/db/migration/V2__visitor_hash.sql | schema change against an existing database |
| release | CHG-002 | approve | pom.xml | modifies a protected file |

## 10. Human approvals

| Node | Subject | Reasons | Decision | By | Waited (s) |
|---|---|---|---|---|---|
| analytics.unique_visitors | 130430797cc067c9 | DATA-001 src/main/resources/db/migration/V2__visitor_hash.sql: schema change against an existing database | approved | reviewer | 1.1 |
| release | 9a93e54da178b3f7 | high-impact stage 'release'<br>CHG-002 pom.xml: modifies a protected file | approved | reviewer | 1.2 |

## 11. Decision log (lineage)

| Id | Node | By | Decision | Rationale | Inputs |
|---|---|---|---|---|---|
| D001 | requirements | assumption | Q-PERF resolved as 'p95-50ms' | p95 < 50 ms in-process (read-through cache, no new infrastructure) | {human_answers=1, requirement_text=1} |
| D002 | requirements | assumption | Q-SAFETY resolved as 'denylist' | Add a configurable domain denylist for known-bad domains | {human_answers=1, requirement_text=1} |
| D003 | design | agent | Record clicks off the request thread; store referrer host + user-agent family only | Keeps redirect latency independent of analytics writes and minimises personal data. | {impact_analysis=1, requirements=1} |
| D004 | design | agent | Configurable domain denylist, matched on domain suffix | Cheap, deterministic, auditable; reputation APIs add a vendor dependency. | {impact_analysis=1, requirements=1} |
| D005 | design | agent | 302 + Cache-Control: no-store for redirects | Every click must reach the service for analytics; 301 is cached by browsers. | {impact_analysis=1, requirements=1} |
| D006 | design | agent | Bounded in-process TTL cache on the redirect path | Meets p95 < 50 ms with no new infrastructure; the TTL bounds staleness and delete invalidates. | {impact_analysis=1, requirements=1} |
| D007 | design | agent | Validate targets at the boundary; never fetch them | Blocks script schemes, credential spoofing and internal hosts without network I/O. | {impact_analysis=1, requirements=1} |
| D008 | plan | agent | Serialise safety.domain_denylist after perf.redirect_cache | Both change [ShortenerProperties.java, LinkService.java]; running them in parallel could produce conflicting edits, so they are ordered like a merge queue. | {design=1, impact_analysis=1, requirements=1} |
| D009 | plan | agent | No new work for [analytics, redirect, url_safety] | Static analysis shows the existing code already implements these: {analytics=[com.example.shortener.config.AppConfig, com.example.shortener.domain.Click, com.example.shortener.domain.LinkStats, com.example.shortener.service.LinkRepository, com.example.shortener.service.LinkService, com.example.shortener.web.LinkController], redirect=[com.example.shortener.domain.Link, com.example.shortener.service.LinkService, com.example.shortener.web.RedirectController], url_safety=[com.example.shortener.ShortenerApplication, com.example.shortener.config.ShortenerProperties, com.example.shortener.service.CodeGenerator, com.example.shortener.service.UrlValidator]} | {design=1, impact_analysis=1, requirements=1} |
| D010 | requirements | human:reviewer | Q-PRIVACY answered: hashed | human clarification | {} |
| D011 | requirements | human:reviewer | Q-PRIVACY resolved as 'hashed' | Daily-rotating keyed hash of IP+UA; raw IP never stored | {human_answers=2, requirement_text=1} |
| D012 | requirements | assumption | Q-PERF resolved as 'p95-50ms' | p95 < 50 ms in-process (read-through cache, no new infrastructure) | {human_answers=2, requirement_text=1} |
| D013 | requirements | assumption | Q-SAFETY resolved as 'denylist' | Add a configurable domain denylist for known-bad domains | {human_answers=2, requirement_text=1} |
| D014 | design | agent | Record clicks off the request thread; store referrer host + user-agent family only | Keeps redirect latency independent of analytics writes and minimises personal data. | {impact_analysis=2, requirements=2} |
| D015 | design | agent | Configurable domain denylist, matched on domain suffix | Cheap, deterministic, auditable; reputation APIs add a vendor dependency. | {impact_analysis=2, requirements=2} |
| D016 | design | agent | 302 + Cache-Control: no-store for redirects | Every click must reach the service for analytics; 301 is cached by browsers. | {impact_analysis=2, requirements=2} |
| D017 | design | agent | Bounded in-process TTL cache on the redirect path | Meets p95 < 50 ms with no new infrastructure; the TTL bounds staleness and delete invalidates. | {impact_analysis=2, requirements=2} |
| D018 | design | agent | Unique visitors via HMAC-SHA256(key, day \| IP \| user agent), truncated | Counts uniques per day without storing personal data; the daily key prevents tracking a visitor across days. | {impact_analysis=2, requirements=2} |
| D019 | design | agent | Validate targets at the boundary; never fetch them | Blocks script schemes, credential spoofing and internal hosts without network I/O. | {impact_analysis=2, requirements=2} |
| D020 | plan | agent | Serialise safety.domain_denylist after perf.redirect_cache | Both change [ShortenerProperties.java, LinkService.java]; running them in parallel could produce conflicting edits, so they are ordered like a merge queue. | {design=2, impact_analysis=2, requirements=2} |
| D021 | plan | agent | Serialise analytics.unique_visitors after perf.redirect_cache | Both change [ShortenerProperties.java, LinkService.java]; running them in parallel could produce conflicting edits, so they are ordered like a merge queue. | {design=2, impact_analysis=2, requirements=2} |
| D022 | plan | agent | Serialise analytics.unique_visitors after safety.domain_denylist | Both change [ShortenerProperties.java, LinkService.java]; running them in parallel could produce conflicting edits, so they are ordered like a merge queue. | {design=2, impact_analysis=2, requirements=2} |
| D023 | plan | agent | No new work for [analytics, redirect, url_safety] | Static analysis shows the existing code already implements these: {analytics=[com.example.shortener.config.AppConfig, com.example.shortener.domain.Click, com.example.shortener.domain.LinkStats, com.example.shortener.service.LinkRepository, com.example.shortener.service.LinkService, com.example.shortener.web.LinkController], redirect=[com.example.shortener.domain.Link, com.example.shortener.service.LinkService, com.example.shortener.web.RedirectController], url_safety=[com.example.shortener.ShortenerApplication, com.example.shortener.config.ShortenerProperties, com.example.shortener.service.CodeGenerator, com.example.shortener.service.TtlCache, com.example.shortener.service.UrlValidator]} | {design=2, impact_analysis=2, requirements=2} |
| D024 | plan | orchestrator | Re-plan to v2 | upstream change; added [analytics.unique_visitors], changed [], removed [clarify.Q-PRIVACY], kept [perf.redirect_cache, safety.domain_denylist] without re-running | {} |

### test_report

```json
{
  "coverage" : 93.9,
  "errors" : 0,
  "failed" : 0,
  "failures" : [ ],
  "ok" : true,
  "passed" : 74,
  "skipped" : 0,
  "total" : 74
}
```

### review_report

```json
{
  "latent_defects" : [ ],
  "lint" : [ ],
  "security" : [ ]
}
```

### release_readiness

```json
{
  "checks" : [ {
    "check" : "all tests pass",
    "detail" : "74 passed / 0 failed",
    "passed" : true
  }, {
    "check" : "line coverage >= 90.0%",
    "detail" : "93.9%",
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
  } ],
  "ready" : true,
  "residual_risks" : [ "assumption: Q-PERF: assumed 'p95 < 50 ms in-process (read-through cache, no new infrastructure)' (non-blocking; confirm or override)", "assumption: Q-SAFETY: assumed 'Add a configurable domain denylist for known-bad domains' (non-blocking; confirm or override)" ]
}
```

## 12. Artifact lineage

Provenance of `release`:

- release v1 ← release
  - plan v2 ← plan
    - design v2 ← design
      - impact_analysis v2 ← analyze
        - requirements v2 ← requirements
          - human_answers v2 ← human:reviewer
          - requirement_text v1 ← scenario
  - release_readiness v1 ← readiness
    - docs v1 ← docs
    - review_report v1 ← review
    - test_report v1 ← test

## 13. Reliability metrics

| Metric | Value |
|---|---|
| nodes_succeeded | 12 |
| nodes_failed | 0 |
| node_success_rate | 1.0 |
| attempts | 14 |
| attempt_success_rate | 1.0 |
| retries | 0 |
| changes_applied | 5 |
| rollbacks | 0 |
| rollback_rate | 0.0 |
| mttr_s |  |
| recoveries | 0 |
| replans | 1 |
| invalidations | 4 |
| reused_nodes | 2 |
| policy_blocks | 0 |
| approvals | 2 |
| approval_wait_s | 1.2 |
| active_time_s | 127.988 |
| wall_time_s | 133.657 |
| sessions | 4 |
| llm_tokens | 0 |

Audit chain: **verified** (120 records, chain intact).

## 14. Change sets

Every applied change (including rolled-back attempts) is kept in `changes/`:

- `changes/analytics.unique_visitors.a1.diff`
- `changes/docs.a1.diff`
- `changes/perf.redirect_cache.a1.diff`
- `changes/release.a1.diff`
- `changes/safety.domain_denylist.a1.diff`
