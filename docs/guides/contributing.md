---
title: Contributing
description: How a change lands in spec-kitty-events — the one-issue-one-branch-one-PR flow, the tests and conformance gate to run, and the adversarial review.
doc_status: active
updated: 2026-10-18T00:00:00Z
type: Guide
kind: How-To
related:
  - guides/docsite
---

# Contributing

`spec-kitty-events` follows the Spec Kitty programme process. `main` is the integration
branch: every PR targets `main`, nothing deploys on merge, and implementers never merge their
own work. The binding process is the programme constitution; see
[`AGENTS.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/AGENTS.md) and
[`PROGRAM.md`](https://github.com/spec-kitty/EXPERIMENTAL-spec-kitty-planning/blob/main/PROGRAM.md).

## The flow

- **One issue → one branch → one PR.** Cut `issue-<n>-<slug>` from `main`; open one PR back to
  `main`. Never push to `main`.
- **Run what you touched, plus the blast radius.** Run every test you write or change and the
  tests around it, and record the commands and counts in the PR. Do not run a whole-repo suite
  before opening the PR — that is the CI agent's job.
- **One adversarial review per PR.** `[MAJOR]` findings block; minors become issues.

## Commands

| Purpose | Command |
|---|---|
| Implementer blast-radius baseline | `make test-fast` |
| Whole suite with coverage (CI agent only) | `make test-full` |
| Packaged conformance gate — **every PR** | `uv run pytest --pyargs spec_kitty_events.conformance` |
| Lint | `ruff check . && ruff format --check .` |

The conformance gate is the packaged contract other repos consume; the in-repo suite does not
collect it, so run it explicitly on every PR.

## When you change the contract

A change that flips any accept/reject boundary is a major package bump and must ship the full
artifact set (changelog, compatibility, schemas, fixtures). Read
[versioning & compatibility](/api/versioning-and-compatibility/) before you start, and
regenerate committed JSON Schemas with `python -m spec_kitty_events.schemas.generate` when you
touch a payload model.

## When you change the docs

Validate and build the docsite as [Building the docsite](/guides/docsite/) describes; the docs
CI runs frontmatter validation, a full build and a built-link check.
