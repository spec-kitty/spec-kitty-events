---
section: Fixed
---
- A pull request from a fork no longer fails the required `cross-repo-harness-tests` check: GitHub
  withholds the secrets the harness needs from fork runs, so the gate job is skipped there (a pass
  to the required check) and a `cross-repo-harness-tests deferred (fork PR)` job reports it. A
  maintainer runs the harness on an upstream branch at the same head before merge
  (`docs/guides/contributing.md`, "Pull requests from forks"); no fork code runs with secrets (#95).
