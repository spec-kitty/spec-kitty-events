---
affected_files: []
cycle_number: 2
mission_slug: cloudevents-envelope-registry-upcaster-01M5Z6VM
reproduction_command:
reviewed_at: '2026-10-27T18:29:17Z'
reviewer_agent: claude-reviewer
wp_id: WP04
---

# WP04 review, cycle 2: changes requested (claude-reviewer)

All three earlier [MAJOR] findings are fixed. I re-ran the probes: the float output, the slug comparison and the ASCII-only ids now match between Python and Java. NaN, the `$`-anchored patterns and the macOS steps in `ensure_jdk25.sh` are also fixed.

Gates:
- `make test-java`: 364 passed.
- Corpus gate: 370 passed.
- Conformance gate: 1198 passed.
- `ruff check` and `ruff format --check`: clean.

## [MAJOR]
- `make test-fast` fails: `tests/test_dockerignore.py::test_dockerignore_covers_every_gitignore_pattern` reports `uncovered ['.cache/jdk-25', 'java/target']`. WP04 added these two patterns to `.gitignore` but not to `.dockerignore`, so `make test-full` will fail in CI.
  - Fix: add the recursive Docker patterns `**/java/target` and `**/.cache/jdk-25` to `.dockerignore`.
  - Reproduce: `uv run pytest tests/test_dockerignore.py -q --no-cov`.

## Minor (open an issue)
- A payload nested 600 deep makes Python `upcast_line` raise an uncaught `RecursionError` (json.loads succeeds; the recursion error comes later, in the deepcopy). Java returns `NOT_JSON` for the same line. This is the documented depth limit, but Python should fail gracefully.
