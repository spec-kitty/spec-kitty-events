---
section: Changed
---

- The repository does not use a GitHub merge queue (#177, ADR 0005 D3, amended 2026-11-02): in
  core a queue duplicated CI runs instead of avoiding reruns. No workflow runs on `merge_group`.
  The ruleset on `main` requires the checks `acceptance`, `cross-repo-harness-tests`,
  `service-gate` and `docs-build`, with "up to date before merging" off. `ci.yml`'s `suite`
  keeps its squad-label gate and is no longer a required check, so it no longer runs on
  `opened` and `ready_for_review`.
