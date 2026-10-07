# Sample runs

Real outputs from one deterministic run of each scenario (`scripts/refresh_samples.sh`
regenerates them), plus one brownfield run with a live local model. The human steps were played
as "reviewer".

| Scenario | Start here | What to look for |
|---|---|---|
| [greenfield](greenfield/report.md) | `report.md` §5–§8 | 8-task plan, parallel branches, every build gate, the packaged-JAR smoke test, release approval |
| [brownfield](brownfield/report.md) | `report.md` §3, §7 | JavaParser analysis finding the real bugs; `changes/impl.expiry.a1.diff` (backfill, rolled back) vs `a2.diff` (approved) |
| [ambiguous](ambiguous/report.md) | `report.md` §2, §7 | three ambiguities, the privacy question, `plan.revised` reusing finished work |
| [llm-brownfield](llm-brownfield/report.md) | `report.md` §7, `changes/` | **live local model** (Ollama, qwen2.5-coder 7B): each model-written change checked by real Maven builds, rolled back or blocked when wrong, deterministic fallback |

Each folder contains:

- `report.md`: the evidence pack (requirements, impact, ADRs, plan, DAG, timeline, gates, policy, approvals, decisions, metrics)
- `audit.jsonl`: the hash-chained audit trail
- `approvals.json`: every approval request and decision, bound to change digests
- `metrics.json`: reliability metrics derived from the audit trail
- `changes/`: every change set applied, including rolled-back attempts
- `workspace/`: the generated Maven project (source, tests, `openapi.json`, docs, release notes)
