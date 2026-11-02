---
section: Changed
---

- The required-check workflows report on every pull request (#177, ADR 0005 D3).
  - `service.yml` and `docs-site.yml` drop their pull-request path filters so `service-gate`
    and `docs-build` always report; an in-job diff of the PR's SHAs skips the heavy work when
    the change misses their paths.
  - A `pr:pause-ci` label pauses a PR's CI (`pr-pause-ci.yml`, as in core).
  - Every action in the changed workflows is pinned by commit SHA.
