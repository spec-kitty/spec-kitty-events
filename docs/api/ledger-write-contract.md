---
title: Ledger write contract
description: persistence.append_event, the fail-closed and fixture-pinned way to append one event to a local JSONL ledger, and the retired LOCAL_ONLY_EVENT_TYPES registry.
doc_status: active
updated: 2026-10-18
type: API
kind: Reference
---

# Ledger write contract

Since **10.8.0**, `spec_kitty_events.persistence.append_event(event, path)` is the reference
way to append one event to a local, append-only JSONL ledger
([#118](https://github.com/spec-kitty/spec-kitty-events/issues/118)). The packaged
`ledger_append` conformance fixtures are the normative contract. The Python function is
one implementation of it, and every other binding (for example a Java write-side
sidecar) is held to the same bytes. The fixture format is defined in
[`conformance/fixtures/ledger_append/README.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/src/spec_kitty_events/conformance/fixtures/ledger_append/README.md).

```python
from spec_kitty_events.persistence import LedgerAppendError, append_event

try:
    append_event(record, ".kittify/events/glossary/mission-001.events.jsonl")
except LedgerAppendError:
    ...  # fail closed: nothing was written
```

## Input kinds

| Input | Record written |
|---|---|
| `Event` envelope | `event.model_dump(mode="json")`. The envelope and its payload are both validated. |
| Flat `dict` with `event_type` | The dict exactly as given. Its identity keys `event_type`, `event_id` and `timestamp` are kept; the remaining keys are validated as the payload. |
| Registered payload model | `{"event_type": <its type>, **fields}` |

Validation is `strict=True` against the committed models and JSON Schemas. An unknown
type, an invalid payload, an unsupported input, a non-JSON-native value or a non-finite
float raises `LedgerValidationError` before the file is opened.

## Bytes

`json.dumps(record, sort_keys=True, ensure_ascii=True, allow_nan=False) + "\n"`:

- keys are sorted recursively;
- separators are `", "` and `": "`;
- non-ASCII text is escaped as `\uXXXX`, so every line is pure ASCII;
- floats use the shortest round-trip form.

For JSON-native records these are the same bytes core's local fallback writes, so a ledger
has one byte shape whichever path wrote a line.

## Atomicity

Each line is one `os.write` on an `O_APPEND` descriptor, which is atomic for a regular
local file (network filesystems give no such guarantee). A non-empty ledger whose last byte
is not LF raises `LedgerTornTailError`, so the writer never extends a partial line. The
torn-tail check is advisory under concurrent writers.

## `LOCAL_ONLY_EVENT_TYPES` (deprecated)

The local-only classification is retired. Every modelled event type is canonical,
`MissionReopened` and `FollowUpRecorded` included, and whether a producer ships a record
off-host is the producer's own decision. The empty set stays exported, with no runtime
warning, through 10.x and is removed in 11.0.
