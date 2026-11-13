# Grounding: #263, the optional `profile_source` key on the structured status actor

Branch `issue-263-profile-source-actor`, cut from `main` at `275e4de`. Spec Kitty CLI
`4.0.0rc9` (core `main` at `1fb3b55`), packs synced.

## Sources read

- spec-kitty-events #263 (body; no comments) and the charter (`.kittify/charter/charter.md`).
- Core #6721 could not be read through the GitHub API from this session (the repository is
  not attached). Its requirement is quoted verbatim in #263 ("accepts exactly
  `{tool, role, profile, model}` or that plus `profile_source` ∈ {`invocation`, `flag`,
  `agent_string`, `wp_frontmatter`, null}, null iff `profile` is null"). Core's own reader
  at `1fb3b55` (`src/specify_cli/status/models.py`: `ProfileSource`, `decode_actor`,
  `_validate_profile_source`) implements exactly that rule; this change mirrors it.
- spec-kitty-charter epic #549 / ADR-0075 (read from a clone of the charter repository):
  one pack-validation module on the kernel rule vocabulary. It defines no actor, agent or
  profile provenance on events; `profile_source` does not occur in that repository.
- #261 (agent and model provenance on mission events) and #264 (`recommended_action`
  meaning).

## Overlap check (stop condition)

No conflict. ADR-0075 does not define profile or agent provenance. #261 proposes `agent`
and `model` fields on the 5.0.0 mission-lifecycle payloads and explicitly leaves their
relation to the structured status actor `{tool, role, profile, model}` open; it does not
define where a profile came from. `profile_source` is additive to the status actor and
does not pre-empt #261's choice.

## Tracked issues squad

| Issue | Disposition |
|---|---|
| #261 | Related, not overlapping. It will have to say how `agent`/`model` relate to the status actor; that actor now has five possible keys. Noted on the PR; no change here. |
| #264 | Unrelated (glossary `recommended_action` semantics). No change. |

## Similar surfaces squad (every structured-actor decode/encode path)

| Surface | Binding | Disposition |
|---|---|---|
| `diary.decode_actor`, `_STRUCTURED_ACTOR_FIELDS` (used by `StatusEvent` / `InnerStateChanged` `__post_init__` and `from_dict`, so `parse_diary`, `reduce`, `reduce_parsed`, `materialize`) | Python | **Changed**: accepts the 4-key actor or the 4 keys plus `profile_source`, core's rule exactly. |
| `diary.actor_identity_str` | Python | Unchanged: projects `tool`, else `role`. |
| `status.StatusTransitionPayload.actor` and `status_transition_payload.schema.json` | Python | Open `Dict` / `additionalProperties: true` today. **Description only**: names the optional fifth key. Tightening it would refuse dict actors (`display_name`, `actor_id`) it accepts today: breaking, out of scope. |
| `StatusTransitionPayload.actor_label` | Python | Unchanged: prefers `profile`. |
| Producer contract `actorbinding` (`data/lane.transitioned.v1`, `data/wp.innerstate.changed.v1`) and the registry's lifted `actor` `legacy_schema` (lane, inner state); composed write, read and legacy schemas regenerated | Python and Java (the Java `CloudEventValidator` reads the same files) | **Changed**: optional `profile_source` with the closed vocabulary and the null-iff rule. Without it, an upcast 5-key row would be refused by both bindings' validators. |
| `cloudevents` upcaster (`_upcast._project_actor`, Java `Upcaster.projectActor`) and decoder (`_decode`) | Python and Java | Unchanged: copy the actor object whole into `data.actorbinding` and back. Covered by new upcast corpus cases that both bindings run. |
| `zeitgeist_attrs` | Python | Unchanged: projects `actor_label` only; a structured actor never rides field for field. |
| `status_log` encoder | Python | Unchanged: encodes any JSON value; no actor key check. |
| Services: `command-cli` `Actors.decodable` / `Actors.writable` (already knows the 5-key shape; `writable` refuses it while the package pin refuses it), `event-log` `RowClassifier` (4-key decode, core parity at the pinned core SHA), `domain-model` `Actor`, `contracts/mission-status` `Actor.yaml` | Java services, `service-v*` line | **Out of scope** (separate release line, ADR-0002/0004; their rule follows core at `service/core-pin.json`). Follow-up issue to lift the writer gate and the read decode once core raises its floor. |
