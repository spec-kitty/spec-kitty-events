---
section: Changed
---

- `service.yml` pins the tools of the service build, so a `service-v*` release no longer
  takes whatever is newest (#208). `setup-uv` installs uv `0.13.6`, the version the
  `service-v0.1.0-rc.1` run fell back to, for the job that builds
  `contract-bundles-<ver>.tar.gz` and for the jobs that run core's peers. `setup-java` installs
  Temurin `25.0.2`, the JDK 25 update the GraalVM CE native legs already pin, instead of the
  floating `25`.
