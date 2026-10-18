<p align="center"><img src="site/src/assets/build-kitty.png" alt="Build Kitty" width="160"></p>

# spec-kitty-events

**Package Version**: `10.7.0` | **Envelope Schema Version**: `3.0.0` | **Python**: `>=3.10`

The **event contracts** of Spec Kitty: the canonical vocabulary the Spec Kitty CLI and
runtime emit as a Mission moves through its lifecycle. This repository is the
event-contract surface of the [Build Kitty](https://github.com/spec-kitty/spec-kitty)
product line — the free CLI and its extension surface.

`spec-kitty-events` owns the CloudEvents-shaped **envelope**, the **payload schemas** for
every event family, the pure **reducers** that fold an event stream into state, the packaged
**conformance fixtures**, and the **compatibility policy**. It is a small, dependency-light
Python package with no runtime dependency on the CLI, so any system — a dashboard, an
analytics job, the Team Kitty dossier, a SaaS ingress — can validate, order and reduce
Spec Kitty events **orthogonally**, without coupling to the CLI's internals.

```
spec-kitty CLI / runtime   ──emit──▶   status.events.jsonl / relay / journal
      (producer)                                   │
                                                   ▼
                                       spec-kitty-events contracts
                              (envelope · payloads · reducers · strict profile)
                                                   │
          ┌────────────────────────┬───────────────┴───────────────┐
          ▼                        ▼                                ▼
   Team Kitty dossier        dashboards / analytics            SaaS ingress
        (consumer)               (consumer)                     (consumer)
```

## What this package gives you

- **One envelope.** `Event` is the single top-level envelope every family rides in: a
  stable set of context fields (identity, ordering clock, project, schema version) carrying
  an opaque, per-family `payload`.
- **Event families.** Typed Pydantic payload models and event-type constants for mission
  lifecycle, work-package status, review and audit, runtime observation, collaboration, and
  semantics and analytics. See [Event families](docs/events/README.md).
- **Deterministic reducers.** Pure functions that fold an ordered event stream into a frozen
  state snapshot (mission state, work-package lanes, audit verdict, collaboration roster, …).
- **A fail-closed strict profile.** `validate_strict_envelope` — an opt-in, deterministic
  gate (`STRICT_PROFILE_ID = "journal/v1"`) for the durable journal and TeamSpace ingress.
- **Conformance fixtures.** Valid/invalid payloads, replay streams and golden reducer
  outputs, shipped in the package so every consumer validates against one corpus.

## Documentation

The documentation site follows the [doc-kitty](https://github.com/spec-kitty/doc-kitty)
convention and is published from `main` to
<https://spec-kitty.github.io/spec-kitty-events/>. Start at
[`docs/README.md`](docs/README.md):

- [Context](docs/context/README.md) — what the package is and how it is produced and consumed.
- [Event families](docs/events/README.md) — the families grouped by concern.
- [API & Contracts](docs/api/README.md) — the envelope, the Python package, the lane
  vocabulary, the versioning policy and the strict profile.
- [Architecture](docs/architecture/README.md) — the envelope design, ordering and
  reduction, and the journal-versus-volatile durability split.
- [Glossary](docs/glossary/README.md) · [Decision Records](docs/adr/README.md) ·
  [Guides](docs/guides/README.md) · [Changelog](docs/changelog/README.md)

The durable, governing contracts live in [`contracts/`](contracts/README.md);
[`COMPATIBILITY.md`](COMPATIBILITY.md) is the public compatibility policy and the
authoritative version-and-compatibility reference.

## Installation

From PyPI (pin an explicit version — see [`COMPATIBILITY.md`](COMPATIBILITY.md) for the
current release and what a major bump means):

```bash
pip install "spec-kitty-events==10.7.0"
# with conformance-validation support:
pip install "spec-kitty-events[conformance]==10.7.0"
```

With [uv](https://docs.astral.sh/uv/), install the exact versions pinned by the committed
`uv.lock` — what CI and `make test-fast` / `make test-full` resolve against:

```bash
uv sync --locked --extra dev --extra conformance
```

## Quick start

### Emit a canonical envelope

```python
import uuid
from datetime import datetime, timezone

from spec_kitty_events import Event

event = Event(
    event_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
    event_type="WPStatusChanged",
    aggregate_id="mission/WP01",
    payload={
        "mission_slug": "mission-001",
        "wp_id": "WP01",
        "from_lane": "planned",
        "to_lane": "claimed",
        "actor": "ci-bot",
        "execution_mode": "worktree",
    },
    timestamp=datetime.now(timezone.utc),
    build_id="build-2026-04-05",
    node_id="runner-01",
    lamport_clock=1,
    project_uuid=uuid.uuid4(),
    correlation_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
    schema_version="3.0.0",
)
```

### Validate a payload against the canonical contract

```python
from spec_kitty_events.conformance import validate_event

result = validate_event(event.payload, "WPStatusChanged")
assert result.valid
```

### Gate a full envelope fail-closed (durable journal / ingress)

```python
from spec_kitty_events.strict import validate_strict_envelope

errors = validate_strict_envelope(event.to_dict())
assert not errors
```

More, including reducing a stream into state, is in
[API & Contracts](docs/api/README.md).

## Versioning in one line

Two version numbers move independently: the **package version** (contract behaviour) and the
**on-wire envelope `schema_version`** (`3.0.0`, held stable across package majors). A change
is major if it flips the accept/reject boundary for any producer or consumer. The rules and
the required artifacts on a bump are in
[`contracts/versioning-and-compatibility.md`](contracts/versioning-and-compatibility.md).

## Development

```bash
make test-fast     # implementer blast-radius baseline
make test-full     # whole suite with coverage (CI agent)
uv run pytest --pyargs spec_kitty_events.conformance   # packaged conformance gate — every PR
ruff check . && ruff format --check .                  # lint
```

See [`AGENTS.md`](AGENTS.md) for the programme process and
[`docs/guides/contributing.md`](docs/guides/contributing.md).

## License

[MIT](LICENSE) © 2026 Spec Kitty, Inc. and contributors.
