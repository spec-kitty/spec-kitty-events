---
title: Services
description: The services of the events domain, the Mission Status Read API and the future write service, and how they read and write the event logs. The code moves here at cutover.
doc_status: active
updated: 2026-10-29T00:00:00Z
type: Architecture
kind: Hub
okf_version: "0.2"
---

# Services

The events domain has a read service and, in time, a write service. Both live in core
(`spec-kitty/spec-kitty`) today and move into this repository at cutover
([ADR-0002](../../adr/0002-events-domain-owns-its-services.md)). These pages describe them
from core at `4eecb124`, with a citation for each claim.

- [Services overview](./overview.md): the context and container views, the onion layering
  and the ArchUnit rules that enforce it, and where the code lives. **Read this first.**
- [The read-write split](./read-write-split.md): the read-only Mission Status Read API, the
  Java write service as the one canonical writer, and the writers and readers today versus
  the target.
- [Event-log lock and append](./event-log-lock-and-append.md): the lock shared with the CLI,
  atomic and durable appends, torn-tail repair, and why CI pins a core SHA.
- [Write-service protocol](./write-service-protocol.md): loopback HTTP, the bearer token,
  the handshake, durability acks, refusal codes and the launcher contract.

Build and release: [ADR-0003](../../adr/0003-gradle-build-and-service-release-line.md) and
[ADR-0004](../../adr/0004-dual-artifact-line.md).
