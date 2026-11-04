---
title: Overview
description: The shape of the spec-kitty-events repository — the contract package's five parts, its two events-domain services, and the producer-consumer flow.
doc_status: active
updated: 2026-11-03T00:00:00Z
type: Architecture
kind: Explanation
related:
  - architecture/ordering-and-reduction
  - architecture/durability-and-relay
  - context/system-context
---

# Overview

`spec-kitty-events` is the contract package **and** the home of the events-domain services:
the Java services and their OpenAPI contracts live here under `service/` and `contracts/`,
imported from core with their history
([ADR-0002](../adr/0002-events-domain-owns-its-services.md)). The package itself has five
parts, and everything else builds on them. Read this page first, then the page for the part
you care about; for the services, start at [the services hub](/api/services/) and
[running the Mission Status Read API](/guides/running-the-service/).

## The five parts

```mermaid
flowchart TB
  env["Envelope<br/>Event (models.py)"]
  fam["Payload families<br/>typed Pydantic models + event-type constants"]
  red["Reducers<br/>pure fold: stream -> frozen state"]
  strict["Strict profile<br/>validate_strict_envelope (journal/v1)"]
  conf["Conformance corpus<br/>fixtures · replay streams · golden outputs"]
  env --> fam
  fam --> red
  fam --> strict
  fam --> conf
```

1. **The envelope.** `Event` is the single top-level envelope every family rides in — stable
   context fields carrying an opaque `payload`. See [the envelope](/api/envelope/).
2. **Payload families.** One module per family defines its typed payload models and its
   event-type string constants. See [Event families](/events/).
3. **Reducers.** Pure, deterministic functions that fold an ordered stream into a frozen
   state snapshot. They all share one pipeline — see
   [ordering and reduction](/architecture/ordering-and-reduction/).
4. **The strict profile.** The opt-in, fail-closed envelope gate for the durable journal and
   ingress. See [the strict profile](/api/strict-profile/).
5. **The conformance corpus.** Valid/invalid payloads, replay streams and golden reducer
   outputs, shipped in the package so every consumer validates against one corpus.

## The producer-consumer flow

The Spec Kitty CLI and runtime are the single producer; everything else is a consumer. The
contract is the only coupling between them.

```mermaid
flowchart LR
  cli["CLI / runtime<br/>(producer)"]
  subgraph pkg["spec-kitty-events"]
    env["envelope"]
    fam["payload"]
    gate["validate / strict"]
    red["reduce"]
  end
  consumer["consumer<br/>dossier · dashboard · ingress"]
  cli -->|emit| env --> fam --> gate --> red --> consumer
```

A producer builds an `Event`, fills the family payload, and emits it on one of three
[transports](/context/system-context/). A consumer validates the payload (`validate_event`),
optionally gates the envelope fail-closed (`validate_strict_envelope`), and folds the stream
with the family reducer. Because the contract carries no behaviour, consumers stay
[orthogonal](/context/) to the CLI.

### The services

Two deployables ship from this repository on the `service-v*` line
([ADR-0003](../adr/0003-gradle-build-and-service-release-line.md),
[ADR-0004](../adr/0004-dual-artifact-line.md)):

- the **read service** (`mission-status-api`, Gradle module `:mission-status-api` rooted at
  `service/`): the loopback read-only Mission Status API — see
  [running the service](/guides/running-the-service/);
- the **write service** (`command-cli`, at `service/command-cli/`): the canonical event
  writer core's launcher starts per git common dir — see
  [service protocol and releases](/guides/service-protocol-and-releases/).

Their OpenAPI contracts live under `contracts/` (`mission-status`, `mission-write`) and are
published in this site's [services hub](/api/services/).

## What this package deliberately does not own

- **No team routing.** Identity on the envelope is build-scoped (`build_id`, `node_id`);
  private-versus-team routing, sharing and approval are host-product policy.
- **No transport or network.** The package defines shapes and folds; producing and shipping
  events is the CLI's and the host's job.
- **No single current-version restatement.** The authoritative version lives once in
  `COMPATIBILITY.md`; see [versioning & compatibility](/api/versioning-and-compatibility/).

## See also

- [Ordering and reduction](/architecture/ordering-and-reduction/) — how a stream becomes
  deterministic state.
- [Durability and the relay](/architecture/durability-and-relay/) — which events are durable
  and which are volatile.
