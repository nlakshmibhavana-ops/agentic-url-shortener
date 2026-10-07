# agentflow: governed agentic SDLC for a URL shortener (Java 21 / Spring Boot 4)

A working prototype of an **agentic software-engineering system**. It takes a requirement
(well-defined, a brownfield ticket, or a vague product ask) and drives it through
requirements → codebase analysis → design → plan → implementation → test → review → docs →
release readiness → release. Agents do the multi-step work; a governed orchestration layer
decides what is accepted, and humans own approvals and final quality.

The demo product is a **URL shortener** (Spring Boot 4, Java 21, Flyway, JDBC). The system builds
it from scratch, upgrades a legacy Spring Boot shortener that has real defects, and turns an
ambiguous ask into a clarified, released change. Every generated change is compiled, checked
with Checkstyle and tested with Maven before it is accepted.

| Deliverable | Where |
|---|---|
| Working prototype (runnable end to end) | `./agentflow` (orchestrator) + `scripts/demo.sh` |
| Architecture overview (components, orchestration model, control flow, decisions) | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Three scenarios: greenfield, brownfield, ambiguous | [docs/SCENARIOS.md](docs/SCENARIOS.md) + `scenarios/*.yaml` |
| Setup instructions | below |
| Testing approach, risks, trade-offs, assumptions, limitations | [docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md) |
| Real outputs of each scenario (report, audit trail, diffs, generated code) | [docs/sample-runs/](docs/sample-runs/) |

## Setup

Requires **JDK 21+** and **Maven 3.9+**.

```bash
./scripts/prefetch.sh            # once: warms ~/.m2 so runs build offline (about a minute)
./agentflow run greenfield       # builds the orchestrator on first use, then runs interactively
./scripts/demo.sh                # all three scenarios, non-interactive (about 5 minutes)
mvn test                         # orchestrator tests (unit + end-to-end scenarios)
mvn test -DexcludedGroups=scenario   # unit tests only, seconds
```

No API key is needed: by default the agents are deterministic (see "Three providers").

## Quick start

```bash
./agentflow run brownfield
```

1. Each step prints live as the agents work. A failed check shows a red `x`, then
   `<- change rolled back`, then a retry. In brownfield, the first expiry migration passes on an
   empty database but would silently expire every existing link; the migration test catches it.
2. When a **human decision** is needed, the run stops and asks you in the terminal: a
   **question** the agents must not guess (the ambiguous scenario asks what may be stored about
   visitors), or an **approval** (a schema change on a live database, or the release), showing
   why, the files, the test evidence, and an option to view the diff.
3. At the end it prints **where the code was built** (`runs/<run-id>/workspace/`), the test
   results, the released version and the evidence report. Then start what it built:

```bash
./agentflow serve <run-id>          # builds the JAR and runs it on http://localhost:8000/
```

| Try | What you'll see |
|---|---|
| `./agentflow run greenfield` | 8 tasks with parallel branches; you approve the release |
| `./agentflow run brownfield` | static analysis (JavaParser) finds the real bugs; a defective migration is rolled back and retried; you approve the migration and the release |
| `./agentflow run ambiguous` | 3 ambiguities; you answer the privacy question; the plan is revised and keeps finished work |
| `./agentflow run ambiguous`, answer `raw` | the privacy guardrail blocks storing raw IPs and the run safe-stops |
| answer `n` at an approval | the change is rolled back and the run stops |

**Non-interactive** (CI or scripts): add `--no-input`. The run stops at each human step and tells
you what to type, e.g. `./agentflow approve <run> release --by alice` then `./agentflow resume <run>`.

### CLI

| Command | Purpose |
|---|---|
| `run <scenario> [--as NAME] [--no-input] [--provider deterministic\|ollama\|claude]` | start a run |
| `resume <run> [--no-input]` | continue after a pause, stop or crash |
| `answer <run> <question> <option> --by NAME` | resolve a clarifying question (triggers re-planning) |
| `approve <run> <node> --by NAME [--reject] [--comment]` | human approval checkpoint |
| `serve <run> [--port 8000]` | build and start the service a run produced |
| `status <run>` / `report <run>` / `list` | state and pending human actions / regenerate report / runs |
| `stop <run>` | safe stop (also Ctrl+C) |
| `verify <run>` | verify the tamper-evident audit chain |
| `metrics` | reliability metrics across all runs |

## What a run produces

```
runs/<run-id>/
  report.md        requirement understanding, impact analysis, ADRs, plan + traceability,
                   mermaid DAG, timeline, gate results, policy findings, approvals,
                   decision log, test/review/readiness evidence, lineage, metrics
  audit.jsonl      append-only, hash-chained events with trace/span ids
  state.json       resumable state (graph, artifacts with versions/lineage, decisions)
  approvals.json   approval requests and decisions, bound to change digests
  metrics.json     success rate, retries, rollbacks, MTTR, latency, approvals, re-plans
  changes/*.diff   every change set applied, including rolled-back attempts
  workspace/       the product: Maven project, tests, openapi.json, docs/, CHANGELOG, RELEASE_NOTES
```

## Three providers

* **deterministic** (default): agents reason with a governed knowledge base, and implementation
  agents draw reviewed change sets from `playbooks/`. Reproducible, so the demo, the tests and
  reviewers all see the same behaviour.
* **ollama**: a local model (tested with `qwen2.5-coder:7b`) does requirements analysis and
  writes the Java code, with no API key and nothing leaving your network. The model's JSON is
  constrained by the agent's record schema and validated again.
  `OLLAMA_BASE_URL` / `OLLAMA_MODEL` select the server and model.
* **claude**: the same agents call Claude (`claude-opus-5-5`) through the Anthropic Java SDK's
  structured outputs. Needs `ANTHROPIC_API_KEY`.

In both LLM modes the implementation agent gets the task, its acceptance tests, the current files
and the previous attempt's build failures. If a model's change fails policy or the build, it is
rolled back and the deterministic agent is the **fallback**. Every provider goes through the same
machinery: policy, write-set boundary, gates, approvals, rollback and audit.

## Repository layout

```
src/main/java/io/agentflow/
  engine/     Engine (scheduling, gates, retries, fallback, approvals, rollback, invalidation,
              re-planning, safe stop, persistence), Scenario templates, RunState, Report
  core/       Graph, Gates, Policy (JavaParser), Approvals, ContextStore, AuditLog, Metrics,
              Workspace (undo logs), Maven runner, AppRunner (packaged-JAR runs), Knowledge
  agents/     requirements (+LLM), codebase analyst, design, planner, implement (+LLM),
              test runner, reviewer, docs, release readiness, release
  llm/        OllamaClient, ClaudeClient, record → JSON schema
  cli/        picocli commands, live progress, interactive prompts
src/main/resources/knowledge.yaml   capability vocabulary, acceptance criteria, ADRs, ambiguity rules
playbooks/    reviewed change catalogues: shortener (v1), linkly (upgrade), shortener-v2 (features)
fixtures/linkly/   the legacy Spring Boot codebase for the brownfield scenario
scenarios/    greenfield.yaml, brownfield.yaml, ambiguous.yaml
src/test/     orchestrator unit tests + end-to-end scenario tests (@Tag("scenario"))
```
