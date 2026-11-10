# Data Model: Write-service cut-over readiness

## StatusLogRepairRequest (wire, closed)

| Field | Type | Rule |
|---|---|---|
| `context` | `RequestContext` | Required. The same object a write carries. `writeSurface` must be `checkout_append`; otherwise `422 WRITE_LOCATION_UNVERIFIED`. |

## StatusLogRepairAck (wire, closed, `200`)

| Field | Type | Rule |
|---|---|---|
| `repaired` | boolean | `true` iff `action != none` |
| `action` | enum `none` \| `completed` \| `quarantined` | See the state rules below |
| `movedBytes` | integer ≥ 0 | The fragment length for `quarantined`, otherwise `0` |
| `quarantinedFile` | `"status.events.jsonl.torn"` \| null | Non-null iff `quarantined`. A fixed name, never a host path |
| `readableAfterRepair` | boolean | The repaired log decodes and folds as the next write would |

## Repair action rules (evaluated under the turn and the Mission lock)

| Log state | Action | Effect on disk |
|---|---|---|
| missing, empty, or last byte is LF | `none` | Nothing. Byte-identical, no file created |
| fragment after the last LF is exactly one readable status row | `completed` | `+ "\n"` to the log, then force |
| any other fragment | `quarantined` | Append the fragment and LF to the quarantine file, then force (and sync the directory if the file is new). Truncate the log to the last LF, force it, then sync the directory |

**Invariants:**
- **Byte conservation.** After any failure, every byte of the original log exists either in the log or in the quarantine file (NFR-001).
- **Append-only quarantine.** The quarantine file is append-only; earlier content is never rewritten.
- **The write path never repairs** (C-001). A write to a torn log answers `500` and changes nothing.

## TransitionUnit.implementationEvidencePresent (wire, optional, request-only)

| Value | Effect on the unforced `in_progress → for_review` evidence guard |
|---|---|
| absent / `null` | Inferred as today (any prior transition of the WP) |
| `true` | Evidence present (the subtask half is still judged) |
| `false` | Refused: `409 TRANSITION_REJECTED`, reason `implementation_evidence_required` |

The value is never persisted. The encoded rows and replay classification are unaffected. Outside that transition, or under force, it is ignored, as in core.

## Guard context

`workspaceContext = executionMode + ":" + <resolved root checkout>` (was `":<root>"`). It is never persisted.

## Lock outcomes (command-cli port)

`LockResult` = `Completed` | `LockHeld` (→ `409 STATUS_LOCK_HELD`) | `PersistenceFailed` (→ `500`) | **`Abandoned`** (new, → `503 SERVICE_DRAINING`, nothing written; D10).
