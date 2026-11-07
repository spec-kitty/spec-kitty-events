---
section: Security
---

- The `service-v*` release's `publish` job, the only job with a write token, now runs in the
  `service-release` environment, so a release waits for a required reviewer and only a
  `service-v*` tag can enter it (#152). The environment, the `service-v*` tag ruleset and the
  `main` ruleset are repository settings, applied on 2026-11-07; they are described in
  `docs/guides/service-protocol-and-releases.md`, "Release protection".
