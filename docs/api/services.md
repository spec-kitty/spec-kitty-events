---
title: Service APIs
description: 'The mission-status read service and mission-write write service: what they are, the service-v* release line they ship on, and their (unreleased) OpenAPI reference pages.'
doc_status: active
updated: 2026-11-03T00:00:00Z
type: API
kind: Hub
related:
  - guides/running-the-service
  - guides/service-protocol-and-releases
---

# Service APIs

`spec-kitty-events` releases on two lines: the Python contract package (PyPI `v*`) and the
events-domain Java services (`service-v*`, ADR-0002). This page introduces the two service
APIs that the `service-v*` line ships; everything else in this section is the Python
package's contract surface.

## The two services

- The **read service**, `mission-status-api` (contract module `mission-status`), is the
  loopback, read-only status service: it reads a project's missions and serves their
  status over HTTP.
- The **write service**, `command-cli` (contract module `mission-write`), is the loopback
  write service the CLI drives; writes pass a fail-closed gate against the contracts.

Both live under [`service/`](https://github.com/spec-kitty/spec-kitty-events/tree/main/service)
and ship on the `service-v*` line, versioned and released apart from the Python package
([ADR-0003](../adr/0003-gradle-build-and-service-release-line.md) owns the Gradle build and
the release line; the contract bundles ride on every `service-v*` tag as release assets).

## Running them

See [Running the Mission Status Read API](/guides/running-the-service/) for run-from-release
and run-from-source recipes.

## Contracts

Each service's contract is normative in the repository:

- [`contracts/mission-status`](https://github.com/spec-kitty/spec-kitty-events/tree/main/contracts/mission-status) — the read service's OpenAPI contract.
- [`contracts/mission-write`](https://github.com/spec-kitty/spec-kitty-events/tree/main/contracts/mission-write) — the write service's OpenAPI contract.

`contracts/tools/bundle.py` resolves each module to a single self-contained OpenAPI file;
the docs site publishes those files byte-for-byte (CI compares digests), so the reference
pages always match the resolver output.

## API reference

- [mission-status API reference](/reference/api/mission-status/) — the read service's
  endpoints, from the bundled contract, with the raw `openapi.yaml` and its sha256 available
  for download.
- [mission-write API reference](/reference/api/mission-write/) — the same for the write service.

**Both references are unreleased** until the first final `service-v*` tag exists: no tag means
no released contract for a consumer to pin. The flip rule (#194): when the first final
`service-v*` tag is cut, set `released: true` for both specs in `site/astro.config.mjs`
in the release pull request. The same rule is recorded in
[Service protocol and releases](/guides/service-protocol-and-releases/).
