---
affected_files: []
cycle_number: 10
mission_slug: events-write-core-01M6TP6W
reproduction_command: spec-kitty agent tasks move-task WP08 --to approved --mission events-write-core-01M6TP6W --agent claude:claude-opus-5-5:reviewer
reviewed_at: '2026-11-08T20:24:28Z'
reviewer_agent: claude:claude-opus-5-5:reviewer
wp_id: WP08
---

Approved by claude:claude-opus-5-5:reviewer: approval:WP08

# WP08 review cycle 10 (Opus): APPROVE

No real fail-open remains.

**Open log-probe rows:** all six existed before this cycle; the log code is unchanged since `ed75e255`.
- `log-leading-nbsp` was a harness artifact: the generator's own pre-count fails on it, while core's real `read_events` strips the NBSP.
- `log-crlf-everywhere` is covered by decision 10.
- `log-nested-600` and `log-nested-1200` hit the 256 nesting cap (audit #16, core spec-kitty/spec-kitty#7439).
- `log-lone-cr-separator` is the recorded R24 residue (decision 19, note b).
- `log-trailing-formfeed` falls under R24.

**R31 (`RootCheckout` asks git) is sound under adversarial probing:**
- environment: every `GIT_*` variable cleared, `HOME`/`XDG` kept;
- parsing: three-line output, with exotic paths handled;
- runtime: real-path comparisons, 5 s timeout and kill, output flood, orphaned grandchild, missing git, an old git;
- nothing escapes, and the check is a once-only, final field.

The two structural fail-closed layouts are covered by FR-009 and decision 21. Results against git 2.43.0:
- `gc_targeted` 254/256 (the 2 are structural), `gc_defaults` 40/40, `gc_fuzz` 1500/1500;
- `c10_root` 11/11, `c9_root` 53/53;
- 21 fresh layouts all match.

Log probes driven directly against core show 0 fail-open. The static frontmatter/log sets (31,011 rows) are byte-identical to cycle 9. The Definition of Done holds in all 11 items; native smoke runs only in CI.

**Minor findings, both fixed by the orchestrator in `caddb77d` (merge `ef6e82a9`):**
1. Decision 21 wording now states the common-dir condition, the 5 s bound, and which global/system configs the cleared `GIT_*` environment hides.
2. `RootCheckoutTest` now asserts that the service agrees with the installed git rather than with git 2.43's pinned verdicts. A differing verdict only prints a notice, and the run must still see git both accept and refuse.

**Commands:**
- `:command-cli:check`: test 1139, nativeTagJvmTest 133, processTest 11, benchmarkTest 1, 0 failures (re-run after the minors: same counts, green).
- Generator `--check`: drift 0 (196 core cases).
- Benchmark: p50 83.4 ms, p95 97.4 ms.

Review: `/tmp/claude-1000/wp08-diff/cycle10/review/REVIEW.md`.
