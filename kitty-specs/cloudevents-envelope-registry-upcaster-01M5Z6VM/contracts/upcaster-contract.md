# Upcaster row-level contract

Binding for every language binding. Implemented by `spec_kitty_events.cloudevents` (Python)
and `ai.speckitty.events.cloudevents` (Java); pinned by `producer_contract/fixtures/upcast/`.

## Inputs

- `row`: one parsed JSON value (or `line`: the raw bytes of one log line).
- `context` (optional): `missionid`, `missionslug`, `projectuuid` — from the Mission's
  `meta.json` and project config. Context fills gaps; it never overrides a row value.
- `mode`: `permissive` (default; local reads and merges) or `strict` (ingress, pre-write).

## Outcomes

| Outcome | Row returned | When |
|---|---|---|
| `mapped` | a new CloudEvent | the row is a recognised legacy form and maps cleanly |
| `passthrough` | the input, unchanged | not an object; already a CloudEvent; unrecognised (shape C/D); or a mapping issue in permissive mode |

In strict mode every issue except `ALREADY_CLOUDEVENT` raises `UpcastError(code)` instead
of a pass-through. The input is never mutated.

## Detection order

1. not a JSON object → `NOT_AN_OBJECT`
2. has `specversion` → `ALREADY_CLOUDEVENT` (pass-through, no warning, also in strict)
3. has `event_type`, `aggregate_id`, `payload`, `node_id`, `build_id` → shape A
   (`transport_envelope`)
4. has `event_type` and `payload` and `row_version` → `journal_envelope`; `aggregate_type` →
   `lifecycle_envelope`; `at` → `decision_envelope`
5. `kind == "annotation"` → annotation flat row
6. `type` equals a registered flat constant → that flat type (retrospective rows)
7. has `to_lane` and `wp_id` and no `event_type` → lane flat row
8. otherwise → `UNRECOGNISED_ROW`

## Error and warning codes

| Code | Meaning |
|---|---|
| `NOT_AN_OBJECT` | the line is not a JSON object (or not JSON: `NOT_JSON`) |
| `NOT_JSON` | `upcast_line` input does not parse |
| `UNRECOGNISED_ROW` | no legacy form matches |
| `UNSUPPORTED_SCHEMA_VERSION` | shape A with `schema_version` other than `3.0.0` |
| `UNREGISTERED_TYPE` | the discriminator has no registry entry for that form |
| `UNEXPECTED_MEMBER` | the row carries a member the form does not map (shape A extras; envelope extras) |
| `MISSING_MEMBER` | a member the form requires is absent or null |
| `INVALID_MEMBER` | a member has the wrong JSON type (for example a non-string id) |
| `INVALID_ID` | an id member does not normalize (not ULID or UUID) |
| `INVALID_TIME` | the time member is not RFC 3339 |
| `CLOCK_OUT_OF_RANGE` | `lamport_clock` outside 0..2147483647 |
| `MISSION_ID_CONFLICT` | row and/or context Mission ids disagree |
| `SUBJECT_CONFLICT` | the aggregate's work package and the payload `wp_id` disagree |
| `MISSION_ID_MISSING` | a mission-scoped row has no Mission id and no project fallback |

## Mapping

- Constants on every mapped row: `specversion` `1.0`, `datacontenttype` `application/json`,
  `schemaversion` `4.0.0`, `type` and `dataschema` from the registry entry.
- Shape A: design section 3, with the registry entry's `transport.lifted` members and
  `transport.lane_aliases`.
- Shape B and CLI forms: the registry entry's `lifted` members (with `projection` and
  `binding_member`), the payload member or the remaining members as `data`, constants removed.
- `source`: `/spec-kitty/missions/<missionid>` when a Mission id is known, else
  `/spec-kitty/projects/<projectuuid>` with `subject` = the aggregate (when the row has one);
  project-scoped types always take the project source.
- Absent or null optional attributes are omitted. No attribute or data member is defaulted.
- Ids normalize with `normalize_event_id` (ULID upper-case; UUID lower-case hyphenated).

## Serialization

Canonical bytes: research R-4. Goldens are canonical bytes; both bindings must reproduce
them exactly.
