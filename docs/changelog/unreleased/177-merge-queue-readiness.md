---
section: Changed
---

- The repository is ready for a GitHub merge queue (#177, ADR 0005 D3). `ci.yml`,
  `acceptance.yml`, `cross-repo-harness-tests.yml`, `service.yml` and `docs-site.yml` run on
  `merge_group`, where the full suite always runs, squad label or not.
  - `ci.yml` resolves a queue run to its merge commit and the queued PR, so its verdict never
    refreshes `main`'s CI baseline, and fails the queue entry unless the verdict is green; its
    concurrency no longer folds queue runs into `main`'s. It now also runs on `opened` and
    `ready_for_review`, so `suite` reports on every PR.
  - `service.yml` and `docs-site.yml` drop their pull-request path filters so `service-gate`
    and `docs-build` always report; an in-job diff of the PR's or the merge group's SHAs
    skips the heavy work when the change misses their paths.
  - A `pr:pause-ci` label pauses a PR's CI (`pr-pause-ci.yml`, as in core).
  - Every action in the changed workflows is pinned by commit SHA.
