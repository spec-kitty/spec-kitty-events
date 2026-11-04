---
section: Added
---

- 168 generated schema reference pages on the docs site (#195): one page per payload
  schema under the event families, the shared schemas, the normative envelope and the
  producer-row pages; the three envelope pages label the normative envelope against its
  CloudEvents profile. Each payload page names its registered event type. `scripts/schema_pages.py --check` guards them against drift from the source
  schemas and runs in `make lint` and in the docs build, which now also triggers on
  `contracts/`, `schemas/` and producer-contract changes.
  - Diagrams render at build time in CI from digest-pinned images (ADR-0006) — Mermaid
    through Playwright Chromium and PlantUML through a pinned server container — so the
    published pages carry rendered `.dk-diagram` figures with no client-side drawing.
