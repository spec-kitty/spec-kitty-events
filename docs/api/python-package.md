---
title: The Python package
description: The spec_kitty_events import surface — emitting an envelope, validating a payload, gating with the strict profile, reducing a stream, and loading schemas and fixtures.
doc_status: active
updated: 2026-10-18
type: API
kind: Reference
related:
  - api/envelope
  - api/strict-profile
  - events/work-and-status
---

# The Python package

`spec_kitty_events` is a small, dependency-light Pydantic package. A consumer imports it,
validates and reduces, and stays insulated from how the CLI produced the event. Everything a
consumer needs is re-exported from the top-level package (`__all__`); the `producer_contract`
and `total_order` sub-surfaces are reached by their own module paths.

## Install

```bash
pip install "spec-kitty-events==10.6.0"
pip install "spec-kitty-events[conformance]==10.6.0"   # with validation support
```

Pin an explicit version and read
[versioning & compatibility](/api/versioning-and-compatibility/) before moving a major.

## Emit an envelope

```python
import uuid
from datetime import datetime, timezone
from spec_kitty_events import Event, WP_STATUS_CHANGED

event = Event(
    event_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
    event_type=WP_STATUS_CHANGED,            # == "WPStatusChanged"
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

Every field is explained in [the envelope](/api/envelope/).

## Validate a payload

`validate_event` checks one payload against its event type's contract. Pass `strict=True` to
validate a whole envelope (shape plus payload), and use the dedicated strict profile for the
fail-closed journal gate.

```python
from spec_kitty_events.conformance import validate_event

result = validate_event(event.payload, "WPStatusChanged")
assert result.valid

# validate a full envelope dict
result = validate_event(event.to_dict(), "WPStatusChanged", strict=True)
assert result.valid
```

## Gate an envelope fail-closed

The strict profile is the deterministic, fail-closed gate for the durable journal and
TeamSpace ingress. It is intentionally separate from `validate_event`.

```python
from spec_kitty_events.strict import validate_strict_envelope, STRICT_PROFILE_ID

errors = validate_strict_envelope(event.to_dict())
assert not errors            # empty list == admitted
assert STRICT_PROFILE_ID == "journal/v1"
```

See [the strict profile](/api/strict-profile/) for what it enforces.

## Reduce a stream into state

Each family ships a pure reducer that folds an ordered list of `Event`s into a frozen state
snapshot. Reducers order, de-duplicate and fold deterministically — see
[ordering and reduction](/architecture/ordering-and-reduction/).

```python
from spec_kitty_events import reduce_status_events

state = reduce_status_events(events)        # -> ReducedStatus
lane = state.wp_states["WP01"].lane
```

| Reducer | Reduced state | Family |
|---|---|---|
| `reduce_status_events` | `ReducedStatus` (per-WP `WPState`) | [work & status](/events/work-and-status/) |
| `reduce_lifecycle_events` | `ReducedMissionState` | [mission lifecycle](/events/mission-lifecycle/) |
| `reduce_mission_next_events` | `ReducedMissionRunState` | [mission lifecycle](/events/mission-lifecycle/) |
| `reduce_mission_audit_events` | `ReducedMissionAuditState` | [review, audit & decisions](/events/review-audit-decisions/) |
| `reduce_decision_point_events` | `ReducedDecisionPointState` | [review, audit & decisions](/events/review-audit-decisions/) |
| `reduce_mission_dossier` | `MissionDossierState` | [review, audit & decisions](/events/review-audit-decisions/) |
| `reduce_collaboration_events` | `ReducedCollaborationState` | [collaboration](/events/collaboration/) |
| `reduce_connector_events` | `ReducedConnectorState` | [runtime observation](/events/runtime-observation/) |
| `reduce_glossary_events` | `ReducedGlossaryState` | [semantics & analytics](/events/semantics-and-analytics/) |

The status-diary reducer that reads the CLI's on-disk `status.events.jsonl` is the standalone
`spec_kitty_events.diary` module (`parse_diary`, `reduce`, `State`); it is the same fold the
Team Kitty dossier runs. See [work & status](/events/work-and-status/).

## Load JSON Schemas and fixtures

Pre-generated JSON Schemas (Draft 2020-12) are generated from the canonical Pydantic models
and shipped in the package:

```python
from spec_kitty_events.schemas import load_schema

schema = load_schema("mission_audit_requested_payload")
```

The packaged conformance corpus (valid/invalid payloads, replay streams, golden reducer
outputs) is the authoritative compatibility surface. Run the drift and conformance gates:

```bash
python -m spec_kitty_events.schemas.generate --check
uv run pytest --pyargs spec_kitty_events.conformance -v
```

## Event-type classification

`LOCAL_ONLY_EVENT_TYPES` is a published `frozenset[str]` of event types that are **not**
routed through the SaaS-bound producer path. It is currently empty — every audited CLI event
routes through the central, SaaS-bound emitter — and exists so downstream consumers can
classify future local-only types without re-shipping a contract.

```python
from spec_kitty_events import LOCAL_ONLY_EVENT_TYPES
assert LOCAL_ONLY_EVENT_TYPES == frozenset()
```

## See also

- [The envelope](/api/envelope/) · [The strict profile](/api/strict-profile/) ·
  [Lane vocabulary](/api/lane-vocabulary/)
- [Event families](/events/) — the payload models each reducer folds.
