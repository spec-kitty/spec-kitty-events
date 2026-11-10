# Contract delta: `mission-write` 1.0.1-SNAPSHOT (this mission)

The normative files are in `contracts/mission-write/` (WP01). This page is the design input.

## New path

`POST /api/v1/missions/{missionId}/status-log-repairs`, operationId `repairStatusLog`, tag `WorkPackages`.

- **Parameters:** `MissionId` and `WriteProtocolVersion` (the protocol header is required, as for a write).
- **Body:** `StatusLogRepairRequest`.
- **Responses:**

| Status | Response | When |
|---|---|---|
| 200 | `StatusLogRepairAck` | Always on success, including `action: none` (D1) |
| 400 | `RequestMalformed` | Bad body or unknown member |
| 401 | `TokenInvalid` | — |
| 403 | `RequestRefused` | Host/Origin guard |
| 404 | `TargetNotFound` | Unknown Mission, as for a write |
| 409 | `WriteConflict` | `STATUS_LOCK_HELD` |
| 413 | `RequestTooLarge` | — |
| 422 | `PlacementRefused` | `WRITE_LOCATION_UNVERIFIED`, including a coordination-routed Mission (gap until migration step 4, ADR-0007 D-1) |
| 500 | `WriteFailed` | `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`, or a catch-all |
| 503 | `ServiceBusy` | `SERVICE_DRAINING` (also when the request was still waiting at drain start, D10), `SERVICE_BUSY` |

No new refusal code.

## Additive edits

- **`TransitionUnit.implementationEvidencePresent`:** `type: [boolean, 'null']`, optional; `x-source` core `src/specify_cli/status/models.py` `TransitionRequest.implementation_evidence_present`.
- **`WriteOperation`:** `x-provisional.open_decision`, saying the vocabulary follows core's operation doors and a new door adds a value. A `### Provisional` CHANGELOG bullet names it.
- **Descriptions:**
  - `WriteRefusalReason`: `workspace_context_required` (wire-unreachable, and why) and `implementation_evidence_required` (now reachable through the field).
  - `WriteRefusalCode.REQUEST_MALFORMED`: the read-back cases, namely nesting beyond 256, an integer beyond 4300 digits, an undecodable actor object, or a unit the fold rejects.
  - `responses/ServiceBusy.yaml`: the drain-aware wording.
- **`enum_pins.json`:** pin `StatusLogRepairAck.action`.

## Compatibility

- **Additive only:** a new path, new schemas, and an optional request property. `breaking_check.py --root contracts` must report no break.
- **Response schemas:** no existing response schema changes.
- **Rolling upgrade:** an older service answers `400` to `implementationEvidencePresent`. That is documented on core #6195.
