---
affected_files: []
cycle_number: 1
mission_slug: write-service-cutover-readiness-01M70THX
reproduction_command: spec-kitty agent tasks move-task WP04 --to approved --mission write-service-cutover-readiness-01M70THX --agent claude:claude-opus-5-5:reviewer-renata
reviewed_at: '2026-11-09T21:16:13Z'
reviewer_agent: claude:claude-opus-5-5:reviewer-renata
wp_id: WP04
---

Approved by claude:claude-opus-5-5:reviewer-renata: approval:WP04

# WP04 review: approved

**Reviewer:** claude-opus-5-5 (reviewer-renata), an independent Opus subagent.

**Verdict: APPROVE, no MAJOR findings.**

**Semantics match core at the pin (`512567ee`):**
- Evidence is inferred only when the flag is `None`, and only on the review handoff (`transition_pipeline.py:174-176`).
- The guard refuses when the flag `is not True` (`wp_state.py:388`).

**Wire:**
- Strict: boolean or null only. Other types and misspellings answer 400.
- Request-only: bytes are identical with the field true, null or absent, and every retry pair is a replay.

**Corpus:**
- 5 oracle-generated cases (`--check` against the pin: total 258, core cases 201, drift 0).
- One HTTP representative per (reason, field), so PASSED goes from 34 to 35 while GUARD_REASONS stays at 15.
- `unreachable.json`: `partial` removed, and the FR-013 reason updated.

**Real root:** it is passed in at construction, the no-arg overloads default to `.` for tests only, and no `<root>` remains.

**Runs:**
- `:command-cli:check`: 1584 tests, 0 failures.
- generator tests: 15 passed.
- packaged conformance gate: 1233 passed.
- `changelog.py check` and `guard`, and ruff: clean.
- mypy on the generator: 11 errors, the same set as on main (core imports).

**Deliberate red (implementer, T021):** dropping the field in `CorpusAuthorityCases` makes `guard-evidence-explicit-false` fail in `ParityCorpusTest`. Dropping it in `DiskMissionFactsParityTest` and `requestBody` fails the matching cases.

**Minor findings:**
- The dead `CorpusCases.unit()` was deleted by the orchestrator in `4ba552ee`.
- The stale `write-service-protocol.md:220-223` belongs to WP07 (T033).
