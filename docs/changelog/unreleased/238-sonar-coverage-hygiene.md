---
section: Changed
---

- **The nightly Sonar scan now sees coverage for every Java module and imports every module's JaCoCo report (#234, #235).**
  - Before: only `service/` and `service/command-cli/` produced JaCoCo XML, so `java/` showed zero coverage; library types came from the whole `modules-2` cache glob; the workflow scanned after a cancel, passed an unused `GITHUB_TOKEN` to the scan, and did not say in its step summary which build steps failed.
  - After: `./gradlew verifyAll` writes one JaCoCo XML per Gradle project with main sources, the collect step warns for each missing report and lists the imported reports, the scan resolves library types from explicit Gradle classpaths (`sonarLibraries`, with a warned fallback to the cache glob), the analysis steps use `!cancelled()`, the scan carries `SONAR_TOKEN` only, and the step summary lists each failed build step. `tests/test_sonar_workflow.py` pins these properties. The scan stays informational and no coverage gate changed.
