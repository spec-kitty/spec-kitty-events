---
title: Changelog
description: Dated record of notable changes to the spec-kitty-events contract package.
doc_status: active
updated: 2026-10-31T00:00:00Z
type: Changelog
kind: Hub
agent:
  priority: 0.5
okf_version: "0.2"
---

# Changelog

Notable changes to `spec-kitty-events`, newest first. Each entry records what
changed, why, and the resulting package version. The full semantic-versioned
history (every release, in Keep a Changelog form) lives in the repo-root
[`CHANGELOG.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/CHANGELOG.md); the public compatibility policy and the
per-version rationale live in [`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md). This
section tracks changes from `10.4.3` onward.

Each page sets `sidebar.order` to minus `YYYYMMDD` plus a same-day sequence
digit (`-202610132` is the second page of 2026-10-13), so the sidebar lists the
newest first without renumbering older pages.

- [2026-10-31 — service-v0.1.0-rc.1: the first events service release (package unchanged)](./2026-10-31-service-v0-1-0-rc-1.md)
- [2026-10-29 — Unreleased: the events domain takes ownership of its services (no package change)](./2026-10-29-events-services-home.md)
- [2026-10-27 — 11.3.0: CloudEvents profiles, the full registry, the legacy-row upcaster and a Java binding](./2026-10-27-11-3-0-cloudevents-registry-upcaster.md)
- [2026-10-19 — 11.2.1: one schema-version source, gate and build invalid fixtures](./2026-10-19-11-2-1-schema-version-source-fixture-breadth.md)
- [2026-10-19 — 11.2.0 release overview: everything new since v11.0.0, fully additive](./2026-10-19-11-2-0-release-overview.md)
- [2026-10-19 — 11.2.0: producer row for the decision timeout](./2026-10-19-11-2-0-decision-timeout-producer-row.md)
- [2026-10-19 — 11.2.0: RACI actor-id sentinels human-in-charge and UNKNOWN](./2026-10-19-11-2-0-raci-actor-id-sentinels.md)
- [2026-10-19 — 11.1.0: decision-significance events and lifecycle conformance breadth](./2026-10-19-11-1-0-decision-significance-lifecycle-conformance.md)
- [2026-10-18 — 11.0.0: decision reopen, core-shaped glossary contracts, optional envelope clock](./2026-10-18-11-0-0-decision-glossary-envelope-clock.md)
- [2026-10-15 — Reducer: a departure from approved outranks a clock-ahead approval (10.5.1)](./2026-10-15-diary-clock-ahead-approval-departure.md)
- [2026-10-13 — Reducer: an equal-timestamp approved -> done settles its approval (10.4.6)](./2026-10-13-diary-equal-at-approved-done-settles-approval.md)
- [2026-10-13 — Reducer: a rollback seed never takes a lane against the real lifecycle (10.4.5)](./2026-10-13-diary-rollback-seed-lane-authority.md)
- [2026-10-12 — Reducer: a reconstruction seed that loses the lane still folds its payload (10.4.4)](./2026-10-12-diary-losing-seed-folds-payload.md)
- [2026-10-12 — Reducer: a backfilled rejection is never overwritten by a bare concurrent approval (10.4.3)](./2026-10-12-diary-concurrent-approval-after-backfilled-rejection.md)
