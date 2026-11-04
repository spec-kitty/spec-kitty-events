---
title: Guides
description: Task-oriented how-tos for contributors to and consumers of spec-kitty-events — contributing, the docsite, running and releasing the services, and the cutover.
doc_status: active
updated: 2026-11-03T00:00:00Z
type: Guide
kind: Hub
okf_version: "0.2"
---

# Guides

- [Contributing](/guides/contributing/) — how a change lands in this repository, which tests
  to run, and the review gate.
- [Building the docsite](/guides/docsite/) — building and checking the doc-kitty site
  locally, and what CI publishes.

Services ([ADR-0002](../adr/0002-events-domain-owns-its-services.md)):

- [Running the Mission Status Read API](./running-the-service.md) — from a verified
  `service-v*` release or from source, its configuration and its routes.
- [Service protocol and releases](./service-protocol-and-releases.md) — the CLI-to-write-service
  protocol in practice, the two release lines, the release assets, how core pins them and how
  to cut a release.
- [Service cutover runbook](./service-cutover-runbook.md) — the maintainer procedure that
  imported the services from core with their history: executed 2026-10-31 and kept as the
  record of the cutover.

For how to consume the contract from Python (emit, validate, reduce), see
[the Python package](/api/python-package/).
