---
affected_files: []
cycle_number: 1
mission_slug: write-service-cutover-readiness-01M70THX
reproduction_command: spec-kitty agent tasks move-task WP05 --to approved --mission write-service-cutover-readiness-01M70THX --agent claude:claude-opus-5-5:reviewer-renata
reviewed_at: '2026-11-09T21:37:46Z'
reviewer_agent: claude:claude-opus-5-5:reviewer-renata
wp_id: WP05
---

Approved by claude:claude-opus-5-5:reviewer-renata: approval:WP05

# WP05 review: approved

**Reviewer:** claude-opus-5-5 (reviewer-renata), an independent Opus subagent.

**Verdict: APPROVE, no MAJOR findings.**

**Verified:**
- **Lock:** `LedgerLock` reads the abandon signal only after a failed `tryAcquire`; a lock it took is always returned, and nothing leaks.
- **Turn:** the three-state turn takes a free turn even while draining.
- **Mapping:**
  - `LockAbandonedException` is caught before `IOException`;
  - an abandoned wait answers 503 `SERVICE_DRAINING` with no `Retry-After`, for both writes and repairs;
  - a timeout is still `SERVICE_BUSY`;
  - no new refusal code.
- **Holders** finish with 201, and their bytes are in the log.
- **Unchanged:** `DRAIN_BUDGET` 5 s, the write budget 10 s, and `LAUNCHER.md`.
- **AOT:** `processAot` is green.

**Runs:** `:event-log:check` (578 tests, 2 skipped) and `:command-cli:check` (1645 tests): green. DrainTest passed 3 more `--rerun-tasks` runs.

**Measured by the implementer:** a waiting request answers 503 in 37–96 ms after the shutdown.

**Minor findings, fixed by the orchestrator in `df4bbc0c`:**
- the sleep heuristic is replaced by deterministic lock-wait and turn-wait observation;
- `awaitStopped` is timed from the shutdown and asserts nothing was in flight;
- `LedgerLock` javadoc for implementors.

**Declared out-of-map edits:**
- `WriteRig.start(budget, customizer)`;
- `ServiceWiringTest`, one line for the new bean parameter;
- `WriteTurn.hasWaiters()`, used only for observation.
