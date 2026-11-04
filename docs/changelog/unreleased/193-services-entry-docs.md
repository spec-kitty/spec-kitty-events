---
section: Changed
---

- The documentation describes the repository as it actually is: home of the Python
  package *and* of the events-domain services under `service/` and `contracts/` (#193,
  ADR 0002). No page calls the repo package-only or the services pending cutover.
  - The README, the docs entry pages, the context pages and the services architecture
    docs state the two release lines — PyPI `v*` for the package, `service-v*` for the
    services — and route readers to the read service (`mission-status-api`) and the
    write service (`command-cli`), whose writes refuse fail-closed while the write core
    lands in #160 and #161.
  - The glossary defines the service vocabulary: read service, write service,
    `service-v*` line, launcher, `endpoint.json` and the fail-closed write gate.
  - `contracts/README.md` and both module changelogs state the ADR-0003 release rule,
    and the retired `contract-<module>-v*` tag line is gone from the operative text.
  - The cutover runbook is marked executed and kept as the record; the command-cli
    research page is marked historical.
