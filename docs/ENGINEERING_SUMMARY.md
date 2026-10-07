# Engineering summary

## 1. Plan and rationale

The assignment's critical differentiator is the orchestration layer, so the build order was:

1. **The products first, to a production bar.** The URL shortener (Spring Boot 4, Java 21, JDBC,
   Flyway; 61 tests, about 93% line coverage, Checkstyle-clean) and a deliberately flawed legacy
   Spring Boot service. They give the orchestrator real code to reason about, real Maven builds
   to run and real defects to catch.
2. **The governance core next:** the dependency graph, gates that run real tools, policy
   evaluated before changes touch disk (on the Java syntax tree), digest-bound approvals,
   undo-log rollback, a hash-chained audit trail, versioned artifacts with lineage.
3. **Agents last.** Thin, replaceable producers of an `AgentResult`. The engine, not the agent,
   decides what is accepted.
4. **Scenarios as acceptance tests for the orchestrator.** Each forces a different path:
   * greenfield: parallel fan-out and fan-in
   * brownfield: codebase reasoning, a defective attempt, rollback, retry, approval of a data migration
   * ambiguous: clarification, partial progress, pause, re-planning with reuse

**Controlled autonomy, concretely:**

| Agents may (autonomously) | Agents may not | Humans own |
|---|---|---|
| Interpret requirements, propose questions and options | Write outside the task's declared paths | Answers to blocking questions (personal data is never assumed) |
| Analyse code, design, decompose, order tasks | Add non-allowlisted dependencies, secrets, command execution, string-built SQL, raw PII columns | Approval of schema changes on live data, protected files, large diffs |
| Produce change sets; retry with build feedback; use the fallback agent | Apply a change (the engine applies it), or edit a released migration | Approval of every release |
| Assume **non-blocking** defaults (recorded, surfaced in the release notes) | Exceed attempt or time budgets | Rejection (rolls back); stop and resume |

## 2. Artifacts produced

**Per run** (`runs/<id>/`): `report.md` (requirement understanding, impact analysis, ADRs, plan,
live DAG, timeline, gates, policy findings, approvals, decision log, test/review/readiness
evidence, lineage, metrics), the hash-chained `audit.jsonl`, `state.json`, `approvals.json`,
`metrics.json`, every change set in `changes/*.diff` (including rolled-back attempts) and every
artifact version in `artifacts/`.

**Product** (`runs/<id>/workspace/`): a Maven project with production code, unit and integration
tests, `openapi.json` (fetched from the running JAR, so it can't drift), `docs/API.md`,
`docs/DESIGN.md` (ADRs, assumptions, out-of-scope items), `README.md`, `CHANGELOG.md`,
`RELEASE_NOTES.md` (with the evidence checklist and residual risks) and `VERSION`.

## 3. Risks, trade-offs and failure scenarios

| Failure scenario | Guardrail / handling | Evidence |
|---|---|---|
| A change works on a fresh database but breaks live data | Test-first black-box migration test that upgrades a v0.4 database with rows; build gate fails → rollback → next candidate | brownfield `impl.expiry` attempt 1 (backfill gave old links an expiry) |
| Agent output is unsafe (secret, command execution, deserialization, string-built SQL) | `SEC-*` block on the syntax tree before apply; counts as a failed attempt with feedback | `PolicyTest` |
| Agent edits a released migration | `DATA-002` block (Flyway checksums) | `PolicyTest` |
| Agent touches files outside its task | `CHG-003` block | `PolicyTest` |
| Human chooses a non-compliant option (store raw IPs) | `PII-001` block; humans can't approve past a block | `choosingRawIpStorageIsStoppedByThePrivacyGuardrail` |
| Approved change differs from what is applied | Approval bound to the digest of the complete outcome (tree, change, policy, gates, inputs); gates re-run and the digest recomputed at resume; a difference invalidates it | `Engine.resumePending`, `ApproversTest` |
| Someone approves as someone else | Token authentication against `config/approvers.yaml` (salted hashes) and role checks (`change`/`data`/`release`) | `ApproversTest` |
| Generated code reads secrets, the network or user files | Allowlisted environment; bubblewrap: no network for builds, empty home, read-only system and Maven cache | `SandboxTest` |
| A symlink, oversized or unexpected file, or a concurrent edit corrupts the workspace | No symlinks followed; type, size and op limits; expected-hash check; two-phase atomic writes with restore | `WorkspaceTest` |
| A test gate passes without the intended tests | Missing test file fails; Surefire reports must show each declared class ran and passed | `BuildGateTest` |
| A requirement clause silently disappears | Clause dispositions; unsupported clauses block until a human descopes or stops | `AgentsTest` |
| A feature is "done" without proof | Criterion → code → passing tagged test check in release readiness | the readiness checks in every sample run |
| Two processes drive one run, or the owner crashes | OS file lock + owner record; fencing token; failover recovery | `OwnershipTest` (real `SIGKILL`) |
| A retry repeats a failure blindly | Structured diagnosis; only a reviewed repair for that diagnosis may run, else stop for a human | `RepairTest` |
| Approver rejects | Roll back, node failed, downstream blocked, run halted | `rejectedApprovalRollsBackAndHalts` |
| LLM outage, malformed or runaway output | Schema-validated structured output; output-token cap; `AgentException` → fallback agent | `llmOutageFallsBackToDeterministicAgents`, `OllamaClientTest`, the live run |
| Runaway loop or cost | Per-node attempts, per-run attempt and time budgets → safe stop | `attemptBudgetTriggersSafeStop` |
| Crash mid-change | In-flight undo log rolled back on resume; atomic state writes | `crashBetweenApplyAndCommitIsRolledBackOnResume` |
| Operator needs to halt now | `stop` / STOP file / Ctrl+C: builds killed, in-flight change rolled back, resumable | `safeStopThenResume` |
| Requirements change mid-run | Artifact invalidation → re-plan → reuse unchanged tasks; unsafe reverts escalate to a human | ambiguous scenario |
| Audit tampering | SHA-256 chain; `verify` detects edits and deletions | `AuditContextTest` |
| Parallel tasks conflict | Planner serialises overlapping write sets; one merge-queue lock for apply + build | ambiguous plan (`Serialise safety.domain_denylist after perf.redirect_cache`) |

**Trade-offs chosen deliberately:**

* **Deterministic default provider.** Reproducibility for reviewers and CI, at the cost of
  default agents being catalogue-driven rather than creative (see Limitations). Ollama and Claude
  are wired through the same governance.
* **Approve after validation.** Better evidence for approvers and no pointless approvals. The
  cost: unapproved changes exist briefly in the *sandbox* workspace, never in a release.
* **One Maven invocation per task gate.** About 7 s per task instead of three JVM start-ups; the
  gate still reports the failing phase.
* **Black-box regression tests for test-first.** Java compiles a module as a unit, so tests that
  reference not-yet-written classes would break every test. HTTP-level and migration tests compile
  against the existing code and fail at runtime until the fix lands. Greenfield tasks deliver each
  class together with its unit tests instead.
* **One workspace lock.** Consistent validation; builds are serialised like a merge queue while
  agent and model work stays parallel.
* **Keyword and AST heuristics** for requirements and codebase analysis: transparent and testable,
  brittle against phrasing they weren't written for. That is where the LLM providers add value,
  and their output is still validated against the capability vocabulary.
* **H2 + in-process rate limiter and cache in the product.** Zero-ops for a prototype behind a
  repository class; multi-instance deployment needs PostgreSQL (a JDBC URL change) and Redis.

## 4. Assumptions

* An approver is whoever holds a registered approver token (`./agentflow approvers add`). The
  registry is a local file, not an identity provider; the demo approver's token is published
  for the scripted demo and CI and must be removed for real use.
* The workspace is a sandbox. "Release" means a versioned, smoke-tested, approved artifact;
  deploying it is out of scope.
* The brownfield system's existing tests are its regression suite, and they are trustworthy.
* Non-blocking ambiguities may proceed on a recorded default; anything touching personal data is blocking.
* JDK 21+ and Maven are available; `scripts/prefetch.sh` has warmed `~/.m2` (builds run offline).

## 5. Limitations (honest)

* **In deterministic mode the implementation agents don't invent code.** They select from reviewed
  change catalogues (`playbooks/`), including deliberately defective candidates. Everything around
  them is real: policy, apply, rollback, the Maven builds and tests, re-planning, approvals, audit
  and metrics. Requirements analysis, codebase analysis, design, planning (selection, ordering,
  serialisation, regression selection, checkpoints), test running, review, docs and release are
  computed, not replayed.
* **LLM providers.** The **Ollama** path ran live end to end (brownfield, qwen2.5-coder 7B on a
  laptop CPU; SCENARIOS.md section 4): every model-written change was compiled and tested; two
  were rejected by the build gate, one was stopped by the output-token cap, and the fallback agent
  completed each. That is the machinery
  working, not the model succeeding. The **Claude** path is implemented with the current Anthropic
  Java SDK (structured outputs) and its fallback is tested with a simulated outage, but it has not
  been run live: that needs an API key.
* Capability vocabulary and questions are a curated catalogue. A clause outside it becomes an
  `unsupported` blocking question (descope or stop), never silently dropped. Dispositions work at
  sentence and semicolon granularity: a sentence that mixes a supported and an unsupported
  request counts as supported. Keyword matching can also mis-classify phrasing it wasn't
  written for.
* Codebase analysis is Java-only (JavaParser) and symbol-based, not a full compiler-grade call graph.
* One process owns a run at a time (file lock + fencing, with failover after a crash), and
  parallelism is virtual threads within it. There are no distributed workers. The lock is a
  local-filesystem lock: across hosts it needs a shared filesystem with working locks, or a
  database lease.
* The sandbox is bubblewrap on Linux. Where user namespaces are unavailable (some containers),
  `auto` mode falls back to environment scrubbing only and records that in the audit trail;
  `AGENTFLOW_SANDBOX=bwrap` (used in CI) fails closed instead. There are no CPU or memory limits
  beyond the build timeout.
* Approver tokens are long random secrets checked against salted hashes, but there is no SSO,
  expiry or two-person rule; anyone with file access to `config/approvers.yaml` can register
  an approver.
* Traceability is only as good as the tags: the check proves every criterion has linked code
  and a passing test that claims it, not that the test fully specifies the criterion. A human
  still reviews the tagged tests (they are in the diff).
* Diagnosis-driven repair can only choose among reviewed alternatives. A failure no repair
  matches stops for a human, by design; it does not invent a fix in deterministic mode.
* Policy rules are heuristics on the syntax tree and text; they are not a substitute for Semgrep,
  gitleaks or a dependency vulnerability scan, which would plug in as more gates.

## 6. Testing approach

Three layers:

1. **Product tests**, run by the orchestrator's gates inside each generated workspace:
   * shortener: 61 JUnit tests (unit + Spring Boot integration over real HTTP), about 93% line
     coverage; 76 after the ambiguous scenario's changes. Tests carry the acceptance-criterion
     ids they prove (`@Tag("AC-...")`)
   * linkly: 11 tests after the upgrade (the original 3 plus 8 black-box regression tests)

   They cover validation abuse cases (javascript:, data:, credentials, private and metadata IPs,
   self-loops), concurrency (atomic click counting under 8 threads), expiry, idempotency,
   ownership isolation, rate limiting, migrations on databases with live rows, the redirect
   performance budget, and privacy (no raw IP anywhere in the click rows).
2. **Orchestrator unit tests** (`GraphTest`, `WorkspaceTest`, `PolicyTest`, `AuditContextTest`,
   `AgentsTest`, `OllamaClientTest`, `RepairTest`, `ApproversTest`, `SandboxTest`, `OwnershipTest`):
   * graph invariants and every policy rule
   * workspace: all-or-nothing apply, path confinement, symlinks, limits, expected hashes, failed writes
   * audit tamper detection and lineage
   * requirement understanding, clause dispositions and planning
   * failure diagnosis and repair selection
   * approver authentication, roles and outcome binding
   * sandbox environment and isolation
   * run ownership against a real killed process, fencing, and a cross-process audit chain
   * model output refinement, and the Ollama client against a fake server
3. **Orchestrator end-to-end tests** (`EngineScenarioTest`, `@Tag("scenario")`). These drive whole
   scenarios with a scripted human:
   * all three happy paths, with metric assertions (rollbacks, retries, re-plans, reuse, parallel overlap)
   * the strict build gate against a real Maven build (`BuildGateTest`: missing and skipped tests fail)
   * PII block, rejected approval, safe stop and resume, budget exhaustion, crash recovery
   * LLM outage fallback

`mvn test` runs everything; `mvn test -DexcludedGroups=scenario` runs the unit layer in seconds.
CI runs both layers and the CLI demo on every push.

## 7. Review feedback and how it was addressed

| # | Feedback | Change | Verified by |
|---|---|---|---|
| 1 | Repair is predefined candidate selection | Failures become a structured diagnosis (phase, tests, locations, rules). The deterministic agent may only apply a reviewed repair declared for that diagnosis, else it stops for a human (`NoRepairException`). `failure.diagnosed` and `repair.selected` are audited. The model gets the diagnosis | `RepairTest` (real gate output), brownfield run |
| 2 | Unsupported requirements can disappear | Every clause gets a disposition (supported, ambiguous, constraint, unsupported). Unsupported clauses are blocking questions: descope or stop. A readiness check and a report table | `AgentsTest` |
| 3 | Builds inherit the environment | `core/Sandbox`: allowlisted environment always. bubblewrap: no network for builds, empty home, read-only system, JDK and Maven cache, and only the workspace writable. CI runs fail-closed | `SandboxTest` |
| 4 | Workspace safety | Symlinks refused, file-type, size and op limits, expected-hash check, and two-phase atomic writes with restore on failure | `WorkspaceTest` |
| 5 | Approvals don't bind the outcome | The approval digest covers the workspace tree, change, policy findings, gate verdicts and input artifact versions (release: plus test, review, docs and readiness). It is recomputed on resume and invalidated on any difference | `ApproversTest`, `Engine.resumePending` |
| 6 | No feature-completion proof | Criterion ids plus `@Tag("AC-...")` on product tests. Release readiness requires linked production code and a passing tagged test for every criterion. Two missing tests were added (code-collision retry, click recording off the request thread) | readiness checks in all three runs |
| 7 | Approver identity is self-declared | Token-authenticated approvers (salted SHA-256, constant-time compare), roles per request, and `approvers add` | `ApproversTest` |
| 8 | Concurrency is process-local | OS file lock plus owner record per run, a fencing token in the state, fsync'd atomic state writes, and cross-process locks for audit and approvals. Recovery after a crash | `OwnershipTest` (a child JVM is `SIGKILL`ed, then another process takes over) |
| 9 | Test gates can pass without the tests | Missing declared tests fail. Surefire reports must show each declared class ran with passing, non-skipped tests, and stale reports are cleared | `BuildGateTest` |

## 8. What I would do next

* Run larger local models (14B+) and Claude against the scenarios and track first-attempt success
  per model as an eval baseline (7B: 0 of 3 implementation attempts accepted). Add an eval set of requirements outside the catalogue.
* Replace the heuristic scanners with real tools (Semgrep, gitleaks, OWASP dependency-check) as
  gates; add mutation testing (PIT) as a gate on test-authoring tasks.
* Real identities for approvers (OIDC) and a two-person rule for data changes and releases.
* cgroup CPU/memory limits for sandboxed builds; distribute workers behind a queue with the state
  store and run leases in PostgreSQL.
* Model-assisted clause analysis (finer than sentences), with the deterministic disposition as
  the floor.
* Product: PostgreSQL plus Redis for multi-instance rate limiting and caching, async click
  ingestion, OpenTelemetry export for the audit spans.
