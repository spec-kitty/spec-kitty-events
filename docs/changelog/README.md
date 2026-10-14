---
title: Changelog
description: Dated record of notable changes to the spec-kitty-events contract package.
doc_status: active
updated: 2026-10-14
type: Changelog
kind: Hub
agent:
  priority: 0.5
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

- [2026-10-13 — Reducer: an equal-timestamp approved -> done settles its approval (10.4.6)](./2026-10-13-diary-equal-at-approved-done-settles-approval.md)
- [2026-10-13 — Reducer: a rollback seed never takes a lane against the real lifecycle (10.4.5)](./2026-10-13-diary-rollback-seed-lane-authority.md)
- [2026-10-12 — Reducer: a reconstruction seed that loses the lane still folds its payload (10.4.4)](./2026-10-12-diary-losing-seed-folds-payload.md)
- [2026-10-12 — Reducer: a backfilled rejection is never overwritten by a bare concurrent approval (10.4.3)](./2026-10-12-diary-concurrent-approval-after-backfilled-rejection.md)
