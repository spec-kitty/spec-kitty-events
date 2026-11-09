---
section: Added
---

- A nightly, informational SonarCloud scan for the Python package and the JVM modules
  (#232). `.github/workflows/sonar.yml` runs on `schedule` and `workflow_dispatch` only, never
  on `pull_request`, so it adds no required check: it generates its own Python and JaCoCo
  coverage in the same job from the scanned revision, skips green when `SONAR_TOKEN` is absent,
  and reports the quality gate in the run summary. See `docs/guides/contributing.md`.
