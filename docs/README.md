---
title: spec-kitty-events documentation
description: Documentation home for the Spec Kitty event contracts — the envelope, event families, reducers and compatibility policy — and the events-domain services it ships.
doc_status: active
updated: 2026-11-03T00:00:00Z
kind: Hub
okf_version: "0.2"
---

# spec-kitty-events documentation

`spec-kitty-events` is the **event-contract surface** of Spec Kitty. It owns the
CloudEvents-shaped envelope, the payload schemas for every event family, the pure reducers
that fold an event stream into state, the packaged conformance fixtures and the
compatibility policy. It is the event-contract repository of the
[Build Kitty](/context/#the-build-kitty-product-line) product line — the free Spec Kitty CLI
and its extension surface.

The Spec Kitty CLI and runtime **produce** these events as a Mission moves through its
lifecycle. Any other system — the Team Kitty dossier, a dashboard, an analytics job, a SaaS
ingress — **consumes** them by importing this package to validate, order and reduce the
stream. Because the contract is a small, dependency-light package with no runtime dependency
on the CLI, those consumers stay **orthogonal**: they couple to the event contract, never to
the CLI's internals. [How it interacts with the CLI core](/context/system-context/).

The repository also ships the two **events-domain services** — the `mission-status-api` read
service and the `command-cli` write service — on their own `service-v*` release line; see
[Service APIs](/api/services/).

This tree follows the [doc-kitty](https://github.com/spec-kitty/doc-kitty) convention: each
section's `README.md` is its index and carries frontmatter, and the sections are registered
in [`_meta/sections.yaml`](https://github.com/spec-kitty/spec-kitty-events/blob/main/docs/_meta/sections.yaml).

## Start here

- **New to the package?** Read [Context](/context/) for what it is, where it sits in the
  product lines, and the producer → contract → consumer model.
- **Looking for a specific event?** [Event families](/events/) groups every family by
  concern, each with its event types, payloads, generated schema pages and reducer; the
  normative envelope is under [The envelope](/api/envelope/).
- **Integrating as a consumer?** [API & Contracts](/api/) is the envelope, the Python
  package surface, the lane vocabulary, the versioning policy and the strict profile.
- **Operating or calling the services?** [Service APIs](/api/services/) introduces the read
  and write services and their API reference; [Running the Mission Status Read
  API](/guides/running-the-service/) gets the read service up from a release or from source.

## Sections

| Section | What it holds |
|---|---|
| [Context](/context/) | What the package is, the Build Kitty line, and how it is produced and consumed. |
| [Event families](/events/) | The event families the runtime emits, grouped by concern. |
| [API & Contracts](/api/) | The envelope, the Python package, the lane vocabulary, versioning, the strict profile and the service APIs. |
| [Architecture](/architecture/) | The envelope design, total order and reduction, the journal-versus-volatile durability split, and the services. |
| [Glossary](/glossary/) | The vocabulary of the event contract. |
| [Decision Records](/adr/) | Architectural decisions, numbered `NNNN-title`. |
| [Guides](/guides/) | How to contribute, how to build the docsite, and how to run, release and cut over the services. |
| [Changelog](/changelog/) | Dated pages for notable changes, newest first. |

## Authoritative sources outside this tree

The docs explain and link; the normative contract lives in code and in the repository-root
policy files:

- The durable, governing contracts: [`contracts/`](https://github.com/spec-kitty/spec-kitty-events/blob/main/contracts/README.md).
- The public compatibility policy and the single declaration of the current package version:
  [`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md).
- The full release history: [`CHANGELOG.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/CHANGELOG.md).
- The canonical models: [`src/spec_kitty_events/`](https://github.com/spec-kitty/spec-kitty-events/tree/main/src/spec_kitty_events).
