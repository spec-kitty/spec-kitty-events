---
section: Fixed
---

- Corrects two `### Added` entries of this section, stale since the cutover import (#208).
  `service-release.yml` no longer refuses every tag: it releases a `service-v*` tag on a
  commit of `main` and refuses one only when the checkout has no `service/`. And
  `service/core-pin.json` serves two CI-only peers, lock interop and the corpus oracle: no
  job regenerates goldens from core, and the native legs' golden replay does not use core.
