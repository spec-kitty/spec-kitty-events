---
title: Services
description: The services of the events domain, the Mission Status Read API and the write service (command-cli), and how they read and write the event logs. The code lives here.
doc_status: active
updated: 2026-10-31T00:00:00Z
type: Architecture
kind: Hub
okf_version: "0.2"
---

# Services

The events domain has a read service and a write service, `command-cli`. The write service runs
and its writes refuse fail-closed until the write core lands (#160, #161). Their code and
contracts live in this repository, under `service/` and `contracts/`, imported from core
(`spec-kitty/spec-kitty`) at `512567eee71e31bb39f580c4f7acf8b865e9d1b2` with their history
([ADR-0002](../../adr/0002-events-domain-owns-its-services.md)). These pages describe them
with a citation for each claim: local paths for what lives here, core at `4eecb124` for what
stays in core.

- [Services overview](./overview.md): the context and container views, the onion layering
  and the ArchUnit rules that enforce it, and where the code lives. **Read this first.**
- [The read-write split](./read-write-split.md): the read-only Mission Status Read API, the
  Java write service as the one canonical writer, and the writers and readers today versus
  the target.
- [Event-log lock and append](./event-log-lock-and-append.md): the lock shared with the CLI,
  atomic and durable appends, torn-tail repair, and why CI pins a core SHA.
- [Write-service protocol](./write-service-protocol.md): loopback HTTP, the bearer token,
  the handshake, durability acks, refusal codes and the launcher contract.
- [Event ledger writers and readers](./event-ledger-writers-and-readers.md): core's map of
  every event log and ledger it writes and reads, the target the event ADRs set, and the
  gap table (G-1 to G-19) the read-write split summarises.
- [command-cli research note](./command-cli-research.md): the research for the write service
  (#156): what core binds, what the contract fixes, the gaps and the proposed slicing.

Build and release: [ADR-0003](../../adr/0003-gradle-build-and-service-release-line.md) and
[ADR-0004](../../adr/0004-dual-artifact-line.md).
