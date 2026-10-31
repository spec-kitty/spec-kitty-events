# Research note: service-v0.1.0-rc.1 readiness (2026-10-31)

- Runbook: §6 lives in spec-kitty-events docs/guides/service-cutover-runbook.md:418-428 (core's main has no copy). §6 = operator approves and cuts the tag per service-protocol-and-releases.md#cut-a-release, verify assets (sha256sum + gh attestation verify with signer/ref), hand tag + names + sums to core.
- Gates (service-release.yml): service/ present (yes); tag regex ^service-v\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$ (service-v0.1.0-rc.1 matches; same regex in core kernel/service_pin.py); tag commit ancestor of origin/main (fetch-depth 0); publish re-checks tag->commit, gh release create --verify-tag --prerelease --latest=false, never replaces.
- main e08f9a8: service.yml push run 41194624028 green (4 natives + native-test floor + golden replay, gradle verifyAll + bootJar, lock-interop x3, service-gate).
- Local: verifyAll green (2809 JUnit, 0 failures, 5 skipped); asset script rehearsal with real jar/bundles/tools/fixtures + placeholder natives: 17 files, SHA256SUMS checks, identical across two runs; invalid tag refused.
- Core vendor maps all 8 assets (prefix mission-status-api, 4 natives incl .exe, jvm .jar, 3 extras).
- Minor: bootJar manifest Implementation-Version 0.0.0-dev (service/build.gradle.kts version); not a blocker.
- Env: Maven Central 429 (mirror init script needed; plugin marker POMs differ between Central and portal -> charter doc gap); shallow clone breaks test_version_not_amended until unshallowed.
