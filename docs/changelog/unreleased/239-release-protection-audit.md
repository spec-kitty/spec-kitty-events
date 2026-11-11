---
section: Security
---

- A daily, read-only release-protection audit (`.github/workflows/release-protection-audit.yml`, #239, closes #221) compares the live `service-release` environment and rulesets with `scripts/release/release-protection-expected.json`. It checks that every `service-v*` release was authored by `github-actions[bot]` and that each asset's provenance attestation was signed by `service-release.yml` at its own tag. A final release that fails turns the run red; a prerelease only warns.
  - The release guide (`docs/guides/service-protocol-and-releases.md`, "Release-protection posture") maps each release vector to its control or to an accepted risk signed off by the maintainer: the `main` ruleset's approval and bypass policy (D5), the rulesets' bypass lists the read-only token cannot see (D6), and the PR-lane canary's org-wide App token (D7).
